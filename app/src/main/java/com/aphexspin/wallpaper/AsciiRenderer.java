package com.aphexspin.wallpaper;

/**
 * Pure-Java port of aphex_spin.py's model, raymarcher, shading and glyph
 * matching. No Android classes here, so it can be tested on a desktop JVM.
 *
 * Usage: set R, light, material, mode, contrast, zoom; call render(cols, rows);
 * then read chars[], fg[] (and bgTop/bgBot for block mode).
 */
public final class AsciiRenderer {

    // ------------------------------------------------------------------ data
    public static final String[] MATERIALS = {"lilac", "obsidian", "chrome", "bone", "acid", "ember"};
    private static final double[][] ALBEDO = {
        {0.26, 0.10, 0.70}, {0.035, 0.03, 0.045}, {0.92, 0.93, 0.98},
        {0.78, 0.74, 0.66}, {0.40, 0.85, 0.06}, {0.95, 0.28, 0.06}};
    private static final double[] F0 = {0.05, 0.06, 0.92, 0.04, 0.05, 0.05};
    private static final boolean[] METAL = {false, false, true, false, false, false};
    private static final double[] SHINE = {120, 160, 300, 60, 110, 110};
    private static final double[] KS = {1.0, 1.4, 1.2, 0.7, 1.0, 1.0};
    private static final double[] KE = {1.15, 1.9, 1.0, 0.9, 1.1, 1.1};

    public static final String[] MODES = {"shape", "ramp", "block"};
    private static final String RAMP = " .:-=+*#%@";
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final String SYMBOLS = " .,:;'`\"-_~^!|/\\()[]{}<>+=*#%@&$?";

    private static final double[][] SAMPLES = {
        {0.28, 0.42}, {0.72, 0.30}, {0.28, 1.06}, {0.72, 0.94}, {0.28, 1.70}, {0.72, 1.58}};
    private static final double SAMPLE_R = 0.30;
    private static final int[][] AFFECT = {{0, 1, 2, 4}, {0, 1, 3, 5}, {2, 4, 6}, {3, 5, 7}, {4, 6, 8, 9}, {5, 7, 8, 9}};

    public static final double[] DEFAULT_LIGHT = unit(-0.55, 0.62, 0.75);
    private static final double[] FILL = unit(0.78, -0.22, 0.58);
    private static final double[] RIM = unit(0.10, 0.62, -0.78);

    private static final int MAX_STEPS = 48;
    private static final double EPS = 0.015;

    // ------------------------------------------------------------------ model (shared, built once)
    private static volatile Model sharedModel;

    public static Model model() {
        Model m = sharedModel;
        if (m == null) {
            synchronized (AsciiRenderer.class) {
                m = sharedModel;
                if (m == null) sharedModel = m = new Model(Data.LOGO_POLY, 5.0, 1.1, 0.9, 7.0);
            }
        }
        return m;
    }

    public static final class Model {
        final double H, rb, x0, y0, res;
        final int nx, ny;
        final float[] D, GX, GY;
        final double halfX, halfY, halfZ, reachX, reachY, radius;

        Model(double[] flat, double depth, double bevel, double corner, double res) {
            int n = flat.length / 2;
            double[][] poly = new double[n][];
            for (int i = 0; i < n; i++) poly[i] = new double[]{flat[2 * i], flat[2 * i + 1]};
            double[][] out = fillet(poly, corner);
            this.H = depth / 2.0;
            this.rb = Math.max(0.05, Math.min(bevel, H * 0.95));
            double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9, ex = 0, ey = 0, rx = 0, r2 = 0;
            for (double[] p : out) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                ex = Math.max(ex, Math.abs(p[0])); ey = Math.max(ey, Math.abs(p[1]));
                rx = Math.max(rx, Math.sqrt(p[0] * p[0] + H * H));
                r2 = Math.max(r2, p[0] * p[0] + p[1] * p[1]);
            }
            this.x0 = minX - 2.5;
            this.y0 = minY - 2.5;
            this.res = res;
            this.nx = (int) ((maxX + 2.5 - x0) * res) + 2;
            this.ny = (int) ((maxY + 2.5 - y0) * res) + 2;
            this.halfX = ex + 0.05; this.halfY = ey + 0.05; this.halfZ = H;
            this.reachX = rx; this.reachY = ey;
            this.radius = Math.sqrt(r2 + H * H);
            D = new float[nx * ny]; GX = new float[nx * ny]; GY = new float[nx * ny];
            bake(out);
        }

        /** A view of the same baked grid with its own lookup scratch, for another thread. */
        Model(Model o) {
            H = o.H; rb = o.rb; x0 = o.x0; y0 = o.y0; res = o.res; nx = o.nx; ny = o.ny;
            D = o.D; GX = o.GX; GY = o.GY;
            halfX = o.halfX; halfY = o.halfY; halfZ = o.halfZ;
            reachX = o.reachX; reachY = o.reachY; radius = o.radius;
        }

        private void bake(double[][] poly) {
            int n = poly.length;
            double[] ax = new double[n], ay = new double[n], exs = new double[n], eys = new double[n], ee = new double[n];
            for (int i = 0; i < n; i++) {
                ax[i] = poly[i][0]; ay[i] = poly[i][1];
                exs[i] = poly[(i + 1) % n][0] - ax[i];
                eys[i] = poly[(i + 1) % n][1] - ay[i];
                ee[i] = exs[i] * exs[i] + eys[i] * eys[i];
            }
            for (int iy = 0; iy < ny; iy++) {
                double py = y0 + iy / res;
                for (int ix = 0; ix < nx; ix++) {
                    double px = x0 + ix / res;
                    double best = Double.MAX_VALUE, bx = 0, by = 0;
                    int crossings = 0;
                    for (int e = 0; e < n; e++) {
                        double t = ((px - ax[e]) * exs[e] + (py - ay[e]) * eys[e]) / ee[e];
                        if (t < 0) t = 0; else if (t > 1) t = 1;
                        double cx = ax[e] + t * exs[e], cy = ay[e] + t * eys[e];
                        double dd = (px - cx) * (px - cx) + (py - cy) * (py - cy);
                        if (dd < best) { best = dd; bx = cx; by = cy; }
                        double a = ay[e], b = ay[e] + eys[e];
                        double ey = eys[e] == 0 ? 1e-12 : eys[e];
                        double xi = ax[e] + (py - a) * exs[e] / ey;
                        if (((a > py) != (b > py)) && px < xi) crossings++;
                    }
                    double dist = Math.sqrt(best);
                    double sign = (crossings & 1) == 1 ? -1.0 : 1.0;
                    double inv = sign / Math.max(dist, 1e-9);
                    int k = iy * nx + ix;
                    D[k] = (float) (dist * sign);
                    GX[k] = (float) ((px - bx) * inv);
                    GY[k] = (float) ((py - by) * inv);
                }
            }
        }

        // lookup state (single-threaded use per renderer is fine: written then read immediately)
        private int li; private double ltx, lty, lout;

        private void lookup(double x, double y) {
            double fx = (x - x0) * res, fy = (y - y0) * res;
            double cx = Math.min(Math.max(fx, 0.0), nx - 1.001);
            double cy = Math.min(Math.max(fy, 0.0), ny - 1.001);
            int ix = (int) cx, iy = (int) cy;
            ltx = cx - ix; lty = cy - iy;
            li = iy * nx + ix;
            double ox = fx - cx, oy = fy - cy;
            lout = (ox == 0 && oy == 0) ? 0.0 : Math.sqrt(ox * ox + oy * oy) / res;
        }

        private double bilerp(float[] F) {
            int i = li;
            double a = F[i], b = F[i + 1], c = F[i + nx], d = F[i + nx + 1];
            double top = a + (b - a) * ltx;
            return top + (c + (d - c) * ltx - top) * lty;
        }

        double d2(double x, double y) {
            lookup(x, y);
            return bilerp(D) + lout;
        }

        double sdf(double x, double y, double z) {
            double q = d2(x, y) + rb;
            double w = Math.abs(z) - (H - rb);
            double qp = Math.max(q, 0.0), wp = Math.max(w, 0.0);
            return Math.min(Math.max(q, w), 0.0) + Math.sqrt(qp * qp + wp * wp) - rb;
        }

        /** Writes the unit normal at (x,y,z) into out. */
        void normal(double x, double y, double z, double[] out) {
            lookup(x, y);
            double q = bilerp(D) + lout + rb;
            double gx = bilerp(GX), gy = bilerp(GY);
            double gl = Math.max(Math.hypot(gx, gy), 1e-9);
            gx /= gl; gy /= gl;
            double sz = z >= 0 ? 1.0 : -1.0;
            double w = Math.abs(z) - (H - rb);
            boolean both = q > 0 && w > 0;
            boolean wall = !both && q > w;
            double qq = both ? q : (wall ? 1.0 : 0.0);
            double ww = both ? w : (wall ? 0.0 : 1.0);
            double nx_ = gx * qq, ny_ = gy * qq, nz_ = sz * ww;
            double l = Math.max(Math.sqrt(nx_ * nx_ + ny_ * ny_ + nz_ * nz_), 1e-9);
            out[0] = nx_ / l; out[1] = ny_ / l; out[2] = nz_ / l;
        }
    }

    static double[][] fillet(double[][] P, double radius) {
        double step = Math.PI / 14;
        int n = P.length;
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] p0 = P[(i - 1 + n) % n], p1 = P[i], p2 = P[(i + 1) % n];
            double ax = p0[0] - p1[0], ay = p0[1] - p1[1], bx = p2[0] - p1[0], by = p2[1] - p1[1];
            double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
            ax /= la; ay /= la; bx /= lb; by /= lb;
            double theta = Math.acos(Math.max(-1.0, Math.min(1.0, ax * bx + ay * by)));
            if (radius <= 0 || theta < 1e-3 || Math.PI - theta < 1e-3) { out.add(p1); continue; }
            double t = Math.min(radius / Math.tan(theta / 2), Math.min(0.45 * la, 0.45 * lb));
            double r = t * Math.tan(theta / 2);
            double sx = ax + bx, sy = ay + by, sl = Math.hypot(sx, sy);
            sx /= sl; sy /= sl;
            double k0 = r / Math.sin(theta / 2);
            double cx = p1[0] + sx * k0, cy = p1[1] + sy * k0;
            double f0 = Math.atan2(p1[1] + ay * t - cy, p1[0] + ax * t - cx);
            double f2 = Math.atan2(p1[1] + by * t - cy, p1[0] + bx * t - cx);
            double sweep = pyMod(f2 - f0 + Math.PI, 2 * Math.PI) - Math.PI;
            int k = Math.max(2, (int) Math.ceil(Math.abs(sweep) / step));
            for (int j = 0; j <= k; j++) {
                double f = f0 + sweep * j / k;
                out.add(new double[]{cx + r * Math.cos(f), cy + r * Math.sin(f)});
            }
        }
        return out.toArray(new double[0][]);
    }

    private static double pyMod(double a, double b) { return a - b * Math.floor(a / b); }

    // ------------------------------------------------------------------ helpers
    static double[] unit(double x, double y, double z) {
        double l = Math.max(1e-12, Math.sqrt(x * x + y * y + z * z));
        return new double[]{x / l, y / l, z / l};
    }

    private static double smoothstep(double a, double b, double x) {
        double t = (x - a) / (b - a);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return t * t * (3.0 - 2.0 * t);
    }

    private static double band(double v, double a, double b, double s) {
        return smoothstep(a - s, a + s, v) * (1.0 - smoothstep(b - s, b + s, v));
    }

    private static double aces(double x) {
        double v = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

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

    public static double[][] initialRotation() { return mul(rotX(0.22), rotY(-0.5)); }

    // ------------------------------------------------------------------ glyphs
    private final char[] glyphChars;
    private final float[] glyphVec;   // 6 per glyph
    private final float[] glyphVV;

    // ------------------------------------------------------------------ inputs
    public double[][] R = initialRotation();
    public double[] light = DEFAULT_LIGHT.clone();
    public int material = 0;
    public int mode = 0;
    public double contrast = 2.25;
    public double zoom = 1.0;

    // ------------------------------------------------------------------ outputs
    public int cols, rows;
    public char[] chars = new char[0];
    public int[] fg = new int[0];     // 0xRRGGBB, -1 = nothing to draw
    public int[] bgTop = new int[0];  // block mode only
    public int[] bgBot = new int[0];

    private final Model m;
    private double[] sr = new double[0], sg = new double[0], sb = new double[0], sl = new double[0];
    private double[] gx = new double[0], gy = new double[0];
    private int gCols = -1, gRows = -1;
    private double gZoom = -1, footprint;
    private final double[] tmpN = new double[3];
    private final double[] col = new double[3];

    public AsciiRenderer() {
        m = new Model(model());
        int count = 0;
        for (int c = 32; c < 127; c++) if (SYMBOLS.indexOf((char) c) >= 0) count++;
        glyphChars = new char[count];
        glyphVec = new float[count * 6];
        glyphVV = new float[count];
        int k = 0;
        for (int c = 32; c < 127; c++) {
            if (SYMBOLS.indexOf((char) c) < 0) continue;
            glyphChars[k] = (char) c;
            float vv = 0;
            for (int j = 0; j < 6; j++) {
                float v = Data.GLYPH_VEC[(c - 32) * 6 + j];
                glyphVec[k * 6 + j] = v;
                vv += v * v;
            }
            glyphVV[k] = vv;
            k++;
        }
    }

    private void grid(int cols, int rows) {
        if (cols == gCols && rows == gRows && zoom == gZoom) return;
        double S = zoom * 0.94 * Math.min(cols / (2.0 * m.reachX), 2.0 * rows / (2.0 * (m.reachY + 1.2)));
        int n = cols * rows * 6;
        gx = new double[n]; gy = new double[n];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                for (int k = 0; k < 6; k++) {
                    int i = (r * cols + c) * 6 + k;
                    gx[i] = ((c + SAMPLES[k][0]) - cols / 2.0) / S;
                    gy[i] = (rows - (2.0 * r + SAMPLES[k][1])) / S;
                }
        footprint = SAMPLE_R * 1.15 / S;
        gCols = cols; gRows = rows; gZoom = zoom;
    }

    // ------------------------------------------------------------------ render
    public void render(int cols, int rows) {
        grid(cols, rows);
        int n = cols * rows * 6;
        if (sr.length != n) {
            sr = new double[n]; sg = new double[n]; sb = new double[n]; sl = new double[n];
        }
        int cells = cols * rows;
        if (chars.length != cells) {
            chars = new char[cells]; fg = new int[cells]; bgTop = new int[cells]; bgBot = new int[cells];
        }
        this.cols = cols; this.rows = rows;
        trace(n);
        if (mode == 2) makeBlock(); else makeGlyphs();
    }

    private void trace(int n) {
        final double[][] R = this.R;
        final double Z0 = m.radius + 5.0;
        final double dx = -R[2][0], dy = -R[2][1], dz = -R[2][2];
        final double isx = 1.0 / (Math.abs(dx) < 1e-9 ? 1e-9 : dx);
        final double isy = 1.0 / (Math.abs(dy) < 1e-9 ? 1e-9 : dy);
        final double isz = 1.0 / (Math.abs(dz) < 1e-9 ? 1e-9 : dz);
        final double s = dz > 0 ? -1.0 : 1.0;
        final boolean capTest = Math.abs(dz) > 0.06;
        final double capLimit = -m.rb - 0.02;
        final double fp = footprint;
        for (int i = 0; i < n; i++) {
            double xv = gx[i], yv = gy[i];
            double ox = xv * R[0][0] + yv * R[1][0] + Z0 * R[2][0];
            double oy = xv * R[0][1] + yv * R[1][1] + Z0 * R[2][1];
            double oz = xv * R[0][2] + yv * R[1][2] + Z0 * R[2][2];
            // slab test against the bounding box
            double t1 = (-m.halfX - ox) * isx, t2 = (m.halfX - ox) * isx;
            double tmin = Math.min(t1, t2), tmax = Math.max(t1, t2);
            t1 = (-m.halfY - oy) * isy; t2 = (m.halfY - oy) * isy;
            tmin = Math.max(tmin, Math.min(t1, t2)); tmax = Math.min(tmax, Math.max(t1, t2));
            t1 = (-m.halfZ - oz) * isz; t2 = (m.halfZ - oz) * isz;
            tmin = Math.max(tmin, Math.min(t1, t2)); tmax = Math.min(tmax, Math.max(t1, t2));
            tmin = Math.max(tmin, 0.0);
            if (!(tmax > tmin)) { sr[i] = sg[i] = sb[i] = sl[i] = 0; continue; }

            double hitT = 0, cov = 0;
            boolean cap = false;
            if (capTest) {
                double tc = (s * m.H - oz) / dz;
                if (m.d2(ox + tc * dx, oy + tc * dy) < capLimit) { hitT = tc; cov = 1; cap = true; }
            }
            if (!cap) {
                double t = tmin, best = Double.POSITIVE_INFINITY, tb = t;
                boolean hit = false;
                for (int step = 0; step < MAX_STEPS; step++) {
                    double dist = m.sdf(ox + t * dx, oy + t * dy, oz + t * dz);
                    if (dist < best) { best = dist; tb = t; }
                    if (dist < EPS) { hit = true; hitT = t; cov = 1; break; }
                    t += Math.max(dist, EPS * 0.5);
                    if (t >= tmax) break;
                }
                if (!hit && best < fp) {
                    double c = Math.min(Math.max(1.0 - best / fp, 0.0), 1.0);
                    cov = c * c * (3.0 - 2.0 * c);
                    hitT = tb;
                }
            }
            if (cov <= 0) { sr[i] = sg[i] = sb[i] = sl[i] = 0; continue; }
            double px = ox + hitT * dx, py = oy + hitT * dy, pz = oz + hitT * dz;
            double nx, ny, nz;
            if (cap) { nx = 0; ny = 0; nz = s; }
            else { m.normal(px, py, pz, tmpN); nx = tmpN[0]; ny = tmpN[1]; nz = tmpN[2]; }
            // to view space: v_i = sum_j R[i][j] * p_j
            double vnx = R[0][0] * nx + R[0][1] * ny + R[0][2] * nz;
            double vny = R[1][0] * nx + R[1][1] * ny + R[1][2] * nz;
            double vnz = R[2][0] * nx + R[2][1] * ny + R[2][2] * nz;
            double vpx = R[0][0] * px + R[0][1] * py + R[0][2] * pz;
            double vpy = R[1][0] * px + R[1][1] * py + R[1][2] * pz;
            double vpz = R[2][0] * px + R[2][1] * py + R[2][2] * pz;
            shade(vnx, vny, vnz, vpx, vpy, vpz);
            double r = col[0] * cov, g = col[1] * cov, b = col[2] * cov;
            sr[i] = r; sg[i] = g; sb[i] = b;
            sl[i] = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        }
    }

    private void shade(double Nx, double Ny, double Nz, double Px, double Py, double Pz) {
        int mat = material;
        double[] alb = ALBEDO[mat];
        double rad = m.radius;
        double Vx = -Px, Vy = -Py, Vz = 2.4 * rad - Pz;
        double vl = Math.sqrt(Vx * Vx + Vy * Vy + Vz * Vz);
        Vx /= vl; Vy /= vl; Vz /= vl;
        double ndv = Nx * Vx + Ny * Vy + Nz * Vz;
        ndv = ndv < 0 ? 0 : (ndv > 1 ? 1 : ndv);
        double Kx = light[0] * 1.7 * rad - Px, Ky = light[1] * 1.7 * rad - Py, Kz = Math.max(0.35, light[2]) * 1.5 * rad - Pz;
        double kl = Math.sqrt(Kx * Kx + Ky * Ky + Kz * Kz);
        Kx /= kl; Ky /= kl; Kz /= kl;
        double hemi = 0.5 + 0.5 * Ny;
        double dr = 0.012 * (1 - hemi) + 0.075 * hemi;
        double dg = 0.010 * (1 - hemi) + 0.070 * hemi;
        double db = 0.018 * (1 - hemi) + 0.100 * hemi;
        double spr = 0, spg = 0, spb = 0;
        double shine = SHINE[mat];
        double norm = (shine + 8.0) / (8.0 * Math.PI);
        for (int li = 0; li < 3; li++) {
            double Lx, Ly, Lz, cr, cg, cb;
            if (li == 0) { Lx = Kx; Ly = Ky; Lz = Kz; cr = 1.65; cg = 0.94 * 1.65; cb = 0.86 * 1.65; }
            else if (li == 1) { Lx = FILL[0]; Ly = FILL[1]; Lz = FILL[2]; cr = 0.15; cg = 0.62 * 0.30; cb = 0.30; }
            else { Lx = RIM[0]; Ly = RIM[1]; Lz = RIM[2]; cr = 1.10; cg = 0.90 * 1.10; cb = 1.10; }
            double ndl = Nx * Lx + Ny * Ly + Nz * Lz;
            if (ndl < 0) ndl = 0;
            dr += ndl * cr; dg += ndl * cg; db += ndl * cb;
            if (ndl > 0) {
                double hx = Lx + Vx, hy = Ly + Vy, hz = Lz + Vz;
                double hl = Math.max(Math.sqrt(hx * hx + hy * hy + hz * hz), 1e-9);
                double ndh = (Nx * hx + Ny * hy + Nz * hz) / hl;
                if (ndh > 0) {
                    double sp = Math.pow(ndh, shine) * norm;
                    spr += sp * cr; spg += sp * cg; spb += sp * cb;
                }
            }
        }
        // reflection into the studio
        double rx = 2.0 * ndv * Nx - Vx, ry = 2.0 * ndv * Ny - Vy, rz = 2.0 * ndv * Nz - Vz;
        double base = 0.035 + 0.11 * smoothstep(-0.35, 0.95, ry);
        double top = band(ry, 0.60, 1.05, 0.07) * band(rx, -0.58, 0.58, 0.07) * 3.4;
        double left = band(rx, -1.05, -0.60, 0.06) * band(ry, -0.30, 0.62, 0.07) * smoothstep(-0.5, 0.0, rz) * 2.4;
        double right = band(rx, 0.64, 1.05, 0.06) * band(ry, -0.48, 0.40, 0.07) * 1.5;
        double front = band(rx, -0.80, -0.08, 0.16) * band(ry, 0.02, 0.62, 0.16) * smoothstep(0.3, 0.8, rz) * 0.9;
        double floor = 0.05 * smoothstep(-0.1, -0.9, ry);
        double er = base * 0.92 + top + left * 1.00 + right * 0.62 + front * 0.95 + floor * 0.9;
        double eg = base * 0.86 + top + left * 0.86 + right * 0.78 + front * 0.92 + floor * 0.8;
        double eb = base * 1.10 + top + left * 0.70 + right * 1.10 + front * 1.00 + floor * 1.1;
        double fr = Math.pow(1.0 - ndv, 5);
        double ke = KE[mat], ks = KS[mat];
        double cr, cg, cb;
        if (METAL[mat]) {
            double Fr = alb[0] + (1 - alb[0]) * fr, Fg = alb[1] + (1 - alb[1]) * fr, Fb = alb[2] + (1 - alb[2]) * fr;
            cr = Fr * (er * ke + spr * ks * 0.2);
            cg = Fg * (eg * ke + spg * ks * 0.2);
            cb = Fb * (eb * ke + spb * ks * 0.2);
        } else {
            double F = F0[mat] + (1 - F0[mat]) * fr;
            cr = alb[0] * dr * (1 - F) + F * er * ke + spr * ks * 0.10;
            cg = alb[1] * dg * (1 - F) + F * eg * ke + spg * ks * 0.10;
            cb = alb[2] * db * (1 - F) + F * eb * ke + spb * ks * 0.10;
        }
        col[0] = Math.pow(aces(cr), 1 / 2.2);
        col[1] = Math.pow(aces(cg), 1 / 2.2);
        col[2] = Math.pow(aces(cb), 1 / 2.2);
    }

    private static int pack(double r, double g, double b) {
        int ri = (int) Math.min(255, Math.max(0, r * 255 + 0.5));
        int gi = (int) Math.min(255, Math.max(0, g * 255 + 0.5));
        int bi = (int) Math.min(255, Math.max(0, b * 255 + 0.5));
        return (ri << 16) | (gi << 8) | bi;
    }

    private void makeBlock() {
        for (int c = 0; c < cols * rows; c++) {
            int o = c * 6;
            bgTop[c] = pack((sr[o] + sr[o + 1] + 0.5 * (sr[o + 2] + sr[o + 3])) / 3.0,
                            (sg[o] + sg[o + 1] + 0.5 * (sg[o + 2] + sg[o + 3])) / 3.0,
                            (sb[o] + sb[o + 1] + 0.5 * (sb[o + 2] + sb[o + 3])) / 3.0);
            bgBot[c] = pack((sr[o + 4] + sr[o + 5] + 0.5 * (sr[o + 2] + sr[o + 3])) / 3.0,
                            (sg[o + 4] + sg[o + 5] + 0.5 * (sg[o + 2] + sg[o + 3])) / 3.0,
                            (sb[o + 4] + sb[o + 5] + 0.5 * (sb[o + 2] + sb[o + 3])) / 3.0);
            chars[c] = ' ';
            fg[c] = -1;
        }
    }

    private double[] S = new double[0], E = new double[0];

    private void makeGlyphs() {
        int cells = cols * rows;
        if (mode == 1) {
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    int cell = r * cols + c, o = cell * 6;
                    double mean = (sl[o] + sl[o + 1] + sl[o + 2] + sl[o + 3] + sl[o + 4] + sl[o + 5]) / 6.0;
                    double th = (BAYER[(r % 4) * 4 + (c % 4)] + 0.5) / 16.0 - 0.5;
                    int k = (int) Math.floor(Math.pow(mean, 0.85) * (RAMP.length() - 1) + 0.5 + th * 0.9);
                    k = Math.max(0, Math.min(RAMP.length() - 1, k));
                    chars[cell] = RAMP.charAt(k);
                }
        } else {
            if (S.length != cells * 6) { S = new double[cells * 6]; E = new double[cells * 6]; }
            for (int i = 0; i < cells * 6; i++) S[i] = Math.min(Math.max(sl[i] * 0.85, 0.0), 1.0);
            enhance(contrast, 1.0 + 0.45 * contrast);
            int ng = glyphChars.length;
            for (int cell = 0; cell < cells; cell++) {
                int o = cell * 6;
                double e0 = E[o], e1 = E[o + 1], e2 = E[o + 2], e3 = E[o + 3], e4 = E[o + 4], e5 = E[o + 5];
                if (e0 == 0 && e1 == 0 && e2 == 0 && e3 == 0 && e4 == 0 && e5 == 0) { chars[cell] = ' '; continue; }
                double best = Double.MAX_VALUE;
                int bi = 0;
                for (int g = 0; g < ng; g++) {
                    int v = g * 6;
                    double d = glyphVV[g] - 2.0 * (e0 * glyphVec[v] + e1 * glyphVec[v + 1] + e2 * glyphVec[v + 2]
                            + e3 * glyphVec[v + 3] + e4 * glyphVec[v + 4] + e5 * glyphVec[v + 5]);
                    if (d < best) { best = d; bi = g; }
                }
                chars[cell] = glyphChars[bi];
            }
        }
        // ink colour
        for (int cell = 0; cell < cells; cell++) {
            if (chars[cell] == ' ') { fg[cell] = -1; continue; }
            int o = cell * 6;
            double ws = 0, ir = 0, ig = 0, ib = 0, peak = 0;
            for (int k = 0; k < 6; k++) {
                double l = sl[o + k];
                ws += l; ir += sr[o + k] * l; ig += sg[o + k] * l; ib += sb[o + k] * l;
                if (l > peak) peak = l;
            }
            ws = Math.max(ws, 1e-6);
            ir /= ws; ig /= ws; ib /= ws;
            double il = 0.2126 * ir + 0.7152 * ig + 0.0722 * ib;
            double target = Math.min(Math.max(Math.pow(Math.max(il, peak * 0.8), 0.6) * 1.25, 0.0), 1.0);
            double k = target / Math.max(il, 1e-6);
            ir *= k; ig *= k; ib *= k;
            double mx = Math.max(1.0, Math.max(ir, Math.max(ig, ib)));
            fg[cell] = pack(ir / mx, ig / mx, ib / mx);
        }
    }

    private double at(int r, int c, int k) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return 0.0;
        return S[(r * cols + c) * 6 + k];
    }

    /** Harri's directional + global contrast enhancement, S -> E. */
    private void enhance(double eDir, double eGlob) {
        double[] ext = new double[10];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int o = (r * cols + c) * 6;
                ext[0] = at(r - 1, c, 4); ext[1] = at(r - 1, c, 5);
                ext[2] = at(r, c - 1, 1); ext[3] = at(r, c + 1, 0);
                ext[4] = at(r, c - 1, 3); ext[5] = at(r, c + 1, 2);
                ext[6] = at(r, c - 1, 5); ext[7] = at(r, c + 1, 4);
                ext[8] = at(r + 1, c, 0); ext[9] = at(r + 1, c, 1);
                double gmax = 0;
                for (int i = 0; i < 6; i++) {
                    double v = S[o + i], mx = v;
                    for (int k : AFFECT[i]) mx = Math.max(mx, ext[k]);
                    double safe = Math.max(mx, 1e-6);
                    double e = Math.pow(v / safe, eDir) * mx;
                    E[o + i] = e;
                    if (e > gmax) gmax = e;
                }
                double g = Math.max(gmax, 1e-6);
                for (int i = 0; i < 6; i++) E[o + i] = Math.pow(E[o + i] / g, eGlob) * g;
            }
    }
}
