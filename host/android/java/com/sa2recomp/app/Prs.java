package com.sa2recomp.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * PRS, SEGA's LZ77 variant the game's .prs files use. Control bits come LSB first from control
 * bytes interleaved with the data: 1 is a literal byte; 00 then two bits (high first) and an
 * offset byte is a 2 to 5 byte copy from up to 256 back; 01 then a little-endian word is a copy
 * from up to 8192 back, of (word & 7) + 2 bytes, or, when those bits are 0, a following byte + 1
 * bytes. A 01 with the word 0 ends the stream.
 *
 * The compressor is greedy over 3-byte hash chains (2-byte copies within 256 back besides); the
 * game only has to read what it writes.
 */
final class Prs {
    private Prs() {}

    private static final int LONG_WINDOW = 8192, SHORT_WINDOW = 256, MAX_COPY = 256, CHAIN_DEPTH = 96;

    /** The writer's control byte and data, in the order the reader takes them. */
    private static final class Out {
        byte[] buf = new byte[1 << 16];
        int len, ctrlAt = -1, bit = 8;

        void need(int n) {
            if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }

        void bit(int b) {
            if (bit == 8) {
                need(1);
                ctrlAt = len;
                buf[len++] = 0;
                bit = 0;
            }
            if (b != 0) buf[ctrlAt] |= (byte) (1 << bit);
            bit++;
        }

        void put(int b) {
            need(1);
            buf[len++] = (byte) b;
        }
    }

    static byte[] compress(byte[] in) {
        final int n = in.length;
        Out o = new Out();
        int[] head = new int[1 << 16];
        Arrays.fill(head, -1);
        int[] prev = new int[Math.max(1, n)];
        int pos = 0, hashed = 0;
        while (pos < n) {
            // Hash every position before pos into the chains.
            while (hashed < pos && hashed + 2 < n) {
                int h = hash(in, hashed);
                prev[hashed] = head[h];
                head[h] = hashed;
                hashed++;
            }
            int bestLen = 0, bestOff = 0;
            int max = Math.min(MAX_COPY, n - pos);
            if (max >= 3) {
                int cand = head[hash(in, pos)];
                int depth = 0;
                // 8191 back at most: 8192 would encode as offset bits 0, which with the long length
                // form is the end marker.
                while (cand >= 0 && pos - cand < LONG_WINDOW && depth++ < CHAIN_DEPTH) {
                    if (in[cand] == in[pos] && in[cand + 1] == in[pos + 1] && in[cand + 2] == in[pos + 2]) {
                        int l = 3;
                        while (l < max && in[cand + l] == in[pos + l]) l++;
                        // Prefer the nearer copy at equal length: it may fit the short form.
                        if (l > bestLen) {
                            bestLen = l;
                            bestOff = pos - cand;
                            if (l == max) break;
                        }
                    }
                    cand = prev[cand];
                }
            }
            if (bestLen < 3 && max >= 2) {
                // A 2-byte copy pays (12 bits for 16) only in the short form.
                for (int back = 1; back <= SHORT_WINDOW && back <= pos; back++) {
                    if (in[pos - back] == in[pos] && in[pos - back + 1] == in[pos + 1]) {
                        bestLen = 2;
                        bestOff = back;
                        break;
                    }
                }
            }
            if (bestLen >= 2 && bestLen <= 5 && bestOff <= SHORT_WINDOW) {
                int s = bestLen - 2;
                o.bit(0);
                o.bit(0);
                o.bit((s >> 1) & 1);
                o.bit(s & 1);
                o.put(SHORT_WINDOW - bestOff);
                pos += bestLen;
            } else if (bestLen >= 3) {
                int off = (LONG_WINDOW - bestOff) & 0x1FFF;    // the 13-bit two's complement offset
                o.bit(0);
                o.bit(1);
                if (bestLen <= 9) {
                    int w = (off << 3) | (bestLen - 2);
                    o.put(w & 0xFF);
                    o.put(w >>> 8);
                } else {
                    int w = off << 3;
                    o.put(w & 0xFF);
                    o.put(w >>> 8);
                    o.put(bestLen - 1);
                }
                pos += bestLen;
            } else {
                o.bit(1);
                o.put(in[pos] & 0xFF);
                pos++;
            }
        }
        o.bit(0);
        o.bit(1);
        o.put(0);
        o.put(0);
        return Arrays.copyOf(o.buf, o.len);
    }

    private static int hash(byte[] b, int p) {
        int v = ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
        return (v * 0x9E3779B1) >>> 16;
    }

    static byte[] decompress(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 4);
        byte[] o = new byte[Math.max(1024, in.length * 4)];
        int olen = 0, pos = 0, ctrl = 0, bits = 0;
        try {
            while (true) {
                if (bits == 0) { ctrl = in[pos++] & 0xFF; bits = 8; }
                int b = ctrl & 1; ctrl >>= 1; bits--;
                if (b == 1) {
                    if (olen == o.length) o = Arrays.copyOf(o, o.length * 2);
                    o[olen++] = in[pos++];
                    continue;
                }
                if (bits == 0) { ctrl = in[pos++] & 0xFF; bits = 8; }
                b = ctrl & 1; ctrl >>= 1; bits--;
                int size, off;
                if (b == 1) {
                    int w = (in[pos] & 0xFF) | ((in[pos + 1] & 0xFF) << 8);
                    pos += 2;
                    if (w == 0) break;
                    off = (w >>> 3) - LONG_WINDOW;
                    size = w & 7;
                    if (size == 0) size = (in[pos++] & 0xFF) + 1;
                    else size += 2;
                } else {
                    if (bits == 0) { ctrl = in[pos++] & 0xFF; bits = 8; }
                    int hi = ctrl & 1; ctrl >>= 1; bits--;
                    if (bits == 0) { ctrl = in[pos++] & 0xFF; bits = 8; }
                    int lo = ctrl & 1; ctrl >>= 1; bits--;
                    size = ((hi << 1) | lo) + 2;
                    off = (in[pos++] & 0xFF) - SHORT_WINDOW;
                }
                int from = olen + off;
                if (from < 0) throw new IOException("PRS: a copy from before the start");
                if (olen + size > o.length) o = Arrays.copyOf(o, Math.max(o.length * 2, olen + size));
                for (int i = 0; i < size; i++) o[olen++] = o[from + i];
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("PRS: the stream ends early");
        }
        out.write(o, 0, olen);
        return out.toByteArray();
    }
}
