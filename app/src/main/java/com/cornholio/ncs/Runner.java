package com.cornholio.ncs;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.SurfaceHolder;

import java.util.Arrays;
import java.util.List;

/**
 * One live picture on one surface: the wallpaper, the system's preview of it,
 * or the preview in settings. A render thread owns the scene; the listener
 * feeds the tap from its own thread; fingers only paint the field's wake.
 * Nothing is drawn and nothing is heard while it is hidden, and in silence it
 * slows to a dozen frames a second.
 */
final class Runner implements Runnable, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final long T0 = System.nanoTime();
    static final AudioTap.Clock CLOCK = () -> (System.nanoTime() - T0) / 1e9 + 1000.0;

    private final Context ctx;
    private final SurfaceHolder holder;
    private final boolean notes, demoOnly;
    private final Object lock = new Object();
    private final Object drawLock = new Object();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile boolean hasSurface;
    private int width, height;
    private boolean prefsDirty = true;
    private boolean woken;                                     // a finger, a note or a new surface (guarded by lock)

    // the field's wake: a ribbon per finger, fading on after the finger lifts
    private final Object trailLock = new Object();
    private final java.util.ArrayList<Trail> trails = new java.util.ArrayList<>();
    private final Trail[] byFinger = new Trail[2];
    private volatile float cellW = 10, cellH = 20, yOff;

    // the note in its fixed place (guarded by lock)
    private String noteLabel, noteValue;
    private double noteAt = -100, noteHold = 2.0, noteFade = 0.5;
    private boolean noteQuiet;

    // render thread only
    private Theme C;
    private AudioTap tap;
    private Ncs ncs;
    private Listener listener;
    private double nextTry;
    private Cells cv;
    private double[][] mask;
    private Painter painter;
    private int listen = -1, loudness = -1, visual, reach, cols = Prefs.D_COLS, fps = Prefs.D_FPS;
    private boolean flow = true, song;
    private String title = "";
    private double titleAt = -100, prevNow, quietSince = -1;
    private boolean saidDeaf;

    /**
     * notes: show the quiet notes (the wallpaper and the system's preview of it).
     * demoOnly: play cornholio's demo song instead of listening (both previews).
     */
    Runner(Context ctx, SurfaceHolder holder, boolean notes, boolean demoOnly) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.holder = holder;
        this.notes = notes;
        this.demoOnly = demoOnly;
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

    // ------------------------------------------------------------------ lifecycle
    void surface(int w, int h) {
        synchronized (lock) {
            width = w;
            height = h;
            woken = true;
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
            saidDeaf = false;
            nextTry = 0;
            if (thread != null && thread.isAlive() && running) {
                lock.notifyAll();
                return;
            }
            running = true;
            thread = new Thread(this, notes ? "ncs-wallpaper" : "ncs-preview");
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

    // ------------------------------------------------------------------ input
    /** Finger `id` touches down at (px, py): a new ribbon starts there, so it never jumps from an old one. */
    void paintStart(int id, float px, float py) {
        if (id < 0 || id >= byFinger.length) return;
        synchronized (trailLock) {
            Trail t = new Trail();
            byFinger[id] = t;
            trails.add(t);
            while (trails.size() > 6) trails.remove(0);
        }
        paint(id, px, py);
    }

    /** Finger `id` at (px, py) on the surface: the field's wake follows it. It paints, it never controls. */
    void paint(int id, float px, float py) {
        if (id < 0 || id >= byFinger.length) return;
        Trail t;
        synchronized (trailLock) {
            t = byFinger[id];
        }
        if (t == null) return;
        int x = (int) Math.floor(px / cellW), y = (int) Math.floor((py - yOff) / cellH);
        t.feed(x, y, CLOCK.now());
        synchronized (lock) {
            woken = true;
            lock.notifyAll();
        }
    }

    /** Finger `id` lifts (-1: all of them); its ribbon fades on its own. */
    void paintEnd(int id) {
        synchronized (trailLock) {
            for (int i = 0; i < byFinger.length; i++) if (id < 0 || i == id) byFinger[i] = null;
        }
    }

    private boolean painting() {
        synchronized (trailLock) {
            for (Trail t : trails) if (t.alive()) return true;
        }
        return false;
    }

    /** This frame's wake from every ribbon (the strongest wins), or null. */
    private double[] wake(double now, int w, int h) {
        Trail[] list;
        synchronized (trailLock) {
            list = trails.toArray(new Trail[0]);
        }
        int lvl = Trail.level(w, h);
        double[] out = null;
        for (Trail t : list) {
            double[] g = t.grid(now, lvl, w, h);
            if (g == null) {
                synchronized (trailLock) {
                    if (byFinger[0] != t && byFinger[1] != t) trails.remove(t);
                }
                continue;
            }
            if (out == null) out = g;
            else for (int i = 0; i < out.length; i++) if (g[i] > out[i]) out[i] = g[i];
        }
        return out;
    }

    /** A quiet note: label in ink, value in the lamp colour, gone after `hold` seconds (2, or 4 for warnings). */
    void note(String label, String value, double hold) {
        synchronized (lock) {
            noteLabel = label;
            noteValue = value;
            noteAt = CLOCK.now();
            noteHold = hold;
            noteFade = 0.5;
            noteQuiet = false;
            woken = true;
            lock.notifyAll();
        }
    }

    /** The one-time hint, in the faint voice, fading slowly. */
    void hint(String text) {
        synchronized (lock) {
            noteLabel = text;
            noteValue = "";
            noteAt = CLOCK.now();
            noteHold = 10.0;
            noteFade = 2.5;
            noteQuiet = true;
            woken = true;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ settings
    private void applyPrefs(double now) {
        SharedPreferences p = Prefs.get(ctx);
        String th = Theme.NAMES[Theme.index(p.getString(Prefs.THEME, Prefs.D_THEME))];
        if (C == null) C = new Theme(th);
        else if (!th.equals(C.name)) C.switchTo(th, now);
        visual = clamp(p.getInt(Prefs.VISUAL, Prefs.D_VISUAL), 0, Ncs.VISUALS.length - 1);
        reach = clamp(p.getInt(Prefs.REACH, Prefs.D_REACH), 0, Prefs.REACHES.length - 1);
        flow = p.getBoolean(Prefs.FLOW, Prefs.D_FLOW);
        song = p.getBoolean(Prefs.SONG, Prefs.D_SONG) && !demoOnly;
        cols = clamp(p.getInt(Prefs.COLS, Prefs.D_COLS), 24, 120);
        fps = clamp(p.getInt(Prefs.FPS, Prefs.D_FPS), 5, 60);
        int li = demoOnly ? 1 : clamp(p.getInt(Prefs.LISTEN, Prefs.D_LISTEN), 0, 1);
        int lo = clamp(p.getInt(Prefs.LOUDNESS, Prefs.D_LOUDNESS), 0, 1);
        if (li != listen || lo != loudness || tap == null) {
            listen = li;
            loudness = lo;
            stopListening();
            tap = new AudioTap(listen == 1, CLOCK);
            ncs = new Ncs(tap);
            saidDeaf = false;
            nextTry = 0;
        }
        if (!song) title = "";
    }

    private boolean canHear() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Listening starts when it may; asking again at most once a second (every five after a failure). */
    private void startListening(double now) {
        if (listener != null || tap == null || tap.demo || now < nextTry) return;
        if (!canHear()) {
            nextTry = now + 1.0;
            if (!saidDeaf && notes) {
                saidDeaf = true;
                note("can't hear the music", "open cornholio ncs to let it listen", 4.0);
            }
            return;
        }
        Listener l = new Listener(tap, CLOCK, loudness == 1);
        if (l.start()) {
            listener = l;
        } else {
            nextTry = now + 5.0;
            if (!saidDeaf && notes) {
                saidDeaf = true;
                note(l.error, "", 4.0);
            }
        }
    }

    private void stopListening() {
        Listener l = listener;
        listener = null;
        if (l != null) l.stop();
    }

    /** The playing song's title, when the song letters are on (it needs notification access). */
    private String pollTitle() {
        try {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (m == null) return "";
            List<MediaController> list = m.getActiveSessions(new ComponentName(ctx, SongListener.class));
            MediaController best = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
            if (best != null) {
                MediaMetadata md = best.getMetadata();
                if (md != null) {
                    String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    return t == null ? "" : t;
                }
            }
        } catch (RuntimeException e) {
            // no notification access: the field keeps cornholio's own letters
        }
        return "";
    }

    // ------------------------------------------------------------------ the loop
    @Override
    public void run() {
        final Thread me = Thread.currentThread();
        if (painter == null) painter = new Painter(Assets.font(ctx), Assets.bold(ctx));
        prevNow = CLOCK.now();
        int failures = 0;
        try {
            while (running && thread == me) {
                try {
                    if (frameOnce(me)) break;
                    failures = 0;
                } catch (RuntimeException e) {
                    android.util.Log.w("CornholioNCS", "frame failed", e);
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
        } finally {
            stopListening();
        }
    }

    private boolean frameOnce(Thread me) {
        double now = CLOCK.now();
        double dt = Math.min(0.1, Math.max(0.0, now - prevNow));
        prevNow = now;
        boolean prefs;
        int w, h;
        synchronized (lock) {
            prefs = prefsDirty;
            prefsDirty = false;
            w = width;
            h = height;
        }
        if (prefs || tap == null) applyPrefs(now);
        startListening(now);
        if (song && now - titleAt > 1.5) {
            titleAt = now;
            title = pollTitle();
        }
        tap.tick(now, true, dt);
        C.update(now);
        boolean drew = drawFrame(w, h, now, dt);

        AudioTap.Fr fr = tap.frame();
        boolean painting = painting();
        boolean noteOn;
        synchronized (lock) {
            noteOn = noteLabel != null && now - noteAt < noteHold + noteFade;
        }
        boolean quiet = fr.silent && !painting && !noteOn && !C.fading(now);
        if (!quiet) quietSince = -1;
        else if (quietSince < 0) quietSince = now;
        int rate = fps;
        if (quietSince >= 0 && now - quietSince > 3.0) {
            // the flow keeps drifting slowly; with it off, nothing moves until the music does
            boolean drifts = flow || visual == 3 || visual == 4;
            rate = Math.min(fps, drifts ? 12 : 5);
        }
        // the next frame is due a frame's time after this one began; resting, a finger or a note wakes it at once
        double due = now + (drew ? 1.0 / rate : 0.2);
        boolean resting = rate < fps;
        synchronized (lock) {
            if (!resting) woken = false;
            while (running && thread == me && !prefsDirty && !(resting && woken)) {
                long left = (long) Math.ceil((due - CLOCK.now()) * 1000);
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    return true;
                }
            }
            woken = false;
            if (!running || thread != me) return true;
        }
        return false;
    }

    private boolean drawFrame(int w, int h, double now, double dt) {
        if (w <= 0 || h <= 0 || !hasSurface) return false;
        int rows = painter.layout(w, h, cols);
        cellW = painter.cellW;
        cellH = painter.cellH;
        yOff = painter.yOff;
        if (cv == null || cv.w != cols || cv.h != rows) {
            cv = new Cells(cols, rows, C.bg);
            mask = new double[rows][cols];
        }

        String nl, nv;
        double nAt, hold, fade;
        boolean quiet;
        synchronized (lock) {
            nl = noteLabel;
            nv = noteValue;
            nAt = noteAt;
            hold = noteHold;
            fade = noteFade;
            quiet = noteQuiet;
        }
        double age = now - nAt;
        boolean noteOn = notes && nl != null && age < hold + fade;
        double alpha = noteOn ? 1.0 - Math.min(1.0, Math.max(0.0, (age - hold) / fade)) : 0.0;
        float size = cellW * (quiet ? 1.0f : 1.15f);
        float baseY = h * 0.09f + size;

        // the field is quiet where the words are, as in cornholio
        for (double[] r : mask) Arrays.fill(r, 1.0);
        if (alpha > 0) {
            int r0 = (int) Math.floor((baseY - size - yOff) / cellH), r1 = (int) Math.floor((baseY + size * 0.35f - yOff) / cellH);
            double q = 1.0 - 0.85 * alpha;
            for (int y = Math.max(0, r0); y <= Math.min(rows - 1, r1); y++) Arrays.fill(mask[y], q);
        }

        int top = Math.max(2, (int) Math.round(rows * Prefs.REACH_TOP[reach]));
        int low = top - 2;
        int visTop = reach == Prefs.REACHES.length - 1 ? 0 : top;
        String vis = Ncs.VISUALS[visual];
        double[] wake = wake(now, cols, rows);                 // computed always: it also lets old ribbons go
        if (vis.equals("lorn") || vis.equals("diffision")) wake = null;
        cv.reset(C.bg);
        ncs.flow = flow;
        ncs.draw(cv, C, now, dt, 0.0, true, true, title, low, visTop, wake, mask, null, vis);

        synchronized (drawLock) {
            if (!hasSurface) return false;
            Canvas c = null;
            try {
                try {
                    c = holder.lockHardwareCanvas();
                } catch (RuntimeException e) {
                    c = null;
                }
                if (c == null) c = holder.lockCanvas();
                if (c == null) return false;
                painter.draw(c, cv, C.bg);
                if (alpha > 0) {
                    double nf = 1.0 - alpha;
                    if (quiet) {
                        painter.line(c, new String[]{nl}, new int[]{Theme.mix(C.dim, C.bg, nf)}, w / 2f, baseY, size, w * 0.92f);
                    } else if (nv == null || nv.isEmpty()) {
                        painter.line(c, new String[]{nl}, new int[]{Theme.mix(C.fg, C.bg, nf)}, w / 2f, baseY, size, w * 0.9f);
                    } else {
                        painter.line(c, new String[]{nl + " · ", nv},
                            new int[]{Theme.mix(C.fg, C.bg, nf), Theme.mix(C.accent, C.bg, nf)}, w / 2f, baseY, size, w * 0.9f);
                    }
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
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
