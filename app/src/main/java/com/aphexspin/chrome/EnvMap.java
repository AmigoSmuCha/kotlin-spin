package com.aphexspin.chrome;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The environments the logo reflects: the sky above the clouds (chrome, gold,
 * gloss) and a studio of softboxes (satin, obsidian, bone), each baked into an
 * octahedral map per palette, as aphex_spin.py's EnvMap. Also the value noise
 * and the asciify-engine hash they are built from.
 */
public final class EnvMap {
    private EnvMap() {}

    public static final int SKY = 0, STUDIO = 1;
    public static final int N = 256;
    public static final int W = N + 1;              // padded by one texel on the far edges

    // ------------------------------------------------------------------ hash and noise
    /** asciify-engine's shader hash (inputs >= 0 here, as in the script). */
    public static double hash(double x, double y) {
        double px = pmod(x * 123.34, 1.0);
        double py = pmod(y * 456.21, 1.0);
        double d = px * (px + 45.32) + py * (py + 45.32);
        return pmod((px + d) * (py + d), 1.0);
    }

    /** numpy's mod: the result takes the divisor's sign. */
    static double pmod(double a, double b) {
        double m = a % b;
        if (m != 0 && (m < 0) != (b < 0)) m += b;
        return m;
    }

    private static float[] noise;

    /** Tileable value noise, 256x256, two octaves of the hash (baked once). */
    static synchronized float[] noise() {
        if (noise != null) return noise;
        int n = 64, S = n * 4;
        float[] T = new float[S * S];
        for (int yi = 0; yi < S; yi++) {
            for (int xi = 0; xi < S; xi++) {
                double x = xi / 4.0, y = yi / 4.0;
                double out = 0.0;
                for (int o = 0; o < 2; o++) {
                    double scale = o == 0 ? 1.0 : 2.0, amp = o == 0 ? 0.65 : 0.35;
                    double xs = x * scale, ys = y * scale;
                    double ix = Math.floor(xs), iy = Math.floor(ys);
                    double fx = xs - ix, fy = ys - iy;
                    double ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
                    double p = n * scale;
                    double h00 = hash(pmod(ix, p), pmod(iy, p));
                    double h10 = hash(pmod(ix + 1, p), pmod(iy, p));
                    double h01 = hash(pmod(ix, p), pmod(iy + 1, p));
                    double h11 = hash(pmod(ix + 1, p), pmod(iy + 1, p));
                    double v = (h00 * (1 - ux) + h10 * ux) * (1 - uy) + (h01 * (1 - ux) + h11 * ux) * uy;
                    out += v * amp;
                }
                T[yi * S + xi] = (float) out;
            }
        }
        noise = T;
        return T;
    }

    /** Smooth noise in [0, 1], one unit per lattice cell, tiling every 64. */
    public static double vnoise(double x, double y) {
        float[] T = noise();
        int n = 256;
        double fx = pmod(x * 4.0, n), fy = pmod(y * 4.0, n);
        if (fx >= n) fx -= n;
        if (fy >= n) fy -= n;
        int ix = (int) fx, iy = (int) fy;
        double tx = fx - ix, ty = fy - iy;
        int ix1 = (ix + 1) % n, iy1 = (iy + 1) % n;
        double a = T[iy * n + ix], b = T[iy * n + ix1], c = T[iy1 * n + ix], d = T[iy1 * n + ix1];
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
    }

    static double smoothstep(double a, double b, double x) {
        double t = (x - a) / (b - a);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return t * t * (3.0 - 2.0 * t);
    }

    static double band(double v, double a, double b, double s) {
        return smoothstep(a - s, a + s, v) * (1.0 - smoothstep(b - s, b + s, v));
    }

    // ------------------------------------------------------------------ environments
    /** Above the clouds: a cold zenith, streaks of cloud, a glowing horizon, a sea of cloud below. */
    public static void sky(double x, double y, double z, Palette P, double[] out) {
        double[][] c = P.c;
        double up = Math.min(Math.max(y, 0.0), 1.0);
        double down = Math.min(Math.max(-y, 0.0), 1.0);
        double t1 = smoothstep(0.0, 0.32, up), t2 = smoothstep(0.30, 0.95, up);
        double k = 1.0 / (up + 0.18);
        double cl = vnoise(x * k * 1.3 + 11.0, z * k * 0.45) * 0.7 + vnoise(x * k * 3.1, z * k * 1.2 + 5.0) * 0.3;
        cl = smoothstep(0.50, 0.82, cl) * (1.0 - smoothstep(0.45, 0.85, up));
        double k2 = 1.0 / (down + 0.10);
        double nn = vnoise(x * k2 * 0.9 + 3.0, z * k2 * 0.9) * 0.65 + vnoise(x * k2 * 2.2, z * k2 * 2.2 + 9.0) * 0.35;
        double sea = smoothstep(0.30, 0.78, nn);
        double haze = Math.exp(-down / 0.11);
        double yy = y / 0.05;
        double glow = Math.exp(-(yy * yy)) * 1.4;
        for (int j = 0; j < 3; j++) {
            double v;
            if (y >= 0) {
                double s = c[Palette.HOR][j] + (c[Palette.MID][j] - c[Palette.HOR][j]) * t1;
                s = s + (c[Palette.ZEN][j] - s) * t2;
                s = s + (c[Palette.CLOUD][j] - s) * (cl * 0.8);
                v = s;
            } else {
                double gr = c[Palette.SEA][j] + c[Palette.SEA_HI][j] * sea;
                gr = gr + (c[Palette.HAZE][j] - gr) * haze;
                v = gr;
            }
            out[j] = v + glow * c[Palette.GLOW][j];
        }
    }

    /** A dim studio with three softboxes, seen in reflection. */
    public static void studio(double x, double y, double z, Palette P, double[] out) {
        double[][] c = P.c;
        double base = 0.035 + 0.11 * smoothstep(-0.35, 0.95, y);
        double top = band(y, 0.60, 1.05, 0.07) * band(x, -0.58, 0.58, 0.07) * 3.4;
        double left = band(x, -1.05, -0.60, 0.06) * band(y, -0.30, 0.62, 0.07) * smoothstep(-0.5, 0.0, z) * 2.4;
        double right = band(x, 0.64, 1.05, 0.06) * band(y, -0.48, 0.40, 0.07) * 1.5;
        double front = band(x, -0.80, -0.08, 0.16) * band(y, 0.02, 0.62, 0.16) * smoothstep(0.3, 0.8, z) * 0.9;
        double floor = 0.05 * smoothstep(-0.1, -0.9, y);
        for (int j = 0; j < 3; j++) {
            out[j] = base * c[Palette.ST_BASE][j] + top * c[Palette.ST_TOP][j] + left * c[Palette.ST_LEFT][j]
                + right * c[Palette.ST_RIGHT][j] + front * c[Palette.ST_FRONT][j] + floor * c[Palette.ST_FLOOR][j];
        }
    }

    public static void env(int kind, double x, double y, double z, Palette P, double[] out) {
        if (kind == SKY) sky(x, y, z, P, out); else studio(x, y, z, P, out);
    }

    // ------------------------------------------------------------------ baked maps
    private static final Map<String, float[]> CACHE = new LinkedHashMap<String, float[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
            return size() > 8;          // four palettes' worth of both kinds
        }
    };

    /** The baked map of one environment for one base palette (W x W x 3 floats). */
    public static float[] get(int kind, String palette) {
        String key = kind + "/" + palette;
        synchronized (CACHE) {
            float[] t = CACHE.get(key);
            if (t != null) return t;
        }
        float[] t = bake(kind, Palette.get(palette));
        synchronized (CACHE) {
            CACHE.put(key, t);
        }
        return t;
    }

    /** Whether both of a palette's maps are baked (a cross-fade can start at once). */
    public static boolean ready(String palette) {
        synchronized (CACHE) {
            return CACHE.containsKey(SKY + "/" + palette) && CACHE.containsKey(STUDIO + "/" + palette);
        }
    }

    private static java.util.concurrent.ExecutorService baker;

    /** Bake a palette's maps on a quiet background thread, so changing to it never stutters. */
    public static synchronized void prefetch(final String palette) {
        if (ready(palette)) return;
        if (baker == null) {
            baker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "aphex-bake");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        baker.execute(() -> {
            get(SKY, palette);
            get(STUDIO, palette);
        });
    }

    static float[] bake(int kind, Palette P) {
        float[] T = new float[W * W * 3];
        double[] out = new double[3];
        double[] u = new double[N];
        for (int i = 0; i < N; i++) u[i] = (i + 0.5) / N * 2.0 - 1.0;
        for (int iy = 0; iy < N; iy++) {
            for (int ix = 0; ix < N; ix++) {
                double U = u[ix], V = u[iy];
                double Z = 1.0 - Math.abs(U) - Math.abs(V);
                double X = Z < 0 ? (1.0 - Math.abs(V)) * Math.signum(U) : U;
                double Y = Z < 0 ? (1.0 - Math.abs(U)) * Math.signum(V) : V;
                double l = Math.sqrt(X * X + Y * Y + Z * Z);
                env(kind, X / l, Y / l, Z / l, P, out);
                int o = (iy * W + ix) * 3;
                T[o] = (float) out[0];
                T[o + 1] = (float) out[1];
                T[o + 2] = (float) out[2];
            }
        }
        // edge padding: the last column and row repeat their neighbours
        for (int iy = 0; iy < N; iy++) System.arraycopy(T, (iy * W + N - 1) * 3, T, (iy * W + N) * 3, 3);
        System.arraycopy(T, (N - 1) * W * 3, T, N * W * 3, W * 3);
        return T;
    }

    /** dst = sum of w[i] * maps[i] (a palette cross-fade). */
    public static void blend(float[][] maps, double[] w, float[] dst) {
        int len = dst.length;
        float w0 = (float) w[0];
        float[] m0 = maps[0];
        for (int i = 0; i < len; i++) dst[i] = w0 * m0[i];
        for (int k = 1; k < maps.length; k++) {
            float wk = (float) w[k];
            float[] mk = maps[k];
            for (int i = 0; i < len; i++) dst[i] += wk * mk[i];
        }
    }

    /** Bilinear lookup of direction r (float, as the script's float32 maths) into out. */
    public static void lookup(float[] T, float rx, float ry, float rz, float[] out) {
        float s = Math.abs(rx) + Math.abs(ry) + Math.abs(rz) + 1e-12f;
        float px = rx / s, py = ry / s;
        float qx, qy;
        if (rz < 0) {
            qx = (1.0f - Math.abs(py)) * Math.signum(px);
            qy = (1.0f - Math.abs(px)) * Math.signum(py);
        } else {
            qx = px;
            qy = py;
        }
        float fx = (qx * 0.5f + 0.5f) * N - 0.5f;
        float fy = (qy * 0.5f + 0.5f) * N - 0.5f;
        fx = Math.min(Math.max(fx, 0.0f), N - 1.0f);
        fy = Math.min(Math.max(fy, 0.0f), N - 1.0f);
        int ix = (int) fx, iy = (int) fy;
        float tx = fx - ix, ty = fy - iy;
        int a = (iy * W + ix) * 3, b = a + 3, c = a + W * 3, d = c + 3;
        for (int j = 0; j < 3; j++) {
            float av = T[a + j];
            float top = av + (T[b + j] - av) * tx;
            float cv = T[c + j];
            out[j] = top + (cv + (T[d + j] - cv) * tx - top) * ty;
        }
    }
}
