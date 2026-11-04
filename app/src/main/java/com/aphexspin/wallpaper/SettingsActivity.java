package com.aphexspin.wallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings screen, built in code so the app needs no extra libraries. */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private LinearLayout root;
    private int pad;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);
        pad = dp(16);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Aphex Spin");
        title.setTextSize(24);
        root.addView(title);

        Button set = new Button(this);
        set.setText("Set as wallpaper");
        set.setOnClickListener(v -> setWallpaper());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        root.addView(set, lp);

        note("Gestures (two fingers, so the launcher's swipes never touch it):\n"
                + "  two-finger drag  spin, let go to fling\n"
                + "  pinch            zoom\n"
                + "  two-finger tap   next material\n"
                + "  three-finger tap reset");

        header("Look");
        choice("Material", Prefs.MATERIAL, Prefs.D_MATERIAL, AsciiRenderer.MATERIALS);
        choice("Glyphs", Prefs.MODE, Prefs.D_MODE, new String[]{"shape (sharpest)", "ramp", "blocks"});
        slider("Characters across", Prefs.COLS, Prefs.D_COLS, 24, 90, 1, "%d");
        slider("Edge contrast", Prefs.CONTRAST, Prefs.D_CONTRAST, 100, 400, 100, "%.2f");

        header("Motion");
        slider("Auto-spin speed", Prefs.SPEED, Prefs.D_SPEED, 0, 200, 100, "%.2f rad/s");
        slider("Frame rate", Prefs.FPS, Prefs.D_FPS, 10, 60, 1, "%d fps");
        toggle("Battery saver: stop auto-spin 30 s after last touch or unlock", Prefs.SAVER, Prefs.D_SAVER);

        header("Clock");
        choice("Show clock", Prefs.CLOCK, Prefs.D_CLOCK, new String[]{"always", "lock screen only", "off"});
        choice("Position", Prefs.CLOCK_POS, Prefs.D_CLOCK_POS, new String[]{"top", "bottom"});
        toggle("Fixed UTC+1 (ignore summer time and the phone's time zone)", Prefs.FIXED_TZ, Prefs.D_FIXED_TZ);
    }

    private void setWallpaper() {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, SpinWallpaper.class));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Open Settings > Wallpaper and pick Aphex Spin", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ small UI helpers
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setPadding(0, pad * 3 / 2, 0, pad / 4);
        root.addView(t);
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setPadding(0, pad, 0, 0);
        root.addView(t);
    }

    private void choice(String label, String key, int def, String[] items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(items.length - 1, prefs.getInt(key, def))));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (prefs.getInt(key, def) != pos) prefs.edit().putInt(key, pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(sp);
        root.addView(row);
    }

    private void slider(String label, String key, int def, int min, int max, int div, String fmt) {
        TextView t = new TextView(this);
        t.setPadding(0, pad / 2, 0, 0);
        root.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        sb.setProgress(cur - min);
        Runnable show = () -> {
            int v = sb.getProgress() + min;
            t.setText(label + ":  " + (div == 1 ? String.format(fmt, v) : String.format(fmt, v / (double) div)));
        };
        show.run();
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                show.run();
                if (fromUser) prefs.edit().putInt(key, p + min).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        root.addView(sb);
    }

    private void toggle(String label, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setPadding(0, pad / 2, 0, pad / 2);
        sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        root.addView(sw);
    }
}
