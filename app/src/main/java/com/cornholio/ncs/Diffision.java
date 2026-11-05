package com.cornholio.ncs;

/**
 * NCS's fifth look: Geiss's Reaction Diffusion 3 (MilkDrop 2's preset, on
 * MilkDrop's own engine) as cornholio runs it with numpy: a grey canvas at
 * twice the screen's half-block pixels each way, standing for one 3.2 times
 * the screen; the warp reads the last frame through the preset's zoom and
 * rotation, sharpened against its blur ("spots and stripes over time"),
 * grained by the treble; the comp shader palettises it in the theme's colours.
 * Float32 throughout, operation for operation, so it matches cornholio's numpy.
 */
public final class Diffision {

    // ------------------------------------------------------------------ MilkDrop's bass, mid and treb
    static final class MilkSound {
        final int[][] bk = new int[3][];
        final double[][] bw = new double[3][];
        double[] avg = new double[3], lng = new double[3], att = {1.0, 1.0, 1.0};
        int frame = 0;

        MilkSound() {
            int R = AudioTap.RATE, N = AudioTap.N;
            for (int b = 0; b < 3; b++) {
                double lo = 512.0 * b / 6, hi = 512.0 * (b + 1) / 6;
                int[] ks = new int[N / 2];
                double[] ws = new double[N / 2];
                int c = 0;
                for (int k = 0; k < N / 2; k++) {
                    double i = (double) k * R / N / 11025.0 * 512;
                    if (lo <= i && i < hi) {
                        ks[c] = k;
                        ws[c] = -0.02 * Math.log((512 - i) / 512);
                        c++;
                    }
                }
                bk[b] = java.util.Arrays.copyOf(ks, c);
                bw[b] = java.util.Arrays.copyOf(ws, c);
            }
        }

        void feed(double[] mags, double dt) {
            double k = Math.max(0.0, dt) * 30.0;
            for (int i = 0; i < 3; i++) {
                double imm = 0.0;
                if (mags != null) for (int q = 0; q < bk[i].length; q++) imm += mags[bk[i][q]] * bw[i][q];
                double rate = Math.pow(imm > avg[i] ? 0.2 : 0.5, k);
                avg[i] = avg[i] * rate + imm * (1 - rate);
                if (mags != null) {
                    rate = Math.pow(frame < 50 ? 0.9 : 0.992, k);
                    lng[i] = lng[i] * rate + imm * (1 - rate);
                }
                double la = lng[i];
                att[i] = Math.abs(la) < 0.001 ? 1.0 : avg[i] / la;
            }
            if (mags != null) frame++;
        }
    }

    static Object[] gauss(double sigma) {
        int r = Math.max(1, (int) (sigma * 2.5 + 0.5));
        double[] k = new double[2 * r + 1];
        double s = 0;
        for (int x = -r; x <= r; x++) {
            double q = x / sigma;
            k[x + r] = Math.exp(-0.5 * (q * q));
            s += k[x + r];
        }
        for (int i = 0; i < k.length; i++) k[i] = k[i] / s;
        return new Object[]{r, k};
    }

    // ------------------------------------------------------------------ the canvas (cornholio's _Rd3 + _NpCanvas)
    static final class Canvas {
        static final double ZOOM = 1.009006, B1ED = 0.25, DOG = 0.3;
        static final double[] SIG = {3.24, 7.2};
        static final double KEEP = 0.9 * (0.8 + 0.2 * 1.10);
        static final double NOISE = 122 / 256.0 * (0.8 + 0.2 * 1.10) * Math.sqrt(0.32 * 0.32 + 0.49 * 0.49 + 0.29 * 0.29) / 1.10;

        final int W, H, ss, DW, DH, m, h1, w1, h2, w2, Wp, nn;
        final double S, ax, ay;
        final double[] us, vs;
        double rg = 0.0, bassThresh = 0.0, dxRes = 0.0, dyRes = 0.0;
        final int r1, r2;
        final float[] k1, k2;
        final PyRandom rng;
        float[] main, shown, blur1, blur2, b2up;
        final float[] edge;
        final int[] urA, urB, ucA, ucB;
        final float[] urF, ucF;
        final float[] noise;
        final int noiseW;

        Canvas(int DW, int DH, int ss, double virt, int seed, Canvas old) {
            this.DW = DW;
            this.DH = DH;
            this.ss = ss;
            W = DW * ss;
            H = DH * ss;
            S = virt / ss;
            ax = H > W ? (double) W / H : 1.0;
            ay = W > H ? (double) H / W : 1.0;
            us = new double[W];
            vs = new double[H];
            for (int x = 0; x < W; x++) us[x] = (x + 0.5) / W;
            for (int y = 0; y < H; y++) vs[y] = (y + 0.5) / H;
            m = Math.max(4, (int) Math.ceil(0.06 * Math.max(W, H)));
            double s1 = SIG[0] / S, s2 = SIG[1] / S;
            Object[] g1 = gauss(Math.max(0.45, Math.sqrt(Math.max(0.0, s1 * s1 - 0.25))) / 2);
            Object[] g2 = gauss(Math.max(0.45, Math.sqrt(Math.max(0.0, s2 * s2 - s1 * s1 - 1.0))) / 4);
            r1 = (Integer) g1[0];
            r2 = (Integer) g2[0];
            double[] d1 = (double[]) g1[1], d2 = (double[]) g2[1];
            k1 = new float[d1.length];
            k2 = new float[d2.length];
            for (int i = 0; i < d1.length; i++) k1[i] = (float) d1[i];
            for (int i = 0; i < d2.length; i++) k2[i] = (float) d2[i];
            h1 = (H + 1) / 2;
            w1 = (W + 1) / 2;
            h2 = (h1 + 1) / 2;
            w2 = (w1 + 1) / 2;
            if (old != null) {
                rg = old.rg;
                bassThresh = old.bassThresh;
                dxRes = old.dxRes;
                dyRes = old.dyRes;
            }
            rng = PyRandom.numpy(seed);
            main = new float[H * W];
            if (old != null) main = resample(old.main, old.H, old.W, H, W);
            shown = main;
            edge = new float[h1 * w1];
            for (int j = 0; j < h1; j++) {
                double v = (j + 0.5) / h1;
                for (int i = 0; i < w1; i++) {
                    double u = (i + 0.5) / w1;
                    double d = Math.min(Math.min(u, v), Math.min(1 - u, 1 - v));
                    edge[j * w1 + i] = (float) ((1 - B1ED) + B1ED * Math.min(1.0, Math.sqrt(d) * 5.0));
                }
            }
            Object[] ur = lin(H, h2, 4), uc = lin(W, w2, 4);
            urA = (int[]) ur[0]; urB = (int[]) ur[1]; urF = (float[]) ur[2];
            ucA = (int[]) uc[0]; ucB = (int[]) uc[1]; ucF = (float[]) uc[2];
            pyramid();
            Wp = W + 2 * m;
            // noise_lq: 256 x 256 random bytes, filtered and wrapped
            float[] tex = new float[256 * 256];
            for (int i = 0; i < tex.length; i++) tex[i] = (float) rng.npRandint(0, 256) / 255.0f;
            int n = Math.max(16, (int) Math.rint(256 / (0.4 * S)));
            int[] xa = new int[n], xb = new int[n];
            float[] fx = new float[n];
            for (int i = 0; i < n; i++) {
                double xsv = i * (256.0 / n) - 0.5;
                long x0 = (long) Math.floor(xsv);
                fx[i] = (float) (xsv - x0);
                xa[i] = (int) Math.floorMod(x0, 256L);
                xb[i] = (int) Math.floorMod(x0 + 1, 256L);
            }
            float[] rows = new float[n * 256];
            for (int i = 0; i < n; i++) {
                float f = fx[i], omf = 1 - f;
                for (int c = 0; c < 256; c++) rows[i * 256 + c] = tex[xa[i] * 256 + c] * omf + tex[xb[i] * 256 + c] * f;
            }
            float[] nz = new float[n * n];
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    float f = fx[j], omf = 1 - f;
                    nz[i * n + j] = rows[i * 256 + xa[j]] * omf + rows[i * 256 + xb[j]] * f - 0.5f;
                }
            nn = n;
            int ty = H / n + 2, tx = W / n + 2;
            noiseW = tx * n;
            noise = new float[ty * n * noiseW];
            for (int y = 0; y < ty * n; y++)
                for (int x = 0; x < noiseW; x++) noise[y * noiseW + x] = nz[(y % n) * n + (x % n)];
        }

        static Object[] lin(int nOut, int nIn, double d) {
            int[] a = new int[nOut], b = new int[nOut];
            float[] f = new float[nOut];
            for (int i = 0; i < nOut; i++) {
                double x = (i + 0.5) / d - 0.5;
                long x0 = (long) Math.floor(x);
                f[i] = (float) (x - x0);
                a[i] = (int) Math.min(nIn - 1, Math.max(0, x0));
                b[i] = (int) Math.min(nIn - 1, Math.max(0, x0 + 1));
            }
            return new Object[]{a, b, f};
        }

        static float[] up(float[] img, int ih, int iw, int[] ra, int[] rb, float[] rf, int[] ca, int[] cb, float[] cf) {
            int oh = ra.length, ow = ca.length;
            float[] rows = new float[oh * iw];
            for (int y = 0; y < oh; y++) {
                int a = ra[y] * iw, b = rb[y] * iw;
                float f = rf[y];
                for (int x = 0; x < iw; x++) {
                    float av = img[a + x];
                    rows[y * iw + x] = av + (img[b + x] - av) * f;
                }
            }
            float[] out = new float[oh * ow];
            for (int y = 0; y < oh; y++)
                for (int x = 0; x < ow; x++) {
                    float av = rows[y * iw + ca[x]];
                    out[y * ow + x] = av + (rows[y * iw + cb[x]] - av) * cf[x];
                }
            return out;
        }

        static float[] resample(float[] img, int h, int w, int H, int W) {
            Object[] r = lin(H, h, (double) H / h), c = lin(W, w, (double) W / w);
            return up(img, h, w, (int[]) r[0], (int[]) r[1], (float[]) r[2], (int[]) c[0], (int[]) c[1], (float[]) c[2]);
        }

        /** separable Gaussian, edge-padded, accumulated in float as numpy does */
        static float[] blur(float[] img, int H, int W, int r, float[] k) {
            int pw = W + 2 * r;
            float[] acc = new float[(H + 2 * r) * W];
            for (int y = 0; y < H + 2 * r; y++) {
                int sy = Math.min(H - 1, Math.max(0, y - r));
                for (int x = 0; x < W; x++) {
                    float s = img[sy * W + Math.min(W - 1, Math.max(0, x - r))] * k[0];
                    for (int o = 1; o < 2 * r + 1; o++) s += img[sy * W + Math.min(W - 1, Math.max(0, x + o - r))] * k[o];
                    acc[y * W + x] = s;
                }
            }
            float[] out = new float[H * W];
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    float s = acc[y * W + x] * k[0];
                    for (int o = 1; o < 2 * r + 1; o++) s += acc[(y + o) * W + x] * k[o];
                    out[y * W + x] = s;
                }
            return out;
        }

        static float[] down(float[] img, int H, int W) {
            int h = (H + 1) / 2, w = (W + 1) / 2;
            float[] out = new float[h * w];
            for (int y = 0; y < h; y++) {
                int y0 = 2 * y, y1 = Math.min(H - 1, 2 * y + 1);
                for (int x = 0; x < w; x++) {
                    int x0 = 2 * x, x1 = Math.min(W - 1, 2 * x + 1);
                    out[y * w + x] = (img[y0 * W + x0] + img[y1 * W + x0] + img[y0 * W + x1] + img[y1 * W + x1]) * 0.25f;
                }
            }
            return out;
        }

        void pyramid() {
            float[] d1 = down(main, H, W);
            float[] b1 = blur(d1, h1, w1, r1, k1);
            for (int i = 0; i < b1.length; i++) b1[i] = b1[i] * edge[i];
            blur1 = b1;
            float[] d2 = down(blur1, h1, w1);
            blur2 = blur(d2, h2, w2, r2, k2);
            b2up = up(blur2, h2, w2, urA, urB, urF, ucA, ucB, ucF);
        }

        double[] perFrame(double t, double[] att) {
            double bassAtt = att[0], midAtt = att[1];
            double rot = 0.010 * (0.60 * Math.sin(0.381 * t) + 0.40 * Math.sin(0.579 * t));
            double cx = 0.5 + 0.210 * (0.60 * Math.sin(0.374 * t) + 0.40 * Math.sin(0.294 * t));
            double cy = 0.5 + 0.210 * (0.60 * Math.sin(0.393 * t) + 0.40 * Math.sin(0.223 * t));
            double dx = 0.003 * (0.60 * Math.sin(0.234 * t) + 0.40 * Math.sin(0.277 * t)) + dxRes;
            double dy = 0.003 * (0.60 * Math.sin(0.284 * t) + 0.40 * Math.sin(0.247 * t)) + dyRes;
            double a = bassAtt > bassThresh ? 1.0 : 0.0;
            bassThresh = a * 2 + (1 - a) * ((bassThresh - 1.3) * 0.96 + 1.3);
            double e = Math.abs(bassThresh - 2.13) < 0.00001 ? 1.0 : 0.0;
            dxRes = e * 0.016 * Math.sin(t * 7) + (1 - e) * dxRes;
            dyRes = e * 0.012 * Math.sin(t * 9) + (1 - e) * dyRes;
            rg = Math.max(rg * 0.77, 0.02 + 0.5 * Math.min(2, Math.max(0, midAtt - 1) * 1.3));
            return new double[]{ZOOM + rg * 0.1, rot, cx, cy, dx, dy};
        }

        void step(double t, double[] att) {
            double[] pf = perFrame(t, att);
            double zoom = pf[0], rot = pf[1], cx = pf[2], cy = pf[3], dx = pf[4], dy = pf[5];
            double c = Math.cos(rot), s = Math.sin(rot);
            double kx = W / ax, ky = H / ay;
            double kxc = kx * c, kxs = kx * s, kys = ky * s, kyc = ky * c;
            double ox = W * ((cx - dx - 0.5) / ax + 0.5) - 0.5 + m, oy = H * ((cy - dy - 0.5) / ay + 0.5) - 0.5 + m;
            double pa = ax / zoom, pb = 0.5 - cx - 0.5 * ax / zoom, qa = ay / zoom, qb = 0.5 - cy - 0.5 * ay / zoom;
            float[] axf = new float[W], bxf = new float[H], ayf = new float[W], byf = new float[H];
            for (int x = 0; x < W; x++) {
                double p = us[x] * pa + pb;
                axf[x] = (float) (kxc * p);
                ayf[x] = (float) (kys * p);
            }
            for (int y = 0; y < H; y++) {
                double q = vs[y] * qa + qb;
                bxf[y] = (float) (ox - kxs * q);
                byf[y] = (float) (oy + kyc * q);
            }
            float fk1 = (float) (KEEP * (1 + DOG)), fk2 = (float) (KEEP * DOG);
            int Hp = H + 2 * m;
            float[] Dp = new float[Hp * Wp];
            for (int y = 0; y < Hp; y++) {
                int sy = Math.floorMod(y - m, H);
                for (int x = 0; x < Wp; x++) {
                    int i = sy * W + Math.floorMod(x - m, W);
                    float d = main[i] * fk1;
                    d -= b2up[i] * fk2;
                    Dp[y * Wp + x] = d;
                }
            }
            float hx = (float) (W + 2 * m - 1.001), hy = (float) (H + 2 * m - 1.001);
            double amp = Math.min(1.0, Math.max(0.0, att[2] - 1.0));
            int noy = 0, nox = 0;
            if (amp > 0) {
                noy = rng.npRandint(0, nn);
                nox = rng.npRandint(0, nn);
            }
            float namp = (float) (NOISE * amp);
            float[] out = new float[H * W];
            float inv255 = (float) (1 / 255.0);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    float X = bxf[y] + axf[x], Y = byf[y] + ayf[x];
                    if (X < 0) X = 0; else if (X > hx) X = hx;
                    if (Y < 0) Y = 0; else if (Y > hy) Y = hy;
                    int x0 = (int) X, y0 = (int) Y;
                    X -= x0;
                    Y -= y0;
                    int i = y0 * Wp + x0;
                    float a = Dp[i];
                    float b = Dp[i + 1];
                    b -= a;
                    b *= X;
                    a += b;
                    float d = Dp[i + 1 + Wp];
                    float cc = Dp[i + Wp];
                    d -= cc;
                    d *= X;
                    cc += d;
                    cc -= a;
                    cc *= Y;
                    a += cc;
                    if (amp > 0) a += noise[(noy + y) * noiseW + nox + x] * namp;
                    if (a < 0f) a = 0f; else if (a > 1f) a = 1f;
                    a *= 255.0f;
                    a = (float) Math.rint(a);
                    a *= inv255;
                    out[y * W + x] = a;
                }
            }
            shown = main;
            pyramid();
            main = out;
        }

        /** Every cell as glyph (0 ' ', 1 '▄', 2 '▀'), foreground step and background step. */
        void cells(int K, int[] g, int[] fgs, int[] bgs) {
            float[] v;
            int vh, vw;
            if (ss == 2) {
                v = down(shown, H, W);
                vh = (H + 1) / 2;
                vw = (W + 1) / 2;
                for (int i = 0; i < v.length; i++) v[i] += blur1[i] * 0.4f;
            } else {
                Object[] vr = lin(H, h1, 2), vc = lin(W, w1, 2);
                float[] b = up(blur1, h1, w1, (int[]) vr[0], (int[]) vr[1], (float[]) vr[2], (int[]) vc[0], (int[]) vc[1], (float[]) vc[2]);
                v = new float[H * W];
                vh = H;
                vw = W;
                for (int i = 0; i < v.length; i++) v[i] = shown[i] + b[i] * 0.4f;
            }
            float km = K - 1;
            int cellsN = (vh / 2) * vw;
            for (int r = 0; r < vh / 2; r++) {
                for (int x = 0; x < vw; x++) {
                    float tv = v[(2 * r) * vw + x] * km + 0.5f, bv = v[(2 * r + 1) * vw + x] * km + 0.5f;
                    int T = (int) Math.min(K - 1, Math.max(0f, tv)), B = (int) Math.min(K - 1, Math.max(0f, bv));
                    int c = r * vw + x;
                    if (c >= cellsN) continue;
                    if (T == 0) {
                        g[c] = B > 0 ? 1 : 0;
                        fgs[c] = B;
                        bgs[c] = 0;
                    } else {
                        g[c] = 2;
                        fgs[c] = T;
                        bgs[c] = B;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the look
    static final int FPS = 60, SS = 2, BUDGET = 120000, K = 64;
    static final double VIRT = 3.2;
    static final char[] GLYPHS = {' ', '▄', '▀'};

    private Canvas cv;
    private int sizeW = -1, sizeR = -1;
    private final MilkSound sound = new MilkSound();
    private double t = 0.0, acc = 0.0;
    private double last = Double.NaN;
    private double[] seen;
    private double heard = -10.0, fed = -10.0;
    private Object palKey;
    private int[] pal;
    private int seed = 7;
    private int[] gl = new int[0], fgi = new int[0], bgi = new int[0];

    private Canvas canvas(int DW, int DH) {
        Canvas old = cv;
        seed++;
        int ss = SS;
        double virt = VIRT;
        if ((long) DW * DH * ss * ss > BUDGET) {
            ss = 1;
            virt = 2.0;
        }
        return new Canvas(DW, DH, ss, virt, seed, old);
    }

    /** Geiss's palettize, pow(ret, float3(0.5, 0.8, 1.7)), in the theme's colours. */
    int[] palette(Theme C, double dim) {
        Object key = java.util.Arrays.asList(C.bg, C.fg, C.accent, Math.rint(dim * 1000) / 1000);
        if (key.equals(palKey)) return pal;
        int bg = C.bg, fg = C.fg, ac = C.accent;
        int[] bgc = {(bg >> 16) & 255, (bg >> 8) & 255, bg & 255};
        int[] fgc = {(fg >> 16) & 255, (fg >> 8) & 255, fg & 255};
        int[] acc_ = {(ac >> 16) & 255, (ac >> 8) & 255, ac & 255};
        double[] es = new double[3];
        for (int i = 0; i < 3; i++) {
            double tt = Math.abs(fgc[i] - bgc[i]) > 2 ? (double) (acc_[i] - bgc[i]) / (fgc[i] - bgc[i]) : 0.5;
            tt = Math.min(0.98, Math.max(0.02, tt));
            es[i] = Math.min(3.0, Math.max(0.4, Math.log(tt) / Math.log(0.5)));
        }
        double kd = 1 - dim;
        int[] p = new int[K];
        for (int k = 0; k < K; k++) {
            double v = (double) k / (K - 1);
            int c;
            if (v <= 0.5) {
                int[] ch = new int[3];
                for (int i = 0; i < 3; i++) ch[i] = (int) Math.rint(bgc[i] + (acc_[i] - bgc[i]) * Math.pow(2 * v, es[i]));
                c = (ch[0] << 16) | (ch[1] << 8) | ch[2];
            } else {
                c = Theme.mix(ac, fg, 2 * v - 1);
            }
            p[k] = dim > 0 ? Theme.mix(bg, c, kd) : c;
        }
        palKey = key;
        pal = p;
        return p;
    }

    private void hear(double now, double[] mags, boolean silent) {
        if (mags != null && mags != seen) {
            double dt = heard > 0 ? Math.min(0.2, now - heard) : 1 / 30.0;
            seen = mags;
            heard = now;
            sound.feed(silent ? null : mags, dt);
            fed = now;
        } else if (now - fed >= 1 / 30.0 && now - heard > 0.12) {
            sound.feed(null, fed > 0 ? Math.min(0.5, now - fed) : 1 / 30.0);
            fed = now;
        }
    }

    void draw(Cells out, Theme C, double now, double dim, int y0, int y1, double[] mags, boolean silent) {
        int W = out.w, R = y1 - y0;
        if (R < 1 || W < 2) return;
        if (sizeW != W || sizeR != R) {
            sizeW = W;
            sizeR = R;
            cv = canvas(W, 2 * R);
            gl = new int[W * R];
            fgi = new int[W * R];
            bgi = new int[W * R];
        }
        hear(now, mags, silent);
        double dt = Double.isNaN(last) ? 0.0 : Math.min(0.25, Math.max(0.0, now - last));
        last = now;
        acc += dt;
        int n = (int) (acc * FPS + 1e-9);
        if (n > 4) {
            n = 4;
            acc = 0.0;
        } else {
            acc -= (double) n / FPS;
        }
        double[] att = sound.att.clone();
        for (int i = 0; i < n; i++) {
            t += 1.0 / FPS;
            cv.step(t, att);
        }
        cv.cells(K, gl, fgi, bgi);
        int[] p = palette(C, dim);
        int a = y0 * W;
        for (int i = 0; i < W * R; i++) {
            out.ch[a + i] = GLYPHS[gl[i]];
            out.fg[a + i] = p[fgi[i]];
            out.bg[a + i] = p[bgi[i]];
            out.bold[a + i] = false;
        }
    }
}
