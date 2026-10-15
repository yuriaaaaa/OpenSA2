package com.sa2recomp.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * OpenSA2's launcher (this fork): where things live and the settings both processes share.
 *
 * Everything the player handles lives in one public folder, Internal storage/OpenSA2/, rather
 * than the app's Android/data folder, which Android 11+ file managers cannot write reliably:
 *
 *   OpenSA2/game/      the 2012 PC game folder (sonic2app.exe and resource/), copied as is
 *   OpenSA2/mods/      sa2-mod-loader mods, one folder each with its mod.ini
 *   OpenSA2/Codes.lst  the mod loader's code list (downloaded by the Codes screen)
 *   OpenSA2/logs/      each session's logs (SessionLogs), unless Settings turns that off
 *   OpenSA2/saves/     the game's saves, copied out after each session (Saves)
 *
 * The app needs "All files access" for that folder; the game process the session starts runs
 * as the app's own user, so it reads the game folder with the same access.
 *
 * The installation is no longer checked file by file: this launcher writes the installer's
 * config itself (writeConfig), so changed and modded files are accepted. The game's runtime
 * still checks sonic2app.exe itself when it loads it (runtime/sa2_exe_overlay.h), because the
 * recompiled code only fits the 2012 Steam executable.
 */
final class OpenSa2 {
    static final String GAME_EXE = "sonic2app.exe";
    /** tools/pins.json executables.sonic2app.exe: the shipped Steam build the release targets. */
    static final String STEAM_EXE_SHA256 = "4b1e09d782a2d919cbed2324ba2c0c78ef5fbe1677d400edab39608191f12c16";
    /** The install table the release's session expects in the config (libmain.so). */
    static final String TABLE_ID = "install-hashes-v1";
    static final String TABLE_RECEIPT = "69d83628f568db2876d05bde9e37315f5e061b36c606f222f8d1723715b58535";

    static final int BLUE = 0xFF0A3CE6;
    static final int GOLD = 0xFFFFC21A;
    static final int NIGHT = 0xFF101830;

    private OpenSa2() {}

    // ------------------------------------------------------------ settings

    @SuppressWarnings("deprecation")
    static SharedPreferences prefs(Context c) {
        // The play screen runs in its own process (":play"); MODE_MULTI_PROCESS rereads the file
        // when each process asks for it, so a change made in the launcher is seen at the next start.
        return c.getSharedPreferences("opensa2", Context.MODE_MULTI_PROCESS);
    }

    static final String[] RES_KEYS = {"screen", "h1080", "h900", "h720", "h540", "h480", "43"};
    static final String[] RES_NAMES = {"Screen (native)", "1080 lines", "900 lines", "720 lines",
                                       "540 lines", "480 lines", "4:3 (original, with bars)"};

    static String resName(String key) {
        for (int i = 0; i < RES_KEYS.length; i++) if (RES_KEYS[i].equals(key)) return RES_NAMES[i];
        return RES_NAMES[0];
    }

    // ------------------------------------------------------------ paths

    static File root() {
        return new File(Environment.getExternalStorageDirectory(), "OpenSA2");
    }

    static File gameDir() {
        return new File(root(), "game");
    }

    static File modsDir() {
        return new File(root(), "mods");
    }

    static File logsDir() {
        return new File(root(), "logs");
    }

    static File codesFile() {
        return new File(root(), "Codes.lst");
    }

    static File configFile(Context c) {
        return new File(c.getFilesDir(), "config/sa2recomp/sa2recomp.cfg");
    }

    static File overlayDir(Context c) {
        return new File(c.getFilesDir(), "data/sa2recomp/overlay");
    }

    /** The folder game.env.extra is read from (the session's --external). */
    static File envDir(Context c) {
        return new File(c.getFilesDir(), "env");
    }

    static boolean haveStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return true;   // API 29: requestLegacyExternalStorage and the runtime permission
    }

    static void makeFolders() {
        gameDir().mkdirs();
        modsDir().mkdirs();
    }

    /** The folder holding sonic2app.exe: game/ itself, or a folder in it, or one below that. */
    static File findGameRoot() {
        File g = gameDir();
        File hit = holdsExe(g);
        if (hit != null) return hit;
        File[] subs = g.listFiles();
        if (subs == null) return null;
        for (File s : subs) {
            if (!s.isDirectory()) continue;
            hit = holdsExe(s);
            if (hit != null) return hit;
            File[] deeper = s.listFiles();
            if (deeper == null) continue;
            for (File d : deeper) if (d.isDirectory() && holdsExe(d) != null) return d;
        }
        return null;
    }

    static File holdsExe(File dir) {
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) if (n.equalsIgnoreCase(GAME_EXE)) return dir;
        return null;
    }

    static File exeIn(File root) {
        String[] names = root.list();
        if (names != null) for (String n : names) if (n.equalsIgnoreCase(GAME_EXE)) return new File(root, n);
        return new File(root, GAME_EXE);
    }

    /** `rel` ("resource/gd_PC/foo.prs") under `base`, each part spelled as it already is on disk
     * (case-blind), so no second folder that differs only in case is ever made. */
    static File resolveCase(File base, String rel) {
        File cur = base;
        for (String part : rel.split("/")) {
            if (part.isEmpty()) continue;
            String use = part;
            String[] names = cur.list();
            if (names != null) {
                for (String n : names) {
                    if (n.equalsIgnoreCase(part)) {
                        use = n;
                        break;
                    }
                }
            }
            cur = new File(cur, use);
        }
        return cur;
    }

    // ------------------------------------------------------------ the installation

    static String configValue(Context c, String key) {
        String text = readText(configFile(c));
        if (text == null) return null;
        for (String line : text.split("\n")) if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
        return null;
    }

    static boolean installed(Context c) {
        String root = configValue(c, "read_root");
        return root != null && new File(root).isDirectory() && holdsExe(new File(root)) != null;
    }

    /** Writes the installer's config for `root` with no hash table check, and the game's default
     * Config/ files the folder lacks into the overlay. Returns lines for the log. */
    static List<String> writeConfig(Context c, File root) throws IOException {
        List<String> said = new ArrayList<>();
        File overlay = overlayDir(c);
        if (!overlay.isDirectory() && !overlay.mkdirs()) throw new IOException("cannot create " + overlay);
        File exe = exeIn(root);
        boolean modLoader = new File(resolveCase(root, "resource/gd_PC/DLL/Win32"), "Data_DLL_orig.dll").isFile()
                || resolveCase(root, "resource/gd_PC/DLL/Win32/Data_DLL_orig.dll").isFile();
        StringBuilder b = new StringBuilder();
        b.append("# sa2recomp configuration, written by the OpenSA2 launcher (no file hash check).\n");
        b.append("format=1\n");
        b.append("table.id=").append(TABLE_ID).append('\n');
        b.append("table.receipt_sha256=").append(TABLE_RECEIPT).append('\n');
        b.append("source.kind=folder\n");
        b.append("source.path=").append(root.getAbsolutePath()).append('\n');
        b.append("read_root=").append(root.getAbsolutePath()).append('\n');
        b.append("overlay=").append(overlay.getAbsolutePath()).append('\n');
        b.append("exe=").append(exe.getAbsolutePath()).append('\n');
        b.append("verified.at=").append(nowIso()).append('\n');
        b.append("verified.mode=none\n");
        b.append("policy=OpenSA2: every file accepted as it is; the runtime checks the executable\n");
        b.append("mod_loader=").append(modLoader ? 1 : 0).append('\n');
        b.append("extras=0\n");
        writeText(configFile(c), b.toString());
        said.add("Config written: game folder " + root.getAbsolutePath());
        for (String[] d : UserDefaults.FILES) {
            if (resolveCase(root, d[0]).isFile()) continue;
            File o = resolveCase(overlay, d[0]);
            if (o.isFile()) continue;
            writeText(o, d[1]);
            said.add("Wrote the game's default " + d[0]);
        }
        return said;
    }

    // ------------------------------------------------------------ game.env.extra

    /** The settings the session passes to the game: resolution, HUD, speed and debug lines. */
    static void writeGameEnv(Context c) {
        SharedPreferences p = prefs(c);
        StringBuilder b = new StringBuilder();
        String res = p.getString("res_mode", "screen");
        if ("43".equals(res)) b.append("SA2_PLAY_RES=off\n");
        else if (res.startsWith("h")) b.append("SA2_PLAY_RES=").append(res).append('\n');
        b.append("SA2_PLAY_HUD43=").append(p.getBoolean("hud43", true) ? 1 : 0).append('\n');
        if (!p.getBoolean("adpf", true)) b.append("SA2_ADPF=0\n");
        for (String line : p.getString("env_extra", "").split("\n")) {
            line = line.trim();
            if (line.startsWith("SA2_") && line.contains("=")) b.append(line).append('\n');
        }
        try {
            writeText(new File(envDir(c), "game.env.extra"), b.toString());
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------ files

    static String readText(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in, 8 << 20), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > cap) break;
        }
        return out.toByteArray();
    }

    static void writeText(File f, String text) throws IOException {
        writeBytes(f, text.getBytes(StandardCharsets.UTF_8));
    }

    static void writeBytes(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
        }
    }

    static void copyFile(File from, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        to.delete();
        if (!tmp.renameTo(to)) throw new IOException("cannot write " + to);
        to.setLastModified(from.lastModified());
    }

    static void removeTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) removeTree(k);
        f.delete();
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format(Locale.ROOT, "%02x", x));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }
}
