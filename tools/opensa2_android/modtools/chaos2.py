import sys, struct, colorsys
import numpy as np
from PIL import Image
sys.path.insert(0, sys.argv[1]); import pakx
SS, SUPER, EFF, OUTEFF, PREV = sys.argv[2:7]
def img(dd):
    hh, w = struct.unpack_from('<II', dd, 12)
    return np.frombuffer(dd, np.uint8, w * hh * 4, 128).reshape(hh, w, 4)[..., [2, 1, 0, 3]].astype(np.float32) / 255
def dds(dd, a):
    hh, w = a.shape[:2]; hd = bytearray(dd[:128]); struct.pack_into('<III', hd, 12, hh, w, w * 4)
    return bytes(hd) + (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)[..., [2, 1, 0, 3]].tobytes()
def hsv(a):
    rgb = a[..., :3]; mx = rgb.max(-1); mn = rgb.min(-1); c = mx - mn
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]; h = np.zeros_like(mx); m = c > 1e-6
    hr = (mx == r) & m; hg = (mx == g) & m & ~hr; hb = m & ~hr & ~hg
    h[hr] = (60 * ((g - b)[hr] / c[hr])) % 360; h[hg] = 60 * ((b - r)[hg] / c[hg]) + 120; h[hb] = 60 * ((r - g)[hb] / c[hb]) + 240
    return h, np.where(mx > 0, c / np.maximum(mx, 1e-6), 0), mx
def fromhsv(h, s, v):
    h6 = (h % 360) / 60; i = np.floor(h6).astype(int) % 6; f = h6 - np.floor(h6)
    p = v * (1 - s); q = v * (1 - s * f); t = v * (1 - s * (1 - f))
    return np.stack([np.choose(i, [v, q, p, p, t, v]), np.choose(i, [t, v, v, q, p, p]), np.choose(i, [p, p, t, v, v, q])], -1)
# 1. the ball: Super Sonic's spin texture, yellow -> Chaos green with black in its darker streaks, red -> purple
h, e, d = pakx.read(SUPER)
spin = [dd for x, dd in zip(e, d) if x[1].endswith(b'sstx_newspin.dds')][0]
a = img(spin); H, S, V = hsv(a)
yellow = (H > 35) & (H < 75) & (S > 0.25)
red = ((H < 25) | (H > 320)) & (S > 0.35)
out = a.copy()
g = np.clip((V - 0.25) / 0.75, 0, 1) ** 1.6          # the darker yellow streaks go to black
green = np.array([0x3A, 0xD4, 0x5C]) / 255
out[..., :3] = np.where(yellow[..., None], green * (0.08 + 1.05 * g)[..., None], out[..., :3])
out[..., :3] = np.where(red[..., None], fromhsv(np.full_like(H, 285), np.clip(S, 0.5, 0.9), V * 0.85), out[..., :3])
ball = out
h, e, d = pakx.read(SS)
for i, (x, dd) in enumerate(zip(e, d)):
    if x[1].endswith(b'stx_newspin.dds'): d[i] = dds(dd, ball)
pakx.write(SS, h, e, d)
# 2. the effects: Nazo's s_efftex, every colour light green, as csonefftex
h, e, d = pakx.read(EFF)
ne = []; prev = []
for i, (x, dd) in enumerate(zip(e, d)):
    lg, sh, s1, s2 = x
    lg = lg.replace(b'\\s_efftex\\', b'\\csonefftex\\'); sh = sh.replace(b's_efftex\\', b'csonefftex\\', 1)
    if sh.endswith(b'.inf'):
        sh = b'csonefftex\\csonefftex.inf'; lg = lg.replace(b's_efftex.inf', b'csonefftex.inf')
        dd = bytearray(dd)
        for j in range(0, len(dd), 60): struct.pack_into('<I', dd, j + 28, 0x4E415B01 + j // 60)
        d[i] = bytes(dd)
    elif dd[:4] == b'DDS ':
        a = img(dd); H, S, V = hsv(a)
        o = a.copy(); o[..., :3] = fromhsv(np.full_like(H, 128), S * 0.75, np.clip(V * 1.08 + 0.04 * S, 0, 1))
        d[i] = dds(dd, o); prev.append(o)
    ne.append([lg, sh, s1, s2])
pakx.write(OUTEFF, h, ne, d)
tiles = [Image.fromarray((np.clip(p[..., :3] * p[..., 3:], 0, 1) * 255).astype(np.uint8)).resize((64, 64), Image.NEAREST) for p in prev]
sheet = Image.new('RGB', (64 * 10 + 200, max(64 * 4, 200)))
for k, t in enumerate(tiles): sheet.paste(t, ((k % 10) * 64, (k // 10) * 64))
sheet.paste(Image.fromarray((ball[..., :3] * 255).astype(np.uint8)).resize((200, 200)), (640, 0))
sheet.save(PREV)
print('ok', len(prev))
