package com.sa2recomp.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage layouts for walkers in speed stages. The SA2 Randomizer mod (GameBanana 48932) ships, in
 * its Options folder, an object layout of each stage per kind of character
 * (Options/set0013_s/0.bin ... 4.bin); its DLL copies the one for the character it picked over
 * the stage's set file. Its code picks 2 for the walkers (Rando.dll: 0 Sonic and Shadow,
 * 1 Knuckles and Rouge, 4 Tails and Eggman on foot, 3 its ninth character, 2 the rest). The DLL
 * cannot run here, so with Characters' random walkers on, each Play stages the layouts and the
 * native helper (kind 13, around the game's stage layout loader) copies the walkers' one, or the
 * stage's usual one, over the file the game reads, for the character about to play the stage.
 *
 * The usual layout is whatever the game would read without this (a mod's, else the game's). The
 * overlay files the helper may have overwritten are listed in files/stage-layouts-written.txt and
 * taken out before the next Play's mods are applied, so the mods' own copies come back.
 */
final class StageLayouts {
    static final String LINES = "stage-layouts.txt", WRITTEN = "stage-layouts-written.txt";
    private static final Pattern DIR = Pattern.compile("set(\\d{4})_(s|hd_s)", Pattern.CASE_INSENSITIVE);

    private StageLayouts() {
    }

    /** Before the mods are applied: the overlay files the helper may have changed go. */
    static void beforeApply(Context c) {
        File list = new File(c.getFilesDir(), WRITTEN);
        String t = OpenSa2.readText(list);
        if (t != null) {
            String inside;
            try {
                inside = OpenSa2.overlayDir(c).getCanonicalPath() + File.separator;
            } catch (IOException e) {
                inside = null;
            }
            for (String l : t.split("\n")) {
                if (l.isEmpty() || inside == null) continue;
                File f = new File(l);
                try {
                    if (f.getCanonicalPath().startsWith(inside)) f.delete();
                } catch (IOException ignored) {
                }
            }
        }
        list.delete();
        new File(c.getFilesDir(), LINES).delete();
    }

    /** The SA2 Randomizer's folder (any installed mod with Options/setNNNN_s/2.bin), or null. */
    static File source(Context c) {
        for (ModManager.Mod m : ModManager.sorted(c)) {
            File o = OpenSa2.resolveCase(m.dir, "Options");
            if (!o.isDirectory()) continue;
            File[] ds = o.listFiles();
            if (ds == null) continue;
            for (File d : ds) if (DIR.matcher(d.getName()).matches() && new File(d, "2.bin").isFile()) return m.dir;
        }
        return null;
    }

    /** After the mods are applied: stages each layout pair and writes the helper's L lines. */
    static void afterApply(Context c, File readRoot, ModManager.Progress p) {
        if (!OpenSa2.prefs(c).getBoolean(NativePatch.RANDOM_PREF, false)
            || !OpenSa2.prefs(c).getBoolean(NativePatch.RANDOM_ANY_PREF, false)) return;
        File src = source(c);
        if (src == null) {
            p.line("Walkers in speed stages: no stage layouts for them (the SA2 Randomizer mod has them; "
                   + "install it, on or off, to use them)");
            return;
        }
        File cache = new File(c.getFilesDir(), "modbuild/layouts");
        cache.mkdirs();
        File overlay = OpenSa2.overlayDir(c);
        StringBuilder lines = new StringBuilder(), written = new StringBuilder();
        int stages = 0;
        File[] ds = OpenSa2.resolveCase(src, "Options").listFiles();
        if (ds == null) return;
        for (File d : ds) {
            Matcher m = DIR.matcher(d.getName());
            if (!m.matches()) continue;
            File walker = new File(d, "2.bin");
            if (!walker.isFile()) continue;
            int level = Integer.parseInt(m.group(1));
            String name = String.format(Locale.ROOT, "set%04d_%s.bin", level, m.group(2).toLowerCase(Locale.ROOT));
            String rel = "resource/gd_PC/" + name;
            File dest = ModManager.placeIn(overlay, readRoot, rel);
            File usual = dest.isFile() ? dest : OpenSa2.resolveCase(readRoot, rel);
            if (!usual.isFile()) continue;
            File u = new File(cache, name + ".0"), w = new File(cache, name + ".2");
            try {
                OpenSa2.copyFile(usual, u);
                OpenSa2.copyFile(walker, w);
                dest.getParentFile().mkdirs();
            } catch (IOException e) {
                continue;
            }
            lines.append(String.format(Locale.ROOT, "L %x 0 %s %s\n", level, u.getAbsolutePath(), dest.getAbsolutePath()));
            lines.append(String.format(Locale.ROOT, "L %x 2 %s %s\n", level, w.getAbsolutePath(), dest.getAbsolutePath()));
            written.append(dest.getAbsolutePath()).append('\n');
            stages++;
        }
        try {
            OpenSa2.writeText(new File(c.getFilesDir(), WRITTEN), written.toString());
            OpenSa2.writeText(new File(c.getFilesDir(), LINES), lines.toString());
        } catch (IOException e) {
            p.line("Walkers in speed stages: could not write the stage layout list (" + e.getMessage() + ")");
            return;
        }
        p.line("Walkers in speed stages: " + stages + " stage layout file(s) from " + src.getName()
               + " ready for when a walker is picked");
    }

    /** The helper's L lines staged by afterApply, or "". */
    static String lines(Context c) {
        String t = OpenSa2.readText(new File(c.getFilesDir(), LINES));
        return t == null ? "" : t;
    }
}
