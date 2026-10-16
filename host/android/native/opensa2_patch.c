/* opensa2_patch.c: OpenSA2's native patch helper (lib/arm64-v8a/libopensa2patch.so).
 *
 * What it is for. The recompiled game (libsa2game.so) runs no x86: each guest function is a
 * native ARM64 function `sub_<va>(state, pc, memory)`, and most calls between them are direct
 * `bl` instructions, so neither a write into the guest's .text (a code list "Patch") nor a change
 * to the runtime's function table reaches them. What can be reproduced is the class of x86 patch
 * that replaces or wraps a whole function from its first byte:
 *
 *   - `ret` written at a function's entry (the function does nothing): its native entry jumps to
 *     a lifted function whose x86 body is a bare `ret`;
 *   - a jump written at a function's entry to another function's entry (WriteJump(A, B), and the
 *     launcher's character swap, LoadSonic -> LoadShadow and so on): A's native entry jumps to B;
 *   - a wrapper around a whole function, here the three that place a character at a level's
 *     start and end: when a swapped-in character has no position for the level, the wrapper
 *     lets the game look the level up under the character it stands in for (below).
 *
 * A patch in the middle of a function (most "Patch" codes, most DLL mods) has no native place to
 * land and is not attempted here.
 *
 * How. The launcher (NativePatch.java) reads libsa2game.so's own symbol table, works out which
 * native addresses to redirect, and writes a plan file. The play screen sets LD_PRELOAD to this
 * library and OPENSA2_PATCHES to the plan for the game process only (the native session copies
 * its environment into the game's). The dynamic linker runs this library's constructor before
 * the game's main, single-threaded, and it:
 *
 *   1. finds libsa2game.so's load address in /proc/self/maps (nothing happens in any other
 *      program), and the page size in /proc/self/auxv;
 *   2. checks each patched entry's first 16 bytes against the bytes the plan expects (the file
 *      the plan was made from), and skips any that differ;
 *   3. builds, in an anonymous executable page, for every patched function a trampoline (its
 *      first four instructions, relocated, then a jump to the fifth) so that the original body
 *      stays callable -- a swap A <-> B and every wrapper need it -- and for every redirect a stub
 *      that sets the lifted `pc` argument to the target's guest address and jumps to the target
 *      (or to the target's trampoline when the target is itself patched);
 *   4. writes `ldr x16, #8; br x16; .quad stub` over each patched entry: the page goes from
 *      R+X to R+W+X and back (never losing X, which is what lets an app process do it), then the
 *      caches are synchronised.
 *
 * The position wrappers. The game finds a character's start position (LoadStartPosition,
 * 0x43D8E0, player in ECX), its end-of-level position (0x43DF30, player in EAX) and its mission
 * 2/3 end position (0x43DD50, player in EAX) in tables kept per character, chosen by the
 * player's CharObj2Base.CharID: Shadow loaded into City Escape finds nothing in Shadow's table
 * and stands at the origin. The plan names, per kind of table, each character's table in guest
 * memory, and a partner for each character the swap puts somewhere new. A wrapper reads the
 * player's CharID and the current level; when the character's own table lacks the level and its
 * partner's has it, it sets CharID to the partner's for the length of the game's own call and
 * puts it back after. Anything it cannot read leaves the call as the game makes it.
 *
 * Everything it does or refuses is printed to stderr, which the session keeps as game.log, as
 * lines starting "opensa2-patch:". It uses no C library (raw system calls), so it loads in the
 * game process with no dependencies, and when anything is unexpected it changes nothing.
 *
 * Plan file (text, one line each, numbers in hex without 0x):
 *   opensa2-patch 1
 *   R <from off> <from size> <to off> <to guest va> <16 expected bytes as 32 hex> <label>
 *   W <from off> <from size> <kind> <player register's State offset> <16 expected bytes> <label>
 *   G <off of the guest-memory base pointer (sa2_guest_fast_base)> <guest size>
 *   C <CurrentLevel va> <MainCharObj2 va> <CharID offset> <the invalid level id>
 *   T <kind> <char id> <table va> <entry size>
 *   P <char id> <partner char id>
 * where an offset is from libsa2game.so's load address (its symbol value).
 */
#include <stddef.h>
#include <stdint.h>

#define SYS_openat 56
#define SYS_close 57
#define SYS_read 63
#define SYS_write 64
#define SYS_munmap 215
#define SYS_mmap 222
#define SYS_mprotect 226

#define AT_FDCWD (-100)
#define O_RDONLY 0
#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4
#define MAP_PRIVATE 2
#define MAP_ANONYMOUS 0x20

#define MAX_PATCHES 64
#define MAX_WRAPS 4
#define MAX_CHARS 32
#define PLAN_MAX (64 * 1024)
#define BIG_MAX (1024 * 1024)

/* ------------------------------------------------------------------ system calls */

static long sys6(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                     : "memory", "cc");
    return x0;
}

static long sys_open(const char *path) { return sys6(SYS_openat, AT_FDCWD, (long)path, O_RDONLY, 0, 0, 0); }
static void sys_close(long fd) { sys6(SYS_close, fd, 0, 0, 0, 0, 0); }
static long sys_read(long fd, void *buf, size_t n) { return sys6(SYS_read, fd, (long)buf, (long)n, 0, 0, 0); }
static long sys_write(long fd, const void *buf, size_t n) { return sys6(SYS_write, fd, (long)buf, (long)n, 0, 0, 0); }
static long sys_mprotect(uintptr_t a, size_t n, int prot) { return sys6(SYS_mprotect, (long)a, (long)n, prot, 0, 0, 0); }
static long sys_mmap(size_t n, int prot) {
    return sys6(SYS_mmap, 0, (long)n, prot, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
}
static void sys_munmap(uintptr_t a, size_t n) { sys6(SYS_munmap, (long)a, (long)n, 0, 0, 0, 0); }

/* The compiler may call these for copies and clears; there is no C library to supply them. Hidden:
 * a preloaded library's exported memcpy would replace the C library's for the whole game. */
__attribute__((visibility("hidden"))) void *memcpy(void *d, const void *s, size_t n) {
    unsigned char *dd = d;
    const unsigned char *ss = s;
    while (n--) *dd++ = *ss++;
    return d;
}
__attribute__((visibility("hidden"))) void *memset(void *d, int c, size_t n) {
    unsigned char *dd = d;
    while (n--) *dd++ = (unsigned char)c;
    return d;
}

/* ------------------------------------------------------------------ text */

static size_t slen(const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    return n;
}

static int starts(const char *s, const char *p) {
    while (*p)
        if (*s++ != *p++) return 0;
    return 1;
}

static char g_line[512];
static size_t g_len;

static void put(const char *s) {
    while (*s && g_len + 1 < sizeof g_line) g_line[g_len++] = *s++;
}
static void put_hex(uint64_t v) {
    char b[17];
    int i = 16;
    b[16] = 0;
    do {
        b[--i] = "0123456789abcdef"[v & 15];
        v >>= 4;
    } while (v && i > 0);
    put("0x");
    put(b + i);
}
static void put_dec(uint64_t v) {
    char b[21];
    int i = 20;
    b[20] = 0;
    do {
        b[--i] = (char)('0' + v % 10);
        v /= 10;
    } while (v && i > 0);
    put(b + i);
}
static void begin(void) {
    g_len = 0;
    put("opensa2-patch: ");
}
static void end(void) {
    put("\n");
    sys_write(2, g_line, g_len);
}
static void say(const char *s) {
    begin();
    put(s);
    end();
}

/* Reads a whole small file (procfs or the plan) into buf; returns its length, or -1. */
static long read_file(const char *path, char *buf, size_t cap) {
    long fd = sys_open(path);
    if (fd < 0) return -1;
    size_t got = 0;
    for (;;) {
        if (got + 1 >= cap) break;
        long n = sys_read(fd, buf + got, cap - 1 - got);
        if (n <= 0) break;
        got += (size_t)n;
    }
    sys_close(fd);
    buf[got] = 0;
    return (long)got;
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Parses a hex number at *p, moving past it and the spaces after it. */
static int parse_hex(const char **p, uint64_t *out) {
    const char *s = *p;
    uint64_t v = 0;
    int n = 0;
    while (hexval(*s) >= 0) {
        v = (v << 4) | (uint64_t)hexval(*s++);
        if (++n > 16) return 0;
    }
    if (!n) return 0;
    while (*s == ' ' || *s == '\t') s++;
    *p = s;
    *out = v;
    return 1;
}

static int parse_bytes16(const char **p, uint8_t out[16]) {
    const char *s = *p;
    for (int i = 0; i < 16; i++) {
        int a = hexval(s[2 * i]), b = hexval(s[2 * i + 1]);
        if (a < 0 || b < 0) return 0;
        out[i] = (uint8_t)(a << 4 | b);
    }
    s += 32;
    while (*s == ' ' || *s == '\t') s++;
    *p = s;
    return 1;
}

/* ------------------------------------------------------------------ the plan */

typedef void *(*lifted_fn)(void *state, uint32_t pc, void *memory);

enum { KIND_REDIRECT = 0, KIND_WRAP = 1 };

typedef struct {
    int type;
    uint64_t from, from_size, to, to_va;   /* redirect: target */
    uint64_t wrap_kind, preg;              /* wrapper: which tables, where the player is */
    uint8_t expect[16];
    char label[48];
    int ok;          /* checked and still to do */
    uint64_t stub;   /* address of its stub */
} patch_t;

typedef struct {
    lifted_fn orig;
    uint32_t preg, kind;
} wrap_t;

static patch_t g_p[MAX_PATCHES];
static int g_n;
static char g_plan[PLAN_MAX];
static char g_big[BIG_MAX];

/* The position wrappers' view of the game. */
static wrap_t g_wrap[MAX_WRAPS];
static int g_nwrap;
static uint64_t g_fastbase_off, g_fastbase;   /* where the runtime keeps the guest memory's address */
static uint32_t g_guest_size;
static uint32_t g_level_va, g_co2_va, g_charid_off, g_invalid = 0xFFFF;
static uint32_t g_table[3][MAX_CHARS], g_table_size[3][MAX_CHARS];
static uint8_t g_partner[MAX_CHARS];

static void label_from(const char *s, char *out, size_t cap) {
    size_t k = 0;
    while (*s && *s != '\n' && k + 1 < cap) out[k++] = *s++;
    out[k] = 0;
}

static void parse_plan(const char *p) {
    if (!starts(p, "opensa2-patch 1")) {
        say("the plan is not a version 1 plan; nothing changed");
        return;
    }
    for (int i = 0; i < MAX_CHARS; i++) g_partner[i] = 0xFF;
    while (*p) {
        while (*p && *p != '\n') p++;
        if (*p == '\n') p++;
        if (!*p || p[1] != ' ') continue;
        char t = *p;
        const char *s = p + 2;
        uint64_t a, b, c, d;
        if (t == 'R' || t == 'W') {
            if (g_n >= MAX_PATCHES) {
                say("more patches than this helper takes; the rest are left out");
                return;
            }
            patch_t *r = &g_p[g_n];
            r->type = t == 'R' ? KIND_REDIRECT : KIND_WRAP;
            if (!parse_hex(&s, &r->from) || !parse_hex(&s, &r->from_size)) continue;
            if (t == 'R') {
                if (!parse_hex(&s, &r->to) || !parse_hex(&s, &r->to_va)) continue;
            } else {
                if (!parse_hex(&s, &r->wrap_kind) || !parse_hex(&s, &r->preg)) continue;
                if (r->wrap_kind > 2 || r->preg > 0x9F0 || (r->preg & 3)) continue;
            }
            if (!parse_bytes16(&s, r->expect)) continue;
            label_from(s, r->label, sizeof r->label);
            r->ok = 1;
            g_n++;
        } else if (t == 'G') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b)) {
                g_fastbase_off = a;
                g_guest_size = (uint32_t)b;
            }
        } else if (t == 'C') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d)) {
                g_level_va = (uint32_t)a;
                g_co2_va = (uint32_t)b;
                g_charid_off = (uint32_t)c;
                g_invalid = (uint32_t)d;
            }
        } else if (t == 'T') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d) && a < 3 &&
                b < MAX_CHARS) {
                g_table[a][b] = (uint32_t)c;
                g_table_size[a][b] = (uint32_t)d;
            }
        } else if (t == 'P') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && a < MAX_CHARS && b < MAX_CHARS) g_partner[a] = (uint8_t)b;
        }
    }
}

/* ------------------------------------------------------------------ the position wrappers */

static int g_ok(uint32_t a, uint32_t n) { return a >= 0x1000 && (uint64_t)a + n <= g_guest_size; }

static uint32_t g_rd16(const uint8_t *g, uint32_t a) { return (uint32_t)g[a] | (uint32_t)g[a + 1] << 8; }
static uint32_t g_rd32(const uint8_t *g, uint32_t a) {
    return (uint32_t)g[a] | (uint32_t)g[a + 1] << 8 | (uint32_t)g[a + 2] << 16 | (uint32_t)g[a + 3] << 24;
}

/* Whether character `id`'s table of kind `kind` holds `level` (it ends at the invalid level). */
static int has_level(const uint8_t *g, uint32_t kind, uint32_t id, uint32_t level) {
    if (kind >= 3 || id >= MAX_CHARS) return 0;
    uint32_t a = g_table[kind][id], sz = g_table_size[kind][id];
    if (!a || sz < 2) return 0;
    for (int i = 0; i < 64; i++, a += sz) {
        if (!g_ok(a, 2)) return 0;
        uint32_t lv = g_rd16(g, a);
        if (lv == g_invalid) return 0;
        if (lv == level) return 1;
    }
    return 0;
}

static void *wrap_common(int slot, void *state, uint32_t pc, void *memory) {
    wrap_t *w = &g_wrap[slot];
    uint8_t *g = g_fastbase ? *(uint8_t **)g_fastbase : 0;
    if (g && g_guest_size) {
        uint32_t player = *(uint32_t *)((uint8_t *)state + w->preg);
        if (player < 8 && g_ok(g_co2_va + 4 * player, 4) && g_ok(g_level_va, 2)) {
            uint32_t co2 = g_rd32(g, g_co2_va + 4 * player);
            if (co2 && g_ok(co2 + g_charid_off, 1)) {
                uint8_t id = g[co2 + g_charid_off];
                uint8_t partner = id < MAX_CHARS ? g_partner[id] : 0xFF;
                uint32_t level = g_rd16(g, g_level_va);
                if (partner != 0xFF && !has_level(g, w->kind, id, level) && has_level(g, w->kind, partner, level)) {
                    g[co2 + g_charid_off] = partner;
                    void *r = w->orig(state, pc, memory);
                    g[co2 + g_charid_off] = id;
                    return r;
                }
            }
        }
    }
    return w->orig(state, pc, memory);
}

static void *wrap0(void *s, uint32_t pc, void *m) { return wrap_common(0, s, pc, m); }
static void *wrap1(void *s, uint32_t pc, void *m) { return wrap_common(1, s, pc, m); }
static void *wrap2(void *s, uint32_t pc, void *m) { return wrap_common(2, s, pc, m); }
static void *wrap3(void *s, uint32_t pc, void *m) { return wrap_common(3, s, pc, m); }
static lifted_fn const g_wrap_fn[MAX_WRAPS] = {wrap0, wrap1, wrap2, wrap3};

/* ------------------------------------------------------------------ the process */

/* libsa2game.so's load address: the start of its mapping at file offset 0. */
static uintptr_t find_base(void) {
    if (read_file("/proc/self/maps", g_big, sizeof g_big) <= 0) return 0;
    const char *p = g_big;
    while (*p) {
        const char *line = p;
        while (*p && *p != '\n') p++;
        const char *eol = p;
        if (*p) p++;
        /* "start-end perms offset dev inode path" */
        uint64_t start = 0, off = 0;
        const char *s = line;
        while (hexval(*s) >= 0) start = (start << 4) | (uint64_t)hexval(*s++);
        if (*s != '-') continue;
        s++;
        while (hexval(*s) >= 0) s++;
        while (*s == ' ') s++;
        while (*s && *s != ' ') s++;           /* perms */
        while (*s == ' ') s++;
        while (hexval(*s) >= 0) off = (off << 4) | (uint64_t)hexval(*s++);
        if (off != 0) continue;
        static const char want[] = "/libsa2game.so";
        size_t wl = sizeof want - 1;
        if ((size_t)(eol - line) < wl) continue;
        const char *tail = eol - wl;
        int same = 1;
        for (size_t i = 0; i < wl; i++)
            if (tail[i] != want[i]) same = 0;
        if (same) return (uintptr_t)start;
    }
    return 0;
}

static uint64_t page_size(void) {
    uint64_t v[64];
    long n = read_file("/proc/self/auxv", (char *)v, sizeof v);
    for (long i = 0; i + 1 < n / 8; i += 2) {
        if (v[i] == 0) break;
        if (v[i] == 6 /* AT_PAGESZ */ && v[i + 1] >= 4096 && !(v[i + 1] & (v[i + 1] - 1))) return v[i + 1];
    }
    return 4096;
}

static void sync_code(uintptr_t start, uintptr_t end) {
    uint64_t ctr;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    uintptr_t dline = (uintptr_t)4 << ((ctr >> 16) & 0xF), iline = (uintptr_t)4 << (ctr & 0xF);
    for (uintptr_t a = start & ~(dline - 1); a < end; a += dline) __asm__ volatile("dc cvau, %0" ::"r"(a) : "memory");
    __asm__ volatile("dsb ish" ::: "memory");
    for (uintptr_t a = start & ~(iline - 1); a < end; a += iline) __asm__ volatile("ic ivau, %0" ::"r"(a) : "memory");
    __asm__ volatile("dsb ish\n\tisb" ::: "memory");
}

/* ------------------------------------------------------------------ code */

static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

typedef struct {
    uint32_t *w;
    int n, cap;
} emit_t;

static int emit(emit_t *e, uint32_t word) {
    if (e->n >= e->cap) return 0;
    e->w[e->n++] = word;
    return 1;
}
static int emit64(emit_t *e, uint64_t v) { return emit(e, (uint32_t)v) && emit(e, (uint32_t)(v >> 32)); }

/* ldr x16, #8; br x16; .quad to */
static int emit_jump(emit_t *e, uint64_t to) {
    return emit(e, 0x58000050u) && emit(e, 0xD61F0200u) && emit64(e, to);
}

/* One instruction of a function's entry, moved, behaving as it did at `pc`. 0 when it cannot be
 * moved (a branch, a literal load, anything unknown). */
static int relocate(emit_t *e, uint32_t insn, uint64_t pc) {
    uint32_t op = (insn >> 25) & 0xF;
    if (op == 0x8 || op == 0x9) {                       /* data processing, immediate */
        if ((insn & 0x1F000000u) == 0x10000000u) {      /* ADR / ADRP */
            int64_t imm = (int64_t)((((insn >> 5) & 0x7FFFF) << 2) | ((insn >> 29) & 3));
            imm = (imm << 43) >> 43;                    /* 21-bit signed */
            uint64_t v;
            if (insn & 0x80000000u) v = (pc & ~(uint64_t)0xFFF) + (uint64_t)(imm << 12);
            else v = pc + (uint64_t)imm;
            uint32_t rd = insn & 31;
            if (rd == 31) return 0;
            /* ldr xd, #8; b #12; .quad v */
            return emit(e, 0x58000040u | rd) && emit(e, 0x14000003u) && emit64(e, v);
        }
        return emit(e, insn);
    }
    if (op == 0xA || op == 0xB) {                       /* branches, exceptions, system */
        if ((insn & 0xFFFFF01Fu) == 0xD503201Fu) return emit(e, insn);   /* hints (nop, bti, pac) */
        return 0;
    }
    if ((op & 0x5) == 0x4) {                            /* loads and stores */
        if ((insn & 0x3B000000u) == 0x18000000u) return 0;   /* literal loads */
        return emit(e, insn);
    }
    if ((op & 0x7) == 0x5 || (op & 0x7) == 0x7) return emit(e, insn);   /* data processing */
    return 0;
}

/* ------------------------------------------------------------------ the work */

static void skipped(patch_t *r, const char *why) {
    r->ok = 0;
    begin();
    put("skipped ");
    put(r->label);
    put(": ");
    put(why);
    end();
}

static void apply(uintptr_t base, uint64_t psize) {
    /* Which patches can go ahead: the entry is the bytes the plan was made from. */
    for (int i = 0; i < g_n; i++) {
        patch_t *r = &g_p[i];
        const uint8_t *at = (const uint8_t *)(base + r->from);
        int same = r->from_size >= 16;
        for (int k = 0; same && k < 16; k++)
            if (at[k] != r->expect[k]) same = 0;
        if (!same) {
            skipped(r, "its code is not what the plan expects");
            continue;
        }
        for (int j = 0; j < i; j++)
            if (g_p[j].ok && g_p[j].from == r->from) {
                skipped(r, "an earlier patch already takes this function");
                break;
            }
    }
    if (g_fastbase_off) g_fastbase = base + g_fastbase_off;

    /* Code space: 128 bytes of trampoline and 32 of stub for each. */
    size_t need = (size_t)g_n * 160 + 64;
    need = (need + psize - 1) & ~(psize - 1);
    long m = sys_mmap(need, PROT_READ | PROT_WRITE);
    if (m < 0 && m > -4096) {
        say("could not get memory for the trampolines; nothing changed");
        return;
    }
    uintptr_t area = (uintptr_t)m;
    uint64_t tramp[MAX_PATCHES];
    int has_tramp[MAX_PATCHES];

    /* Trampolines: each patched function's first four instructions, moved, then on to its fifth. */
    for (int i = 0; i < g_n; i++) {
        patch_t *r = &g_p[i];
        has_tramp[i] = 0;
        if (!r->ok) continue;
        uint64_t t = area + (uint64_t)i * 160;
        emit_t e = {(uint32_t *)t, 0, 32};
        int good = 1;
        for (int k = 0; k < 4 && good; k++) good = relocate(&e, rd32(r->expect + 4 * k), base + r->from + 4 * k);
        if (good) good = emit_jump(&e, base + r->from + 16);
        tramp[i] = t;
        has_tramp[i] = good;
    }

    /* Stubs. A redirect: the guest address of the target in the pc argument, then the target's
     * original body. A wrapper: straight to the wrapper, which calls the original body. */
    for (int i = 0; i < g_n; i++) {
        patch_t *r = &g_p[i];
        if (!r->ok) continue;
        uint64_t s = area + (uint64_t)i * 160 + 128;
        emit_t e = {(uint32_t *)s, 0, 8};
        if (r->type == KIND_WRAP) {
            if (!has_tramp[i]) {
                skipped(r, "its first instructions cannot be moved");
                continue;
            }
            if (g_nwrap >= MAX_WRAPS) {
                skipped(r, "more wrappers than this helper has");
                continue;
            }
            if (!g_fastbase || !g_guest_size || !g_co2_va || !g_level_va) {
                skipped(r, "the plan does not say where the game's data is");
                continue;
            }
            wrap_t *w = &g_wrap[g_nwrap];
            w->orig = (lifted_fn)tramp[i];
            w->preg = (uint32_t)r->preg;
            w->kind = (uint32_t)r->wrap_kind;
            emit_jump(&e, (uint64_t)g_wrap_fn[g_nwrap]);
            g_nwrap++;
            r->stub = s;
            continue;
        }
        uint64_t dest = base + r->to;
        for (int j = 0; j < g_n; j++) {
            if (g_p[j].ok && g_p[j].from == r->to) {
                if (!has_tramp[j]) {
                    dest = 0;
                    break;
                }
                dest = tramp[j];
            }
        }
        if (!dest) {
            skipped(r, "its target is patched too and its first instructions cannot be moved");
            continue;
        }
        uint32_t va = (uint32_t)r->to_va;
        emit(&e, 0x52800001u | ((va & 0xFFFFu) << 5));          /* movz w1, #lo */
        emit(&e, 0x72A00001u | ((va >> 16) << 5));              /* movk w1, #hi, lsl #16 */
        emit_jump(&e, dest);
        r->stub = s;
    }
    if (sys_mprotect(area, need, PROT_READ | PROT_EXEC) != 0) {
        say("this device does not allow the trampolines to run (no executable memory); nothing changed");
        sys_munmap(area, need);
        return;
    }
    sync_code(area, area + need);

    /* The entries. */
    int done = 0;
    for (int i = 0; i < g_n; i++) {
        patch_t *r = &g_p[i];
        if (!r->ok) continue;
        uintptr_t at = base + r->from;
        uintptr_t p0 = at & ~(uintptr_t)(psize - 1), p1 = (at + 16 + psize - 1) & ~(uintptr_t)(psize - 1);
        if (sys_mprotect(p0, p1 - p0, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            begin();
            put("could not open the game's code for writing at ");
            put(r->label);
            put("; this and the rest are left out");
            end();
            break;
        }
        uint32_t *w = (uint32_t *)at;
        uint64_t s = r->stub;
        w[2] = (uint32_t)s;
        w[3] = (uint32_t)(s >> 32);
        w[1] = 0xD61F0200u;     /* br x16 */
        w[0] = 0x58000050u;     /* ldr x16, #8 */
        sync_code(at, at + 16);
        sys_mprotect(p0, p1 - p0, PROT_READ | PROT_EXEC);
        done++;
        begin();
        put("applied ");
        put(r->label);
        put(" (");
        put_hex(r->from);
        if (r->type == KIND_REDIRECT) {
            put(" -> ");
            put_hex(r->to);
        } else {
            put(", wrapped");
        }
        put(")");
        end();
    }
    begin();
    put_dec((uint64_t)done);
    put(" of ");
    put_dec((uint64_t)g_n);
    put(" patch(es) applied");
    end();
}

/* The plan's path, from the environment the game started with. */
static const char *plan_path(void) {
    long n = read_file("/proc/self/environ", g_big, sizeof g_big);
    if (n <= 0) return 0;
    static const char key[] = "OPENSA2_PATCHES=";
    for (long i = 0; i < n;) {
        const char *s = g_big + i;
        size_t l = slen(s);
        if (starts(s, key) && l > sizeof key - 1) return s + sizeof key - 1;
        i += (long)l + 1;
    }
    return 0;
}

__attribute__((constructor)) static void opensa2_patch_init(void) {
    const char *path = plan_path();
    if (!path || !*path) return;
    static char pathcopy[512];
    size_t l = slen(path);
    if (l >= sizeof pathcopy) return;
    for (size_t i = 0; i <= l; i++) pathcopy[i] = path[i];
    uintptr_t base = find_base();
    if (!base) return;                       /* not the game */
    if (read_file(pathcopy, g_plan, sizeof g_plan) <= 0) {
        say("cannot read the plan; nothing changed");
        return;
    }
    parse_plan(g_plan);
    if (!g_n) {
        say("the plan has no patches");
        return;
    }
    begin();
    put("libsa2game.so at ");
    put_hex(base);
    put(", ");
    put_dec((uint64_t)g_n);
    put(" patch(es) planned");
    end();
    apply(base, page_size());
}
