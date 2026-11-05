package com.aphexspin.chrome;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.view.SurfaceHolder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One live picture on one surface: the wallpaper, or the preview in settings.
 * A render thread owns the Scene; gestures and settings reach it through a
 * small queue it drains at the start of each frame, so the touch thread never
 * waits for a frame. Draws nothing while hidden and sleeps when nothing moves.
 */
final class Runner implements Runnable, SharedPreferences.OnSharedPreferenceChangeListener {

    private final Context ctx;
    private final SurfaceHolder holder;
    private final boolean wallpaper;
    private final Object lock = new Object();
    private final Object drawLock = new Object();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile boolean hasSurface;
    private int width, height;

    // ---- the queue (guarded by lock)
    private double qAx, qAy, qZoom = 1.0;
    private boolean qDragging;
    private double[] qLight;
    private double[] qFling;
    private boolean qReset;
    private boolean prefsDirty = true;
    private long releasedNs = System.nanoTime() - 10_000_000_000L;
    private long lastTouchNs = System.nanoTime();
    private String noteLabel, noteValue;
    private long noteAtNs;
    private double noteHold = 2.0, noteFade = 0.5;
    private boolean noteQuiet;

    // ---- render thread
    private Scene scene;
    private final Frame frame = new Frame();
    private Painter painter;
    private String pending;
    private boolean dragging;
    private int cols = Prefs.D_COLS, fps = Prefs.D_FPS, clockMode = Prefs.D_CLOCK, clockPos = Prefs.D_CLOCK_POS, puff = -1;
    private double speed = Prefs.D_SPIN / 100.0;
    private boolean saver, fixedTz;
    private SimpleDateFormat dateFmt, timeFmt;
    private boolean fmtFixed;
    private long fmtMinute = -1;
    private String dateText = "", timeText = "";
    private boolean locked;
    private long lockCheckNs;
    private final KeyguardManager keyguard;

    Runner(Context ctx, SurfaceHolder holder, boolean wallpaper) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.holder = holder;
        this.wallpaper = wallpaper;
        keyguard = wallpaper ? (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE) : null;
        Prefs.get(this.ctx).registerOnSharedPreferenceChangeListener(this);
    }

    void release() {
        Prefs.get(ctx).unregisterOnSharedPreferenceChangeListener(this);
        stop();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        synchronized (lock) {
            prefsDirty = true;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ surface and lifecycle
    void surface(int w, int h) {
        synchronized (lock) {
            width = w;
            height = h;
            lock.notifyAll();
        }
        hasSurface = true;
    }

    void surfaceGone() {
        hasSurface = false;
        stop();
        synchronized (drawLock) {
            // a frame in progress finishes before the surface goes
        }
    }

    void start() {
        synchronized (lock) {
            lastTouchNs = System.nanoTime();     // battery saver: the spin comes back on unlock
            if (thread != null && thread.isAlive() && running) {
                lock.notifyAll();
                return;
            }
            running = true;
            thread = new Thread(this, wallpaper ? "aphex-wallpaper" : "aphex-preview");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
    }

    void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
            lock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ input, from the touch thread
    void touchStart() {
        synchronized (lock) {
            qDragging = true;
            qFling = null;
            lastTouchNs = System.nanoTime();
            lock.notifyAll();
        }
    }

    /** Turn by (ax, ay); the sun follows the fingers at (u, v), the screen's -1..1. */
    void dragBy(double ax, double ay, double u, double v) {
        synchronized (lock) {
            qAx += ax;
            qAy += ay;
            qLight = Tracer.unit(u * 1.4, v * 1.4, 1.0);
            lastTouchNs = System.nanoTime();
            lock.notifyAll();
        }
    }

    void pinch(double factor) {
        synchronized (lock) {
            qZoom *= factor;
            lock.notifyAll();
        }
    }

    /** Let go; with a fling (rad/s about x and y) when it was still moving. */
    void touchEnd(double[] fling) {
        synchronized (lock) {
            qDragging = false;
            qFling = fling;
            releasedNs = System.nanoTime();
            lastTouchNs = releasedNs;
            lock.notifyAll();
        }
    }

    void resetView() {
        synchronized (lock) {
            qReset = true;
            lock.notifyAll();
        }
        note("back to the start", "");
    }

    /** A quiet note in its fixed place: label in ink, value in the lamp colour, gone after 2 s. */
    void note(String label, String value) {
        synchronized (lock) {
            noteLabel = label;
            noteValue = value;
            noteAtNs = System.nanoTime();
            noteHold = 2.0;
            noteFade = 0.5;
            noteQuiet = false;
            lastTouchNs = noteAtNs;
            lock.notifyAll();
        }
    }

    /** The one-time hint: muted, a while longer, then gone for good. */
    void hint(String text) {
        synchronized (lock) {
            noteLabel = text;
            noteValue = "";
            noteAtNs = System.nanoTime();
            noteHold = 12.0;
            noteFade = 2.5;
            noteQuiet = true;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ settings, on the render thread
    private void applyPrefs(boolean first) {
        SharedPreferences p = Prefs.get(ctx);
        int pf = clamp(p.getInt(Prefs.PUFF, Prefs.D_PUFF), 30, 150);
        if (pf != puff) {
            try {
                Balloon b = Assets.balloon(ctx, pf);
                if (scene == null) scene = new Scene(b); else scene.setModel(b);
                puff = pf;
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        String pal = p.getString(Prefs.PALETTE, Prefs.D_PALETTE);
        if (Palette.index(pal) == 0 && !pal.equals("aphex")) pal = "aphex";
        int fin = clamp(p.getInt(Prefs.FINISH, Prefs.D_FINISH), 0, Tracer.FINISHES.length - 1);
        if (first) {
            scene.jump(pal, fin);
            pending = null;
        } else {
            if (!pal.equals(scene.palette())) {
                if (EnvMap.ready(pal)) {
                    scene.setPalette(pal);
                    pending = null;
                } else {
                    pending = pal;
                    EnvMap.prefetch(pal);
                }
            } else {
                pending = null;
            }
            scene.setMaterial(fin);
        }
        EnvMap.prefetch(Palette.NAMES[(Palette.index(pal) + 1) % Palette.NAMES.length]);
        scene.mode = clamp(p.getInt(Prefs.MODE, Prefs.D_MODE), 0, 2);
        scene.allGlyphs = p.getBoolean(Prefs.LETTERS, Prefs.D_LETTERS);
        scene.backdrop = p.getBoolean(Prefs.SKY, Prefs.D_SKY);
        scene.stars = p.getBoolean(Prefs.STARS, Prefs.D_STARS);
        scene.groundMode = clamp(p.getInt(Prefs.GROUND, Prefs.D_GROUND), 0, 2);
        scene.contrast = clamp(p.getInt(Prefs.CONTRAST, Prefs.D_CONTRAST), 100, 400) / 100.0;
        cols = clamp(p.getInt(Prefs.COLS, Prefs.D_COLS), 20, 120);
        speed = clamp(p.getInt(Prefs.SPIN, Prefs.D_SPIN), 0, 600) / 100.0;
        fps = clamp(p.getInt(Prefs.FPS, Prefs.D_FPS), 5, 60);
        saver = p.getBoolean(Prefs.SAVER, Prefs.D_SAVER);
        clockMode = clamp(p.getInt(Prefs.CLOCK, Prefs.D_CLOCK), 0, 2);
        clockPos = clamp(p.getInt(Prefs.CLOCK_POS, Prefs.D_CLOCK_POS), 0, 1);
        fixedTz = p.getBoolean(Prefs.FIXED_TZ, Prefs.D_FIXED_TZ);
        fmtMinute = -1;
    }

    // ------------------------------------------------------------------ the loop
    @Override
    public void run() {
        final Thread me = Thread.currentThread();
        if (painter == null) painter = new Painter(Assets.font(ctx));
        prevNs = System.nanoTime();
        boolean first = scene == null;
        int failures = 0;
        while (running && thread == me) {
            try {
                if (frameOnce(me, first)) break;
                first = false;
                failures = 0;
            } catch (RuntimeException e) {
                // a bad frame is skipped, never the wallpaper: back off and try again
                android.util.Log.w("AphexChrome", "frame failed", e);
                failures++;
                synchronized (lock) {
                    try {
                        lock.wait(Math.min(5000, 100L * failures));
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
    }

    private long prevNs;

    /** One frame and the wait after it; returns true when the loop should end. */
    private boolean frameOnce(Thread me, boolean first) {
        {
            long now = System.nanoTime();
            double dt = Math.min(0.1, (now - prevNs) / 1e9);
            prevNs = now;
            boolean prefs;
            double ax, ay, zm;
            double[] lt, fling;
            boolean reset;
            long released, lastTouch;
            int w, h;
            synchronized (lock) {
                prefs = prefsDirty;
                prefsDirty = false;
                ax = qAx; ay = qAy; zm = qZoom;
                qAx = qAy = 0; qZoom = 1.0;
                lt = qLight; qLight = null;
                fling = qFling; qFling = null;
                reset = qReset; qReset = false;
                dragging = qDragging;
                released = releasedNs;
                lastTouch = lastTouchNs;
                w = width;
                h = height;
            }
            if (prefs || scene == null) applyPrefs(first || scene == null);
            if (pending != null && EnvMap.ready(pending)) {
                scene.setPalette(pending);
                pending = null;
            }
            if (reset) {
                scene.reset();
                scene.zoom = 1.0;
            }
            if (ax != 0 || ay != 0) {
                scene.dragBy(ax, ay);
                scene.spin[0] = scene.spin[1] = 0;
            }
            if (zm != 1.0) scene.zoom = Math.min(2.5, Math.max(0.5, scene.zoom * zm));
            if (lt != null) scene.lightTarget = lt;
            if (fling != null) {
                scene.spin[0] = Math.max(-9, Math.min(9, fling[0]));
                scene.spin[1] = Math.max(-9, Math.min(9, fling[1]));
            }
            double since = (now - released) / 1e9;
            double idle = (now - lastTouch) / 1e9;
            double sp = speed;
            boolean resting = saver && idle > 33.0;
            if (saver) sp *= Math.min(1.0, Math.max(0.0, 1.0 - (idle - 30.0) / 3.0));
            if (!dragging && since > 1.5) scene.lightTarget = Scene.DEFAULT_LIGHT.clone();
            scene.step(dt, dragging, since, sp);
            // momentum this slow is invisible; stop it rather than spend frames on it
            if (Math.abs(scene.spin[0]) < 0.01 && Math.abs(scene.spin[1]) < 0.01) scene.spin[0] = scene.spin[1] = 0;

            boolean noteOn = drawFrame(w, h, now);

            boolean moving = dragging || Math.abs(scene.spin[0]) > 1e-3 || Math.abs(scene.spin[1]) > 1e-3
                || sp > 0 || scene.fading() || !scene.lightSettled() || noteOn || pending != null;
            long waitMs;
            long spent = (System.nanoTime() - now) / 1_000_000L;
            if (moving) {
                // while a finger is on it (or it is flung) it runs at 45 fps at least: touch feels direct
                boolean touched = dragging || Math.abs(scene.spin[0]) > 0.3 || Math.abs(scene.spin[1]) > 0.3;
                int rate = touched ? Math.max(fps, 45) : fps;
                waitMs = Math.max(1, 1000L / Math.max(1, rate) - spent);
            } else if (scene.sparkling() && !resting) {
                waitMs = Math.max(1, 1000L / Math.min(12, fps) - spent);     // the sparkles keep twinkling
            } else if (wallpaper && clockMode == 1) {
                waitMs = 1000;                                                // watch for lock and unlock
            } else if (wallpaper && clockMode == 0) {
                waitMs = 60_000 - (System.currentTimeMillis() % 60_000) + 20; // the next minute
            } else {
                waitMs = 0;                                                   // until something happens
            }
            synchronized (lock) {
                if (!running || thread != me) return true;
                if (prefsDirty || qAx != 0 || qAy != 0 || qReset || qFling != null) return false;
                try {
                    lock.wait(waitMs);
                } catch (InterruptedException e) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Renders and draws one frame; returns whether a note is still on screen. */
    private boolean drawFrame(int w, int h, long now) {
        if (w <= 0 || h <= 0 || !hasSurface) return false;
        int rows = painter.layout(w, h, cols);
        scene.render(frame, cols, rows);
        String nl, nv;
        long nAt;
        double hold, fade;
        boolean quiet;
        synchronized (lock) {
            nl = noteLabel; nv = noteValue; nAt = noteAtNs; hold = noteHold; fade = noteFade; quiet = noteQuiet;
        }
        double age = (now - nAt) / 1e9;
        boolean noteOn = nl != null && age < hold + fade;
        synchronized (drawLock) {
            if (!hasSurface) return noteOn;
            Canvas c = null;
            try {
                try {
                    c = holder.lockHardwareCanvas();
                } catch (RuntimeException e) {
                    c = null;
                }
                if (c == null) c = holder.lockCanvas();
                if (c == null) return noteOn;
                if (wallpaper) {
                    float size = painter.sizeFor("wed 00/00/0000 · 00:00", w * 0.58f);
                    float clockY = clockPos == 0 ? h * 0.075f + size : h * 0.86f;
                    float noteY = clockPos == 0 ? clockY + size * 1.9f : clockY - size * 1.9f;
                    // the rows of the clock and the notes are reserved: the sky keeps out of them
                    float bandTop = Math.min(clockY, noteY) - size * 1.4f;
                    float bandBottom = Math.max(clockY, noteY) + size * 0.7f;
                    painter.draw(c, frame, bandTop, bandBottom);
                    if (showClock()) drawClock(c, w, clockY, size);
                    if (noteOn) drawNote(c, w, clockMode == 2 && clockPos == 0 ? clockY : noteY, size * 0.85f,
                        nl, nv, age, hold, fade, quiet);
                } else {
                    painter.draw(c, frame, 0f, -1f);
                }
            } catch (RuntimeException ignored) {
                // the surface went away mid-frame
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return noteOn;
    }

    private boolean showClock() {
        if (clockMode == 2) return false;
        if (clockMode == 0) return true;
        long now = System.nanoTime();
        if (now - lockCheckNs > 500_000_000L) {
            lockCheckNs = now;
            locked = keyguard != null && keyguard.isKeyguardLocked();
        }
        return locked;
    }

    private void drawClock(Canvas c, int w, float y, float size) {
        if (dateFmt == null || fmtFixed != fixedTz) {
            dateFmt = new SimpleDateFormat("EEE dd/MM/yyyy", Locale.ENGLISH);
            timeFmt = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
            fmtFixed = fixedTz;
            fmtMinute = -1;
        }
        long ms = System.currentTimeMillis();
        if (ms / 60_000 != fmtMinute) {
            fmtMinute = ms / 60_000;
            TimeZone tz = fixedTz ? TimeZone.getTimeZone("GMT+01:00") : TimeZone.getDefault();
            dateFmt.setTimeZone(tz);
            timeFmt.setTimeZone(tz);
            Date d = new Date(ms);
            dateText = dateFmt.format(d).toLowerCase(Locale.ENGLISH);
            timeText = timeFmt.format(d);
        }
        painter.line(c, new String[]{dateText, " · ", timeText}, new int[]{frame.muted, frame.faint, frame.ink},
            w / 2f, y, size, w * 0.9f);
    }

    private void drawNote(Canvas c, int w, float y, float size, String label, String value, double age,
                          double hold, double fade, boolean quiet) {
        float nf = (float) Math.min(1.0, Math.max(0.0, (age - hold) / fade));
        int g = frame.ground;
        if (quiet) {
            painter.line(c, new String[]{label}, new int[]{Painter.mix(frame.muted, g, nf)}, w / 2f, y, size * 0.8f, w * 0.92f);
            return;
        }
        if (value == null || value.isEmpty()) {
            painter.line(c, new String[]{label}, new int[]{Painter.mix(frame.ink, g, nf)}, w / 2f, y, size, w * 0.9f);
        } else {
            painter.line(c, new String[]{label + " · ", value},
                new int[]{Painter.mix(frame.ink, g, nf), Painter.mix(frame.accent, g, nf)}, w / 2f, y, size, w * 0.9f);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
