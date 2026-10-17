import sys, struct, json, colorsys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, sys.argv[1]); import pakx
MDL, ENTS, SRC, OUTMDL, OUTPAK = sys.argv[2:7]
b = bytearray(open(MDL, 'rb').read())
E = json.load(open(ENTS))
GREEN = np.array([0x3A, 0xD4, 0x5C]) / 255.0
def hand(p): return abs(p[2]) > 1.0 and p[1] < 5.2
# ---- UVs
feet = {}
for t, o, d, p, uv, s in E:
    if t == 6: feet.setdefault(p[2] > 0, []).append(p[2])
fc = {k: float(np.mean(v)) for k, v in feet.items()}
newuv = {}
for t, o, d, p, uv, s in E:
    u, v = uv
    if t == 2:
        if hand(p): u, v = 0.875, (0.25 if p[1] < 4.5 else 0.75)
        else: u = u * 0.75
    elif t == 6:
        zc = fc[p[2] > 0]
        if p[1] < 0.5: u, v = 0.1, 0.5                          # sole: grey
        elif p[1] > 1.75: u, v = 0.9, 0.5                       # cuff: gold
        elif abs(p[2] - zc) < 0.22 and p[0] < 0.4: u, v = 0.5, 0.1   # stripe down the front: purple
        else: u, v = 0.5, 0.5                                   # black
    else: continue
    struct.pack_into('<hh', b, o, int(round(u * d)), int(round(v * d)))
    newuv[o] = (u, v)
open(OUTMDL, 'wb').write(b)
# ---- textures
h, e, d = pakx.read(SRC)
def img(dd):
    hh, w = struct.unpack_from('<II', dd, 12)
    return np.frombuffer(dd, np.uint8, w * hh * 4, 128).reshape(hh, w, 4)[..., [2, 1, 0, 3]].astype(np.float32) / 255
def dds(dd, a):
    hh, w = a.shape[:2]
    hd = bytearray(dd[:128]); struct.pack_into('<III', hd, 12, hh, w, w * 4)
    o = (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)[..., [2, 1, 0, 3]]
    return bytes(hd) + o.tobytes()
def big(a, w, hh, rs=Image.BILINEAR):
    return np.asarray(Image.fromarray((a * 255).astype(np.uint8)).resize((w, hh), rs)).astype(np.float32) / 255
def greenify(a):
    rgb = a[..., :3]; mx = rgb.max(-1); mn = rgb.min(-1); sat = (mx - mn) / np.maximum(mx, 1e-6)
    lum = rgb @ np.array([0.3, 0.59, 0.11])
    fur = (lum > 0.35) & (sat < 0.45) | ((rgb[..., 2] > rgb[..., 0] + 0.08) & (lum > 0.2))
    ref = np.median(lum[fur]) if fur.any() else 0.8
    g = GREEN[None, None, :] * np.clip(lum / ref, 0, 1.3)[..., None]
    out = a.copy(); out[..., :3] = np.where(fur[..., None], np.clip(g, 0, 1), rgb)
    return out
names = [x[1].split(b'\\')[-1].decode() for x in e]
tex = {}
for i, (x, dd) in enumerate(zip(e, d)):
    n = names[i]
    if dd[:4] != b'DDS ': continue
    a = img(dd)
    if n == 'stx_00.dds':
        g = big(greenify(a), 192, 256)
        r = np.ones((256, 64, 4), np.float32)
        r[:128, :, :3] = 0.97
        r[128:, :, :3] = np.array([0xF0, 0xB8, 0x28]) / 255
        a = np.concatenate([g, r], 1)
    elif n == 'stx_01.dds':
        a = big(greenify(a), 256, 256)
        im = Image.fromarray((a * 255).astype(np.uint8)); dr = ImageDraw.Draw(im)
        cu, cv, hu, hv = 0.75 * 256, 0.79 * 256, 0.075 * 256, 0.055 * 256
        def dia(k, col): dr.polygon([(cu - hu * k, cv), (cu, cv - hv * k), (cu + hu * k, cv), (cu, cv + hv * k)], fill=col)
        dia(1.25, (10, 10, 10, 255)); dia(1.0, (0x6A, 0x1F, 0x9A, 255)); dia(0.45, (0xA8, 0x5C, 0xD8, 255))
        a = np.asarray(im).astype(np.float32) / 255
    elif n == 'stx_ref00.dds':
        yy, xx = np.mgrid[0:128, 0:128] / 127.0 * 2 - 1; r = np.sqrt(xx ** 2 + yy ** 2)
        col = np.array([1.0, 0.86, 0.18]) * np.clip(1.25 - 0.45 * r, 0, 1)[..., None]
        col = np.where((r > 0.78)[..., None], col * np.clip((0.95 - r) / 0.17, 0, 1)[..., None], col)
        a = np.concatenate([col, np.ones((128, 128, 1))], -1).astype(np.float32)
    elif n == 'hdstx_00.dds':
        rgb = a[..., :3]; gsel = (rgb[..., 1] > rgb[..., 0] + 0.1) & (rgb[..., 1] > rgb[..., 2])
        a = a.copy(); a[..., :3] = np.where(gsel[..., None], rgb[..., 1:2] * np.array([0.35, 0.08, 0.5]), rgb)
    elif n == 'stx_s000.dds':
        a = np.ones((128, 128, 4), np.float32)
        a[..., :3] = np.array([0x12, 0x12, 0x14]) / 255
        a[:32, 32:96, :3] = np.array([0x6A, 0x1F, 0x9A]) / 255
        a[32:96, 96:, :3] = np.array([0xF0, 0xB8, 0x28]) / 255
        a[32:96, :32, :3] = np.array([0x33, 0x33, 0x38]) / 255
    elif n == 's_wind.dds':
        lum = a[..., :3].max(-1, keepdims=True)
        a = a.copy(); a[..., :3] = lum * np.array([0.12, 0.45, 0.18])
    else:
        continue
    d[i] = dds(dd, a); tex[n] = a
# inf: names, GBIX, sizes
infi = [i for i, n in enumerate(names) if n.endswith('.inf')][0]
inf = bytearray(d[infi])
for j in range(0, len(inf), 60):
    nm = inf[j:j + 28].rstrip(b'\0').decode() + '.dds'
    if nm in tex:
        hh, w = tex[nm].shape[:2]; struct.pack_into('<II', inf, j + 44, w, hh)
d[infi] = bytes(inf)
pakx.write(OUTPAK, h, e, d)
print('ok', {k: v.shape for k, v in tex.items()})
