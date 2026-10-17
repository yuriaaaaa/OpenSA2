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
 * Read to tell a mod's archive from the game's when a mod replaces one (a texture in another place
 * or with another global index is the first thing to check when a mod's texture shows up in the
 * wrong place), and to merge one that several mods replace (merge).
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

    /**
     * Several mods replace the same archive (Sonikko's and Shady's stageMap.pak, each with one
     * character's icon changed, and Character Select Plus's with the extra characters): the
     * loader keeps only the last, which puts back the other mods' characters as the game has them.
     * This writes the last mod's archive with every texture it leaves as the game's taken from the
     * latest earlier mod that changed it (its .inf row's size too; the place and global index stay
     * the last mod's). Returns the textures taken, each "name (mod)"; nothing is written when none
     * is. `mods` are {name, File} in mod order.
     */
    static List<String> merge(File game, List<Object[]> mods, File out) throws IOException {
        List<String> taken = new ArrayList<>();
        if (mods.size() < 2) return taken;
        Map<String, byte[]> g = new HashMap<>();
        for (Object[] f : TexturePack.pakFiles(game)) g.put(((String) f[0]).toLowerCase(Locale.ROOT), (byte[]) f[1]);
        List<Map<String, byte[]>> earlier = new ArrayList<>();
        for (int i = 0; i < mods.size() - 1; i++) {
            Map<String, byte[]> m = new HashMap<>();
            for (Object[] f : TexturePack.pakFiles((File) mods.get(i)[1])) m.put(((String) f[0]).toLowerCase(Locale.ROOT), (byte[]) f[1]);
            earlier.add(m);
        }
        List<Object[]> base = TexturePack.pakFiles((File) mods.get(mods.size() - 1)[1]);
        int infAt = -1;
        Map<String, Integer> from = new HashMap<>();   // texture short name (no .dds) -> earlier mod
        for (int k = 0; k < base.size(); k++) {
            String n = ((String) base.get(k)[0]).toLowerCase(Locale.ROOT);
            if (n.endsWith(".inf")) {
                infAt = k;
                continue;
            }
            byte[] gd = g.get(n);
            if (gd == null || !java.util.Arrays.equals(gd, (byte[]) base.get(k)[1])) continue;
            for (int i = earlier.size() - 1; i >= 0; i--) {
                byte[] md = earlier.get(i).get(n);
                if (md != null && !java.util.Arrays.equals(md, gd)) {
                    base.get(k)[1] = md;
                    String tn = n.substring(n.lastIndexOf('\\') + 1);
                    if (tn.endsWith(".dds") || tn.endsWith(".png")) tn = tn.substring(0, tn.length() - 4);
                    from.put(tn, i);
                    taken.add(tn + " (" + mods.get(i)[0] + ")");
                    break;
                }
            }
        }
        if (taken.isEmpty()) return taken;
        if (infAt >= 0) {
            String infName = ((String) base.get(infAt)[0]).toLowerCase(Locale.ROOT);
            byte[] inf = ((byte[]) base.get(infAt)[1]).clone();
            for (int o = 0; o + 60 <= inf.length; o += 60) {
                int e = o;
                while (e < o + 28 && inf[e] != 0) e++;
                String tn = new String(inf, o, e - o, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                Integer i = from.get(tn);
                byte[] theirs = i == null ? null : earlier.get(i).get(infName);
                if (theirs == null) continue;
                for (int p = 0; p + 60 <= theirs.length; p += 60) {
                    int e2 = p;
                    while (e2 < p + 28 && theirs[e2] != 0) e2++;
                    if (!new String(theirs, p, e2 - p, StandardCharsets.ISO_8859_1).equalsIgnoreCase(tn)) continue;
                    System.arraycopy(theirs, p + 32, inf, o + 32, 28);   // all but the name and global index
                    break;
                }
            }
            base.get(infAt)[1] = inf;
        }
        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        for (Object[] f : base) {
            names.add((String) f[0]);
            datas.add((byte[]) f[1]);
        }
        TexturePack.writePakFile(names, datas, out);
        return taken;
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
