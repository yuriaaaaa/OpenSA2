package com.sa2recomp.app;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A reader for the 7z archives GameBanana's SA2 mods ship as, enough to unpack them on the phone:
 * the LZMA and LZMA2 codecs (Lzma) and the Copy, BCJ x86 and Delta filters, which together cover
 * what p7zip writes for these mods. Each folder (a solid run of files) is decoded once; a plain
 * compressor's output streams to a temporary file, so a large solid archive never sits in memory,
 * and a filtered folder (small, usually a DLL) is filtered whole. BCJ2, AES and the other codecs
 * are refused by name, with the advice to re-save the mod as a .zip.
 */
final class SevenZ {
    private SevenZ() {}

    private static final byte[] SIG = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
    private static final int K_END = 0x00, K_HEADER = 0x01, K_MAIN_STREAMS = 0x04, K_FILES = 0x05,
            K_PACK = 0x06, K_UNPACK = 0x07, K_SUBSTREAMS = 0x08, K_SIZE = 0x09, K_CRC = 0x0A,
            K_FOLDER = 0x0B, K_CODERS_UNPACK_SIZE = 0x0C, K_NUM_UNPACK = 0x0D, K_EMPTY_STREAM = 0x0E,
            K_EMPTY_FILE = 0x0F, K_NAME = 0x11, K_ENCODED_HEADER = 0x17, K_DUMMY = 0x19;
    private static final long MAX_IN_MEMORY_FILTER = 512L << 20;

    static final class Entry {
        String name;
        boolean isDir;
        long size;
        int folder = -1;        // -1 for an empty-stream entry (a directory or empty file)
        long offsetInFolder;
    }

    /** One coder in a folder: its method id and properties, and its stream counts. */
    private static final class Coder {
        byte[] method;
        byte[] props;
        int numIn, numOut;
    }

    private static final class Folder {
        final List<Coder> coders = new ArrayList<>();
        int[] bindIn, bindOut;       // bindIn[k] fed by out-stream bindOut[k]
        int[] packedIndices;         // which in-stream indices the pack streams feed
        long[] unpackSizes;          // per out-stream
        long outSize;                // the folder's output (its unbound out-stream)
        int finalOut;
        List<Long> packSizes = new ArrayList<>();
        long packPos;                // byte offset of this folder's first pack stream in the file
    }

    /** The whole archive's stream layout. */
    private static final class Streams {
        long packPosBase;
        List<Long> packSizes = new ArrayList<>();
        List<Folder> folders = new ArrayList<>();
        List<Long> subSizes = new ArrayList<>();   // every file's size, in folder order
        int[] numUnpackPerFolder;
    }

    interface Listener {
        void line(String s);
    }

    /** Reads 7z numbers, bit vectors and little-endian ints from a byte array. */
    private static final class R {
        final byte[] b;
        int p;

        R(byte[] b) {
            this.b = b;
        }

        int u8() {
            return b[p++] & 0xFF;
        }

        long readNumber() {
            int first = u8();
            long value = 0;
            int mask = 0x80;
            for (int i = 0; i < 8; i++) {
                if ((first & mask) == 0) {
                    value |= ((long) (first & (mask - 1))) << (8 * i);
                    return value;
                }
                value |= ((long) u8()) << (8 * i);
                mask >>>= 1;
            }
            return value;
        }

        int readNum() {
            return (int) readNumber();
        }

        long u64() {
            long v = 0;
            for (int i = 0; i < 8; i++) v |= ((long) u8()) << (8 * i);
            return v;
        }

        int u32() {
            return u8() | (u8() << 8) | (u8() << 16) | (u8() << 24);
        }

        boolean[] bits(int n) {
            boolean[] out = new boolean[n];
            int mask = 0, v = 0;
            for (int i = 0; i < n; i++) {
                if (mask == 0) {
                    v = u8();
                    mask = 0x80;
                }
                out[i] = (v & mask) != 0;
                mask >>>= 1;
            }
            return out;
        }

        boolean[] allOrBits(int n) {
            int all = u8();
            if (all != 0) {
                boolean[] out = new boolean[n];
                java.util.Arrays.fill(out, true);
                return out;
            }
            return bits(n);
        }
    }

    static List<Entry> list(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] sigHeader = new byte[32];
            raf.readFully(sigHeader);
            for (int i = 0; i < SIG.length; i++)
                if (sigHeader[i] != SIG[i]) throw new IOException("not a 7z archive");
            long nextOff = le64(sigHeader, 12), nextSize = le64(sigHeader, 20);
            if (nextSize == 0) return new ArrayList<>();
            if (nextSize > (64L << 20)) throw new IOException("7z: the header is implausibly large");
            byte[] header = new byte[(int) nextSize];
            raf.seek(32 + nextOff);
            raf.readFully(header);
            R r = new R(header);
            int id = r.u8();
            if (id == K_ENCODED_HEADER) {
                header = decodeEncodedHeader(raf, r);
                r = new R(header);
                id = r.u8();
            }
            if (id != K_HEADER) throw new IOException("7z: unexpected header id 0x" + Integer.toHexString(id));
            return parseHeader(r);
        }
    }

    /** Unpacks every file to `outDir`, returning what was written (directories included). */
    static List<Entry> extract(File f, File outDir, Listener log) throws IOException {
        List<Entry> entries = list(f);
        // Group the files that have a stream by folder, in order.
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] sigHeader = new byte[32];
            raf.readFully(sigHeader);
            long nextOff = le64(sigHeader, 12), nextSize = le64(sigHeader, 20);
            byte[] header = new byte[(int) nextSize];
            raf.seek(32 + nextOff);
            raf.readFully(header);
            R r = new R(header);
            int id = r.u8();
            if (id == K_ENCODED_HEADER) {
                header = decodeEncodedHeader(raf, r);
                r = new R(header);
                r.u8();
            }
            Streams s = reparseStreams(header);
            File tmpDir = new File(outDir, ".7z-tmp");
            tmpDir.mkdirs();
            try {
                int folderCount = s.folders.size();
                // Folder pack offsets.
                long pos = 32 + s.packPosBase;
                int packIdx = 0;
                for (Folder fl : s.folders) {
                    fl.packPos = pos;
                    for (int k = 0; k < fl.packSizes.size(); k++) pos += s.packSizes.get(packIdx++);
                }
                // Decode each folder that holds a wanted file, once, to a temp file.
                File[] folderOut = new File[folderCount];
                for (Entry e : entries) {
                    if (e.isDir || e.folder < 0) continue;
                    if (folderOut[e.folder] == null) {
                        folderOut[e.folder] = new File(tmpDir, "folder" + e.folder);
                        decodeFolder(raf, s.folders.get(e.folder), folderOut[e.folder], log);
                    }
                }
                for (Entry e : entries) {
                    File dest = new File(outDir, e.name);
                    if (e.isDir) {
                        dest.mkdirs();
                        continue;
                    }
                    File parent = dest.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (e.folder < 0) {
                        new FileOutputStream(dest).close();   // an empty file
                        continue;
                    }
                    sliceOut(folderOut[e.folder], e.offsetInFolder, e.size, dest);
                }
            } finally {
                OpenSa2.removeTree(tmpDir);
            }
        }
        return entries;
    }

    private static void sliceOut(File folderOut, long off, long size, File dest) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(folderOut, "r");
             OutputStream out = new FileOutputStream(dest)) {
            in.seek(off);
            byte[] buf = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("7z: a file runs past its folder's output");
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    // ------------------------------------------------------------ folder decoding

    private static byte[] decodeEncodedHeader(RandomAccessFile raf, R r) throws IOException {
        // kEncodedHeader is followed directly by a StreamsInfo (no kMainStreamsInfo wrapper).
        Streams s = readStreamsInfo(r);
        if (s.folders.size() != 1) throw new IOException("7z: the encoded header is not one folder");
        Folder fl = s.folders.get(0);
        long pos = 32 + s.packPosBase;
        fl.packPos = pos;
        File tmp = File.createTempFile("sa2-7zhdr", ".bin");
        try {
            decodeFolder(raf, fl, tmp, null);
            long len = tmp.length();
            if (len > (64L << 20)) throw new IOException("7z: the decoded header is too large");
            byte[] out = new byte[(int) len];
            try (InputStream in = new FileInputStream(tmp)) {
                readFully(in, out);
            }
            return out;
        } finally {
            tmp.delete();
        }
    }

    /** Decodes one folder's coder chain to `out`, streaming the compressor stage. */
    private static void decodeFolder(RandomAccessFile raf, Folder fl, File out, Listener log) throws IOException {
        // Only a linear chain (one pack stream, single-in/single-out coders) is handled; this is
        // what p7zip writes for these mods. The order is resolved from the bind pairs: the pack
        // stream feeds one coder, whose output feeds the next, up to the folder's output.
        if (fl.coders.size() > 4) throw unsupported("a folder of " + fl.coders.size() + " coders");
        int[] outToCoder = new int[totalOut(fl)];
        int oi = 0;
        for (int c = 0; c < fl.coders.size(); c++)
            for (int k = 0; k < fl.coders.get(c).numOut; k++) outToCoder[oi++] = c;
        // The order, pack -> final: start at the coder owning the final out-stream and walk back
        // through bind pairs to the coder fed by the pack stream.
        List<Integer> chain = new ArrayList<>();
        int cur = outToCoder[fl.finalOut];
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        while (true) {
            if (!seen.add(cur)) throw unsupported("a folder whose coders form a loop");
            chain.add(0, cur);
            int inIndex = inStreamStart(fl, cur);
            Integer boundOut = boundOutFor(fl, inIndex);
            if (boundOut == null) break;              // fed by the pack stream
            cur = outToCoder[boundOut];
        }
        // The pack stream region. A linear chain has one pack stream feeding its first coder.
        long start = fl.packPos;
        long packLen = fl.packSizes.isEmpty() ? 0 : fl.packSizes.get(0);
        File prev = null;
        for (int idx = 0; idx < chain.size(); idx++) {
            int ci = chain.get(idx);
            Coder coder = fl.coders.get(ci);
            long outSize = fl.unpackSizes[outStreamStart(fl, ci)];
            File stageOut = idx == chain.size() - 1 ? out : File.createTempFile("sa2-7zs", ".bin");
            InputStream src = idx == 0 ? new RegionInputStream(raf, start, packLen) : new FileInputStream(prev);
            try (InputStream in2 = src; OutputStream os = new FileOutputStream(stageOut)) {
                runCoder(coder, in2, os, outSize);
            }
            if (prev != null) prev.delete();
            prev = stageOut == out ? null : stageOut;
        }
    }

    private static void runCoder(Coder coder, InputStream in, OutputStream out, long outSize) throws IOException {
        byte[] m = coder.method;
        if (m.length == 1 && m[0] == 0x00) {
            copy(in, out, outSize);
        } else if (m.length == 1 && m[0] == 0x21) {
            int dictSize = lzma2Dict(coder.props);
            Lzma.decodeLzma2(in, out, dictSize);
        } else if (m.length == 3 && m[0] == 0x03 && m[1] == 0x01 && m[2] == 0x01) {
            Lzma.decodeLzma1(in, out, coder.props, outSize);
        } else if (m.length == 1 && m[0] == 0x03) {
            delta(in, out, outSize, coder.props != null && coder.props.length > 0 ? (coder.props[0] & 0xFF) + 1 : 1);
        } else if (m.length == 4 && m[0] == 0x03 && m[1] == 0x03 && m[2] == 0x01 && m[3] == 0x03) {
            bcjX86(in, out, outSize);
        } else {
            throw unsupported("the codec " + hex(m)
                    + (eq(m, 0x03, 0x03, 0x01, 0x1B) ? " (BCJ2)" : eq(m, 0x06, (byte) 0xF1, 0x07, 0x01) ? " (AES)" : ""));
        }
    }

    private static boolean eq(byte[] m, int... v) {
        if (m.length != v.length) return false;
        for (int i = 0; i < v.length; i++) if ((m[i] & 0xFF) != (v[i] & 0xFF)) return false;
        return true;
    }

    private static IOException unsupported(String what) {
        return new IOException("this 7z uses " + what + ", which the app cannot unpack; "
                + "re-save the mod as a .zip (7-Zip: Add to archive, format zip) and add that");
    }

    private static final int MAX_DICT = 192 << 20;   // the window the phone will allocate at most

    private static int lzma2Dict(byte[] props) {
        if (props == null || props.length < 1) return 1 << 24;
        int bits = props[0] & 0x3F;
        if (bits > 40) return MAX_DICT;
        long dict = bits >= 40 ? 0xFFFFFFFFL : (long) (2 | (bits & 1)) << (bits / 2 + 11);
        return (int) Math.min(dict, MAX_DICT);
    }

    // ------------------------------------------------------------ filters (whole buffer)

    private static void delta(InputStream in, OutputStream out, long size, int distance) throws IOException {
        byte[] state = new byte[distance];
        int pos = 0;
        byte[] buf = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("Delta: short input");
            for (int i = 0; i < n; i++) {
                byte v = (byte) (buf[i] + state[pos]);
                state[pos] = v;
                buf[i] = v;
                pos = pos + 1 == distance ? 0 : pos + 1;
            }
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static boolean test86(byte b) {
        return b == 0 || b == (byte) 0xFF;
    }

    /**
     * The BCJ x86 decoder, a port of the LZMA SDK's x86_Convert (Bra86.c, decoding), over the
     * whole buffer: filtered folders are small (usually a DLL). ip starts at 0.
     */
    private static void bcjX86(InputStream in, OutputStream out, long size) throws IOException {
        if (size > MAX_IN_MEMORY_FILTER) throw unsupported("an x86-filtered stream larger than 512 MB");
        byte[] data = new byte[(int) size];
        readFully(in, data);
        if (data.length >= 5) {
            int end = data.length - 4;
            int mask = 0;
            int pos = 0;
            while (true) {
                int p = pos;
                while (p < end && (data[p] & 0xFE) != 0xE8) p++;
                int d = p - pos;
                pos = p;
                if (p >= end) break;
                if (d > 2) {
                    mask = 0;
                } else {
                    mask >>>= d;
                    if (mask != 0 && (mask > 4 || mask == 3 || test86(data[p + (mask >>> 1) + 1]))) {
                        mask = (mask >>> 1) | 4;
                        pos++;
                        continue;
                    }
                }
                if (test86(data[p + 4])) {
                    int v = (data[p + 1] & 0xFF) | ((data[p + 2] & 0xFF) << 8) | ((data[p + 3] & 0xFF) << 16)
                            | ((data[p + 4] & 0xFF) << 24);
                    int cur = p + 5;                  // ip (0) + 5 + pos
                    pos += 5;
                    v -= cur;
                    if (mask != 0) {
                        int sh = (mask & 6) << 2;
                        if (test86((byte) (v >>> sh))) {
                            v ^= (0x100 << sh) - 1;
                            v -= cur;
                        }
                        mask = 0;
                    }
                    data[p + 1] = (byte) v;
                    data[p + 2] = (byte) (v >>> 8);
                    data[p + 3] = (byte) (v >>> 16);
                    data[p + 4] = (byte) (0 - ((v >>> 24) & 1));
                } else {
                    mask = (mask >>> 1) | 4;
                    pos++;
                }
            }
        }
        out.write(data);
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("Copy: short input");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    // ------------------------------------------------------------ header parsing

    private static List<Entry> parseHeader(R r) throws IOException {
        Streams streams = null;
        List<Entry> entries = new ArrayList<>();
        int id = r.u8();
        if (id == 0x02) {   // archive properties: skipped
            skipProperties(r);
            id = r.u8();
        }
        if (id == 0x03) throw unsupported("additional streams (a rare 7z layout)");
        if (id == K_MAIN_STREAMS) {
            streams = readStreamsInfo(r);
            id = r.u8();
        }
        if (id == K_FILES) {
            entries = readFilesInfo(r, streams);
            id = r.u8();
        }
        return entries;
    }

    private static Streams reparseStreams(byte[] header) throws IOException {
        R r = new R(header);
        r.u8();   // K_HEADER
        int id = r.u8();
        if (id == 0x02) {
            skipProperties(r);
            id = r.u8();
        }
        if (id != K_MAIN_STREAMS) throw new IOException("7z: no main streams info");
        Streams s = readStreamsInfo(r);
        // Re-read files info to set folder/offset on entries is done in list(); here only the
        // stream layout is needed.
        assignFileStreams(r, s);
        return s;
    }

    /** Reads files info after streams, filling each folder's file sizes/offsets by walking subSizes. */
    private static void assignFileStreams(R r, Streams s) {
        // Not strictly needed for decoding folders; folders know their own pack layout.
    }

    private static void skipProperties(R r) {
        while (true) {
            int type = r.u8();
            if (type == K_END) break;
            long size = r.readNumber();
            r.p += size;
        }
    }

    private static Streams readStreamsInfo(R r) throws IOException {
        Streams s = new Streams();
        int id = r.u8();
        if (id == K_PACK) {
            s.packPosBase = r.readNumber();
            int numPack = r.readNum();
            int sub = r.u8();
            if (sub == K_SIZE) {
                for (int i = 0; i < numPack; i++) s.packSizes.add(r.readNumber());
                sub = r.u8();
            }
            while (sub != K_END) {
                if (sub == K_CRC) skipDigests(r, numPack);
                else skipOne(r);
                sub = r.u8();
            }
            id = r.u8();
        }
        if (id == K_UNPACK) {
            readUnpackInfo(r, s);
            id = r.u8();
        }
        if (id == K_SUBSTREAMS) {
            readSubStreamsInfo(r, s);
            id = r.u8();
        } else {
            // One file per folder.
            s.numUnpackPerFolder = new int[s.folders.size()];
            for (int i = 0; i < s.folders.size(); i++) {
                s.numUnpackPerFolder[i] = 1;
                s.subSizes.add(s.folders.get(i).outSize);
            }
        }
        if (id != K_END) throw new IOException("7z: streams info did not end cleanly (0x" + Integer.toHexString(id) + ")");
        // Distribute pack sizes to folders in order.
        int packIdx = 0;
        for (Folder fl : s.folders) {
            int n = numPackStreams(fl);
            for (int k = 0; k < n; k++) fl.packSizes.add(s.packSizes.get(packIdx++));
        }
        return s;
    }

    private static int numPackStreams(Folder fl) {
        // In-streams not bound are fed by pack streams.
        int total = 0;
        for (Coder c : fl.coders) total += c.numIn;
        return total - (fl.bindIn == null ? 0 : fl.bindIn.length);
    }

    private static void readUnpackInfo(R r, Streams s) throws IOException {
        if (r.u8() != K_FOLDER) throw new IOException("7z: unpack info without folders");
        int numFolders = r.readNum();
        int external = r.u8();
        if (external != 0) throw unsupported("folders stored externally");
        for (int i = 0; i < numFolders; i++) s.folders.add(readFolder(r));
        if (r.u8() != K_CODERS_UNPACK_SIZE) throw new IOException("7z: no coders unpack size");
        for (Folder fl : s.folders) {
            int outs = totalOut(fl);
            fl.unpackSizes = new long[outs];
            for (int k = 0; k < outs; k++) fl.unpackSizes[k] = r.readNumber();
            fl.outSize = fl.unpackSizes[fl.finalOut];
        }
        int id = r.u8();
        while (id != K_END) {
            if (id == K_CRC) skipDigests(r, numFolders);
            else skipOne(r);
            id = r.u8();
        }
    }

    private static Folder readFolder(R r) throws IOException {
        Folder fl = new Folder();
        int numCoders = r.readNum();
        int inIndex = 0, outIndex = 0;
        for (int i = 0; i < numCoders; i++) {
            Coder c = new Coder();
            int flags = r.u8();
            int idSize = flags & 0x0F;
            c.method = new byte[idSize];
            for (int k = 0; k < idSize; k++) c.method[k] = (byte) r.u8();
            if ((flags & 0x10) != 0) {
                c.numIn = r.readNum();
                c.numOut = r.readNum();
            } else {
                c.numIn = 1;
                c.numOut = 1;
            }
            if ((flags & 0x20) != 0) {
                int propSize = r.readNum();
                c.props = new byte[propSize];
                for (int k = 0; k < propSize; k++) c.props[k] = (byte) r.u8();
            }
            if ((flags & 0x80) != 0) throw unsupported("an alternative-method folder");
            fl.coders.add(c);
            inIndex += c.numIn;
            outIndex += c.numOut;
        }
        int numBind = outIndex - 1;
        fl.bindIn = new int[numBind];
        fl.bindOut = new int[numBind];
        for (int i = 0; i < numBind; i++) {
            fl.bindIn[i] = r.readNum();
            fl.bindOut[i] = r.readNum();
        }
        int numPacked = inIndex - numBind;
        fl.packedIndices = new int[numPacked];
        if (numPacked == 1) {
            // The one in-stream not bound.
            int idx = -1;
            for (int i = 0; i < inIndex; i++) {
                boolean bound = false;
                for (int b : fl.bindIn) if (b == i) bound = true;
                if (!bound) {
                    idx = i;
                    break;
                }
            }
            fl.packedIndices[0] = idx;
        } else {
            for (int i = 0; i < numPacked; i++) fl.packedIndices[i] = r.readNum();
        }
        // The folder's output: the out-stream not used as a bind pair's output.
        fl.finalOut = 0;
        for (int o = 0; o < outIndex; o++) {
            boolean bound = false;
            for (int b : fl.bindOut) if (b == o) bound = true;
            if (!bound) {
                fl.finalOut = o;
                break;
            }
        }
        return fl;
    }

    private static int totalOut(Folder fl) {
        int n = 0;
        for (Coder c : fl.coders) n += c.numOut;
        return n;
    }

    private static int inStreamStart(Folder fl, int coder) {
        int n = 0;
        for (int i = 0; i < coder; i++) n += fl.coders.get(i).numIn;
        return n;
    }

    private static int outStreamStart(Folder fl, int coder) {
        int n = 0;
        for (int i = 0; i < coder; i++) n += fl.coders.get(i).numOut;
        return n;
    }

    private static Integer boundOutFor(Folder fl, int inIndex) {
        if (fl.bindIn != null)
            for (int i = 0; i < fl.bindIn.length; i++) if (fl.bindIn[i] == inIndex) return fl.bindOut[i];
        return null;
    }

    private static void readSubStreamsInfo(R r, Streams s) throws IOException {
        int numFolders = s.folders.size();
        s.numUnpackPerFolder = new int[numFolders];
        int id = r.u8();
        if (id == K_NUM_UNPACK) {
            for (int i = 0; i < numFolders; i++) s.numUnpackPerFolder[i] = r.readNum();
            id = r.u8();
        } else {
            java.util.Arrays.fill(s.numUnpackPerFolder, 1);
        }
        // Sizes: all but the last in each folder are listed; the last is the remainder.
        for (int f = 0; f < numFolders; f++) {
            int num = s.numUnpackPerFolder[f];
            if (num == 0) continue;
            long sum = 0;
            for (int i = 0; i < num - 1; i++) {
                long sz = (id == K_SIZE) ? r.readNumber() : 0;
                s.subSizes.add(sz);
                sum += sz;
            }
            s.subSizes.add(s.folders.get(f).outSize - sum);
        }
        if (id == K_SIZE) id = r.u8();
        while (id != K_END) {
            if (id == K_CRC) {
                int numDigests = 0;
                for (int f = 0; f < numFolders; f++) numDigests += s.numUnpackPerFolder[f];
                skipDigests(r, numDigests);
            } else {
                skipOne(r);
            }
            id = r.u8();
        }
    }

    private static List<Entry> readFilesInfo(R r, Streams s) throws IOException {
        int numFiles = r.readNum();
        Entry[] entries = new Entry[numFiles];
        for (int i = 0; i < numFiles; i++) entries[i] = new Entry();
        boolean[] emptyStream = new boolean[numFiles];
        boolean[] emptyFile = null;
        while (true) {
            int type = r.u8();
            if (type == K_END) break;
            long size = r.readNumber();
            int end = r.p + (int) size;
            switch (type) {
                case K_EMPTY_STREAM:
                    emptyStream = r.bits(numFiles);
                    break;
                case K_EMPTY_FILE: {
                    int numEmpty = 0;
                    for (boolean b : emptyStream) if (b) numEmpty++;
                    emptyFile = r.bits(numEmpty);
                    break;
                }
                case K_NAME: {
                    int external = r.u8();
                    if (external != 0) throw unsupported("names stored externally");
                    for (int i = 0; i < numFiles; i++) {
                        StringBuilder sb = new StringBuilder();
                        while (true) {
                            int lo = r.u8(), hi = r.u8();
                            int ch = lo | (hi << 8);
                            if (ch == 0) break;
                            sb.append((char) ch);
                        }
                        entries[i].name = sb.toString().replace('\\', '/');
                    }
                    break;
                }
                default:
                    break;
            }
            r.p = end;
        }
        // Map files to streams: non-empty-stream files take folder output in order.
        int emptyIdx = 0;
        int fileInFolder = 0, folder = 0;
        long offset = 0;
        int subIdx = 0;
        // Walk folders and their sub-streams.
        List<Long> subSizes = s == null ? new ArrayList<>() : s.subSizes;
        int[] perFolder = s == null ? new int[0] : s.numUnpackPerFolder;
        int folderCursor = 0, withinFolder = 0;
        long folderOffset = 0;
        for (int i = 0; i < numFiles; i++) {
            Entry e = entries[i];
            if (emptyStream[i]) {
                boolean isFile = emptyFile != null && emptyIdx < emptyFile.length && emptyFile[emptyIdx];
                e.isDir = !isFile;
                e.folder = -1;
                e.size = 0;
                emptyIdx++;
            } else {
                // Advance to a folder that still has sub-streams.
                while (s != null && folderCursor < perFolder.length && withinFolder >= perFolder[folderCursor]) {
                    folderCursor++;
                    withinFolder = 0;
                    folderOffset = 0;
                }
                e.folder = folderCursor;
                e.size = subIdx < subSizes.size() ? subSizes.get(subIdx) : 0;
                e.offsetInFolder = folderOffset;
                folderOffset += e.size;
                withinFolder++;
                subIdx++;
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) out.add(e);
        return out;
    }

    private static void skipDigests(R r, int num) {
        boolean[] defined = r.allOrBits(num);
        for (boolean d : defined) if (d) r.u32();
    }

    private static void skipOne(R r) {
        long size = r.readNumber();
        r.p += size;
    }

    // ------------------------------------------------------------ small helpers

    private static long le64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (b[o + i] & 0xFF)) << (8 * i);
        return v;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int k = in.read(buf, n, buf.length - n);
            if (k < 0) throw new IOException("7z: unexpected end of stream");
            n += k;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** A bounded view of the archive file, from `start` for `len` bytes. */
    private static final class RegionInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long left;

        RegionInputStream(RandomAccessFile raf, long start, long len) throws IOException {
            this.raf = raf;
            this.left = len;
            raf.seek(start);
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = raf.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = raf.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
