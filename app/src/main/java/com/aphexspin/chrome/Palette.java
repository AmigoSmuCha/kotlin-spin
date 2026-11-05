package com.aphexspin.chrome;

/**
 * Palettes, after aphex_spin.py and the focus / cornholio Design System: six
 * roles each (ground, ink, accent the lamp, title the cold highlight, panel,
 * caution) and an aurora. Every other colour the renderer and the interface use
 * is derived from those roles, so one palette is never mixed with another
 * (except while one cross-fades into the next). "aphex" is the original sky.
 */
public final class Palette {

    public static final String[] NAMES = {
        "aphex", "axel clay", "miasma", "gruvbox", "graphite", "dracula", "nord", "catppuccin", "integral lab"};

    /** One line per palette, in the system's voice. */
    public static final String[] FEEL = {
        "the original sky, cold blue over a sea of cloud",
        "a kiln at night · one molten orange ember",
        "an autumn marsh · a cool lamp in a warm room",
        "retro and punchy · cream on black, a red lamp",
        "pencil on slate · almost monochrome",
        "neon at midnight · near-white, red and violet",
        "arctic dusk · frost, violet and wheat",
        "a soft pastel night · peach and pink",
        "focus's house palette · warm greys, one hot orange"};

    // ground, ink, accent, title, panel, caution, aurora x5
    private static final String[][] ROLES = {
        null,
        {"#1b1b1b", "#d5c4a1", "#fd4a00", "#83a598", "#201d1b", "#fb4934", "#f6e8a0", "#f45e18", "#a3283c", "#de3030", "#ffaa3c"},
        {"#161413", "#d7c483", "#83a598", "#b36d43", "#201d1b", "#eca824", "#eccb7c", "#8b9a13", "#84405a", "#d3869b", "#d8a04a"},
        {"#1c1c1c", "#fbf1c7", "#fb4934", "#b8bb26", "#85a598", "#fe8019", "#96e846", "#48a555", "#2d6671", "#29305c", "#2b174c"},
        {"#222222", "#d8d8d8", "#585858", "#ffe5cf", "#d8d8d8", "#6e6e6e", "#d8d9fb", "#c5c6fa", "#9ea1f7", "#8b8ef5", "#777cf3"},
        {"#1c1d26", "#f8f8f2", "#ff0000", "#bd93f9", "#2b2e3b", "#ffb86c", "#f6e8a0", "#ff8f6c", "#c056a0", "#ff5555", "#ffb86c"},
        {"#20242d", "#d8dee9", "#7d64a3", "#88c0d0", "#3b4252", "#ebcb8b", "#ebcb8b", "#ff5555", "#c4a7e7", "#bf616a", "#ebcb8b"},
        {"#11111a", "#cdd6f4", "#fab387", "#f5c2e7", "#313244", "#fae3b0", "#4b3d3a", "#7a5d57", "#b9a7a2", "#d4b2a0", "#fae3b0"},
        {"#262626", "#d5c4a1", "#fd4a00", "#83a598", "#201d1b", "#cc241d", "#da1b61", "#fd4a00", "#5e4b8b", "#fb4934", "#fdab3c"},
    };

    // entries (each an RGB triple)
    public static final int ZEN = 0, MID = 1, HOR = 2, CLOUD = 3, SEA = 4, SEA_HI = 5, HAZE = 6, GLOW = 7, SUN = 8,
        ST_BASE = 9, ST_TOP = 10, ST_LEFT = 11, ST_RIGHT = 12, ST_FRONT = 13, ST_FLOOR = 14,
        KEY = 15, FILL = 16, RIM = 17, AMB_LO = 18, AMB_HI = 19,
        CHROME = 20, GLOSS = 21, SATIN = 22, OBSIDIAN = 23, GOLD = 24, BONE = 25,
        WHITE = 26, STAR0 = 27, STAR1 = 28, STAR2 = 29, NIGHT = 30,
        INK = 31, MUTED = 32, FAINT = 33, ACCENT = 34, GROUND = 35, PANEL = 36;
    static final int COUNT = 37;

    public final String name;
    public final double[][] c = new double[COUNT][];
    /** Whether the palette paints its own ground (aphex keeps the screen's own). */
    public boolean hasGround;

    private Palette(String name) {
        this.name = name;
    }

    public static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ helpers
    static double[] hex(String h) {
        h = h.startsWith("#") ? h.substring(1) : h;
        return new double[]{Integer.parseInt(h.substring(0, 2), 16) / 255.0,
            Integer.parseInt(h.substring(2, 4), 16) / 255.0,
            Integer.parseInt(h.substring(4, 6), 16) / 255.0};
    }

    static double[] lin(double[] c) {
        return new double[]{Math.pow(c[0], 2.2), Math.pow(c[1], 2.2), Math.pow(c[2], 2.2)};
    }

    static double[] mix(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double[] mul(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    static double[] v(double r, double g, double b) {
        return new double[]{r, g, b};
    }

    /** sRGB 0-1 triple to 0xRRGGBB, as aphex_spin's _ink. */
    public static int ink(double[] c) {
        int r = (int) (Math.min(1.0, Math.max(0.0, c[0])) * 255 + 0.5);
        int g = (int) (Math.min(1.0, Math.max(0.0, c[1])) * 255 + 0.5);
        int b = (int) (Math.min(1.0, Math.max(0.0, c[2])) * 255 + 0.5);
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ base palettes
    private static final Palette[] BASE = new Palette[NAMES.length];

    public static synchronized Palette get(String name) {
        int i = index(name);
        if (BASE[i] == null) BASE[i] = build(NAMES[i]);
        return BASE[i];
    }

    private static Palette build(String name) {
        Palette p = new Palette(name);
        double[][] c = p.c;
        if (name.equals("aphex")) {
            c[ZEN] = v(0.09, 0.17, 0.52); c[MID] = v(0.50, 0.64, 0.95);
            c[HOR] = v(1.0, 0.95, 0.97); c[CLOUD] = v(0.96, 0.94, 1.0);
            c[SEA] = v(0.20, 0.09, 0.13); c[SEA_HI] = v(0.50, 0.24, 0.26);
            c[HAZE] = v(0.94, 0.86, 0.92); c[GLOW] = v(1.0, 0.97, 0.98);
            c[SUN] = v(1.0, 0.96, 0.9);
            c[ST_BASE] = v(0.92, 0.86, 1.10); c[ST_TOP] = v(1.0, 1.0, 1.0);
            c[ST_LEFT] = v(1.0, 0.86, 0.70); c[ST_RIGHT] = v(0.62, 0.78, 1.10);
            c[ST_FRONT] = v(0.95, 0.92, 1.00); c[ST_FLOOR] = v(0.9, 0.8, 1.1);
            c[KEY] = v(1.0, 0.94, 0.86); c[FILL] = v(0.50, 0.62, 1.0); c[RIM] = v(1.0, 0.90, 1.0);
            c[AMB_LO] = v(0.012, 0.010, 0.018); c[AMB_HI] = v(0.075, 0.07, 0.10);
            c[CHROME] = v(0.96, 0.95, 1.0); c[GLOSS] = v(0.86, 0.10, 0.38);
            c[SATIN] = v(0.26, 0.10, 0.70); c[OBSIDIAN] = v(0.035, 0.03, 0.045);
            c[GOLD] = v(1.0, 0.78, 0.40); c[BONE] = v(0.78, 0.74, 0.66);
            c[WHITE] = v(1.0, 1.0, 1.0);
            c[STAR0] = v(1.0, 1.0, 1.0); c[STAR1] = hex("#e4e8ff"); c[STAR2] = hex("#a9b2e6");
            c[NIGHT] = hex("#0e0d13");
            c[INK] = hex("#dcdcf0"); c[MUTED] = hex("#8b8fa8"); c[FAINT] = hex("#55576a"); c[ACCENT] = hex("#f07ab4");
            c[GROUND] = c[NIGHT].clone();
            c[PANEL] = hex("#17161f");
            p.hasGround = false;
            return p;
        }
        String[] r = ROLES[index(name)];
        double[] g = hex(r[0]), ink = hex(r[1]), acc = hex(r[2]), title = hex(r[3]), panel = hex(r[4]), caution = hex(r[5]);
        double[] aur0 = hex(r[6]);
        double[] ones = v(1, 1, 1);
        c[ZEN] = mul(lin(mix(g, title, 0.72)), 1.15);
        c[MID] = mul(lin(title), 1.55);
        c[HOR] = mul(lin(mix(ink, aur0, 0.2)), 1.05);
        c[CLOUD] = mul(lin(mix(ink, title, 0.3)), 1.1);
        c[SEA] = mul(lin(mix(g, acc, 0.62)), 1.3);
        c[SEA_HI] = mul(lin(mix(acc, caution, 0.3)), 1.05);
        c[HAZE] = mul(lin(mix(ink, acc, 0.3)), 1.05);
        c[GLOW] = lin(ink);
        c[SUN] = lin(ink);
        c[ST_BASE] = mul(lin(mix(g, title, 0.35)), 4.0);
        c[ST_TOP] = mul(lin(ink), 1.1);
        c[ST_LEFT] = lin(mix(ink, caution, 0.35));
        c[ST_RIGHT] = lin(mix(ink, title, 0.55));
        c[ST_FRONT] = lin(ink);
        c[ST_FLOOR] = mul(lin(mix(g, acc, 0.4)), 6.0);
        c[KEY] = mul(lin(ink), 1.15);
        c[FILL] = mul(lin(title), 1.2);
        c[RIM] = mul(lin(mix(ink, title, 0.3)), 1.1);
        c[AMB_LO] = mul(lin(g), 0.6);
        c[AMB_HI] = mul(lin(mix(g, title, 0.35)), 1.2);
        c[CHROME] = lin(mix(ones, ink, 0.3));
        c[GLOSS] = lin(acc);
        c[SATIN] = lin(title);
        double[] ob = mul(lin(g), 1.2);
        c[OBSIDIAN] = v(ob[0] + 0.01, ob[1] + 0.01, ob[2] + 0.01);
        c[GOLD] = lin(mix(caution, ink, 0.45));
        c[BONE] = lin(ink);
        double[] wp = v(Math.min(1.0, ink[0] * 1.08), Math.min(1.0, ink[1] * 1.08), Math.min(1.0, ink[2] * 1.08));
        c[WHITE] = wp;
        c[STAR0] = wp.clone();
        c[STAR1] = mix(ink, title, 0.35);
        c[STAR2] = mix(ink, g, 0.55);
        c[GROUND] = g;
        c[NIGHT] = g.clone();
        c[INK] = ink;
        c[MUTED] = mix(ink, g, 0.38);
        c[FAINT] = mix(ink, g, 0.64);
        c[ACCENT] = acc;
        c[PANEL] = panel;
        p.hasGround = true;
        return p;
    }

    /**
     * A weighted mix of base palettes, (name, weight) pairs: a cross-fade, even
     * one started half way through another (aphex_spin's mix_palettes).
     */
    public static Palette mix(String[] names, double[] w) {
        if (names.length == 1) return get(names[0]);
        Palette[] parts = new Palette[names.length];
        for (int i = 0; i < names.length; i++) parts[i] = get(names[i]);
        Palette out = new Palette(names[names.length - 1]);
        for (int k = 0; k < COUNT; k++) {
            double[] s = new double[3];
            for (int i = 0; i < parts.length; i++) {
                double[] val = parts[i].c[k];
                for (int j = 0; j < 3; j++) s[j] = s[j] + w[i] * val[j];
            }
            out.c[k] = s;
        }
        double groundWeight = 0;
        for (int i = 0; i < parts.length; i++) if (parts[i].hasGround) groundWeight += w[i];
        out.hasGround = groundWeight >= 0.5;
        return out;
    }
}
