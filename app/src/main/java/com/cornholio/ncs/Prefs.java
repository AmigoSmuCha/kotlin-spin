package com.cornholio.ncs;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the wallpaper, its previews and the settings screen. Saved at once, no questions. */
final class Prefs {
    private Prefs() {}

    static final String FILE = "settings";

    static final String LISTEN = "listen";        // 0 the phone's sound, 1 the demo song
    static final String LOUDNESS = "loudness";    // 0 as played, 1 evened out
    static final String VISUAL = "visual";        // index into Ncs.VISUALS
    static final String THEME = "theme";          // a Theme.NAMES entry
    static final String REACH = "reach";          // 0 half the screen, 1 two thirds, 2 all of it
    static final String FLOW = "flow";            // the slow flow of letters behind
    static final String TRAIL = "trail";          // 0 two fingers paint, 1 any finger paints, 2 no trail
    static final String SONG = "song";            // the field takes the playing song's letters
    static final String COLS = "cols";            // characters across
    static final String FPS = "fps";
    static final String HINTED = "hinted";

    static final String D_THEME = "default";
    static final int D_LISTEN = 0, D_LOUDNESS = 0, D_VISUAL = 0, D_REACH = 0, D_TRAIL = 0, D_COLS = 48, D_FPS = 30;
    static final boolean D_FLOW = true, D_SONG = false;

    static final int[] COLS_STEPS = {36, 40, 44, 48, 52, 56, 64};
    static final int[] FPS_STEPS = {20, 24, 30, 45, 60};
    static final String[] REACHES = {"half the screen", "two thirds", "all of it"};
    static final String[] REACH_FEEL = {
        "the visualizer stands in the lower half, the flow above it",
        "the visualizer takes the lower two thirds",
        "the visualizer takes the whole screen"};
    static final double[] REACH_TOP = {0.5, 1 / 3.0, 0.0};
    static final String[] TRAILS = {"two fingers", "any finger", "none"};
    static final String[] TRAIL_FEEL = {
        "two fingers paint a wake through the letters · one finger stays the launcher's",
        "every touch paints a wake, the drawer's swipes too",
        "the letters ignore your fingers"};

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
