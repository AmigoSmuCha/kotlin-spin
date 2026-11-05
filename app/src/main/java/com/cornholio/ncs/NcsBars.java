package com.cornholio.ncs;

/**
 * The NCS bars: wide ASCII bars on a floor, quick to rise, falling with
 * gravity, the row kicking on every beat. When a bar drops, its peak hangs
 * above it a moment, then falls after it. In silence: a dotted floor.
 */
public final class NcsBars {
    static final double HOLD = 0.45;
    double[] h = new double[0], v = new double[0], pk = new double[0], pv = new double[0], hold = new double[0];
    private int beats = 0;
    private double kick = 0.0;

    static String body(int bw) { return bw == 2 ? "88" : "M#M"; }
    static String cap(int bw) { return bw == 2 ? "dP" : "dMb"; }
    static String half(int bw) { return bw == 2 ? ".." : ".0."; }
    static String rest(int bw) { return bw == 2 ? ".." : "..."; }
    static String peak(int bw) { return bw == 2 ? "--" : "---"; }

    /** The bands over n places: bass on the left, or (mirrored) bass in the middle. */
    static double[] spread(double[] levels, int n, boolean mirrored) {
        int B = levels.length;
        double[] out = new double[n];
        double half = Math.max(1e-9, (n - 1) / 2.0);
        for (int i = 0; i < n; i++) {
            double u = mirrored ? Math.abs(i - half) / half : (double) i / Math.max(1, n - 1);
            double q = Math.min(1.0, u) * (B - 1);
            int j = (int) q;
            double k = q - j;
            out[i] = levels[j] * (1 - k) + levels[Math.min(B - 1, j + 1)] * k;
        }
        return out;
    }

    /** Every bar lifts its neighbours, less with distance: ridges, not a comb. */
    static double[] monstercat(double[] raw) {
        double falloff = 1.55;
        int reach = 8;
        double[] sm = raw.clone();
        int n = raw.length;
        for (int i = 0; i < n; i++) {
            double r = raw[i];
            if (r <= 0.02) continue;
            for (int j = Math.max(0, i - reach); j < Math.min(n, i + reach + 1); j++) {
                double v = r / Math.pow(falloff, Math.abs(i - j));
                if (v > sm[j]) sm[j] = v;
            }
        }
        return sm;
    }

    /** bar width, gap, count and left edge for the span x0..x1 */
    static int[] layout(int x0, int x1) {
        int span = x1 - x0;
        int bw = span >= 90 ? 3 : 2, gap = 1;
        int n = (span + gap) / (bw + gap);
        int left = x0 + Math.floorDiv(span - (n * (bw + gap) - gap), 2);
        return new int[]{bw, gap, n, left};
    }

    void step(AudioTap.Fr fr, double now, double dt, int n, double maxH, boolean mirrored) {
        double[] sm = monstercat(spread(fr.levels, n, mirrored));
        if (h.length != n) {
            h = new double[n]; v = new double[n]; pk = new double[n]; pv = new double[n]; hold = new double[n];
        }
        if (fr.beats != beats) {
            beats = fr.beats;
            kick = 1.0;
        }
        kick *= Math.exp(-dt * 6);
        double scale = maxH * (0.92 + 0.08 * kick);
        double rise = 1 - Math.exp(-dt * 42);
        for (int i = 0; i < n; i++) {
            double target = Math.pow(sm[i], 0.7) * scale;
            double hh = h[i], vv = v[i];
            if (target >= hh) {
                hh += (target - hh) * rise;
                vv = 0.0;
            } else {
                vv += 38.0 * dt;
                hh = Math.max(target, hh - vv * dt);
            }
            h[i] = hh;
            v[i] = vv;
            if (hh >= pk[i]) {
                pk[i] = hh;
                pv[i] = 0.0;
                hold[i] = now + HOLD;
            } else if (now > hold[i]) {
                pv[i] += 16.0 * dt;
                pk[i] = Math.max(hh, pk[i] - pv[i] * dt);
            }
        }
    }

    String[] rows(int i, int bw) {
        double hh = h[i];
        int full = (int) hh;
        double frac = hh - (int) hh;
        int extra = (frac >= 0.45 || (full == 0 && frac >= 0.25)) ? 1 : 0;
        String[] rows = new String[full + extra];
        for (int k = 0; k < full; k++) rows[k] = body(bw);
        if (full > 0) rows[full - 1] = cap(bw);
        if (extra == 1) rows[full] = half(bw);
        return rows;
    }

    /** Returns bw, gap, n, left, or null when there is no room. */
    int[] draw(Cells cv, Theme C, double now, double dt, double dim, AudioTap.Fr fr, int x0, int x1, int base,
               double maxH, double[][] mask, boolean mirrored) {
        if (maxH < 2 || x1 - x0 < 12) return null;
        int[] geo = layout(x0, x1);
        int bw = geo[0], gap = geo[1], n = geo[2], left = geo[3];
        step(fr, now, dt, n, maxH, mirrored);
        int foot = C.accent, tip = C.warn, bgc = C.bg;
        double kd = 1 - dim;
        double top = Math.max(1.0, maxH - 1);
        int nc = (int) maxH + 2;
        int[] cols = new int[nc];
        for (int k = 0; k < nc; k++) cols[k] = Theme.mix(bgc, Theme.mix(foot, tip, Math.min(1.0, k / top)), kd);
        int W = cv.w;
        char[] chs = cv.ch;
        int[] fgs = cv.fg;
        int restc = Theme.mix(bgc, foot, 0.3 * kd);
        for (int i = 0; i < n; i++) {
            int x = left + i * (bw + gap);
            String[] rows = rows(i, bw);
            if (rows.length == 0) {
                double[] mrow = mask[base];
                int a = base * W;
                String r = rest(bw);
                for (int dx = 0; dx < r.length(); dx++) {
                    if (x + dx >= 0 && x + dx < W && mrow[x + dx] > 0.3) {
                        chs[a + x + dx] = r.charAt(dx);
                        fgs[a + x + dx] = restc;
                    }
                }
            }
            for (int k = 0; k < rows.length; k++) {
                int y = base - k;
                if (y < 1) break;
                double[] mrow = mask[y];
                int a = y * W;
                for (int gx = x + bw; gx < x + bw + gap; gx++) {
                    if (gx >= 0 && gx < W && mrow[gx] > 0.3) {
                        chs[a + gx] = ' ';
                        fgs[a + gx] = bgc;
                    }
                }
                int col = cols[Math.min(k, cols.length - 1)];
                String glyphs = rows[k];
                for (int dx = 0; dx < glyphs.length(); dx++) {
                    int xx = x + dx;
                    if (xx >= 0 && xx < W) {
                        double q = mrow[xx];
                        if (q > 0.3) {
                            chs[a + xx] = glyphs.charAt(dx);
                            fgs[a + xx] = q >= 1 ? col : Theme.mix(bgc, col, q);
                        }
                    }
                }
            }
            int pr = (int) pk[i];
            if (pk[i] - h[i] >= 1.0 && pr >= rows.length) {
                int y = base - pr;
                if (1 <= y) {
                    int col = Theme.mix(bgc, tip, Math.min(1.0, 0.55 + 0.45 * (hold[i] > now ? 1 : 0)) * kd);
                    double[] mrow = mask[y];
                    int a = y * W;
                    String p = peak(bw);
                    for (int dx = 0; dx < p.length(); dx++) {
                        int xx = x + dx;
                        if (xx >= 0 && xx < W) {
                            double q = mrow[xx];
                            if (q > 0.3) {
                                chs[a + xx] = p.charAt(dx);
                                fgs[a + xx] = q >= 1 ? col : Theme.mix(bgc, col, q);
                            }
                        }
                    }
                }
            }
        }
        return geo;
    }
}
