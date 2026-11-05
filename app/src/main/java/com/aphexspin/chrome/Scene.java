package com.aphexspin.chrome;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What is on screen and how it moves: orientation, sun, zoom, palette and
 * finish with their cross-fades, glyph mode and ground (aphex_spin.py's View
 * and Engine). render() traces a frame, remembering depths so the next frame's
 * rays can start just in front of the surface.
 */
public final class Scene {

    public static final double FADE_PALETTE = 0.9, FADE_FINISH = 0.6;
    public static final double[] DEFAULT_LIGHT = Tracer.unit(-0.55, 0.62, 0.75);
    public static final int GROUND_PALETTE = 0, GROUND_SOLID = 1, GROUND_BLACK = 2;
    public static final String[] GROUNDS = {"palette", "solid", "black"};
    public static final String[] GROUND_FEEL = {
        "the palette decides · aphex keeps pure black",
        "always the palette's own night",
        "pure black under every palette"};

    private static final double[] SX = {0.28, 0.72, 0.28, 0.72, 0.28, 0.72};
    private static final double[] SY = {0.42, 0.30, 1.06, 0.94, 1.70, 1.58};
    private static final double SAMPLE_R = 0.30;

    private final Tracer tracer = new Tracer();
    private Ascii glyphsAll, glyphsSym;

    // ---- view state
    public double[][] R;
    public double[] light, lightTarget;
    public final double[] spin = new double[2];
    public double zoom = 1.0, clock = 0.0;
    public int mode = Ascii.SHAPE;
    public double contrast = 2.25;
    public boolean allGlyphs = true, backdrop = false, stars = true;
    public int groundMode = GROUND_PALETTE;
    private String palette = "aphex";
    private int material = 0;
    private String[] fromN;
    private double[] fromW;
    private double mixT0 = -10;
    private int matFrom = -1;
    private double matT0 = -10;

    // ---- caches
    private int gCols = -1, gRows = -1;
    private double gZoom = -1;
    private float[] xs = new float[0], ys = new float[0];
    private double footprint, cell;
    private double[][] prevR;
    private float[] prevDepth = new float[0];
    private boolean havePrev;
    private final Map<String, float[]> sky = new HashMap<>();
    private float[] backdropBuf = new float[0];
    private String palKey;
    private Palette palNow;
    private final float[][] envNow = new float[2][];
    private final float[][] envBlend = new float[2][];
    private boolean sparkled;

    public Scene(Balloon model) {
        tracer.setModel(model);
        reset();
    }

    public Balloon model() {
        return tracer.model();
    }

    /** A new balloon (another puff): forgets everything sized by the old one. */
    public void setModel(Balloon b) {
        tracer.setModel(b);
        gCols = -1;
        havePrev = false;
        sky.clear();
    }

    public void reset() {
        R = mul(rotX(0.16), rotY(-0.42));
        light = DEFAULT_LIGHT.clone();
        lightTarget = light.clone();
        spin[0] = spin[1] = 0;
    }

    // ------------------------------------------------------------------ palette and finish, with cross-fades
    static double ease(double x) {
        x = Math.min(1.0, Math.max(0.0, x));
        return x * x * (3.0 - 2.0 * x);
    }

    public String palette() {
        return palette;
    }

    public int material() {
        return material;
    }

    /** The palette now, as base palettes and weights. */
    private Object[] mix() {
        if (fromN == null) return new Object[]{new String[]{palette}, new double[]{1.0}};
        double w = ease((clock - mixT0) / FADE_PALETTE);
        if (w >= 1.0) {
            fromN = null;
            return new Object[]{new String[]{palette}, new double[]{1.0}};
        }
        LinkedHashMap<String, Double> d = new LinkedHashMap<>();
        for (int i = 0; i < fromN.length; i++) {
            Double old = d.get(fromN[i]);
            double add = fromW[i] * (1.0 - w);
            d.put(fromN[i], old == null ? add : old + add);
        }
        d.remove(palette);
        double cur = 0;
        for (double v : d.values()) cur += v;
        String[] n = new String[d.size() + 1];
        double[] ws = new double[d.size() + 1];
        int k = 0;
        for (Map.Entry<String, Double> e : d.entrySet()) {
            n[k] = e.getKey();
            ws[k] = e.getValue();
            k++;
        }
        n[k] = palette;
        ws[k] = 1.0 - cur;
        return new Object[]{n, ws};
    }

    /** Straight to a palette and finish, no cross-fade (a fresh start). */
    public void jump(String pal, int mat) {
        palette = pal;
        fromN = null;
        material = mat;
        matFrom = -1;
    }

    public void setPalette(String name) {
        if (!name.equals(palette)) {
            Object[] m = mix();
            fromN = (String[]) m[0];
            fromW = (double[]) m[1];
            mixT0 = clock;
            palette = name;
        }
    }

    public void setMaterial(int m) {
        if (m != material) {
            matFrom = material;
            matT0 = clock;
            material = m;
        }
    }

    /** Whether a palette or finish is still fading in. */
    public boolean fading() {
        return (fromN != null && clock - mixT0 < FADE_PALETTE) || (matFrom >= 0 && clock - matT0 < FADE_FINISH);
    }

    /** Whether the last frame had a sparkle (they twinkle, so they want frames). */
    public boolean sparkling() {
        return sparkled;
    }

    private void palFor(String[] n, double[] w, boolean needSky, boolean needStudio) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) sb.append(n[i]).append('=').append(w[i]).append(';');
        String key = sb.toString();
        if (key.equals(palKey)) return;
        palKey = key;
        palNow = Palette.mix(n, w);
        for (int kind = 0; kind < 2; kind++) {
            if (n.length == 1) {
                envNow[kind] = EnvMap.get(kind, n[0]);
                continue;
            }
            if (kind == EnvMap.SKY ? !needSky : !needStudio) {
                envNow[kind] = EnvMap.get(kind, n[n.length - 1]);   // unused this frame
                palKey = null;                                       // so it is blended once needed
                continue;
            }
            float[][] maps = new float[n.length][];
            for (int i = 0; i < n.length; i++) maps[i] = EnvMap.get(kind, n[i]);
            if (envBlend[kind] == null) envBlend[kind] = new float[maps[0].length];
            EnvMap.blend(maps, w, envBlend[kind]);
            envNow[kind] = envBlend[kind];
        }
    }

    /** The interface colours and ground of the palette now on screen. */
    public Palette paletteNow() {
        Object[] m = mix();
        palFor((String[]) m[0], (double[]) m[1], false, false);
        return palNow;
    }

    private double[] groundColour(String name) {
        Palette p = Palette.get(name);
        if (groundMode == GROUND_BLACK) return new double[3];
        if (groundMode == GROUND_SOLID) return (p.hasGround ? p.c[Palette.GROUND] : p.c[Palette.NIGHT]).clone();
        return p.hasGround ? p.c[Palette.GROUND].clone() : new double[3];
    }

    // ------------------------------------------------------------------ the sample grid
    private void grid(int cols, int rows) {
        if (cols == gCols && rows == gRows && zoom == gZoom) return;
        Balloon m = tracer.model();
        double S = zoom * 0.94 * Math.min(cols / (2.0 * m.reachX), 2.0 * rows / (2.0 * (m.reachY + 1.2)));
        int n = cols * rows * 6;
        xs = new float[n];
        ys = new float[n];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                for (int k = 0; k < 6; k++) {
                    int i = (r * cols + c) * 6 + k;
                    xs[i] = (float) (((c + SX[k]) - cols / 2.0) / S);
                    ys[i] = (float) ((rows - (2.0 * r + SY[k])) / S);
                }
        footprint = SAMPLE_R * 1.15 / S;
        cell = 1.0 / S;
        gCols = cols;
        gRows = rows;
        gZoom = zoom;
        sky.clear();
        havePrev = false;
    }

    /** Where each ray may safely start: in front of what its neighbourhood hit last frame. */
    private float[] coherentStarts(int rows, int cols) {
        if (!havePrev) return null;
        double tr = 0;
        for (int i = 0; i < 3; i++) tr += R[i][0] * prevR[i][0] + R[i][1] * prevR[i][1] + R[i][2] * prevR[i][2];
        double c = Math.max(-1.0, Math.min(1.0, (tr - 1.0) / 2.0));
        double move = Math.acos(c) * tracer.model().radius;
        int reach = (int) Math.ceil(move / cell) + 1;
        if (reach > 4) return null;
        int cells = rows * cols;
        float[] T = new float[cells];
        for (int i = 0; i < cells; i++) {
            float m = Float.POSITIVE_INFINITY;
            for (int k = 0; k < 6; k++) {
                float d = prevDepth[i * 6 + k];
                if (!Float.isNaN(d) && !Float.isInfinite(d) && d < m) m = d;
            }
            T[i] = m;
        }
        float[] tmp = new float[cells];
        for (int r = 0; r < rows; r++)
            for (int x = 0; x < cols; x++) {
                float m = Float.POSITIVE_INFINITY;
                for (int y = Math.max(0, r - reach); y <= Math.min(rows - 1, r + reach); y++) m = Math.min(m, T[y * cols + x]);
                tmp[r * cols + x] = m;
            }
        float back = (float) (2.0 * move + 0.25 + 0.6 * cell);
        float[] start = new float[cells * 6];
        for (int r = 0; r < rows; r++)
            for (int x = 0; x < cols; x++) {
                float m = Float.POSITIVE_INFINITY;
                for (int xx = Math.max(0, x - reach); xx <= Math.min(cols - 1, x + reach); xx++) m = Math.min(m, tmp[r * cols + xx]);
                float s = m - back;
                int o = (r * cols + x) * 6;
                for (int k = 0; k < 6; k++) start[o + k] = s;
            }
        return start;
    }

    /** The sky behind the logo, sparse and dim, as the script's View.sky. */
    private float[] skyFor(int kind, String name) {
        String key = kind + "/" + name;
        float[] s = sky.get(key);
        if (s != null) return s;
        int n = xs.length;
        float ymin = Float.POSITIVE_INFINITY, ymax = Float.NEGATIVE_INFINITY;
        for (float y : ys) { ymin = Math.min(ymin, y); ymax = Math.max(ymax, y); }
        float span = ymax - ymin;
        if (span == 0) span = 1.0f;
        Palette P = Palette.get(name);
        double[] e = new double[3];
        s = new float[n * 3];
        for (int i = 0; i < n; i++) {
            float dy = 0.78f * (ys[i] - ymin) / span - 0.47f;
            float dx = 0.78f * xs[i] / span;
            double l = Math.sqrt((double) dx * dx + (double) dy * dy + 1.0);
            EnvMap.env(kind, dx / l, dy / l, -1.0 / l, P, e);
            double r = Math.pow(acesC(e[0] * 0.8), 1 / 2.2), g = Math.pow(acesC(e[1] * 0.8), 1 / 2.2), b = Math.pow(acesC(e[2] * 0.8), 1 / 2.2);
            double lum = r * 0.2126 + g * 0.7152 + b * 0.0722;
            double wisp = EnvMap.smoothstep(0.35, 0.8,
                EnvMap.vnoise(dx * 9.0f + 2.0f, dy * 26.0f) * 0.7 + EnvMap.vnoise(dx * 23.0f, dy * 61.0f) * 0.3);
            double keep = EnvMap.smoothstep(0.50, 1.0, lum) * (0.30 + 0.70 * wisp) * 0.34;
            s[i * 3] = (float) (r * keep);
            s[i * 3 + 1] = (float) (g * keep);
            s[i * 3 + 2] = (float) (b * keep);
        }
        sky.put(key, s);
        return s;
    }

    private static double acesC(double x) {
        double v = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private Ascii glyphs() {
        if (allGlyphs) {
            if (glyphsAll == null) glyphsAll = new Ascii(true);
            return glyphsAll;
        }
        if (glyphsSym == null) glyphsSym = new Ascii(false);
        return glyphsSym;
    }

    // ------------------------------------------------------------------ one frame
    public void render(Frame out, int cols, int rows) {
        grid(cols, rows);
        Object[] mx = mix();
        String[] names = (String[]) mx[0];
        double[] ws = (double[]) mx[1];
        int also = -1;
        double aw = 1.0;
        if (matFrom >= 0) {
            double w = ease((clock - matT0) / FADE_FINISH);
            if (w >= 1.0) matFrom = -1;
            else {
                also = matFrom;
                aw = w;
            }
        }
        int envA = Tracer.envOf(material), envB = also >= 0 ? Tracer.envOf(also) : envA;
        palFor(names, ws, envA == EnvMap.SKY || envB == EnvMap.SKY, envA == EnvMap.STUDIO || envB == EnvMap.STUDIO);
        int n = cols * rows * 6;
        float[] start = coherentStarts(rows, cols);
        tracer.trace(xs, ys, n, R, light, material, footprint, start, palNow, envNow, also, aw);
        if (prevDepth.length != n) prevDepth = new float[n];
        System.arraycopy(tracer.depth, 0, prevDepth, 0, n);
        prevR = new double[][]{R[0].clone(), R[1].clone(), R[2].clone()};
        havePrev = true;

        float[] bd = null;
        if (backdrop) {
            if (backdropBuf.length != n * 3) backdropBuf = new float[n * 3];
            java.util.Arrays.fill(backdropBuf, 0f);
            int kind = Tracer.envOf(material);
            for (int i = 0; i < names.length; i++) {
                float[] s = skyFor(kind, names[i]);
                float w = (float) ws[i];
                for (int j = 0; j < s.length; j++) backdropBuf[j] += w * s[j];
            }
            bd = backdropBuf;
        }
        double[] ground = new double[3];
        for (int i = 0; i < names.length; i++) {
            double[] g = groundColour(names[i]);
            for (int j = 0; j < 3; j++) ground[j] += ws[i] * g[j];
        }
        double[][] c = palNow.c;
        int[] inks = {Palette.ink(c[Palette.STAR0]), Palette.ink(c[Palette.STAR1]), Palette.ink(c[Palette.STAR2])};
        Ascii a = glyphs();
        a.make(out, tracer.rgb, tracer.lum, tracer.glint, tracer.cov, rows, cols, mode, contrast, bd, stars, clock, inks, ground);
        sparkled = stars && a.sparkled;
        out.ink = Palette.ink(c[Palette.INK]);
        out.muted = Palette.ink(c[Palette.MUTED]);
        out.faint = Palette.ink(c[Palette.FAINT]);
        out.accent = Palette.ink(c[Palette.ACCENT]);
        out.panel = Palette.ink(c[Palette.PANEL]);
    }

    // ------------------------------------------------------------------ motion
    /** Turn by ax about the view's x axis and ay about its y axis (a drag). */
    public void dragBy(double ax, double ay) {
        R = orthonormal(mul(mul(rotX(ax), rotY(ay)), R));
    }

    /**
     * Advance by dt seconds: momentum, the auto-spin easing back in after a
     * release, the sun easing to its target, the clock the sparkles twinkle on.
     */
    public void step(double dt, boolean dragging, double sinceRelease, double spinSpeed) {
        if (!dragging) {
            if (Math.abs(spin[0]) > 1e-3 || Math.abs(spin[1]) > 1e-3) {
                R = orthonormal(mul(mul(rotX(spin[0] * dt), rotY(spin[1] * dt)), R));
                double k = Math.exp(-dt / 1.3);
                spin[0] *= k;
                spin[1] *= k;
            }
            double blend = Math.min(1.0, Math.max(0.0, (sinceRelease - 0.6) / 1.8));
            if (spinSpeed * blend != 0) R = orthonormal(mul(rotY(spinSpeed * dt * blend), R));
        }
        double k = 1 - Math.exp(-dt / 0.12);
        light = Tracer.unit(light[0] + (lightTarget[0] - light[0]) * k, light[1] + (lightTarget[1] - light[1]) * k,
            light[2] + (lightTarget[2] - light[2]) * k);
        clock += dt;
    }

    public boolean lightSettled() {
        return Math.abs(light[0] - lightTarget[0]) + Math.abs(light[1] - lightTarget[1]) + Math.abs(light[2] - lightTarget[2]) < 1e-3;
    }

    // ------------------------------------------------------------------ matrices
    public static double[][] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    public static double[][] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    public static double[][] mul(double[][] A, double[][] B) {
        double[][] C = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                C[i][j] = A[i][0] * B[0][j] + A[i][1] * B[1][j] + A[i][2] * B[2][j];
        return C;
    }

    public static double[][] orthonormal(double[][] R) {
        double[] x = R[0].clone();
        double l = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
        x[0] /= l; x[1] /= l; x[2] /= l;
        double d = x[0] * R[1][0] + x[1] * R[1][1] + x[2] * R[1][2];
        double[] y = {R[1][0] - x[0] * d, R[1][1] - x[1] * d, R[1][2] - x[2] * d};
        l = Math.sqrt(y[0] * y[0] + y[1] * y[1] + y[2] * y[2]);
        y[0] /= l; y[1] /= l; y[2] /= l;
        double[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
        return new double[][]{x, y, z};
    }
}
