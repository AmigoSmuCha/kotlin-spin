package com.aphexspin.chrome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings, after the focus / cornholio Design System: one quiet plate over the
 * live picture, every row stating its value in one line, a tap goes forward
 * and a long press goes back, every change applied and saved at once. All of
 * it is text in one monospace face, in the palette's own colours, and a palette
 * change cross-fades the whole screen in 0.9 s.
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private Typeface font;
    private PreviewView preview;
    private LinearLayout root;
    private ScrollView plate;
    private final List<TextView> inkViews = new ArrayList<>(), mutedViews = new ArrayList<>(), faintViews = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private TextView title, subtitle, hint;
    private int[] ui = new int[7];          // ground, panel, ink, muted, faint, accent, plate ink
    private ValueAnimator fade;

    private interface Show {
        String[] get();                     // value, description
    }

    private interface Step {
        void by(int d);
    }

    private final class Row {
        final TextView label, value, desc;
        final Show show;
        ValueAnimator flash;
        float lit;                          // 1: the lamp colour (just changed), 0: ink

        Row(TextView l, TextView v, TextView d, Show s) {
            label = l;
            value = v;
            desc = d;
            show = s;
        }

        void refresh() {
            String[] s = show.get();
            value.setText(s[0]);
            desc.setText(s[1]);
        }

        void light() {
            if (flash != null) flash.cancel();
            flash = ValueAnimator.ofFloat(1f, 1f, 0f);
            flash.setDuration(2000);
            flash.addUpdateListener(a -> {
                lit = (float) a.getAnimatedValue();
                colour();
            });
            flash.start();
        }

        void colour() {
            value.setTextColor(0xFF000000 | Painter.mix(ui[6], ui[5], lit));
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);
        font = Assets.font(this);
        int pad = dp(14);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        title = text("APHEX CHROME", 16, inkViews);
        title.setLetterSpacing(0.12f);
        title.setPadding(pad, pad, pad, 0);
        root.addView(title);
        subtitle = text("an inflated chrome logo, raymarched into ascii", 11, mutedViews);
        subtitle.setPadding(pad, dp(2), pad, dp(6));
        root.addView(subtitle);

        preview = new PreviewView(this);
        int h = getResources().getDisplayMetrics().heightPixels;
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (h * 0.34f)));

        hint = text("drag spins it here · on the home screen it takes two fingers", 10, faintViews);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(pad, dp(4), pad, dp(6));
        root.addView(hint);

        plate = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(18));
        plate.addView(list);
        root.addView(plate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // ---- the rows
        row(list, "wallpaper", () -> new String[]{"set it", "opens the system picker · choose home, or home and lock"},
            d -> setWallpaper());
        row(list, "palette", () -> {
            int i = Palette.index(prefs.getString(Prefs.PALETTE, Prefs.D_PALETTE));
            return new String[]{Palette.NAMES[i], Palette.FEEL[i]};
        }, d -> {
            int i = Palette.index(prefs.getString(Prefs.PALETTE, Prefs.D_PALETTE));
            String next = Palette.NAMES[(i + d + Palette.NAMES.length) % Palette.NAMES.length];
            prefs.edit().putString(Prefs.PALETTE, next).apply();
            recolour(true);
        });
        row(list, "finish", () -> {
            int i = intIn(Prefs.FINISH, Prefs.D_FINISH, Tracer.FINISHES.length);
            return new String[]{Tracer.FINISHES[i], Tracer.FINISH_FEEL[i]};
        }, d -> cycle(Prefs.FINISH, Prefs.D_FINISH, Tracer.FINISHES.length, d));
        row(list, "glyphs", () -> {
            int i = intIn(Prefs.MODE, Prefs.D_MODE, Ascii.MODES.length);
            return new String[]{Ascii.MODES[i], Ascii.MODE_FEEL[i]};
        }, d -> cycle(Prefs.MODE, Prefs.D_MODE, Ascii.MODES.length, d));
        row(list, "letters", () -> prefs.getBoolean(Prefs.LETTERS, Prefs.D_LETTERS)
                ? new String[]{"all", "every printable glyph, the richest shading"}
                : new String[]{"symbols", "classic ascii-art texture, no letters"},
            d -> flip(Prefs.LETTERS, Prefs.D_LETTERS));
        row(list, "sky behind", () -> prefs.getBoolean(Prefs.SKY, Prefs.D_SKY)
                ? new String[]{"on", "sparse clouds of the same sky behind the logo"}
                : new String[]{"off", "the ground alone, emptiness is the design"},
            d -> flip(Prefs.SKY, Prefs.D_SKY));
        row(list, "sparkles", () -> prefs.getBoolean(Prefs.STARS, Prefs.D_STARS)
                ? new String[]{"on", "four-point stars twinkle where the sun glints"}
                : new String[]{"off", "only the glints, no stars"},
            d -> flip(Prefs.STARS, Prefs.D_STARS));
        row(list, "ground", () -> {
            int i = intIn(Prefs.GROUND, Prefs.D_GROUND, Scene.GROUNDS.length);
            return new String[]{Scene.GROUNDS[i], Scene.GROUND_FEEL[i]};
        }, d -> cycle(Prefs.GROUND, Prefs.D_GROUND, Scene.GROUNDS.length, d));
        row(list, "size", () -> new String[]{prefs.getInt(Prefs.COLS, Prefs.D_COLS) + " across",
                "characters across the screen · more is finer, and costs more"},
            d -> stepIn(Prefs.COLS, Prefs.D_COLS, Prefs.COLS_STEPS, d));
        row(list, "contrast", () -> new String[]{String.format(Locale.ENGLISH, "%.2f", prefs.getInt(Prefs.CONTRAST, Prefs.D_CONTRAST) / 100.0),
                "how hard the edges are drawn by the glyphs' shapes"},
            d -> stepIn(Prefs.CONTRAST, Prefs.D_CONTRAST, Prefs.CONTRAST_STEPS, d));
        row(list, "spin", () -> {
            int v = prefs.getInt(Prefs.SPIN, Prefs.D_SPIN);
            return new String[]{v == 0 ? "still" : String.format(Locale.ENGLISH, "%.2f rad/s", v / 100.0),
                v == 0 ? "it turns only when you turn it" : "the slow turn it takes on its own"};
        }, d -> stepIn(Prefs.SPIN, Prefs.D_SPIN, Prefs.SPIN_STEPS, d));
        row(list, "puff", () -> new String[]{String.format(Locale.ENGLISH, "%.2f", prefs.getInt(Prefs.PUFF, Prefs.D_PUFF) / 100.0),
                "how far the balloon is inflated"},
            d -> stepIn(Prefs.PUFF, Prefs.D_PUFF, Prefs.PUFF_STEPS, d));
        row(list, "frame rate", () -> new String[]{prefs.getInt(Prefs.FPS, Prefs.D_FPS) + " fps",
                "smoother costs battery · 45 at least while you touch it"},
            d -> stepIn(Prefs.FPS, Prefs.D_FPS, Prefs.FPS_STEPS, d));
        row(list, "battery saver", () -> prefs.getBoolean(Prefs.SAVER, Prefs.D_SAVER)
                ? new String[]{"on", "the spin rests 30 s after your last touch or unlock"}
                : new String[]{"off", "it keeps turning while you can see it"},
            d -> flip(Prefs.SAVER, Prefs.D_SAVER));
        row(list, "clock", () -> {
            int i = intIn(Prefs.CLOCK, Prefs.D_CLOCK, 3);
            String[] v = {"always", "lock screen only", "off"};
            String[] f = {"date in muted, time in ink, in a fixed place", "only while the phone is locked", "no clock, the logo alone"};
            return new String[]{v[i], f[i]};
        }, d -> cycle(Prefs.CLOCK, Prefs.D_CLOCK, 3, d));
        row(list, "clock place", () -> intIn(Prefs.CLOCK_POS, Prefs.D_CLOCK_POS, 2) == 0
                ? new String[]{"top", "under the status bar, notes beneath it"}
                : new String[]{"bottom", "above the dock, notes above it"},
            d -> cycle(Prefs.CLOCK_POS, Prefs.D_CLOCK_POS, 2, d));
        row(list, "time zone", () -> prefs.getBoolean(Prefs.FIXED_TZ, Prefs.D_FIXED_TZ)
                ? new String[]{"utc+1", "fixed all year, summer time ignored"}
                : new String[]{"the phone's", "follows the phone, summer time too"},
            d -> flip(Prefs.FIXED_TZ, Prefs.D_FIXED_TZ));

        TextView help = text("on the home screen\n"
            + "two fingers drag · spin it, let go to fling\n"
            + "pinch · zoom\n"
            + "tap with two · next palette\n"
            + "tap with three · next finish\n"
            + "hold two still · back to the start\n"
            + "one finger stays the launcher's\n\n"
            + "tap a row · forward     hold a row · back", 10, faintViews);
        help.setPadding(dp(16), dp(18), dp(16), 0);
        list.addView(help);

        recolour(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Row r : rows) r.refresh();
        recolour(false);
        preview.resume();
    }

    @Override
    protected void onPause() {
        preview.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        preview.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ rows
    private void row(LinearLayout list, String name, Show show, Step step) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(9), dp(16), dp(9));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = text(name, 13, mutedViews);
        TextView value = text("", 13, null);
        value.setGravity(Gravity.END);
        line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(line);
        TextView desc = text("", 10, faintViews);
        desc.setPadding(0, dp(2), 0, 0);
        box.addView(desc);
        Row r = new Row(label, value, desc, show);
        rows.add(r);
        box.setOnClickListener(v -> {
            step.by(1);
            r.refresh();
            r.light();
        });
        box.setOnLongClickListener(v -> {
            step.by(-1);
            r.refresh();
            r.light();
            return true;
        });
        r.refresh();
        list.addView(box);
    }

    private int intIn(String key, int def, int n) {
        return Math.max(0, Math.min(n - 1, prefs.getInt(key, def)));
    }

    private void cycle(String key, int def, int n, int d) {
        prefs.edit().putInt(key, (intIn(key, def, n) + d + n) % n).apply();
    }

    private void flip(String key, boolean def) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
    }

    private void stepIn(String key, int def, int[] steps, int d) {
        int cur = prefs.getInt(key, def), at = 0;
        for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - cur) < Math.abs(steps[at] - cur)) at = i;
        prefs.edit().putInt(key, steps[(at + d + steps.length) % steps.length]).apply();
    }

    private void setWallpaper() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, ChromeWallpaper.class));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "open settings · wallpaper, and pick aphex chrome", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ colour: one palette, cross-faded whole
    private int[] rolesOf(String name) {
        Palette p = Palette.get(name);
        double[][] c = p.c;
        int ground = Palette.ink(p.hasGround ? c[Palette.GROUND] : c[Palette.NIGHT]);
        int panel = Palette.ink(c[Palette.PANEL]);
        int ink = Palette.ink(c[Palette.INK]), muted = Palette.ink(c[Palette.MUTED]), faint = Palette.ink(c[Palette.FAINT]);
        int accent = Palette.ink(c[Palette.ACCENT]);
        // a light plate (gruvbox, graphite) takes ground-coloured ink
        int plateInk = contrast(ink, panel) < 3.0 ? ground : ink;
        return new int[]{ground, panel, ink, muted, faint, accent, plateInk};
    }

    private void recolour(boolean animate) {
        int[] target = rolesOf(prefs.getString(Prefs.PALETTE, Prefs.D_PALETTE));
        if (fade != null) fade.cancel();
        if (!animate) {
            ui = target;
            paint();
            return;
        }
        final int[] from = ui.clone();
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(900);
        fade.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            t = t * t * (3f - 2f * t);
            int[] now = new int[7];
            for (int i = 0; i < 7; i++) now[i] = Painter.mix(from[i], target[i], t);
            ui = now;
            paint();
        });
        fade.start();
    }

    @SuppressWarnings("deprecation")
    private void paint() {
        int ground = 0xFF000000 | ui[0], panel = 0xFF000000 | ui[1];
        root.setBackgroundColor(ground);
        plate.setBackgroundColor(panel);
        try {
            getWindow().setStatusBarColor(ground);
            getWindow().setNavigationBarColor(panel);
        } catch (RuntimeException ignored) {
        }
        boolean lightPlate = ui[6] == ui[0];
        int pMuted = lightPlate ? Painter.mix(ui[0], ui[1], 0.38f) : ui[3];
        int pFaint = lightPlate ? Painter.mix(ui[0], ui[1], 0.55f) : ui[4];
        title.setTextColor(0xFF000000 | ui[2]);
        subtitle.setTextColor(0xFF000000 | ui[3]);
        hint.setTextColor(0xFF000000 | ui[4]);
        for (TextView v : mutedViews) if (v != subtitle) v.setTextColor(0xFF000000 | pMuted);
        for (TextView v : faintViews) if (v != hint) v.setTextColor(0xFF000000 | pFaint);
        for (Row r : rows) r.colour();
    }

    private static double lum(int c) {
        double[] v = {((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    // ------------------------------------------------------------------ helpers
    private TextView text(String s, int sp, List<TextView> role) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        if (role != null) role.add(t);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
