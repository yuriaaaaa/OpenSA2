import struct
T = {0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'Q',7:'q',8:'f'}
def read(b, off=0):
    assert b[off:off+4] == b'@UTF', b[off:off+4]
    size = struct.unpack_from('>I', b, off+4)[0]; base = off + 8
    _, rows_off, str_off, data_off, name_off, ncol, rowlen, nrows = struct.unpack_from('>HHIIIHHI', b, base)
    def s(o):
        p = base + str_off + o; e = b.index(b'\0', p); return b[p:e].decode('latin1')
    cols = []; p = base + 24
    for i in range(ncol):
        fl = b[p]; nm = struct.unpack_from('>I', b, p+1)[0]; p += 5
        st, ty = fl & 0xF0, fl & 0x0F
        const = None
        if st == 0x30:
            const, p = val(b, p, ty, s, base + data_off)
        cols.append((s(nm), st, ty, const))
    rows = []
    for r in range(nrows):
        p = base + rows_off + r * rowlen; row = {}
        for nm, st, ty, const in cols:
            if st == 0x50: row[nm], p = val(b, p, ty, s, base + data_off)
            elif st == 0x30: row[nm] = const
            else: row[nm] = None
        rows.append(row)
    return {'name': s(name_off), 'cols': [(c[0], c[1], c[2]) for c in cols], 'rows': rows}
def val(b, p, ty, s, dbase):
    if ty in T:
        f = '>' + T[ty]; return struct.unpack_from(f, b, p)[0], p + struct.calcsize(f)
    if ty == 0xA: return s(struct.unpack_from('>I', b, p)[0]), p + 4
    if ty == 0xB:
        o, n = struct.unpack_from('>II', b, p); return bytes(b[dbase+o:dbase+o+n]), p + 8
    raise ValueError(ty)
def write(t):
    strs = bytearray(b'<NULL>\0'); sidx = {}
    def S(x):
        if x not in sidx: sidx[x] = len(strs); strs.extend(x.encode('latin1') + b'\0')
        return sidx[x]
    S(t['name'])
    data = bytearray()
    def D(x):
        if not x: return 0, 0
        while len(data) % 8: data.append(0)
        o = len(data); data.extend(x); return o, len(x)
    cols = bytearray(); rowlen = 0
    for nm, st, ty in t['cols']:
        cols += bytes([st | ty]) + struct.pack('>I', S(nm))
        if st == 0x30:
            v = t['rows'][0][nm]
            cols += enc(ty, v, S, D)
        if st == 0x50: rowlen += {0xA:4,0xB:8}.get(ty) or struct.calcsize('>' + T[ty])
    rows = bytearray()
    for r in t['rows']:
        for nm, st, ty in t['cols']:
            if st == 0x50: rows += enc(ty, r[nm], S, D)
    hdr = 24
    rows_off = hdr + len(cols); str_off = rows_off + len(rows); data_off = str_off + len(strs)
    pad = (-data_off) % 32; data_off += pad
    body = struct.pack('>HHIIIHHI', 1, rows_off, str_off, data_off, S(t['name']), len(t['cols']), rowlen, len(t['rows']))
    body += cols + rows + strs + b'\0' * pad + data
    while len(body) % 4: body += b'\0'
    return b'@UTF' + struct.pack('>I', len(body)) + body
def enc(ty, v, S, D):
    if ty in T: return struct.pack('>' + T[ty], v)
    if ty == 0xA: return struct.pack('>I', S(v))
    if ty == 0xB: return struct.pack('>II', *D(v))
