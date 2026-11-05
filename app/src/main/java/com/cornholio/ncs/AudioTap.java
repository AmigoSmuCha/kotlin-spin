package com.cornholio.ncs;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * What NCS hears, as cornholio's AudioTap: 32 bands (0..1) from 45 Hz to
 * 7.2 kHz, their onsets, the bass, the loudness, beats and hits, and whether
 * it is silence; the harmony for Lorn; the spectrum for Diffision. Fed 16 kHz
 * mono samples (the phone's sound, resampled), or it plays its own demo song
 * through the very same analysis. The gain follows the music but has a floor:
 * near-silence is never stretched to full height.
 */
public final class AudioTap {

    public static final int RATE = 16000, N = 512, BANDS = 32;
    static final double LO = 45.0, HI = 7200.0;
    static final double GATE_DB = -62.0, CEIL_MIN = -38.0, RANGE = 40.0, RELEASE = 4.0;
    static final int LONG = 4120;
    static final double HOP = 1 / 15.0;
    static final int PITCH_LO = 36, PITCH_HI = 96;

    /** Everything a visualizer may use, copied at once. */
    public static final class Fr {
        public double[] levels, raw, flux, peaks;
        public double energy, bass, db;
        public boolean silent;
        public int beats, hits;
        public double beatT;
    }

    /** The harmony, as last heard (see harmony()). */
    public static final class Harm {
        public double[] chroma, notes;
        public int melody;
        public double sure, centroid, flatness, t;
    }

    public interface Clock {
        double now();
    }

    private final Clock clock;
    public final boolean demo;
    public volatile String state = "off";
    private double wantUntil;
    public volatile double gotData;

    private final double[] levels = new double[BANDS], peaks = new double[BANDS];
    private double[] raw = new double[BANDS], flux = new double[BANDS];
    private double energy, bass, db = -120.0;
    private boolean silent = true;
    private double beatT = -10, hitT = -10;
    private int beats, hits;
    private double ceil = CEIL_MIN, quietFor, aclock, lastBeat = -10, lastHit = -10;
    private final ArrayDeque<Double> bflux = new ArrayDeque<>(), hflux = new ArrayDeque<>();
    private Spectrum spec;
    private int[][] edges;
    private double[] edgeTilt;
    private final double[] longBuf = new double[LONG];
    private int longLen, longPos;
    private double[] mags;
    private Harm harm;
    private Spectrum pitchSpec;
    private int[] ptK, ptP;
    private double[] ptW;

    // the demo's song
    private double dT, dAcc, dGap, dBpm = 118.0, dRoot = 55.0, dPrevNoise, dCarry, dLp, dLead;
    private final double[] dSaw = new double[3];
    private final double[] dRing = new double[N];
    private String dSong;
    private boolean dFirstSong = true;
    private final PyRandom dRng = PyRandom.python(7);

    // live listening: the last N samples as heard
    private final double[] ring = new double[N];
    private double lastAnalysed = -1;

    public AudioTap(boolean demo, Clock clock) {
        this.demo = demo;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ life cycle
    /** Called every frame the scene needs the music; starts on demand. */
    public void want(double now) {
        wantUntil = now + 3.0;
        if (state.equals("off")) start();
    }

    public void start() {
        state = demo ? "demo" : "listening";
    }

    public void stop() {
        synchronized (this) {
            longLen = 0;
            longPos = 0;
        }
        harm = null;
        if (state.equals("listening") || state.equals("demo")) state = "off";
    }

    /** The song changed: the demo's little song changes with it, after a moment of silence. */
    public void song(String key) {
        if (key.equals(dSong)) return;
        boolean first = dSong == null;
        dSong = key;
        long h;
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(key.getBytes("UTF-8"));
            h = ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFFL) << 16) | ((d[2] & 0xFFL) << 8) | (d[3] & 0xFFL);
        } catch (Exception e) {
            h = 0;
        }
        dBpm = 92.0 + h % 41;
        double[] roots = {41.2, 43.65, 46.25, 49.0, 51.9, 55.0, 58.3, 61.7};
        dRoot = roots[(int) ((h >> 8) % 8)];
        dT = 0.0;
        if (!first) dGap = 1.3;
    }

    public void tick(double now, boolean playing, double dt) {
        if ((state.equals("listening") || state.equals("demo")) && now > wantUntil) {
            stop();
            return;
        }
        if (state.equals("demo")) synth(dt, playing);
        if (!state.equals("demo")) {
            synchronized (this) {
                if (now - gotData > 0.4) {                     // no data: let it settle
                    double k = Math.exp(-dt * 3.0);
                    for (int i = 0; i < BANDS; i++) levels[i] = levels[i] * k;
                    raw = new double[BANDS];
                    flux = new double[BANDS];
                    energy *= k;
                    bass *= k;
                    silent = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ live samples
    /** Fresh 16 kHz samples (-1..1) as they arrive, as the tap's reader takes them from parec. */
    public void feed(double[] fresh, int n, double now) {
        synchronized (this) {
            int keep = Math.min(n, N);
            System.arraycopy(ring, keep, ring, 0, N - keep);
            System.arraycopy(fresh, n - keep, ring, N - keep, keep);
            for (int i = 0; i < n; i++) longAppend(fresh[i]);
        }
        gotData = now;
        if (lastAnalysed < 0) lastAnalysed = now;
        if (now - lastAnalysed >= 1 / 30.0) {                 // thirty looks a second is plenty
            analyse(ring.clone(), now - lastAnalysed);
            lastAnalysed = now;
        }
    }

    private void longAppend(double v) {
        longBuf[longPos] = v;
        longPos = (longPos + 1) % LONG;
        if (longLen < LONG) longLen++;
    }

    // ------------------------------------------------------------------ analysis
    private void bandEdges() {
        if (edges != null) return;
        double ratio = HI / LO;
        edges = new int[BANDS][2];
        edgeTilt = new double[BANDS];
        for (int b = 0; b < BANDS; b++) {
            double f0 = LO * Math.pow(ratio, (double) b / BANDS);
            double f1 = LO * Math.pow(ratio, (double) (b + 1) / BANDS);
            int k0 = Math.max(1, (int) (f0 * N / RATE));
            int k1 = Math.max(k0 + 1, (int) Math.ceil(f1 * N / RATE));
            double fc = Math.sqrt(f0 * f1);
            edges[b][0] = k0;
            edges[b][1] = k1;
            edgeTilt[b] = 4.0 * (Math.log(fc / 250.0) / Math.log(2));
        }
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }

    private void analyse(double[] samples, double dtIn) {
        if (spec == null) spec = new Spectrum(N);
        double dt = Math.max(1e-3, dtIn);
        double[] m = spec.mags(samples);
        double ss = 0;
        for (double s : samples) ss += s * s;
        double rms = Math.sqrt(ss / samples.length);
        double d = 20 * Math.log10(rms + 1e-12);
        quietFor = d < GATE_DB ? quietFor + dt : 0.0;
        boolean sil;
        if (silent) sil = !(d > GATE_DB + 3.0);
        else sil = quietFor > 0.3;
        double gate = sil ? 0.0 : Math.min(1.0, Math.max(0.0, (d - GATE_DB) / 9.0));
        double scale = 4.0 / N;
        bandEdges();
        double[] L = new double[BANDS];
        double top = Double.NEGATIVE_INFINITY;
        for (int b = 0; b < BANDS; b++) {
            double mx = Double.NEGATIVE_INFINITY;
            for (int k = edges[b][0]; k < edges[b][1]; k++) mx = Math.max(mx, m[k]);
            L[b] = 20 * Math.log10(mx * scale + 1e-12) + edgeTilt[b];
            if (b == 0 || L[b] > top) top = L[b];
        }
        if (!sil) ceil = top > ceil ? top : ceil - RELEASE * dt;
        ceil = Math.max(ceil, CEIL_MIN);
        double floor = ceil - RANGE;
        double[] r = new double[BANDS];
        for (int b = 0; b < BANDS; b++) r[b] = Math.pow(Math.min(1.0, Math.max(0.0, (L[b] - floor) / RANGE)), 1.5) * gate;
        double up = 1 - Math.exp(-dt / 0.025);
        double down = 1 - Math.exp(-dt / 0.14);
        double loud = sil ? 0.0 : Math.min(1.0, Math.max(0.0, (d + 50.0) / 38.0));
        aclock += dt;
        double c = aclock;
        synchronized (this) {
            mags = m;
            double[] prev = raw;
            double[] fl = new double[BANDS];
            for (int i = 0; i < BANDS; i++) fl[i] = r[i] > prev[i] ? r[i] - prev[i] : 0.0;
            raw = r;
            flux = fl;
            db = d;
            silent = sil;
            for (int i = 0; i < BANDS; i++) {
                double v = r[i];
                levels[i] += (v - levels[i]) * (v > levels[i] ? up : down);
                peaks[i] = Math.max(levels[i], peaks[i] - 0.6 * dt);
            }
            double bs = (r[0] + r[1] + r[2] + r[3] + r[4]) / 5;
            bass += (bs - bass) * (bs > bass ? up : 1 - Math.exp(-dt / 0.2));
            energy += (loud - energy) * (loud > energy ? up : 1 - Math.exp(-dt / 0.3));
            double bf = 0;
            for (int i = 0; i < 6; i++) bf += fl[i];
            bf /= 6;
            double hf = 0;
            for (int i = 8; i < BANDS; i++) hf += fl[i];
            hf /= (BANDS - 8);
            for (int kind = 0; kind < 2; kind++) {
                double val = kind == 0 ? bf : hf;
                ArrayDeque<Double> hist = kind == 0 ? bflux : hflux;
                double thr;
                if (hist.size() >= 8) {
                    double[] sorted = new double[hist.size()];
                    int i = 0;
                    for (double x : hist) sorted[i++] = x;
                    Arrays.sort(sorted);
                    thr = sorted[(int) (sorted.length * 0.75)] + 0.03;
                } else {
                    thr = 0.05;
                }
                if (!sil && val > thr) {
                    if (kind == 0 && bs > 0.22 && c - lastBeat > 0.3) {
                        lastBeat = c;
                        beatT = clock.now();
                        beats++;
                    } else if (kind == 1 && c - lastHit > 0.12) {
                        lastHit = c;
                        hitT = clock.now();
                        hits++;
                    }
                }
                hist.addLast(val);
                if (hist.size() > 48) hist.removeFirst();
            }
        }
    }

    // ------------------------------------------------------------------ the demo's song
    private void synth(double dt, boolean playing) {
        double want = RATE * dt + dCarry;
        int n = (int) want;
        dCarry = want - n;
        if (n <= 0) return;
        double[] fresh;
        int m = Math.min(n, N * 2);
        if (!playing || dGap > 0) {
            dGap = Math.max(0.0, dGap - dt);
            fresh = new double[m];
        } else {
            fresh = demoSamples(m);
        }
        synchronized (this) {
            int keep = Math.min(m, N);
            System.arraycopy(dRing, keep, dRing, 0, N - keep);
            System.arraycopy(fresh, m - keep, dRing, N - keep, keep);
            for (int i = 0; i < m; i++) longAppend(fresh[i]);
        }
        dAcc += dt;
        if (dAcc >= 1 / 30.0) {
            gotData = clock.now();
            analyse(dRing.clone(), dAcc);
            dAcc = 0.0;
        }
    }

    private static final double[] PROG = {1.0, 0.794, 1.189, 0.891};
    private static final double[] MELODY = {2.0, 2.378, 2.67, 3.0, 3.56, 2.67, 2.378, 2.0};
    private static final double[] ARP = {1.0, 1.26, 1.5, 2.0};

    private static double pmod(double a, double b) {
        double r = a % b;
        if (r != 0 && (r < 0) != (b < 0)) r += b;
        return r;
    }

    private double[] demoSamples(int n) {
        double R = RATE;
        double beat = 60.0 / dBpm;
        double root = dRoot;
        double tau = 2 * Math.PI;
        double[] out = new double[n];
        double t0 = dT;
        double pn = dPrevNoise, lp = dLp, lead = dLead;
        double[] saw = dSaw;
        for (int i = 0; i < n; i++) {
            double t = t0 + i / R;
            double b = t / beat;
            int bar = (int) Math.floor(b / 4);
            int section = Math.floorMod(bar, 16);
            boolean kickOn = section < 8 || section >= 12;
            boolean arpOn = section >= 8;
            double ph = b - (int) b;
            double kt = ph * beat;
            double f = root * PROG[Math.floorMod(bar, 4)];
            double s = 0.0;
            if (kickOn && kt < 0.3) s += 0.55 * Math.sin(tau * (45 * kt + 2.6 * (1 - Math.exp(-30 * kt)))) * Math.exp(-kt * 9);
            double pump = kickOn ? 0.3 + 0.7 * Math.min(1.0, kt * 5) : 1.0;
            double calm = kickOn ? 1.0 : 0.3;
            s += 0.16 * pump * calm * Math.sin(tau * f * t);
            double rawPad = 0.0;
            double[] muls = {4.0, 5.0, 6.0};
            for (int v = 0; v < 3; v++) {
                saw[v] = pmod(saw[v] + f * muls[v] / R, 1.0);
                rawPad += saw[v] * 2 - 1;
            }
            lp += (rawPad - lp) * 0.32;
            s += pump * 0.045 * (calm + 0.4) / 1.4 * lp * (0.75 + 0.25 * Math.sin(tau * 0.25 * t));
            if (Math.floorMod(section, 8) >= 4) {
                double et = pmod(b * 2, 1.0) * beat / 2;
                double note = root * 8 * MELODY[Math.floorMod((int) (b * 2), 8)];
                lead = pmod(lead + note / R, 1.0);
                s += 0.05 * (lead < 0.5 ? 1.0 : -1.0) * Math.exp(-et * 5);
            }
            double noise = dRng.random() * 2 - 1;
            double hp = noise - pn;
            pn = noise;
            double ht = pmod(b * 2, 1.0) * beat / 2;
            s += 0.07 * calm * hp * Math.exp(-ht * 45);
            int ib = (int) b;
            if ((Math.floorMod(ib, 4) == 1 || Math.floorMod(ib, 4) == 3) && kickOn)
                s += (0.16 * noise + 0.1 * Math.sin(tau * 190 * kt)) * Math.exp(-kt * 16);
            if (arpOn) {
                double st = pmod(b * 4, 1.0) * beat / 4;
                int step = Math.floorMod((int) (b * 4), 4);
                s += 0.05 * Math.pow(calm, 0.5) * Math.sin(tau * f * 8 * ARP[step] * t) * Math.exp(-st * 12);
            }
            out[i] = Math.max(-1.0, Math.min(1.0, s));
        }
        dT = t0 + n / R;
        dPrevNoise = pn;
        dLp = lp;
        dLead = lead;
        return out;
    }

    /** The demo song itself, as 16 kHz samples (for tests of the listening). */
    public static double[] demoSong(double seconds) {
        AudioTap t = new AudioTap(true, () -> 0.0);
        t.song("");
        int n = (int) (seconds * RATE);
        double[] out = new double[n];
        int done = 0;
        while (done < n) {
            int k = Math.min(1024, n - done);
            System.arraycopy(t.demoSamples(k), 0, out, done, k);
            done += k;
        }
        return out;
    }

    // ------------------------------------------------------------------ the harmony: what Lorn reads
    private void pitchTables() {
        if (pitchSpec != null) return;
        double fs = RATE / 4.0;
        int nf = 1024;
        int n = PITCH_HI - PITCH_LO;
        int[] k = new int[4096], p = new int[4096];
        double[] w = new double[4096];
        int cnt = 0;
        for (int kk = 1; kk < nf / 2; kk++) {
            double f = kk * fs / nf;
            if (f < 58 || f > 2050) continue;
            double mm = 69 + 12 * log2(f / 440.0) - PITCH_LO;
            int i = (int) Math.floor(mm);
            double t = mm - i;
            int[] ps = {i, i + 1};
            double[] ws = {1 - t, t};
            for (int q = 0; q < 2; q++) {
                if (ps[q] >= 0 && ps[q] < n && ws[q] > 0.05) {
                    k[cnt] = kk;
                    p[cnt] = ps[q];
                    w[cnt] = ws[q];
                    cnt++;
                }
            }
        }
        ptK = Arrays.copyOf(k, cnt);
        ptP = Arrays.copyOf(p, cnt);
        ptW = Arrays.copyOf(w, cnt);
        pitchSpec = new Spectrum(nf);
    }

    /**
     * What is sounding, heard anew fifteen times a second over the last quarter
     * second: the chromagram, the semitones, the top voice and how sure, the
     * spectral centroid and flatness. Null while nothing is heard.
     */
    public Harm harmony() {
        double now = clock.now();
        Harm h = harm;
        if (h != null && now - h.t < HOP) return h;
        double[] x;
        synchronized (this) {
            if (!(state.equals("listening") || state.equals("demo")) || now - gotData > 0.4 || longLen < LONG || silent) {
                harm = null;
                return null;
            }
            x = new double[LONG];
            for (int i = 0; i < LONG; i++) x[i] = longBuf[(longPos + i) % LONG];
        }
        pitchTables();
        for (int pass = 0; pass < 2; pass++) {                 // 16 kHz to 4 kHz, a binomial half-band each time
            int len = x.length;
            int cnt = 0;
            for (int i = 2; i < len - 2; i += 2) cnt++;
            double[] y = new double[cnt];
            int j = 0;
            for (int i = 2; i < len - 2; i += 2) y[j++] = (x[i - 2] + 4 * x[i - 1] + 6 * x[i] + 4 * x[i + 1] + x[i + 2]) * 0.0625;
            x = y;
        }
        x = Arrays.copyOfRange(x, x.length - 1024, x.length);
        double[] mg = pitchSpec.mags(x);
        int n = PITCH_HI - PITCH_LO;
        double[] sp = new double[n];
        for (int q = 0; q < ptK.length; q++) {
            double v = mg[ptK[q]] * ptW[q];
            if (v > sp[ptP[q]]) sp[ptP[q]] = v;
        }
        double top = 0;
        for (double v : sp) top = Math.max(top, v);
        if (top == 0) top = 1e-12;
        double k30 = Math.log(31.0);
        double[] notes = new double[n];
        for (int q = 0; q < n; q++) notes[q] = Math.log1p(30 * sp[q] / top) / k30;
        double[] chroma = new double[12];
        for (int q = 0; q < n; q++) chroma[(q + PITCH_LO) % 12] += notes[q];
        double[] srt = chroma.clone();
        Arrays.sort(srt);
        double floor = srt[6];
        for (int q = 0; q < 12; q++) chroma[q] = chroma[q] > floor ? chroma[q] - floor : 0.0;
        double cm = 0;
        for (double v : chroma) cm = Math.max(cm, v);
        if (cm > 1e-9) for (int q = 0; q < 12; q++) chroma[q] = chroma[q] / cm;
        else Arrays.fill(chroma, 0.0);
        // the top voice
        int lo = 60 - PITCH_LO;
        double rtop = Double.NEGATIVE_INFINITY;
        int rtopAt = lo;
        for (int q = lo; q < n; q++) if (notes[q] > rtop) { rtop = notes[q]; rtopAt = q; }
        int melody = -1;
        for (int p = n - 1; p >= lo; p--) {
            double v = notes[p];
            if (v < 0.72 * rtop || v < notes[p - 1] || (p + 1 < n && v < notes[p + 1])) continue;
            boolean over = false;
            for (int off : new int[]{12, 19, 24}) if (p - off >= 0 && notes[p - off] > v * 1.05) over = true;
            if (over) continue;
            melody = p;
            break;
        }
        if (melody < 0) melody = rtopAt;
        double[] reg = Arrays.copyOfRange(notes, lo, n);
        double[] regs = reg.clone();
        Arrays.sort(regs);
        double mid = regs[regs.length / 2];
        double sure = (notes[melody] - mid) / (notes[melody] + 1e-9);
        // the timbre, from the newest spectrum (0 .. 8 kHz)
        double[] mm = mags;
        double centroid = 0.0, flat = 0.0;
        if (mm != null) {
            double tot = 0;
            for (int i = 2; i < 200; i++) tot += mm[i];
            if (tot > 1e-12) {
                double wsum = 0;
                for (int i = 2; i < 200; i++) wsum += i * mm[i];
                centroid = wsum / tot * RATE / N;
                double logs = 0;
                for (int i = 2; i < 200; i++) logs += Math.log(mm[i] + 1e-12);
                logs /= 198;
                flat = Math.min(1.0, Math.exp(logs) / (tot / 198));
            }
        }
        Harm out = new Harm();
        out.chroma = chroma;
        out.notes = notes;
        out.melody = melody + PITCH_LO;
        out.sure = Math.max(0.0, Math.min(1.0, sure));
        out.centroid = centroid;
        out.flatness = flat;
        out.t = now;
        harm = out;
        return out;
    }

    public synchronized Fr frame() {
        Fr f = new Fr();
        f.levels = levels.clone();
        f.raw = raw.clone();
        f.flux = flux.clone();
        f.peaks = peaks.clone();
        f.energy = energy;
        f.bass = bass;
        f.db = db;
        f.silent = silent;
        f.beats = beats;
        f.beatT = beatT;
        f.hits = hits;
        return f;
    }

    /** The newest spectrum (256 bins to 8 kHz), a new array each time the tap hears. */
    public synchronized double[] spectrum() {
        return mags;
    }
}
