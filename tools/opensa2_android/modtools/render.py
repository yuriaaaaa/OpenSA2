import sys, json, struct
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, sys.argv[1]); import pakx
tris = json.load(open(sys.argv[2]))
h, e, d = pakx.read(sys.argv[3])
T = []
for dd in d:
    if dd[:4] != b'DDS ': continue
    hh, w = struct.unpack_from('<II', dd, 12)
    T.append(np.frombuffer(dd, np.uint8, w * hh * 4, 128).reshape(hh, w, 4)[..., [2, 1, 0]])
L = np.array([-0.6, 0.5, -0.3]); L /= np.linalg.norm(L)
W, H = 1100, 640; S = 50
im = Image.new('RGB', (W, H), (25, 25, 30)); dr = ImageDraw.Draw(im)
for view, ox in ((0, 270), (1, 820)):
    key = (lambda t: -np.mean([p[0] for p, _ in t[1]])) if view == 0 else (lambda t: np.mean([p[2] for p, _ in t[1]]))
    for t, vs in sorted(tris, key=key):
        P = np.array([p for p, _ in vs])
        n = np.cross(P[1] - P[0], P[2] - P[0]); nn = np.linalg.norm(n); n = n / nn if nn else n
        shade = 0.55 + 0.45 * abs(n @ L)
        if vs[0][1] is not None and t < len(T):
            uv = np.mean([u for _, u in vs], 0); tx = T[t]
            c = tx[min(int(uv[1] % 1 * tx.shape[0]), tx.shape[0] - 1), min(int(uv[0] % 1 * tx.shape[1]), tx.shape[1] - 1)]
        elif t == 5: c = T[5][T[5].shape[0] // 2, T[5].shape[1] // 2]
        else: c = (200, 0, 0)
        c = tuple(int(x * shade) for x in c)
        pts = [(ox + p[2] * S, H - 30 - p[1] * S) for p in P] if view == 0 else [(ox + p[0] * S - 60, H - 30 - p[1] * S) for p in P]
        dr.polygon(pts, fill=c)
im.save(sys.argv[4])
