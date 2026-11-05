import com.cornholio.ncs.*;

/** Mirrors ref.py: the demo song through AudioTap and Ncs, one JSON line per frame. */
public class Run {
    public static void main(String[] a) {
        String visual = a[0];
        int W = Integer.parseInt(a[1]), H = Integer.parseInt(a[2]), frames = Integer.parseInt(a[3]);
        String theme = a.length > 4 ? a[4] : "default";
        String title = a.length > 5 ? a[5] : "";
        boolean finger = a.length > 6 && a[6].equals("1");
        Trail trail = new Trail();
        double dt = 1 / 30.0;
        final double[] clock = {1000.0};
        Theme C = new Theme(theme);
        AudioTap tap = new AudioTap(true, () -> clock[0]);
        Ncs ncs = new Ncs(tap);
        Cells cv = new Cells(W, H, C.bg);
        double[][] mask = new double[H][W];
        for (double[] r : mask) java.util.Arrays.fill(r, 1.0);
        int top = H - 2 - (int) (H * 0.42) + 1;
        int low = top - 2;
        StringBuilder sb = new StringBuilder();
        for (int f = 0; f < frames; f++) {
            clock[0] += dt;
            double now = clock[0];
            tap.tick(now, true, dt);
            cv.reset(C.bg);
            double[] wake = null;
            if (finger) {
                if ((20 <= f && f < 80) || (110 <= f && f < 140))
                    trail.feed((int) (W / 2.0 + Math.cos(f * 0.15) * W * 0.35), (int) (H / 2.0 + Math.sin(f * 0.15) * H * 0.3), now);
                wake = trail.grid(now, Trail.level(W, H), W, H);
            }
            String t = f < frames / 2 ? title : (title.isEmpty() ? "" : "Řeka Ůsvitů");
            ncs.draw(cv, C, now, dt, 0.0, true, true, t, low, top, wake, mask, null, visual);
            AudioTap.Fr fr = tap.frame();
            sb.setLength(0);
            sb.append("{\"f\":").append(f).append(",\"beats\":").append(fr.beats).append(",\"hits\":").append(fr.hits)
              .append(",\"energy\":").append(fr.energy).append(",\"bass\":").append(fr.bass).append(",\"silent\":").append(fr.silent)
              .append(",\"levels\":[");
            for (int i = 0; i < fr.levels.length; i++) sb.append(i > 0 ? "," : "").append(fr.levels[i]);
            sb.append("],\"ch\":\"");
            for (char c : cv.ch) {
                if (c == '"' || c == '\\') sb.append('\\').append(c);
                else if (c < 128) sb.append(c);
                else sb.append(String.format("\\u%04x", (int) c));
            }
            sb.append("\",\"fg\":[");
            for (int i = 0; i < cv.fg.length; i++) sb.append(i > 0 ? "," : "").append('"').append(String.format("%06x", cv.fg[i])).append('"');
            sb.append("],\"bg\":[");
            for (int i = 0; i < cv.bg.length; i++) sb.append(i > 0 ? "," : "").append('"').append(String.format("%06x", cv.bg[i])).append('"');
            sb.append("],\"bold\":\"");
            for (boolean b : cv.bold) sb.append(b ? '1' : '0');
            sb.append("\"}");
            System.out.println(sb);
        }
    }
}
