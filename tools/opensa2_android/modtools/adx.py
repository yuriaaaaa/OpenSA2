import struct, math
import numpy as np
def coefs(rate, cutoff=500):
    z = math.cos(2 * math.pi * cutoff / rate); a = math.sqrt(2) - z; b = math.sqrt(2) - 1
    c = (a - math.sqrt((a + b) * (a - b))) / b
    return int(c * 8192), int(c * c * -4096)
def header(d):
    off = struct.unpack_from('>H', d, 2)[0]
    enc, bs, bits, nch = d[4], d[5], d[6], d[7]
    rate, n = struct.unpack_from('>II', d, 8)
    cut = struct.unpack_from('>H', d, 16)[0]
    return off + 4, bs, nch, rate, n, cut
def decode(d, plus1=False):
    start, bs, nch, rate, n, cut = header(d)
    c1, c2 = coefs(rate, cut)
    out = np.zeros((nch, n), np.int32); h = [[0, 0] for _ in range(nch)]
    p = start; i = 0
    while i < n and p + bs * nch <= len(d):
        for ch in range(nch):
            f = d[p:p + bs]; p += bs
            sc = struct.unpack_from('>H', f, 0)[0]
            if sc & 0x8000: return out[:, :i], rate
            sc += 1 if plus1 else 0
            h1, h2 = h[ch]
            for k in range(32):
                if i + k >= n: break
                byte = f[2 + k // 2]; nib = (byte >> 4) if k % 2 == 0 else (byte & 15)
                if nib >= 8: nib -= 16
                s = nib * sc + ((c1 * h1 + c2 * h2) >> 12)
                s = max(-32768, min(32767, s)); out[ch, i + k] = s; h2, h1 = h1, s
            h[ch] = [h1, h2]
        i += 32
    return out, rate
def encode(x, rate, cutoff=500):
    """x: (nch, n) int16 array -> ADX bytes (enc 3, 18-byte frames, version 4 header, no loop)."""
    nch, n = x.shape
    c1, c2 = coefs(rate, cutoff)
    # as CRI's encoder writes it (and the game's own banks have it): version 4, data at 0x28
    hdr = bytearray(struct.pack('>HHBBBBIIHBB', 0x8000, 0x24, 3, 18, 4, nch, rate, n, cutoff, 4, 0))
    hdr += b'\0' * (0x22 - len(hdr)) + b'(c)CRI'
    body = bytearray(); h = [[0, 0] for _ in range(nch)]
    for i in range(0, n, 32):
        for ch in range(nch):
            blk = x[ch, i:i + 32].astype(np.int64)
            blk = np.concatenate([blk, np.zeros(32 - len(blk), np.int64)])
            best = None
            # first estimate of the scale from an open-loop residual
            h1, h2 = h[ch]; mx = 0
            for k in range(32):
                pr = (c1 * h1 + c2 * h2) >> 12; mx = max(mx, abs(int(blk[k]) - pr)); h2, h1 = h1, int(blk[k])
            s0 = max(1, int(math.ceil(mx / 7)))
            for sc in sorted(set(max(1, min(0x7FFF, int(s0 * f))) for f in (0.6, 0.75, 0.9, 1.0, 1.15, 1.35, 1.7))):
                h1, h2 = h[ch]; err = 0; nibs = []
                for k in range(32):
                    pr = (c1 * h1 + c2 * h2) >> 12
                    q = int(round((int(blk[k]) - pr) / sc)); q = max(-8, min(7, q))
                    s = max(-32768, min(32767, q * sc + pr)); err += (s - int(blk[k])) ** 2
                    nibs.append(q & 15); h2, h1 = h1, s
                if best is None or err < best[0]: best = (err, sc, nibs, [h1, h2])
            _, sc, nibs, hh = best; h[ch] = hh
            body += struct.pack('>H', sc) + bytes((nibs[2 * k] << 4) | nibs[2 * k + 1] for k in range(16))
    body += struct.pack('>HH', 0x8001, 14) + b'\0' * 14     # end frame
    return bytes(hdr) + bytes(body)
