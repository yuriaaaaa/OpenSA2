import sys, capstone
import os; L=os.environ['SA2_GAME_LIB']
data=open(L,'rb').read(); foff,vaddr=0x2d3660,0x2d7660
syms={}
for l in open(os.environ['SA2_SUBS']):
    n,v,s=l.split(); syms[n]=(int(v,16),int(s,0))
md=capstone.Cs(capstone.CS_ARCH_ARM64,capstone.CS_MODE_ARM)
n=sys.argv[1]; v,s=syms[n]
o=v-vaddr+foff
for i in md.disasm(data[o:o+s],v):
    print(f'{i.address:x} {i.mnemonic} {i.op_str}')
