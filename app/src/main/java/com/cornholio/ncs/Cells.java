package com.cornholio.ncs;

/** cornholio's Canvas: a grid of cells, each a character, its ink, its ground and whether it is bold. */
public final class Cells {
    public int w, h;
    public char[] ch;
    public int[] fg, bg;
    public boolean[] bold;
    public int base;

    public Cells(int w, int h, int bg) {
        resize(w, h, bg);
    }

    public void resize(int w, int h, int bg) {
        this.w = w;
        this.h = h;
        int n = w * h;
        ch = new char[n];
        fg = new int[n];
        this.bg = new int[n];
        bold = new boolean[n];
        reset(bg);
    }

    public void reset(int bgc) {
        java.util.Arrays.fill(ch, ' ');
        java.util.Arrays.fill(fg, bgc);
        java.util.Arrays.fill(bg, bgc);
        java.util.Arrays.fill(bold, false);
        base = bgc;
    }

    public void put(int x, int y, char c, int fgc) {
        if (x < 0 || x >= w || y < 0 || y >= h) return;
        int i = y * w + x;
        ch[i] = c;
        fg[i] = fgc;
        bold[i] = false;
    }

    public boolean isBlank(int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h && ch[y * w + x] == ' ';
    }
}
