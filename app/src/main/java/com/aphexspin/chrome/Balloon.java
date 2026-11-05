package com.aphexspin.chrome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The model: a balloon, as aphex_spin.py's Model. The outline is inflated like
 * a membrane under pressure: phi solves -lap(phi) = 1 inside it (baked once by
 * tools/bake_balloon.py into balloon.bin), and the surface is z^2 = Q(phi).
 * This builds Q and its gradient (packed per grid cell as bilinear
 * coefficients) and the voxel grid of rigorous lower bounds on the distance to
 * the surface, for a given puff and squash.
 */
public final class Balloon {

    // the baked field
    public final int nx, ny;
    public final double x0, y0, res, extX, extY, maxR2;
    final float[] phi;

    // the surface for one puff / squash
    public final double puff, squash;
    public double hmax, halfX, halfY, halfZ, reachX, reachY, radius;
    /** Per cell (ny-1)*(nx-1): Q, dQ/dx, dQ/dy as a + b*tx + c*ty + d*tx*ty (12 floats). */
    final float[] C;
    final int cnx;
    final float kx0, ky0, kres, kmx, kmy;
    /** Voxel bounds, edge-padded by one voxel: (vnz+2, vny+2, vnx+2). */
    final float[] VBp;
    final float vx, vy, vz, vinv;
    final int pnx, pny, pnz;

    /** The baked phi grid, read from balloon.bin. */
    public static final class Field {
        final int nx, ny;
        final double x0, y0, res, extX, extY, maxR2;
        final float[] phi;

        Field(int nx, int ny, double x0, double y0, double res, double extX, double extY, double maxR2, float[] phi) {
            this.nx = nx; this.ny = ny; this.x0 = x0; this.y0 = y0; this.res = res;
            this.extX = extX; this.extY = extY; this.maxR2 = maxR2; this.phi = phi;
        }

        public static Field read(InputStream in) throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            ByteBuffer b = ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            if (b.get() != 'B' || b.get() != 'L' || b.get() != 'N' || b.get() != '1') throw new IOException("not a balloon field");
            int nx = b.getInt(), ny = b.getInt();
            double x0 = b.getDouble(), y0 = b.getDouble(), res = b.getDouble();
            double ex = b.getDouble(), ey = b.getDouble(), r2 = b.getDouble();
            float[] phi = new float[nx * ny];
            b.asFloatBuffer().get(phi);
            return new Field(nx, ny, x0, y0, res, ex, ey, r2, phi);
        }
    }

    public Balloon(Field f, double puff, double squash) {
        this.nx = f.nx; this.ny = f.ny; this.x0 = f.x0; this.y0 = f.y0; this.res = f.res;
        this.extX = f.extX; this.extY = f.extY; this.maxR2 = f.maxR2; this.phi = f.phi;
        this.puff = puff; this.squash = squash;
        int n = nx * ny;

        // height profile: Q = H^2, ~2 s^2 phi at the rim, gently squashed where wide
        double s2 = 2.0 * puff * puff, K = 6.0;
        double[] Q = new double[n], dQ = new double[n];
        for (int i = 0; i < n; i++) {
            double p = phi[i];
            double pos = Math.max(p, 0.0);
            double u = 1.0 + pos / K;
            if (p > 0) {
                Q[i] = s2 * pos / Math.pow(u, squash);
                dQ[i] = s2 * Math.pow(u, -squash - 1.0) * (1.0 + (1.0 - squash) * pos / K);
            } else {
                Q[i] = s2 * p;
                dQ[i] = s2;
            }
        }
        // np.gradient(phi, 1/res): central differences inside, one-sided at the edges
        double c = 1.0 / res;
        float[] F = new float[n * 3];
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                int i = y * nx + x;
                double px, py;
                if (x == 0) px = (phi[i + 1] - (double) phi[i]) / c;
                else if (x == nx - 1) px = ((double) phi[i] - phi[i - 1]) / c;
                else px = ((double) phi[i + 1] - phi[i - 1]) / (2.0 * c);
                if (y == 0) py = ((double) phi[i + nx] - phi[i]) / c;
                else if (y == ny - 1) py = ((double) phi[i] - phi[i - nx]) / c;
                else py = ((double) phi[i + nx] - phi[i - nx]) / (2.0 * c);
                F[i * 3] = (float) Q[i];
                F[i * 3 + 1] = (float) (dQ[i] * px);
                F[i * 3 + 2] = (float) (dQ[i] * py);
            }
        }
        double[] Hh = new double[n];
        double hm = 0;
        for (int i = 0; i < n; i++) {
            Hh[i] = Math.sqrt(Math.max(Q[i], 0.0));
            if (Hh[i] > hm) hm = Hh[i];
        }
        hmax = hm;
        halfX = extX + 0.05;
        halfY = extY + 0.05;
        halfZ = hmax + 0.05;
        reachX = Math.sqrt(extX * extX + hmax * hmax);
        reachY = extY;
        radius = Math.sqrt(maxR2 + hmax * hmax);

        // pack the bilinear coefficients of each cell
        cnx = nx - 1;
        int cells = (nx - 1) * (ny - 1);
        C = new float[cells * 12];
        for (int y = 0; y < ny - 1; y++) {
            for (int x = 0; x < nx - 1; x++) {
                int o = (y * cnx + x) * 12;
                int ia = (y * nx + x) * 3, ib = ia + 3, ic = ia + nx * 3, id = ic + 3;
                for (int f3 = 0; f3 < 3; f3++) {
                    double a = F[ia + f3], b = F[ib + f3], cc = F[ic + f3], d = F[id + f3];
                    C[o + f3 * 4] = (float) a;
                    C[o + f3 * 4 + 1] = (float) (b - a);
                    C[o + f3 * 4 + 2] = (float) (cc - a);
                    C[o + f3 * 4 + 3] = (float) (a - b - cc + d);
                }
            }
        }
        kx0 = (float) x0;
        ky0 = (float) y0;
        kres = (float) res;
        kmx = (float) (nx - 1.001);
        kmy = (float) (ny - 1.001);

        // ---- voxel lower bounds on the distance to the balloon
        double v = 1.0;
        double vlox = x0, vloy = y0, vloz = -hmax - 1.0;
        int nvx = (int) ((nx / res) / v) + 1;
        int nvy = (int) ((ny / res) / v) + 1;
        int nvz = (int) ((2 * hmax + 2.0) / v) + 1;
        int[] ixs = new int[nvx], iys = new int[nvy];
        for (int i = 0; i < nvx; i++) {
            double cx = x0 + (i + 0.5) * v;
            ixs[i] = (int) Math.max(0, Math.min(nx - 1, Math.rint((cx - x0) * res)));
        }
        for (int i = 0; i < nvy; i++) {
            double cy = y0 + (i + 0.5) * v;
            iys[i] = (int) Math.max(0, Math.min(ny - 1, Math.rint((cy - y0) * res)));
        }
        double[] az = new double[nvz];
        for (int i = 0; i < nvz; i++) az[i] = Math.abs(vloz + (i + 0.5) * v);
        double[] best = new double[nvz * nvy * nvx];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        double[] radii = {0.6, 1.2, 2.0, 3.0, 4.5, 6.5, 9.0, 13.0};
        double[] colmax = new double[nx];
        double[] M = new double[nvy * nvx];
        for (double r : radii) {
            int k = (int) Math.ceil(r * res) + 1;
            for (int j = 0; j < nvy; j++) {
                int cy = iys[j];
                int y0i = Math.max(0, cy - k), y1i = Math.min(ny - 1, cy + k);
                for (int x = 0; x < nx; x++) {
                    double m = Double.NEGATIVE_INFINITY;
                    for (int y = y0i; y <= y1i; y++) m = Math.max(m, Hh[y * nx + x]);
                    colmax[x] = m;
                }
                for (int i = 0; i < nvx; i++) {
                    int cx = ixs[i];
                    int x0i = Math.max(0, cx - k), x1i = Math.min(nx - 1, cx + k);
                    double m = Double.NEGATIVE_INFINITY;
                    for (int x = x0i; x <= x1i; x++) m = Math.max(m, colmax[x]);
                    M[j * nvx + i] = m;
                }
            }
            for (int z = 0; z < nvz; z++)
                for (int j = 0; j < nvy; j++)
                    for (int i = 0; i < nvx; i++) {
                        int o = (z * nvy + j) * nvx + i;
                        best[o] = Math.max(best[o], Math.min(r, az[z] - M[j * nvx + i]));
                    }
        }
        double shrink = v * Math.sqrt(3) / 2;
        pnx = nvx + 2;
        pny = nvy + 2;
        pnz = nvz + 2;
        VBp = new float[pnx * pny * pnz];
        for (int z = 0; z < pnz; z++) {
            int sz = Math.min(nvz - 1, Math.max(0, z - 1));
            for (int j = 0; j < pny; j++) {
                int sj = Math.min(nvy - 1, Math.max(0, j - 1));
                for (int i = 0; i < pnx; i++) {
                    int si = Math.min(nvx - 1, Math.max(0, i - 1));
                    VBp[(z * pny + j) * pnx + i] = (float) (best[(sz * nvy + sj) * nvx + si] - shrink);
                }
            }
        }
        vx = (float) (vlox - v);
        vy = (float) (vloy - v);
        vz = (float) (vloz - v);
        vinv = (float) (1.0 / v);
    }

    /** The rigorous lower bound on the distance to the surface at (x, y, z). */
    float bound(float x, float y, float z) {
        int ix = (int) ((x - vx) * vinv);
        int iy = (int) ((y - vy) * vinv);
        int iz = (int) ((z - vz) * vinv);
        long flat = ((long) iz * pny + iy) * pnx + ix;
        if (flat < 0) flat = 0; else if (flat >= VBp.length) flat = VBp.length - 1;
        return VBp[(int) flat];
    }
}
