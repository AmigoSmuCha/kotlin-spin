package com.cornholio.ncs;

import java.util.HashMap;
import java.util.Map;

/**
 * Bass in the middle, the highs out to the sides, the bars mirrored in water:
 * under the waterline they stand upside down (M#M reads W#W), shorter and
 * dimmer, swaying in ripples that run faster on the bass. In silence the
 * water is a still line.
 */
public final class Mirror {
    static final double SQUASH = 0.75;
    final NcsBars bars = new NcsBars();
    private double t = 0.0;
    private final Map<Integer, Integer> veil = new HashMap<>();
    private int veilBg = -1;

    /** reading a glyph upside down, for reflections: M#M to W#W, dMb to qWp */
    static char flip(char c) {
        switch (c) {
            case 'M': return 'W';
            case 'W': return 'M';
            case 'd': return 'q';
            case 'q': return 'd';
            case 'b': return 'p';
            case 'p': return 'b';
            case 'P': return 'b';
            case '.': return '\'';
            case '\'': return '.';
            case ',': return '`';
            case '`': return ',';
            case '^': return 'v';
            case 'v': return '^';
            case '/': return '\\';
            case '\\': return '/';
            default: return c;
        }
    }

    /** Dims the field's glyphs in the given cells toward the background. */
    static void veil(Cells cv, int bgc, int[] cells, int count, double amount, Map<Integer, Integer> cache) {
        char[] ch = cv.ch;
        int[] fg = cv.fg;
        for (int k = 0; k < count; k++) {
            int ii = cells[k];
            if (ch[ii] != ' ') {
                int c = fg[ii];
                Integer v = cache.get(c);
                if (v == null) {
                    v = Theme.mix(c, bgc, amount);
                    cache.put(c, v);
                }
                fg[ii] = v;
            }
        }
    }

    int draw(Cells cv, Theme C, double now, double dt, double dim, AudioTap.Fr fr, int x0, int x1, int top, int bottom,
             double[][] mask) {
        int area = bottom - top + 1;
        int horizon = top + (int) (area * 0.62);
        int[] geo = bars.draw(cv, C, now, dt, dim, fr, x0, x1, horizon, horizon - top + 1, mask, true);
        if (geo == null) return horizon;
        int bw = geo[0], gap = geo[1], n = geo[2], left = geo[3];
        t += dt * (0.3 + 1.8 * fr.bass);
        int wl = horizon + 1;
        int depth = bottom - horizon;
        double kd = 1 - dim;
        int W = cv.w;
        char[] chs = cv.ch;
        int[] fgs = cv.fg;
        int bgc = C.bg;
        if (veilBg != bgc) {
            veil.clear();
            veilBg = bgc;
        }
        int[] cells = new int[Math.max(0, bottom + 1 - wl) * Math.max(0, x1 - x0)];
        int cnt = 0;
        for (int y = wl; y <= bottom; y++) for (int x = x0; x < x1; x++) cells[cnt++] = y * W + x;
        veil(cv, bgc, cells, cnt, 0.78, veil);
        int surf = Theme.mix(bgc, C.accent2, 0.2 * kd);
        double[] mrow0 = mask[wl];
        int a0 = wl * W, off = (int) (t * 3);
        for (int x = x0; x < x1; x++) {
            if (Math.floorMod(x + off, 3) == 0 && mrow0[x] > 0.3) {
                chs[a0 + x] = '-';
                fgs[a0 + x] = surf;
            }
        }
        int foot = C.accent, tip = C.warn;
        int maxH = horizon - top + 1;
        double amp = 0.2 + 1.3 * fr.bass;
        String[][] rowsOf = new String[n][];
        for (int i = 0; i < n; i++) rowsOf[i] = bars.rows(i, bw);
        double squash = Math.min(SQUASH, depth / Math.max(1.0, maxH));
        for (int k = 0; k < depth; k++) {
            int y = wl + k;
            int src = (int) (k / squash);
            double fade = 0.5 * Math.pow(1 - (double) k / (depth + 1), 1.1) * kd;
            double deep = 0.5 + (double) k / Math.max(1, depth);
            int col = Theme.mix(bgc, Theme.mix(foot, tip, Math.min(1.0, src / Math.max(1.0, maxH - 1))), fade);
            double[] mrow = mask[y];
            int a = y * W;
            double glint = 0.86 - 0.06 * k;
            for (int i = 0; i < n; i++) {
                String[] rows = rowsOf[i];
                if (src >= rows.length) continue;
                int x = left + i * (bw + gap);
                int sh = (int) Math.rint(Math.sin(k * 1.25 + t * 2.7 + x * 0.045) * amp * deep);
                String g = rows[src];
                for (int dx = 0; dx < g.length(); dx++) {
                    int gx = x + dx + sh;
                    if (!(gx >= 0 && gx < W) || Math.sin(gx * 0.31 + k * 2.3 - t * 1.9) > glint) continue;
                    double q = mrow[gx];
                    if (q > 0.3) {
                        chs[a + gx] = flip(g.charAt(dx));
                        fgs[a + gx] = q >= 1 ? col : Theme.mix(bgc, col, q);
                    }
                }
            }
        }
        return horizon;
    }
}
