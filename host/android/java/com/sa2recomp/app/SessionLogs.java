package com.sa2recomp.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Session logs where the player can reach them. The native session (libmain.so) keeps each
 * session in the app's private files/sessions/<stamp>-<pid>/ (the game's working folder, with its
 * FIFOs, which shared storage cannot hold) and its report in files/last-session.txt, and names
 * those private paths in the report. Android lets no file manager into them, so each session's
 * regular files are copied to Internal storage/OpenSA2/logs/<session>/, with the report, the
 * codes log and the launcher's own log of the Play, and the report is shown with the public
 * path in place of the private one. The newest KEEP sessions are kept there.
 *
 * This runs whenever the launcher comes back, not only right after a Play, so a session whose
 * launcher was closed meanwhile (the system ends the launcher's process while the game runs)
 * still gets its logs copied.
 */
final class SessionLogs {
    private SessionLogs() {}

    static final int KEEP = 5;
    private static final long MAX_FILE = 64L << 20;
    private static final Pattern NAME = Pattern.compile("\\d{8}-\\d{6}-\\d+(-stream)?");

    static boolean wanted(Context c) {
        return OpenSa2.prefs(c).getBoolean("save_logs", true) && OpenSa2.haveStorage();
    }

    static File privateSessions(Context c) {
        return new File(c.getFilesDir(), "sessions");
    }

    /** The newest session folder, or null. */
    static File newest(Context c) {
        File[] dirs = privateSessions(c).listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;
        Arrays.sort(dirs, (a, b) -> a.getName().compareTo(b.getName()));
        return dirs[dirs.length - 1];
    }

    /** `text` with the app's private session paths written as the public logs folder's. */
    static String publicPaths(Context c, String text) {
        if (text == null) return null;
        String priv = privateSessions(c).getAbsolutePath() + "/";
        String pub = "Internal storage/OpenSA2/logs/";
        String out = text.replace(priv, pub);
        // The same folder under /data/data and /data/user/0.
        String pkg = c.getPackageName();
        out = out.replace("/data/user/0/" + pkg + "/files/sessions/", pub)
                 .replace("/data/data/" + pkg + "/files/sessions/", pub);
        return out;
    }

    /**
     * Copies every kept session's files out, the report and the launcher's logs into the newest
     * one's, and drops copies past KEEP. Returns the newest session's public folder, or null.
     */
    static File export(Context c) throws IOException {
        File[] dirs = privateSessions(c).listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;
        Arrays.sort(dirs, (a, b) -> a.getName().compareTo(b.getName()));
        File pub = OpenSa2.logsDir();
        if (!pub.isDirectory() && !pub.mkdirs()) throw new IOException("cannot create " + pub);
        for (File d : dirs) mirror(d, new File(pub, d.getName()));
        File newest = dirs[dirs.length - 1];
        File out = new File(pub, newest.getName());
        String report = OpenSa2.readText(new File(c.getFilesDir(), "last-session.txt"));
        // The report is the newest session's when it names that session's folder.
        if (report != null && report.contains(newest.getName())) {
            OpenSa2.writeText(new File(out, "last-session.txt"), publicPaths(c, report));
        }
        // The codes engine's log and the launcher's log of the Play that started the session.
        for (String n : new String[] {"codes.log", "launcher.log"}) {
            File f = new File(c.getFilesDir(), n);
            File t = new File(out, n);
            if (f.isFile() && !same(f, t)) OpenSa2.copyFile(f, t);
        }
        prune(pub);
        return out;
    }

    private static boolean same(File a, File b) {
        return b.isFile() && a.length() == b.length() && b.lastModified() >= a.lastModified();
    }

    /** Regular files only (the FIFOs are left), each under MAX_FILE, copied when changed. */
    private static void mirror(File from, File to) throws IOException {
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(to, k.getName());
            if (k.isDirectory()) {
                mirror(k, t);
            } else if (k.isFile() && k.length() <= MAX_FILE && !same(k, t)) {
                OpenSa2.copyFile(k, t);
            }
        }
    }

    private static void prune(File pub) {
        File[] dirs = pub.listFiles(f -> f.isDirectory() && NAME.matcher(f.getName()).matches());
        if (dirs == null || dirs.length <= KEEP) return;
        Arrays.sort(dirs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < dirs.length - KEEP; i++) OpenSa2.removeTree(dirs[i]);
    }

    /**
     * Why a session failed, from its own logs: the player's verdict and failures (player.log's
     * end, host/player/player.cpp) and, when the game ended by itself, game.log's last lines.
     */
    static List<String> why(File session, String report) {
        List<String> out = new ArrayList<>();
        if (session == null || report == null || !report.contains("FAIL")) return out;
        String tail = tail(new File(session, "player.log"), 64 << 10);
        if (tail != null) {
            boolean in = false;
            for (String l : tail.split("\n")) {
                if (l.startsWith("verdict:")) {
                    in = true;
                    out.add("player.log: " + l.trim());
                } else if (in && l.startsWith("  failure:")) {
                    out.add("  " + l.trim());
                } else if (in) {
                    in = false;
                }
            }
        }
        if (report.contains("the game ended (exit") || report.contains("the game presented nothing")) {
            String g = tail(new File(session, "game.log"), 16 << 10);
            if (g != null) {
                String[] lines = g.split("\n");
                out.add("game.log ends:");
                for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) out.add("  " + lines[i]);
            }
        }
        return out;
    }

    static String tail(File f, int max) {
        if (!f.isFile()) return null;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int n = (int) Math.min(len, max);
            byte[] b = new byte[n];
            r.seek(len - n);
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
