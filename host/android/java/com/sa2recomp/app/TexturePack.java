package com.sa2recomp.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The mod loader's texture packs, turned into the PAK archives the 2012 PC game reads.
 *
 * A mod's textures/&lt;name&gt;/ folder (index.txt: "global index,file[,WxH]" a line, '#' for a
 * comment) or textures/&lt;name&gt;.pvmx archive replaces the texture list &lt;name&gt; on PC: the
 * loader hooks the game's PAK loader (LoadPAKTextures) and builds the list from the pack instead,
 * in the pack's order. This port has no loader, but the game itself reads
 * resource/gd_PC/PRS/&lt;name&gt;.pak first, and its texture loader takes PNG as well as DDS, so the
 * same list is written as a PAK: an &lt;name&gt;.inf (60 bytes a texture: name, global index, size)
 * and each image under &lt;name&gt;\&lt;texture&gt;.dds (whatever its format, as SA Tools writes them).
 * When the game's own PAK of that name has more textures than the pack, the rest are kept from it
 * by position, so the list is never shorter than the game expects.
 */
final class TexturePack {
    private TexturePack() {}

    static final class Entry {
        long gbix;
        String name;         // without extension, lower case
        File file;           // a file of a folder pack
        long offset, size;   // a PVMX's entry
        int width, height;   // the size the pack gives (0: the image's own)
    }

    /** A texture pack folder: textures/&lt;name&gt;/ with an index.txt. */
    static boolean isFolder(File dir) {
        return dir.isDirectory() && OpenSa2.resolveCase(dir, "index.txt").isFile();
    }

    /** A .pvmx archive ("PVMX", version 1). */
    static boolean isPvmx(File f) {
        if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".pvmx")) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[5];
            r.readFully(h);
            return h[0] == 'P' && h[1] == 'V' && h[2] == 'M' && h[3] == 'X' && h[4] == 1;
        } catch (IOException e) {
            return false;
        }
    }

    /** index.txt's entries, in order. */
    static List<Entry> readFolder(File dir) throws IOException {
        String text = OpenSa2.readText(OpenSa2.resolveCase(dir, "index.txt"));
        if (text == null) throw new IOException("no index.txt");
        List<Entry> out = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\n")) {
            n++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",");
            if (f.length < 2) throw new IOException("index.txt line " + n + " has no comma");
            Entry e = new Entry();
            try {
                e.gbix = Long.parseLong(f[0].trim()) & 0xFFFFFFFFL;
            } catch (NumberFormatException x) {
                throw new IOException("index.txt line " + n + ": bad global index " + f[0]);
            }
            String file = f[1].trim();
            if (ModManager.safeRel(file) == null) throw new IOException("index.txt line " + n + " leaves the folder: " + file);
            e.file = OpenSa2.resolveCase(dir, file);
            if (!e.file.isFile()) throw new IOException("index.txt line " + n + ": " + file + " is missing");
            e.name = stem(file);
            if (f.length >= 3) {
                String[] wh = f[2].trim().toLowerCase(Locale.ROOT).split("x");
                if (wh.length == 2) {
                    try {
                        e.width = Integer.parseInt(wh[0].trim());
                        e.height = Integer.parseInt(wh[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            out.add(e);
        }
        if (out.isEmpty()) throw new IOException("index.txt lists no textures");
        return out;
    }

    /** A PVMX's dictionary (pvmx.cpp in sa2-mod-loader: typed fields, then offset and size). */
    static List<Entry> readPvmx(File f) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.seek(5);
            for (int type = r.read(); type != 0; type = r.read()) {
                if (type < 0) throw new IOException("the PVMX ends early");
                Entry e = new Entry();
                while (type != 0) {
                    if (type == 1) {
                        e.gbix = Integer.reverseBytes(r.readInt()) & 0xFFFFFFFFL;
                    } else if (type == 2) {
                        StringBuilder b = new StringBuilder();
                        for (int c = r.read(); c > 0; c = r.read()) b.append((char) c);
                        e.name = stem(b.toString());
                    } else if (type == 3) {
                        e.width = Integer.reverseBytes(r.readInt());
                        e.height = Integer.reverseBytes(r.readInt());
                    } else {
                        throw new IOException("the PVMX has an unknown field " + type);
                    }
                    type = r.read();
                }
                e.offset = Long.reverseBytes(r.readLong());
                e.size = Long.reverseBytes(r.readLong());
                if (e.name == null || e.offset < 0 || e.size <= 0 || e.offset + e.size > f.length())
                    throw new IOException("the PVMX has a bad entry");
                out.add(e);
                if (out.size() > 10000) throw new IOException("the PVMX has too many entries");
            }
        }
        return out;
    }

    static String stem(String file) {
        String n = file.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.toLowerCase(Locale.ROOT).trim();
        return n.length() > 28 ? n.substring(0, 28) : n;
    }

    private static byte[] data(Entry e, File pvmx) throws IOException {
        if (e.file != null) {
            if (e.file.length() > (64 << 20)) throw new IOException(e.file.getName() + " is too large");
            return java.nio.file.Files.readAllBytes(e.file.toPath());
        }
        if (e.size > (64 << 20)) throw new IOException(e.name + " is too large");
        byte[] b = new byte[(int) e.size];
        try (RandomAccessFile r = new RandomAccessFile(pvmx, "r")) {
            r.seek(e.offset);
            r.readFully(b);
        }
        return b;
    }

    /** An image's own width and height: PNG and DDS (the formats packs use); null for others. */
    static int[] size(byte[] b) {
        if (b.length >= 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
            return new int[] {be32(b, 16), be32(b, 20)};
        if (b.length >= 20 && b[0] == 'D' && b[1] == 'D' && b[2] == 'S' && b[3] == ' ')
            return new int[] {le32(b, 16), le32(b, 12)};
        return null;
    }

    /** One texture of the PAK being written. */
    private static final class Tex {
        String name;
        long gbix;
        int w, h;
        byte[] data;
        byte[] inf;    // the game's own 60-byte row, when kept from its PAK
    }

    /**
     * Writes &lt;name&gt;.pak for the pack (`entries` from `src`, a folder or a .pvmx). `gamePak` is
     * the game's own PAK of that name, or null. Returns how many textures it holds.
     */
    static int writePak(String name, List<Entry> entries, File src, File gamePak, File out) throws IOException {
        String pvm = name.toLowerCase(Locale.ROOT);
        List<Tex> list = new ArrayList<>();
        for (Entry e : entries) {
            Tex t = new Tex();
            t.name = e.name;
            t.gbix = e.gbix;
            t.data = data(e, src);
            int[] wh = size(t.data);
            if (e.width > 0 && e.height > 0) wh = new int[] {e.width, e.height};
            if (wh == null) throw new IOException(e.name + ": only PNG and DDS images can be used here");
            t.w = wh[0];
            t.h = wh[1];
            list.add(t);
        }
        // The game's own textures past the pack's last, by position.
        List<Pak.Tex> gameInf = gamePak != null && gamePak.isFile() ? Pak.inf(gamePak) : null;
        if (gameInf != null && gameInf.size() > list.size()) {
            List<Object[]> game = pakFiles(gamePak);
            byte[] inf = null;
            for (Object[] f : game) if (((String) f[0]).equalsIgnoreCase(pvm + "\\" + pvm + ".inf")) inf = (byte[]) f[1];
            if (inf != null) {
                for (int i = list.size(); (i + 1) * 60 <= inf.length; i++) {
                    String tn = cstr(inf, i * 60, 28);
                    byte[] body = null;
                    for (Object[] f : game) if (((String) f[0]).equalsIgnoreCase(pvm + "\\" + tn + ".dds")) body = (byte[]) f[1];
                    if (body == null) break;
                    Tex t = new Tex();
                    t.name = tn.toLowerCase(Locale.ROOT);
                    t.data = body;
                    t.inf = Arrays.copyOfRange(inf, i * 60, i * 60 + 60);
                    list.add(t);
                }
            }
        }
        // Names must be unique in the archive: a repeated one gets a suffix in both places.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Tex t : list) {
            String base = t.name.isEmpty() ? "tex" : t.name;
            String n = base;
            for (int k = 1; !seen.add(n); k++) {
                String sfx = "_" + k;
                n = (base.length() + sfx.length() > 28 ? base.substring(0, 28 - sfx.length()) : base) + sfx;
            }
            if (!n.equals(t.name) && t.inf != null) {
                byte[] nb = n.getBytes(StandardCharsets.ISO_8859_1);
                Arrays.fill(t.inf, 0, 28, (byte) 0);
                System.arraycopy(nb, 0, t.inf, 0, Math.min(28, nb.length));
            }
            t.name = n;
        }
        ByteArrayOutputStream inf = new ByteArrayOutputStream();
        for (Tex t : list) {
            byte[] row = t.inf;
            if (row == null) {
                row = new byte[60];
                byte[] nb = t.name.getBytes(StandardCharsets.ISO_8859_1);
                System.arraycopy(nb, 0, row, 0, Math.min(28, nb.length));
                put32(row, 28, (int) t.gbix);
                put32(row, 44, t.w);
                put32(row, 48, t.h);
                put32(row, 56, 0x80000000);    // mipmapped, as the game's own rows have it
            }
            inf.write(row, 0, 60);
        }
        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        names.add(pvm + "\\" + pvm + ".inf");
        datas.add(inf.toByteArray());
        for (Tex t : list) {
            names.add(pvm + "\\" + t.name + ".dds");
            datas.add(t.data);
        }
        writePakFile(names, datas, out);
        return list.size();
    }

    /** The PAK container (SA Tools ArchiveLib PAK.cs): "\x01pak", counts and sizes, the file table, the data. */
    static void writePakFile(List<String> names, List<byte[]> datas, File out) throws IOException {
        long total = 0;
        for (byte[] d : datas) total += d.length;
        if (total > Integer.MAX_VALUE) throw new IOException("the archive would be too large");
        File tmp = new File(out.getPath() + ".tmp");
        out.getParentFile().mkdirs();
        try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            o.write(new byte[] {1, 'p', 'a', 'k'});
            o.write(new byte[33]);
            writeLe(o, names.size());
            writeLe(o, (int) total);
            writeLe(o, (int) total);
            o.write(new byte[8]);
            writeLe(o, names.size());
            for (int i = 0; i < names.size(); i++) {
                byte[] longPath = ("..\\..\\..\\sonic2\\resource\\gd_pc\\prs\\" + names.get(i)).getBytes(StandardCharsets.ISO_8859_1);
                byte[] shortPath = names.get(i).getBytes(StandardCharsets.ISO_8859_1);
                writeLe(o, longPath.length);
                o.write(longPath);
                writeLe(o, shortPath.length);
                o.write(shortPath);
                writeLe(o, datas.get(i).length);
                writeLe(o, datas.get(i).length);
            }
            for (byte[] d : datas) o.write(d);
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
        }
    }

    /** A PAK's files: {short path, bytes}. */
    static List<Object[]> pakFiles(File f) throws IOException {
        List<Object[]> out = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[0x3D];
            r.readFully(head);
            if (head[0] != 1 || head[1] != 'p' || head[2] != 'a' || head[3] != 'k') throw new IOException("not a PAK");
            int count = le32(head, 0x39);
            if (count < 0 || count > 100000) throw new IOException("bad PAK");
            List<String> names = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            byte[] w = new byte[4];
            for (int i = 0; i < count; i++) {
                r.readFully(w);
                int l = le32(w, 0);
                if (l < 0 || l > 4096) throw new IOException("bad PAK");
                r.skipBytes(l);
                r.readFully(w);
                int l2 = le32(w, 0);
                if (l2 < 0 || l2 > 4096) throw new IOException("bad PAK");
                byte[] nm = new byte[l2];
                r.readFully(nm);
                names.add(new String(nm, StandardCharsets.ISO_8859_1));
                r.readFully(w);
                sizes.add(le32(w, 0));
                r.readFully(w);
            }
            for (int i = 0; i < count; i++) {
                int n = sizes.get(i);
                if (n < 0 || n > (256 << 20)) throw new IOException("bad PAK");
                byte[] b = new byte[n];
                r.readFully(b);
                out.add(new Object[] {names.get(i), b});
            }
        }
        return out;
    }

    /** A signature of a pack's files (names, sizes, times), to rebuild only when it changed. */
    static String signature(File src) {
        StringBuilder b = new StringBuilder("v1\n");
        if (src.isFile()) {
            b.append(src.getName()).append(' ').append(src.length()).append(' ').append(src.lastModified()).append('\n');
            return b.toString();
        }
        File[] kids = src.listFiles();
        if (kids != null) {
            Arrays.sort(kids);
            for (File k : kids)
                if (k.isFile()) b.append(k.getName()).append(' ').append(k.length()).append(' ').append(k.lastModified()).append('\n');
        }
        return b.toString();
    }

    private static String cstr(byte[] b, int o, int max) {
        int e = o;
        while (e < o + max && b[e] != 0) e++;
        return new String(b, o, e - o, StandardCharsets.ISO_8859_1).trim();
    }

    private static void writeLe(OutputStream o, int v) throws IOException {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
        o.write(v >>> 24);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
