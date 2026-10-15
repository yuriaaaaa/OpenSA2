package com.sa2recomp.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * sa2-mod-loader mods (X-Hax/sa2-mod-loader, the loader SA Mod Manager installs for the Steam
 * game), read from Internal storage/OpenSA2/mods/<folder>/mod.ini.
 *
 * What a mod can change here: every file under its gd_PC folder replaces the game's file of the
 * same path under resource/gd_PC (models, PRS and PAK archives, set and camera files, sound
 * banks and ADX music, event files, text), from the mod folder and from its IncludeDir folders;
 * its [ReplaceFiles] entries; and its code file (mod.ini Codes=), whose Required codes run while
 * the mod is on (CodeEngine). Mods lower in the list win, as in the mod loader.
 *
 * The runtime's file system reads the overlay folder before the game folder
 * (runtime/sa2_files.h, decision 3), so applying the mods is copying each replacement into the
 * overlay at its game path. A manifest (files/mods-applied.tsv) records what was copied, so a
 * mod turned off is taken out again and an unchanged file is not copied twice. The game folder
 * itself is never written.
 *
 * What cannot work, and is shown on the mod: DLL mods (Windows x86 code), EXEData/DLLData
 * (data the loader patches into the program's memory at start), and the loader's texture-pack
 * folders (textures/, replacetex/: loose images the loader's Direct3D hook swaps in).
 */
final class ModManager {

    static final class Mod {
        String folder;
        File dir;
        String name, author = "", version = "", description = "", category = "";
        String dllFile = "", codes = "", exeData = "", dllData = "";
        final List<File> roots = new ArrayList<>();      // the mod folder and its IncludeDirs
        final Map<String, String> replaceFiles = new LinkedHashMap<>();
        int files;                                        // gd_PC files found
        boolean textures;

        List<String> warnings() {
            List<String> w = new ArrayList<>();
            if (!dllFile.isEmpty()) w.add("Has a DLL (" + dllFile + "): Windows code, it cannot run here");
            if (!exeData.isEmpty() || !dllData.isEmpty()) w.add("EXEData/DLLData patches are not supported");
            if (textures) w.add("Texture-pack folders (textures/, replacetex/) are not supported yet");
            if (files == 0 && replaceFiles.isEmpty() && codes.isEmpty()) w.add("Nothing this port can use");
            return w;
        }
    }

    private ModManager() {}

    // ------------------------------------------------------------ mod.ini

    /** Groups of key=value lines; keys before any [group] are in "". Keys keep their case. */
    static Map<String, Map<String, String>> readIni(File f) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Map<String, String> cur = new LinkedHashMap<>();
        out.put("", cur);
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first && line.startsWith("﻿")) line = line.substring(1);
                first = false;
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    String g = line.substring(1, line.length() - 1).trim();
                    cur = out.get(g);
                    if (cur == null) {
                        cur = new LinkedHashMap<>();
                        out.put(g, cur);
                    }
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                cur.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static String get(Map<String, String> g, String key) {
        if (g == null) return "";
        for (Map.Entry<String, String> e : g.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return "";
    }

    static File child(File dir, String name) {
        String[] names = dir.list();
        if (names != null) for (String n : names) if (n.equalsIgnoreCase(name)) return new File(dir, n);
        return new File(dir, name);
    }

    // ------------------------------------------------------------ scanning

    static List<Mod> scan() {
        List<Mod> out = new ArrayList<>();
        File[] dirs = OpenSa2.modsDir().listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            File ini = child(d, "mod.ini");
            if (!ini.isFile()) continue;
            Map<String, Map<String, String>> g = readIni(ini);
            Map<String, String> top = g.get("");
            Mod m = new Mod();
            m.folder = d.getName();
            m.dir = d;
            m.name = get(top, "Name");
            if (m.name.isEmpty()) m.name = d.getName();
            m.author = get(top, "Author");
            m.version = get(top, "Version");
            m.description = get(top, "Description").replace("\\n", "\n");
            m.category = get(top, "Category");
            m.dllFile = get(top, "DLLFile");
            m.codes = get(top, "Codes");
            m.exeData = get(top, "EXEData");
            m.dllData = get(top, "DLLData");
            m.roots.add(d);
            Map<String, String> cfg = null;
            for (Map.Entry<String, Map<String, String>> e : g.entrySet()) if (e.getKey().equalsIgnoreCase("Config")) cfg = e.getValue();
            int inc = 0;
            try {
                inc = Integer.parseInt(get(cfg, "IncludeDirCount").isEmpty() ? "0" : get(cfg, "IncludeDirCount"));
            } catch (NumberFormatException ignored) {
            }
            for (int i = 0; i < inc; i++) {
                String rel = get(cfg, "IncludeDir" + i);
                if (!rel.isEmpty()) m.roots.add(new File(d, rel.replace('\\', '/')));
            }
            for (Map.Entry<String, Map<String, String>> e : g.entrySet()) {
                if (e.getKey().equalsIgnoreCase("ReplaceFiles")) m.replaceFiles.putAll(e.getValue());
            }
            for (File r : m.roots) {
                File gd = child(r, "gd_PC");
                m.files += count(gd);
                if (child(r, "textures").isDirectory() || child(r, "replacetex").isDirectory()) m.textures = true;
            }
            out.add(m);
        }
        return out;
    }

    private static int count(File f) {
        if (f.isFile()) return 1;
        File[] kids = f.listFiles();
        int n = 0;
        if (kids != null) for (File k : kids) n += count(k);
        return n;
    }

    // ------------------------------------------------------------ order and on/off

    static List<String> order(Context c) {
        String s = OpenSa2.prefs(c).getString("mods_order", "");
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static Set<String> enabled(Context c) {
        String s = OpenSa2.prefs(c).getString("mods_on", "");
        Set<String> out = new HashSet<>();
        for (String x : s.split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static void save(Context c, List<String> order, Set<String> on) {
        SharedPreferences.Editor e = OpenSa2.prefs(c).edit();
        e.putString("mods_order", String.join("\n", order));
        e.putString("mods_on", String.join("\n", on));
        e.commit();
    }

    /** The mods in the saved order, new ones (off) at the end; drops folders that are gone. */
    static List<Mod> sorted(Context c) {
        List<Mod> all = scan();
        Map<String, Mod> by = new HashMap<>();
        for (Mod m : all) by.put(m.folder, m);
        List<Mod> out = new ArrayList<>();
        for (String f : order(c)) {
            Mod m = by.remove(f);
            if (m != null) out.add(m);
        }
        for (Mod m : all) if (by.containsKey(m.folder)) out.add(m);
        return out;
    }

    static List<Mod> active(Context c) {
        Set<String> on = enabled(c);
        List<Mod> out = new ArrayList<>();
        for (Mod m : sorted(c)) if (on.contains(m.folder)) out.add(m);
        return out;
    }

    // ------------------------------------------------------------ applying

    interface Progress {
        void line(String s);
    }

    private static boolean skipped(String rel) {
        String l = rel.toLowerCase(Locale.ROOT);
        return l.startsWith("resource/gd_pc/savedata/") || l.startsWith("config/") || l.equals("sonic2app.exe")
                || l.startsWith("resource/gd_pc/dll/");
    }

    private static void walk(File dir, String rel, Map<String, File> into) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String r = rel + "/" + k.getName();
            if (k.isDirectory()) walk(k, r, into);
            else if (k.isFile() && !skipped(r)) into.put(r.toLowerCase(Locale.ROOT), k);
        }
    }

    /** Copies the enabled mods' files into the overlay and takes out the ones no longer wanted. */
    static void apply(Context c, File readRoot, Progress p) throws IOException {
        File overlay = OpenSa2.overlayDir(c);
        Map<String, File> want = new LinkedHashMap<>();   // folded game path -> source
        Map<String, String> spell = new HashMap<>();      // folded -> as the mod spells it
        for (Mod m : active(c)) {
            Map<String, File> mine = new LinkedHashMap<>();
            for (File r : m.roots) {
                File gd = child(r, "gd_PC");
                if (gd.isDirectory()) walk(gd, "resource/gd_PC", mine);
            }
            for (Map.Entry<String, String> e : m.replaceFiles.entrySet()) {
                String to = e.getKey().replace('\\', '/');
                String from = e.getValue().replace('\\', '/');
                File src = from.toLowerCase(Locale.ROOT).startsWith("mods/")
                        ? OpenSa2.resolveCase(OpenSa2.modsDir(), from.substring(5))
                        : OpenSa2.resolveCase(readRoot, from);
                if (src.isFile() && !skipped(to)) mine.put(to.toLowerCase(Locale.ROOT), src);
            }
            for (Map.Entry<String, File> e : mine.entrySet()) {
                want.remove(e.getKey());   // a later mod wins, and keeps its own place
                want.put(e.getKey(), e.getValue());
            }
            for (File r : m.roots) {
                File gd = child(r, "gd_PC");
                if (!gd.isDirectory()) continue;
                Map<String, File> tmp = new HashMap<>();
                walk(gd, "resource/gd_PC", tmp);
                for (Map.Entry<String, File> e : tmp.entrySet()) {
                    String path = e.getValue().getAbsolutePath();
                    String sub = path.substring(gd.getAbsolutePath().length() + 1);
                    spell.put(e.getKey(), "resource/gd_PC/" + sub.replace('\\', '/'));
                }
            }
            p.line("Mod on: " + m.name + " (" + mine.size() + " file(s))");
        }

        File manifest = new File(c.getFilesDir(), "mods-applied.tsv");
        Map<String, String[]> old = new HashMap<>();
        String text = OpenSa2.readText(manifest);
        if (text != null) {
            for (String line : text.split("\n")) {
                String[] f = line.split("\t");
                if (f.length >= 5) old.put(f[0], f);
            }
        }
        int removed = 0, copied = 0, kept = 0;
        for (Map.Entry<String, String[]> e : old.entrySet()) {
            if (want.containsKey(e.getKey())) continue;
            File dest = new File(e.getValue()[4]);
            if (dest.isFile() && dest.delete()) removed++;
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, File> e : want.entrySet()) {
            File src = e.getValue();
            String rel = spell.containsKey(e.getKey()) ? spell.get(e.getKey()) : e.getKey();
            File dest = placeIn(overlay, readRoot, rel);
            String[] was = old.get(e.getKey());
            boolean same = was != null && was[1].equals(src.getAbsolutePath()) && was[2].equals(Long.toString(src.length()))
                    && was[3].equals(Long.toString(src.lastModified())) && dest.isFile() && dest.length() == src.length()
                    && was[4].equals(dest.getAbsolutePath());
            if (same) {
                kept++;
            } else {
                if (was != null && !was[4].equals(dest.getAbsolutePath())) new File(was[4]).delete();
                OpenSa2.copyFile(src, dest);
                copied++;
                if (copied % 25 == 0) p.line("Copied " + copied + " file(s)...");
            }
            out.append(e.getKey()).append('\t').append(src.getAbsolutePath()).append('\t').append(src.length())
               .append('\t').append(src.lastModified()).append('\t').append(dest.getAbsolutePath()).append('\n');
        }
        OpenSa2.writeText(manifest, out.toString());
        p.line("Mods: " + want.size() + " file(s) in place (" + copied + " copied, " + kept + " unchanged, "
               + removed + " taken out)");
    }

    /** `rel` under the overlay, each folder spelled as the overlay or else the game folder spells it. */
    private static File placeIn(File overlay, File readRoot, String rel) {
        File o = overlay, g = readRoot;
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            String use = null;
            String[] names = o.list();
            if (names != null) for (String n : names) if (n.equalsIgnoreCase(part)) use = n;
            if (use == null && g != null) {
                String[] gn = g.list();
                if (gn != null) for (String n : gn) if (n.equalsIgnoreCase(part)) use = n;
            }
            if (use == null) use = part;
            o = new File(o, use);
            g = g == null ? null : new File(g, use);
            if (g != null && !g.isDirectory()) g = null;
        }
        return o;
    }

    // ------------------------------------------------------------ codes

    /** Every code from Codes.lst and the active mods' code files. */
    static List<CodeList.Code> allCodes(Context c, List<String> errors) {
        List<CodeList.Code> out = new ArrayList<>(CodeList.parse(OpenSa2.codesFile(), "Codes.lst", errors));
        for (Mod m : active(c)) {
            if (m.codes.isEmpty()) continue;
            out.addAll(CodeList.parse(OpenSa2.resolveCase(m.dir, m.codes.replace('\\', '/')), m.name, errors));
        }
        return out;
    }

    static String codeKey(CodeList.Code code) {
        return code.source + "|" + code.name;
    }

    static Set<String> codesOn(Context c) {
        Set<String> out = new HashSet<>();
        for (String x : OpenSa2.prefs(c).getString("codes_on", "").split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static void saveCodesOn(Context c, Set<String> on) {
        OpenSa2.prefs(c).edit().putString("codes_on", String.join("\n", on)).commit();
    }

    /** The codes to run: the ones switched on, and every Required one from an active mod. */
    static List<CodeList.Code> codesToRun(Context c, List<String> errors) {
        Set<String> on = codesOn(c);
        List<CodeList.Code> out = new ArrayList<>();
        for (CodeList.Code code : allCodes(c, errors)) {
            boolean modCode = !code.source.equals("Codes.lst");
            if (on.contains(codeKey(code)) || (modCode && code.required)) out.add(code);
        }
        return out;
    }
}
