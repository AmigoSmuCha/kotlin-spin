package com.cornholio.ncs;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Draws cornholio's cells onto a canvas: grounds, glyphs (bold where Lorn wants it), half blocks as rectangles. */
final class Painter {
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rect = new Paint();
    float cellW, cellH, yOff;
    int rows;
    private float base;
    private int laidW = -1, laidH = -1, laidCols = -1;

    Painter(Typeface regular, Typeface boldFace) {
        glyph.setTypeface(regular);
        bold.setTypeface(boldFace);
        text.setTypeface(regular);
    }

    /** cols characters across w x h (a cell twice as tall as wide); returns the rows that fit. */
    int layout(int w, int h, int cols) {
        if (w == laidW && h == laidH && cols == laidCols) return rows;
        cellW = (float) w / cols;
        cellH = cellW * 2f;
        rows = Math.max(8, (int) (h / cellH));
        yOff = (h - rows * cellH) / 2f;
        glyph.setTextSize(100f);
        float adv = glyph.measureText("M");
        float size = 100f * cellW / adv;
        glyph.setTextSize(size);
        bold.setTextSize(size);
        Paint.FontMetrics fm = glyph.getFontMetrics();
        base = (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        laidW = w;
        laidH = h;
        laidCols = cols;
        return rows;
    }

    void draw(Canvas c, Cells cv, int ground) {
        c.drawColor(0xFF000000 | ground);
        int W = cv.w, H = cv.h;
        char[] ch = cv.ch;
        int[] fg = cv.fg, bg = cv.bg;
        boolean[] bd = cv.bold;
        int lastG = -2, lastB = -2;
        float half = cellH / 2f;
        for (int r = 0; r < H; r++) {
            float top = yOff + r * cellH;
            float y = top + base;
            for (int x = 0; x < W; x++) {
                int i = r * W + x;
                float x0 = x * cellW;
                if (bg[i] != ground) {
                    rect.setColor(0xFF000000 | bg[i]);
                    c.drawRect(x0, top, x0 + cellW + 0.5f, top + cellH + 0.5f, rect);
                }
                char k = ch[i];
                if (k == ' ') continue;
                if (k == '▀' || k == '▄') {
                    rect.setColor(0xFF000000 | fg[i]);
                    float y0 = k == '▀' ? top : top + half;
                    c.drawRect(x0, y0, x0 + cellW + 0.5f, y0 + half + 0.5f, rect);
                    continue;
                }
                Paint p = bd[i] ? bold : glyph;
                if (bd[i]) {
                    if (fg[i] != lastB) {
                        p.setColor(0xFF000000 | fg[i]);
                        lastB = fg[i];
                    }
                } else if (fg[i] != lastG) {
                    p.setColor(0xFF000000 | fg[i]);
                    lastG = fg[i];
                }
                c.drawText(ch, i, 1, x0, y, p);
            }
        }
    }

    /** One centred line made of parts in their own colours, at baseline y, at most `width` wide. */
    void line(Canvas c, String[] parts, int[] colours, float centreX, float y, float size, float width) {
        text.setTextSize(size);
        float total = 0;
        for (String p : parts) total += text.measureText(p);
        if (total > width && total > 0) {
            text.setTextSize(size * width / total);
            total = 0;
            for (String p : parts) total += text.measureText(p);
        }
        float x = centreX - total / 2f;
        for (int i = 0; i < parts.length; i++) {
            text.setColor(0xFF000000 | colours[i]);
            c.drawText(parts[i], x, y, text);
            x += text.measureText(parts[i]);
        }
    }
}
