package com.sa2recomp.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installing a mod archive into Internal storage/OpenSA2/mods/. Shared by the Mods screen's
 * "Add mod from ZIP" and the GameBanana install: a .zip is unpacked by Java's own
 * ZipInputStream, a .7z by SevenZ (so the .7z mods on GameBanana install directly), and the
 * folder holding mod.ini is moved under mods/ (a nested archive keeps the mod's own folder name).
 */
final class ModInstall {
    private ModInstall() {}

    interface Progress {
        void line(String s);
    }

    /** A zip or 7z archive type, by its first bytes. */
    static boolean isSevenZ(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[6];
            return in.read(h) == 6 && h[0] == '7' && h[1] == 'z' && (h[2] & 0xFF) == 0xBC && (h[3] & 0xFF) == 0xAF;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isZip(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5 || h[2] == 7);
        } catch (IOException e) {
            return false;
        }
    }

    /** A filesystem-safe folder name from a mod's title or a file name. */
    static String safeName(String name) {
        if (name == null) name = "mod";
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip") || name.toLowerCase(Locale.ROOT).endsWith(".7z"))
            name = name.substring(0, name.lastIndexOf('.'));
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "mod" : name;
    }

    /**
     * Unpacks `archive` (zip or 7z) into mods/ and switches the new mods on. Returns their folder
     * names. `fallbackName` names the mod folder when the archive holds mod.ini at its root.
     */
    static List<String> install(Context c, File archive, String fallbackName, Progress p) throws IOException {
        OpenSa2.makeFolders();
        File tmp = new File(OpenSa2.modsDir(), ".unpack-" + System.currentTimeMillis());
        tmp.mkdirs();
        try {
            if (isZip(archive)) {
                unzip(archive, tmp);
            } else if (isSevenZ(archive)) {
                p.line("Unpacking a 7z archive...");
                SevenZ.extract(archive, tmp, p::line);
            } else {
                throw new IOException("not a ZIP or 7z archive (RAR is not supported: re-save it as a .zip)");
            }
            List<File> found = new ArrayList<>();
            findMods(tmp, 0, found);
            if (found.isEmpty()) throw new IOException("no mod.ini in the archive");
            List<String> names = new ArrayList<>();
            for (File f : found) {
                String name = safeName(f.equals(tmp) ? fallbackName : f.getName());
                File dest = new File(OpenSa2.modsDir(), name);
                if (dest.exists()) OpenSa2.removeTree(dest);
                if (!f.renameTo(dest)) {
                    copyTree(f, dest);
                    OpenSa2.removeTree(f);
                }
                names.add(name);
                p.line("Installed mod: " + name);
            }
            Set<String> on = ModManager.enabled(c);
            on.addAll(names);
            ModManager.save(c, ModManager.order(c), on);
            return names;
        } finally {
            OpenSa2.removeTree(tmp);
        }
    }

    private static void unzip(File archive, File tmp) throws IOException {
        String canon = tmp.getCanonicalPath() + File.separator;
        try (InputStream raw = new java.io.FileInputStream(archive);
             ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            int entries = 0;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(tmp, e.getName().replace('\\', '/'));
                if (!out.getCanonicalPath().startsWith(canon)) throw new IOException("bad path in the ZIP: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) o.write(buf, 0, n);
                }
                entries++;
            }
            if (entries == 0) throw new IOException("the ZIP is empty");
        }
    }

    /** The folders holding a mod.ini, up to three deep; a folder with its own mod.ini is one mod. */
    static void findMods(File dir, int depth, List<File> out) {
        if (ModManager.child(dir, "mod.ini").isFile()) {
            out.add(dir);
            return;
        }
        if (depth >= 4) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.isDirectory() && !k.getName().equals(".7z-tmp")) findMods(k, depth + 1, out);
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else {
            OpenSa2.copyFile(from, to);
        }
    }
}
