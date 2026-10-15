package com.sa2recomp.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GameBanana one-click install for the Mods screen: a mod page URL, a bare mod id, or a
 * one-click sa2mm: link is turned into a download, which is unpacked into Internal
 * storage/OpenSA2/mods/ (ModInstall; GameBanana's SA2 mods are .zip or .7z, both handled).
 *
 * The one-click link is the format SA Mod Manager registers (sa2mm:&lt;url&gt;,gb_itemtype:Mod,
 * gb_itemid:12345,...). When no direct download URL is given, the mod's files are read from the
 * public API (apiv11 ProfilePage) and the best one is chosen: a .zip before a .7z, the newest
 * when several, skipping files the manager would not treat as a mod archive.
 */
final class GameBanana {
    private GameBanana() {}

    static final String SCHEME = "sa2mm";
    private static final int MAX_BYTES = 400 << 20;

    interface Progress {
        void line(String s);
    }

    /** What a request resolves to. */
    static final class Target {
        long itemId;
        String downloadUrl;    // a direct file URL, when the request carried one
        String name;           // the mod's name, when known
        String folder;         // a folder name the one-click link asked for
    }

    /** A mod page URL (gamebanana.com/mods/12345), a bare id, or an sa2mm: one-click link. */
    static Target parse(String input) throws IOException {
        Target t = new Target();
        String s = input.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith(SCHEME + ":")) {
            String rest = s.substring(SCHEME.length() + 1);
            String[] parts = rest.split(",");
            if (parts.length > 0 && parts[0].contains("://")) t.downloadUrl = parts[0];
            for (int i = (t.downloadUrl != null ? 1 : 0); i < parts.length; i++) {
                int colon = parts[i].indexOf(':');
                if (colon <= 0) continue;
                String k = parts[i].substring(0, colon).toLowerCase(Locale.ROOT);
                String v = parts[i].substring(colon + 1);
                if (k.equals("gb_itemid")) t.itemId = parseLong(v);
                else if (k.equals("name")) t.name = decode(v);
                else if (k.equals("folder")) t.folder = decode(v);
            }
            if (t.itemId == 0 && t.downloadUrl != null) t.itemId = idFromUrl(t.downloadUrl);
            return t;
        }
        if (s.matches("\\d+")) {
            t.itemId = Long.parseLong(s);
            return t;
        }
        t.itemId = idFromUrl(s);
        if (t.itemId == 0) throw new IOException("not a GameBanana mod link, id or one-click link: " + input);
        return t;
    }

    private static long idFromUrl(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("gamebanana\\.com/(?:mods|dl)/(\\d+)").matcher(url);
        return m.find() ? parseLong(m.group(1)) : 0;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Installs the mod a request names. Returns the installed folder names.
     */
    static List<String> install(Context c, String input, Progress p) throws IOException {
        Target t = parse(input);
        if (t.name != null) p.line("GameBanana: " + t.name + (t.itemId != 0 ? " (mod " + t.itemId + ")" : ""));
        String url = t.downloadUrl;
        String fileName = null;
        if (url == null) {
            if (t.itemId == 0) throw new IOException("no mod id to look up");
            JSONObject profile = getJson("https://gamebanana.com/apiv11/Mod/" + t.itemId + "/ProfilePage");
            if (t.name == null) t.name = profile.optString("_sName", "mod " + t.itemId);
            p.line("GameBanana: " + t.name);
            Object[] pick = pickFile(profile);
            url = (String) pick[0];
            fileName = (String) pick[1];
            p.line("Downloading " + fileName + " (" + human((long) pick[2]) + ")...");
        }
        String base = ModInstall.safeName(t.folder != null ? t.folder : t.name != null ? t.name
                : fileName != null ? fileName : "gamebanana-" + t.itemId);
        File cache = new File(c.getCacheDir(), "gb-download");
        cache.mkdirs();
        File archive = new File(cache, "mod-" + System.currentTimeMillis()
                + (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".7z") ? ".7z" : ".zip"));
        try {
            download(url, archive, p);
            p.line("Downloaded " + human(archive.length()) + ", unpacking...");
            return ModInstall.install(c, archive, base, p::line);
        } finally {
            archive.delete();
        }
    }

    /** The file to download: a .zip before a .7z, the newest added when several of a kind. */
    private static Object[] pickFile(JSONObject profile) throws IOException {
        JSONArray files = profile.optJSONArray("_aFiles");
        if (files == null || files.length() == 0) throw new IOException("the mod has no downloadable files");
        JSONObject bestZip = null, best7z = null, bestOther = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String name = f.optString("_sFile", "").toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) bestZip = newer(bestZip, f);
            else if (name.endsWith(".7z")) best7z = newer(best7z, f);
            else if (name.endsWith(".rar")) { /* skip: RAR cannot be unpacked here */ }
            else bestOther = newer(bestOther, f);
        }
        JSONObject f = bestZip != null ? bestZip : best7z != null ? best7z : bestOther;
        if (f == null) throw new IOException("the mod has only files this app cannot unpack (RAR); "
                + "open it on GameBanana and download a .zip or .7z");
        return new Object[] {f.optString("_sDownloadUrl"), f.optString("_sFile"), f.optLong("_nFilesize")};
    }

    private static JSONObject newer(JSONObject a, JSONObject b) {
        if (a == null) return b;
        return b.optLong("_tsDateAdded") >= a.optLong("_tsDateAdded") ? b : a;
    }

    private static JSONObject getJson(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("GameBanana answered HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                byte[] b = OpenSa2.readAll(in, 8 << 20);
                return new JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("GameBanana sent something unexpected: " + e.getMessage());
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File to, Progress p) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("the download answered HTTP " + code);
            long total = conn.getContentLengthLong();
            if (total > MAX_BYTES) throw new IOException("the file is larger than " + human(MAX_BYTES));
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[1 << 16];
                long got = 0, lastSaid = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (got > MAX_BYTES) throw new IOException("the download is too large");
                    if (total > 0 && got - lastSaid > (4 << 20)) {
                        p.line("  " + (got * 100 / total) + "% (" + human(got) + " of " + human(total) + ")");
                        lastSaid = got;
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "OpenSA2/1.0 (Android)");
        conn.setRequestProperty("Accept", "application/json, */*");
        return conn;
    }

    static String human(long n) {
        if (n >= (1 << 20)) return String.format(Locale.ROOT, "%.1f MB", n / (double) (1 << 20));
        if (n >= (1 << 10)) return String.format(Locale.ROOT, "%.0f KB", n / (double) (1 << 10));
        return n + " B";
    }
}
