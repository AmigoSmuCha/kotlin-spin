package com.cornholio.ncs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * asciify's trail, as cornholio keeps it: a finger's recent path is a ribbon
 * that fades with time and along its length. It paints; it never controls.
 */
public final class Trail {
    static final double LIFE = 1.2, LENGTH = 24.0, HEAD = 0.85, HEAD_TAU = 0.11;
    static final double[] RADIUS = {1.3, 1.7, 2.2};
    private final ArrayDeque<double[]> pts = new ArrayDeque<>();

    public synchronized void feed(double x, double y, double now) {
        pts.addLast(new double[]{x + 0.5, y + 0.5, now});
        while (pts.size() > 48) pts.removeFirst();
    }

    public static int level(int W, int H) {
        int area = W * H;
        return area < 3200 ? 0 : area < 6600 ? 1 : 2;
    }

    /** The wake this frame, per cell (0..0.8) of a w x h grid, or null when there is none. */
    public synchronized double[] grid(double now, int level, int w, int h) {
        while (!pts.isEmpty() && now - pts.peekFirst()[2] > LIFE) pts.removeFirst();
        if (pts.isEmpty()) return null;
        double[] out = new double[w * h];
        double R0 = RADIUS[level];
        double walked = 0.0;
        List<double[]> list = new ArrayList<>(pts);
        double[] head = list.get(list.size() - 1);
        double[] prev = head;
        List<double[]> segs = new ArrayList<>();
        if (list.size() == 1) segs.add(new double[]{head[0], head[1], head[2], head[0], head[1], head[2]});
        for (int i = list.size() - 2; i >= 0; i--) {
            double[] p = list.get(i);
            segs.add(new double[]{prev[0], prev[1], prev[2], p[0], p[1], p[2]});
            prev = p;
        }
        for (double[] sg : segs) {
            double x0 = sg[0], y0 = sg[1], t0 = sg[2], x1 = sg[3], y1 = sg[4], t1 = sg[5];
            double ln = Math.hypot((x1 - x0) * 0.5, y1 - y0);
            double speed = ln / Math.max(0.008, Math.abs(t0 - t1));
            double push = Math.min(1.0, speed / 30.0);
            double rBase = R0 * (0.7 + 0.3 * push);
            double strength = 0.6 + 0.3 * push;
            int steps = Math.max(1, (int) (ln / 0.5));
            int count = steps + (ln == 0 ? 1 : 0);
            for (int k = 0; k < count; k++) {
                double f = (double) k / steps;
                double s = walked + ln * f;
                if (s > LENGTH) return out;
                double px = x0 + (x1 - x0) * f, py = y0 + (y1 - y0) * f;
                double age = now - (t0 + (t1 - t0) * f);
                if (age >= LIFE) continue;
                double wv = strength * Math.pow(1 - age / LIFE, 1.5) * (1 - s / LENGTH);
                if (wv < 0.03) continue;
                double r = rBase * (1 + HEAD * Math.exp(-age / HEAD_TAU));
                double rr = r * r;
                for (int yy = (int) (py - r * 1.8); yy <= (int) (py + r * 1.8); yy++) {
                    double dy = yy + 0.5 - py;
                    double ey = Math.exp(-(dy * dy) / rr);
                    if (yy < 0 || yy >= h) continue;
                    for (int xx = (int) (px - r * 3.6); xx <= (int) (px + r * 3.6); xx++) {
                        if (xx < 0 || xx >= w) continue;
                        double dx = (xx + 0.5 - px) * 0.5;
                        double v = wv * ey * Math.exp(-(dx * dx) / rr);
                        int i = yy * w + xx;
                        if (v > 0.02 && v > out[i]) out[i] = v;
                    }
                }
            }
            walked += ln;
        }
        return out;
    }

    public synchronized boolean alive() {
        return !pts.isEmpty();
    }
}
