package com.aphexspin.chrome;

import android.content.SharedPreferences;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * The live wallpaper. Every gesture uses two or three fingers, so the
 * launcher's one-finger swipes (pages, the app drawer) never move it.
 *
 *   two-finger drag    spin it, let go mid-swing and it keeps the momentum;
 *                      the sun follows your fingers
 *   pinch              zoom
 *   two-finger tap     next palette (cross-fades in 0.9 s)
 *   three-finger tap   next finish
 *   hold two fingers   back to the start
 */
public class ChromeWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new ChromeEngine();
    }

    private final class ChromeEngine extends Engine {
        private Runner runner;
        private boolean visible;
        private int width = 1, height = 1;
        private float slop;

        // gesture state (touch thread)
        private boolean twoActive, moved, held, pinching;
        private int maxPtr;
        private long downAt, twoAt, lastMoveAt;
        private float lastCx, lastCy, lastDist, startCx, startCy, startDist;
        private final double[] vel = new double[2];

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            slop = 14f * getResources().getDisplayMetrics().density;
            runner = new Runner(ChromeWallpaper.this, holder, true);
        }

        @Override
        public void onDestroy() {
            runner.release();
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = Math.max(1, w);
            height = Math.max(1, h);
            runner.surface(w, h);
            if (visible) runner.start();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            runner.surfaceGone();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                runner.start();
                SharedPreferences p = Prefs.get(ChromeWallpaper.this);
                if (!isPreview() && !p.getBoolean(Prefs.HINTED, false)) {
                    p.edit().putBoolean(Prefs.HINTED, true).apply();
                    runner.hint("two fingers spin it · tap with two for the palette, three for the finish");
                }
            } else {
                runner.stop();
            }
        }

        // ------------------------------------------------------------------ gestures
        @Override
        public void onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            int n = e.getPointerCount();
            long t = e.getEventTime();
            switch (act) {
                case MotionEvent.ACTION_DOWN:
                    downAt = t;
                    maxPtr = 1;
                    moved = false;
                    held = false;
                    twoActive = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPtr = Math.max(maxPtr, n);
                    startCx = cx(e);
                    startCy = cy(e);
                    startDist = spread(e);
                    if (n == 2) {
                        twoActive = true;
                        pinching = false;
                        twoAt = t;
                        lastCx = startCx;
                        lastCy = startCy;
                        lastDist = startDist;
                        lastMoveAt = t;
                        vel[0] = vel[1] = 0;
                        runner.touchStart();
                    } else if (twoActive) {
                        twoActive = false;          // a third finger: hold still for the tap
                        runner.touchEnd(null);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (n >= 2) {
                        float x = cx(e), y = cy(e);
                        if (Math.hypot(x - startCx, y - startCy) > slop
                            || (n == 2 && Math.abs(spread(e) - startDist) > slop)) moved = true;
                    }
                    if (twoActive && n == 2) {
                        if (!moved && !held && t - twoAt > 700) {
                            held = true;
                            runner.resetView();
                        }
                        if (!held) moveTwo(e, t);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (twoActive && n == 2) endTwo(t);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (twoActive) endTwo(t);
                    if (act == MotionEvent.ACTION_UP && !moved && !held) {
                        long dur = t - downAt;
                        if (maxPtr == 2 && dur < 350) nextPalette();
                        else if (maxPtr == 3 && dur < 600) nextFinish();
                    }
                    break;
                default:
                    break;
            }
            super.onTouchEvent(e);
        }

        private void moveTwo(MotionEvent e, long t) {
            float x = cx(e), y = cy(e), d = spread(e);
            double k = 3.2 / width;
            double ax = (y - lastCy) * k;      // vertical drag tilts
            double ay = (x - lastCx) * k;      // horizontal drag turns
            double et = Math.max(1 / 120.0, (t - lastMoveAt) / 1000.0);
            vel[0] = 0.55 * vel[0] + 0.45 * ax / et;
            vel[1] = 0.55 * vel[1] + 0.45 * ay / et;
            runner.dragBy(ax, ay, x / width * 2.0 - 1.0, 1.0 - y / height * 2.0);
            if (!pinching && Math.abs(d - startDist) > 2 * slop) pinching = true;
            if (pinching && lastDist > slop && d > slop) runner.pinch(d / lastDist);
            lastCx = x;
            lastCy = y;
            lastDist = d;
            lastMoveAt = t;
        }

        private void endTwo(long t) {
            twoActive = false;
            runner.touchEnd(!held && t - lastMoveAt < 80 ? new double[]{vel[0], vel[1]} : null);
        }

        private void nextPalette() {
            SharedPreferences p = Prefs.get(ChromeWallpaper.this);
            String cur = p.getString(Prefs.PALETTE, Prefs.D_PALETTE);
            String next = Palette.NAMES[(Palette.index(cur) + 1) % Palette.NAMES.length];
            p.edit().putString(Prefs.PALETTE, next).apply();
            runner.note("palette", next);
        }

        private void nextFinish() {
            SharedPreferences p = Prefs.get(ChromeWallpaper.this);
            int cur = p.getInt(Prefs.FINISH, Prefs.D_FINISH);
            int next = (cur + 1) % Tracer.FINISHES.length;
            p.edit().putInt(Prefs.FINISH, next).apply();
            runner.note("finish", Tracer.FINISHES[next]);
        }
    }

    static float cx(MotionEvent e) {
        int n = Math.min(e.getPointerCount(), 2);
        float s = 0;
        for (int i = 0; i < n; i++) s += e.getX(i);
        return s / n;
    }

    static float cy(MotionEvent e) {
        int n = Math.min(e.getPointerCount(), 2);
        float s = 0;
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }

    static float spread(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }
}
