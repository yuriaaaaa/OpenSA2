package com.sa2recomp.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Very large mod textures, made lighter to load. Some character mods ship uncompressed 32-bit
 * DDS textures of 2048x2048 without mipmaps (16 MB each; Sonikko's sonictex.pak is 75 MB): the
 * game uploads them and the runtime builds every smaller level on the CPU while the game waits,
 * which is the slow stretch after a save loads. Such a texture is halved until it is at most
 * MAX pixels on a side, and given its full mipmap chain (box filter), so it loads as a quarter of
 * the data with nothing left to build. Compressed (DXT) and already-small textures are kept.
 */
final class TexSlim {
    private TexSlim() {}

    static final int MAX = 1024;

    /** The DDS made smaller with mipmaps, or null when it is left as it is. */
    static byte[] slim(byte[] d) {
        if (d.length < 128 || d[0] != 'D' || d[1] != 'D' || d[2] != 'S' || d[3] != ' ') return null;
        int h = le32(d, 12), w = le32(d, 16), mips = le32(d, 28);
        int pfFlags = le32(d, 80), fourcc = le32(d, 84), bits = le32(d, 88);
        if ((pfFlags & 0x4) != 0 || fourcc != 0 || bits != 32) return null;   // compressed or not 32-bit
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null;
        if ((long) w * h * 4 + 128 > d.length) return null;
        if (Math.max(w, h) <= MAX && mips > 1) return null;
        if (Math.max(w, h) <= 256) return null;    // small: not worth it
        byte[] px = new byte[w * h * 4];
        System.arraycopy(d, 128, px, 0, px.length);
        while (Math.max(w, h) > MAX) {
            px = half(px, w, h);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        List<byte[]> levels = new ArrayList<>();
        levels.add(px);
        int lw = w, lh = h;
        while (lw > 1 || lh > 1) {
            px = half(px, lw, lh);
            lw = Math.max(1, lw / 2);
            lh = Math.max(1, lh / 2);
            levels.add(px);
        }
        int total = 128;
        for (byte[] l : levels) total += l.length;
        byte[] out = new byte[total];
        System.arraycopy(d, 0, out, 0, 128);
        put32(out, 8, le32(d, 8) | 0x1 | 0x2 | 0x4 | 0x8 | 0x1000 | 0x20000);   // caps, size, pitch, format, mipcount
        put32(out, 12, h);
        put32(out, 16, w);
        put32(out, 20, w * 4);
        put32(out, 28, levels.size());
        put32(out, 108, le32(d, 108) | 0x1000 | 0x8 | 0x400000);                 // texture, complex, mipmap
        int at = 128;
        for (byte[] l : levels) {
            System.arraycopy(l, 0, out, at, l.length);
            at += l.length;
        }
        return out;
    }

    /** A 32-bit image at half size (box filter; an odd edge keeps its last row or column). */
    private static byte[] half(byte[] px, int w, int h) {
        int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
        byte[] o = new byte[nw * nh * 4];
        for (int y = 0; y < nh; y++) {
            int y0 = Math.min(h - 1, y * 2), y1 = Math.min(h - 1, y * 2 + 1);
            for (int x = 0; x < nw; x++) {
                int x0 = Math.min(w - 1, x * 2), x1 = Math.min(w - 1, x * 2 + 1);
                int a = (y0 * w + x0) * 4, b = (y0 * w + x1) * 4, c = (y1 * w + x0) * 4, e = (y1 * w + x1) * 4;
                int q = (y * nw + x) * 4;
                for (int k = 0; k < 4; k++)
                    o[q + k] = (byte) (((px[a + k] & 0xFF) + (px[b + k] & 0xFF) + (px[c + k] & 0xFF) + (px[e + k] & 0xFF) + 2) >> 2);
            }
        }
        return o;
    }

    /**
     * A PAK with its large textures slimmed (and their .inf rows' sizes to match) written to out;
     * returns how many were, 0 (nothing written) when none needed it.
     */
    static int slimPak(File in, File out) throws IOException {
        List<Object[]> files = TexturePack.pakFiles(in);
        int changed = 0, infAt = -1;
        List<String> renamed = new ArrayList<>();
        List<int[]> sizes = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String n = ((String) files.get(i)[0]).toLowerCase(Locale.ROOT);
            if (n.endsWith(".inf")) {
                infAt = i;
                continue;
            }
            byte[] s = slim((byte[]) files.get(i)[1]);
            if (s == null) continue;
            files.get(i)[1] = s;
            String tn = n.substring(n.lastIndexOf('\\') + 1);
            if (tn.endsWith(".dds")) tn = tn.substring(0, tn.length() - 4);
            renamed.add(tn);
            sizes.add(new int[] {le32(s, 16), le32(s, 12)});
            changed++;
        }
        if (changed == 0) return 0;
        if (infAt >= 0) {
            byte[] inf = ((byte[]) files.get(infAt)[1]).clone();
            for (int o = 0; o + 60 <= inf.length; o += 60) {
                int e = o;
                while (e < o + 28 && inf[e] != 0) e++;
                String tn = new String(inf, o, e - o, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                int k = renamed.indexOf(tn);
                if (k < 0) continue;
                put32(inf, o + 44, sizes.get(k)[0]);
                put32(inf, o + 48, sizes.get(k)[1]);
            }
            files.get(infAt)[1] = inf;
        }
        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        for (Object[] f : files) {
            names.add((String) f[0]);
            datas.add((byte[]) f[1]);
        }
        TexturePack.writePakFile(names, datas, out);
        return changed;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }
}
