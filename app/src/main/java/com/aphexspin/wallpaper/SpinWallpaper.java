package com.aphexspin.wallpaper;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The live wallpaper. One render thread per engine, running only while the
 * wallpaper is visible. All gestures are two- or three-finger, so the
 * launcher's one-finger swipes (pages, app drawer) never move the model.
 *
 *   two-finger drag   spin it (let go mid-swing and it keeps the momentum)
 *   pinch             zoom
 *   two-finger tap    next material
 *   three-finger tap  reset view
 */
public class SpinWallpaper extends WallpaperService {

    private static Typeface font;

    @Override
    public Engine onCreateEngine() {
        if (font == null) {
            try {
                font = Typeface.createFromAsset(getAssets(), "DejaVuSansMono.ttf");
            } catch (RuntimeException e) {
                font = Typeface.MONOSPACE;
            }
        }
        return new SpinEngine();
    }

    private final class SpinEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener, Runnable {

        private final Object lock = new Object();
        private final Object drawLock = new Object();
        private volatile Thread thread;
        private volatile boolean running;
        private volatile boolean visible;
        private volatile boolean hasSurface;
        private int width, height;

        // settings (read by the render thread)
        private volatile int material, mode, cols, fps, clockMode, clockPos;
        private volatile double speed, contrast;
        private volatile boolean saver, fixedTz;

        // view state, guarded by lock
        private double[][] R = AsciiRenderer.initialRotation();
        private final double[] spin = new double[2];
        private double zoom = 1.0;
        private double[] light = AsciiRenderer.DEFAULT_LIGHT.clone();
        private double[] lightTarget = AsciiRenderer.DEFAULT_LIGHT.clone();
        private boolean touching;
        private long releasedNs = System.nanoTime() - 10_000_000_000L;
        private long lastTouchNs = System.nanoTime();

        // gesture state, guarded by lock
        private boolean twoActive, gMoved;
        private int gMaxPtr;
        private long gStart, lastMoveMs;
        private float lastCx, lastCy, lastDist, startCx, startCy, startDist;
        private final double[] vel = new double[2];

        // drawing
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rectPaint = new Paint();
        private SimpleDateFormat clockFmt;
        private boolean clockFixedTz;
        private long clockMinute = -1;
        private String clockText = "";
        private boolean locked;
        private long lockCheckNs;
        private KeyguardManager keyguard;
        private float slop;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            slop = 14f * getResources().getDisplayMetrics().density;
            glyphPaint.setTypeface(font);
            clockPaint.setTypeface(font);
            SharedPreferences p = Prefs.get(SpinWallpaper.this);
            p.registerOnSharedPreferenceChangeListener(this);
            loadPrefs(p);
        }

        @Override
        public void onDestroy() {
            Prefs.get(SpinWallpaper.this).unregisterOnSharedPreferenceChangeListener(this);
            stopThread();
            super.onDestroy();
        }

        private void loadPrefs(SharedPreferences p) {
            material = clamp(p.getInt(Prefs.MATERIAL, Prefs.D_MATERIAL), 0, AsciiRenderer.MATERIALS.length - 1);
            mode = clamp(p.getInt(Prefs.MODE, Prefs.D_MODE), 0, AsciiRenderer.MODES.length - 1);
            cols = clamp(p.getInt(Prefs.COLS, Prefs.D_COLS), 20, 120);
            fps = clamp(p.getInt(Prefs.FPS, Prefs.D_FPS), 5, 60);
            speed = p.getInt(Prefs.SPEED, Prefs.D_SPEED) / 100.0;
            contrast = p.getInt(Prefs.CONTRAST, Prefs.D_CONTRAST) / 100.0;
            saver = p.getBoolean(Prefs.SAVER, Prefs.D_SAVER);
            clockMode = p.getInt(Prefs.CLOCK, Prefs.D_CLOCK);
            clockPos = p.getInt(Prefs.CLOCK_POS, Prefs.D_CLOCK_POS);
            fixedTz = p.getBoolean(Prefs.FIXED_TZ, Prefs.D_FIXED_TZ);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            loadPrefs(p);
            clockMinute = -1;
            wake();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            synchronized (lock) {
                width = w;
                height = h;
            }
            hasSurface = true;
            if (visible) startThread(); else wake();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            hasSurface = false;
            stopThread();
            synchronized (drawLock) {
                // wait for a frame in progress to finish before the surface goes
            }
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                synchronized (lock) {
                    lastTouchNs = System.nanoTime();   // battery saver: spin again after unlock
                }
                lockCheckNs = 0;
                if (hasSurface) startThread();
            } else {
                stopThread();
            }
        }

        private void startThread() {
            synchronized (lock) {
                if (thread != null && thread.isAlive() && running) {
                    lock.notifyAll();
                    return;
                }
                running = true;
                thread = new Thread(this, "aphex-spin");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.start();
            }
        }

        private void stopThread() {
            Thread t;
            synchronized (lock) {
                running = false;
                t = thread;
                thread = null;
                lock.notifyAll();
            }
            if (t != null && t != Thread.currentThread()) {
                try {
                    t.join(600);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void wake() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        // ------------------------------------------------------------------ render loop
        @Override
        public void run() {
            final Thread me = Thread.currentThread();
            final AsciiRenderer renderer = new AsciiRenderer();   // first one builds the model (~0.5 s)
            long prev = System.nanoTime();
            while (running && thread == me) {
                long now = System.nanoTime();
                double dt = Math.min(0.1, (now - prev) / 1e9);
                prev = now;
                boolean animating;
                synchronized (lock) {
                    animating = step(now, dt);
                    renderer.R = R;
                    renderer.light = light.clone();
                    renderer.zoom = zoom;
                }
                renderer.material = material;
                renderer.mode = mode;
                renderer.contrast = contrast;
                drawFrame(renderer);

                long frameNs = 1_000_000_000L / Math.max(1, fps);
                long spent = System.nanoTime() - now;
                long waitMs;
                if (animating) {
                    waitMs = Math.max(1, (frameNs - spent) / 1_000_000L);
                } else if (clockMode == 1) {
                    waitMs = 1000;                              // watch for lock / unlock
                } else if (clockMode == 0) {
                    long ms = System.currentTimeMillis();
                    waitMs = 60_000 - (ms % 60_000) + 20;      // next minute
                } else {
                    waitMs = 0;                                 // sleep until something happens
                }
                synchronized (lock) {
                    if (!running || thread != me) break;
                    try {
                        lock.wait(waitMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }

        /** Advances motion; returns true while anything is moving. Holds lock. */
        private boolean step(long now, double dt) {
            boolean moving = touching;
            if (!touching) {
                if (Math.abs(spin[0]) > 1e-3 || Math.abs(spin[1]) > 1e-3) {
                    R = AsciiRenderer.orthonormal(AsciiRenderer.mul(
                            AsciiRenderer.mul(AsciiRenderer.rotX(spin[0] * dt), AsciiRenderer.rotY(spin[1] * dt)), R));
                    double k = Math.exp(-dt / 1.3);
                    spin[0] *= k;
                    spin[1] *= k;
                    moving = true;
                }
                double sp = speed;
                if (saver) {
                    double idle = (now - lastTouchNs) / 1e9;
                    sp *= Math.min(1.0, Math.max(0.0, 1.0 - (idle - 30.0) / 3.0));
                }
                double since = (now - releasedNs) / 1e9;
                double blend = Math.min(1.0, Math.max(0.0, (since - 0.6) / 1.8));
                if (sp * blend > 0) {
                    R = AsciiRenderer.orthonormal(AsciiRenderer.mul(AsciiRenderer.rotY(sp * dt * blend), R));
                    moving = true;
                } else if (sp > 0) {
                    moving = true;   // still blending back in
                }
                if (since > 1.5) lightTarget = AsciiRenderer.DEFAULT_LIGHT.clone();
            }
            double k = 1 - Math.exp(-dt / 0.12);
            double lx = light[0] + (lightTarget[0] - light[0]) * k;
            double ly = light[1] + (lightTarget[1] - light[1]) * k;
            double lz = light[2] + (lightTarget[2] - light[2]) * k;
            light = AsciiRenderer.unit(lx, ly, lz);
            double dl = Math.abs(light[0] - lightTarget[0]) + Math.abs(light[1] - lightTarget[1])
                    + Math.abs(light[2] - lightTarget[2]);
            return moving || dl > 1e-3;
        }

        private void drawFrame(AsciiRenderer renderer) {
            int w, h;
            synchronized (lock) {
                w = width;
                h = height;
            }
            if (w <= 0 || h <= 0 || !hasSurface) return;
            float cellW = (float) w / cols;
            float cellH = cellW * 2f;
            int rows = Math.max(4, (int) (h / cellH));
            float yOff = (h - rows * cellH) / 2f;
            renderer.render(cols, rows);

            synchronized (drawLock) {
                if (!hasSurface) return;
                SurfaceHolder holder = getSurfaceHolder();
                Canvas c = null;
                try {
                    try {
                        c = holder.lockHardwareCanvas();
                    } catch (RuntimeException e) {
                        c = null;
                    }
                    if (c == null) c = holder.lockCanvas();
                    if (c == null) return;
                    c.drawColor(0xFF000000);
                    if (renderer.mode == 2) drawBlocks(renderer, c, cellW, cellH, yOff);
                    else drawGlyphs(renderer, c, cellW, cellH, yOff);
                    drawClock(c, w, h);
                } catch (RuntimeException ignored) {
                    // surface went away mid-frame
                } finally {
                    if (c != null) {
                        try {
                            holder.unlockCanvasAndPost(c);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        }

        private void drawGlyphs(AsciiRenderer renderer, Canvas c, float cellW, float cellH, float yOff) {
            glyphPaint.setTextSize(100f);
            float adv = glyphPaint.measureText("M");
            glyphPaint.setTextSize(100f * cellW / adv);
            Paint.FontMetrics fm = glyphPaint.getFontMetrics();
            float base = (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            char[] ch = renderer.chars;
            int[] fg = renderer.fg;
            int cols = renderer.cols, rows = renderer.rows;
            int last = 0;
            for (int r = 0; r < rows; r++) {
                float y = yOff + r * cellH + base;
                for (int x = 0; x < cols; x++) {
                    int i = r * cols + x;
                    int f = fg[i];
                    if (f < 0) continue;
                    if (f != last) {
                        glyphPaint.setColor(0xFF000000 | f);
                        last = f;
                    }
                    c.drawText(ch, i, 1, x * cellW, y, glyphPaint);
                }
            }
        }

        private void drawBlocks(AsciiRenderer renderer, Canvas c, float cellW, float cellH, float yOff) {
            int[] top = renderer.bgTop, bot = renderer.bgBot;
            int cols = renderer.cols, rows = renderer.rows;
            float half = cellH / 2f;
            for (int r = 0; r < rows; r++) {
                float y = yOff + r * cellH;
                for (int x = 0; x < cols; x++) {
                    int i = r * cols + x;
                    float x0 = x * cellW;
                    if (top[i] != 0) {
                        rectPaint.setColor(0xFF000000 | top[i]);
                        c.drawRect(x0, y, x0 + cellW + 0.5f, y + half + 0.5f, rectPaint);
                    }
                    if (bot[i] != 0) {
                        rectPaint.setColor(0xFF000000 | bot[i]);
                        c.drawRect(x0, y + half, x0 + cellW + 0.5f, y + cellH + 0.5f, rectPaint);
                    }
                }
            }
        }

        private void drawClock(Canvas c, int w, int h) {
            int cm = clockMode;
            if (cm == 2) return;
            if (cm == 1) {
                long now = System.nanoTime();
                if (now - lockCheckNs > 500_000_000L) {
                    lockCheckNs = now;
                    locked = keyguard != null && keyguard.isKeyguardLocked();
                }
                if (!locked) return;
            }
            boolean fixed = fixedTz;
            if (clockFmt == null || clockFixedTz != fixed) {
                clockFmt = new SimpleDateFormat("EEE dd/MM/yyyy  HH:mm", Locale.ENGLISH);
                clockFixedTz = fixed;
                clockMinute = -1;
            }
            long ms = System.currentTimeMillis();
            if (ms / 60_000 != clockMinute) {
                clockMinute = ms / 60_000;
                clockFmt.setTimeZone(fixed ? TimeZone.getTimeZone("GMT+01:00") : TimeZone.getDefault());
                clockText = clockFmt.format(new Date(ms)).toUpperCase(Locale.ENGLISH);
            }
            clockPaint.setTextSize(100f);
            float tw = clockPaint.measureText(clockText);
            clockPaint.setTextSize(100f * (w * 0.58f) / tw);
            clockPaint.setColor(0xCCB8A8F0);
            float x = (w - clockPaint.measureText(clockText)) / 2f;
            float y = clockPos == 0 ? h * 0.075f + clockPaint.getTextSize() : h * 0.86f;
            c.drawText(clockText, x, y, clockPaint);
        }

        // ------------------------------------------------------------------ gestures
        @Override
        public void onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            int n = e.getPointerCount();
            long t = e.getEventTime();
            synchronized (lock) {
                switch (act) {
                    case MotionEvent.ACTION_DOWN:
                        gStart = t;
                        gMaxPtr = 1;
                        gMoved = false;
                        twoActive = false;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        gMaxPtr = Math.max(gMaxPtr, n);
                        startCx = centroidX(e);
                        startCy = centroidY(e);
                        startDist = n >= 2 ? spread(e) : 0;
                        if (n == 2) {
                            twoActive = true;
                            touching = true;
                            lastCx = startCx;
                            lastCy = startCy;
                            lastDist = startDist;
                            lastMoveMs = t;
                            vel[0] = vel[1] = 0;
                            spin[0] = spin[1] = 0;
                            lastTouchNs = System.nanoTime();
                        } else {
                            twoActive = false;         // three fingers: hold still
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (n >= 2) {
                            float cx = centroidX(e), cy = centroidY(e);
                            if (Math.hypot(cx - startCx, cy - startCy) > slop
                                    || (n == 2 && Math.abs(spread(e) - startDist) > slop)) gMoved = true;
                        }
                        if (twoActive && n == 2) moveTwo(e, t);
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        if (twoActive && n == 2) endTwo(t);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (twoActive) endTwo(t);
                        touching = false;
                        if (act == MotionEvent.ACTION_UP && !gMoved) {
                            long dur = t - gStart;
                            if (gMaxPtr == 2 && dur < 350) {
                                int next = (material + 1) % AsciiRenderer.MATERIALS.length;
                                Prefs.get(SpinWallpaper.this).edit().putInt(Prefs.MATERIAL, next).apply();
                                material = next;
                            } else if (gMaxPtr == 3 && dur < 600) {
                                R = AsciiRenderer.initialRotation();
                                spin[0] = spin[1] = 0;
                                zoom = 1.0;
                                lightTarget = AsciiRenderer.DEFAULT_LIGHT.clone();
                            }
                        }
                        break;
                    default:
                        break;
                }
                lock.notifyAll();
            }
            super.onTouchEvent(e);
        }

        private void moveTwo(MotionEvent e, long t) {
            float cx = centroidX(e), cy = centroidY(e), d = spread(e);
            float w = Math.max(1, width);
            double k = 3.2 / w;
            double ax = (cy - lastCy) * k;    // vertical drag tilts
            double ay = (cx - lastCx) * k;    // horizontal drag turns
            R = AsciiRenderer.orthonormal(AsciiRenderer.mul(
                    AsciiRenderer.mul(AsciiRenderer.rotX(ax), AsciiRenderer.rotY(ay)), R));
            double et = Math.max(1 / 120.0, (t - lastMoveMs) / 1000.0);
            vel[0] = 0.55 * vel[0] + 0.45 * ax / et;
            vel[1] = 0.55 * vel[1] + 0.45 * ay / et;
            if (lastDist > slop && d > slop) {
                zoom = Math.min(2.5, Math.max(0.5, zoom * d / lastDist));
            }
            double u = cx / w * 2.0 - 1.0;
            double v = 1.0 - cy / Math.max(1, height) * 2.0;
            lightTarget = AsciiRenderer.unit(u * 1.4, v * 1.4, 1.0);
            lastCx = cx;
            lastCy = cy;
            lastDist = d;
            lastMoveMs = t;
            lastTouchNs = System.nanoTime();
        }

        private void endTwo(long t) {
            if (t - lastMoveMs < 80) {
                spin[0] = Math.max(-9, Math.min(9, vel[0]));
                spin[1] = Math.max(-9, Math.min(9, vel[1]));
            }
            twoActive = false;
            touching = false;
            releasedNs = System.nanoTime();
            lastTouchNs = releasedNs;
        }
    }

    private static float centroidX(MotionEvent e) {
        float s = 0;
        int n = Math.min(e.getPointerCount(), 2);
        for (int i = 0; i < n; i++) s += e.getX(i);
        return s / n;
    }

    private static float centroidY(MotionEvent e) {
        float s = 0;
        int n = Math.min(e.getPointerCount(), 2);
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }

    private static float spread(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
