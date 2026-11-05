package com.cornholio.ncs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Euler's Tonnetz, listening (cornholio's Lorn): the twelve notes on a
 * triangular lattice where neighbours are the consonant intervals and every
 * triangle is a triad. The chromagram lights the notes that sound, the lines
 * between them and the triangles of the chords; the top voice walks the lines
 * in brackets; onsets ripple through it; sixteen beads keep where in the bar
 * they fell; the timbre tints and roughens the light.
 */
public final class Lorn {
    static final double ASP = 0.47;
    static final int SLOTS = 16;
    static final String[] NOTES = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};

    // ---- the lattice
    static final class Node {
        int x, y, pc, i, j;
        String name;
    }

    static final class Edge {
        int a, b;
        int[] k, x, y;
        char[] g;
        double[] t;
    }

    static final class Tri {
        int a, b, c, root;
        boolean minor;
        int[] k, x, y;
        double[] depth;
        int[] label;            // x, y, width, or null
    }

    static final class Lay {
        int v, s, W, H, top, base;
        List<Node> nodes = new ArrayList<>();
        Map<Long, Integer> at = new HashMap<>();
        List<Edge> edges = new ArrayList<>();
        List<List<int[]>> nbrs = new ArrayList<>();     // (other node, edge)
        List<Tri> tris = new ArrayList<>();
        int[] beads;            // rows, or null
    }

    private Lay lay;
    private Object lkey;
    private final double[] cs = new double[12];
    private int[] chord, cand;
    private double candSince = 0.0;
    private double[] focus;
    private int head = -1;
    private double headAt = -10.0;
    private double melSeen = Double.NaN;
    private final Map<Integer, double[]> trail = new HashMap<>();   // edge -> t0, strength, from node
    private final List<double[]> waves = new ArrayList<>();        // x, y, t0, strength, speed, life
    private Integer beats, hits;
    private double period = 0.5, beatAt = -10.0;
    private int nbeat = 0;
    private double[] slotB = new double[SLOTS], slotH = new double[SLOTS];
    private double temp = 0.4, grain = 0.0, presence = 0.0, energy = 0.0, tick = 0.0;
    private final Map<Integer, Double> tmem = new LinkedHashMap<>();
    private double[] field = new double[0];
    private int ctri = -1;
    private final Map<Long, Integer> colCache = new HashMap<>();
    private Object ckey;
    private double[] shade;
    private Object skey;

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    private static int[] upRight(int i, int j) { return j % 2 == 0 ? new int[]{i, j + 1} : new int[]{i + 1, j + 1}; }
    private static int[] upLeft(int i, int j) { return j % 2 == 0 ? new int[]{i - 1, j + 1} : new int[]{i, j + 1}; }
    private static int[] downRight(int i, int j) { return j % 2 == 0 ? new int[]{i, j - 1} : new int[]{i + 1, j - 1}; }

    private Lay layout(int W, int H, int top, int base) {
        Object k = java.util.Arrays.asList(W, H, top, base);
        if (k.equals(lkey)) return lay;
        int room = Math.max(3, base - top + 1);
        int v = Math.max(3, Math.min(6, (int) Math.rint(room / 4.3)));
        if (H < 14) v = 3;
        int s = 2 * v;
        int y0 = H >= 14 ? base - 3 : base;
        List<Integer> rows = new ArrayList<>();
        while (y0 - rows.size() * v >= 1) rows.add(y0 - rows.size() * v);
        int mid = (W - 1) / 2;
        int x0 = Math.floorMod(mid, s);
        Lay L = new Lay();
        L.v = v; L.s = s; L.W = W; L.H = H; L.top = top; L.base = base;
        int iMid = Math.floorDiv(mid - x0, s);
        for (int j = 0; j < rows.size(); j++) {
            int y = rows.get(j);
            int off = (j & 1) != 0 ? v : 0;
            int i = -Math.floorDiv(x0 + off, s) - 1;
            while (true) {
                int x = x0 + off + i * s;
                if (x >= W) break;
                int pc = Math.floorMod(7 * ((i - iMid) - Math.floorDiv(j, 2)) + 4 * j, 12);
                Node n = new Node();
                n.x = x; n.y = y; n.pc = pc; n.name = NOTES[pc]; n.i = i; n.j = j;
                L.nodes.add(n);
                L.at.put(key(i, j), L.nodes.size() - 1);
                i++;
            }
        }
        for (int q = 0; q < L.nodes.size(); q++) L.nbrs.add(new ArrayList<>());
        for (int n = 0; n < L.nodes.size(); n++) {
            Node nd = L.nodes.get(n);
            int x = nd.x, y = nd.y, i = nd.i, j = nd.j;
            String[] kinds = {"h", "ur", "ul"};
            int[][] others = {{i + 1, j}, upRight(i, j), upLeft(i, j)};
            for (int q = 0; q < 3; q++) {
                Integer m = L.at.get(key(others[q][0], others[q][1]));
                if (m == null) continue;
                Node mn = L.nodes.get(m);
                int bx = mn.x;
                List<double[]> cells = new ArrayList<>();
                List<Character> gl = new ArrayList<>();
                if (kinds[q].equals("h")) {
                    int a = x + nd.name.length() + 1, b = bx - 2;
                    for (int cx = a; cx <= b; cx++) {
                        if (cx >= 0 && cx < W && 1 <= y && y <= H - 2) {
                            cells.add(new double[]{y * W + cx, (double) (cx - x) / s, cx, y});
                            gl.add('─');
                        }
                    }
                } else {
                    int step = kinds[q].equals("ur") ? 1 : -1;
                    char g = kinds[q].equals("ur") ? '╱' : '╲';
                    for (int kk = 1; kk < v; kk++) {
                        int cx = x + step * kk, cy = y - kk;
                        if (cx >= 0 && cx < W && 1 <= cy && cy <= H - 2) {
                            cells.add(new double[]{cy * W + cx, (double) kk / v, cx, cy});
                            gl.add(g);
                        }
                    }
                }
                Edge e = new Edge();
                e.a = n; e.b = m;
                int c = cells.size();
                e.k = new int[c]; e.t = new double[c]; e.x = new int[c]; e.y = new int[c]; e.g = new char[c];
                for (int z = 0; z < c; z++) {
                    double[] cc = cells.get(z);
                    e.k[z] = (int) cc[0]; e.t[z] = cc[1]; e.x[z] = (int) cc[2]; e.y[z] = (int) cc[3]; e.g[z] = gl.get(z);
                }
                int ei = L.edges.size();
                L.edges.add(e);
                L.nbrs.get(n).add(new int[]{m, ei});
                L.nbrs.get(m).add(new int[]{n, ei});
            }
        }
        for (int n = 0; n < L.nodes.size(); n++) {
            Node nd = L.nodes.get(n);
            int x = nd.x, y = nd.y, i = nd.i, j = nd.j;
            Integer b = L.at.get(key(i + 1, j));
            if (b == null) continue;
            int[] ur = upRight(i, j), dr = downRight(i, j);
            Integer[] apexes = {L.at.get(key(ur[0], ur[1])), L.at.get(key(dr[0], dr[1]))};
            int[] signs = {-1, 1};
            boolean[] minors = {false, true};
            for (int q = 0; q < 2; q++) {
                Integer apex = apexes[q];
                if (apex == null) continue;
                int sign = signs[q];
                List<double[]> cells = new ArrayList<>();
                int[] label = null;
                int want = Math.max(1, (int) Math.rint(v / 3.0));
                for (int kk = 1; kk < v; kk++) {
                    int cy = y + sign * kk;
                    int lo = x + kk + 1, hi = x + s - kk - 1;
                    for (int cx = lo; cx <= hi; cx++) {
                        if (cx >= 0 && cx < W && 1 <= cy && cy <= H - 2) {
                            double depth = hi > lo ? Math.min(cx - lo, hi - cx) / Math.max(1.0, (hi - lo) / 2.0) : 1.0;
                            cells.add(new double[]{cy * W + cx, depth, cx, cy});
                        }
                    }
                    if (kk == want) label = new int[]{x + v, cy, hi - lo + 1};
                }
                Tri t = new Tri();
                t.a = n; t.b = b; t.c = apex; t.root = nd.pc; t.minor = minors[q]; t.label = label;
                int c = cells.size();
                t.k = new int[c]; t.depth = new double[c]; t.x = new int[c]; t.y = new int[c];
                for (int z = 0; z < c; z++) {
                    double[] cc = cells.get(z);
                    t.k[z] = (int) cc[0]; t.depth[z] = cc[1]; t.x[z] = (int) cc[2]; t.y[z] = (int) cc[3];
                }
                L.tris.add(t);
            }
        }
        L.beads = H >= 14 ? new int[]{base - 1, base} : null;
        lay = L;
        lkey = k;
        trail.clear();
        head = -1;
        waves.clear();
        tmem.clear();
        return L;
    }

    private double[] shadeOf(int W, int H, double[][] mask) {
        Object k = java.util.Arrays.asList(W, H, System.identityHashCode(mask));
        if (k.equals(skey)) return shade;
        double[] sh = new double[W * H];
        for (int y = 1; y < H - 1; y++) {
            double[] mrow = mask[y];
            for (int x = 0; x < W; x++) {
                double q = mrow[x];
                if (q <= 0.3) continue;
                sh[y * W + x] = q;
            }
        }
        shade = sh;
        skey = k;
        return sh;
    }

    // ---- what the music is doing
    private void chord(double now) {
        double tot = Math.sqrt(sq(cs));
        double bestSc = -1;
        int bestR = -1;
        boolean bestMinor = false;
        boolean have = false;
        if (tot > 0.25) {
            for (int r = 0; r < 12; r++) {
                for (int mi = 0; mi < 2; mi++) {
                    boolean minor = mi == 1;
                    int third = minor ? 3 : 4;
                    double sc = (cs[r] + cs[(r + third) % 12] + cs[(r + 7) % 12]) / (1.7320508 * tot);
                    if (!have || sc > bestSc) {
                        have = true;
                        bestSc = sc;
                        bestR = r;
                        bestMinor = minor;
                    }
                }
            }
        }
        if (!have || bestSc < 0.62) {
            if (chord != null && now - candSince > 0.4) chord = null;
            cand = null;
            return;
        }
        int[] c = {bestR, bestMinor ? 1 : 0};
        if (chord != null && chord[0] == c[0] && chord[1] == c[1]) {
            cand = null;
            return;
        }
        if (cand == null || cand[0] != c[0] || cand[1] != c[1]) {
            cand = c;
            candSince = now;
        }
        if (chord == null || now - candSince > 0.12) {
            chord = cand;
            cand = null;
        }
    }

    private static double sq(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        return s;
    }

    private static double dist(double x0, double y0, double x1, double y1) {
        double dx = (x1 - x0) * ASP, dy = y1 - y0;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private int nearest(Lay L, Set<Integer> pcs, double x, double y, double[] centre, double pull) {
        double bd = 0;
        int bn = -1;
        for (int n = 0; n < L.nodes.size(); n++) {
            Node nd = L.nodes.get(n);
            if (!pcs.contains(nd.pc) || !(L.top - 1 <= nd.y && nd.y <= L.base)) continue;
            double d = dist(x, y, nd.x, nd.y);
            if (centre != null) d += pull * dist(centre[0], centre[1], nd.x, nd.y);
            if (bn < 0 || d < bd) {
                bd = d;
                bn = n;
            }
        }
        return bn;
    }

    /** The shortest walk along the lattice's lines from a to b (at most a few steps), as (edge, from) pairs. */
    private List<int[]> path(Lay L, int a, int b, int most) {
        List<int[]> res = new ArrayList<>();
        if (a == b) return res;
        Map<Integer, int[]> prev = new HashMap<>();
        prev.put(a, null);
        List<Integer> frontier = new ArrayList<>();
        frontier.add(a);
        for (int s = 0; s < most; s++) {
            List<Integer> nxt = new ArrayList<>();
            for (int n : frontier) {
                for (int[] me : L.nbrs.get(n)) {
                    int m = me[0], e = me[1];
                    if (!prev.containsKey(m)) {
                        prev.put(m, new int[]{n, e});
                        if (m == b) {
                            List<int[]> p = new ArrayList<>();
                            int cur = m;
                            while (prev.get(cur) != null) {
                                int[] pe = prev.get(cur);
                                p.add(new int[]{pe[1], pe[0]});
                                cur = pe[0];
                            }
                            java.util.Collections.reverse(p);
                            return p;
                        }
                        nxt.add(m);
                    }
                }
            }
            frontier = nxt;
        }
        return null;
    }

    private void melody(Lay L, AudioTap.Harm hm, double now, double[] centre) {
        if (hm == null || hm.t == melSeen) return;
        melSeen = hm.t;
        int pc = Math.floorMod(hm.melody, 12);
        if (hm.sure < 0.45 || cs[pc] < 0.18) return;
        if (head >= 0 && L.nodes.get(head).pc == pc) {
            headAt = now;
            return;
        }
        Set<Integer> one = new HashSet<>();
        one.add(pc);
        if (head < 0) {
            head = nearest(L, one, centre[0], centre[1], null, 0.4);
            headAt = now;
            return;
        }
        Node hn = L.nodes.get(head);
        int target = nearest(L, one, hn.x, hn.y, centre, 0.5);
        if (target < 0) return;
        List<int[]> p = path(L, head, target, 4);
        if (p != null && !p.isEmpty()) {
            for (int k = 0; k < p.size(); k++) trail.put(p.get(k)[0], new double[]{now + 0.045 * k, 0.6 + 0.4 * hm.sure, p.get(k)[1]});
        }
        head = target;
        headAt = now;
    }

    private double beat(AudioTap.Fr fr, double now, boolean beat, boolean hit) {
        if (beat) {
            double gap = now - beatAt;
            if (0.25 < gap && gap < 1.5) period += (gap - period) * 0.3;
            beatAt = now;
            nbeat++;
        }
        double P = period;
        double since = now - beatAt;
        double beatsIn = Math.floorMod(nbeat, 4) + (Math.min(since, P * 1.98) / P);
        double pos = pmod(beatsIn / 4.0, 1.0);
        int slot = Math.floorMod((int) Math.rint(pos * SLOTS), SLOTS);
        if (beat) slotB[slot] = Math.min(1.0, slotB[slot] + 0.55 + 0.45 * Math.min(1.0, fr.bass));
        if (hit) slotH[slot] = Math.min(1.0, slotH[slot] + 0.6);
        return pos;
    }

    static double pmod(double a, double b) {
        double r = a % b;
        if (r != 0 && (r < 0) != (b < 0)) r += b;
        return r;
    }

    // ---- drawing
    private int mixc(Theme C, double h, double m, double w, double L, double kd) {
        long q = ((long) (int) (h * 6) << 48) | ((long) (int) (m * 6) << 32) | ((long) (int) (w * 6) << 16) | (int) (L * 12);
        Integer c = colCache.get(q);
        if (c == null) {
            double tot = h + m + w + 1e-9;
            int hue = C.accent;
            if (m > 0) hue = h != 0 ? Theme.mix(hue, C.warn, m / tot) : C.warn;
            if (w > 0) hue = Theme.mix(hue, Theme.mix(C.accent2, C.fg, 0.25), w / tot);
            if (L > 0.85) hue = Theme.mix(hue, C.fg, (L - 0.85) / 0.15 * 0.35);
            int q3 = (int) (L * 12);
            c = Theme.mix(C.bg, hue, Math.min(1.0, 0.18 + 0.82 * (q3 / 12.0)) * kd);
            colCache.put(q, c);
        }
        return c;
    }

    static int grad(int[] stops, double k) {
        k = k < 0 ? 0.0 : k > 1 ? 1.0 : k;
        double p = k * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) p);
        return Theme.mix(stops[i], stops[i + 1], p - i);
    }

    public int draw(Cells cv, Theme C, double now, double dtIn, double dim, AudioTap.Fr fr, int top, int base,
                    double[][] mask, AudioTap.Harm hm) {
        int W = cv.w, H = cv.h;
        if (W < 12 || H < 6) return 0;
        double dt = Math.max(0.0, Math.min(0.1, dtIn));
        Lay L = layout(W, H, top, base);
        double kd = 1 - dim;
        Object ck = java.util.Arrays.asList(C.bg, C.fg, C.accent, C.accent2, C.warn, C.faint, kd);
        if (!ck.equals(ckey)) {
            ckey = ck;
            colCache.clear();
        }
        boolean silent = fr.silent;
        double eT = silent ? 0.0 : fr.energy;
        energy += (eT - energy) * (1 - Math.exp(-dt / 0.06));
        double on = silent || hm == null ? 0.0 : Math.min(1.0, energy * 6.0);
        presence += (on - presence) * (1 - Math.exp(-dt / (on > presence ? 0.12 : 0.5)));
        double loud = presence * (0.55 + 0.45 * energy);
        double[] tc = hm != null && !silent ? hm.chroma : null;
        double up = 1 - Math.exp(-dt / 0.08), down = 1 - Math.exp(-dt / 0.45);
        for (int p = 0; p < 12; p++) {
            double t = tc != null ? tc[p] * loud : 0.0;
            cs[p] += (t - cs[p]) * (t > cs[p] ? up : down);
        }
        chord(now);
        double[] roomc = {(W - 1) / 2.0, (top + base) / 2.0};
        if (focus == null) focus = roomc.clone();
        if (chord != null) {
            int r = chord[0];
            boolean minor = chord[1] == 1;
            double bd = 0;
            int bti = -1;
            double btx = 0, bty = 0;
            for (int ti = 0; ti < L.tris.size(); ti++) {
                Tri tr = L.tris.get(ti);
                if (tr.root != r || tr.minor != minor) continue;
                double tx = (L.nodes.get(tr.a).x + L.nodes.get(tr.b).x + L.nodes.get(tr.c).x) / 3.0;
                double ty = (L.nodes.get(tr.a).y + L.nodes.get(tr.b).y + L.nodes.get(tr.c).y) / 3.0;
                if (!(top - 1 <= ty && ty <= base)) continue;
                double d = dist(focus[0], focus[1], tx, ty) + 0.6 * dist(roomc[0], roomc[1], tx, ty);
                if (bti < 0 || d < bd) {
                    bd = d;
                    bti = ti;
                    btx = tx;
                    bty = ty;
                }
            }
            ctri = bti;
            if (bti >= 0) {
                double g = 1 - Math.exp(-dt / 0.5);
                focus[0] += (btx - focus[0]) * g;
                focus[1] += (bty - focus[1]) * g;
            }
        } else {
            ctri = -1;
        }
        double fx = focus[0], fy = focus[1];
        melody(L, !silent ? hm : null, now, focus);
        if (head >= 0 && now - headAt > 1.6) head = -1;
        if (beats == null) {
            beats = fr.beats;
            hits = fr.hits;
        }
        boolean beat = fr.beats != beats && !silent;
        boolean hit = fr.hits != hits && !silent;
        beats = fr.beats;
        hits = fr.hits;
        double pos = beat(fr, now, beat, hit);
        if (beat) {
            int src = -1;
            if (chord != null) {
                Set<Integer> one = new HashSet<>();
                one.add(chord[0]);
                src = nearest(L, one, fx, fy, null, 0.4);
            }
            double sx = src >= 0 ? L.nodes.get(src).x : fx, sy = src >= 0 ? L.nodes.get(src).y : fy;
            waves.add(new double[]{sx, sy, now, 0.45 + 0.55 * Math.min(1.0, fr.bass), 30.0, 0.9});
        }
        if (hit && head >= 0) {
            waves.add(new double[]{L.nodes.get(head).x, L.nodes.get(head).y, now, 0.3, 22.0, 0.45});
        }
        for (int i = waves.size() - 1; i >= 0; i--) if (!(now - waves.get(i)[2] < waves.get(i)[5])) waves.remove(i);
        while (waves.size() > 6) waves.remove(0);
        double fade = Math.exp(-dt / (8.0 * period));
        for (int i = 0; i < SLOTS; i++) {
            slotB[i] = slotB[i] * fade;
            slotH[i] = slotH[i] * fade;
        }
        double tt, gt;
        if (hm != null && !silent && hm.centroid > 0) {
            tt = (log2(Math.max(1.0, hm.centroid)) - log2(350.0)) / (log2(3500.0) - log2(350.0));
            gt = (hm.flatness - 0.3) / 0.4;
        } else {
            tt = temp;
            gt = 0.0;
        }
        double g = 1 - Math.exp(-dt / 0.3);
        temp += (Math.max(0.0, Math.min(1.0, tt)) - temp) * g;
        grain += (Math.max(0.0, Math.min(1.0, gt)) - grain) * g;
        tick += dt;
        double asp = ASP;
        double sig = 2.7 * L.v;
        double sv = sig * 1.45;
        int nn = L.nodes.size();
        double[] vis = new double[nn], nl = new double[nn];
        for (int n = 0; n < nn; n++) {
            Node nd = L.nodes.get(n);
            double dx = (nd.x - fx) * asp, dy = nd.y - fy;
            double d2 = dx * dx + dy * dy;
            double u = (nd.y - (top - 3)) / 3.0;
            u = u <= 0 ? 0.0 : u >= 1 ? 1.0 : u;
            vis[n] = u * (0.2 + 0.8 * Math.exp(-d2 / (sv * sv)));
            nl[n] = cs[nd.pc] * (0.07 + 0.93 * Math.exp(-d2 / (sig * sig))) * u;
        }
        double[] sh = shadeOf(W, H, mask);
        char[] chs = cv.ch;
        int[] fgs = cv.fg;
        boolean[] bds = cv.bold;
        int bgc = C.bg;
        int drawn = 0;
        // the chords
        if (field.length != W * H) field = new double[W * H];
        double[] F = field;
        List<Integer> touched = new ArrayList<>();
        double keep = Math.exp(-dt / 5.0);
        for (int ti = 0; ti < L.tris.size(); ti++) {
            Tri tr = L.tris.get(ti);
            double f = Math.pow(nl[tr.a] * nl[tr.b] * nl[tr.c], 1 / 3.0);
            if (ti == ctri) f = Math.min(1.0, f * 1.35);
            Double old = tmem.get(ti);
            double mv = (old == null ? 0.0 : old) * keep;
            if (ti == ctri && f > mv) mv = f;
            if (mv > 0.02) tmem.put(ti, mv);
            else tmem.remove(ti);
            double lit = (ti == ctri ? 0.95 : 0.6) * f + 0.17 * mv;
            if (lit < 0.04) continue;
            for (int z = 0; z < tr.k.length; z++) {
                int k = tr.k[z];
                if (F[k] == 0.0) touched.add(k);
                F[k] += lit * (0.5 + 0.5 * tr.depth[z]);
            }
        }
        List<double[]> wv = new ArrayList<>();
        for (double[] w : waves) wv.add(new double[]{w[0], w[1], (now - w[2]) * w[4], w[3] * (1 - (now - w[2]) / w[5])});
        int tint = grad(new int[]{C.accent2, C.accent, C.warn}, 0.12 + 0.76 * temp);
        int ripCol = Theme.mix(C.accent2, C.fg, 0.25);
        double grn = grain;
        PyRandom rnd = PyRandom.python((long) (tick * 12));
        Map<Long, Integer> fcache = new HashMap<>();
        Set<Integer> clear = new HashSet<>();
        if (ctri >= 0 && chord != null && L.tris.get(ctri).label != null) {
            int[] lb = L.tris.get(ctri).label;
            int nm = NOTES[chord[0]].length() + (chord[1] == 1 ? 1 : 0);
            int sx = lb[0] - Math.floorDiv(nm - 1, 2);
            for (int xx = sx - 1; xx < sx + nm + 1; xx++) clear.add(lb[1] * W + xx);
        }
        for (int k : touched) {
            double v = F[k];
            F[k] = 0.0;
            double q = sh[k];
            if (q == 0.0 || v < 0.06 || clear.contains(k)) continue;
            double r = 0.0;
            if (!wv.isEmpty()) {
                int y = k / W, x = k % W;
                for (double[] w : wv) {
                    if (w[3] > 0) {
                        double dx = (x - w[0]) * asp, dy = y - w[1];
                        double d = Math.sqrt(dx * dx + dy * dy) - w[2];
                        if (-2.2 < d && d < 2.2) r += w[3] * Math.exp(-d * d / 0.9);
                    }
                }
                v *= 1 + 0.8 * r;
            }
            if (v > 1.0) v = 1.0;
            char ch;
            if (v < 0.2) ch = '·';
            else if (grn > 0.02 && rnd.random() < grn * 0.45) ch = ",;'.`".charAt((int) (rnd.random() * 5));
            else ch = v < 0.52 ? ':' : '+';
            int k0 = (int) (v * 16), k1 = (int) (Math.min(1.0, r) * 6), k2 = (int) (q * 8);
            long fk = ((long) k0 << 40) | ((long) k1 << 20) | k2;
            Integer col = fcache.get(fk);
            if (col == null) {
                int hue = r > 0.05 ? Theme.mix(tint, ripCol, Math.min(1.0, r) * 0.7) : tint;
                col = Theme.mix(bgc, hue, Math.min(1.0, 0.12 + 0.85 * (k0 / 16.0)) * (k2 / 8.0) * kd);
                fcache.put(fk, col);
            }
            chs[k] = ch;
            fgs[k] = col;
            drawn++;
        }
        // the lines
        Map<Integer, Integer> skCol = new HashMap<>();
        for (int e = 0; e < L.edges.size(); e++) {
            Edge ed = L.edges.get(e);
            double la = nl[ed.a], lb = nl[ed.b];
            double both = la < lb ? la : lb;
            double ve = vis[ed.a] < vis[ed.b] ? vis[ed.a] : vis[ed.b];
            double sk = 0.075 * ve * ve;
            double[] tr = trail.get(e);
            double tl = 0.0;
            if (tr != null) {
                double age = now - tr[0];
                if (age > 3.0) {
                    trail.remove(e);
                    tr = null;
                } else if (age >= 0) {
                    tl = tr[1] * Math.exp(-age / 1.3);
                } else {
                    tr = null;
                }
            }
            if (both < 0.05 && tl < 0.03 && sk < 0.025 && !(!wv.isEmpty() && ve > 0.3)) continue;
            for (int z = 0; z < ed.k.length; z++) {
                int k = ed.k[z];
                double q = sh[k];
                if (q == 0.0) continue;
                double m = 0.0;
                if (tr != null) {
                    double tt2 = tr[2] == ed.a ? ed.t[z] : 1 - ed.t[z];
                    if ((now - tr[0]) / 0.045 >= tt2) m = tl;
                }
                double w = 0.0;
                if (!wv.isEmpty() && ve > 0.3) {
                    for (double[] wa : wv) {
                        if (wa[3] <= 0) continue;
                        double dx = (ed.x[z] - wa[0]) * asp, dy = ed.y[z] - wa[1];
                        double d = Math.sqrt(dx * dx + dy * dy) - wa[2];
                        if (-2.4 < d && d < 2.4) w += wa[3] * Math.exp(-d * d / 1.1) * ve;
                    }
                }
                double Lv = both > m ? both : m;
                if (w > Lv) Lv = w;
                if (Lv < 0.05) {
                    if (sk < 0.025) continue;
                    int qk = (int) (sk * q * 40);
                    Integer col = skCol.get(qk);
                    if (col == null) {
                        col = Theme.mix(bgc, C.fg, qk / 40.0 * kd);
                        skCol.put(qk, col);
                    }
                    chs[k] = ed.g[z];
                    fgs[k] = col;
                    continue;
                }
                if (Lv > 1.0) Lv = 1.0;
                chs[k] = ed.g[z];
                fgs[k] = mixc(C, both, m, Math.min(1.0, w), Lv * q, kd);
                drawn++;
            }
        }
        // the notes
        for (int n = 0; n < nn; n++) {
            Node nd = L.nodes.get(n);
            int x = nd.x, y = nd.y;
            if (!(1 <= y && y <= H - 2) || vis[n] < 0.02) continue;
            double Lv = nl[n];
            double w = 0.0;
            for (double[] wa : wv) {
                if (wa[3] > 0) {
                    double d = dist(wa[0], wa[1], x, y) - wa[2];
                    if (-2.4 < d && d < 2.4) w += wa[3] * Math.exp(-d * d / 1.1) * 0.8 * vis[n];
                }
            }
            boolean hd = n == head;
            boolean far = vis[n] < 0.42 && Lv < 0.08 && w < 0.08 && !hd;
            String text = far ? "·" : (hd ? "(" + nd.name + ")" : nd.name);
            int sx = hd ? x - 1 : x;
            double baseA = 0.17 * vis[n];
            for (int dx = 0; dx < text.length(); dx++) {
                int xx = sx + dx;
                if (!(0 <= xx && xx < W)) continue;
                int k = y * W + xx;
                double q = sh[k];
                if (q == 0.0) continue;
                char ch = text.charAt(dx);
                int col;
                if (hd) col = Theme.mix(bgc, Theme.mix(C.warn, C.fg, ch != '(' && ch != ')' ? 0.2 : 0.0), q * kd);
                else if (Lv > 0.06 || w > 0.06) {
                    int lit = mixc(C, Lv, 0.0, Math.min(1.0, w), Math.min(1.0, Math.max(Lv, w) * 1.15), kd);
                    col = Theme.mix(bgc, lit, q);
                } else col = Theme.mix(bgc, C.fg, baseA * q * kd);
                chs[k] = ch;
                fgs[k] = col;
                if (hd || Lv > 0.62) bds[k] = true;
                drawn++;
            }
        }
        // the chord's name, inside its triangle
        if (ctri >= 0 && chord != null) {
            int[] lb = L.tris.get(ctri).label;
            if (lb != null) {
                String text = NOTES[chord[0]] + (chord[1] == 1 ? "m" : "");
                if (lb[2] >= text.length()) {
                    int sx = lb[0] - Math.floorDiv(text.length() - 1, 2);
                    int col = Theme.mix(bgc, Theme.mix(C.accent, C.fg, 0.6), presence * kd);
                    for (int dx = 0; dx < text.length(); dx++) {
                        int xx = sx + dx;
                        if (0 <= xx && xx < W) {
                            int k = lb[1] * W + xx;
                            double q = sh[k];
                            if (q > 0.0) {
                                chs[k] = text.charAt(dx);
                                fgs[k] = Theme.mix(bgc, col, q);
                                bds[k] = true;
                            }
                        }
                    }
                }
            }
        }
        // the bar: sixteen beads
        if (L.beads != null) {
            int rh = L.beads[0], rb = L.beads[1];
            double swd = (double) W / SLOTS;
            int cur = Math.floorMod((int) (pos * SLOTS), SLOTS);
            int cold = C.accent2;
            int hiCol = Theme.mix(C.accent2, C.fg, 0.35);
            int dimdot = Theme.mix(bgc, C.fg, 0.14 * kd);
            for (int sl = 0; sl < SLOTS; sl++) {
                int x = (int) ((sl + 0.5) * swd);
                if (!(0 <= x && x < W)) continue;
                boolean beatSlot = sl % 4 == 0;
                int[] rowsB = {rh, rb};
                double[] vals = {slotH[sl], slotB[sl]};
                int[] hues = {hiCol, cold};
                for (int q = 0; q < 2; q++) {
                    int k = rowsB[q] * W + x;
                    if (sh[k] == 0.0) continue;
                    double val = vals[q];
                    char ch;
                    int col;
                    if (val > 0.08) {
                        ch = val > 0.66 ? '●' : '•';
                        col = Theme.mix(bgc, hues[q], (0.3 + 0.7 * val) * kd);
                    } else {
                        ch = '·';
                        col = beatSlot ? Theme.mix(dimdot, C.fg, 0.08) : dimdot;
                    }
                    if (sl == cur && presence > 0.05) col = Theme.mix(col, C.fg, 0.35 * presence);
                    chs[k] = ch;
                    fgs[k] = col;
                    drawn++;
                }
            }
        }
        return drawn;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }
}
