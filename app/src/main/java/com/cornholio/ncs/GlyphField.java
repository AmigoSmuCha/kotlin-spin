package com.cornholio.ncs;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The flow behind everything (asciify-engine's glyph field, as cornholio
 * re-made it): slow interfering waves, quantised into ' .,:-+' and the song's
 * own letters, quiet where the words are. A beat sends a thin ring out; when
 * the song changes, its new letters sweep out like a ripple; a finger leaves a
 * wake where sparse glyphs fill in and dense ones open up.
 */
public final class GlyphField {

    static final String INK_ORDER = "L7T1IYJCF2S3V5Z4XPGEA6U9KHDOR08W&MNBQ";
    static final String FIELD_BASE = " .,:-+";
    private static final int[][] BAYER4 = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    static double bayer(int x, int y) {
        return (BAYER4[y & 3][x & 3] + 0.5) / 16;
    }

    private static final Map<Character, String> FOLD = new HashMap<>();
    static {
        String[][] f = {{"ø", "o"}, {"Ø", "O"}, {"ł", "l"}, {"Ł", "L"}, {"đ", "d"}, {"Đ", "D"}, {"ß", "SS"}, {"ẞ", "SS"},
            {"æ", "AE"}, {"Æ", "AE"}, {"œ", "OE"}, {"Œ", "OE"}, {"þ", "TH"}, {"Þ", "TH"}, {"ı", "i"}};
        for (String[] p : f) FOLD.put(p[0].charAt(0), p[1]);
    }

    /** 'Řeka Ůsvitů' to 'REKA USVITU': plain capitals, no marks. */
    static String foldTitle(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String r = FOLD.get(c);
            sb.append(r != null ? r : String.valueOf(c));
        }
        String t = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replaceAll("\\s+", " ").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** ' .,:-+' and the song's own letters, lightest to densest. */
    static String titleCharset(String title, int most) {
        List<Character> letters = new ArrayList<>();
        for (char c : foldTitle(title == null ? "" : title).toCharArray()) {
            if (INK_ORDER.indexOf(c) >= 0 && !letters.contains(c)) letters.add(c);
        }
        if (letters.isEmpty()) {
            letters.add('M');
            letters.add('U');
            letters.add('R');
        }
        letters.sort((a, b) -> Integer.compare(INK_ORDER.indexOf(a), INK_ORDER.indexOf(b)));
        if (letters.size() > most) {
            List<Character> pick = new ArrayList<>();
            for (int i = 0; i < most; i++) pick.add(letters.get((int) Math.rint(i * (letters.size() - 1) / (double) (most - 1))));
            letters = pick;
        }
        StringBuilder sb = new StringBuilder(FIELD_BASE);
        for (char c : letters) sb.append(c);
        return sb.toString();
    }

    private String title;
    String charset = titleCharset("", 9);
    private String old;
    private double switchT = -10.0;
    private double ox, oy;
    private final List<double[]> pulses = new ArrayList<>();
    double t = 30.0;
    private int colW = -1;
    private double[][] cols;
    private int vigW = -1, vigH = -1;
    private double[][] vig;
    private int baseW = -1, baseH = -1;
    private double baseT = -1.0;
    private double[][] baseRows;
    private final Map<Integer, double[]> dith = new HashMap<>();
    private Object heldKey;
    private double heldT = -1.0;
    private final Map<Integer, Object[]> held = new HashMap<>();

    public void setTitle(String title, double now, double originX, double originY) {
        if (title.equals(this.title)) return;
        if (this.title != null) {
            old = charset;
            switchT = now;
            ox = originX;
            oy = originY;
        }
        this.title = title;
        charset = titleCharset(title, 9);
    }

    public void pulse(double x, double y, double now, double strength) {
        pulses.add(new double[]{x, y, now, strength});
        while (pulses.size() > 3) pulses.remove(0);
    }

    private double[][] colgrid(int w) {
        if (colW != w) {
            double[] ks = {0.11, 0.047, 0.29};
            cols = new double[w][6];
            for (int x = 0; x < w; x++)
                for (int k = 0; k < 3; k++) {
                    cols[x][2 * k] = Math.sin(x * ks[k]);
                    cols[x][2 * k + 1] = Math.cos(x * ks[k]);
                }
            colW = w;
        }
        return cols;
    }

    private double[][] vignette(int w, int h) {
        double amount = 0.3;
        if (vigW != w || vigH != h) {
            vig = new double[h][w];
            for (int y = 0; y < h; y++) {
                double dy = (y + 0.5) / h - 0.5;
                for (int x = 0; x < w; x++) {
                    double dx = (x + 0.5) / w - 0.5;
                    vig[y][x] = Math.max(0.0, 1 - amount * 2.2 * (dx * dx * 0.8 + dy * dy));
                }
            }
            vigW = w;
            vigH = h;
        }
        return vig;
    }

    private double[] dither(int y, int w) {
        int key = ((y & 3) << 16) | w;
        double[] row = dith.get(key);
        if (row == null) {
            row = new double[w];
            for (int x = 0; x < w; x++) row[x] = 1.0 - bayer(x, y);
            dith.put(key, row);
        }
        return row;
    }

    /** The flow itself, recomputed fifteen times a second of scene time. */
    double[][] base(int w, int h) {
        if (baseW == w && baseH == h && Math.abs(t - baseT) < 1 / 15.0) return baseRows;
        double[][] cg = colgrid(w);
        double[][] vg = vignette(w, h);
        double[][] rows = new double[h][];
        for (int y = 1; y < h - 1; y++) {
            double a1 = 1.8 * Math.sin(y * 0.21 + t * 0.23) + t * 0.31;
            double a2 = -y * 0.19 + t * 0.17;
            double a3 = y * 0.53 - t * 0.6;
            double ca1 = Math.cos(a1), sa1 = Math.sin(a1), ca2 = Math.cos(a2), sa2 = Math.sin(a2), ca3 = Math.cos(a3), sa3 = Math.sin(a3);
            double[] row = new double[w];
            for (int x = 0; x < w; x++) {
                double[] cc = cg[x];
                double f2 = cc[2] * ca2 + cc[3] * sa2;
                double v = 0.5 + 0.30 * (cc[0] * ca1 + cc[1] * sa1) * f2 + 0.18 * f2 + 0.08 * (cc[4] * ca3 + cc[5] * sa3);
                if (v > 0.5) {
                    double q = (v - 0.5) * 2.0;
                    row[x] = q * q * vg[y][x];
                } else {
                    row[x] = 0.0;
                }
            }
            rows[y] = row;
        }
        baseW = w;
        baseH = h;
        baseT = t;
        baseRows = rows;
        return rows;
    }

    /**
     * Draws the field. mask[y][x]: where it may show; wake: the finger's wake
     * per cell (0..0.8), or null; skipRows: rows left alone.
     */
    public void draw(Cells cv, Theme C, double now, double dt, double dim, double[][] mask, double[] wake,
                     double energy, double bass, double speed, boolean[] skipRows) {
        int w = cv.w, h = cv.h;
        t += dt * speed;
        double[][] rows = base(w, h);
        double gain = 0.62 + 0.55 * energy + 0.45 * bass;
        double sweep = now - switchT;
        String old = (this.old != null && !this.old.isEmpty() && sweep < 2.2) ? this.old : null;
        double front = sweep * Math.max(w * 0.5, h) * 0.75;
        for (int i = pulses.size() - 1; i >= 0; i--) if (!(now - pulses.get(i)[2] < 2.2)) pulses.remove(i);
        List<double[]> rings = new ArrayList<>();
        for (double[] p : pulses) {
            double age = now - p[2];
            double amp = p[3] * Math.exp(-age * 1.3) * 0.55;
            if (amp > 0.03) rings.add(new double[]{p[0], p[1], age * 0.6 * h, amp, Math.max(0.9, 0.028 * h)});
        }
        int ink = Theme.mix(C.accent2, C.fg, 0.3);
        double kDim = 0.4 * (1 - dim);
        String cs = charset;
        int n1 = cs.length() - 1;
        int[] colsK = new int[n1 + 1];
        for (int k = 1; k <= n1; k++) colsK[k] = Theme.mix(C.bg, ink, (0.12 + 0.88 * Math.pow((double) k / n1, 1.3)) * kDim);
        int o1 = 0;
        int[] ocols = null;
        if (old != null) {
            o1 = old.length() - 1;
            ocols = new int[o1 + 1];
            for (int k = 1; k <= o1; k++) ocols[k] = Theme.mix(C.bg, ink, (0.12 + 0.88 * Math.pow((double) k / o1, 1.3)) * kDim);
        }
        boolean[] wrow = new boolean[h];
        if (wake != null)
            for (int y = 1; y < h - 1; y++)
                for (int x = 0; x < w; x++) if (wake[y * w + x] > 0) { wrow[y] = true; break; }
        char[] chs = cv.ch;
        int[] fgs = cv.fg;
        Object key = java.util.Arrays.asList(w, h, System.identityHashCode(mask), cs, dim);
        boolean full = old != null || !key.equals(heldKey) || now - heldT >= 1 / 15.0;
        if (full) {
            heldKey = key;
            heldT = now;
            held.clear();
        }
        for (int y = 1; y < h - 1; y++) {
            if (skipRows != null && skipRows[y]) continue;
            boolean busy = wrow[y];
            if (!busy) for (double[] r : rings) if (Math.abs(y - r[1]) < r[2] + r[4] * 2.0) { busy = true; break; }
            int a = y * w;
            if (!full && !busy && held.containsKey(y)) {
                Object[] hr = held.get(y);
                char[] hc = (char[]) hr[0];
                int[] hf = (int[]) hr[1];
                for (int x = 0; x < w; x++) {
                    if (hc[x] != ' ') {
                        chs[a + x] = hc[x];
                        fgs[a + x] = hf[x];
                    }
                }
                continue;
            }
            double[] mrow = mask[y];
            double[] brow = rows[y];
            double[] v = new double[w];
            for (int x = 0; x < w; x++) v[x] = brow[x] * mrow[x] * gain;
            for (double[] r : rings) {
                double px = r[0], py = r[1], fr = r[2], amp = r[3], width = r[4];
                double dy = y - py;
                double rout = fr + width * 2.0;
                if (Math.abs(dy) >= rout) continue;
                double rin = fr - width * 2.0;
                double xo = Math.sqrt(rout * rout - dy * dy) * 2;
                double xi = rin > Math.abs(dy) ? Math.sqrt(rin * rin - dy * dy) * 2 : 0.0;
                double[][] spans = {{px - xo, px - xi}, {px + xi, px + xo}};
                for (double[] sp : spans) {
                    int xa = Math.max(0, (int) sp[0]), xb = Math.min(w, (int) sp[1] + 1);
                    for (int x = xa; x < xb; x++) {
                        double q = (Math.hypot((x - px) * 0.5, dy) - fr) / width;
                        v[x] += amp * Math.exp(-q * q) * mrow[x];
                    }
                }
            }
            if (wrow[y]) {                                     // the trail: sparse fills in, dense opens up
                for (int x = 0; x < w; x++) {
                    double wv = wake[a + x];
                    if (wv <= 0) continue;
                    double tt = v[x] < 1 ? v[x] : 1.0;
                    v[x] = tt + wv * mrow[x] * (1 - 2 * tt);
                }
            }
            double[] dth = dither(y, w);
            int[] ks = new int[w];
            for (int x = 0; x < w; x++) {
                double tt = v[x];
                ks[x] = tt < 0.02 ? 0 : Math.min(n1, (int) ((tt < 0.98 ? tt : 0.98) * n1 + dth[x]));
            }
            if (old == null) {
                char[] rc = new char[w];
                int[] rf = new int[w];
                for (int x = 0; x < w; x++) {
                    int k = ks[x];
                    rc[x] = k != 0 ? cs.charAt(k) : ' ';
                    rf[x] = k != 0 ? colsK[k] : 0;
                    if (rc[x] != ' ') {
                        chs[a + x] = rc[x];
                        fgs[a + x] = rf[x];
                    }
                }
                held.put(y, new Object[]{rc, rf});
                continue;
            }
            double ddy = y - oy;
            double reach = front > Math.abs(ddy) ? Math.sqrt(Math.max(0.0, front * front - ddy * ddy)) * 2 : -1;
            for (int x = 0; x < w; x++) {
                if (Math.abs(x - ox) <= reach) {
                    int k = ks[x];
                    if (k != 0) {
                        chs[a + x] = cs.charAt(k);
                        fgs[a + x] = colsK[k];
                    }
                } else {
                    double tt = v[x];
                    if (tt >= 0.02) {
                        int k = Math.min(o1, (int) ((tt < 0.98 ? tt : 0.98) * o1 + dth[x]));
                        if (k != 0) {
                            chs[a + x] = old.charAt(k);
                            fgs[a + x] = ocols[k];
                        }
                    }
                }
            }
        }
    }
}
