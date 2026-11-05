package com.cornholio.ncs;

/**
 * cornholio's colour variables (its Palette, read as C.fg, C.accent, ...):
 * its sixteen themes, each turned from focus's compact roles into the full
 * set, and a theme change that cross-fades every variable at once in 0.9 s.
 * Colours are 0xRRGGBB; mix() rounds as Python's round() does.
 */
public final class Theme {

    public static final String[] NAMES = {
        "default", "Axel Clay", "miasma", "gruvbox", "nord", "dracula", "tokyo-night", "rose-pine",
        "catppuccin-mocha", "solarized-dark", "catppuccin-latte", "Graphite", "magma", "viridis", "phosphor", "paper"};

    /** One line per theme, in the system's voice. */
    public static final String[] FEEL = {
        "integral lab · warm greys, a cool teal sky, one hot orange",
        "a kiln at night · one molten orange ember",
        "an autumn marsh · a cool lamp in a warm room",
        "retro and punchy · cream on black, a red lamp",
        "arctic dusk · frost, violet and wheat",
        "neon at midnight · near-white, red and violet",
        "a city after rain · lilac, teal and amber",
        "a rose-lit night · iris, rose and gold",
        "a soft pastel night · peach and pink",
        "solarized · ink-blue water, violet and orange",
        "the pastel night's light twin, on paper",
        "pencil on slate · almost monochrome",
        "magma · violet, coral and pale gold",
        "viridis · blue, green and yellow, even to every eye",
        "an oscilloscope in a dark lab · green, amber",
        "a figure in a journal · ink on off-white"};

    // background, panel, title, warning, cursor, text, muted (null: derived)
    private static final String[][] UI = {
        {"#262626", "#201d1b", "#83a598", "#cc241d", "#fd4a00", "#d5c4a1", null},
        {"#262626", "#201d1b", "#83a598", "#fb4934", "#fd4a00", "#d5c4a1", null},
        {"#201d1b", "#201d1b", "#b36d43", "#eca824", "#83a598", "#d7c483", "#928374"},
        {"#282828", "#85a598", "#b8bb26", "#fe8019", "#fb4934", "#fbf1c7", null},
        {"#2e3440", "#3b4252", "#88c0d0", "#ebcb8b", "#7d64a3", "#d8dee9", null},
        {"#282a36", "#2b2e3b", "#bd93f9", "#ffb86c", "#ff0000", "#f8f8f2", null},
        {"#1a1b26", "#24283b", "#bb9af7", "#e0af68", "#01796f", "#a9b1d6", null},
        {"#191724", "#1f1d2e", "#c4a7e7", "#f6c177", "#ffcdef", "#e0def4", null},
        {"#181825", "#313244", "#f5c2e7", "#fae3b0", "#fab387", "#cdd6f4", null},
        {"#002b36", "#073642", "#268bd2", "#cb4b16", "#6c71c4", "#839496", null},
        {"#eff1f5", "#e6e9ef", "#8839ef", "#df8e1d", "#fe640b", "#4c4f69", null},
        {"#303030", "#d8d8d8", "#ffe5cf", "#6e6e6e", "#585858", "#d8d8d8", null},
        {"#1d1338", "#352151", "#c13f82", "#fde4a5", "#fb8761", "#f3e8d2", null},
        {"#16132b", "#25224a", "#26828e", "#fde725", "#44bf70", "#e8f1dd", null},
        {"#07140d", "#123826", "#2fb7a0", "#ffb000", "#3dff85", "#b9f5c8", null},
        {"#f8f6f0", "#e6e1d3", "#238823", "#d4620a", "#1f77b4", "#1f1f1d", "#6b6a64"},
    };

    public static final String[] KEYS = {"bg", "fg", "dim", "faint", "ghost", "accent", "accent2", "star", "snow",
        "sel", "lift", "warn", "body", "beak"};
    public static final int BG = 0, FG = 1, DIM = 2, FAINT = 3, GHOST = 4, ACCENT = 5, ACCENT2 = 6, STAR = 7, SNOW = 8,
        SEL = 9, LIFT = 10, WARN = 11, BODY = 12, BEAK = 13;

    public static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ colour helpers
    public static int hex(String h) {
        return Integer.parseInt(h.substring(1), 16);
    }

    private static int r(int c) { return (c >> 16) & 255; }
    private static int g(int c) { return (c >> 8) & 255; }
    private static int b(int c) { return c & 255; }

    /** cornholio's mix(): t <= 0 is a, t >= 1 is b, between them rounded as Python rounds. */
    public static int mix(int a, int b, double t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int rr = (int) Math.rint(r(a) + (r(b) - r(a)) * t);
        int gg = (int) Math.rint(g(a) + (g(b) - g(a)) * t);
        int bb = (int) Math.rint(b(a) + (b(b) - b(a)) * t);
        return (rr << 16) | (gg << 8) | bb;
    }

    /** _mixhex: like mix but always rounding (no shortcut at the ends). */
    static int mixhex(int a, int b, double t) {
        int rr = (int) Math.rint(r(a) + (r(b) - r(a)) * t);
        int gg = (int) Math.rint(g(a) + (g(b) - g(a)) * t);
        int bb = (int) Math.rint(b(a) + (b(b) - b(a)) * t);
        return (rr << 16) | (gg << 8) | bb;
    }

    static boolean light(int c) {
        return 0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c) > 128;
    }

    /** The full set of a theme's colour variables, in KEYS order. */
    public static int[] roles(String name) {
        String[] u = UI[index(name)];
        int background = hex(u[0]), panel = hex(u[1]), title = hex(u[2]), warning = hex(u[3]), cursor = hex(u[4]),
            text = hex(u[5]);
        int bg = light(background) ? background : mixhex(background, 0x000000, 0.3);
        boolean lt = light(bg);
        int[] v = new int[KEYS.length];
        v[BG] = bg;
        v[FG] = text;
        v[DIM] = u[6] != null ? hex(u[6]) : mixhex(text, bg, 0.38);
        v[FAINT] = mixhex(text, bg, 0.64);
        v[GHOST] = mixhex(text, bg, 0.84);
        v[ACCENT] = cursor;
        v[ACCENT2] = title;
        v[STAR] = mixhex(text, bg, 0.12);
        v[SNOW] = lt ? mixhex(text, bg, 0.55) : mixhex(text, 0xffffff, 0.3);
        v[SEL] = panel;
        v[LIFT] = mixhex(panel, cursor, 0.22);
        v[WARN] = warning;
        v[BODY] = lt ? mixhex(text, bg, 0.15) : mixhex(bg, text, 0.2);
        v[BEAK] = mixhex(warning, cursor, 0.5);
        return v;
    }

    // ------------------------------------------------------------------ the live variables
    public static final double FADE = 0.9;
    public String name;
    public int bg, fg, dim, faint, ghost, accent, accent2, star, snow, sel, lift, warn, body, beak;
    private int[] src, dst;
    private double t0 = -10;

    public Theme(String name) {
        this.name = NAMES[index(name)];
        dst = roles(this.name);
        src = dst.clone();
        set(dst);
    }

    private void set(int[] v) {
        bg = v[BG]; fg = v[FG]; dim = v[DIM]; faint = v[FAINT]; ghost = v[GHOST]; accent = v[ACCENT];
        accent2 = v[ACCENT2]; star = v[STAR]; snow = v[SNOW]; sel = v[SEL]; lift = v[LIFT]; warn = v[WARN];
        body = v[BODY]; beak = v[BEAK];
    }

    private int[] now() {
        return new int[]{bg, fg, dim, faint, ghost, accent, accent2, star, snow, sel, lift, warn, body, beak};
    }

    /** Cross-fade every variable to another theme, from now. */
    public void switchTo(String name, double now) {
        src = now();
        this.name = NAMES[index(name)];
        dst = roles(this.name);
        t0 = now;
    }

    public boolean fading(double now) {
        return now - t0 < FADE;
    }

    static double ease(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    public void update(double now) {
        double t = (now - t0) / FADE;
        if (t >= 1.05) return;
        double e = ease(t);
        int[] v = new int[KEYS.length];
        for (int i = 0; i < v.length; i++) v[i] = mix(src[i], dst[i], e);
        set(v);
    }
}
