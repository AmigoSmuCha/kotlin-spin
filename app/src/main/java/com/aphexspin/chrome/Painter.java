package com.aphexspin.chrome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Draws a Frame of characters onto a canvas, plus the quiet lines of text around it. */
final class Painter {
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rect = new Paint();
    float cellW, cellH, yOff;
    int rows;
    private float base;
    private int laidW = -1, laidH = -1, laidCols = -1;

    Painter(Typeface tf) {
        glyph.setTypeface(tf);
        text.setTypeface(tf);
    }

    /** cols characters across w x h (a cell twice as tall as wide); returns the rows that fit. */
    int layout(int w, int h, int cols) {
        if (w == laidW && h == laidH && cols == laidCols) return rows;
        cellW = (float) w / cols;
        cellH = cellW * 2f;
        rows = Math.max(4, (int) (h / cellH));
        yOff = (h - rows * cellH) / 2f;
        glyph.setTextSize(100f);
        float adv = glyph.measureText("M");
        glyph.setTextSize(100f * cellW / adv);
        Paint.FontMetrics fm = glyph.getFontMetrics();
        base = (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        laidW = w;
        laidH = h;
        laidCols = cols;
        return rows;
    }

    /** Draws f; cells of sky (not logo) whose row meets the band [clearTop, clearBottom] are left out. */
    void draw(Canvas c, Frame f, float clearTop, float clearBottom) {
        c.drawColor(0xFF000000 | f.ground);
        int cols = f.cols, rows = f.rows;
        boolean[] logo = f.logo;
        if (f.block) {
            float half = cellH / 2f;
            int g = f.ground;
            for (int r = 0; r < rows; r++) {
                float y = yOff + r * cellH;
                boolean band = y + cellH > clearTop && y < clearBottom;
                for (int x = 0; x < cols; x++) {
                    int i = r * cols + x;
                    if (band && !logo[i]) continue;
                    float x0 = x * cellW;
                    if (f.top[i] != g) {
                        rect.setColor(0xFF000000 | f.top[i]);
                        c.drawRect(x0, y, x0 + cellW + 0.5f, y + half + 0.5f, rect);
                    }
                    if (f.bot[i] != g) {
                        rect.setColor(0xFF000000 | f.bot[i]);
                        c.drawRect(x0, y + half, x0 + cellW + 0.5f, y + cellH + 0.5f, rect);
                    }
                }
            }
            return;
        }
        char[] ch = f.chars;
        int[] fg = f.fg;
        int last = -2;
        for (int r = 0; r < rows; r++) {
            float top = yOff + r * cellH;
            boolean band = top + cellH > clearTop && top < clearBottom;
            float y = top + base;
            for (int x = 0; x < cols; x++) {
                int i = r * cols + x;
                int k = fg[i];
                if (k < 0 || (band && !logo[i])) continue;
                if (k != last) {
                    glyph.setColor(0xFF000000 | k);
                    last = k;
                }
                c.drawText(ch, i, 1, x * cellW, y, glyph);
            }
        }
    }

    /** sRGB mix of two 0xRRGGBB colours. */
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (ar + (br - ar) * t + 0.5f) << 16) | ((int) (ag + (bg - ag) * t + 0.5f) << 8) | (int) (ab + (bb - ab) * t + 0.5f);
    }

    /**
     * One centred line made of parts in their own colours, at baseline y, text
     * as wide as `width` at most and `size` px tall at most.
     */
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

    /** The size that makes `sample` exactly `width` px wide. */
    float sizeFor(String sample, float width) {
        text.setTextSize(100f);
        float w = text.measureText(sample);
        return w <= 0 ? 12f : 100f * width / w;
    }
}
