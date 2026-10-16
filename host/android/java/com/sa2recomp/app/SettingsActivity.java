package com.sa2recomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Settings: picture, touch controls and debug. Each one applies at the next Play. */
public class SettingsActivity extends Activity {
    private SharedPreferences p;
    private Button mRes;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        p = OpenSa2.prefs(this);
        LinearLayout col = Ui.screen(this, "Settings");

        col.addView(Ui.heading(this, "Picture"));
        mRes = Ui.button(this, "", v -> pickResolution());
        col.addView(Ui.row(this, mRes));
        col.addView(Ui.text(this, "Screen draws the game at your screen's own size, full width. Fewer lines run "
                + "lighter on the phone; 4:3 is the original picture with bars at the sides.", 13, 0xFFA8B4C8));
        col.addView(Ui.check(this, "HUD at its original 4:3 positions", p.getBoolean("hud43", true),
                on -> p.edit().putBoolean("hud43", on).apply()));
        col.addView(Ui.check(this, "Centre the story select on wide screens (its menu is drawn for 16:9)",
                p.getBoolean(NativePatch.MENU_PREF, true), on -> p.edit().putBoolean(NativePatch.MENU_PREF, on).apply()));

        col.addView(Ui.heading(this, "Touch controls"));
        col.addView(Ui.check(this, "Show touch controls", p.getBoolean("touch_on", true),
                on -> p.edit().putBoolean("touch_on", on).apply()));
        col.addView(Ui.check(this, "Vibrate on press", p.getBoolean("touch_haptics", true),
                on -> p.edit().putBoolean("touch_haptics", on).apply()));
        TextView op = Ui.text(this, "", 15, Color.WHITE);
        col.addView(op);
        SeekBar bar = new SeekBar(this);
        bar.setMax(85);
        bar.setProgress(p.getInt("touch_opacity", 70) - 15);
        op.setText("Opacity: " + p.getInt("touch_opacity", 70) + "%");
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(OpenSa2.GOLD));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(OpenSa2.GOLD));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                p.edit().putInt("touch_opacity", v + 15).apply();
                op.setText("Opacity: " + (v + 15) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(bar);

        col.addView(Ui.heading(this, "Debug"));
        col.addView(Ui.check(this, "Save session logs to Internal storage/OpenSA2/logs (the newest "
                + SessionLogs.KEEP + " sessions)", p.getBoolean("save_logs", true),
                on -> p.edit().putBoolean("save_logs", on).apply()));
        col.addView(Ui.check(this, "Performance hints (ADPF)", p.getBoolean("adpf", true),
                on -> p.edit().putBoolean("adpf", on).apply()));
        col.addView(Ui.row(this, Ui.button(this, "Extra game settings (SA2_ lines)", v -> editExtra())));
        col.addView(Ui.text(this, "For testing: lines like SA2_GUEST_HOST_CPUS=7 go to the game as they are "
                + "(the session's game.env.extra).", 13, 0xFFA8B4C8));
        col.addView(Ui.row(this, Ui.button(this, "Reset game data (saves stay)", v -> resetOverlay())));
        refreshRes();
    }

    private void refreshRes() {
        mRes.setText("Resolution: " + OpenSa2.resName(p.getString("res_mode", "screen")));
    }

    private void pickResolution() {
        String cur = p.getString("res_mode", "screen");
        int sel = 0;
        for (int i = 0; i < OpenSa2.RES_KEYS.length; i++) if (OpenSa2.RES_KEYS[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this).setTitle("Resolution")
                .setSingleChoiceItems(OpenSa2.RES_NAMES, sel, (d, w) -> {
                    p.edit().putString("res_mode", OpenSa2.RES_KEYS[w]).apply();
                    refreshRes();
                    d.dismiss();
                }).show();
    }

    private void editExtra() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(4);
        e.setText(p.getString("env_extra", ""));
        new AlertDialog.Builder(this).setTitle("Extra game settings").setView(e)
                .setPositiveButton("Save", (d, w) -> p.edit().putString("env_extra", e.getText().toString()).apply())
                .setNegativeButton("Cancel", null).show();
    }

    /** Takes the mods and the game's default Config files out of the overlay (not SAVEDATA). */
    private void resetOverlay() {
        new AlertDialog.Builder(this).setTitle("Reset game data?")
                .setMessage("Removes the mod files put in place and the game's Config files from the app's "
                        + "storage. Save files are kept. Play puts everything back.")
                .setPositiveButton("Reset", (d, w) -> {
                    java.io.File o = OpenSa2.overlayDir(this);
                    java.io.File[] kids = o.listFiles();
                    if (kids != null) {
                        for (java.io.File k : kids) {
                            if (k.getName().equalsIgnoreCase("resource")) {
                                java.io.File gd = OpenSa2.resolveCase(k, "gd_PC");
                                java.io.File[] in = gd.listFiles();
                                if (in != null) for (java.io.File x : in) if (!x.getName().equalsIgnoreCase("SAVEDATA")) OpenSa2.removeTree(x);
                            } else {
                                OpenSa2.removeTree(k);
                            }
                        }
                    }
                    new java.io.File(getFilesDir(), "mods-applied.tsv").delete();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
