import sys,struct,colorsys
sys.path.insert(0,sys.argv[1]); import pakx
import numpy as np
from PIL import Image
src,dst,prev=sys.argv[2],sys.argv[3],sys.argv[4]
h,e,d=pakx.read(src)
def green(dd):
    w,hh=struct.unpack_from('<II',dd,16)[::-1][0],struct.unpack_from('<I',dd,12)[0]
    hgt,wid=struct.unpack_from('<II',dd,12)
    px=np.frombuffer(dd,np.uint8,wid*hgt*4,128).reshape(hgt,wid,4).astype(np.float32)/255
    b,g,r,a=px[...,0],px[...,1],px[...,2],px[...,3]
    mx=np.max(px[...,:3],-1); mn=np.min(px[...,:3],-1); c=mx-mn
    # hue (degrees)
    hue=np.zeros_like(mx); m=c>1e-6
    hr=(mx==r)&m; hg=(mx==g)&m&~hr; hb=m&~hr&~hg
    hue[hr]=(60*((g-b)[hr]/c[hr]))%360; hue[hg]=60*((b-r)[hg]/c[hg])+120; hue[hb]=60*((r-g)[hb]/c[hb])+240
    sat=np.where(mx>0,c/np.maximum(mx,1e-6),0)
    sel=(hue>185)&(hue<265)&(sat>0.25)
    # blue -> emerald green, a touch brighter
    nh=(hue-232+135)%360; v=np.minimum(mx*1.15+0.04,1.0); s=sat
    hh6=nh/60; i=np.floor(hh6).astype(int)%6; f=hh6-np.floor(hh6)
    p=v*(1-s); q=v*(1-s*f); t=v*(1-s*(1-f))
    R=np.choose(i,[v,q,p,p,t,v]); G=np.choose(i,[t,v,v,q,p,p]); B=np.choose(i,[p,p,t,v,v,q])
    out=px.copy()
    out[...,2]=np.where(sel,R,r); out[...,1]=np.where(sel,G,g); out[...,0]=np.where(sel,B,b)
    o=(np.clip(out,0,1)*255+0.5).astype(np.uint8)
    return dd[:128]+o.tobytes()+dd[128+wid*hgt*4:], o
ne=[];nd=[]
for (lg,sh,s1,s2),dd in zip(e,d):
    lg=lg.replace(b'\\sonictex\\',b'\\ssonictex\\'); sh=sh.replace(b'sonictex\\',b'ssonictex\\',1)
    if sh.endswith(b'.inf'):
        sh=b'ssonictex\\ssonictex.inf'; lg=lg.replace(b'sonictex.inf',b'ssonictex.inf')
        dd=bytearray(dd)
        for i in range(0,len(dd),60): struct.pack_into('<I',dd,i+28,0x4E415A01+i//60)
        dd=bytes(dd)
    if dd[:4]==b'DDS ':
        dd,o=green(dd)
        name=sh.split(b'\\')[-1].decode()
        Image.fromarray(o[...,[2,1,0,3]]).save(prev+'/'+name+'.png')
    ne.append([lg,sh,s1,s2]); nd.append(dd)
pakx.write(dst,h,ne,nd)
for x in ne: print(x[0],x[1])
