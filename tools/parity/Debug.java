package com.aphexspin.chrome;

/** Test hooks (desktop parity checks only). */
public final class Debug {
    private Debug() {}
    public static float[] C(Balloon b) { return b.C; }
    public static float[] VBp(Balloon b) { return b.VBp; }
    /** Finish any palette fade at once. */
    public static void settle(Scene s) { s.clock += 100; s.paletteNow(); s.clock -= 100; }
    public static void setMaterialNow(Scene s, int m) { s.setMaterial(m); s.clock += 100; s.render(new Frame(), 4, 4); s.clock -= 100; }
}
