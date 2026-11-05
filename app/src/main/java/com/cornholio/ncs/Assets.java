package com.cornholio.ncs;

import android.content.Context;
import android.graphics.Typeface;

/** The one monospace face (DejaVu Sans Mono) and its bold, loaded once per process. */
final class Assets {
    private Assets() {}

    private static Typeface font, bold;

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

    static synchronized Typeface bold(Context c) {
        if (bold == null) {
            try {
                bold = Typeface.createFromAsset(c.getAssets(), "DejaVuSansMono-Bold.ttf");
            } catch (RuntimeException e) {
                bold = Typeface.create(font(c), Typeface.BOLD);
            }
        }
        return bold;
    }
}
