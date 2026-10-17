# Pads a character texture archive (pak) to N textures with copies of one of its textures, so models
# that name texture numbers past the end (the Nazo models use 9, 10 and 37) read a real texture.
import sys, struct
sys.path.insert(0, sys.argv[1]); import pakx
src, out, n, fill, gbix0 = sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], int(sys.argv[6], 16)
h, e, d = pakx.read(src)
infi = [i for i, x in enumerate(e) if x[1].endswith(b'.inf')][0]
inf = bytearray(d[infi]); count = len(inf) // 60
folder = e[infi][1].split(b'\\')[0]
lgpre = e[infi][0][:e[infi][0].rfind(b'\\') + 1]
fi = [i for i, x in enumerate(e) if x[1].endswith(fill.encode())][0]
fe = [j for j in range(count) if inf[60 * j:60 * j + 28].rstrip(b'\0').decode() + '.dds' == fill][0]
for k in range(count, n):
    name = b'pad_%02d' % k
    ent = bytearray(inf[60 * fe:60 * fe + 60]); ent[0:28] = name.ljust(28, b'\0')
    struct.pack_into('<I', ent, 28, gbix0 + k)
    inf += ent
    e.append([lgpre + name + b'.dds', folder + b'\\' + name + b'.dds', 0, 0]); d.append(d[fi])
d[infi] = bytes(inf)
pakx.write(out, h, e, d)
print(src.split('/')[-1], count, '->', len(inf) // 60)
