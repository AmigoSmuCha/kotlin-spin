package com.cornholio.ncs;

import android.app.WallpaperColors;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * The live wallpaper. Nothing a single finger does changes it, so the
 * launcher's swipes (pages, the app drawer) never move it.
 *
 *   two fingers        paint a wake through the letters (the trail setting
 *                      can give this to any finger, or to none)
 *   two-finger tap     next visualizer
 *   three-finger tap   next theme (the whole palette cross-fades in 0.9 s)
 *
 * It tells Android its theme's colours, so the launcher's labels and the
 * system's own colours can follow it (dark text on paper and latte).
 */
public class NcsWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new NcsEngine();
    }

    private final class NcsEngine extends Engine {
        private Runner runner;
        private boolean visible;
        private float slop;

        // gesture state (the main thread)
        private int maxPtr, trailMode;
        private long downAt;
        private boolean moved, painting;
        private float startCx, startCy;

        // a theme change, from a tap or from settings, gives Android the new colours
        private final SharedPreferences.OnSharedPreferenceChangeListener themeWatch = (p, key) -> {
            if (Prefs.THEME.equals(key) && Build.VERSION.SDK_INT >= 27) notifyColorsChanged();
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            Prefs.get(NcsWallpaper.this).registerOnSharedPreferenceChangeListener(themeWatch);
            slop = 14f * getResources().getDisplayMetrics().density;
            // the system's preview plays the demo song, so the picker shows it moving
            runner = new Runner(NcsWallpaper.this, holder, true, isPreview());
        }

        @Override
        public void onDestroy() {
            Prefs.get(NcsWallpaper.this).unregisterOnSharedPreferenceChangeListener(themeWatch);
            runner.release();
            super.onDestroy();
        }

        @Override
        public WallpaperColors onComputeColors() {
            int[] v = Theme.roles(Prefs.get(NcsWallpaper.this).getString(Prefs.THEME, Prefs.D_THEME));
            Color ground = Color.valueOf(0xFF000000 | v[Theme.BG]);
            Color lamp = Color.valueOf(0xFF000000 | v[Theme.ACCENT]);
            Color title = Color.valueOf(0xFF000000 | v[Theme.ACCENT2]);
            if (Build.VERSION.SDK_INT >= 31) {
                int hints = Theme.light(v[Theme.BG]) ? WallpaperColors.HINT_SUPPORTS_DARK_TEXT : WallpaperColors.HINT_SUPPORTS_DARK_THEME;
                return new WallpaperColors(ground, lamp, title, hints);
            }
            return new WallpaperColors(ground, lamp, title);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
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
                SharedPreferences p = Prefs.get(NcsWallpaper.this);
                if (!isPreview() && !p.getBoolean(Prefs.HINTED, false)) {
                    p.edit().putBoolean(Prefs.HINTED, true).apply();
                    runner.hint("two fingers paint the letters · tap with two for the next look, three for the next theme");
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
                    painting = false;
                    trailMode = Prefs.get(NcsWallpaper.this).getInt(Prefs.TRAIL, Prefs.D_TRAIL);
                    if (trailMode == 1) startPainting(e);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPtr = Math.max(maxPtr, n);
                    startCx = cx(e);
                    startCy = cy(e);
                    if (trailMode == 1) {
                        int i = e.getActionIndex();
                        runner.paintStart(e.getPointerId(i), e.getX(i), e.getY(i));
                    } else if (trailMode == 0 && n == 2) {
                        startPainting(e);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (n >= 2 && Math.hypot(cx(e) - startCx, cy(e) - startCy) > slop) moved = true;
                    if (painting) {
                        for (int i = 0; i < n; i++) runner.paint(e.getPointerId(i), e.getX(i), e.getY(i));
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (painting) {
                        runner.paintEnd(e.getPointerId(e.getActionIndex()));
                        if (trailMode == 0 && n - 1 < 2) stopPainting();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopPainting();
                    if (act == MotionEvent.ACTION_UP && !moved) {
                        long dur = t - downAt;
                        if (maxPtr == 2 && dur < 350) nextVisual();
                        else if (maxPtr == 3 && dur < 600) nextTheme();
                    }
                    break;
                default:
                    break;
            }
            super.onTouchEvent(e);
        }

        private void startPainting(MotionEvent e) {
            painting = true;
            for (int i = 0; i < e.getPointerCount(); i++) runner.paintStart(e.getPointerId(i), e.getX(i), e.getY(i));
        }

        private void stopPainting() {
            if (!painting) return;
            painting = false;
            runner.paintEnd(-1);
        }

        private void nextVisual() {
            SharedPreferences p = Prefs.get(NcsWallpaper.this);
            int n = Ncs.VISUALS.length;
            int next = (Math.max(0, Math.min(n - 1, p.getInt(Prefs.VISUAL, Prefs.D_VISUAL))) + 1) % n;
            p.edit().putInt(Prefs.VISUAL, next).apply();
            runner.note("visualizer", Ncs.VISUALS[next], 2.0);
        }

        private void nextTheme() {
            SharedPreferences p = Prefs.get(NcsWallpaper.this);
            String cur = p.getString(Prefs.THEME, Prefs.D_THEME);
            String next = Theme.NAMES[(Theme.index(cur) + 1) % Theme.NAMES.length];
            p.edit().putString(Prefs.THEME, next).apply();
            runner.note("theme", next, 2.0);
        }
    }

    static float cx(MotionEvent e) {
        int n = Math.min(e.getPointerCount(), 3);
        float s = 0;
        for (int i = 0; i < n; i++) s += e.getX(i);
        return s / n;
    }

    static float cy(MotionEvent e) {
        int n = Math.min(e.getPointerCount(), 3);
        float s = 0;
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }
}
