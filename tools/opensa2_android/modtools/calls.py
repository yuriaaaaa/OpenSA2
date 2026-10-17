import sys, capstone
import os; L=os.environ['SA2_GAME_LIB']
data=open(L,'rb').read(); foff,vaddr=0x2d3660,0x2d7660
syms={}; rev={}
for l in open(os.environ['SA2_SUBS']):
    n,v,s=l.split(); syms[n]=(int(v,16),int(s,0)); rev[int(v,16)]=n
md=capstone.Cs(capstone.CS_ARCH_ARM64,capstone.CS_MODE_ARM)
for n in sys.argv[1:]:
    v,s=syms[n]; o=v-vaddr+foff
    out=[]
    for i in md.disasm(data[o:o+s],v):
        if i.mnemonic in ('bl','b') and i.op_str.startswith('#'):
            t=int(i.op_str[1:],16)
            if t in rev: out.append(i.mnemonic+' '+rev[t])
    print(n, out)
