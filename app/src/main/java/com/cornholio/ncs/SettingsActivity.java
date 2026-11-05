package com.cornholio.ncs;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, after the focus / cornholio Design System: one quiet plate under
 * the live picture, every row stating its value in one line, a tap goes
 * forward and a long press goes back, every change applied and saved at once.
 * All of it is text in one monospace face, in the theme's own colours, and a
 * theme change cross-fades the whole screen in 0.9 s.
 */
public class SettingsActivity extends Activity {

    private static final int ASK_HEAR = 7;
    private static final String ASKED = "asked_hear";

    private SharedPreferences prefs;
    private Typeface font;
    private PreviewView preview;
    private LinearLayout root;
    private ScrollView plate;
    private final List<TextView> mutedViews = new ArrayList<>(), faintViews = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private TextView title, subtitle, hint;
    private int[] ui = new int[7];          // ground, panel, ink, muted, faint, accent, plate ink
    private ValueAnimator fade;
    private boolean setAfterAsk;
    private int barsLight = -1;

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
            value.setTextColor(0xFF000000 | Theme.mix(ui[6], ui[5], lit));
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

        title = text("CORNHOLIO NCS", 16, null);
        title.setLetterSpacing(0.12f);
        title.setPadding(pad, pad, pad, 0);
        root.addView(title);
        subtitle = text("cornholio's visualizer on the home screen, listening to what the phone plays", 11, null);
        subtitle.setPadding(pad, dp(2), pad, dp(6));
        root.addView(subtitle);

        preview = new PreviewView(this);
        int h = getResources().getDisplayMetrics().heightPixels;
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (h * 0.34f)));

        hint = text("the demo song plays here · a finger paints the letters", 10, null);
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
        row(list, "listening", () -> listen() == 0
                ? new String[]{"the phone's sound", "whatever the phone plays, as it plays it"}
                : new String[]{"the demo song", "cornholio's own little song · nothing is heard"},
            d -> {
                cycle(Prefs.LISTEN, Prefs.D_LISTEN, 2, d);
                refreshAll();
                if (listen() == 0 && !canHear()) askToHear();
            });
        row(list, "permission", () -> {
            if (listen() == 1) return new String[]{"not needed", "the demo song needs nothing"};
            return canHear()
                ? new String[]{"allowed", "it hears what the phone plays · nothing is recorded or kept"}
                : new String[]{"not yet", "tap to let it hear the phone · android calls this recording audio, but nothing is recorded or kept"};
        }, d -> askToHear());
        row(list, "loudness", () -> intIn(Prefs.LOUDNESS, Prefs.D_LOUDNESS, 2) == 0
                ? new String[]{"as played", "follows the volume · quiet when you turn it down"}
                : new String[]{"evened out", "the same height at any volume"},
            d -> cycle(Prefs.LOUDNESS, Prefs.D_LOUDNESS, 2, d));
        row(list, "visualizer", () -> {
            int i = intIn(Prefs.VISUAL, Prefs.D_VISUAL, Ncs.VISUALS.length);
            return new String[]{Ncs.VISUALS[i], Ncs.VISUAL_FEEL[i]};
        }, d -> cycle(Prefs.VISUAL, Prefs.D_VISUAL, Ncs.VISUALS.length, d));
        row(list, "theme", () -> {
            int i = Theme.index(prefs.getString(Prefs.THEME, Prefs.D_THEME));
            return new String[]{Theme.NAMES[i], Theme.FEEL[i]};
        }, d -> {
            int i = Theme.index(prefs.getString(Prefs.THEME, Prefs.D_THEME));
            String next = Theme.NAMES[(i + d + Theme.NAMES.length) % Theme.NAMES.length];
            prefs.edit().putString(Prefs.THEME, next).apply();
            recolour(true);
        });
        row(list, "reach", () -> {
            int i = intIn(Prefs.REACH, Prefs.D_REACH, Prefs.REACHES.length);
            return new String[]{Prefs.REACHES[i], Prefs.REACH_FEEL[i]};
        }, d -> cycle(Prefs.REACH, Prefs.D_REACH, Prefs.REACHES.length, d));
        row(list, "flow behind", () -> prefs.getBoolean(Prefs.FLOW, Prefs.D_FLOW)
                ? new String[]{"on", "the slow flow of letters behind the bars, the water and the ridges"}
                : new String[]{"off", "the visualizer alone on the ground"},
            d -> flip(Prefs.FLOW, Prefs.D_FLOW));
        row(list, "trail", () -> {
            int i = intIn(Prefs.TRAIL, Prefs.D_TRAIL, Prefs.TRAILS.length);
            return new String[]{Prefs.TRAILS[i], Prefs.TRAIL_FEEL[i]};
        }, d -> cycle(Prefs.TRAIL, Prefs.D_TRAIL, Prefs.TRAILS.length, d));
        row(list, "song letters", () -> {
            if (!prefs.getBoolean(Prefs.SONG, Prefs.D_SONG))
                return new String[]{"off", "the field keeps cornholio's own letters"};
            return songAccess()
                ? new String[]{"on", "the field takes the playing song's letters and ripples when the song changes"}
                : new String[]{"on · no access", "tap to give notification access, it only reads the song's title · greyed out? app info · ⋮ · allow restricted settings"};
        }, d -> {
            boolean on = prefs.getBoolean(Prefs.SONG, Prefs.D_SONG);
            if (on && d > 0 && !songAccess()) {
                openSongAccess();
                return;
            }
            flip(Prefs.SONG, Prefs.D_SONG);
            if (!on && !songAccess()) openSongAccess();
        });
        row(list, "size", () -> new String[]{prefs.getInt(Prefs.COLS, Prefs.D_COLS) + " across",
                "characters across the screen · more is finer, and costs more"},
            d -> stepIn(Prefs.COLS, Prefs.D_COLS, Prefs.COLS_STEPS, d));
        row(list, "frame rate", () -> new String[]{prefs.getInt(Prefs.FPS, Prefs.D_FPS) + " fps",
                "smoother costs battery · in silence it rests at a dozen"},
            d -> stepIn(Prefs.FPS, Prefs.D_FPS, Prefs.FPS_STEPS, d));

        TextView help = text("on the home screen\n"
            + "two fingers · paint a wake through the letters\n"
            + "tap with two · next visualizer\n"
            + "tap with three · next theme\n"
            + "one finger stays the launcher's\n\n"
            + "tap a row · forward     hold a row · back", 10, faintViews);
        help.setPadding(dp(16), dp(18), dp(16), 0);
        list.addView(help);

        recolour(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
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

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != ASK_HEAR) return;
        refreshAll();
        if (setAfterAsk) {
            setAfterAsk = false;
            openPicker();
        }
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

    private void refreshAll() {
        for (Row r : rows) r.refresh();
    }

    private int listen() {
        return intIn(Prefs.LISTEN, Prefs.D_LISTEN, 2);
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

    // ------------------------------------------------------------------ the system's parts
    private boolean canHear() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Asks once in Android's own dialog; once Android stops asking, opens the app's page instead. */
    private void askToHear() {
        boolean asked = prefs.getBoolean(ASKED, false);
        if (canHear() || (asked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))) {
            openAppPage();
            return;
        }
        prefs.edit().putBoolean(ASKED, true).apply();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_HEAR);
    }

    private void openAppPage() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "settings · apps · cornholio ncs · permissions", Toast.LENGTH_LONG).show();
        }
    }

    private ComponentName songComponent() {
        return new ComponentName(this, SongListener.class);
    }

    private boolean songAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                return nm != null && nm.isNotificationListenerAccessGranted(songComponent());
            }
            String on = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return on != null && on.contains(songComponent().flattenToString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void openSongAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, songComponent().flattenToString());
                startActivity(i);
                return;
            }
        } catch (ActivityNotFoundException ignored) {
            // the list of all of them, below
        }
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "settings · notifications · device & app notifications · cornholio ncs", Toast.LENGTH_LONG).show();
        }
    }

    private void setWallpaper() {
        if (listen() == 0 && !canHear()) {
            boolean asked = prefs.getBoolean(ASKED, false);
            if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                setAfterAsk = true;                 // ask first, then the picker
                askToHear();
                return;
            }
        }
        openPicker();
    }

    private void openPicker() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, NcsWallpaper.class));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "open settings · wallpaper, and pick cornholio ncs", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ colour: one theme, cross-faded whole
    private int[] rolesOf(String name) {
        int[] v = Theme.roles(name);
        int ground = v[Theme.BG], panel = v[Theme.SEL], ink = v[Theme.FG];
        // a light plate (gruvbox, Graphite) takes ground-coloured ink
        int plateInk = contrast(ink, panel) < 3.0 ? ground : ink;
        return new int[]{ground, panel, ink, v[Theme.DIM], v[Theme.FAINT], v[Theme.ACCENT], plateInk};
    }

    private void recolour(boolean animate) {
        int[] target = rolesOf(prefs.getString(Prefs.THEME, Prefs.D_THEME));
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
            for (int i = 0; i < 7; i++) now[i] = Theme.mix(from[i], target[i], t);
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
            getWindow().setNavigationBarColor(ground);
        } catch (RuntimeException ignored) {
        }
        bars(Theme.light(ui[0]));
        boolean lightPlate = ui[6] == ui[0];
        int pMuted = lightPlate ? Theme.mix(ui[0], ui[1], 0.38) : ui[3];
        int pFaint = lightPlate ? Theme.mix(ui[0], ui[1], 0.55) : ui[4];
        title.setTextColor(0xFF000000 | ui[2]);
        subtitle.setTextColor(0xFF000000 | ui[3]);
        hint.setTextColor(0xFF000000 | ui[4]);
        for (TextView v : mutedViews) v.setTextColor(0xFF000000 | pMuted);
        for (TextView v : faintViews) v.setTextColor(0xFF000000 | pFaint);
        for (Row r : rows) r.colour();
    }

    /** Dark status-bar icons on a light ground, light ones on a dark ground. */
    @SuppressWarnings("deprecation")
    private void bars(boolean light) {
        int want = light ? 1 : 0;
        if (want == barsLight) return;
        barsLight = want;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(light ? mask : 0, mask);
                }
            } else {
                View decor = getWindow().getDecorView();
                int f = decor.getSystemUiVisibility();
                int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                decor.setSystemUiVisibility(light ? f | mask : f & ~mask);
            }
        } catch (RuntimeException ignored) {
        }
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
