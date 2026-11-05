package com.cornholio.ncs;

/**
 * Android's Visualizer hands out snapshots: the last n 8-bit samples of what
 * the phone plays, read from one ring buffer, so two snapshots overlap byte
 * for byte. This finds each new snapshot's overlap with the one before (the
 * nearest to what the time since then predicts) and passes on only the new
 * samples, a continuous stream; then a low-pass and a resampler bring it to
 * cornholio's 16 kHz.
 */
public final class Stitch {
    private final int n;
    private final double rate;
    private final byte[] prev;
    private boolean havePrev;
    private long prevNs;
    public int gaps, joins, lastFresh;

    // the low-pass (windowed sinc) and the resampler to 16 kHz
    private final float[] taps;
    private final float[] hist;
    private int hpos;
    private final double step;
    private double pos;                    // where the next 16 kHz sample falls, in filtered input samples after `last`
    private float last;
    private boolean haveLast;
    private final double[] out = new double[8192];

    public interface Sink {
        void samples(double[] s, int count);
    }

    public Stitch(int captureSize, int sampleRate) {
        n = captureSize;
        rate = sampleRate;
        prev = new byte[n];
        int T = 63;
        taps = new float[T];
        double fc = 7400.0 / sampleRate;            // under 16 kHz's Nyquist, over the tap's 7.2 kHz
        double sum = 0;
        for (int i = 0; i < T; i++) {
            double x = i - (T - 1) / 2.0;
            double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (T - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (T - 1));
            taps[i] = (float) (sinc * w);
            sum += sinc * w;
        }
        for (int i = 0; i < T; i++) taps[i] = (float) (taps[i] / sum);
        hist = new float[T];
        step = sampleRate / (double) AudioTap.RATE;
    }

    /** A new snapshot taken at nanoTime t: pass on its new samples, resampled. */
    public void snapshot(byte[] cap, long t, Sink sink) {
        int fresh;
        if (!havePrev) {
            fresh = n;
        } else {
            boolean same = true;
            for (int i = 0; i < n; i++) if (cap[i] != prev[i]) { same = false; break; }
            double expect = (t - prevNs) * rate / 1e9;
            if (same) {
                boolean hush = true;
                for (int i = 0; i < n; i++) if (cap[i] != (byte) 0x80) { hush = false; break; }
                if (!hush) return;                             // nothing new has been played since
                // silence flows as silence: as many samples as the time that passed
                int k = (int) Math.min(rate, Math.round(expect));
                if (k <= 0) return;
                prevNs = t;
                lastFresh = k;
                emitSilence(k, sink);
                return;
            }
            fresh = -1;
            int minOverlap = Math.min(96, n / 4);
            int e = (int) Math.round(Math.max(1, Math.min(n - minOverlap, expect)));
            for (int k = 0; k <= 2 * n && fresh < 0; k++) {
                int d = (k & 1) == 0 ? e + k / 2 : e - (k + 1) / 2;   // nearest to the expectation first
                if (d < 1 || d > n - minOverlap) continue;
                boolean ok = true;
                for (int i = 0; i < n - d; i++) if (cap[i] != prev[i + d]) { ok = false; break; }
                if (ok) fresh = d;
            }
            if (fresh < 0) {
                fresh = n;                                     // too long since: a gap, take it all
                gaps++;
            } else {
                joins++;
            }
        }
        System.arraycopy(cap, 0, prev, 0, n);
        havePrev = true;
        prevNs = t;
        lastFresh = fresh;
        int count = 0;
        for (int i = n - fresh; i < n; i++) {
            count = push(((cap[i] & 0xFF) - 128) / 128.0f, count, sink);
        }
        if (count > 0) sink.samples(out, count);
    }

    private void emitSilence(int k, Sink sink) {
        int count = 0;
        for (int i = 0; i < k; i++) count = push(0f, count, sink);
        if (count > 0) sink.samples(out, count);
    }

    /** One input sample through the low-pass and the resampler; returns how many are waiting in out. */
    private int push(float x, int count, Sink sink) {
        {
            hist[hpos] = x;
            hpos = (hpos + 1) % hist.length;
            float y = 0;
            int T = taps.length;
            for (int k = 0; k < T; k++) y += taps[k] * hist[(hpos + k) % T];
            if (!haveLast) {
                last = y;
                haveLast = true;
                return count;
            }
            // 16 kHz samples between `last` (at 0) and y (at 1)
            while (pos < 1.0) {
                out[count++] = last + (y - last) * pos;
                pos += step;
                if (count == out.length) {
                    sink.samples(out, count);
                    count = 0;
                }
            }
            pos -= 1.0;
            last = y;
            return count;
        }
    }
}
