package com.aphexspin.chrome;

/**
 * Orthographic sphere tracing against the balloon, then chrome or glossy
 * plastic: a port of aphex_spin.py's Renderer. Rays march as a wavefront, like
 * the script's vectorised loop: over-relaxed steps with a secant pull-back,
 * empty space leapt on the voxel bound, coherent starts from last frame's
 * depths, and grazing rays anti-aliased from how near they passed.
 */
public final class Tracer {

    public static final String[] FINISHES = {"chrome", "gloss", "satin", "obsidian", "gold", "bone"};
    public static final String[] FINISH_FEEL = {
        "a mirror of the sky above the clouds",
        "candy plastic under the sun",
        "soft plastic in a studio of softboxes",
        "black glass in the studio",
        "warm metal in the sky",
        "matte, like fired clay"};
    static final int[] ENV = {EnvMap.SKY, EnvMap.SKY, EnvMap.STUDIO, EnvMap.STUDIO, EnvMap.SKY, EnvMap.STUDIO};
    static final boolean[] METAL = {true, false, false, false, true, false};
    static final int[] TINT = {Palette.CHROME, Palette.GLOSS, Palette.SATIN, Palette.OBSIDIAN, Palette.GOLD, Palette.BONE};
    static final double[] F0 = {0, 0.06, 0.05, 0.06, 0, 0.04};
    static final double[] SHINE = {0, 150.0, 120.0, 160.0, 0, 60.0};
    static final double[] SPEC = {0, 1.0, 1.0, 1.4, 0, 0.7};
    static final double[] ENVK = {1.0, 1.0, 1.15, 1.9, 1.0, 0.9};

    public static int envOf(int material) {
        return ENV[material];
    }

    static final int MAX_STEPS = 40;
    static final float OMEGA = 1.5f;
    static final double[] FILL = unit(0.78, -0.22, 0.58);
    static final double[] RIM = unit(0.10, 0.62, -0.78);

    static double[] unit(double x, double y, double z) {
        double l = Math.max(1e-12, Math.sqrt(x * x + y * y + z * z));
        return new double[]{x / l, y / l, z / l};
    }

    // ------------------------------------------------------------------ outputs
    public float[] rgb = new float[0], lum = new float[0], glint = new float[0], cov = new float[0], depth = new float[0];

    // ------------------------------------------------------------------ scratch
    private Balloon m;
    private float[] OX = new float[0], OY = new float[0], OZ = new float[0], TMIN = new float[0], TMAX = new float[0], hitT = new float[0];
    private int[] act = new int[0];
    // marching state
    private int[] sIdx = new int[0];
    private float[] sOx = new float[0], sOy = new float[0], sOz = new float[0], sT = new float[0], sTmax = new float[0],
        sTp = new float[0], sGp = new float[0], sBest = new float[0], sTb = new float[0], sTmin = new float[0];
    private boolean[] sWarm = new boolean[0];
    private int[] missI = new int[0];
    private float[] missD = new float[0], missT = new float[0];
    private int missN;
    private final float[] fq = new float[3];
    private final float[] envc = new float[3];
    private final double[] col = new double[3];
    private final float[] colA = new float[3];
    private float glintOut;

    public void setModel(Balloon m) {
        this.m = m;
    }

    public Balloon model() {
        return m;
    }

    private void ensure(int n) {
        if (rgb.length == n * 3) return;
        rgb = new float[n * 3]; lum = new float[n]; glint = new float[n]; cov = new float[n]; depth = new float[n];
        OX = new float[n]; OY = new float[n]; OZ = new float[n]; TMIN = new float[n]; TMAX = new float[n]; hitT = new float[n];
        act = new int[n];
        sIdx = new int[n]; sOx = new float[n]; sOy = new float[n]; sOz = new float[n]; sT = new float[n]; sTmax = new float[n];
        sTp = new float[n]; sGp = new float[n]; sBest = new float[n]; sTb = new float[n]; sTmin = new float[n];
        sWarm = new boolean[n];
        missI = new int[n]; missD = new float[n]; missT = new float[n];
    }

    /** Q, dQ/dx, dQ/dy at (x, y), bilinear, in float as the script's float32 maths. */
    private void field(float x, float y, float[] out) {
        Balloon b = m;
        float fx = Math.min(Math.max((x - b.kx0) * b.kres, 0.0f), b.kmx);
        float fy = Math.min(Math.max((y - b.ky0) * b.kres, 0.0f), b.kmy);
        float ixf = (float) Math.floor(fx), iyf = (float) Math.floor(fy);
        float tx = fx - ixf, ty = fy - iyf;
        int o = ((int) iyf * b.cnx + (int) ixf) * 12;
        float[] C = b.C;
        float txy = tx * ty;
        out[0] = C[o] + C[o + 1] * tx + C[o + 2] * ty + C[o + 3] * txy;
        out[1] = C[o + 4] + C[o + 5] * tx + C[o + 6] * ty + C[o + 7] * txy;
        out[2] = C[o + 8] + C[o + 9] * tx + C[o + 10] * ty + C[o + 11] * txy;
    }

    private void miss(int i, float d, float t) {
        missI[missN] = i;
        missD[missN] = d;
        missT[missN] = t;
        missN++;
    }

    /**
     * Rays through view-space points (xv, yv) looking down -z, against the model
     * turned by R (object to view). Fills rgb (sRGB, 3 per ray), lum, glint,
     * cov and depth (NaN where a ray missed). start: coherent start depths per
     * ray, or null. envs: the palette's baked sky and studio maps.
     */
    public void trace(float[] xv, float[] yv, int n, double[][] R, double[] light, int material, double footprint,
                      float[] start, Palette pal, float[][] envs, int alsoMaterial, double alsoWeight) {
        ensure(n);
        final Balloon m = this.m;
        final float z0 = (float) (m.radius + 5.0);
        final float r00 = (float) R[0][0], r01 = (float) R[0][1], r02 = (float) R[0][2];
        final float r10 = (float) R[1][0], r11 = (float) R[1][1], r12 = (float) R[1][2];
        final float r20 = (float) R[2][0], r21 = (float) R[2][1], r22 = (float) R[2][2];
        final float dx = -r20, dy = -r21, dz = -r22;
        final float hx = (float) m.halfX, hy = (float) m.halfY, hz = (float) m.halfZ;
        final float ddx = Math.abs(dx) > 1e-9f ? dx : 1e-9f;
        final float ddy = Math.abs(dy) > 1e-9f ? dy : 1e-9f;
        final float ddz = Math.abs(dz) > 1e-9f ? dz : 1e-9f;

        int na = 0;
        for (int i = 0; i < n; i++) {
            float x = xv[i], y = yv[i];
            float ox = x * r00 + y * r10 + z0 * r20;
            float oy = x * r01 + y * r11 + z0 * r21;
            float oz = x * r02 + y * r12 + z0 * r22;
            OX[i] = ox; OY[i] = oy; OZ[i] = oz;
            float tmin = 0f, tmax = Float.POSITIVE_INFINITY;
            float a = (-hx - ox) / ddx, b = (hx - ox) / ddx;
            tmin = Math.max(tmin, Math.min(a, b)); tmax = Math.min(tmax, Math.max(a, b));
            a = (-hy - oy) / ddy; b = (hy - oy) / ddy;
            tmin = Math.max(tmin, Math.min(a, b)); tmax = Math.min(tmax, Math.max(a, b));
            a = (-hz - oz) / ddz; b = (hz - oz) / ddz;
            tmin = Math.max(tmin, Math.min(a, b)); tmax = Math.min(tmax, Math.max(a, b));
            TMIN[i] = tmin; TMAX[i] = tmax;
            hitT[i] = Float.NaN;
            cov[i] = 0f;
            if (tmax > tmin) act[na++] = i;
        }

        // coherent starts, then phase 1: cold rays leap empty space on the voxel bound
        int cold = 0;
        for (int k = 0; k < na; k++) {
            int i = act[k];
            float t = TMIN[i];
            boolean warm = false;
            if (start != null) {
                float s0 = start[i];
                warm = !Float.isNaN(s0) && !Float.isInfinite(s0) && s0 > t;
                if (warm) t = s0;
            }
            if (!warm) {
                cold++;
                for (int it = 0; it < 10; it++) {
                    float bd = m.bound(OX[i] + t * dx, OY[i] + t * dy, OZ[i] + t * dz);
                    if (bd > 0.4f) t = t + bd; else break;
                }
            }
            sIdx[k] = i;
            sT[k] = t;
            sWarm[k] = warm;
        }
        int ns = 0;
        for (int k = 0; k < na; k++) {
            int i = sIdx[k];
            if (cold > 0 && !(sT[k] < TMAX[i])) continue;   // the script filters only when some rays were cold
            sIdx[ns] = i;
            sT[ns] = sT[k];
            sWarm[ns] = sWarm[k];
            ns++;
        }
        final float eps = (float) Math.max(0.004, 0.12 * footprint);
        final float minStep = (float) Math.max(0.004, 0.30 * footprint);
        for (int k = 0; k < ns; k++) {
            int i = sIdx[k];
            sOx[k] = OX[i]; sOy[k] = OY[i]; sOz[k] = OZ[i];
            sTmax[k] = TMAX[i];
            sTp[k] = sT[k];
            sGp[k] = Float.POSITIVE_INFINITY;
            sBest[k] = Float.POSITIVE_INFINITY;
            sTb[k] = sT[k];
            sTmin[k] = TMIN[i];
        }
        missN = 0;
        int total = Math.max(1, ns);
        final float[] q = fq;
        for (int stepNo = 0; stepNo < MAX_STEPS; stepNo++) {
            if (ns == 0 || (stepNo > 9 && ns < total * 0.02)) break;
            int keep = 0;
            for (int k = 0; k < ns; k++) {
                float t = sT[k];
                float x = sOx[k] + t * dx, y = sOy[k] + t * dy, z = sOz[k] + t * dz;
                field(x, y, q);
                float zz = z * z;
                float g = zz - q[0];
                float e = g / ((float) Math.sqrt(q[1] * q[1] + q[2] * q[2] + 4.0f * zz) + 1e-12f);
                boolean inside = g < 0;
                float gp = sGp[k];
                boolean first = Float.isNaN(gp) || Float.isInfinite(gp);
                boolean redo = inside && first && sWarm[k];
                if (redo) {
                    t = sTmin[k];
                    sWarm[k] = false;
                    inside = false;
                }
                boolean done = inside || (e < eps && !redo);
                if (done) {
                    float th = t;
                    if (inside && !first) {
                        float tp = sTp[k];
                        th = tp + (t - tp) * gp / (gp - g);
                    }
                    int j = sIdx[k];
                    hitT[j] = th;
                    cov[j] = 1.0f;
                }
                if (e < sBest[k] && !redo) {
                    sBest[k] = e;
                    sTb[k] = t;
                }
                float step = Math.max(OMEGA * e, minStep);
                if (e > 0.4f) {
                    float bd = m.bound(x, y, z);
                    if (bd > 0.4f) step = bd;
                }
                float nt;
                if (redo) {
                    sGp[k] = Float.POSITIVE_INFINITY;
                    nt = t;
                } else {
                    sTp[k] = t;
                    sGp[k] = g;
                    nt = t + step;
                }
                sT[k] = nt;
                if (done) continue;
                if (nt >= sTmax[k]) {
                    miss(sIdx[k], sBest[k], sTb[k]);
                    continue;
                }
                if (keep != k) {
                    sIdx[keep] = sIdx[k]; sOx[keep] = sOx[k]; sOy[keep] = sOy[k]; sOz[keep] = sOz[k];
                    sT[keep] = sT[k]; sTmax[keep] = sTmax[k]; sTp[keep] = sTp[k]; sGp[keep] = sGp[k];
                    sBest[keep] = sBest[k]; sTb[keep] = sTb[k]; sTmin[keep] = sTmin[k]; sWarm[keep] = sWarm[k];
                }
                keep++;
            }
            ns = keep;
        }
        for (int k = 0; k < ns; k++) miss(sIdx[k], sBest[k], sTb[k]);
        // near misses: shade where the ray passed closest, weighted by how near
        final float fp = (float) footprint;
        for (int k = 0; k < missN; k++) {
            float md = missD[k];
            if (md < fp) {
                float c = Math.min(Math.max(1.0f - md / fp, 0.0f), 1.0f);
                int j = missI[k];
                hitT[j] = missT[k];
                cov[j] = c * c * (3.0f - 2.0f * c);
            }
        }

        // shading
        final double[] sun = unit(light[0], light[1], light[2]);
        final boolean also = alsoMaterial >= 0 && alsoMaterial != material;
        final float wf = (float) alsoWeight;
        for (int i = 0; i < n; i++) {
            float c = cov[i];
            depth[i] = c >= 1.0f ? hitT[i] : Float.NaN;
            int o = i * 3;
            if (!(c > 0)) {
                rgb[o] = 0; rgb[o + 1] = 0; rgb[o + 2] = 0;
                glint[i] = 0;
                lum[i] = 0;
                continue;
            }
            float th = hitT[i];
            float px = OX[i] + th * dx, py = OY[i] + th * dy, pz = OZ[i] + th * dz;
            field(px, py, q);
            float gx = -q[1], gy = -q[2], gz = 2.0f * pz;
            float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz) + 1e-12f;
            float nx = gx / gl, ny = gy / gl, nz = gz / gl;
            float vnx = nx * r00 + ny * r01 + nz * r02;
            float vny = nx * r10 + ny * r11 + nz * r12;
            float vnz = nx * r20 + ny * r21 + nz * r22;
            float vpx = px * r00 + py * r01 + pz * r02;
            float vpy = px * r10 + py * r11 + pz * r12;
            float vpz = px * r20 + py * r21 + pz * r22;
            shade(vnx, vny, vnz, vpx, vpy, vpz, light, sun, material, pal, envs);
            float cr = (float) col[0], cg = (float) col[1], cb = (float) col[2], gg = glintOut;
            if (also) {
                shade(vnx, vny, vnz, vpx, vpy, vpz, light, sun, alsoMaterial, pal, envs);
                float ar = (float) col[0], ag = (float) col[1], ab = (float) col[2], ga = glintOut;
                cr = ar + (cr - ar) * wf;
                cg = ag + (cg - ag) * wf;
                cb = ab + (cb - ab) * wf;
                gg = ga + (gg - ga) * wf;
            }
            float rr = cr * c, rg = cg * c, rb = cb * c;
            rgb[o] = rr; rgb[o + 1] = rg; rgb[o + 2] = rb;
            glint[i] = gg * c;
            lum[i] = rr * 0.2126f + rg * 0.7152f + rb * 0.0722f;
        }
    }

    private static double smoothstep(double a, double b, double x) {
        double t = (x - a) / (b - a);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return t * t * (3.0 - 2.0 * t);
    }

    private static double aces(double x) {
        double v = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Shading in view space (the script's Renderer.shade): colour into col, glint into glintOut. */
    private void shade(float Nx, float Ny, float Nz, float Px, float Py, float Pz, double[] light, double[] sun,
                       int mat, Palette pal, float[][] envs) {
        double[][] P = pal.c;
        double[] tint = P[TINT[mat]];
        double rad = m.radius;
        float Vx = 0.0f - Px, Vy = 0.0f - Py, Vz = (float) (2.4 * rad) - Pz;
        float vl = (float) Math.sqrt(Vx * Vx + Vy * Vy + Vz * Vz);
        Vx /= vl; Vy /= vl; Vz /= vl;
        float ndv = Nx * Vx + Ny * Vy + Nz * Vz;
        ndv = ndv < 0 ? 0 : (ndv > 1 ? 1 : ndv);
        float rx = 2.0f * ndv * Nx - Vx, ry = 2.0f * ndv * Ny - Vy, rz = 2.0f * ndv * Nz - Vz;
        int kind = ENV[mat];
        EnvMap.lookup(envs[kind], rx, ry, rz, envc);
        double cs = rx * sun[0] + ry * sun[1] + rz * sun[2];
        cs = cs < 0 ? 0 : (cs > 1 ? 1 : cs);
        double e0 = envc[0], e1 = envc[1], e2 = envc[2];
        boolean env64 = false;
        if (kind == EnvMap.SKY) {
            double c2 = cs * cs, c4 = c2 * c2;
            double c60 = Math.pow(c4 * c4 * c4, 5);
            double sg = Math.pow(c60, 15) * 60.0 + c60 * 0.9;
            e0 = e0 + sg * P[Palette.SUN][0];
            e1 = e1 + sg * P[Palette.SUN][1];
            e2 = e2 + sg * P[Palette.SUN][2];
            env64 = true;
        }
        double c2 = cs * cs, c4 = c2 * c2, c16 = c4 * c4;
        double gl = c16 * c16 * c16 * c16 * c16;
        float fr = (float) Math.pow(1.0f - ndv, 5);
        double cr, cg, cb;
        double envk = ENVK[mat];
        if (METAL[mat]) {
            double F0r = tint[0] + (1.0 - tint[0]) * fr, F0g = tint[1] + (1.0 - tint[1]) * fr, F0b = tint[2] + (1.0 - tint[2]) * fr;
            cr = F0r * e0 * envk + 0.02 * tint[0];
            cg = F0g * e1 * envk + 0.02 * tint[1];
            cb = F0b * e2 * envk + 0.02 * tint[2];
        } else {
            double kx = light[0] * 1.7 * rad - Px, ky = light[1] * 1.7 * rad - Py, kz = Math.max(0.35, light[2]) * 1.5 * rad - Pz;
            double kl = Math.sqrt(kx * kx + ky * ky + kz * kz);
            kx /= kl; ky /= kl; kz /= kl;
            float hemi = 0.5f + 0.5f * Ny;
            double[] lo = P[Palette.AMB_LO], hi = P[Palette.AMB_HI];
            double dr = lo[0] * (1 - hemi) + hi[0] * hemi;
            double dg = lo[1] * (1 - hemi) + hi[1] * hemi;
            double db = lo[2] * (1 - hemi) + hi[2] * hemi;
            double sr = 0, sgc = 0, sb = 0;
            double shine = SHINE[mat];
            double norm = (shine + 8.0) / (8.0 * Math.PI);
            for (int li = 0; li < 3; li++) {
                double Lx, Ly, Lz;
                double[] cc;
                double kk;
                if (li == 0) { Lx = kx; Ly = ky; Lz = kz; cc = P[Palette.KEY]; kk = 1.65; }
                else if (li == 1) { Lx = FILL[0]; Ly = FILL[1]; Lz = FILL[2]; cc = P[Palette.FILL]; kk = 0.30; }
                else { Lx = RIM[0]; Ly = RIM[1]; Lz = RIM[2]; cc = P[Palette.RIM]; kk = 1.10; }
                double c0 = cc[0] * kk, c1 = cc[1] * kk, c2c = cc[2] * kk;
                double ndl = Nx * Lx + Ny * Ly + Nz * Lz;
                if (ndl < 0) ndl = 0;
                dr = dr + ndl * c0; dg = dg + ndl * c1; db = db + ndl * c2c;
                double hx = Lx + Vx, hy = Ly + Vy, hz = Lz + Vz;
                double hl = Math.max(Math.sqrt(hx * hx + hy * hy + hz * hz), 1e-9);
                hx /= hl; hy /= hl; hz /= hl;
                double nh = Nx * hx + Ny * hy + Nz * hz;
                if (nh < 0) nh = 0;
                double s = Math.pow(nh, shine) * norm * (ndl > 0 ? 1.0 : 0.0);
                sr = sr + s * c0; sgc = sgc + s * c1; sb = sb + s * c2c;
                if (li == 0) gl = Math.pow(nh, shine * 4);
            }
            float F = (float) F0[mat] + (float) (1.0 - F0[mat]) * fr;
            double fe0, fe1, fe2;
            if (env64) {
                fe0 = F * e0 * envk; fe1 = F * e1 * envk; fe2 = F * e2 * envk;
            } else {
                float ek = (float) envk;
                fe0 = (F * (float) e0) * ek; fe1 = (F * (float) e1) * ek; fe2 = (F * (float) e2) * ek;
            }
            double spk = SPEC[mat];
            cr = tint[0] * dr * (1.0f - F) + fe0 + sr * spk * 0.10;
            cg = tint[1] * dg * (1.0f - F) + fe1 + sgc * spk * 0.10;
            cb = tint[2] * db * (1.0f - F) + fe2 + sb * spk * 0.10;
        }
        double or = Math.pow(aces(cr), 1 / 2.2), og = Math.pow(aces(cg), 1 / 2.2), ob = Math.pow(aces(cb), 1 / 2.2);
        double[] white = P[Palette.WHITE];
        if (Math.min(white[0], Math.min(white[1], white[2])) < 0.999) {
            double lm = or * 0.2126 + og * 0.7152 + ob * 0.0722;
            double h = smoothstep(0.55, 1.0, lm);
            or = or + (Math.min(or, white[0]) - or) * h;
            og = og + (Math.min(og, white[1]) - og) * h;
            ob = ob + (Math.min(ob, white[2]) - ob) * h;
        }
        col[0] = (float) or;
        col[1] = (float) og;
        col[2] = (float) ob;
        glintOut = (float) gl;
    }
}
