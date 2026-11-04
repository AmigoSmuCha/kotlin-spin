package com.aphexspin.wallpaper;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared between the wallpaper and the settings screen. */
final class Prefs {
    private Prefs() {}

    static final String FILE = "settings";

    static final String MATERIAL = "material";      // index into AsciiRenderer.MATERIALS
    static final String MODE = "mode";              // index into AsciiRenderer.MODES
    static final String COLS = "cols";              // characters across the screen
    static final String FPS = "fps";
    static final String SPEED = "speed";            // auto-spin, hundredths of rad/s
    static final String CONTRAST = "contrast";      // hundredths
    static final String SAVER = "saver";            // stop auto-spin 30 s after last touch
    static final String CLOCK = "clock";            // 0 always, 1 lock screen only, 2 off
    static final String CLOCK_POS = "clock_pos";    // 0 top, 1 bottom
    static final String FIXED_TZ = "fixed_tz";      // true: always UTC+1, false: phone time zone

    static final int D_MATERIAL = 0, D_MODE = 0, D_COLS = 44, D_FPS = 30, D_SPEED = 55, D_CONTRAST = 225;
    static final boolean D_SAVER = false, D_FIXED_TZ = false;
    static final int D_CLOCK = 0, D_CLOCK_POS = 0;

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
