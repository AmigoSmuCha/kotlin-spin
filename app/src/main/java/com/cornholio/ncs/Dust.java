package com.cornholio.ncs;

import java.util.ArrayList;
import java.util.List;

/** NCS's particles: specks drifting out from the middle, faster on the bass. */
public final class Dust {
    private final PyRandom rng = PyRandom.python(5);
    private final List<double[]> p = new ArrayList<>();
    private int sw = -1, sh = -1;

    private double[] spawn(int W, int H, boolean anywhere) {
        double a = rng.uniform(0, 2 * Math.PI);
        double d = anywhere ? rng.uniform(0.05, 1.0) : rng.uniform(0.02, 0.15);
        double[] q = {W / 2.0 + Math.cos(a) * d * W / 2, H / 2.0 + Math.sin(a) * d * H / 2, Math.cos(a), Math.sin(a) * 0.5, 0};
        q[4] = Math.pow(rng.random(), 1.6);
        return q;
    }

    void draw(Cells cv, Theme C, double dt, double dim, double bass, double energy, double[][] mask, boolean silent) {
        int W = cv.w, H = cv.h;
        if (sw != W || sh != H) {
            sw = W;
            sh = H;
            p.clear();
            int count = Math.max(12, W * H / 90);
            for (int i = 0; i < count; i++) p.add(spawn(W, H, true));
        }
        double speed = (silent ? 0.4 : 1.5) + 9.0 * bass + 3.0 * energy;
        double kd = 1 - dim;
        for (int i = 0; i < p.size(); i++) {
            double[] q = p.get(i);
            double near = 0.3 + q[4];
            q[0] += q[2] * speed * near * dt * 2;
            q[1] += q[3] * speed * near * dt * 2;
            int x = (int) q[0], y = (int) q[1];
            if (!(0 <= x && x < W && 1 <= y && y < H - 1)) {
                p.set(i, spawn(W, H, false));
                continue;
            }
            if (cv.isBlank(x, y) && mask[y][x] > 0.6) {
                char c = q[4] > 0.8 ? '•' : q[4] > 0.4 ? '·' : '.';
                cv.put(x, y, c, Theme.mix(C.bg, C.fg, (0.18 + 0.4 * q[4]) * kd));
            }
        }
    }
}
