package com.sa2recomp.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * The game's saves, kept where they survive the app. The game writes its saves
 * (resource/gd_PC/SAVEDATA/SONIC2B__S01 and the rest) through the runtime's file system into the
 * overlay, which is in the app's private storage and goes when the app is uninstalled. So after
 * each session they are copied to Internal storage/OpenSA2/saves/, and before each Play a save
 * there that the app lacks, or a newer, different one (a fresh install; saves brought from the
 * PC game), is copied in. A save the game deleted (the runtime's whiteout marker) is deleted from
 * there too.
 */
final class Saves {
    private Saves() {}

    /** runtime/sa2_files.c: an overlay file this prefix + a save's name marks the save deleted. */
    private static final String WHITEOUT = ".sa2-whiteout.";

    static File publicDir() {
        return new File(OpenSa2.root(), "saves");
    }

    static File overlayDir(Context c) {
        return OpenSa2.resolveCase(OpenSa2.overlayDir(c), "resource/gd_PC/SAVEDATA");
    }

    /** A save's name as the game spells it, or null for a file that is no save of the game's. */
    static String saveName(String name) {
        String u = name.toUpperCase(Locale.ROOT);
        if (!u.startsWith("SONIC2B__") || u.length() > 32) return null;
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if (!(ch == '_' || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9'))) return null;
        }
        return u;
    }

    /** After a session: the app's saves out to OpenSA2/saves. Returns how many were copied. */
    static synchronized int export(Context c) throws IOException {
        File from = overlayDir(c);
        File[] kids = from.listFiles();
        if (kids == null) return 0;
        File to = publicDir();
        int n = 0;
        for (File f : kids) {
            if (!f.isFile()) continue;
            if (f.getName().startsWith(WHITEOUT)) {
                String gone = saveName(f.getName().substring(WHITEOUT.length()));
                if (gone != null) {
                    File p = find(to, gone);
                    if (p != null) p.delete();
                }
                continue;
            }
            String name = saveName(f.getName());
            if (name == null) continue;
            File p = find(to, name);
            if (p != null && sameBytes(f, p)) continue;
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            if (p != null && !p.getName().equals(name)) p.delete();
            OpenSa2.copyFile(f, new File(to, name));
            n++;
        }
        return n;
    }

    /** Before Play: saves in OpenSA2/saves the app lacks, or newer different ones, copied in. */
    static synchronized int importInto(Context c, ModManager.Progress p) throws IOException {
        File[] kids = publicDir().listFiles();
        if (kids == null) return 0;
        Arrays.sort(kids);
        File to = overlayDir(c);
        int n = 0;
        for (File f : kids) {
            String name = f.isFile() ? saveName(f.getName()) : null;
            if (name == null || f.length() == 0) continue;
            File have = find(to, name);
            if (have != null && (have.lastModified() >= f.lastModified() || sameBytes(have, f))) continue;
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            if (have != null && !have.getName().equals(name)) have.delete();
            File marker = find(to, WHITEOUT + name);
            if (marker != null) marker.delete();
            OpenSa2.copyFile(f, new File(to, name));
            n++;
            p.line("Save " + name + " brought in from Internal storage/OpenSA2/saves");
        }
        return n;
    }

    private static File find(File dir, String name) {
        String[] names = dir.list();
        if (names != null) for (String x : names) if (x.equalsIgnoreCase(name)) return new File(dir, x);
        return null;
    }

    private static boolean sameBytes(File a, File b) {
        if (!a.isFile() || !b.isFile() || a.length() != b.length()) return false;
        try (InputStream x = new FileInputStream(a); InputStream y = new FileInputStream(b)) {
            byte[] p = new byte[1 << 15], q = new byte[1 << 15];
            while (true) {
                int n = x.read(p);
                if (n <= 0) return true;
                int m = 0;
                while (m < n) {
                    int k = y.read(q, m, n - m);
                    if (k <= 0) return false;
                    m += k;
                }
                for (int i = 0; i < n; i++) if (p[i] != q[i]) return false;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
