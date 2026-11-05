package com.aphexspin.chrome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Samples to characters: aphex_spin.py's make_frame. Shape-based glyph choice
 * after Alex Harri ("ASCII characters are not pixels"): each cell's six samples,
 * contrast-enhanced, are matched against six sampling circles measured on every
 * glyph of DejaVu Sans Mono. Also the ramp and half-block modes, the sky
 * behind the logo and the sun's four-point sparkles.
 */
public final class Ascii {

    public static final String SYMBOLS = " .,:;'`\"-_~^!|/\\()[]{}<>+=*#%@&$?";
    public static final int SHAPE = 0, RAMP = 1, BLOCK = 2;
    public static final String[] MODES = {"shape", "ramp", "block"};
    public static final String[] MODE_FEEL = {
        "letters chosen by their shape, the sharpest",
        "a density ramp, dithered",
        "half blocks, two pixels to a cell"};
    private static final String RAMP_CHARS = " .:-=+*#%@";
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final int[][] AFFECT = {{0, 1, 2, 4}, {0, 1, 3, 5}, {2, 4, 6}, {3, 5, 7}, {4, 6, 8, 9}, {5, 7, 8, 9}};

    // four-point stars, small to large: row, col, glyph, ink (a cell is twice as tall as wide)
    private static final int[][][] STARS = {
        {{0, 0, '*', 0}, {0, -1, '-', 1}, {0, 1, '-', 1}},
        {{0, 0, '*', 0}, {0, -1, '-', 0}, {0, 1, '-', 0}, {0, -2, '-', 2}, {0, 2, '-', 2}, {-1, 0, '|', 1}, {1, 0, '|', 1}},
        {{0, 0, '*', 0}, {0, -1, '-', 0}, {0, 1, '-', 0}, {0, -2, '-', 1}, {0, 2, '-', 1}, {0, -3, '-', 2}, {0, 3, '-', 2},
            {0, -4, '-', 2}, {0, 4, '-', 2}, {-1, 0, '|', 0}, {1, 0, '|', 0}, {-2, 0, '\'', 2}, {2, 0, '.', 2}},
    };

    /** Write colours as the terminal script does (low two bits dropped), for comparisons. */
    public static boolean maskLikeTerminal = false;

    /** Whether the last frame drew a sparkle. */
    public boolean sparkled;

    private final char[] gch;
    private final float[] gvec, gvv;
    private final int ng;

    public Ascii(boolean all) {
        int count = 0;
        for (int c = 32; c < 127; c++) if (all || SYMBOLS.indexOf((char) c) >= 0) count++;
        ng = count;
        gch = new char[count];
        gvec = new float[count * 6];
        gvv = new float[count];
        int k = 0;
        for (int c = 32; c < 127; c++) {
            if (!all && SYMBOLS.indexOf((char) c) < 0) continue;
            gch[k] = (char) c;
            float vv = 0;
            for (int j = 0; j < 6; j++) {
                float v = Data.GLYPH_VEC[(c - 32) * 6 + j];
                gvec[k * 6 + j] = v;
                vv += v * v;
            }
            gvv[k] = vv;
            k++;
        }
    }

    // ------------------------------------------------------------------ x ** e as a table
    private static final Map<Double, float[]> LUTS = new HashMap<>();

    static double round3(double e) {
        return new BigDecimal(e).setScale(3, RoundingMode.HALF_EVEN).doubleValue();
    }

    static synchronized float[] lut(double e) {
        double key = round3(e);
        float[] t = LUTS.get(key);
        if (t == null) {
            t = new float[2049];
            for (int i = 0; i <= 2048; i++) t[i] = (float) Math.pow(i / 2048.0, key);
            LUTS.put(key, t);
        }
        return t;
    }

    private static float pw(float ratio, float[] lut) {
        int i = (int) (ratio * 2048.0f);
        if (i < 0) i = 0; else if (i > 2048) i = 2048;
        return lut[i];
    }

    private static int pack(float r, float g, float b) {
        float R = Math.min(Math.max(r * 255.0f + 0.5f, 0f), 255f);
        float G = Math.min(Math.max(g * 255.0f + 0.5f, 0f), 255f);
        float B = Math.min(Math.max(b * 255.0f + 0.5f, 0f), 255f);
        int c = ((int) R << 16) | ((int) G << 8) | (int) B;
        return maskLikeTerminal ? (c & 0xFCFCFC) : c;
    }

    // ------------------------------------------------------------------ scratch
    private float[] S = new float[0], E = new float[0], W = new float[0], INK = new float[0], PEAK = new float[0];
    private float[] cellG = new float[0], tmpA = new float[0], tmpB = new float[0];
    private float[] rgbW = new float[0], lumW = new float[0];

    /** max over a window of +-kr rows and +-kc columns, edges clipped. */
    static void slideMax(float[] a, int rows, int cols, int kr, int kc, float[] tmp, float[] out) {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                float m = Float.NEGATIVE_INFINITY;
                for (int y = Math.max(0, r - kr); y <= Math.min(rows - 1, r + kr); y++) m = Math.max(m, a[y * cols + c]);
                tmp[r * cols + c] = m;
            }
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                float m = Float.NEGATIVE_INFINITY;
                for (int x = Math.max(0, c - kc); x <= Math.min(cols - 1, c + kc); x++) m = Math.max(m, tmp[r * cols + x]);
                out[r * cols + c] = m;
            }
    }

    /**
     * One frame from traced samples (6 per cell, row-major cells). backdrop:
     * the sky behind the logo per sample (3 floats each) or null. ground: the
     * colour painted behind everything (linear-free sRGB 0-1).
     */
    public void make(Frame out, float[] rgbIn, float[] lumIn, float[] glint, float[] cov, int rows, int cols, int mode,
                     double contrast, float[] backdrop, boolean stars, double clock, int[] inks, double[] ground) {
        out.size(cols, rows);
        sparkled = false;
        int cells = rows * cols, n = cells * 6;
        if (rgbW.length != n * 3) {
            rgbW = new float[n * 3]; lumW = new float[n];
            S = new float[n]; E = new float[n];
            W = new float[cells]; INK = new float[cells * 3]; PEAK = new float[cells];
            cellG = new float[cells]; tmpA = new float[cells]; tmpB = new float[cells];
        }
        float[] rgb = rgbIn, lum = lumIn;
        if (backdrop != null) {
            for (int i = 0; i < cells; i++) {
                float m = cov[i * 6];
                for (int k = 1; k < 6; k++) m = Math.max(m, cov[i * 6 + k]);
                cellG[i] = m;
            }
            slideMax(cellG, rows, cols, 1, 2, tmpA, tmpB);
            for (int i = 0; i < n; i++) {
                float fade = (1.0f - cov[i]) * (1.0f - 0.85f * tmpB[i / 6]);
                int o = i * 3;
                float r = rgbIn[o] + backdrop[o] * fade, g = rgbIn[o + 1] + backdrop[o + 1] * fade, b = rgbIn[o + 2] + backdrop[o + 2] * fade;
                rgbW[o] = r; rgbW[o + 1] = g; rgbW[o + 2] = b;
                lumW[i] = r * 0.2126f + g * 0.7152f + b * 0.0722f;
            }
            rgb = rgbW;
            lum = lumW;
        }
        out.ground = ground == null ? 0 : Palette.ink(ground);
        for (int c = 0; c < cells; c++) {
            boolean on = false;
            for (int k = 0; k < 6 && !on; k++) on = cov[c * 6 + k] > 0;
            out.logo[c] = on;
        }

        if (mode == BLOCK) {
            out.block = true;
            float gr = 0, gg = 0, gb = 0;
            if (ground != null) { gr = (float) ground[0]; gg = (float) ground[1]; gb = (float) ground[2]; }
            float[] s = new float[18];
            for (int c = 0; c < cells; c++) {
                for (int k = 0; k < 6; k++) {
                    int o = (c * 6 + k) * 3;
                    float u = 1.0f - cov[c * 6 + k];
                    s[k * 3] = rgb[o] + (ground != null ? gr * u : 0f);
                    s[k * 3 + 1] = rgb[o + 1] + (ground != null ? gg * u : 0f);
                    s[k * 3 + 2] = rgb[o + 2] + (ground != null ? gb * u : 0f);
                }
                float[] t = new float[3], b = new float[3];
                for (int j = 0; j < 3; j++) {
                    t[j] = (s[j] + s[3 + j] + 0.5f * (s[6 + j] + s[9 + j])) / 3.0f;
                    b[j] = (s[12 + j] + s[15 + j] + 0.5f * (s[6 + j] + s[9 + j])) / 3.0f;
                }
                out.top[c] = pack(Math.min(Math.max(t[0], 0f), 1f), Math.min(Math.max(t[1], 0f), 1f), Math.min(Math.max(t[2], 0f), 1f));
                out.bot[c] = pack(Math.min(Math.max(b[0], 0f), 1f), Math.min(Math.max(b[1], 0f), 1f), Math.min(Math.max(b[2], 0f), 1f));
                out.chars[c] = '▀';
                out.fg[c] = out.top[c];
            }
            return;
        }
        out.block = false;
        // lum-weighted ink and the brightest sample of each cell
        for (int c = 0; c < cells; c++) {
            int o = c * 6;
            float l0 = lum[o], l1 = lum[o + 1], l2 = lum[o + 2], l3 = lum[o + 3], l4 = lum[o + 4], l5 = lum[o + 5];
            float ws = l0 + l1 + l2 + l3 + l4 + l5;
            W[c] = ws;
            float d = Math.max(ws, 1e-6f);
            for (int j = 0; j < 3; j++) {
                float v = rgb[o * 3 + j] * l0 + rgb[(o + 1) * 3 + j] * l1 + rgb[(o + 2) * 3 + j] * l2
                    + rgb[(o + 3) * 3 + j] * l3 + rgb[(o + 4) * 3 + j] * l4 + rgb[(o + 5) * 3 + j] * l5;
                INK[c * 3 + j] = v / d;
            }
            PEAK[c] = Math.max(Math.max(Math.max(l0, l1), Math.max(l2, l3)), Math.max(l4, l5));
        }
        char[] chars = out.chars;
        if (mode == RAMP) {
            float k6 = (float) (1.0 / 6.0);
            float e85 = 0.85f;
            for (int r = 0; r < rows; r++)
                for (int x = 0; x < cols; x++) {
                    int c = r * cols + x;
                    float mean = W[c] * k6;
                    double th = (BAYER[(r % 4) * 4 + (x % 4)] + 0.5) / 16.0 - 0.5;
                    float a = (float) Math.pow(mean, e85) * 9 + 0.5f;
                    int k = (int) Math.floor(a + th * 0.9);
                    k = Math.max(0, Math.min(RAMP_CHARS.length() - 1, k));
                    chars[c] = RAMP_CHARS.charAt(k);
                }
        } else {
            for (int i = 0; i < n; i++) S[i] = Math.min(lum[i] * 0.85f, 1.0f);
            enhance(rows, cols, contrast, 1.0 + 0.45 * contrast);
            for (int c = 0; c < cells; c++) {
                int o = c * 6;
                float s0 = E[o], s1 = E[o + 1], s2 = E[o + 2], s3 = E[o + 3], s4 = E[o + 4], s5 = E[o + 5];
                if (s0 == 0 && s1 == 0 && s2 == 0 && s3 == 0 && s4 == 0 && s5 == 0) {
                    chars[c] = ' ';
                    continue;
                }
                float best = Float.MAX_VALUE;
                int bi = 0;
                for (int g = 0; g < ng; g++) {
                    int v = g * 6;
                    float dot = s0 * gvec[v] + s1 * gvec[v + 1] + s2 * gvec[v + 2] + s3 * gvec[v + 3] + s4 * gvec[v + 4] + s5 * gvec[v + 5];
                    float d = dot * -2.0f + gvv[g];
                    if (d < best) { best = d; bi = g; }
                }
                chars[c] = gch[bi];
            }
        }
        // ink: the hue of the lit samples, brightness compressed but kept
        float[] l06 = lut(0.6);
        for (int c = 0; c < cells; c++) {
            float ir = INK[c * 3], ig = INK[c * 3 + 1], ib = INK[c * 3 + 2];
            float il = ir * 0.2126f + ig * 0.7152f + ib * 0.0722f;
            float target = pw(Math.max(il, PEAK[c] * 0.8f), l06) * 1.25f;
            target = Math.min(target, 1.0f);
            float k = target / Math.max(il, 1e-6f);
            float fr = ir * k, fgc = ig * k, fb = ib * k;
            float mx = Math.max(Math.max(Math.max(fr, fgc), fb), 1.0f);
            out.fg[c] = pack(fr / mx, fgc / mx, fb / mx);
        }
        if (stars && glint != null) sparkles(out, glint, rows, cols, clock, inks);
        for (int c = 0; c < cells; c++) if (chars[c] == ' ') out.fg[c] = -1;
    }

    /** Harri's contrast enhancement, S to E (directional, then global). */
    private void enhance(int rows, int cols, double eDir, double eGlob) {
        float[] ld = lut(eDir), lg = lut(eGlob);
        float eps = 1e-6f;
        float[] ext = new float[10];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int o = (r * cols + c) * 6;
                ext[0] = at(r - 1, c, 4, rows, cols); ext[1] = at(r - 1, c, 5, rows, cols);
                ext[2] = at(r, c - 1, 1, rows, cols); ext[3] = at(r, c + 1, 0, rows, cols);
                ext[4] = at(r, c - 1, 3, rows, cols); ext[5] = at(r, c + 1, 2, rows, cols);
                ext[6] = at(r, c - 1, 5, rows, cols); ext[7] = at(r, c + 1, 4, rows, cols);
                ext[8] = at(r + 1, c, 0, rows, cols); ext[9] = at(r + 1, c, 1, rows, cols);
                float gmax = 0;
                for (int i = 0; i < 6; i++) {
                    float s = S[o + i], mx = s;
                    for (int k : AFFECT[i]) mx = Math.max(mx, ext[k]);
                    float e = pw(s / Math.max(mx, eps), ld) * mx;
                    E[o + i] = e;
                    if (i == 0 || e > gmax) gmax = e;
                }
                float g = Math.max(gmax, eps);
                for (int i = 0; i < 6; i++) E[o + i] = pw(E[o + i] / g, lg) * g;
            }
    }

    private float at(int r, int c, int k, int rows, int cols) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return 0f;
        return S[(r * cols + c) * 6 + k];
    }

    /** Four-point stars on the brightest glints of the sun, twinkling. */
    private void sparkles(Frame out, float[] glint, int rows, int cols, double clock, int[] inks) {
        int cells = rows * cols;
        float gmax = 0;
        for (int c = 0; c < cells; c++) {
            float m = glint[c * 6];
            for (int k = 1; k < 6; k++) m = Math.max(m, glint[c * 6 + k]);
            cellG[c] = m;
            if (m > gmax) gmax = m;
        }
        if (gmax < 0.45f) return;
        slideMax(cellG, rows, cols, 2, 4, tmpA, tmpB);
        int np = 0;
        int[] cand = new int[16];
        for (int c = 0; c < cells; c++) {
            if (cellG[c] >= tmpB[c] && cellG[c] > 0.45f) {
                if (np == cand.length) cand = java.util.Arrays.copyOf(cand, np * 2);
                cand[np++] = c;
            }
        }
        // brightest first (stable for equal glints)
        Integer[] order = new Integer[np];
        for (int i = 0; i < np; i++) order[i] = cand[i];
        java.util.Arrays.sort(order, (a, b) -> Float.compare(cellG[b], cellG[a]));
        int limit = Math.min(5, np);
        sparkled = limit > 0;
        for (int s = 0; s < limit; s++) {
            int c = order[s];
            int y = c / cols, x = c % cols;
            double phase = EnvMap.hash(x * 0.731 + 0.5, y * 0.377 + 0.5) * 6.283;
            double size = (0.5 + 0.5 * Math.sin(clock * 4.5 + phase)) * Math.min(1.0, 0.4 + cellG[c]);
            int[][] star = STARS[Math.min(2, (int) (size * 3.0))];
            for (int[] p : star) {
                int yy = y + p[0], xx = x + p[1];
                if (yy >= 0 && yy < rows && xx >= 0 && xx < cols) {
                    out.chars[yy * cols + xx] = (char) p[2];
                    out.fg[yy * cols + xx] = inks[p[3]];
                    out.logo[yy * cols + xx] = true;
                }
            }
        }
    }
}
