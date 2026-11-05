package com.aphexspin.chrome;

/** One frame of characters and colours: what the painter draws. */
public final class Frame {
    public int cols, rows;
    /** Half-block mode: each cell is two rectangles, top and bottom. */
    public boolean block;
    public char[] chars = new char[0];
    /** Ink per cell, 0xRRGGBB; -1 where nothing is drawn. */
    public int[] fg = new int[0];
    public int[] top = new int[0], bot = new int[0];
    /** Where the logo is (or a sparkle): the sky behind stays clear of the clock's rows, the logo never does. */
    public boolean[] logo = new boolean[0];
    /** The ground behind everything, 0xRRGGBB. */
    public int ground;
    /** Interface colours of the palette now on screen (0xRRGGBB). */
    public int ink, muted, faint, accent, panel;

    void size(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        int n = cols * rows;
        if (chars.length != n) {
            chars = new char[n];
            fg = new int[n];
            top = new int[n];
            bot = new int[n];
            logo = new boolean[n];
        }
    }

    public void copyFrom(Frame f) {
        size(f.cols, f.rows);
        block = f.block;
        System.arraycopy(f.chars, 0, chars, 0, chars.length);
        System.arraycopy(f.fg, 0, fg, 0, fg.length);
        System.arraycopy(f.top, 0, top, 0, top.length);
        System.arraycopy(f.bot, 0, bot, 0, bot.length);
        System.arraycopy(f.logo, 0, logo, 0, logo.length);
        ground = f.ground;
        ink = f.ink; muted = f.muted; faint = f.faint; accent = f.accent; panel = f.panel;
    }
}
