import com.aphexspin.chrome.*;
import java.io.*;

public class Parity {
    public static void main(String[] a) throws Exception {
        String cmd = a[0];
        Balloon.Field f = Balloon.Field.read(new FileInputStream("app/src/main/assets/balloon.bin"));
        long t0 = System.nanoTime();
        Balloon b = new Balloon(f, 0.82, 0.30);
        long t1 = System.nanoTime();
        // frame: cols rows palette material mode charset backdrop
        int cols = Integer.parseInt(a[1]), rows = Integer.parseInt(a[2]);
        Scene s = new Scene(b);
        s.setPalette(a[3]);
        s.clock = 0;
        // no fade: jump straight there, as a fresh --palette run
        Debug.settle(s);
        int mat = java.util.Arrays.asList(Tracer.FINISHES).indexOf(a[4]);
        Debug.setMaterialNow(s, mat);
        s.mode = java.util.Arrays.asList(Ascii.MODES).indexOf(a[5]);
        s.allGlyphs = a[6].equals("all");
        s.backdrop = a[7].equals("1");
        if (a.length > 8) { s.R = Scene.mul(Scene.rotX(Double.parseDouble(a[9])), Scene.mul(Scene.rotY(Double.parseDouble(a[8])), s.R)); }
        Ascii.maskLikeTerminal = true;
        Frame fr = new Frame();
        s.render(fr, cols, rows);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                sb.append((int) fr.chars[i]).append(' ').append(fr.block ? fr.top[i] : fr.fg[i]).append(' ').append(fr.block ? fr.bot[i] : -1).append('\n');
            }
        }
        System.out.print(sb);
    }

    static void dump(String path, float[] v) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(v.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bb.asFloatBuffer().put(v);
            o.write(bb.array());
        }
    }
}
