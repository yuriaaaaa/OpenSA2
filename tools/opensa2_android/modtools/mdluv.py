import struct, sys, math, json
import numpy as np
b = open(sys.argv[1], 'rb').read()
root = struct.unpack_from('<I', b, 8)[0]
def u32(o): return struct.unpack_from('<I', b, o)[0]
def mat(o):
    fl = u32(o)
    px, py, pz = struct.unpack_from('<3f', b, o + 8)
    ax, ay, az = [a * 2 * math.pi / 65536 for a in struct.unpack_from('<3i', b, o + 20)]
    sx, sy, sz = struct.unpack_from('<3f', b, o + 32)
    T = np.eye(4); 
    if not fl & 1: T[:3, 3] = [px, py, pz]
    def rx(a): c, s = math.cos(a), math.sin(a); return np.array([[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]])
    def ry(a): c, s = math.cos(a), math.sin(a); return np.array([[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]])
    def rz(a): c, s = math.cos(a), math.sin(a); return np.array([[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]])
    R = np.eye(4)
    if not fl & 2:
        R = (ry(ay) @ rx(ax) @ rz(az)) if fl & 0x20 else (rz(az) @ ry(ay) @ rx(ax))
    S = np.eye(4)
    if not fl & 4: S = np.diag([sx, sy, sz, 1])
    return T @ R @ S
buf = {}
ents = []; sid = [0]
tris = []   # (texid, [(pos, uv)]*3)
VSZ = {}
def vlist(p, M):
    while True:
        h = u32(p); t = h & 0xFF; fl = (h >> 8) & 0xFF; size = h >> 16
        if t == 0xFF: return
        if 32 <= t <= 50:
            off, cnt = struct.unpack_from('<HH', b, p + 4)
            per = (size - 1) // cnt if cnt else 0
            q = p + 8
            ws = fl & 3
            for i in range(cnt):
                x, y, z = struct.unpack_from('<3f', b, q)
                if t == 32 or t == 33: pass
                idx, w = off + i, 1.0
                if t in (37, 44):
                    nf = u32(q + 4 * (per - 1)); idx = off + (nf & 0xFFFF); w = (nf >> 16) / 255.0
                v = (M @ np.array([x, y, z, 1.0]))[:3]
                if t in (37, 44):
                    if ws == 0 or idx not in buf: buf[idx] = w * v
                    else: buf[idx] = buf[idx] + w * v
                else: buf[idx] = v
                q += 4 * per
        p += 4 + size * 4
PER = {64:1,65:3,66:3,67:4,68:6,69:6,70:3,71:5,72:5,73:1,74:5,75:5}
def plist(p):
    tex = 0
    while True:
        h = struct.unpack_from('<H', b, p)[0]; t = h & 0xFF
        if t == 0xFF: return
        if t < 8: p += 2; continue
        if t < 16:
            tex = struct.unpack_from('<H', b, p + 2)[0] & 0x1FFF; p += 4; continue
        size = struct.unpack_from('<H', b, p + 2)[0]
        if 64 <= t <= 75:
            q = p + 4
            w = struct.unpack_from('<H', b, q)[0]; n = w & 0x3FFF; uo = w >> 14; q += 2
            per = PER[t]
            for s in range(n):
                sid[0] += 1
                ln = struct.unpack_from('<h', b, q)[0]; q += 2
                vs = []
                for i in range(abs(ln)):
                    idx = struct.unpack_from('<H', b, q)[0]
                    uv = None
                    if t in (65, 66, 71, 72, 74, 75, 68, 69):
                        u, v = struct.unpack_from('<hh', b, q + 2)
                        d = 1024.0 if t in (66, 69, 72, 75) else 256.0
                        uv = (u / d, v / d)
                        if idx in buf: ents.append((tex, q + 2, d, buf[idx].tolist(), uv, sid[0]))
                    q += 2 * per
                    if i >= 2: q += 2 * uo
                    vs.append((idx, uv))
                for i in range(len(vs) - 2):
                    a, c, d2 = vs[i], vs[i + 1], vs[i + 2]
                    if all(x[0] in buf for x in (a, c, d2)):
                        tris.append((tex, [(buf[x[0]].tolist(), x[1]) for x in (a, c, d2)]))
        p += 4 + size * 2
def walk(o, P):
    while o:
        M = P @ mat(o)
        m = u32(o + 4)
        if m:
            vl, pl = u32(m), u32(m + 4)
            if vl: vlist(vl, M)
            if pl: plist(pl)
        c = u32(o + 44)
        if c: walk(c, M)
        o = u32(o + 48)
walk(root, np.eye(4))
json.dump(tris, open(sys.argv[2], 'w'))
json.dump(ents, open(sys.argv[2] + '.ents', 'w'))
from collections import Counter
print(len(tris), Counter(t for t, _ in tris))
allp = np.array([p for _, vs in tris for p, _ in vs]); print(allp.min(0), allp.max(0))
