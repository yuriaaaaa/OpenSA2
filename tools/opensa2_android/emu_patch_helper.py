#!/usr/bin/env python3
"""Runs libopensa2patch.so's constructor in an ARM64 emulator (unicorn) against a real
libsa2game.so, then checks the patches by executing the patched game code.

  pip install unicorn
  python3 tools/opensa2_android/emu_patch_helper.py libsa2game.so libopensa2patch.so [plan.txt]

libsa2game.so is the game library of the release APK the app is built from (lib/arm64-v8a/, never
committed); libopensa2patch.so is host/android/native/opensa2_patch.c built as build_apk.py builds
it. Without plan.txt a built-in plan is used (a refused bad-bytes entry, a ret patch, a swap in
both directions, an adrp relocation, and the three position wrappers); with one (a plan the
launcher wrote, NativePatch.build) the checks are: every patch applied, a ret patch returns, the
swap reaches the other body, and the start-position wrapper finds the stand-in's start.

The constructor's system calls are served here (a fake /proc/self/{environ,maps,auxv}, the plan,
mmap/mprotect), so what is tested is the helper's own logic: plan parsing, the expected-bytes
check, trampolines and stubs, the entry writes, and that the game's code then runs the way the
patch says -- including the game's own LoadStartPosition, run with the runtime's function table
brought up (sa2_ft_init, sa2_ft_register_generated) and the release's .text overlay in guest
memory, against synthetic start tables. What it cannot show is what Android allows: LD_PRELOAD
of the helper and making the game's code writable are only seen on a device (game.log's
"opensa2-patch:" lines).
"""
import struct
import sys

from unicorn import (Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR, UC_HOOK_CODE,
                     UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC, UC_PROT_ALL, UC_HOOK_MEM_UNMAPPED)
from unicorn.arm64_const import *

GAME_BASE = 0x5500000000
PATCH_BASE = 0x7100000000
MMAP_BASE = 0x7200000000
STACK = 0x7f00000000
STOP = 0x7e00000000          # a mapped page of `brk`; returning here ends a run
GUEST = 0x6000000000          # guest memory (sparse: only the pages a test touches)
STATE = 0x6f00000000
IMPORTS = 0x6e00000000         # the game's C library imports: a page of `ret`, served in Python
HEAP = 0x6d00000000
PLAN = "/data/user/0/com.yuriaaaaa.opensa2/files/native-patches.txt"


def u32(b, o): return struct.unpack_from("<I", b, o)[0]
def u64(b, o): return struct.unpack_from("<Q", b, o)[0]


class Elf:
    def __init__(self, path):
        self.b = open(path, "rb").read()
        b = self.b
        self.phoff, self.shoff = u64(b, 0x20), u64(b, 0x28)
        self.phnum, self.shnum, self.shstrndx = struct.unpack_from("<H", b, 0x38)[0], struct.unpack_from("<H", b, 0x3C)[0], struct.unpack_from("<H", b, 0x3E)[0]
        self.ph = []
        for i in range(self.phnum):
            o = self.phoff + 56 * i
            self.ph.append(struct.unpack_from("<IIQQQQQQ", b, o))
        self.sh = []
        for i in range(self.shnum):
            o = self.shoff + 64 * i
            self.sh.append(struct.unpack_from("<IIQQQQIIQQ", b, o))
        self.syms = {}
        for s in self.sh:
            if s[1] == 2:   # SHT_SYMTAB
                strtab = self.sh[s[6]]
                for k in range(s[5] // 24):
                    o = s[4] + 24 * k
                    name, info, other, shndx, value, size = struct.unpack_from("<IBBHQQ", b, o)
                    nm = b[strtab[4] + name: b.index(b"\0", strtab[4] + name)].decode()
                    if nm and shndx:
                        self.syms[nm] = (value, size)
        self.dynsyms = []
        dyn = [p for p in self.ph if p[0] == 2]
        self.dyn = {}
        if dyn:
            p = dyn[0]
            for k in range(p[5] // 16):
                tag, val = struct.unpack_from("<qQ", b, p[2] + 16 * k)
                if tag == 0:
                    break
                self.dyn.setdefault(tag, val)

    def vaddr_to_off(self, v):
        for p in self.ph:
            if p[0] == 1 and p[3] <= v < p[3] + p[5]:
                return v - p[3] + p[2]
        raise KeyError(hex(v))

    def load(self, uc, base):
        end = 0
        for p in self.ph:
            if p[0] != 1:
                continue
            _, flags, off, va, pa, filesz, memsz, align = p
            a0 = (base + va) & ~0xFFF
            a1 = (base + va + memsz + 0xFFF) & ~0xFFF
            try:
                uc.mem_map(a0, a1 - a0, UC_PROT_ALL)
            except UcError:
                # overlaps an earlier segment's last page
                start = max(a0, end)
                if a1 > start:
                    uc.mem_map(start, a1 - start, UC_PROT_ALL)
            uc.mem_write(base + va, self.b[off:off + filesz])
            end = max(end, a1)
        # protections after loading
        for p in self.ph:
            if p[0] != 1:
                continue
            _, flags, off, va, pa, filesz, memsz, align = p
            a0 = (base + va) & ~0xFFF
            a1 = (base + va + memsz + 0xFFF) & ~0xFFF
            prot = (UC_PROT_READ if flags & 4 else 0) | (UC_PROT_WRITE if flags & 2 else 0) | (UC_PROT_EXEC if flags & 1 else 0)
            if flags & 2:
                prot |= UC_PROT_READ
            uc.mem_protect(a0, a1 - a0, prot | UC_PROT_READ)
        self.relocate(uc, base)
        return end

    def dynsym(self, idx):
        symtab, strtab = self.dyn[6], self.dyn[5]
        o = self.vaddr_to_off(symtab) + 24 * idx
        name, info, other, shndx, value, size = struct.unpack_from("<IBBHQQ", self.b, o)
        so = self.vaddr_to_off(strtab) + name
        nm = self.b[so:self.b.index(b"\0", so)].decode()
        return nm, shndx, value

    def relocate(self, uc, base):
        self.imports = {}
        for (tab, sz) in ((7, 8), (23, 2)):     # DT_RELA/RELASZ, DT_JMPREL/PLTRELSZ
            if tab not in self.dyn:
                continue
            start = self.vaddr_to_off(self.dyn[tab])
            size = self.dyn[sz]
            for o in range(start, start + size, 24):
                off, info, add = struct.unpack_from("<QQq", self.b, o)
                typ, sym = info & 0xFFFFFFFF, info >> 32
                if typ == 1027:   # RELATIVE
                    uc.mem_write(base + off, struct.pack("<Q", (base + add) & (2**64 - 1)))
                elif typ in (257, 1025, 1026):  # ABS64, GLOB_DAT, JUMP_SLOT
                    nm, shndx, value = self.dynsym(sym)
                    v = base + value + add if shndx else IMPORTS + (sym << 4)
                    if not shndx:
                        self.imports[IMPORTS + (sym << 4)] = nm
                    uc.mem_write(base + off, struct.pack("<Q", v & (2**64 - 1)))


class Sys:
    """The constructor's system calls."""

    def __init__(self, uc, files):
        self.uc, self.files, self.fds, self.next = uc, files, {}, 3
        self.err = b""
        self.mmap_at = MMAP_BASE
        self.mprotects = []
        self.now_ms = 1_000_000      # CLOCK_MONOTONIC, moved on by the tests

    def hook(self, uc, intno, _):
        if intno != 2:
            raise RuntimeError(f"interrupt {intno}")
        n = uc.reg_read(UC_ARM64_REG_X8)
        a = [uc.reg_read(r) for r in (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X5)]
        r = self.call(n, a)
        uc.reg_write(UC_ARM64_REG_X0, r & (2**64 - 1))

    def cstr(self, a):
        s = b""
        while True:
            c = self.uc.mem_read(a, 64)
            i = c.find(b"\0")
            if i >= 0:
                return (s + c[:i]).decode()
            s += bytes(c)
            a += 64

    def call(self, n, a):
        if n == 56:   # openat
            p = self.cstr(a[1])
            if a[2] & 0o1:   # O_WRONLY (with O_CREAT|O_TRUNC): a file the helper writes
                self.files[p] = b""
                fd = self.next
                self.next += 1
                self.fds[fd] = [None, 0, p]
                return fd
            if p not in self.files:
                return -2
            fd = self.next
            self.next += 1
            self.fds[fd] = [self.files[p], 0]
            return fd
        if n == 57:
            self.fds.pop(a[0], None)
            return 0
        if n == 63:
            f = self.fds.get(a[0])
            if f is None:
                return -9
            data = f[0][f[1]:f[1] + a[2]]
            f[1] += len(data)
            self.uc.mem_write(a[1], bytes(data))
            return len(data)
        if n == 64:
            data = bytes(self.uc.mem_read(a[1], a[2]))
            if a[0] == 2:
                self.err += data
            elif a[0] in self.fds and len(self.fds[a[0]]) == 3:
                self.files[self.fds[a[0]][2]] += data
            return a[2]
        if n == 222:  # mmap
            size = (a[1] + 0xFFF) & ~0xFFF
            at = self.mmap_at
            self.mmap_at += size + 0x10000
            prot = self.prot(a[2])
            self.uc.mem_map(at, size, prot)
            return at
        if n == 226:  # mprotect
            self.mprotects.append((a[0], a[1], a[2]))
            if a[0] & 0xFFF:
                return -22
            self.uc.mem_protect(a[0], (a[1] + 0xFFF) & ~0xFFF, self.prot(a[2]))
            return 0
        if n == 215:
            return 0
        if n == 278:  # getrandom
            import os
            self.uc.mem_write(a[0], os.urandom(a[1]))
            return a[1]
        if n == 113:  # clock_gettime
            self.uc.mem_write(a[1], struct.pack("<qq", self.now_ms // 1000, (self.now_ms % 1000) * 1_000_000))
            return 0
        raise RuntimeError(f"system call {n}")

    @staticmethod
    def prot(p):
        r = 0
        if p & 1: r |= UC_PROT_READ
        if p & 2: r |= UC_PROT_WRITE
        if p & 4: r |= UC_PROT_EXEC
        return r


def machine(game, patch=None):
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    end = game.load(uc, GAME_BASE)
    if patch:
        patch.load(uc, PATCH_BASE)
    uc.mem_map(STACK - 0x100000, 0x100000, UC_PROT_READ | UC_PROT_WRITE)
    uc.mem_map(STOP, 0x1000, UC_PROT_ALL)
    uc.mem_write(STOP, struct.pack("<I", 0xD4200000) * 1024)   # brk #0
    uc.mem_map(STATE, 0x10000, UC_PROT_READ | UC_PROT_WRITE)
    uc.mem_map(IMPORTS, 0x100000, UC_PROT_ALL)
    uc.mem_write(IMPORTS, struct.pack("<I", 0xD65F03C0) * (0x100000 // 4))   # ret
    uc.mem_map(HEAP, 0x4000000, UC_PROT_READ | UC_PROT_WRITE)
    heap = [HEAP]

    def libc(u, addr, size, data):
        nm = game.imports.get(addr)
        if nm is None:
            return
        x0, x1 = u.reg_read(UC_ARM64_REG_X0), u.reg_read(UC_ARM64_REG_X1)
        if nm in ("calloc", "malloc"):
            n = x0 * x1 if nm == "calloc" else x0
            at = heap[0]
            heap[0] += (n + 15) & ~15
            u.reg_write(UC_ARM64_REG_X0, at)
        elif nm == "free":
            pass
        else:
            raise RuntimeError(f"the game called {nm}")
    uc.hook_add(UC_HOOK_CODE, libc, begin=IMPORTS, end=IMPORTS + 0x100000)
    return uc, end


def bring_up(uc, game):
    """The runtime's function table (sa2_ft_init + the generated entries) and the guest .text
    bytes the release carries (jump tables), so a lifted function's switch can dispatch."""
    sym = game.syms
    uc.reg_write(UC_ARM64_REG_SP, STACK - 0x1000)
    for name, arg in (("sa2_ft_init", 30000), ("sa2_ft_register_generated", 0)):
        v, _ = sym[name]
        uc.reg_write(UC_ARM64_REG_X0, arg)
        uc.reg_write(UC_ARM64_REG_X30, STOP)
        uc.emu_start(GAME_BASE + v, STOP, count=50_000_000)
    runs_v, runs_n = sym["overlay_runs"]
    bytes_v, bytes_n = sym["overlay_bytes"]
    runs = game.b[game.vaddr_to_off(runs_v):game.vaddr_to_off(runs_v) + runs_n]
    blob = game.b[game.vaddr_to_off(bytes_v):game.vaddr_to_off(bytes_v) + bytes_n]
    for k in range(0, runs_n, 12):
        va, ln, off = struct.unpack_from("<III", runs, k)
        a0, a1 = (GUEST + va) & ~0xFFF, (GUEST + va + ln + 0xFFF) & ~0xFFF
        for pg in range(a0, a1, 0x1000):
            try:
                uc.mem_map(pg, 0x1000, UC_PROT_READ | UC_PROT_WRITE)
            except UcError:
                pass
        uc.mem_write(GUEST + va, blob[off:off + ln])


def reset(uc):
    """The same starting registers and a clean stack, so two machines can be compared."""
    for k in range(29):
        uc.reg_write(UC_ARM64_REG_X0 + k, 0x1000 + k)
    uc.reg_write(UC_ARM64_REG_X29, 0x2900)
    uc.mem_write(STACK - 0x10000, bytes(0x10000))


def run_function(uc, addr, x0=0, x1=0, x2=0, until=None, limit=200000, want_trace=True):
    uc.reg_write(UC_ARM64_REG_SP, STACK - 0x1000)
    uc.reg_write(UC_ARM64_REG_X0, x0)
    uc.reg_write(UC_ARM64_REG_X1, x1)
    uc.reg_write(UC_ARM64_REG_X2, x2)
    uc.reg_write(UC_ARM64_REG_X30, STOP)
    trace = []
    def unmapped(u, access, address, size, value, data):
        pc = u.reg_read(UC_ARM64_REG_PC)
        g = f" (guest {address - GUEST:#x})" if GUEST <= address < GUEST + 0x20000000 else ""
        print(f"  unmapped access {access} at {address:#x}{g} size {size} from native {pc - GAME_BASE:#x}")
        return False
    hu = uc.hook_add(UC_HOOK_MEM_UNMAPPED, unmapped)
    h = uc.hook_add(UC_HOOK_CODE, lambda u, a, s, d: trace.append(a)) if want_trace else None
    try:
        uc.emu_start(addr, until if until is not None else STOP, count=limit)
    finally:
        uc.hook_del(hu)
        if h is not None:
            uc.hook_del(h)
    return trace


def guest_base_var(uc, game):
    r32, _ = game.syms["__remill_read_memory_32"]
    code = game.b[game.vaddr_to_off(r32):game.vaddr_to_off(r32) + 0x30]
    for k in range(0, 0x30, 4):
        w = u32(code, k)
        if (w & 0x9F000000) == 0x90000000:
            immlo, immhi = (w >> 29) & 3, (w >> 5) & 0x7FFFF
            imm = (immhi << 2) | immlo
            if imm & (1 << 20):
                imm -= 1 << 21
            page = ((r32 + k) & ~0xFFF) + (imm << 12)
            off = ((u32(code, k + 4) >> 10) & 0xFFF) * 8
            return u64(bytes(uc.mem_read(GAME_BASE + page + off, 8)), 0)
    raise RuntimeError("no guest base")


def given_checks(uc, game, sc, fn, check):
    gb = guest_base_var(uc, game)
    uc.mem_map(GUEST + 0x1F0000, 0x20000, UC_PROT_READ | UC_PROT_WRITE)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    # Disable Music: returns at once.
    music, _ = fn(0x442CF0)
    st = bytearray(0x1000)
    struct.pack_into("<I", st, 0x908, 0x200000)
    uc.mem_write(STATE, bytes(st))
    uc.mem_write(GUEST + 0x200000, struct.pack("<I", 0x401234))
    run_function(uc, GAME_BASE + music, STATE, 0x442CF0, 0)
    s2 = bytes(uc.mem_read(STATE, 0x1000))
    check(u32(s2, 0x908) == 0x200004 and u32(s2, 0x9A8) == 0x401234, "Disable Music: the music function returns at once")
    # Sonic -> Shadow reaches Shadow's body; Shadow -> Sonic reaches Sonic's.
    sonic, _ = fn(0x716E00)
    shadow, _ = fn(0x717360)
    run_function(uc, GAME_BASE + sonic, STATE, 0x716E00, 0, until=GAME_BASE + shadow + 16)
    check(uc.reg_read(UC_ARM64_REG_PC) == GAME_BASE + shadow + 16, "LoadSonic runs LoadShadow")
    run_function(uc, GAME_BASE + shadow, STATE, 0x717360, 0, until=GAME_BASE + sonic + 16)
    check(uc.reg_read(UC_ARM64_REG_PC) == GAME_BASE + sonic + 16, "LoadShadow runs LoadSonic")
    # The start position wrapper with the game's own LoadStartPosition.
    bring_up(uc, game)
    for a, n in ((0x1746000, 0x5000), (0x1934000, 0x1000), (0x1DE9000, 0x2000), (0x1000000, 0x2000), (0x300000, 0x1000),
                 (0x8AF000, 0x1000)):
        try:
            uc.mem_map(GUEST + a, n, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
        uc.mem_write(GUEST + a, bytes(n))
    for a in (0x1746F20, 0x17472C0, 0x17475D8, 0x17478F0, 0x1747BE8, 0x1747C40, 0x1748008, 0x1748378, 0x1748400):
        uc.mem_write(GUEST + a, struct.pack("<h", 0x5B))
    uc.mem_write(GUEST + 0x17472C0, struct.pack("<hHHH9f", 13, 0x1234, 0, 0, 5.0, 6.0, 7.0, 0, 0, 0, 0, 0, 0)
                 + struct.pack("<h", 0x5B))                                         # Shadow's table has City Escape
    uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", 13))
    uc.mem_write(GUEST + 0x1DE9600, struct.pack("<I", 0x1000000))
    uc.mem_write(GUEST + 0x1DEA6E0, struct.pack("<I", 0x1001000))
    uc.mem_write(GUEST + 0x1000000, bytes([0, 0, 0, 0]))                            # Sonic (CharID 0) in it
    st = bytearray(0x1000)
    struct.pack_into("<I", st, 0x908, 0x200000)
    struct.pack_into("<I", st, 0x8C8, 0)
    struct.pack_into("<I", st, 0x8F8, 0x300000)
    uc.mem_write(STATE, bytes(st))
    uc.mem_write(GUEST + 0x200000, struct.pack("<II", 0x401234, 0x300100))
    lsp, _ = fn(0x43D8E0)
    run_function(uc, GAME_BASE + lsp, STATE, 0x43D8E0, 0, limit=2_000_000)
    pos = struct.unpack("<3f", bytes(uc.mem_read(GUEST + 0x300000, 12)))
    rot = struct.unpack("<3i", bytes(uc.mem_read(GUEST + 0x300100, 12)))
    cid = bytes(uc.mem_read(GUEST + 0x1000001, 1))[0]
    check(pos == (5.0, 6.0, 7.0) and rot[1] == 0x1234 and cid == 0,
          f"Sonic standing in for Shadow starts at Shadow's start (pos {pos}, CharID after {cid})")


def boot(game, patch, plan_text):
    """A machine with the game and the helper loaded, the helper's constructor run on `plan_text`."""
    uc, _ = machine(game, patch)
    maps = (f"{GAME_BASE:x}-{GAME_BASE + 0x2d4000:x} r--p 00000000 fd:05 1234 /data/app/~~x==/com.yuriaaaaa.opensa2-y==/lib/arm64/libsa2game.so\n"
            f"{GAME_BASE + 0x2d7000:x}-{GAME_BASE + 0x8ddc000:x} r-xp 002d3000 fd:05 1234 /data/app/~~x==/com.yuriaaaaa.opensa2-y==/lib/arm64/libsa2game.so\n").encode()
    files = {
        "/proc/self/environ": b"PATH=/system/bin\0OPENSA2_PATCHES=" + PLAN.encode() + b"\0",
        "/proc/self/maps": maps,
        "/proc/self/auxv": struct.pack("<4Q", 6, 4096, 0, 0),
        PLAN: plan_text,
    }
    sc = Sys(uc, files)
    uc.hook_add(UC_HOOK_INTR, sc.hook)
    ctor = u64(bytes(uc.mem_read(PATCH_BASE + patch.dyn[25], 8)), 0)
    run_function(uc, ctor, limit=50_000_000, want_trace=False)
    return uc, sc


def fill_checks(game, patch, check):
    """Kind 9 around the ret function sub_7f4700 standing in for the port's sprite call: a menu
    background spanning a 2340x1080 screen a texel a unit (x -200, width 1040, u 0..1.015625, v
    0..0.9375) reaches the body with u ending at the 16:9 picture's width (853.33/1024) and v cropped
    about its centre by the same factor; the caller's arguments are as before after; a sprite that
    is not such a background, or the same on a 16:9 screen, is left alone."""
    sym = game.syms
    fn, fsize = sym["sub_7f4700"]
    first16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    fb, _ = sym["sa2_guest_fast_base"]
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "A 174ba64 174ba68 10 9",
                      f"W {fn:x} {fsize:x} 9 0 {first16} menu backgrounds"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("1 of 1 patch(es) applied" in sc.err.decode(), "fill: the wrapper applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x174B000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a >= 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    seen = []

    def at_body(u, a, size, d):
        seen.append(struct.unpack("<10f", bytes(u.mem_read(GUEST + 0x1F8004, 40))))
    hk = uc.hook_add(UC_HOOK_CODE, at_body, begin=GAME_BASE + fn + 16, end=GAME_BASE + fn + 16)

    def run(h, args):
        seen.clear()
        uc.mem_write(GUEST + 0x174BA64, struct.pack("<ff", h, 1080.0))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x668227) + struct.pack("<10f", *args))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=100000)
        after = struct.unpack("<10f", bytes(uc.mem_read(GUEST + 0x1F8004, 40)))
        s2 = bytes(uc.mem_read(STATE, 0x1000))
        return (seen[0] if seen else None), after, (u32(s2, 0x908), u32(s2, 0x9A8))

    bg = (-200.0, 0.0, 1040.0, 480.0, 1e-4, 0.0, 0.0, 1.015625, 0.9375, 0.0)
    inside, after, regs = run(2340.0, bg)
    k = 1040.0 / (480.0 * 16 / 9)
    want_v0 = (0.9375 - 0.9375 / k) / 2
    print(f"  fill: inside {[round(x, 4) for x in inside[:9]]}")
    check(inside is not None and abs(inside[7] - 853.3333 / 1024) < 1e-4 and abs(inside[6] - want_v0) < 1e-4
          and abs(inside[8] - (want_v0 + 0.9375 / k)) < 1e-4
          and all(abs(a - b) < 1e-6 for a, b in zip(inside[:6], bg[:6])),
          "fill: the background's u ends at the picture's width and v is cropped about its centre")
    check(tuple(round(x, 6) for x in after) == tuple(round(x, 6) for x in bg),
          "fill: the caller's arguments are put back")
    check(regs == (0x1F8004, 0x668227), "fill: the wrapped function returned normally")
    check("menu background drawn across the screen's 2340 px" in sc.err.decode(), "fill: it says so once")
    hint = (48.0, 440.0, 256.0, 30.0, 5.0, 0.0, 0.0, 1.0, 0.05, 0.0)
    inside, after, regs = run(2340.0, hint)
    check(inside is not None and tuple(round(x, 6) for x in inside) == tuple(round(x, 6) for x in hint),
          "fill: another sprite is left alone")
    bg169 = (-106.66667, 0.0, 853.33333, 480.0, 1e-4, 0.0, 0.0, 0.8333333, 0.9375, 0.0)
    inside, after, regs = run(1920.0, bg169)
    check(inside is not None and tuple(round(x, 4) for x in inside) == tuple(round(x, 4) for x in bg169),
          "fill: on a 16:9 screen the background is left alone")
    uc.hook_del(hk)


def moves_checks(game, patch, check):
    """Kind 11 (set 1, upgrades cleared) around the ret function sub_7f4700 standing in for the
    walker's main: with MainCharObj2[0] the Chao Walker (CharID2 13) and [1] the Dark Chao Walker
    (14), the body runs with them read as Tails's (6) and Eggman's (7) walkers, both are put back
    after, and their upgrades are cleared; a set-0 line is not used, and the story walkers are left
    alone."""
    sym = game.syms
    fn, fsize = sym["sub_7f4700"]
    first16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    fb, _ = sym["sa2_guest_fast_base"]
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "M 3 8 0",
                      "M 3 d 6 1", "M 3 e 7 1", f"W {fn:x} {fsize:x} b 11 {first16} walkers"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("1 of 1 patch(es) applied" in sc.err.decode(), "walkers: the wrapper applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1DE9000, 0x1000000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a == 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    seen = []

    def at_body(u, a, size, d):
        seen.append((bytes(u.mem_read(GUEST + 0x1000003, 1))[0], bytes(u.mem_read(GUEST + 0x1000103, 1))[0]))
    hk = uc.hook_add(UC_HOOK_CODE, at_body, begin=GAME_BASE + fn + 16, end=GAME_BASE + fn + 16)

    def run(c0, c1):
        seen.clear()
        uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", 0x1000000, 0x1000100))
        uc.mem_write(GUEST + 0x1000000, bytes([0, 6, 0, c0]) + bytes(0x20) + struct.pack("<I", 0x1FFF7FFF))
        uc.mem_write(GUEST + 0x1000100, bytes([1, 7, 0, c1]) + bytes(0x20) + struct.pack("<I", 0x1FFF7FFF))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x668227))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=100000)
        after = (bytes(uc.mem_read(GUEST + 0x1000003, 1))[0], bytes(uc.mem_read(GUEST + 0x1000103, 1))[0])
        up = (u32(bytes(uc.mem_read(GUEST + 0x1000024, 4)), 0), u32(bytes(uc.mem_read(GUEST + 0x1000124, 4)), 0))
        s2 = bytes(uc.mem_read(STATE, 0x1000))
        return (seen[0] if seen else None), after, up, (u32(s2, 0x908), u32(s2, 0x9A8))

    inside, after, up, regs = run(13, 14)
    check(inside == (6, 7), f"walkers: the Chao Walkers read as the story walkers inside (got {inside})")
    check(after == (13, 14), f"walkers: put back after (got {after})")
    check(up == (0, 0), f"walkers: their upgrades are cleared (got {up})")
    check(regs == (0x1F8004, 0x668227), "walkers: the wrapped function returned normally")
    inside, after, up, regs = run(6, 8)
    check(inside == (6, 8) and after == (6, 8) and up == (0x1FFF7FFF, 0x1FFF7FFF),
          "walkers: Tails's walker and a set-0 character are left alone")
    uc.hook_del(hk)


def restart_checks(game, patch, check):
    """Kind 12 around the ret function sub_7f4700 standing in for a restart handler, the Y line
    naming LoadSonic as the restart-with-reload handler: before a checkpoint the call reaches
    LoadSonic's entry with pc 0x716E00; after one (CheckpointRecords[0].level the current level)
    it runs its own body."""
    sym = game.syms
    fn, fsize = sym["sub_7f4700"]
    rl, _ = sym["sub_716e00"]
    first16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    fb, _ = sym["sa2_guest_fast_base"]
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "K 1934b88",
                      f"Y {rl:x} 716e00", f"W {fn:x} {fsize:x} c 1 {first16} restart"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("1 of 1 patch(es) applied" in sc.err.decode(), "restart: the wrapper applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1934000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a == 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    seen = []

    def at(u, a, size, d):
        seen.append(("reload", u.reg_read(UC_ARM64_REG_X1)) if a == GAME_BASE + rl else ("own", 0))
        if a == GAME_BASE + rl:
            u.emu_stop()
    h1 = uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + rl, end=GAME_BASE + rl)
    h2 = uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + fn + 16, end=GAME_BASE + fn + 16)

    def run(cp):
        seen.clear()
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", 13))
        uc.mem_write(GUEST + 0x1934B88, struct.pack("<h", cp))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x668227))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=100000)
        return seen[0] if seen else None

    check(run(0x5B) == ("reload", 0x716E00), "restart: before a checkpoint it goes the reload handler's way")
    check(run(13) == ("own", 0), "restart: after a checkpoint it runs its own body")
    uc.hook_del(h1)
    uc.hook_del(h2)


def setload_checks(game, patch, check):
    """Kind 13 around the ret function sub_7f4700 standing in for the stage layout loader, kind 10
    on LoadSonic and Tails's walker (group 2, also in the speed group): for City Escape (13) the
    layout loader picks the character and copies its class's layout over the file the game reads
    (a walker's when the walker is picked), and LoadSonic then plays that pick; a second layout
    file of the same load takes the same pick; a level with no L lines is left alone."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_7f4700"]
    son, sons = sym["sub_716e00"]
    wal, wals = sym["sub_740eb0"]
    f16 = lambda v: game.b[game.vaddr_to_off(v):game.vaddr_to_off(v) + 16].hex()
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "K 1934b88",
                      f"Z {son:x} 716e00", f"Z {wal:x} 740eb0",
                      f"W {son:x} {sons:x} a 0 {f16(son)} random Sonic",
                      f"W {wal:x} {wals:x} a 6 {f16(wal)} random walker",
                      "L d 0 /l/usual.bin /o/set0013_s.bin", "L d 2 /l/walker.bin /o/set0013_s.bin",
                      f"W {fn:x} {fsize:x} d 0 {f16(fn)} layouts"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    sc.files["/l/usual.bin"] = b"USUAL" * 10
    sc.files["/l/walker.bin"] = b"WALKER" * 10
    check("3 of 3 patch(es) applied" in sc.err.decode(), "layouts: the wrappers applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1934000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a == 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    hit = []

    def at(u, a, size, d):
        hit.append(a)
        u.emu_stop()
    hs = [uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + v + 16, end=GAME_BASE + v + 16) for v in (son, wal)]

    def call(native, va, level):
        hit.clear()
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", level))
        uc.mem_write(GUEST + 0x1934B88, struct.pack("<h", 0x5B))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x668227))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + native, STATE, va, 0, limit=100000)
        return hit[0] if hit else None

    # The layout loading first (as a boss's init does): the pick is made there and LoadSonic takes it.
    call(fn, 0x7F4700, 13)
    out = sc.files.get("/o/set0013_s.bin")
    who = call(son, 0x716E00, 13)
    check(out == (b"WALKER" * 10 if who == GAME_BASE + wal + 16 else b"USUAL" * 10),
          "layouts: a layout loaded before the characters is the one for the character then picked")
    # The characters first (a stage's init loads the layout after them), stage after stage.
    agree = True
    seen = set()
    for _ in range(24):
        sc.files.pop("/o/set0013_s.bin", None)
        who = call(son, 0x716E00, 13)
        call(fn, 0x7F4700, 13)
        out = sc.files.get("/o/set0013_s.bin")
        call(fn, 0x7F4700, 13)                       # the hard-mode file of the same load
        out2 = sc.files.get("/o/set0013_s.bin")
        walker = who == GAME_BASE + wal + 16
        seen.add(walker)
        agree &= out == out2 and out == (b"WALKER" * 10 if walker else b"USUAL" * 10)
    check(agree and seen == {True, False},
          f"layouts: each load's layout matches the character just picked (both kinds seen: {seen})")
    sc.files.pop("/o/set0013_s.bin", None)
    call(fn, 0x7F4700, 14)
    check("/o/set0013_s.bin" not in sc.files, "layouts: a stage with no layouts is left alone")
    for h in hs:
        uc.hook_del(h)


def levelgate_checks(game, patch, check):
    """Kind 14 around LoadEmeraldManager's stand-in (the ret function sub_7f4700 would hide the
    difference, so the wrapped function is LoadSonic, stopped at its body): in City Escape (13) the
    call returns at once through the J line's ret function, popping the return address (and with
    the fourth field 1, EAX 0); in Pumpkin Hill (5, an H line) the body runs."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_716e00"]
    rt, _ = sym["sub_6fe430"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", f"J {rt:x} 6fe430",
                      "H 5", "H 7", "H 42 1", f"W {fn:x} {fsize:x} e 1 {f16} gate"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("1 of 1 patch(es) applied" in sc.err.decode(), "level gate: the wrapper applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1934000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a == 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    hit = []

    def at(u, a, size, d):
        hit.append(a)
        u.emu_stop()
    h = uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + fn + 16, end=GAME_BASE + fn + 16)

    def run(level):
        hit.clear()
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", level))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x401234))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        struct.pack_into("<I", st, 0x8A8, 0x55)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x716E00, 0, limit=100000)
        s2 = bytes(uc.mem_read(STATE, 0x1000))
        return bool(hit), (u32(s2, 0x908), u32(s2, 0x9A8), u32(s2, 0x8A8))

    ran, regs = run(13)
    check(not ran and regs == (0x1F8004, 0x401234, 0), f"level gate: in City Escape it returns at once with EAX 0 ({ran}, {[hex(x) for x in regs]})")
    ran, regs = run(5)
    check(ran, "level gate: in Pumpkin Hill the body runs")
    ran, regs = run(0x42)
    check(not ran, "level gate: a level of another set (Final Hazard, set 1) does not open it")
    uc.hook_del(h)


def spindash_checks(game, patch, check):
    """Kind 15 (texture lists traded while Amy plays) and kind 16 (a 16-bit field written after the
    call at EDI's structure), each around the ret function sub_7f4700."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_7f4700"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    for kind in (15, 16):
        lines = ["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "M 3 8 0 0",
                 "S 0 8 a08b94 a08f94", f"Q {fn:x} 36a 2"]
        lines.append(f"W {fn:x} {fsize:x} {kind:x} {0 if kind == 15 else 0x8f8:x} {f16} k{kind}")
        uc, sc = boot(game, patch, ("\n".join(lines) + "\n").encode())
        check("1 of 1 patch(es) applied" in sc.err.decode(), f"kind {kind}: the wrapper applied")
        gb = guest_base_var(uc, game)
        uc.mem_write(gb, struct.pack("<Q", GUEST))
        for a in (0xA08000, 0x1DE9000, 0x1000000, 0x1F0000):
            try:
                uc.mem_map(GUEST + a, 0x10000 if a == 0x1F0000 else 0x2000, UC_PROT_READ | UC_PROT_WRITE)
            except UcError:
                pass
        seen = []

        def at(u, a, size, d):
            seen.append(bytes(u.mem_read(GUEST + 0xA08B94, 8)))
        h = uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + fn + 16, end=GAME_BASE + fn + 16)
        uc.mem_write(GUEST + 0xA08B94, b"SONICEFF")
        uc.mem_write(GUEST + 0xA08F94, b"AMY__EFF")
        uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", 0x1000000, 0))
        uc.mem_write(GUEST + 0x1000000, bytes([0, 0, 0, 8]) + bytes(0x400))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x668227))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        struct.pack_into("<I", st, 0x8F8, 0x1000000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=100000)
        if kind == 15:
            check(seen and seen[0] == b"AMY__EFF" and bytes(uc.mem_read(GUEST + 0xA08B94, 8)) == b"SONICEFF",
                  "kind 15: Amy's effect list stands in for Sonic's during the call, and is put back")
        else:
            check(u32(bytes(uc.mem_read(GUEST + 0x100036A, 4)), 0) & 0xFFFF == 2,
                  "kind 16: field 0x36A of EDI's structure is 2 after the call")
        uc.hook_del(h)


def chargate_checks(game, patch, check):
    """Kind 17 around LoadSonic (stopped at its body): with player 1 Super Sonic (CharID 9) it
    returns at once in City Escape, runs in Final Hazard (H set 1); with Sonic (CharID 0) it runs."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_716e00"]
    rt, _ = sym["sub_6fe430"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", f"J {rt:x} 6fe430",
                      "H 5", "H 42 1", f"W {fn:x} {fsize:x} 11 61 {f16} chargate"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("1 of 1 patch(es) applied" in sc.err.decode(), "character gate: the wrapper applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1934000, 0x1DE9000, 0x1000000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a == 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    hit = []

    def at(u, a, size, d):
        hit.append(a)
        u.emu_stop()
    h = uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + fn + 16, end=GAME_BASE + fn + 16)

    def run(level, cid):
        hit.clear()
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", level))
        uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", 0x1000000, 0))
        uc.mem_write(GUEST + 0x1000000, bytes([0, cid, 0, cid]))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x401234))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x716E00, 0, limit=100000)
        s2 = bytes(uc.mem_read(STATE, 0x1000))
        return bool(hit), (u32(s2, 0x908), u32(s2, 0x9A8))

    ran, regs = run(13, 9)
    check(not ran and regs == (0x1F8004, 0x401234), "character gate: Super Sonic in City Escape skips it")
    check(run(0x42, 9)[0], "character gate: Super Sonic in Final Hazard runs it")
    check(run(13, 0)[0], "character gate: Sonic in City Escape runs it")
    uc.hook_del(h)


def super_checks(game, patch, check):
    """Kinds 18 and 19 with the ret function standing in for LoadMDLFile and LoadCharTextures (it
    hands EAX back, so the model file name's address is read as the model list and the texture
    list name's as the texture list): around the ret function sub_7f4700 standing in for Sonic_Main,
    Sonic (CharID2 0) gets model 328 in CharacterModels, the texture list, his animation list's
    commonest model changed to 328, the super physics and invincibility; the caller's registers are
    as before. With a B line he does so only with the button pressed in a jump with 50 rings, loses
    a ring a second, and is himself again (textures, animation list, physics) at none."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_7f4700"]
    rt, _ = sym["sub_6fe430"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    dfn, dsize = sym["sub_411600"]
    d16 = game.b[game.vaddr_to_off(dfn):game.vaddr_to_off(dfn) + 16].hex()
    for tline in ("", "B 200 32"):
        extra = [tline, "E 0 a08b94 2670e00 CSONEFFTEX"] if tline else []
        plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "H 42 1",
                          "X 0 1100000 1100100 148 9", f"F {rt:x} 6fe430 {rt:x} 6fe430"] + extra
                         + [f"W {fn:x} {fsize:x} 12 0 {f16} supermain"]
                         + ([f"W {dfn:x} {dsize:x} 13 0 {d16} superdisp"] if tline else [])) + "\n"
        uc, sc = boot(game, patch, plan.encode())
        nw = 2 if tline else 1
        check(f"{nw} of {nw} patch(es) applied" in sc.err.decode(), "super: the wrappers applied")
        gb = guest_base_var(uc, game)
        uc.mem_write(gb, struct.pack("<Q", GUEST))
        for a in (0x1934000, 0x1DE9000, 0x1000000, 0x1100000, 0x1200000, 0x1F0000, 0x1730000, 0x1740000,
                  0xA00000, 0x2670000):
            try:
                uc.mem_map(GUEST + a, 0x10000, UC_PROT_READ | UC_PROT_WRITE)
            except UcError:
                pass
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", 13))
        uc.mem_write(GUEST + 0x1934BE0, struct.pack("<h", 16))
        uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", 0x1000000, 0))
        uc.mem_write(GUEST + 0x1DEA6C0, struct.pack("<II", 0x1003000, 0))
        uc.mem_write(GUEST + 0x17391E8 + 0x84 * 9, bytes([0x5A]) * 0x84)
        co2 = bytearray(0x400)
        co2[1], co2[3] = 0, 0
        co2[0xC0:0xC0 + 0x84] = bytes([0x11]) * 0x84
        struct.pack_into("<I", co2, 0x198, 0x1200000)
        struct.pack_into("<I", co2, 0x394, 0xAAAA0000)
        uc.mem_write(GUEST + 0x1000000, bytes(co2))
        uc.mem_write(GUEST + 0x1003000, bytes([6]) + bytes(15))
        uc.mem_write(GUEST + 0x1100000, struct.pack("<iI", 5, 0x1234) + struct.pack("<iI", 328, 0x5678) + struct.pack("<iI", -1, 0))
        lst = bytearray(16 * 203)
        for i in range(203):
            struct.pack_into("<hh", lst, 16 * i, i, 6 if i == 30 else 0)
        uc.mem_write(GUEST + 0x1200000, bytes(lst))

        def frame(rings, press, frames=1):
            uc.mem_write(GUEST + 0x174B028, struct.pack("<h", rings))
            uc.mem_write(GUEST + 0x174B03C, struct.pack("<I", frames))
            uc.mem_write(GUEST + 0x1DEFC10, struct.pack("<I", press))
            uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x401234))
            st = bytearray(0x1000)
            struct.pack_into("<I", st, 0x908, 0x1F8000)
            struct.pack_into("<I", st, 0x8A8, 0x77)
            struct.pack_into("<I", st, 0x8C8, 0x88)
            uc.mem_write(STATE, bytes(st))
            run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=400000)
            s2 = bytes(uc.mem_read(STATE, 0x1000))
            check((u32(s2, 0x908), u32(s2, 0x9A8)) == (0x1F8004, 0x401234), "super: the wrapped function returned normally")
            tex = u32(bytes(uc.mem_read(GUEST + 0x1000394, 4)), 0)
            l2 = bytes(uc.mem_read(GUEST + 0x1200000, 16 * 203))
            models = [struct.unpack_from("<h", l2, 16 * i + 2)[0] for i in range(203)]
            phys = bytes(uc.mem_read(GUEST + 0x10000C0, 0x84))
            pw = struct.unpack("<H", bytes(uc.mem_read(GUEST + 0x1000010, 2)))[0]
            r = struct.unpack("<h", bytes(uc.mem_read(GUEST + 0x174B028, 2)))[0]
            return tex, models, phys, pw, r

        def is_super(t):
            tex, models, phys, pw, _ = t
            return (tex == 0x1100100 and models.count(328) == 202 and models[30] == 6
                    and phys == bytes([0x5A]) * 0x84 and pw & 0x8000)

        def is_self(t):
            tex, models, phys, pw, _ = t
            if not (tex == 0xAAAA0000 and models.count(0) == 202 and phys == bytes([0x11]) * 0x84 and not pw & 0x8000):
                print("    is", hex(tex), models.count(0), models.count(328), phys[:4].hex(), hex(pw), t[4])
            return tex == 0xAAAA0000 and models.count(0) == 202 and phys == bytes([0x11]) * 0x84 and not pw & 0x8000

        if not tline:
            t = frame(0, 0)
            cm = struct.unpack("<II", bytes(uc.mem_read(GUEST + 0x1DE9620 + 8 * 328, 8)))
            check(cm == (328, 0x5678), f"super: model 328 put in CharacterModels ({cm})")
            check(is_super(t), "super (always): textures, body model 328 (the ball keeps its own), physics, invincibility")
        else:
            check(is_self(frame(60, 0)), "super (transform): nothing without the button")
            check(is_self(frame(40, 0x200)), "super (transform): nothing with 40 rings")
            t = frame(60, 0x200, 7)
            check(is_super(t) and t[4] == 60, "super (transform): the button in a jump with 60 rings transforms")
            t = frame(60, 0, 120)
            check(is_super(t) and t[4] == 59, f"super (transform): a ring a second ({t[4]})")
            check(is_self(frame(1, 0, 180)), "super (transform): himself again at no rings")
            check(is_super(frame(55, 0x200, 7)), "super (transform): again")
            check(is_self(frame(55, 0x200, 8)), "super (transform): the button in a jump turns back")
            # the effect list: the mod's (the stand-in loader hands back the name's address) while super
            uc.mem_write(GUEST + 0xA08B94, struct.pack("<II", 0x5000, 7))
            frame(55, 0x200, 7)
            eff = bytes(uc.mem_read(GUEST + 0xA08B94, 8))
            check(eff == b"CSONEFFT", f"super (transform): Sonic's effect list is the mod's while super ({eff})")
            frame(55, 0x200, 8)
            eff = struct.unpack("<II", bytes(uc.mem_read(GUEST + 0xA08B94, 8)))
            check(eff == (0x5000, 7), f"super (transform): and his own again after ({eff})")
            # kind 19: the ball keeps the super textures when both lists name the same textures
            def texlist(at, names):
                uc.mem_write(GUEST + at, struct.pack("<II", at + 0x40, len(names)))
                for i, n in enumerate(names):
                    uc.mem_write(GUEST + at + 0x40 + 12 * i, struct.pack("<III", at + 0x100 + 0x20 * i, 0, 0))
                    uc.mem_write(GUEST + at + 0x100 + 0x20 * i, n.encode() + b"\0")
            for old_names, same in ((["S_WIND", "stx_00"], True), (["s_wind", "stx_01"], False)):
                texlist(0x1000800, old_names)
                texlist(0x1100100, ["s_wind", "stx_00"])
                uc.mem_write(GUEST + 0x1000394, struct.pack("<I", 0x1000800))
                frame(55, 0x200, 7)                                    # super: the old list is kept aside
                uc.mem_write(GUEST + 0x1000178, struct.pack("<H", 30))   # the ball's animation (model 6)
                uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x401234))
                st = bytearray(0x1000)
                struct.pack_into("<I", st, 0x908, 0x1F8000)
                uc.mem_write(STATE, bytes(st))
                run_function(uc, GAME_BASE + dfn, STATE, 0x411600, 0, limit=400000)
                said = sc.err.decode()
                want = "name the same textures" if same else "differ: the ball is drawn with the old ones"
                check(f"super: texture lists 0x1000800 and 0x1100100 {want}" in said,
                      f"super (display): lists {'alike' if same else 'unlike'} -> {want}")
                tex = u32(bytes(uc.mem_read(GUEST + 0x1000394, 4)), 0)
                check(tex == 0x1100100, f"super (display): the super list is the player's after the call ({hex(tex)})")
                uc.mem_write(GUEST + 0x1000178, struct.pack("<H", 0))
                frame(55, 0x200, 8)                                    # back, for the next round
        print("   ", [l for l in sc.err.decode().splitlines() if "super:" in l][:4])


def flash_voice_checks(game, patch, check):
    """The D line: Y in a jump with 50 rings starts a green flash (ScreenFadeARGB) and plays cue 18
    (the O line's sound function, the ret function standing in, called with ESI 0x3012); the form
    comes at frame 8, the flash is gone at the D line's last frame and cue 19 plays then. Kind 20
    around a ret-shaped function standing in for PlayVoice: a voice number of an N line returns at
    once with EAX 1 while player 1 is Sonic, others run the function; each number is logged once."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_7f4700"]
    vfn, vsize = sym["sub_46f1c1"]
    rt, _ = sym["sub_6fe430"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    v16 = game.b[game.vaddr_to_off(vfn):game.vaddr_to_off(vfn) + 16].hex()
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "H 42 1",
                      "X 0 1100000 1100100 148 9", f"F {rt:x} 6fe430 {rt:x} 6fe430", "B 200 32",
                      f"O {rt:x} 6fe430", "D 12 13 3c 40ff60", "N 618 14", f"J {rt:x} 6fe430",
                      f"W {fn:x} {fsize:x} 12 0 {f16} supermain",
                      f"W {vfn:x} {vsize:x} 14 0 {v16} voice"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("2 of 2 patch(es) applied" in sc.err.decode(), "flash: the wrappers applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1934000, 0x1DE9000, 0x1000000, 0x1100000, 0x1200000, 0x1F0000, 0x1730000, 0x1740000, 0x1710000):
        try:
            uc.mem_map(GUEST + a, 0x10000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", 13))
    uc.mem_write(GUEST + 0x1934BE0, struct.pack("<h", 16))
    uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", 0x1000000, 0))
    uc.mem_write(GUEST + 0x1DEA6C0, struct.pack("<II", 0x1003000, 0))
    co2 = bytearray(0x400)
    struct.pack_into("<I", co2, 0x198, 0x1200000)
    struct.pack_into("<I", co2, 0x394, 0xAAAA0000)
    uc.mem_write(GUEST + 0x1000000, bytes(co2))
    uc.mem_write(GUEST + 0x1003000, bytes([6]) + bytes(15))
    uc.mem_write(GUEST + 0x1100000, struct.pack("<iI", 328, 0x5678) + struct.pack("<iI", -1, 0))
    lst = bytearray(16 * 203)
    for i in range(203):
        struct.pack_into("<hh", lst, 16 * i, i, 6 if i == 30 else 0)
    uc.mem_write(GUEST + 0x1200000, bytes(lst))
    esis = []

    def call(f, va, num=None, press=0, rings=60):
        uc.mem_write(GUEST + 0x174B028, struct.pack("<h", rings))
        uc.mem_write(GUEST + 0x174B03C, struct.pack("<I", 7))
        uc.mem_write(GUEST + 0x1DEFC10, struct.pack("<I", press))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<II", 0x401234, num or 0))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        struct.pack_into("<I", st, 0x8A8, 0x77)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + f, STATE, va, 0, limit=400000)
        return bytes(uc.mem_read(STATE, 0x1000))

    alphas, forms = [], []
    call(fn, 0x7F4700, press=0x200)
    for t in range(70):
        call(fn, 0x7F4700)
        alphas.append(bytes(uc.mem_read(GUEST + 0x171CDA0, 4))[3])
        forms.append(u32(bytes(uc.mem_read(GUEST + 0x1000394, 4)), 0) == 0x1100100)
    col = bytes(uc.mem_read(GUEST + 0x171CDA0, 4))
    check(max(alphas) == 0xB0 and alphas[7] == 0xB0, f"flash: up to 0xB0 by frame 8 ({alphas[:10]})")
    check(forms.index(True) == 7, f"flash: the form comes at the brightest (frame {forms.index(True) + 1})")
    check(alphas[-1] == 0 and col == b"\0\0\0\0", f"flash: gone at the end ({alphas[50:62]})")
    said = sc.err.decode()
    check(said.count("is the super form") == 1, "flash: one transformation")
    s2 = call(vfn, 0x46F1C1, num=0x618)
    check(u32(s2, 0x8A8) == 1 and u32(s2, 0x908) == 0x1F8004, f"voice: voice 1560 for Sonic is the cue (EAX {u32(s2, 0x8A8)})")
    s3 = call(vfn, 0x46F1C1, num=0x619)
    check(u32(s3, 0x908) == 0x1F8004, "voice: another number runs the function")
    said = sc.err.decode()
    check("voice: the game asks for voice 1560" in said and "voice: the game asks for voice 1561" in said,
          "voice: each number logged")
    print("   ", [l for l in said.splitlines() if "voice:" in l or "flash" in l][:4])


def hunt_checks(game, patch, check):
    """The I line: around Sonic_Main (kind 18, here the ret function sub_7f4700) in a level of H set
    2, a player that is not a hunter gets the emerald manager (the ret function standing in) once
    per character object; a hunter (CharID 4) gets none; another level none."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_7f4700"]
    rt, _ = sym["sub_6fe430"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", f"I {rt:x} 6fe430",
                      "H 10 2", f"W {fn:x} {fsize:x} 12 0 {f16} hunt"]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    check("1 of 1 patch(es) applied" in sc.err.decode(), "hunt: the wrapper applied")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x1934000, 0x1DE9000, 0x1000000, 0x1F0000):
        try:
            uc.mem_map(GUEST + a, 0x10000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    uc.mem_write(GUEST + 0x1934BE0, struct.pack("<h", 16))

    def frame(level, co2, charid):
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", level))
        uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", co2, 0))
        uc.mem_write(GUEST + co2, bytes([0, charid, 0, charid]))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x401234))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=400000)
        return sc.err.decode().count("hunt: the emerald manager loaded")

    check(frame(0xD, 0x1000000, 0) == 0, "hunt: not in City Escape")
    check(frame(0x10, 0x1000000, 0) == 1, "hunt: Sonic in Wild Canyon gets the emerald manager")
    check(frame(0x10, 0x1000000, 0) == 1, "hunt: once per character object")
    check(frame(0x10, 0x1000400, 4) == 1, "hunt: none for a hunter")
    check(frame(0x10, 0x1000800, 6) == 2, "hunt: a walker's new object gets it again")


AURA_FNS = (0x411600, 0x44C350, 0x427470, 0x4274E0, 0x4275B0, 0x427680, 0x427750, 0x42D340, 0x42EB30,
            0x446D00, 0x446D10, 0x446D20, 0x44B2E0, 0x433D40, 0x42D650)


def edge_rows(game, pairs):
    """The edge table rows (offsets from the load address) of jcc-taken {target, source} pairs, found
    in PT_GNU_RELRO as NativePatch.gameFixes finds them."""
    relro = [p for p in game.ph if p[0] == 0x6474E552][0]
    vaddr, off, filesz = relro[3], relro[2], relro[5]
    d = game.b[off:off + filesz]
    out = {}
    for t, src in pairs:
        for o in range(0, len(d) - 24, 8):
            if u32(d, o) == t and u32(d, o + 4) == src and d[o + 8] == 2 and d[o + 9] == 0:
                out[t] = vaddr + o
                break
    return out


def edge_checks(game, patch, check):
    """e lines: Pyramid Cave's branch at 0x7059AB into 0x7059AF, the rest of sub_7059a0 lifted as
    sub_7059af. With the row allowed the game's own __remill_missing_block (policy 2, the
    generated edge table) dispatches into sub_7059af with the state the branch left (ECX pushed,
    EIP 0x7059AF); without it the runtime traps (sa2_trap_note, which calls the C library here).
    A row that is not the plan's is left alone."""
    sym = game.syms
    rows = edge_rows(game, [(0x7059AF, 0x7059AB)])
    check(0x7059AF in rows, "game fix: the edge table has Pyramid Cave's row (jcc-taken, not allowed)")
    row = rows[0x7059AF]
    fb, _ = sym["sa2_guest_fast_base"]
    a0, _ = sym["sub_7059a0"]
    af, _ = sym["sub_7059af"]
    pol, _ = sym["sa2_missing_block_dispatch"]
    reg, _ = sym["sa2_mb_register_generated"]

    def run(lines):
        plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000"] + lines) + "\n"
        uc, sc = boot(game, patch, plan.encode())
        bring_up(uc, game)
        uc.reg_write(UC_ARM64_REG_X30, STOP)
        uc.emu_start(GAME_BASE + reg, STOP, count=1_000_000)
        uc.mem_write(GAME_BASE + pol, struct.pack("<i", 2))
        gb = guest_base_var(uc, game)
        uc.mem_write(gb, struct.pack("<Q", GUEST))
        for a in (0x1AF0000, 0x1F0000):
            try:
                uc.mem_map(GUEST + a, 0x10000, UC_PROT_READ | UC_PROT_WRITE)
            except UcError:
                pass
        uc.mem_write(GUEST + 0x1AF00CC, struct.pack("<I", 0x1234))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x704DA0))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        struct.pack_into("<I", st, 0x8C8, 0x77)
        uc.mem_write(STATE, bytes(st))
        hit = []

        def at(u, a, size, d):
            hit.append(a)
            u.emu_stop()
        h = uc.hook_add(UC_HOOK_CODE, at, begin=GAME_BASE + af, end=GAME_BASE + af)
        err = None
        try:
            run_function(uc, GAME_BASE + a0, STATE, 0x7059A0, 0, limit=200000, want_trace=False)
        except Exception as e:  # the trap path reaches the C library
            err = str(e)
        uc.hook_del(h)
        s2 = bytes(uc.mem_read(STATE, 0x1000))
        allow = bytes(uc.mem_read(GAME_BASE + row + 9, 1))[0]
        return uc, sc, bool(hit), err, (u32(s2, 0x908), u32(s2, 0x8C8), u32(s2, 0x9A8)), allow

    uc, sc, hit, err, regs, allow = run([])
    check(not hit and allow == 0, f"game fix: without the e line the branch is refused ({err})")
    uc, sc, hit, err, regs, allow = run([f"e {row:x} 7059af 7059ab", f"e {row + 24:x} 7059af 7059ab",
                                         f"e {row:x} 7059af 7059ab"])
    log = sc.err.decode()
    check(allow == 1, "game fix: the row's allow byte is set")
    check("is followed" in log and "is not what the plan expects" in log and "already followed" in log,
          "game fix: the log says followed, a row not the plan's is left alone, a repeat is already followed")
    check(hit and regs == (0x1F7FFC, 0x1234, 0x7059AF),
          f"game fix: the branch dispatches into sub_7059af with ECX pushed ({hit}, {[hex(x) for x in regs]})")
    pushed = u32(bytes(uc.mem_read(GUEST + 0x1F7FFC, 4)), 0)
    check(pushed == 0x77, f"game fix: the old ECX is on the guest stack ({pushed:#x})")
    prot = [m for m in sc.mprotects if m[2] in (1, 3)]
    check(len(prot) == 2 and prot[0][2] == 3 and prot[1][2] == 1 and prot[0][0] == prot[1][0],
          "game fix: the edge table's page is opened R+W and put back to R")


def aura_checks(game, patch, check):
    """The a and c lines and kind 21, with the ret function sub_6fe430 standing in for every game
    function the aura uses (told apart by the guest address each call is given) and sub_411600 for
    Super_DispDelay3: becoming the super form loads SSONEFFTEX (LoadTextureList, ECX the name, EDX
    the list) and gives the player's task the display as its DisplaySub_Delayed4; the display, for
    that task outside Final Hazard, draws the aura through the game's calls (position, Z/X/0x8000-Y
    turns, scale, 3 down, a c line's tint as a constant material, the model with ProcessChunkModel)
    with SSONEFFTEX in use and a texture number 3-8, then puts the matrix stack, texture list and
    number back and returns without the game's body; in Final Hazard the body runs; another task,
    the stage's end (animation 54) or no c line draw less or nothing; back from the super form the
    task's own display returns. A plan missing an a line leaves the display alone."""
    sym = game.syms
    fb, _ = sym["sa2_guest_fast_base"]
    fn, fsize = sym["sub_7f4700"]
    rt, _ = sym["sub_6fe430"]
    dfn, dsize = sym["sub_411600"]
    f16 = game.b[game.vaddr_to_off(fn):game.vaddr_to_off(fn) + 16].hex()
    d16 = game.b[game.vaddr_to_off(dfn):game.vaddr_to_off(dfn) + 16].hex()
    OBJ, D1, CO2, M, RI = 0x1001000, 0x1003000, 0x1000000, 0x1A50000, 0x2675000
    f32 = lambda x: struct.unpack("<I", struct.pack("<f", x))[0]

    def setup(lines, n_applied):
        a_lines = [f"a {i:x} {0 if i in (0, 14) else rt:x} {va:x}" for i, va in enumerate(AURA_FNS)]
        a_lines = [l for l in a_lines if not any(l.startswith(f"a {d:x} ") for d in lines.get("drop", ()))]
        plan = "\n".join(["opensa2-patch 1", f"G {fb:x} 20000000", "C 1934b70 1de9600 1 5b", "H 42 1",
                          "X 0 1100000 1100100 148 9", "X 1 1100000 1100100 148 a", f"F {rt:x} 6fe430 {rt:x} 6fe430",
                          f"J {rt:x} 6fe430"] + lines.get("extra", []) + a_lines
                         + [f"W {fn:x} {fsize:x} 12 0 {f16} supermain", f"W {dfn:x} {dsize:x} 15 0 {d16} aura"]) + "\n"
        uc, sc = boot(game, patch, plan.encode())
        check(f"{n_applied} of 2 patch(es) applied" in sc.err.decode(), f"aura: {n_applied} of 2 wrappers applied")
        gb = guest_base_var(uc, game)
        uc.mem_write(gb, struct.pack("<Q", GUEST))
        for a in (0x1934000, 0x1DE9000, 0x1DEA000, 0x1000000, 0x1100000, 0x1200000, 0x1F0000, 0x1700000, 0x1730000,
                  0x1740000, 0x1A50000, 0x2670000, 0xA00000):
            try:
                uc.mem_map(GUEST + a, 0x10000, UC_PROT_READ | UC_PROT_WRITE)
            except UcError:
                pass
        uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", 13))
        uc.mem_write(GUEST + 0x1934BE0, struct.pack("<h", 16))
        uc.mem_write(GUEST + 0x1DE9600, struct.pack("<II", CO2, 0))
        uc.mem_write(GUEST + 0x1DEA6C0, struct.pack("<II", D1, 0))
        uc.mem_write(GUEST + 0x1DEA6E0, struct.pack("<II", OBJ, 0))
        obj = bytearray(0x50)
        struct.pack_into("<I", obj, 0x28, 0xABCDEF)
        struct.pack_into("<I", obj, 0x34, D1)
        struct.pack_into("<I", obj, 0x40, CO2)
        uc.mem_write(GUEST + OBJ, bytes(obj))
        d1 = bytearray(0x40)
        d1[0] = 6                                                     # in a jump
        struct.pack_into("<3i3f3f", d1, 8, 0x100, 0x200, 0x300, 10.0, 20.0, 30.0, 1.0, 1.5, 2.0)
        uc.mem_write(GUEST + D1, bytes(d1))
        co2 = bytearray(0x400)
        struct.pack_into("<I", co2, 0x198, 0x1200000)
        struct.pack_into("<I", co2, 0x394, 0xAAAA0000)
        uc.mem_write(GUEST + CO2, bytes(co2))
        uc.mem_write(GUEST + 0x1100000, struct.pack("<iI", 328, 0x5678) + struct.pack("<iI", -1, 0))
        uc.mem_write(GUEST + 0x1200000, bytes(16 * 203))
        # SSONEFFTEX: 20 textures, loaded; the aura's texture number; the matrix stack; RenderInfo
        uc.mem_write(GUEST + 0x170ACD4, struct.pack("<II", 0x1705000, 20))
        for i in range(20):
            uc.mem_write(GUEST + 0x1705000 + 12 * i, struct.pack("<III", 0x1706000, 0, 0x1707000 + i))
        uc.mem_write(GUEST + 0x170ACEE, bytes([0x55]))
        uc.mem_write(GUEST + M, struct.pack("<12f", *range(1, 13)))
        uc.mem_write(GUEST + 0x1A557FC, struct.pack("<I", M))
        uc.mem_write(GUEST + 0x2670588, struct.pack("<I", M + 0x1000))
        uc.mem_write(GUEST + 0x2670544, struct.pack("<I", RI))
        uc.mem_write(GUEST + RI + 0x20, struct.pack("<I", 0x777777))
        calls = []

        def seen(u, addr, size, data):
            st = bytes(u.mem_read(STATE, 0x1000))
            esp = u32(st, 0x908)
            args = struct.unpack("<4I", bytes(u.mem_read(GUEST + esp + 4, 16)))
            calls.append({"va": u.reg_read(UC_ARM64_REG_X1), "eax": u32(st, 0x8A8), "ebx": u32(st, 0x8B8),
                          "ecx": u32(st, 0x8C8), "edx": u32(st, 0x8D8), "args": args,
                          "tex": u32(bytes(u.mem_read(GUEST + RI + 0x20, 4)), 0),
                          "anim": bytes(u.mem_read(GUEST + 0x170ACEE, 1))[0],
                          "m30": bytes(u.mem_read(GUEST + M + 0x30, 0x30))})
        uc.hook_add(UC_HOOK_CODE, seen, begin=GAME_BASE + rt, end=GAME_BASE + rt)
        return uc, sc, calls

    def aura_calls(calls):   # leaving out the J line's ret (the same stand-in) and the loaders
        return [c for c in calls if c["va"] in set(AURA_FNS[2:14])]

    def frame(uc, rings=60, press=0, frames=7):
        uc.mem_write(GUEST + 0x174B028, struct.pack("<h", rings))
        uc.mem_write(GUEST + 0x174B03C, struct.pack("<I", frames))
        uc.mem_write(GUEST + 0x1DEFC10, struct.pack("<I", press))
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<I", 0x401234))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + fn, STATE, 0x7F4700, 0, limit=400000)

    def display(uc, task=OBJ, body=0):   # body: the stand-in's own body (a `ret 4`) is to run
        uc.mem_write(GUEST + 0x1F8000, struct.pack("<II", 0x401234, task))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x1F8000)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + dfn, STATE, 0x411600, 0, limit=400000)
        s2 = bytes(uc.mem_read(STATE, 0x1000))
        return (u32(s2, 0x908), u32(s2, 0x9A8)) == (0x1F8004 + 4 * body, 0x401234)

    delayed4 = lambda uc: u32(bytes(uc.mem_read(GUEST + OBJ + 0x28, 4)), 0)

    # Always super, Sonic, tinted green.
    uc, sc, calls = setup({"extra": ["c 0 40ff60"]}, 2)
    frame(uc)
    load = [c for c in calls if c["va"] == 0x44C350]
    check(len(load) == 1 and (load[0]["ecx"], load[0]["edx"]) == (0x8BAA1C, 0x170ACD4),
          f"aura: SSONEFFTEX loaded with the super form ({[(hex(c['ecx']), hex(c['edx'])) for c in load]})")
    check(delayed4(uc) == 0x411600, f"aura: the task's DisplaySub_Delayed4 is the display ({hex(delayed4(uc))})")
    calls.clear()
    check(display(uc), "aura: the display returns normally")
    check(calls and calls[-1]["va"] == 0x6FE430, "aura: and through the bare ret, not the game's body")
    calls[:] = aura_calls(calls)
    got = [c["va"] for c in calls]
    want = [0x427470, 0x42D340, 0x427680, 0x4274E0, 0x4275B0, 0x427750, 0x427470, 0x42D340,
            0x446D00, 0x446D20, 0x44B2E0, 0x42EB30, 0x433D40, 0x446D10]
    check(got == want, f"aura: the game's calls in order ({[hex(v) for v in got]})")
    if got == want:
        c = calls
        check(c[0]["eax"] == M + 0x30 and c[0]["args"][:3] == (f32(10), f32(20), f32(30))
              and c[0]["m30"] == bytes(uc.mem_read(GUEST + M, 0x30)), "aura: pushed, then at the character's position")
        check([(x["ebx"], x["args"][0]) for x in c[2:5]] == [(M + 0x30, 0x300), (M + 0x30, 0x100), (M + 0x30, 0x7E00)],
              "aura: turned Z, X, then 0x8000 - Y")
        check(c[5]["args"][:3] == (f32(1.0), f32(1.5), f32(2.0)), "aura: scaled as the character")
        check(c[6]["eax"] == M + 0x60 and c[6]["args"][:3] == (0, f32(-3.0), 0), "aura: pushed again, 3 down")
        check(c[9]["eax"] == 0x10, "aura: constant material on")
        check(c[10]["args"] == (f32(1.0), f32(0x40 / 255), f32(1.0), f32(0x60 / 255)), "aura: tinted 40ff60")
        check(c[11]["args"][:2] == (0x170B47C, 0x42D650), "aura: the model drawn with ProcessChunkModel")
        check(c[11]["tex"] == 0x170ACD4 and 3 <= c[11]["anim"] <= 8,
              f"aura: SSONEFFTEX in use, texture {c[11]['anim']} (3-8 for Sonic)")
    after = (u32(bytes(uc.mem_read(GUEST + 0x1A557FC, 4)), 0), u32(bytes(uc.mem_read(GUEST + RI + 0x20, 4)), 0),
             bytes(uc.mem_read(GUEST + 0x170ACEE, 1))[0])
    check(after == (M, 0x777777, 0x55), f"aura: matrix stack, texture list and number put back ({after})")
    check("aura: player 0 glows, tinted 0x40ff60" in sc.err.decode(), "aura: said once")
    calls.clear()
    check(display(uc, 0x1001800) and not aura_calls(calls), "aura: another task gets nothing")
    uc.mem_write(GUEST + CO2 + 0x178, struct.pack("<h", 54))
    calls.clear()
    check(display(uc) and not aura_calls(calls), "aura: none at the stage's end (animation 54)")
    uc.mem_write(GUEST + CO2 + 0x178, struct.pack("<h", 0))
    uc.mem_write(GUEST + 0x1934B70, struct.pack("<h", 0x42))
    calls.clear()
    check(display(uc, body=1) and not aura_calls(calls) and 0x6FE430 not in {c["va"] for c in calls},
          "aura: in Final Hazard the game's own body runs")

    # Transforming, Shadow, no tint; back from the super form.
    uc, sc, calls = setup({"extra": ["B 200 32"]}, 2)
    uc.mem_write(GUEST + CO2 + 3, bytes([1]))
    frame(uc)
    check(delayed4(uc) == 0xABCDEF, "aura (transform): nothing before transforming")
    frame(uc, 60, 0x200)
    check(delayed4(uc) == 0x411600, "aura (transform): the display on transforming")
    calls.clear()
    display(uc)
    calls[:] = aura_calls(calls)
    got = [c["va"] for c in calls]
    check(got == [0x427470, 0x42D340, 0x427680, 0x4274E0, 0x4275B0, 0x427750, 0x427470, 0x42D340, 0x42EB30],
          f"aura (transform): no tint without a c line ({[hex(v) for v in got]})")
    check(calls and 9 <= calls[-1]["anim"] <= 14, "aura (transform): texture 9-14 for Shadow")
    frame(uc, 55, 0x200, 8)
    check(delayed4(uc) == 0xABCDEF, f"aura (transform): the task's own display back after ({hex(delayed4(uc))})")

    # An a line missing: the display is not wrapped, so the task is not given it.
    uc, sc, calls = setup({"drop": (8,)}, 1)
    frame(uc)
    check(delayed4(uc) == 0xABCDEF, "aura: a plan missing an a line leaves the task's display alone")
    print("   ", [l for l in sc.err.decode().splitlines() if "aura" in l][:3])


def layout_checks(game, patch, check):
    """Kind 3 (laid out at 16:9) around sub_86eac0, which calls the ret function sub_6fe430,
    itself traced (kind 4): inside, HorizontalResolution reads 1920 for a 2340x1080 screen and the
    runtime's HUD guard state says "swapped, real width 2340"; after, all of it is as before. And
    the drawing log: shut until a kind-8 (or kind-3) wrapper runs, a budget of lines per function
    as the screen opens and another a few seconds later, a sprite (kind 5), memory at a pointer
    argument (kind 7) and the dispatch (kind 6)."""
    sym = game.syms
    caller, csize = sym["sub_86eac0"]
    callee, esize = sym["sub_6fe430"]
    gate, gsize = sym["sub_863dd0"]
    ptrf, psize = sym["sub_7f4700"]
    ptrd, pdsize = sym["sub_8704f0"]
    gate2, g2size = sym["sub_870580"]

    def first16(v):
        return game.b[game.vaddr_to_off(v):game.vaddr_to_off(v) + 16].hex()

    hud, _ = sym["g_hud43"]
    sw, swsize = sym["g_hud43_swapped"]
    real, _ = sym["g_hud43_real"]
    fb, _ = sym["sa2_guest_fast_base"]
    plan = "\n".join([
        "opensa2-patch 1",
        f"G {fb:x} 20000000",
        "A 174ba64 174ba68 10 9",
        f"U {hud:x} {sw:x} {swsize:x} {real:x}",
        "V 174ba64 2",
        f"W {caller:x} {csize:x} 3 0 {first16(caller)} a screen laid out at 16:9",
        f"W {callee:x} {esize:x} 4 4 {first16(callee)} a traced call",
        f"W {sym['sub_66b6d0'][0]:x} {sym['sub_66b6d0'][1]:x} 5 1 {first16(sym['sub_66b6d0'][0])} a traced sprite draw",
        f"W {sym['sa2_ft_dispatch'][0]:x} {sym['sa2_ft_dispatch'][1]:x} 6 0 {first16(sym['sa2_ft_dispatch'][0])} dispatch",
        f"W {gate:x} {gsize:x} 8 0 {first16(gate)} the screen's own function",
        f"W {gate2:x} {g2size:x} 8 0 {first16(gate2)} another screen's function",
        f"W {ptrf:x} {psize:x} 7 8a8 {first16(ptrf)} a draw with its points at EAX",
        f"W {ptrd:x} {pdsize:x} 7 1001 {first16(ptrd)} a draw with a structure on the stack",
        "W 1000 10 7 9f1 00000000000000000000000000000000 a refused register offset",
    ]) + "\n"
    uc, sc = boot(game, patch, plan.encode())
    log = sc.err.decode()
    print(log, end="")
    check("8 of 8 patch(es) applied" in log, "log: the eight wrappers applied (the bad line refused)")
    gb = guest_base_var(uc, game)
    uc.mem_write(gb, struct.pack("<Q", GUEST))
    for a in (0x174B000, 0x1F0000, 0x200000, 0x210000, 0x300000):
        try:
            uc.mem_map(GUEST + a, 0x10000 if a >= 0x1F0000 else 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
    uc.mem_write(GUEST + 0x174BA64, struct.pack("<ff", 2340.0, 1080.0))

    def call(fn_va, native, regs=None, ret=0x401234):
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x200000)
        struct.pack_into("<I", st, 0x918, 0x210100)
        for off, v in (regs or {}).items():
            struct.pack_into("<I", st, off, v)
        uc.mem_write(STATE, bytes(st))
        uc.mem_write(GUEST + 0x200000, struct.pack("<I", ret))
        run_function(uc, GAME_BASE + native, STATE, fn_va, 0, limit=100000)
        return bytes(uc.mem_read(STATE, 0x1000))

    # The log is shut until the screen's own function runs.
    call(0x6FE430, callee)
    check("trace 0x6fe430" not in sc.err.decode(), "log: shut before the screen opens")
    sc.now_ms += 10
    s2 = call(0x863DD0, gate, ret=0x401240)
    out = sc.err.decode()
    check("trace: 0x863dd0 ran, so its screen is open" in out and "enter 0x863dd0 @0\n" in out,
          "gate: its first run opens the log, and says it entered")
    check("vars at open 0x174ba64 0x45124000 0x44870000" in out, "gate: the plan's guest memory is logged as it opens")
    check(u32(s2, 0x908) == 0x200004 and u32(s2, 0x9A8) == 0x401240, "gate: the wrapped function returned normally")
    sc.now_ms += 7
    call(0x6FE430, callee, {0x8A8: 0x11, 0x8C8: 0x22, 0x8D8: 0x33})
    check("trace 0x6fe430 ret 0x401234 eax 0x11 ecx 0x22 edx 0x33 args 0x0 0x0 0x0 0x0 @7\n" in sc.err.decode(),
          "trace: registers, stack words and the time since the screen opened")

    seen = []

    def at_body(u, a, size, d):
        h = u32(bytes(u.mem_read(GUEST + 0x174BA64, 4)), 0)
        seen.append((h, struct.unpack("<i", bytes(u.mem_read(GAME_BASE + hud, 4)))[0],
                     bytes(u.mem_read(GAME_BASE + sw, 1))[0], u32(bytes(u.mem_read(GAME_BASE + real, 4)), 0)))
    hk = uc.hook_add(UC_HOOK_CODE, at_body, begin=GAME_BASE + callee + 16, end=GAME_BASE + callee + 16)

    def run(h, v):
        seen.clear()
        uc.mem_write(GUEST + 0x174BA64, struct.pack("<ff", h, v))
        s2 = call(0x86EAC0, caller, {0x8A8: 0x1234})
        after = (u32(bytes(uc.mem_read(GUEST + 0x174BA64, 4)), 0),
                 struct.unpack("<i", bytes(uc.mem_read(GAME_BASE + hud, 4)))[0],
                 bytes(uc.mem_read(GAME_BASE + sw, 1))[0])
        return seen[:], after, (u32(s2, 0x908), u32(s2, 0x9A8))

    f = lambda x: struct.unpack("<I", struct.pack("<f", x))[0]
    before_hud = struct.unpack("<i", bytes(uc.mem_read(GAME_BASE + hud, 4)))[0]
    inside, after, regs = run(2340.0, 1080.0)
    check(inside and inside[0][0] == f(1920.0), f"layout: inside, the width reads 1920 ({inside[:1]})")
    check(inside and inside[0][1] == 1 and inside[0][2] == 1 and inside[0][3] == f(2340.0),
          "layout: inside, the runtime's guards are told the real width (g_hud43 on, swapped, real 2340)")
    check(after == (f(2340.0), before_hud, 0), f"layout: after, width {after[0]:#x}, g_hud43 {after[1]} (was "
          f"{before_hud}), swapped {after[2]}")
    check(regs == (0x200004, 0x401234), "layout: the wrapped function returned normally")
    log2 = sc.err.decode()
    check("laid out at 16:9: width 2340 -> 1920" in log2, "layout: it says so once")
    check("enter 0x86eac0" in log2, "layout: and that it entered")
    check("trace 0x6fe430 ret 0x86eacd" in log2 and " in 0x86eac0\n" in log2,
          "trace: the call inside is logged with its return address and the screen function it is in")
    inside, after, regs = run(1920.0, 1080.0)
    check(inside and inside[0][0] == f(1920.0) and inside[0][2] == 0, "layout: a 16:9 screen is left alone")
    uc.hook_del(hk)
    uc.mem_write(GUEST + 0x174BA64, struct.pack("<ff", 2340.0, 1080.0))

    # The budgets: 16 lines as the screen opens (3 used above), none until it has been up 4 s,
    # then 48 more; another screen function's first run, later, opens the log again.
    n0 = sc.err.decode().count("trace 0x6fe430")
    for _ in range(40):
        call(0x6FE430, callee)
    n1 = sc.err.decode().count("trace 0x6fe430")
    check(n1 == 16, f"budget: 16 lines as the screen opens ({n0} -> {n1})")
    sc.now_ms += 4000
    for _ in range(140):
        call(0x6FE430, callee)
    out = sc.err.decode()
    n2 = out.count("trace 0x6fe430")
    check(n2 == 16 + 48 and "the screen has been up 4000 ms; logging again" in out and "vars later 0x174ba64" in out,
          f"budget: 48 more once the screen has been up 4 s ({n2 - n1} more), with the memory again")
    call(0x6FE430, callee)
    check(sc.err.decode().count("trace 0x6fe430") == n2, "budget: then nothing more")
    call(0x870580, gate2)
    call(0x6FE430, callee)
    out = sc.err.decode()
    check("trace: 0x870580 ran, so its screen is open" in out and out.count("trace 0x6fe430") == n2 + 1
          and out.count("vars at open") == 2, "budget: another screen function, seconds later, opens the log again")

    # Kind 5: a sprite draw with the sprite in EBX, logged with its NJS_SPRITE and NJS_TEXANIM.
    spr, _ = sym["sub_66b6d0"]
    uc.mem_write(GUEST + 0x300000, struct.pack("<5fiII", -200.0, 12.0, -1.0, 2.0, 0.5, 0, 0x300100, 0x300200))
    uc.mem_write(GUEST + 0x300200 + 2 * 20, struct.pack("<10h", 640, 480, 320, 240, 0, 0, 255, 255, 7, 0))
    call(0x66B6D0, spr, {0x8A8: 2, 0x8B8: 0x300000})
    line = [l for l in sc.err.decode().splitlines() if "sprite 0x66b6d0" in l]
    print("  " + (line[0] if line else "(no sprite line)"))
    want_p = " ".join(hex(x) for x in struct.unpack("<8I", struct.pack("<5fiII", -200.0, 12.0, -1.0, 2.0, 0.5, 0, 0x300100, 0x300200)))
    check(bool(line) and "ret 0x401234 n 0x2 p " + want_p in line[0] and "anim 0x1e00280 0xf00140" in line[0]
          and " H 0x45124000 @" in line[0], "sprite: the sprite, its animation entry and the screen width are logged")

    # Kind 7: the 24 words at EAX; and a structure on the stack followed to its points.
    pts = struct.pack("<24f", *[float(i) for i in range(24)])
    uc.mem_write(GUEST + 0x300400, pts)
    call(0x7F4700, ptrf, {0x8A8: 0x300400, 0x8C8: 4})
    line = [l for l in sc.err.decode().splitlines() if l.startswith("opensa2-patch: ptr 0x7f4700")]
    print("  " + (line[0] if line else "(no ptr line)"))
    want = " ".join(hex(x) for x in struct.unpack("<24I", pts))
    check(bool(line) and f"ecx 0x4 edx 0x0 arg1 0x0 at 0x300400 {want} @" in line[0], "ptr: the 24 words at EAX")
    uc.mem_write(GUEST + 0x300600, struct.pack("<4I", 0x300400, 0x300700, 0, 4) + bytes(80))
    st_ret = 0x401250
    uc.mem_write(GUEST + 0x200004, struct.pack("<I", 0x300600))
    s2 = call(0x8704F0, ptrd, ret=st_ret)
    uc.mem_write(GUEST + 0x200004, struct.pack("<I", 0x300600))
    s2 = call(0x8704F0, ptrd, ret=st_ret)
    line = [l for l in sc.err.decode().splitlines() if l.startswith("opensa2-patch: ptr 0x8704f0")]
    print("  " + (line[0][:160] + " ..." if line else "(no ptr line)"))
    want8 = " ".join(hex(x) for x in struct.unpack("<8I", pts[:32]))
    check(bool(line) and "arg1 0x300600 at 0x300600 0x300400 0x300700 0x0 0x4" in line[0]
          and f"then 0x300400 {want8} @" in line[0], "ptr: a structure on the stack, followed to its points")
    check(u32(s2, 0x908) == 0x200004 and u32(s2, 0x9A8) == st_ret, "ptr: the wrapped function returned normally")

    # Kind 6: the dispatch, through the runtime's real table.
    bring_up(uc, game)
    dsp, _ = sym["sa2_ft_dispatch"]
    st = bytearray(0x1000)
    struct.pack_into("<I", st, 0x908, 0x200000)
    uc.mem_write(STATE, bytes(st))
    uc.mem_write(GUEST + 0x200000, struct.pack("<I", 0x401238))
    uc.reg_write(UC_ARM64_REG_X3, 0)
    run_function(uc, GAME_BASE + dsp, 0x6FE430, STATE, 0x6FE430, limit=200000)
    s3 = bytes(uc.mem_read(STATE, 0x1000))
    check("call 0x6fe430 ret 0x401238 @" in sc.err.decode(), "dispatch: the first call to an address is logged")
    check(u32(s3, 0x908) == 0x200004 and u32(s3, 0x9A8) == 0x401238, "dispatch: and the call is made (ret function)")
    n_call = sc.err.decode().count("call 0x6fe430")
    uc.mem_write(STATE, bytes(st))
    run_function(uc, GAME_BASE + dsp, 0x6FE430, STATE, 0x6FE430, limit=200000)
    check(sc.err.decode().count("call 0x6fe430") == n_call, "dispatch: a second call to it is not logged again")


def main():
    game, patch = Elf(sys.argv[1]), Elf(sys.argv[2])
    sym = game.syms

    def fn(va):
        return sym[f"sub_{va:x}"]

    def expect(va):
        v, s = fn(va)
        return game.b[game.vaddr_to_off(v):game.vaddr_to_off(v) + 16].hex()

    plan = ["opensa2-patch 1"]
    tests = [  # (from va, to va, label)
        (0x442CF0, 0x6FE430, "Disable Music: sub_442cf0 -> ret"),
        (0x716E00, 0x717360, "swap: LoadSonic -> LoadShadow"),
        (0x717360, 0x716E00, "swap: LoadShadow -> LoadSonic"),
        (0x728110, 0x7288B0, "LoadKnuckles -> LoadTikal"),
        (0x6FE440 if "sub_6fe440" in sym else 0x442CF0, 0x80F95D, "adrp test -> sub_80f95d"),
        (0x80F95D, 0x6FE430, "sub_80f95d -> ret (gives it a trampoline)"),
    ]
    for f, t, label in tests:
        fv, fs = fn(f)
        tv, _ = fn(t)
        plan.append(f"R {fv:x} {fs:x} {tv:x} {t:x} {expect(f)} {label}")
    plan.append("R 10 40 20 401000 00000000000000000000000000000000 bad bytes are refused")
    # The position wrappers: start (ECX = player), end and mission 2/3 end (EAX = player).
    fb, _ = sym["sa2_guest_fast_base"]
    plan.append(f"G {fb:x} 20000000")
    plan.append("C 1934b70 1de9600 1 5b")
    for cid, va in ((0, 0x1746F20), (1, 0x17472C0), (4, 0x17475D8), (5, 0x17478F0), (6, 0x1748008), (7, 0x1747C40)):
        plan.append(f"T 0 {cid:x} {va:x} 2c")
    for cid, va in ((0, 0x1748488), (1, 0x1748850), (4, 0x1748B98), (5, 0x1748EB0), (6, 0x1749568), (7, 0x17491C8)):
        plan.append(f"T 1 {cid:x} {va:x} 2c")
    for cid, va in ((0, 0x174A280), (1, 0x174A3A0), (4, 0x174A460), (5, 0x174A540), (6, 0x174A6E0), (7, 0x174A600)):
        plan.append(f"T 2 {cid:x} {va:x} 20")
    plan.append("P 1 0")
    plan.append("P 0 1")
    for va, kind, preg, label in ((0x43D8E0, 0, 0x8C8, "start position"), (0x43DF30, 1, 0x8A8, "end position"),
                                  (0x43DD50, 2, 0x8A8, "mission 2/3 end position")):
        v, s_ = fn(va)
        plan.append(f"W {v:x} {s_:x} {kind:x} {preg:x} {expect(va)} {label}")
    plan_text = ("\n".join(plan) + "\n").encode()
    given = len(sys.argv) > 3
    if given:
        plan_text = open(sys.argv[3], "rb").read()
        print("using the plan", sys.argv[3])

    uc, end = machine(game, patch)
    maps = (f"{GAME_BASE:x}-{GAME_BASE + 0x2d4000:x} r--p 00000000 fd:05 1234 /data/app/~~x==/com.yuriaaaaa.opensa2-y==/lib/arm64/libsa2game.so\n"
            f"{GAME_BASE + 0x2d7000:x}-{GAME_BASE + 0x8ddc000:x} r-xp 002d3000 fd:05 1234 /data/app/~~x==/com.yuriaaaaa.opensa2-y==/lib/arm64/libsa2game.so\n"
            f"{PATCH_BASE:x}-{PATCH_BASE + 0x1000:x} r--p 00000000 fd:05 99 /data/app/~~x==/com.yuriaaaaa.opensa2-y==/lib/arm64/libopensa2patch.so\n").encode()
    files = {
        "/proc/self/environ": b"PATH=/system/bin\0OPENSA2_PATCHES=" + PLAN.encode() + b"\0LD_PRELOAD=x\0",
        "/proc/self/maps": maps,
        "/proc/self/auxv": struct.pack("<8Q", 3, GAME_BASE + 0x40, 6, 4096, 9, GAME_BASE + 0x2d7660, 0, 0),
        PLAN: plan_text,
    }
    sc = Sys(uc, files)
    uc.hook_add(UC_HOOK_INTR, sc.hook)
    init_array = patch.dyn[25]
    ctor = u64(bytes(uc.mem_read(PATCH_BASE + init_array, 8)), 0)
    run_function(uc, ctor, limit=50_000_000, want_trace=False)
    print(f"constructor returned to {uc.reg_read(UC_ARM64_REG_PC):#x} (STOP is {STOP:#x})")
    print(sc.err.decode(), end="")
    ok = True

    def check(cond, what):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + what)
        ok &= bool(cond)

    log = sc.err.decode()
    if given:
        n = sum(1 for l in plan_text.decode().splitlines() if l[:2] in ("R ", "W "))
        check(f"{n} of {n} patch(es) applied" in log, f"the launcher's plan: all {n} applied")
        given_checks(uc, game, sc, fn, check)
        print("ALL PASS" if ok else "SOME FAILED")
        return 0 if ok else 1
    check("9 of 10 patch(es) applied" in log, "the summary line")
    check("skipped bad bytes are refused" in log, "a redirect whose bytes differ is refused")
    exec_flips = [m for m in sc.mprotects if m[2] == 7]
    check(all(m[0] & 0xFFF == 0 for m in sc.mprotects), "every mprotect is page aligned")
    check(len(exec_flips) >= 5, "entries opened R+W+X (never dropping X)")

    # 1. Disable Music: the ret template's effect, through the stub.
    music, _ = fn(0x442CF0)
    ret_fn, _ = fn(0x6FE430)
    # guest memory: GOT entry -> variable holding the guest base
    got_slot = None
    gb_var = None
    # find the guest base variable: __remill_read_memory_32 reads [[adrp page + 0xeb0]]
    r32, _ = sym["__remill_read_memory_32"]
    code = game.b[game.vaddr_to_off(r32):game.vaddr_to_off(r32) + 0x30]
    for k in range(0, 0x30, 4):
        w = u32(code, k)
        if (w & 0x9F000000) == 0x90000000:
            immlo, immhi = (w >> 29) & 3, (w >> 5) & 0x7FFFF
            imm = ((immhi << 2) | immlo)
            if imm & (1 << 20):
                imm -= 1 << 21
            page = ((r32 + k) & ~0xFFF) + (imm << 12)
            w2 = u32(code, k + 4)
            off = ((w2 >> 10) & 0xFFF) * 8
            got_slot = page + off
            break
    gb_var = u64(bytes(uc.mem_read(GAME_BASE + got_slot, 8)), 0)
    uc.mem_map(GUEST + 0x1F0000, 0x20000, UC_PROT_READ | UC_PROT_WRITE)
    uc.mem_write(gb_var, struct.pack("<Q", GUEST))
    esp = 0x200000
    st = bytearray(0x1000)
    struct.pack_into("<I", st, 0x908, esp)          # ESP
    struct.pack_into("<I", st, 0x848, 0)            # SS base
    uc.mem_write(STATE, bytes(st))
    uc.mem_write(GUEST + esp, struct.pack("<I", 0x00401234))
    trace = run_function(uc, GAME_BASE + music, STATE, 0x442CF0, 0)
    s2 = bytes(uc.mem_read(STATE, 0x1000))
    check(u32(s2, 0x908) == esp + 4 and u32(s2, 0x9A8) == 0x401234,
          f"sub_442cf0 now returns at once (ESP {u32(s2, 0x908):#x}, EIP {u32(s2, 0x9A8):#x})")
    check(GAME_BASE + ret_fn in trace, "and it ran the ret function's body")
    check(not any(GAME_BASE + music + 16 <= a < GAME_BASE + music + 0x400 for a in trace), "and none of its own")

    # 2. LoadSonic -> LoadShadow, with LoadShadow itself patched (a swap): through the trampoline.
    sonic, _ = fn(0x716E00)
    shadow, _ = fn(0x717360)
    ref, _ = machine(game)          # an unpatched machine for the reference run
    for m in (uc, ref):
        reset(m)
    run_function(ref, GAME_BASE + shadow, STATE, 0x717360, 0x77, until=GAME_BASE + shadow + 16)
    trace = run_function(uc, GAME_BASE + sonic, STATE, 0x716E00, 0x77, until=GAME_BASE + shadow + 16)
    regs = [UC_ARM64_REG_SP, UC_ARM64_REG_X0, UC_ARM64_REG_X2, UC_ARM64_REG_X29, UC_ARM64_REG_X30]
    same = all(uc.reg_read(r) == ref.reg_read(r) for r in regs)
    if not same:
        print("  differ:", [(r, hex(uc.reg_read(r)), hex(ref.reg_read(r))) for r in regs if uc.reg_read(r) != ref.reg_read(r)])
    sp = uc.reg_read(UC_ARM64_REG_SP)
    stack_same = bytes(uc.mem_read(sp, 0xd0)) == bytes(ref.mem_read(sp, 0xd0))
    check(uc.reg_read(UC_ARM64_REG_PC) == GAME_BASE + shadow + 16, "LoadSonic reaches LoadShadow's body")
    check(same and stack_same, "with the registers and stack LoadShadow's own entry would have made")
    check(uc.reg_read(UC_ARM64_REG_X1) & 0xFFFFFFFF == 0x717360, "and pc (w1) set to LoadShadow's guest address")
    check(not any(GAME_BASE + shadow <= a < GAME_BASE + shadow + 16 for a in trace), "without running LoadShadow's patched entry")

    # 3. LoadShadow -> LoadSonic (the other half of the swap).
    ref2, _ = machine(game)
    run_function(ref2, GAME_BASE + sonic, STATE, 0x716E00, 0x77, until=GAME_BASE + sonic + 16)
    run_function(uc, GAME_BASE + shadow, STATE, 0x717360, 0x77, until=GAME_BASE + sonic + 16)
    check(uc.reg_read(UC_ARM64_REG_PC) == GAME_BASE + sonic + 16 and uc.reg_read(UC_ARM64_REG_SP) == ref2.reg_read(UC_ARM64_REG_SP),
          "LoadShadow reaches LoadSonic's body")

    # 4. Knuckles -> Tikal (Tikal not patched: straight to its entry).
    knux, _ = fn(0x728110)
    tikal, _ = fn(0x7288B0)
    run_function(uc, GAME_BASE + knux, STATE, 0x728110, 0x77, until=GAME_BASE + tikal)
    check(uc.reg_read(UC_ARM64_REG_PC) == GAME_BASE + tikal and uc.reg_read(UC_ARM64_REG_X1) & 0xFFFFFFFF == 0x7288B0,
          "LoadKnuckles goes to LoadTikal's entry with pc 0x7288b0")

    # 5. ADRP relocation: the redirect into sub_80f95d runs its moved adrp right.
    f80, _ = fn(0x80F95D)
    src = 0x6FE440 if "sub_6fe440" in sym else 0x442CF0
    srcv, _ = fn(src)
    ref3, _ = machine(game)
    reset(ref3)
    reset(uc)
    run_function(ref3, GAME_BASE + f80, STATE, 0x80F95D, 0x77, until=GAME_BASE + f80 + 16)
    if src == 0x442CF0:
        print("note: the adrp test shares sub_442cf0 (the first redirect wins), skipped")
    else:
        run_function(uc, GAME_BASE + srcv, STATE, src, 0x77, until=GAME_BASE + f80 + 16)
        diffs = [r for r in range(UC_ARM64_REG_X0, UC_ARM64_REG_X0 + 29)
                 if r not in (UC_ARM64_REG_X1, UC_ARM64_REG_X16) and uc.reg_read(r) != ref3.reg_read(r)]
        check(uc.reg_read(UC_ARM64_REG_PC) == GAME_BASE + f80 + 16 and not diffs,
              f"a moved adrp computes the same page (sub_80f95d; differing registers: {diffs})")

    # 6. The start position wrapper around the game's own LoadStartPosition (sub_43d8e0).
    def guest_setup(m, charid, level):
        for a, n in ((0x1746000, 0x5000), (0x1934000, 0x1000), (0x1DE9000, 0x2000), (0x1000000, 0x2000), (0x300000, 0x1000)):
            try:
                m.mem_map(GUEST + a, n, UC_PROT_READ | UC_PROT_WRITE)
            except UcError:
                pass
            m.mem_write(GUEST + a, bytes(n))
        try:
            m.mem_map(GUEST + 0x1F0000, 0x20000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
        m.mem_write(gb_var, struct.pack("<Q", GUEST))
        def start(level, rot, x, y, z):
            return struct.pack("<hHHH9f", level, rot, rot, rot, x, y, z, x, y, z, x, y, z)
        inval = struct.pack("<h", 0x5B) + bytes(42)
        for a in (0x17475D8, 0x17478F0, 0x1747BE8, 0x1747C40, 0x1748008, 0x1748378, 0x1748400, 0x1748488, 0x1748850,
                  0x1748B98, 0x1748EB0, 0x17491C8, 0x1749568, 0x17498B0, 0x1749938, 0x174A280, 0x174A3A0, 0x174A460,
                  0x174A540, 0x174A600, 0x174A6E0):
            m.mem_write(GUEST + a, struct.pack("<h", 0x5B))
        m.mem_write(GUEST + 0x1746F20, start(13, 0x4000, 1.0, 2.0, 3.0) + inval)        # Sonic: City Escape
        m.mem_write(GUEST + 0x17472C0, start(16, 0x8000, 7.0, 8.0, 9.0) + inval)        # Shadow: Radical Highway
        m.mem_write(GUEST + 0x1934B70, struct.pack("<h", level))
        m.mem_write(GUEST + 0x1DE9600, struct.pack("<I", 0x1000000))                   # MainCharObj2[0]
        m.mem_write(GUEST + 0x1DEA6E0, struct.pack("<I", 0x1001000))                   # MainCharacter[0]
        m.mem_write(GUEST + 0x1000000, bytes([0, charid, 0, charid]))
        st = bytearray(0x1000)
        struct.pack_into("<I", st, 0x908, 0x200000)
        struct.pack_into("<I", st, 0x8C8, 0)            # ECX: player 0
        struct.pack_into("<I", st, 0x8F8, 0x300000)     # EDI: the position
        m.mem_write(STATE, bytes(st))
        m.mem_write(GUEST + 0x200000, struct.pack("<II", 0x00401234, 0x300100))   # return, rotation
        m.mem_write(GUEST + 0x300000, struct.pack("<3f", 99.0, 99.0, 99.0))
        m.mem_write(GUEST + 0x300100, struct.pack("<3i", 7, 7, 7))
        try:
            m.mem_map(GUEST + 0x8AF000, 0x1000, UC_PROT_READ | UC_PROT_WRITE)
        except UcError:
            pass
        m.mem_write(GUEST + 0x8AF004, struct.pack("<f", 0.0))     # .rdata: the 0.0f the position is cleared with

    def start_result(m):
        st = bytes(m.mem_read(STATE, 0x1000))
        pos = struct.unpack("<3f", bytes(m.mem_read(GUEST + 0x300000, 12)))
        rot = struct.unpack("<3i", bytes(m.mem_read(GUEST + 0x300100, 12)))
        cid = bytes(m.mem_read(GUEST + 0x1000001, 1))[0]
        return pos, rot, cid, u32(st, 0x908), u32(st, 0x9A8), u32(st, 0x8A8)

    lsp, _ = fn(0x43D8E0)
    ref4, _ = machine(game)
    bring_up(ref4, game)
    bring_up(uc, game)
    gb4 = u64(bytes(ref4.mem_read(GAME_BASE + got_slot, 8)), 0)
    assert gb4 == gb_var
    guest_setup(ref4, 1, 13)
    run_function(ref4, GAME_BASE + lsp, STATE, 0x43D8E0, 0, limit=2_000_000)
    r_ref = start_result(ref4)
    print("  unpatched game, Shadow in City Escape:", r_ref)
    check(r_ref[0] == (0.0, 0.0, 0.0) and r_ref[3] == 0x200004 and r_ref[4] == 0x401234,
          "the game's own LoadStartPosition finds nothing for Shadow in City Escape (it reads the tables named)")
    guest_setup(ref4, 0, 13)
    run_function(ref4, GAME_BASE + lsp, STATE, 0x43D8E0, 0, limit=2_000_000)
    r_ref0 = start_result(ref4)
    check(r_ref0[0] == (1.0, 2.0, 3.0) and r_ref0[1][1] == 0x4000,
          f"and finds Sonic's City Escape start for Sonic {r_ref0[:2]}")
    guest_setup(uc, 1, 13)
    run_function(uc, GAME_BASE + lsp, STATE, 0x43D8E0, 0, limit=2_000_000)
    r = start_result(uc)
    print("  patched game, Shadow in City Escape:", r)
    check(r[0] == (1.0, 2.0, 3.0) and r[1][1] == 0x4000, "with the wrapper, Shadow starts at Sonic's City Escape start")
    check(r[2] == 1 and r[3] == 0x200004 and r[4] == 0x401234 and r[5] == r_ref0[5],
          "and CharID is Shadow again after, the call returned normally, EAX as the game's")
    guest_setup(uc, 1, 16)
    run_function(uc, GAME_BASE + lsp, STATE, 0x43D8E0, 0, limit=2_000_000)
    r = start_result(uc)
    check(r[0] == (7.0, 8.0, 9.0) and r[2] == 1, "Shadow in his own level keeps his own start")
    guest_setup(uc, 4, 13)
    run_function(uc, GAME_BASE + lsp, STATE, 0x43D8E0, 0, limit=2_000_000)
    r = start_result(uc)
    check(r[0] == (1.0, 2.0, 3.0) and r[2] == 4,
          "a character with no partner and no entry (Knuckles in City Escape) starts where one who has it does")

    # 7. The end-position wrappers swap CharID for the game's call (stopped at the body's start).
    for va, preg, table, label in ((0x43DF30, 0x8A8, 0x1748488, "end position"), (0x43DD50, 0x8A8, 0x174A280, "mission 2/3 end")):
        f_, _ = fn(va)
        guest_setup(uc, 1, 13)
        uc.mem_write(GUEST + 0x1748488, struct.pack("<h", 13) + bytes(42) + struct.pack("<h", 0x5B))
        uc.mem_write(GUEST + 0x174A280, struct.pack("<h", 13) + bytes(30) + struct.pack("<h", 0x5B))
        uc.mem_write(GUEST + 0x1748850, struct.pack("<h", 0x5B))
        uc.mem_write(GUEST + 0x174A3A0, struct.pack("<h", 0x5B))
        st = bytearray(uc.mem_read(STATE, 0x1000))
        struct.pack_into("<I", st, 0x8A8, 0)
        uc.mem_write(STATE, bytes(st))
        run_function(uc, GAME_BASE + f_, STATE, va, 0, until=GAME_BASE + f_ + 16)
        cid = bytes(uc.mem_read(GUEST + 0x1000001, 1))[0]
        check(cid == 0, f"{label}: the game's body runs with CharID Sonic for Shadow in City Escape")

    layout_checks(game, patch, check)
    fill_checks(game, patch, check)
    moves_checks(game, patch, check)
    restart_checks(game, patch, check)
    setload_checks(game, patch, check)
    levelgate_checks(game, patch, check)
    spindash_checks(game, patch, check)
    chargate_checks(game, patch, check)
    super_checks(game, patch, check)
    flash_voice_checks(game, patch, check)
    hunt_checks(game, patch, check)
    aura_checks(game, patch, check)
    edge_checks(game, patch, check)
    print("ALL PASS" if ok else "SOME FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
