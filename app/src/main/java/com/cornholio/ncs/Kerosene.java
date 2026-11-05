package com.cornholio.ncs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Joy Division's Unknown Pleasures, playing live: the last second or so of the
 * music as ridges, one behind another, the newest in front, each hiding what
 * lies behind it, the back lines fading into the dark. On every beat a cluster
 * of sharp spikes, like the pulsar's own pulse. In silence the lines lie straight.
 */
public final class Kerosene {
    static final double STEP = 0.06;
    static final int RES = 240;
    private final ArrayDeque<Object[]> hist = new ArrayDeque<>();   // (time, height, profile), newest first
    private double acc = 0.0, t = 0.0, kick = 0.0;
    private int beats = 0;
    private final PyRandom rng = PyRandom.python(23);
    private final Map<Integer, Integer> veil = new HashMap<>();
    private int veilBg = -1;

    /** cornholio's _vnoise: smooth value noise in 0..1 on an integer hash. */
    static double vnoise(double x, double y) {
        double xf = Math.floor(x), yf = Math.floor(y);
        long xi = (long) xf, yi = (long) yf;
        double fx = x - xf, fy = y - yf;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = h(xi, yi), b = h(xi + 1, yi), c = h(xi, yi + 1), d = h(xi + 1, yi + 1);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    private static double h(long a, long b) {
        long n = (a * 374761393L + b * 668265263L) & 0xFFFFFFFFL;
        n = ((n ^ (n >> 13)) * 1274126177L) & 0xFFFFFFFFL;
        return (n & 0xFFFF) / 65535.0;
    }

    private double[] profile(AudioTap.Fr fr) {
        int N = RES;
        double[] lv = fr.levels, rw = fr.raw;
        int B = lv.length;
        double tau = 2 * Math.PI;
        double e = fr.energy, bs = fr.bass;
        double ka = 2.3 * tau / N, kb = 3.6 * tau / N, kc = 1.2 * tau / N;
        double pa = -0.33 * tau * t + 1.7, pb = 0.21 * tau * t + 0.4, pc = -0.08 * tau * t + 2.9;
        double n1 = t * 1.3, n2 = t * 2.7, n3 = t * 5.3;
        double[] swell = new double[N];
        for (int jj = 0; jj < N; jj++) {
            double w = Math.sin(ka * jj + pa) * Math.sin(kb * jj + pb) + 0.6 * Math.sin(kc * jj + pc);
            swell[jj] = 0.5 + 0.5 * w / 1.6;
        }
        int M = N / 3;
        double[] coarse = new double[M + 1];
        for (int m = 0; m <= M; m++) {
            double u = (double) m / M;
            coarse[m] = (vnoise(u * 9, n1) - 0.5) + 0.5 * (vnoise(u * 21 + 7, n2) - 0.5) + 0.25 * (vnoise(u * 47 + 3, n3) - 0.5);
        }
        double[] grain = new double[N];
        for (int jj = 0; jj < N; jj++)
            grain[jj] = coarse[jj / 3] + (coarse[jj / 3 + 1] - coarse[jj / 3]) * (jj % 3) / 3;
        double[] prof = new double[N];
        double scroll = t * 0.012;
        double bassAt = Double.NaN;
        for (int b = 0; b < B; b++) {
            double pos = pmod(0.03 + 0.94 * (b + 0.5) / B + 0.22 * (vnoise(b * 0.53 + 11.0, t * 0.15) - 0.5) + scroll, 1.0);
            if (b == 2) bassAt = pos;
            double v = 0.5 * lv[b] + 0.5 * rw[b];
            if (v < 0.04) continue;
            double amp = Math.pow(v, 1.75);
            double width = (0.006 + 0.016 * (1 - (double) b / B)) * N;
            double split = (0.4 + 1.6 * vnoise(b * 1.7 + 3.0, t * 0.9)) * width;
            double[][] cs = {{pos * N - split * 0.5, amp}, {pos * N + split * 0.5, amp * 0.7}};
            for (double[] ca : cs) {
                double c = ca[0], a = ca[1];
                int r = (int) (width * 3) + 1;
                double inv = 1.0 / width;
                for (int jj = (int) c - r; jj <= (int) c + r; jj++) {
                    double d = (jj - c) * inv;
                    prof[Math.floorMod(jj, N)] += a * Math.exp(-d * d);
                }
            }
        }
        if (fr.beats != beats && !fr.silent) {               // a pulse, near the bass
            beats = fr.beats;
            kick = 1.0;
            double p = pmod((!Double.isNaN(bassAt) ? bassAt : rng.random()) + rng.uniform(-0.12, 0.12), 1.0);
            int cnt = rng.randint(3, 5);
            for (int q = 0; q < cnt; q++) {
                double c = (p + rng.uniform(-0.035, 0.035)) * N;
                double width = rng.uniform(0.003, 0.009) * N;
                double amp = rng.uniform(0.5, 1.0) * (0.35 + bs);
                int r = (int) (width * 3) + 1;
                for (int jj = (int) c - r; jj <= (int) c + r; jj++) {
                    double d = (jj - c) / width;
                    prof[Math.floorMod(jj, N)] += amp * Math.exp(-d * d);
                }
            }
        }
        double[] out = new double[N];
        for (int jj = 0; jj < N; jj++) {
            double sw = swell[jj];
            double v = prof[jj] * (0.12 + 1.25 * sw * sw) * (1 + 0.8 * grain[jj]) + e * 0.04 * grain[jj];
            out[jj] = v > 0 ? 1 - Math.exp(-v * 1.15) : 0.0;
        }
        return out;
    }

    static double pmod(double a, double b) {
        double r = a % b;
        if (r != 0 && (r < 0) != (b < 0)) r += b;
        return r;
    }

    /** The glyphs of a line through one column, from its height at the left and right edges. */
    static int ridge(double yl, double yr, int[] rows, char[] glyphs) {
        double s = yr - yl;
        if (-0.45 < s && s < 0.45) {
            double ym = (yl + yr) * 0.5;
            int r = (int) ym;
            double f = ym - r;
            if (f < 0.3) { rows[0] = r - 1; glyphs[0] = '_'; }
            else if (f < 0.68) { rows[0] = r; glyphs[0] = '-'; }
            else { rows[0] = r; glyphs[0] = '_'; }
            return 1;
        }
        char g = s < 0 ? '/' : '\\';
        if (-1.6 < s && s < 1.6) {
            rows[0] = (int) ((yl + yr) * 0.5);
            glyphs[0] = g;
            return 1;
        }
        double lo = s < 0 ? yr : yl, hi = s < 0 ? yl : yr;
        int r0 = (int) lo, r1 = (int) hi;
        int end = hi - r1 > 0.45 ? r1 : r1 - 1;
        int k = 0;
        rows[k] = r0;
        glyphs[k++] = g;
        for (int r = r0 + 1; r <= end; r++) {
            rows[k] = r;
            glyphs[k++] = '|';
        }
        return k;
    }

    boolean draw(Cells cv, Theme C, double now, double dt, double dim, AudioTap.Fr fr, int x0, int x1, int top, int bottom,
                 double[][] mask) {
        int area = bottom - top + 1;
        t += dt;
        kick *= Math.exp(-dt * 5);
        acc += dt;
        if (hist.isEmpty() || acc >= STEP) {
            acc = Math.min(STEP, Math.max(0.0, acc - STEP));
            double[] prof = profile(fr);
            hist.addFirst(new Object[]{t, 0.86 + 0.28 * kick, prof});
            while (hist.size() > 40) hist.removeLast();
        }
        int K = Math.max(6, Math.min(20, (int) (area * 0.8)));
        double gap = (area - 2) / (K + 4.0);
        double A0 = Math.max(3.0, Math.min(area * 0.55, gap * 7.0));
        int span = Math.max(1, x1 - x0);
        int N = RES;
        double wind = 0.05 * Math.sin(t * 0.07 + 1.3);
        double kd = 1 - dim;
        int W = cv.w;
        int bgc = C.bg;
        int[] hor = new int[W];
        java.util.Arrays.fill(hor, bottom + 1);
        List<int[]> cells = new ArrayList<>();          // x, y, glyph, colour
        int[] rr = new int[256];
        char[] gg = new char[256];
        Iterator<Object[]> it = hist.iterator();
        int lines = Math.min(K, hist.size());
        for (int i = 0; i < lines; i++) {
            Object[] hrec = it.next();
            double tc = (Double) hrec[0], tall = (Double) hrec[1];
            double[] prof = (double[]) hrec[2];
            double z = (double) i / (K - 1);
            int yb = (int) (bottom - i * gap + 0.5);
            double A = A0 * (1 - 0.25 * z) * tall;
            double shift = (t - tc) * wind * N;
            double[] edges = new double[span + 1];
            for (int e = 0; e <= span; e++) {
                double q = pmod((double) e / span * N + shift, N);
                int k = (int) q;
                double f = q - k;
                if (k >= N) k -= N;
                double hh = A * (prof[k] * (1 - f) + prof[(k + 1) % N] * f);
                edges[e] = yb - (hh > 0.4 ? hh : 0.0);
            }
            double fog = 0.78 * Math.pow(z, 1.2);
            int line = Theme.mix(C.accent, bgc, fog);
            int hot = Theme.mix(C.warn, bgc, fog);
            for (int c = 0; c < span; c++) {
                int x = x0 + c;
                double yl = edges[c], yr = edges[c + 1];
                int limit = hor[x];
                int best = limit;
                int nr = ridge(yl, yr, rr, gg);
                for (int q = 0; q < nr; q++) {
                    int cy = rr[q];
                    if (cy < best) best = cy;
                    if (top <= cy && cy < limit) {
                        double lift = A > 0 ? (yb - cy) / A : 0.0;
                        int col = lift <= 0.3 ? line : Theme.mix(line, hot, Math.min(1.0, (lift - 0.3) / 0.6));
                        cells.add(new int[]{x, cy, gg[q], col});
                    }
                }
                hor[x] = best;
            }
        }
        for (int x = 0; x < x0; x++) hor[x] = hor[x0];
        for (int x = x1; x < W; x++) hor[x] = hor[x1 - 1];
        if (veilBg != bgc) {
            veil.clear();
            veilBg = bgc;
        }
        int[] vc = new int[W * (bottom + 2)];
        int cnt = 0;
        for (int x = 0; x < W; x++) for (int y = Math.max(top, hor[x]); y <= bottom; y++) vc[cnt++] = y * W + x;
        Mirror.veil(cv, bgc, vc, cnt, 0.86, veil);
        char[] chs = cv.ch;
        int[] fgs = cv.fg;
        for (int[] cl : cells) {
            int x = cl[0], y = cl[1];
            double q = mask[y][x] * kd;
            if (q > 0.3 * kd) {
                int ii = y * W + x;
                chs[ii] = (char) cl[2];
                fgs[ii] = q >= 1 ? cl[3] : Theme.mix(bgc, cl[3], q);
            }
        }
        return true;
    }
}
