package com.aphexspin.chrome;

import android.content.Context;
import android.graphics.Typeface;

import java.io.IOException;
import java.io.InputStream;

/** The baked balloon field, the balloon built from it, and the font: loaded once per process. */
final class Assets {
    private Assets() {}

    private static Balloon.Field field;
    private static Balloon balloon;
    private static int balloonPuff = -1;
    private static Typeface font;

    static synchronized Balloon balloon(Context c, int puffHundredths) throws IOException {
        if (field == null) {
            try (InputStream in = c.getAssets().open("balloon.bin")) {
                field = Balloon.Field.read(in);
            }
        }
        if (balloon == null || balloonPuff != puffHundredths) {
            balloon = new Balloon(field, puffHundredths / 100.0, 0.30);
            balloonPuff = puffHundredths;
        }
        return balloon;
    }

    static synchronized Typeface font(Context c) {
        if (font == null) {
            try {
                font = Typeface.createFromAsset(c.getAssets(), "DejaVuSansMono.ttf");
            } catch (RuntimeException e) {
                font = Typeface.MONOSPACE;
            }
        }
        return font;
    }
}
