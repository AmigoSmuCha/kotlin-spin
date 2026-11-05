package com.cornholio.ncs;

/**
 * The scene (cornholio's NCS): listens, draws the field and the dust, then the
 * visualizer: bars, mirror, kerosene, lorn or diffision. Lorn is drawn alone,
 * with nothing behind it; Diffision is the whole area. Nothing here moves
 * unless the music does: in silence the bars lie flat, the water is still,
 * the ridges are straight lines.
 */
public final class Ncs {
    public static final String[] VISUALS = {"bars", "mirror", "kerosene", "lorn", "diffision"};
    public static final String[] VISUAL_FEEL = {
        "the NCS bars · quick to rise, falling with gravity, kicked by the beat",
        "bass in the middle, the bars mirrored in water",
        "the last second of the music as ridges · unknown pleasures, live",
        "euler's tonnetz · chords light up as triangles, the melody walks",
        "geiss's reaction diffusion 3 · globs and stripes kicked by the beat"};

    public final AudioTap tap;
    final GlyphField field = new GlyphField();
    final NcsBars spectrum = new NcsBars();
    final Mirror mirror = new Mirror();
    final Kerosene kerosene = new Kerosene();
    final Lorn lorn = new Lorn();
    final Diffision diffision = new Diffision();
    final Dust dust = new Dust();
    private int beats = 0;
    /** The slow flow of letters behind the bars, mirror and ridges. */
    public boolean flow = true;

    public Ncs(AudioTap tap) {
        this.tap = tap;
    }

    /**
     * One frame. low: the lowest row taken by what sits above the visualizer
     * (in cornholio, the title's stack); the visualizer stands from the bottom
     * up to just under it. visTop: Diffision's first row. wake: the finger's
     * wake per cell, or null.
     */
    public void draw(Cells cv, Theme C, double now, double dt, double dim, boolean ground, boolean playing, String title,
                     int low, int visTop, double[] wake, double[][] mask, boolean[] skipRows, String visual) {
        int W = cv.w, H = cv.h;
        if (ground && playing) {
            tap.want(now);
            tap.song(title);
        }
        AudioTap.Fr fr = tap.frame();
        if (visual.equals("diffision")) {
            diffision.draw(cv, C, now, dim, visTop, H, tap.spectrum(), fr.silent);
            return;
        }
        int base = H - 2;
        field.setTitle(title == null ? "" : title, now, Math.floorDiv(W, 2), base);
        int maxH = H - 3 - low;
        if (maxH < 3) maxH = Math.max(3, Math.min((int) (H * 0.42), H - 4));
        int top = base - maxH + 1;
        if ((visual.equals("mirror") || visual.equals("kerosene")) && maxH < 8) visual = "bars";
        int oy = visual.equals("mirror") ? top + (int) (maxH * 0.62) : base;
        if (fr.beats != beats) {
            beats = fr.beats;
            if (ground && !fr.silent) field.pulse(Math.floorDiv(W, 2), oy, now, 0.35 + 0.4 * fr.bass);
        }
        double speed = playing && !fr.silent ? 1.0 : 0.25;
        if (visual.equals("lorn") && ground) {
            lorn.draw(cv, C, now, dt, dim, fr, top, base, mask, tap.harmony());
            return;
        }
        if (flow) field.draw(cv, C, now, dt, dim, mask, wake, fr.energy, fr.bass, speed, skipRows);
        if (!ground) return;
        dust.draw(cv, C, dt, dim, fr.bass, fr.energy, mask, fr.silent);
        if (visual.equals("mirror")) mirror.draw(cv, C, now, dt, dim, fr, 1, W - 1, top, base, mask);
        else if (visual.equals("kerosene")) kerosene.draw(cv, C, now, dt, dim, fr, 1, W - 1, top, base, mask);
        else spectrum.draw(cv, C, now, dt, dim, fr, 1, W - 1, base, maxH, mask, false);
    }
}
