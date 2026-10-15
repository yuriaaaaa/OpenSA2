package com.sa2recomp.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 2012 PC game's texture archives (resource/gd_PC/PRS/*.pak): "\x01pak", a file count at
 * 0x39, then each file's long path, short path and size twice, then the files in order. The
 * archive's <name>.inf lists its textures, 60 bytes each: a 28-byte name, the global index and
 * the size. The game fills a texture list in the .inf's order, and textures that share a global
 * index share one loaded texture.
 *
 * Only read here, to tell a mod's archive from the game's when a mod replaces one: a texture in
 * another place or with another global index is the first thing to check when a mod's texture
 * shows up in the wrong place.
 */
final class Pak {
    private Pak() {}

    static final class Tex {
        String name;
        long gbix;
        int width, height;
    }

    /** The textures the archive's .inf lists, in order; null when the file is no PAK. */
    static List<Tex> inf(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[0x3D];
            if (r.length() < head.length) return null;
            r.readFully(head);
            if (head[0] != 1 || head[1] != 'p' || head[2] != 'a' || head[3] != 'k') return null;
            int count = le32(head, 0x39);
            if (count <= 0 || count > 100000) return null;
            long at = 0x3D;
            List<String> names = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            byte[] w = new byte[4];
            for (int i = 0; i < count; i++) {
                r.seek(at);
                r.readFully(w);
                int l = le32(w, 0);
                if (l < 0 || l > 4096) return null;
                at += 4 + l;
                r.seek(at);
                r.readFully(w);
                int l2 = le32(w, 0);
                if (l2 < 0 || l2 > 4096) return null;
                byte[] nm = new byte[l2];
                r.readFully(nm);
                names.add(new String(nm, StandardCharsets.ISO_8859_1));
                at += 4 + l2;
                r.seek(at);
                r.readFully(w);
                sizes.add(le32(w, 0));
                at += 8;
            }
            long data = at;
            for (int i = 0; i < count; i++) {
                int size = sizes.get(i);
                if (names.get(i).toLowerCase(Locale.ROOT).endsWith(".inf")) {
                    if (size < 0 || size > (1 << 20)) return null;
                    byte[] b = new byte[size];
                    r.seek(data);
                    r.readFully(b);
                    List<Tex> out = new ArrayList<>();
                    for (int o = 0; o + 60 <= b.length; o += 60) {
                        Tex t = new Tex();
                        int e = o;
                        while (e < o + 28 && b[e] != 0) e++;
                        t.name = new String(b, o, e - o, StandardCharsets.ISO_8859_1);
                        t.gbix = le32(b, o + 28) & 0xFFFFFFFFL;
                        t.width = le32(b, o + 44);
                        t.height = le32(b, o + 48);
                        out.add(t);
                    }
                    return out;
                }
                data += Math.max(0, size);
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /**
     * How a mod's archive differs from the game's: lines for the log, empty when the textures
     * the game has are where the game has them, under the same global index.
     */
    static List<String> compare(File game, File mod) {
        List<String> out = new ArrayList<>();
        List<Tex> g = inf(game), m = inf(mod);
        if (g == null || m == null) return out;
        Map<String, Integer> gAt = new HashMap<>(), mAt = new HashMap<>();
        for (int i = 0; i < g.size(); i++) gAt.put(g.get(i).name.toLowerCase(Locale.ROOT), i);
        for (int i = 0; i < m.size(); i++) mAt.put(m.get(i).name.toLowerCase(Locale.ROOT), i);
        int moved = 0, regbix = 0, missing = 0;
        List<String> detail = new ArrayList<>();
        for (int i = 0; i < g.size(); i++) {
            Tex gt = g.get(i);
            Integer mi = mAt.get(gt.name.toLowerCase(Locale.ROOT));
            if (mi == null) {
                missing++;
                if (detail.size() < 8) detail.add("  the game's texture " + i + " " + gt.name + " is not in the mod's");
                continue;
            }
            Tex mt = m.get(mi);
            if (mi != i) {
                moved++;
                if (detail.size() < 8) detail.add("  " + gt.name + ": texture " + i + " in the game's, " + mi + " in the mod's");
            }
            if (mt.gbix != gt.gbix) {
                regbix++;
                if (detail.size() < 8) detail.add("  " + gt.name + ": global index " + gt.gbix + " in the game's, " + mt.gbix + " in the mod's");
            }
        }
        int added = 0;
        for (Tex mt : m) if (!gAt.containsKey(mt.name.toLowerCase(Locale.ROOT))) added++;
        if (moved + regbix + missing + added == 0) return out;
        out.add(mod.getName() + ": " + g.size() + " textures in the game's, " + m.size() + " in the mod's"
                + (moved > 0 ? "; " + moved + " in another place" : "")
                + (regbix > 0 ? "; " + regbix + " with another global index" : "")
                + (missing > 0 ? "; " + missing + " missing" : "")
                + (added > 0 ? "; " + added + " added" : ""));
        out.addAll(detail);
        return out;
    }
}
