import com.cornholio.ncs.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Desktop pictures of the wallpaper: the core runs cornholio's demo song for a
 * while, then the last frame is drawn the way the phone's Painter draws it.
 * args: out.png width height cols visual theme reach(0..2) seconds [finger 0/1] [note label] [note value]
 *
 * From the repository's top level:
 *   S=app/src/main/java/com/cornholio/ncs; mkdir -p build/shots
 *   javac -d build/shots $S/{AudioTap,Cells,Diffision,Dust,GlyphField,Kerosene,Lorn,Mirror,Ncs,NcsBars,PyRandom,Spectrum,Stitch,Theme,Trail}.java tools/Shots.java
 *   java -Djava.awt.headless=true -cp build/shots Shots bars.png 480 854 48 bars default 0 14
 */
public class Shots {
    public static void main(String[] a) throws Exception {
        String out = a[0];
        int W = Integer.parseInt(a[1]), H = Integer.parseInt(a[2]), cols = Integer.parseInt(a[3]);
        String visual = a[4], theme = a[5];
        int reach = Integer.parseInt(a[6]);
        double seconds = Double.parseDouble(a[7]);
        boolean finger = a.length > 8 && a[8].equals("1");
        String nl = a.length > 9 ? a[9] : null, nv = a.length > 10 ? a[10] : "";

        Font regular = Font.createFont(Font.TRUETYPE_FONT, new File("app/src/main/assets/DejaVuSansMono.ttf"));
        Font boldF = Font.createFont(Font.TRUETYPE_FONT, new File("app/src/main/assets/DejaVuSansMono-Bold.ttf"));
        float cellW = (float) W / cols, cellH = cellW * 2f;
        int rows = Math.max(8, (int) (H / cellH));
        float yOff = (H - rows * cellH) / 2f;

        final double[] clock = {1000.0};
        Theme C = new Theme(theme);
        AudioTap tap = new AudioTap(true, () -> clock[0]);
        Ncs ncs = new Ncs(tap);
        Cells cv = new Cells(cols, rows, C.bg);
        double[][] mask = new double[rows][cols];
        for (double[] r : mask) java.util.Arrays.fill(r, 1.0);
        double[][] veiled = new double[rows][cols];
        for (double[] r : veiled) java.util.Arrays.fill(r, 1.0);
        float size = cellW * 1.15f, baseY = H * 0.09f + size;
        if (nl != null) {
            int r0 = (int) Math.floor((baseY - size - yOff) / cellH), r1 = (int) Math.floor((baseY + size * 0.35f - yOff) / cellH);
            for (int y = Math.max(0, r0); y <= Math.min(rows - 1, r1); y++) java.util.Arrays.fill(veiled[y], 0.15);
        }
        double[] REACH_TOP = {0.5, 1 / 3.0, 0.0};
        int top = Math.max(2, (int) Math.round(rows * REACH_TOP[reach]));
        int low = top - 2, visTop = reach == 2 ? 0 : top;
        Trail trail = new Trail();
        double dt = 1 / 30.0;
        int frames = (int) (seconds * 30);
        for (int f = 0; f < frames; f++) {
            clock[0] += dt;
            double now = clock[0];
            tap.tick(now, true, dt);
            double[] wake = null;
            if (finger && f > frames - 22) {
                double u = (f - (frames - 22)) / 21.0;
                trail.feed((int) (cols * (0.2 + 0.6 * u)), (int) (rows * (0.22 + 0.1 * Math.sin(u * 3.0))), now);
            }
            if (finger && !visual.equals("lorn") && !visual.equals("diffision")) wake = trail.grid(now, Trail.level(cols, rows), cols, rows);
            cv.reset(C.bg);
            ncs.draw(cv, C, now, dt, 0.0, true, true, "", low, visTop, wake, nl != null ? veiled : mask, null, visual);
        }

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(C.bg));
        g.fillRect(0, 0, W, H);
        Font probe = regular.deriveFont(100f);
        float adv = (float) probe.getStringBounds("M", g.getFontRenderContext()).getWidth();
        float fs = 100f * cellW / adv;
        Font fr = regular.deriveFont(fs), fb = boldF.deriveFont(fs);
        FontMetrics fm = g.getFontMetrics(fr);
        float base = (cellH - (fm.getAscent() + fm.getDescent())) / 2f + fm.getAscent();
        float half = cellH / 2f;
        for (int r = 0; r < rows; r++) {
            float y0 = yOff + r * cellH;
            for (int x = 0; x < cols; x++) {
                int i = r * cols + x;
                float x0 = x * cellW;
                if (cv.bg[i] != C.bg) {
                    g.setColor(new Color(cv.bg[i]));
                    g.fill(new java.awt.geom.Rectangle2D.Float(x0, y0, cellW + 0.5f, cellH + 0.5f));
                }
                char k = cv.ch[i];
                if (k == ' ' || k == 0) continue;
                g.setColor(new Color(cv.fg[i]));
                if (k == '▀' || k == '▄') {
                    float yy = k == '▀' ? y0 : y0 + half;
                    g.fill(new java.awt.geom.Rectangle2D.Float(x0, yy, cellW + 0.5f, half + 0.5f));
                    continue;
                }
                g.setFont(cv.bold[i] ? fb : fr);
                g.drawString(String.valueOf(k), x0, y0 + base);
            }
        }
        if (nl != null) {
            Font nf = regular.deriveFont(size);
            g.setFont(nf);
            String p1 = nv.isEmpty() ? nl : nl + " · ";
            FontMetrics m = g.getFontMetrics(nf);
            float w1 = m.stringWidth(p1), w2 = nv.isEmpty() ? 0 : m.stringWidth(nv);
            float x = W / 2f - (w1 + w2) / 2f;
            g.setColor(new Color(C.fg));
            g.drawString(p1, x, baseY);
            if (!nv.isEmpty()) {
                g.setColor(new Color(C.accent));
                g.drawString(nv, x + w1, baseY);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println(out + " · " + cols + " x " + rows + " cells · beats " + tap.frame().beats);
    }
}
