import struct
def read(p):
    b=open(p,'rb').read(); assert b[:4]==b'\x01pak'
    n=struct.unpack_from('<I',b,0x39)[0]; at=0x3D; ents=[]
    for i in range(n):
        l=struct.unpack_from('<I',b,at)[0]; lg=b[at+4:at+4+l]; at+=4+l
        l2=struct.unpack_from('<I',b,at)[0]; sh=b[at+4:at+4+l2]; at+=4+l2
        s1,s2=struct.unpack_from('<II',b,at); at+=8
        ents.append([lg,sh,s1,s2])
    d=at; data=[]
    for e in ents: data.append(b[d:d+e[2]]); d+=e[2]
    return b[:0x39],ents,data
def write(p,head,ents,data):
    out=bytearray(head)+struct.pack('<I',len(ents))
    for (lg,sh,s1,s2),dd in zip(ents,data):
        out+=struct.pack('<I',len(lg))+lg+struct.pack('<I',len(sh))+sh+struct.pack('<II',len(dd),len(dd))
    for dd in data: out+=dd
    open(p,'wb').write(out)
