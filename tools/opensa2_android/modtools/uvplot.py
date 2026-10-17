import json, sys, struct
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, sys.argv[1]); import pakx
tris = json.load(open('tris.json'))
h, e, d = pakx.read(sys.argv[4])   # the texture archive the model uses
texs = [dd for dd in d if dd[:4] == b'DDS ']
allp = np.array([p for _, vs in tris for p, _ in vs]); lo, hi = allp.min(0), allp.max(0)
out = []
for tid in map(int, sys.argv[2].split(',')):
    dd = texs[tid]; hh, w = struct.unpack_from('<II', dd, 12)
    im = Image.fromarray(np.frombuffer(dd, np.uint8, w*hh*4, 128).reshape(hh, w, 4)[..., [2,1,0]].copy()).resize((512, 512))
    ov = Image.new('RGB', (512, 512)); dr = ImageDraw.Draw(ov)
    for t, vs in tris:
        if t != tid or vs[0][1] is None: continue
        c = np.mean([p for p, _ in vs], 0); n = (c - lo) / (hi - lo)
        col = (int(255*n[1]), int(255*n[2]), int(255*n[0]))   # R=height G=front(z) B=x
        dr.polygon([((u % 1) * 512, (v % 1) * 512) for _, (u, v) in vs], fill=col, outline=(255,255,255))
    out.append(Image.blend(im, ov, 0.6))
s = Image.new('RGB', (512 * len(out), 512))
for i, o in enumerate(out): s.paste(o, (512 * i, 0))
s.save(sys.argv[3])
