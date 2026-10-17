package com.sa2recomp.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * LZMA and LZMA2 decoding, the codecs 7z archives use (GameBanana's SA2 mods are .7z). This is
 * the LZMA SDK's reference decoder (Igor Pavlov, public domain), adapted to decode a stream to a
 * stream through a sliding window, so a 7z folder's output is written straight to a file and
 * never held whole in memory.
 */
final class Lzma {
    private Lzma() {}

    /** The decoded bytes, kept dictSize back for matches and flushed to the output as they pass. */
    static final class Window {
        private byte[] buf;
        private int winSize, pos, streamPos;
        private final OutputStream out;
        long written;

        Window(int dictSize, OutputStream out) {
            this.winSize = Math.max(dictSize, 1 << 12);
            this.buf = new byte[winSize];
            this.out = out;
        }

        void putByte(byte b) throws IOException {
            buf[pos++] = b;
            if (pos == winSize) flush();
        }

        byte getByte(int dist) {
            int i = pos - dist - 1;
            if (i < 0) i += winSize;
            return buf[i];
        }

        void copyMatch(int dist, int len) throws IOException {
            for (; len > 0; len--) putByte(getByte(dist));
        }

        boolean checkDistance(int dist) {
            return dist <= pos || streamPos != 0 || pos == winSize ? dist < winSize : dist < pos;
        }

        boolean isEmpty() {
            return pos == 0 && streamPos == 0;
        }

        void flush() throws IOException {
            int n = pos - streamPos;
            if (n <= 0) return;
            out.write(buf, streamPos, n);
            written += n;
            if (pos == winSize) pos = 0;
            streamPos = pos;
        }
    }

    static final class RangeDecoder {
        static final int TOP = 1 << 24;
        final InputStream in;
        int code, range;

        RangeDecoder(InputStream in) throws IOException {
            this.in = in;
            range = -1;
            in.read();                 // the first byte is 0
            for (int i = 0; i < 4; i++) code = (code << 8) | (in.read() & 0xFF);
        }

        int decodeDirectBits(int numTotalBits) throws IOException {
            int result = 0;
            for (int i = numTotalBits; i != 0; i--) {
                range >>>= 1;
                int t = (code - range) >>> 31;
                code -= range & (t - 1);
                result = (result << 1) | (1 - t);
                if ((range & 0xFF000000) == 0) {
                    code = (code << 8) | (in.read() & 0xFF);
                    range <<= 8;
                }
            }
            return result;
        }

        int decodeBit(short[] probs, int index) throws IOException {
            int prob = probs[index];
            int newBound = (range >>> 11) * prob;
            if ((code ^ 0x80000000) < (newBound ^ 0x80000000)) {
                range = newBound;
                probs[index] = (short) (prob + ((2048 - prob) >>> 5));
                if ((range & 0xFF000000) == 0) {
                    code = (code << 8) | (in.read() & 0xFF);
                    range <<= 8;
                }
                return 0;
            }
            range -= newBound;
            code -= newBound;
            probs[index] = (short) (prob - (prob >>> 5));
            if ((range & 0xFF000000) == 0) {
                code = (code << 8) | (in.read() & 0xFF);
                range <<= 8;
            }
            return 1;
        }
    }

    static void initProbs(short[] p) {
        java.util.Arrays.fill(p, (short) 1024);
    }

    static int bitTreeDecode(short[] probs, int off, int numBits, RangeDecoder rc) throws IOException {
        int m = 1;
        for (int i = 0; i < numBits; i++) m = (m << 1) + rc.decodeBit(probs, off + m);
        return m - (1 << numBits);
    }

    static int bitTreeReverseDecode(short[] probs, int off, int numBits, RangeDecoder rc) throws IOException {
        int m = 1, sym = 0;
        for (int i = 0; i < numBits; i++) {
            int bit = rc.decodeBit(probs, off + m);
            m = (m << 1) + bit;
            sym |= bit << i;
        }
        return sym;
    }

    /** The length coder (match length), shared shape for lengths and rep-lengths. */
    static final class LenDecoder {
        final short[] choice = new short[2];
        final short[] low = new short[16 * 8];     // 16 pos states * 8
        final short[] mid = new short[16 * 8];
        final short[] high = new short[256];

        void reset() {
            initProbs(choice);
            initProbs(low);
            initProbs(mid);
            initProbs(high);
        }

        int decode(RangeDecoder rc, int posState) throws IOException {
            if (rc.decodeBit(choice, 0) == 0) return bitTreeDecode(low, posState * 8, 3, rc);
            if (rc.decodeBit(choice, 1) == 0) return 8 + bitTreeDecode(mid, posState * 8, 3, rc);
            return 16 + bitTreeDecode(high, 0, 8, rc);
        }
    }

    /** One LZMA stream's state: dictionary window, probabilities, lc/lp/pb, into a shared Window. */
    static final class State {
        int lc, lp, pb;
        short[] litProbs;
        final short[] isMatch = new short[12 << 4];
        final short[] isRep = new short[12];
        final short[] isRepG0 = new short[12];
        final short[] isRepG1 = new short[12];
        final short[] isRepG2 = new short[12];
        final short[] isRep0Long = new short[12 << 4];
        final short[] posSlot = new short[4 * 64];
        final short[] posDecoders = new short[115];
        final short[] align = new short[16];
        final LenDecoder lenDec = new LenDecoder();
        final LenDecoder repLenDec = new LenDecoder();
        int state;
        int rep0, rep1, rep2, rep3;

        void setProps(int lclppb) {
            int d = lclppb;
            pb = d / 45; d %= 45;
            lp = d / 9; lc = d % 9;
            litProbs = new short[0x300 << (lc + lp)];
        }

        void resetState() {
            state = 0;
            rep0 = rep1 = rep2 = rep3 = 0;
            initProbs(litProbs);
            initProbs(isMatch);
            initProbs(isRep);
            initProbs(isRepG0);
            initProbs(isRepG1);
            initProbs(isRepG2);
            initProbs(isRep0Long);
            initProbs(posSlot);
            initProbs(posDecoders);
            initProbs(align);
            lenDec.reset();
            repLenDec.reset();
        }

        void decodeLiteral(Window w, RangeDecoder rc, long pos) throws IOException {
            int prevByte = w.isEmpty() ? 0 : w.getByte(0) & 0xFF;
            int litState = ((int) pos & ((1 << lp) - 1)) << lc;
            litState += (prevByte >>> (8 - lc));
            int probOff = 0x300 * litState;
            int symbol = 1;
            if (state >= 7) {
                int matchByte = w.getByte(rep0) & 0xFF;
                do {
                    int matchBit = (matchByte >>> 7) & 1;
                    matchByte <<= 1;
                    int bit = rc.decodeBit(litProbs, probOff + ((1 + matchBit) << 8) + symbol);
                    symbol = (symbol << 1) | bit;
                    if (matchBit != bit) break;
                } while (symbol < 0x100);
            }
            while (symbol < 0x100) symbol = (symbol << 1) | rc.decodeBit(litProbs, probOff + symbol);
            w.putByte((byte) symbol);
        }
    }

    // The position of the next byte, needed for the literal/pos state low bits.
    // Kept outside the window so the window stays a pure ring buffer.
    static long totalPos;

    /** Decodes one LZMA2 stream (control-byte chunks) from `in` to `out`. */
    static void decodeLzma2(InputStream in, OutputStream out, int dictSize) throws IOException {
        Window w = new Window(dictSize, out);
        State st = new State();
        totalPos = 0;
        boolean needInitProps = true, needInitState = true;
        while (true) {
            int control = in.read();
            if (control < 0 || control == 0) break;
            if (control >= 0x80) {
                int unpackSize = ((control & 0x1F) << 16) + (in.read() << 8) + in.read() + 1;
                int packSize = (in.read() << 8) + in.read() + 1;
                int mode = (control >> 5) & 3;       // 0 copy-reset? bits: see below
                if (mode >= 2) {                      // new props
                    int props = in.read();
                    st.setProps(props);
                    needInitProps = false;
                } else if (needInitProps) {
                    throw new IOException("LZMA2: props before any were given");
                }
                if (mode >= 1) {                      // reset state
                    st.resetState();
                    needInitState = false;
                } else if (needInitState) {
                    throw new IOException("LZMA2: state used before reset");
                }
                // mode==3 also resets the dictionary: the position counter the pos/lit states use
                // restarts, and an encoder makes no match reaching past it, so the ring window needs
                // no explicit clear.
                if (mode == 3) totalPos = 0;
                decodeChunk(in, w, st, unpackSize, packSize);
            } else if (control <= 2) {
                // Uncompressed chunk: control 1 resets the dictionary, 2 does not.
                int size = (in.read() << 8) + in.read() + 1;
                if (control == 1) totalPos = 0;
                for (int i = 0; i < size; i++) {
                    int b = in.read();
                    if (b < 0) throw new IOException("LZMA2: short uncompressed chunk");
                    w.putByte((byte) b);
                    totalPos++;
                }
                needInitState = true;   // an LZMA chunk after an uncompressed one must reset state
            } else {
                throw new IOException("LZMA2: bad control byte 0x" + Integer.toHexString(control));
            }
        }
        w.flush();
    }

    private static void decodeChunk(InputStream in, Window w, State st, int unpackSize, int packSize)
            throws IOException {
        byte[] packed = new byte[packSize];
        int got = 0;
        while (got < packSize) {
            int k = in.read(packed, got, packSize - got);
            if (k < 0) throw new IOException("LZMA2: short compressed chunk");
            got += k;
        }
        java.io.ByteArrayInputStream bs = new java.io.ByteArrayInputStream(packed);
        RangeDecoder rc = new RangeDecoder(bs);
        decodeToSize(w, st, rc, unpackSize);
    }

    /** Plain LZMA (7z coder 030101): props byte, 4-byte dict size, then the stream to unpackSize. */
    static void decodeLzma1(InputStream in, OutputStream out, byte[] props, long unpackSize) throws IOException {
        int lclppb = props[0] & 0xFF;
        int dictSize = (props[1] & 0xFF) | ((props[2] & 0xFF) << 8) | ((props[3] & 0xFF) << 16) | ((props[4] & 0xFF) << 24);
        if (dictSize < (1 << 12)) dictSize = 1 << 12;
        Window w = new Window(dictSize, out);
        State st = new State();
        st.setProps(lclppb);
        st.resetState();
        totalPos = 0;
        RangeDecoder rc = new RangeDecoder(in);
        decodeToSize(w, st, rc, (int) unpackSize);
        w.flush();
    }

    private static void decodeToSize(Window w, State st, RangeDecoder rc, long limit) throws IOException {
        long end = totalPos + limit;
        while (totalPos < end) {
            int posState = (int) totalPos & ((1 << st.pb) - 1);
            if (rc.decodeBit(st.isMatch, (st.state << 4) + posState) == 0) {
                st.decodeLiteral(w, rc, totalPos);
                st.state = st.state < 4 ? 0 : st.state < 10 ? st.state - 3 : st.state - 6;
                totalPos++;
                continue;
            }
            int len;
            if (rc.decodeBit(st.isRep, st.state) != 0) {
                if (rc.decodeBit(st.isRepG0, st.state) == 0) {
                    if (rc.decodeBit(st.isRep0Long, (st.state << 4) + posState) == 0) {
                        st.state = st.state < 7 ? 9 : 11;
                        w.putByte(w.getByte(st.rep0));
                        totalPos++;
                        continue;
                    }
                } else {
                    int dist;
                    if (rc.decodeBit(st.isRepG1, st.state) == 0) {
                        dist = st.rep1;
                    } else if (rc.decodeBit(st.isRepG2, st.state) == 0) {
                        dist = st.rep2;
                        st.rep2 = st.rep1;
                    } else {
                        dist = st.rep3;
                        st.rep3 = st.rep2;
                        st.rep2 = st.rep1;
                    }
                    st.rep1 = st.rep0;
                    st.rep0 = dist;
                }
                len = st.repLenDec.decode(rc, posState) + 2;
                st.state = st.state < 7 ? 8 : 11;
            } else {
                st.rep3 = st.rep2;
                st.rep2 = st.rep1;
                st.rep1 = st.rep0;
                len = st.lenDec.decode(rc, posState) + 2;
                st.state = st.state < 7 ? 7 : 10;
                st.rep0 = decodeDistance(st, rc, len - 2);
                if (st.rep0 == 0xFFFFFFFF) break;    // the end marker
            }
            w.copyMatch(st.rep0, len);
            totalPos += len;
        }
    }

    private static int decodeDistance(State st, RangeDecoder rc, int len) throws IOException {
        int lenState = Math.min(len, 3);
        int posSlot = bitTreeDecode(st.posSlot, lenState * 64, 6, rc);
        if (posSlot < 4) return posSlot;
        int numDirect = (posSlot >> 1) - 1;
        int dist = (2 | (posSlot & 1)) << numDirect;
        if (posSlot < 14) {
            dist += bitTreeReverseDecode(st.posDecoders, dist - posSlot - 1, numDirect, rc);
        } else {
            dist += rc.decodeDirectBits(numDirect - 4) << 4;
            dist += bitTreeReverseDecode(st.align, 0, 4, rc);
        }
        return dist;
    }
}
