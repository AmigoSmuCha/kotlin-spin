package com.aphexspin.chrome;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the wallpaper, its preview and the settings screen. Saved at once, no questions. */
final class Prefs {
    private Prefs() {}

    static final String FILE = "settings";

    static final String PALETTE = "palette";      // a Palette.NAMES entry
    static final String FINISH = "finish";        // index into Tracer.FINISHES
    static final String MODE = "mode";            // index into Ascii.MODES
    static final String LETTERS = "letters";      // true: every printable glyph
    static final String SKY = "sky";              // the sky behind the logo
    static final String STARS = "stars";          // sparkles on the sun's glints
    static final String GROUND = "ground";        // Scene.GROUND_*
    static final String COLS = "cols";            // characters across
    static final String CONTRAST = "contrast";    // hundredths
    static final String SPIN = "spin";            // auto-spin, hundredths of rad/s
    static final String FPS = "fps";
    static final String PUFF = "puff";            // hundredths
    static final String SAVER = "saver";          // auto-spin rests 30 s after the last touch
    static final String CLOCK = "clock";          // 0 always, 1 lock screen only, 2 off
    static final String CLOCK_POS = "clock_pos";  // 0 top, 1 bottom
    static final String FIXED_TZ = "fixed_tz";    // true: always UTC+1
    static final String HINTED = "hinted";        // the one-time gesture hint has been shown

    static final String D_PALETTE = "aphex";
    static final int D_FINISH = 0, D_MODE = 0, D_GROUND = 0, D_COLS = 48, D_CONTRAST = 225, D_SPIN = 55, D_FPS = 24,
        D_PUFF = 82, D_CLOCK = 0, D_CLOCK_POS = 0;
    static final boolean D_LETTERS = true, D_SKY = false, D_STARS = true, D_SAVER = false, D_FIXED_TZ = false;

    static final int[] COLS_STEPS = {36, 40, 44, 48, 52, 56, 64};
    static final int[] CONTRAST_STEPS = {100, 125, 150, 175, 200, 225, 250, 275, 300, 350, 400};
    static final int[] SPIN_STEPS = {0, 15, 30, 55, 80, 120, 200};
    static final int[] FPS_STEPS = {15, 20, 24, 30, 45, 60};
    static final int[] PUFF_STEPS = {50, 60, 70, 82, 95, 110};

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
