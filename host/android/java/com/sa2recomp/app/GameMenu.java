package com.sa2recomp.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.Sa2VirtualPad;

/**
 * The in-game menu: a three-finger tap (or the phone's Back) opens it. Touch controls, the touch
 * layout editor, cheats and codes (switched live), the next start's resolution and HUD, the debug
 * view, and Exit game. Blue and gold like the launcher.
 */
final class GameMenu {
    private final Sa2Activity mActivity;
    private final TouchControlsView mTouch;
    private AlertDialog mOpen;

    GameMenu(Sa2Activity a, TouchControlsView touch) {
        mActivity = a;
        mTouch = touch;
    }

    private SharedPreferences prefs() {
        return OpenSa2.prefs(mActivity);
    }

    void show() {
        if (mOpen != null && mOpen.isShowing()) return;
        mTouch.releaseAll();
        SharedPreferences p = prefs();
        boolean touchOn = p.getBoolean("touch_on", true);
        int opacity = p.getInt("touch_opacity", 70);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("Resume");
        acts.add(() -> {});
        items.add("Touch controls: " + (touchOn ? "On" : "Off"));
        acts.add(() -> {
            boolean on = !touchOn;
            p.edit().putBoolean("touch_on", on).commit();
            mTouch.setEnabledControls(on);
            Sa2VirtualPad.setWanted(on);
            show();
        });
        items.add("Touch opacity: " + opacity + "%");
        acts.add(() -> {
            int next = opacity >= 100 ? 30 : opacity >= 90 ? 100 : opacity + 20;
            p.edit().putInt("touch_opacity", next).commit();
            mTouch.setOpacity(next / 100f);
            show();
        });
        items.add("Edit touch layout");
        acts.add(() -> new TouchLayoutEditor(mActivity, (ViewGroup) mTouch.getParent(), mTouch, saved -> {}));
        items.add("Cheats and codes");
        acts.add(this::codes);
        items.add("Resolution: " + OpenSa2.resName(p.getString("res_mode", "screen")) + " (next start)");
        acts.add(this::resolution);
        items.add("HUD at 4:3 positions: " + (p.getBoolean("hud43", true) ? "On" : "Off") + " (next start)");
        acts.add(() -> {
            p.edit().putBoolean("hud43", !p.getBoolean("hud43", true)).commit();
            OpenSa2.writeGameEnv(mActivity);
            show();
        });
        items.add("Debug info");
        acts.add(this::debug);
        items.add("Exit game");
        acts.add(mActivity::endGame);

        ListView list = list(items);
        AlertDialog d = Ui.dialog(mActivity).setCustomTitle(title("OpenSA2")).setView(list).create();
        list.setOnItemClickListener((parent, view, pos, id) -> {
            d.dismiss();
            acts.get(pos).run();
        });
        Ui.style(d);
        mOpen = d;
        d.show();
    }

    private TextView title(String s) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextColor(OpenSa2.GOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = Ui.dp(mActivity, 16);
        t.setPadding(pad, pad, pad, pad / 2);
        return t;
    }

    private ListView list(List<String> items) {
        ListView list = new ListView(mActivity);
        list.setAdapter(new ArrayAdapter<String>(mActivity, android.R.layout.simple_list_item_1, items) {
            @Override
            public android.view.View getView(int pos, android.view.View convert, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, convert, parent);
                v.setTextColor(pos == 0 ? OpenSa2.GOLD : Color.WHITE);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                return v;
            }
        });
        return list;
    }

    private void codes() {
        List<String> errors = new ArrayList<>();
        List<CodeList.Code> all = ModManager.allCodes(mActivity, errors);
        if (all.isEmpty()) {
            message("Cheats and codes", "No code list yet. On the launcher, open Codes and tap Download code list.");
            return;
        }
        Set<String> on = ModManager.codesOn(mActivity);
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            CodeList.Code c = all.get(i);
            names[i] = (c.patch ? "[Patch] " : "") + c.name + (c.source.equals("Codes.lst") ? "" : "  (" + c.source + ")");
            checked[i] = on.contains(ModManager.codeKey(c)) || (!c.source.equals("Codes.lst") && c.required);
        }
        AlertDialog d = Ui.dialog(mActivity)
                .setCustomTitle(title("Cheats and codes"))
                .setMultiChoiceItems(names, checked, (dlg, which, isOn) -> {
                    String k = ModManager.codeKey(all.get(which));
                    if (isOn) on.add(k);
                    else on.remove(k);
                })
                .setPositiveButton("Apply", (dlg, w) -> {
                    ModManager.saveCodesOn(mActivity, on);
                    mActivity.reloadCodes();
                    android.widget.Toast.makeText(mActivity, "Codes: " + mActivity.codes().state()
                            + ". Patches stay until the game restarts.", android.widget.Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Back", (dlg, w) -> show())
                .create();
        Ui.style(d);
        d.show();
    }

    private void resolution() {
        String cur = prefs().getString("res_mode", "screen");
        int sel = 0;
        for (int i = 0; i < OpenSa2.RES_KEYS.length; i++) if (OpenSa2.RES_KEYS[i].equals(cur)) sel = i;
        AlertDialog d = Ui.dialog(mActivity)
                .setCustomTitle(title("Resolution (next start)"))
                .setSingleChoiceItems(OpenSa2.RES_NAMES, sel, (dlg, which) -> {
                    prefs().edit().putString("res_mode", OpenSa2.RES_KEYS[which]).commit();
                    OpenSa2.writeGameEnv(mActivity);
                    dlg.dismiss();
                    show();
                })
                .create();
        Ui.style(d);
        d.show();
    }

    private void debug() {
        StringBuilder b = new StringBuilder();
        CodeEngine e = mActivity.codes();
        b.append("Codes: ").append(e == null ? "off" : e.state()).append('\n');
        if (e != null) for (String l : e.logLines()) b.append("  ").append(l).append('\n');
        b.append("Touch pad: ").append(Sa2VirtualPad.active() ? "connected" : "not connected")
         .append(Sa2VirtualPad.realControllerConnected() ? " (a controller is connected)" : "").append('\n');
        String session = OpenSa2.readText(new File(mActivity.getFilesDir(), "last-session.txt"));
        if (session != null) {
            String[] lines = session.split("\n");
            b.append("\nThis session (newest last):\n");
            for (int i = Math.max(0, lines.length - 30); i < lines.length; i++) b.append(lines[i]).append('\n');
        }
        message("Debug info", b.toString());
    }

    private void message(String t, String body) {
        TextView v = new TextView(mActivity);
        v.setText(body);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        v.setTextIsSelectable(true);
        int pad = Ui.dp(mActivity, 14);
        v.setPadding(pad, 0, pad, pad);
        ScrollView s = new ScrollView(mActivity);
        s.addView(v);
        AlertDialog d = Ui.dialog(mActivity).setCustomTitle(title(t)).setView(s)
                .setPositiveButton("Back", (dlg, w) -> show()).create();
        Ui.style(d);
        d.show();
    }
}
