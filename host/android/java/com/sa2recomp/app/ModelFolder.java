package com.sa2recomp.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A character model folder, the sa2-mod-loader way of replacing a model file such as
 * sonicmdl.prs: gd_PC/sonicmdl/ holding sonicmdl.ini (model index = label, as SA Tools splits
 * the file) and the models as SA2MDL files. On PC the loader's LoadMDLFile hook
 * (sa2-mod-loader dllmain.cpp, LoadMDLFile_ri) loads every *.sa2mdl in the folder and hands the
 * game each index's labelled object. The game here has no loader, so the folder is turned back
 * into the file the game itself reads: a PRS-compressed, big-endian MDL (SA Tools
 * SplitTools/SplitMDL.cs, SA2MDL.Build), a table of (index, offset) pairs ended by -1, then the
 * models.
 *
 * Each SA2MDL (version 3, the only one the loader reads) is little-endian with its pointers as
 * offsets into the file and its model data from 0x10 to the metadata; the label chunk names
 * offsets. The data is copied as it is and turned big-endian in place, walking what the loader
 * fixes up: the objects from the root (NJS_OBJECT, 13 words), their chunk models (NJS_CNK_MODEL,
 * 6 words), the vertex chunk lists (32-bit words) and the poly chunk lists (16-bit words), as
 * SA Tools writes them for the game. Pointers move to the copy's place in the new file.
 */
final class ModelFolder {
    private ModelFolder() {}

    private static final long SA2MDL = 0x4C444D324153L;
    private static final int LABEL_CHUNK = 0x4C42414C, END_CHUNK = 0x444E45;
    private static final int HEADER = 0x10, OBJECT_SIZE = 0x34, CHUNK_MODEL_SIZE = 0x18;

    /** The folder's ini (named as the folder), or null. */
    static File ini(File dir) {
        String want = dir.getName() + ".ini";
        String[] names = dir.list();
        if (names != null) for (String n : names) if (n.equalsIgnoreCase(want)) return new File(dir, n);
        return null;
    }

    static List<File> models(File dir) {
        List<File> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) if (k.isFile() && k.getName().toLowerCase(Locale.ROOT).endsWith(".sa2mdl")) out.add(k);
        }
        // The loader takes the files in the order Windows lists them: by name, case-blind.
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** A model folder: <name>/<name>.ini beside at least one SA2MDL file. */
    static boolean is(File dir) {
        return dir.isDirectory() && ini(dir) != null && !models(dir).isEmpty();
    }

    /** What went into a build, for the signature that tells when to build again. */
    static String signature(File dir) {
        StringBuilder b = new StringBuilder("v1");
        File ini = ini(dir);
        if (ini != null) b.append('|').append(ini.getName()).append(':').append(ini.length()).append(':').append(ini.lastModified());
        for (File f : models(dir)) b.append('|').append(f.getName()).append(':').append(f.length()).append(':').append(f.lastModified());
        return b.toString();
    }

    static final class Result {
        byte[] mdl;                                        // uncompressed, big-endian
        int indices, placed, files, objects, chunkModels;
        final List<String> notes = new ArrayList<>();
    }

    /** One SA2MDL file read for the build. */
    private static final class Source {
        String name;
        byte[] b;
        int root, end;                                     // the model data is [HEADER, end)
        final Map<String, Integer> labels = new HashMap<>();
        int base;                                          // where its data lands in the MDL
    }

    static Result build(File dir) throws IOException {
        Result r = new Result();
        File ini = ini(dir);
        if (ini == null) throw new IOException("no " + dir.getName() + ".ini in " + dir.getName() + "/");
        // The ini's top group: index=label, as the loader reads it (other keys, BigEndian and the
        // like, name no label and are passed over).
        List<Integer> order = new ArrayList<>();
        List<String> labelOf = new ArrayList<>();
        Map<String, String> top = ModManager.readIni(ini).get("");
        if (top != null) {
            for (Map.Entry<String, String> e : top.entrySet()) {
                int idx;
                try {
                    idx = Integer.parseInt(e.getKey().trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                order.add(idx);
                labelOf.add(e.getValue().trim());
            }
        }
        r.indices = order.size();

        List<Source> sources = new ArrayList<>();
        for (File f : models(dir)) {
            Source s = read(f, r.notes);
            if (s != null) sources.add(s);
        }
        r.files = sources.size();
        if (sources.isEmpty()) throw new IOException("no SA2MDL (version 3) model in " + dir.getName() + "/");

        // Each index's object: the first file (in the loader's order) holding its label.
        List<int[]> entries = new ArrayList<>();          // {index, source, offset in source}
        for (int i = 0; i < order.size(); i++) {
            String label = labelOf.get(i);
            int hit = -1;
            for (int s = 0; s < sources.size() && hit < 0; s++) {
                Integer at = sources.get(s).labels.get(label);
                if (at != null) {
                    entries.add(new int[] {order.get(i), s, at});
                    hit = s;
                }
            }
            if (hit < 0) r.notes.add("index " + order.get(i) + ": no model has the label " + label);
        }
        r.placed = entries.size();

        // Layout: the table, then each file's model data at a 16-byte boundary.
        int size = align16((entries.size() + 1) * 8);
        for (Source s : sources) {
            s.base = size;
            size = align16(size + (s.end - HEADER));
        }
        byte[] out = new byte[size];
        for (Source s : sources) System.arraycopy(s.b, HEADER, out, s.base, s.end - HEADER);

        BitSet swapped = new BitSet(size);
        for (int si = 0; si < sources.size(); si++) {
            Source s = sources.get(si);
            Walk w = new Walk(s, out, swapped);
            w.object(s.root);
            // An index whose label the root's tree does not reach still gets a whole object.
            for (int[] e : entries) if (e[1] == si) w.object(e[2]);
            r.objects += w.objects.size();
            r.chunkModels += w.models.size();
        }

        int at = 0;
        for (int[] e : entries) {
            putBe32(out, at, e[0]);
            putBe32(out, at + 4, sources.get(e[1]).base + e[2] - HEADER);
            at += 8;
        }
        putBe32(out, at, -1);
        putBe32(out, at + 4, 0);
        r.mdl = out;
        return r;
    }

    private static int align16(int v) {
        return (v + 15) & ~15;
    }

    private static Source read(File f, List<String> notes) throws IOException {
        long len = f.length();
        if (len < HEADER || len > (64L << 20)) {
            notes.add(f.getName() + ": not a model file this size (" + len + " bytes), skipped");
            return null;
        }
        byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) throw new IOException(f.getName() + ": read short");
                n += k;
            }
        }
        long magic = le64(b, 0);
        int version = (int) (magic >>> 56);
        if ((magic & 0xFFFFFFFFFFFFFFL) != SA2MDL) {
            notes.add(f.getName() + ": not an SA2MDL file, skipped");
            return null;
        }
        if (version != 3) {
            notes.add(f.getName() + ": SA2MDL version " + version + " (the mod loader reads version 3 only), skipped");
            return null;
        }
        Source s = new Source();
        s.name = f.getName();
        s.b = b;
        s.root = le32(b, 8);
        s.end = le32(b, 12);
        if (s.end < HEADER || s.end > b.length) s.end = b.length;
        if (s.root < HEADER || s.root + OBJECT_SIZE > s.end) throw new IOException(f.getName() + ": the root object is outside the file");
        // Metadata chunks: type, size, data; the label chunk is (offset, string offset in the
        // chunk) pairs ended by two -1 words.
        int p = le32(b, 12);
        while (p >= HEADER && p + 8 <= b.length) {
            int type = le32(b, p), csize = le32(b, p + 4);
            if (type == END_CHUNK || csize < 0) break;
            int base = p + 8;
            if (base + csize > b.length) break;
            if (type == LABEL_CHUNK) {
                for (int q = base; q + 8 <= base + csize; q += 8) {
                    int data = le32(b, q), str = le32(b, q + 4);
                    if (data == -1 && str == -1) break;
                    int sp = base + str, e = sp;
                    if (sp < base || sp >= b.length) continue;
                    while (e < b.length && b[e] != 0) e++;
                    s.labels.put(new String(b, sp, e - sp, StandardCharsets.ISO_8859_1), data);
                }
            }
            p = base + csize;
        }
        return s;
    }

    /** One file's structures turned big-endian in the MDL, each once. */
    private static final class Walk {
        final Source s;
        final byte[] out;
        final BitSet swapped;
        final Set<Integer> objects = new HashSet<>(), models = new HashSet<>(), vlists = new HashSet<>(),
                plists = new HashSet<>();

        Walk(Source s, byte[] out, BitSet swapped) {
            this.s = s;
            this.out = out;
            this.swapped = swapped;
        }

        int at(int fileOffset) {
            return s.base + fileOffset - HEADER;
        }

        void check(int fileOffset, int n, String what) throws IOException {
            if (fileOffset < HEADER || n < 0 || (long) fileOffset + n > s.end)
                throw new IOException(s.name + ": " + what + " at 0x" + Integer.toHexString(fileOffset) + " runs outside the model data");
        }

        void swap(int fileOffset, int n, int unit, String what) throws IOException {
            check(fileOffset, n, what);
            int o = at(fileOffset);
            if (swapped.get(o, o + n).cardinality() != 0)
                throw new IOException(s.name + ": " + what + " at 0x" + Integer.toHexString(fileOffset) + " overlaps other model data");
            swapped.set(o, o + n);
            for (int p = o; p < o + n; p += unit) {
                for (int i = 0, j = unit - 1; i < j; i++, j--) {
                    byte t = out[p + i];
                    out[p + i] = out[p + j];
                    out[p + j] = t;
                }
            }
        }

        /** The pointer word at `word` (a file offset, already big-endian in out) moved to the MDL. */
        void relocate(int word, int target) {
            putBe32(out, at(word), at(target));
        }

        void object(int root) throws IOException {
            ArrayDeque<Integer> todo = new ArrayDeque<>();
            todo.push(root);
            while (!todo.isEmpty()) {
                int o = todo.pop();
                if (!objects.add(o)) continue;
                check(o, OBJECT_SIZE, "an object");
                int model = le32(s.b, o + 4), child = le32(s.b, o + 0x2C), sibling = le32(s.b, o + 0x30);
                swap(o, OBJECT_SIZE, 4, "an object");
                if (model != 0) {
                    relocate(o + 4, model);
                    chunkModel(model);
                }
                if (child != 0) {
                    relocate(o + 0x2C, child);
                    todo.push(child);
                }
                if (sibling != 0) {
                    relocate(o + 0x30, sibling);
                    todo.push(sibling);
                }
            }
        }

        void chunkModel(int m) throws IOException {
            if (!models.add(m)) return;
            check(m, CHUNK_MODEL_SIZE, "a chunk model");
            int vlist = le32(s.b, m), plist = le32(s.b, m + 4);
            swap(m, CHUNK_MODEL_SIZE, 4, "a chunk model");
            if (vlist != 0) {
                relocate(m, vlist);
                if (vlists.add(vlist)) swap(vlist, vertexListBytes(vlist), 4, "a vertex list");
            }
            if (plist != 0) {
                relocate(m + 4, plist);
                if (plists.add(plist)) swap(plist, polyListBytes(plist), 2, "a poly list");
            }
        }

        /** Vertex chunks: a header word (type, flags, size in words after it) up to the end chunk. */
        int vertexListBytes(int start) throws IOException {
            int p = start;
            while (true) {
                check(p, 4, "a vertex chunk");
                int h = le32(s.b, p);
                if ((h & 0xFF) == 0xFF) return p + 4 - start;
                p += 4 + (h >>> 16) * 4;
            }
        }

        /** Poly chunks: bits (one word), tiny (two), the rest a header and a size in words. */
        int polyListBytes(int start) throws IOException {
            int p = start;
            while (true) {
                check(p, 2, "a poly chunk");
                int h = le16(s.b, p), type = h & 0xFF;
                if (type == 0xFF) return p + 2 - start;
                if (type < 8) {
                    p += 2;
                } else if (type < 16) {
                    p += 4;
                } else {
                    check(p, 4, "a poly chunk");
                    p += 4 + le16(s.b, p + 2) * 2;
                }
            }
        }
    }

    // ------------------------------------------------------------ bytes

    static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    static long le64(byte[] b, int o) {
        return (le32(b, o) & 0xFFFFFFFFL) | ((long) le32(b, o + 4) << 32);
    }

    static void putBe32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    /** Builds the folder and writes `<name>.prs` (compressed) to `out`. */
    static Result buildPrs(File dir, File out) throws IOException {
        Result r = build(dir);
        OpenSa2.writeBytes(out, Prs.compress(r.mdl));
        return r;
    }

    static String describe(Result r) {
        return r.placed + " of " + r.indices + " models from " + r.files + " file(s)"
                + (r.notes.isEmpty() ? "" : "; " + String.join("; ", r.notes.subList(0, Math.min(3, r.notes.size())))
                + (r.notes.size() > 3 ? " (" + (r.notes.size() - 3) + " more)" : ""));
    }
}
