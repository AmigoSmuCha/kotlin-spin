package com.cornholio.ncs;

/** Magnitudes of a real signal by cornholio's iterative radix-2 FFT, Hann-windowed. */
public final class Spectrum {
    final int n;
    private final int[] rev;
    private final double[] twr, twi, win;
    private final double[] re, im;

    public Spectrum(int n) {
        this.n = n;
        int bits = 31 - Integer.numberOfLeadingZeros(n);
        rev = new int[n];
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        twr = new double[n / 2];
        twi = new double[n / 2];
        for (int k = 0; k < n / 2; k++) {
            double th = (-2.0 * Math.PI * k) / n;
            twr[k] = Math.cos(th);
            twi[k] = Math.sin(th);
        }
        win = new double[n];
        for (int i = 0; i < n; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
        re = new double[n];
        im = new double[n];
    }

    public double[] mags(double[] samples) {
        for (int i = 0; i < n; i++) {
            int r = rev[i];
            re[i] = samples[r] * win[r];
            im[i] = 0.0;
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2, step = n / size;
            for (int start = 0; start < n; start += size) {
                int k = 0;
                for (int j = start; j < start + half; j++) {
                    double ar = re[j + half], ai = im[j + half];
                    double tr = twr[k] * ar - twi[k] * ai;
                    double ti = twr[k] * ai + twi[k] * ar;
                    double ur = re[j], ui = im[j];
                    re[j] = ur + tr;
                    im[j] = ui + ti;
                    re[j + half] = ur - tr;
                    im[j + half] = ui - ti;
                    k += step;
                }
            }
        }
        double[] out = new double[n / 2];
        for (int i = 0; i < n / 2; i++) out[i] = Math.hypot(re[i], im[i]);
        return out;
    }
}
