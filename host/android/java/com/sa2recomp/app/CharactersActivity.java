package com.sa2recomp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Character swap: play a story character's stages as another character of the same play style
 * (Sonic as Shadow, Amy or Metal Sonic; Knuckles as Rouge, Tikal or Chaos Zero; the walkers as
 * each other or the Chao Walkers). The part of Character Select Plus that can be done without
 * running its DLL: the swap replaces the story character's Load function with the chosen one's
 * (NativePatch), everywhere the game loads that character, from the next Play.
 */
public class CharactersActivity extends Activity {
    private SharedPreferences p;
    private final Button[] mButtons = new Button[NativePatch.SLOTS.length];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        p = OpenSa2.prefs(this);
        LinearLayout col = Ui.screen(this, "Characters");
        col.addView(Ui.text(this, "Play a character's stages as someone else with the same play style. Applies from "
                + "the next Play, everywhere that character is loaded (story, stage select, 2P and the Chao World). "
                + "Experimental: voices and some sounds may stay the original character's, and a stage may expect an "
                + "upgrade the stand-in does not have.", 13, 0xFFA8B4C8));
        if (!new File(getApplicationInfo().nativeLibraryDir, NativePatch.HELPER).isFile()) {
            col.addView(Ui.text(this, "This build has no " + NativePatch.HELPER + ", so swaps cannot apply.", 14, OpenSa2.GOLD));
        }
        col.addView(Ui.check(this, "Random each stage: anyone of the same play style (the choices below are set aside)",
                p.getBoolean(NativePatch.RANDOM_PREF, false), on -> {
                    p.edit().putBoolean(NativePatch.RANDOM_PREF, on).apply();
                    show();
                }));
        col.addView(Ui.check(this, "With random: the walkers can turn up in speed stages too (Tails, Eggman and the Chao Walkers; "
                + "very experimental: some may not be able to finish every stage)",
                p.getBoolean(NativePatch.RANDOM_ANY_PREF, false),
                on -> p.edit().putBoolean(NativePatch.RANDOM_ANY_PREF, on).apply()));
        col.addView(Ui.text(this, "Random picks again each time a stage loads, and when you restart it or die before reaching a "
                + "checkpoint; once you have touched a checkpoint, the same character carries on.", 12, 0xFFA8B4C8));
        String[] heads = {"Speed stages", null, "Treasure hunting", null, "Walker shooting", null};
        for (int i = 0; i < NativePatch.SLOTS.length; i++) {
            if (heads[i] != null) col.addView(Ui.heading(this, heads[i]));
            final int at = i;
            mButtons[i] = Ui.button(this, "", v -> pick(at));
            col.addView(Ui.row(this, mButtons[i]));
        }
        col.addView(Ui.row(this, Ui.button(this, "Everyone as themselves", v -> {
            SharedPreferences.Editor e = p.edit();
            for (String s : NativePatch.SLOTS) e.remove(NativePatch.swapPref(s));
            e.apply();
            show();
        })));
        col.addView(Ui.text(this, "After a Play, the session report lists what the game took (“Native patches”). "
                + "If a swap does not apply on your phone, that report says why.", 12, 0xFFA8B4C8));
        show();
    }

    private void show() {
        Map<String, String> sw = NativePatch.swaps(this);
        for (int i = 0; i < NativePatch.SLOTS.length; i++) {
            NativePatch.Chara from = NativePatch.chara(NativePatch.SLOTS[i]);
            NativePatch.Chara to = NativePatch.chara(sw.get(from.key));
            mButtons[i].setText(from.name + "  →  " + to.name);
            mButtons[i].setAlpha(p.getBoolean(NativePatch.RANDOM_PREF, false) ? 0.4f : 1f);
        }
    }

    private void pick(int at) {
        NativePatch.Chara from = NativePatch.chara(NativePatch.SLOTS[at]);
        List<NativePatch.Chara> g = NativePatch.group(from.group);
        String cur = NativePatch.swaps(this).get(from.key);
        String[] names = new String[g.size()];
        int sel = 0;
        for (int i = 0; i < g.size(); i++) {
            names[i] = g.get(i).name + (g.get(i) == from ? " (no swap)" : "");
            if (g.get(i).key.equals(cur)) sel = i;
        }
        AlertDialog d = Ui.dialog(this).setTitle(from.name + " is played as")
                .setSingleChoiceItems(names, sel, (x, which) -> {
                    NativePatch.Chara to = g.get(which);
                    if (to == from) p.edit().remove(NativePatch.swapPref(from.key)).apply();
                    else p.edit().putString(NativePatch.swapPref(from.key), to.key).apply();
                    show();
                    x.dismiss();
                })
                .setNegativeButton("Cancel", null).create();
        Ui.style(d);
        d.show();
    }
}
