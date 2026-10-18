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
 *   A <HorizontalResolution va> <VerticalResolution va> <num> <den>   (layout wrappers' aspect)
 *   U <off of g_hud43> <off of g_hud43_swapped> <its size, 1 or 4> <off of g_hud43_real>
 *   V <guest va> <dwords>        (guest memory logged when a traced screen opens, and later)
 * where an offset is from libsa2game.so's load address (its symbol value). A W line's kind is 0-2
 * for the position tables (its fourth field the State offset of the player's register), 3 for a
 * function laid out at A's aspect (the runtime's HUD guards, U, keep the real width for the
 * viewport and projection inside it), and for working out how a screen draws, 4-8: logged calls
 * (4: EAX, ECX, EDX and the first stack words, the fourth field how many; 5: a Ninja 2D sprite;
 * 6: the runtime's dispatch; 7: memory at a pointer argument, the fourth field the register's
 * State offset or a stack word's index, plus 0x1000 to follow the pointer found there too) and 8,
 * a function whose first run opens the log (as does a kind-3 wrapper's). Each logged function has
 * a budget of lines as the screen opens and another once it has been up a few seconds. Kind 9
 * wraps the port's sprite call so a menu background's 16:9 picture fills a wider screen (below).
 * Kind 10 wraps a character's Load function (fourth field: its group, 0-3) and plays it as a random
 * member of that group each time, with a line `Z <off> <guest va>` for each member (bits 2-5 of the
 * fourth field: other groups it is also a member of; `K <CheckpointRecords va>`: a reload in the
 * same level after a checkpoint was touched keeps the last pick); a second P
 * line for a character gives the position wrappers a second partner to look a level up under.
 * Kind 11 wraps a function of Sonic's action code so that, while it runs, a player whose
 * CharObj2Base.CharID2 is `from` reads `to`, from lines `M <CharID2 offset> <from> <to>` (with
 * the C line's MainCharObj2), an optional fifth field the set of lines a wrapper takes (its fourth
 * field's low four bits; bit 4: also clear that player's Upgrades, CharObj2Base+0x24). Set 1: the
 * Chao Walkers read as Tails's and Eggman's walkers in the walker's main.
 * Kind 12 wraps a restart handler (fourth field 0: pause Restart, 1: after a death) and sends it
 * down the game's restart-with-reload handler, `Y <off> <guest va>`, so random picks again.
 * Kind 13 wraps the stage layout loader: `L <level> <class 0|1|2> <file> <where the game reads it>`
 * lines give a speed stage a walker's layout (class 2) when a walker is to play it.
 * Kind 14 lets a function run only in the levels of `H <level>` lines, elsewhere returning at once
 * through the lifted bare ret, `J <off> <guest va>` (fourth field bit 0: with EAX 0; bits 4-7 the
 * set of H lines, `H <level> [set]`, it takes).
 * Kind 15 trades two texture lists for a call while a player is a CharID2 (`S <set> <CharID2> <va>
 * <va>`); kind 16 writes a 16-bit field after a call (`Q <off> <field> <value>`, the fourth field
 * the State offset of the register holding the structure). Kind 17 makes a shared function return
 * at once while a player is one of some CharIDs, outside a set of levels. Kinds 18 and 19 (Sonic_Main,
 * Sonic_Display) make Sonic and Shadow their super forms in every stage: `X <CharID2> <model file
 * name va> <texture list name va> <model index> [<PhysicsArray index>]`, `F <LoadMDLFile off> <va>
 * <LoadCharTextures off> <va>`; `B <button mask> <rings>` makes it a transformation (the button in
 * a jump with that many rings, a ring a second, back at none or on the button again) instead of
 * the form from the first frame; `D <cue> <cue> <frames> <rrggbb>` flashes the screen as it
 * transforms and plays two voice-bank cues through `O <PlaySoundProbably off> <va>`; `E <CharID2>
 * <texture list va> <name va> <name>` stands a mod's effect texture list in while super. Kind 20
 * wraps PlayVoice: logs each voice number once, and plays `N <voice> <cue>` cues instead while
 * player 1 is Sonic (needs the J line). Kind 21 wraps Super_DispDelay3 for the super form's aura:
 * `a <index> <off> <va>` lines name the game functions it draws with, `c <CharID2> <rrggbb>` tints
 * a character's aura (needs the J and C lines; below).
 *
 * `e <off> <target va> <source va>` lines are fixes to the game itself, not patches: each names
 * a row of the runtime's missing-block edge table (runtime/remill_runtime.h, sa2_mb_edge) whose
 * conditional branch goes into the rest of its own function, which the function map split off
 * as a function of its own (lifted whole from that address). The runtime refuses any branch but
 * a `jmp` into another entry, so the game stopped there (Pyramid Cave, at 0x007059AB); the
 * helper sets the row's `allow` byte, the runtime's own "dispatch although it is not a jmp", as
 * Documentation/dispatch-exceptions.json does for the rows it reviewed. A row whose target,
 * source or kind is not the plan's is left alone.
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
#define SYS_clock_gettime 113
#define SYS_getrandom 278

#define AT_FDCWD (-100)
#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT 0100
#define O_TRUNC 01000
#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4
#define MAP_PRIVATE 2
#define MAP_ANONYMOUS 0x20

#define MAX_PATCHES 64
#define MAX_WRAPS 48
#define MAX_VARS 8
#define MAX_CHARS 32
#define PLAN_MAX (256 * 1024)
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
static long sys_create(const char *path) {
    return sys6(SYS_openat, AT_FDCWD, (long)path, O_WRONLY | O_CREAT | O_TRUNC, 0600, 0, 0);
}
static void sys_close(long fd) { sys6(SYS_close, fd, 0, 0, 0, 0, 0); }
static long sys_read(long fd, void *buf, size_t n) { return sys6(SYS_read, fd, (long)buf, (long)n, 0, 0, 0); }
static long sys_write(long fd, const void *buf, size_t n) { return sys6(SYS_write, fd, (long)buf, (long)n, 0, 0, 0); }
static long sys_mprotect(uintptr_t a, size_t n, int prot) { return sys6(SYS_mprotect, (long)a, (long)n, prot, 0, 0, 0); }
static long sys_mmap(size_t n, int prot) {
    return sys6(SYS_mmap, 0, (long)n, prot, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
}
static void sys_munmap(uintptr_t a, size_t n) { sys6(SYS_munmap, (long)a, (long)n, 0, 0, 0, 0); }
static uint64_t now_ms(void) {
    struct {
        long s, ns;
    } t = {0, 0};
    sys6(SYS_clock_gettime, 1 /* CLOCK_MONOTONIC */, (long)&t, 0, 0, 0, 0);
    return (uint64_t)t.s * 1000u + (uint64_t)t.ns / 1000000u;
}

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

static char g_line[1024];
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
/* One line at a time: the game's threads can log at once (the dispatch runs on all of them). */
static volatile char g_line_lock;
static void begin(void) {
    while (__atomic_test_and_set(&g_line_lock, __ATOMIC_ACQUIRE)) {
    }
    g_len = 0;
    put("opensa2-patch: ");
}
static void end(void) {
    put("\n");
    sys_write(2, g_line, g_len);
    __atomic_clear(&g_line_lock, __ATOMIC_RELEASE);
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
    uint32_t preg, kind;    /* kinds 0-2: position tables (preg: the player's register);
                               3: laid out at an aspect; 4-8: logged (above) */
    int early, late, said;  /* lines left as the screen opens and later; said "enter" */
    uint64_t from;          /* the wrapped function's offset (kind 16's Q lines) */
} wrap_t;

enum { WRAP_LAYOUT = 3, WRAP_TRACE = 4, WRAP_SPRITE = 5, WRAP_DISPATCH = 6, WRAP_PTR = 7, WRAP_GATE = 8, WRAP_FILL = 9, WRAP_RANDOM = 10, WRAP_MOVES = 11, WRAP_RESTART = 12, WRAP_SETLOAD = 13, WRAP_LEVELGATE = 14, WRAP_TEXSWAP = 15, WRAP_POSTSTORE = 16, WRAP_CHARGATE = 17, WRAP_SUPERMAIN = 18, WRAP_SUPERDISP = 19, WRAP_VOICE = 20, WRAP_AURA = 21 };
#define TRACE_EARLY 16
#define TRACE_LATE 48
#define TRACE_LATER_MS 4000
#define TRACE_REARM_MS 3000

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
static uint8_t g_partner[MAX_CHARS], g_partner2[MAX_CHARS];
/* Random swaps (kind 10): each group's members, their guest addresses (Z lines) and original bodies. */
#define MAX_GROUP 16
static uint64_t g_z_off[64];
static uint32_t g_z_va[64];
static int g_nz;
static lifted_fn g_grp_fn[4][MAX_GROUP];
static uint32_t g_grp_va[4][MAX_GROUP];
static int g_grp_n[4];
static uint64_t g_rng;
/* After a death past a checkpoint the stage reloads its character: keep that stage's pick (K line:
 * CheckpointRecords, whose first short is the level a checkpoint was touched in). */
static uint32_t g_cp_va;
/* Kind 12: a restart sent down the game's own restart-with-reload path (Y line: its offset and
 * guest address), so that random picks again; g_force_repick makes that reload pick anew. */
static uint64_t g_reload_off;
static uint32_t g_reload_va;
static int g_force_repick;
static int g_last_k[4] = {-1, -1, -1, -1};
static uint32_t g_last_level[4];
/* Each group member's own group (a walker is in the speed group too, its own group 2). */
static uint8_t g_grp_home[4][MAX_GROUP];
/* Kind 13: stage layouts per kind of stand-in (L lines: level, class 0 speed, 1 hunter or 2 walker, the layout to copy,
 * where the game reads it). The class a speed stage's layout was put in place for, a pick made
 * as the layout loaded (before the characters), and whether a pick came after the last load. */
#define MAX_LAYOUTS 96
#define LAYOUT_PATH 192
static uint32_t g_ly_level[MAX_LAYOUTS], g_ly_class[MAX_LAYOUTS];
static char g_ly_src[MAX_LAYOUTS][LAYOUT_PATH], g_ly_dst[MAX_LAYOUTS][LAYOUT_PATH];
static int g_nly;
static uint32_t g_lay_level = 0xFFFF, g_lay_class;
static int g_pending_k = -1;
static uint32_t g_pending_level = 0xFFFF;
static int g_picked_since_load;
static int g_load_k = -1;   /* this load's pick, for its second layout file */
static uint32_t g_load_level = 0xFFFF;
static char g_copybuf[256 * 1024];
/* Kind 14: functions that run only in some levels (H lines), returning at once elsewhere through
 * the lifted bare `ret` (J line: its offset and guest address). */
#define MAX_HLEVELS 32
static uint32_t g_hlevel[MAX_HLEVELS], g_hset[MAX_HLEVELS];
static int g_nhlevel;
static uint64_t g_ret_off;
static uint32_t g_ret_va;
/* Kind 15: two NJS_TEXLISTs swapped for the length of a call when a player is a CharID2
 * (S lines: set, CharID2, texlist, texlist). Kind 16: a 16-bit field written after the call at
 * a register's address (Q lines: the wrapped function's offset, field offset, value). */
#define MAX_SWAPS 8
static uint32_t g_sw_set[MAX_SWAPS], g_sw_id[MAX_SWAPS], g_sw_a[MAX_SWAPS], g_sw_b[MAX_SWAPS];
static int g_nsw;
#define MAX_POST 8
static uint64_t g_q_from[MAX_POST];
static uint32_t g_q_field[MAX_POST], g_q_value[MAX_POST];
static int g_nq;
/* Kinds 18 and 19: Sonic and Shadow as their super forms in every stage (X lines: CharID2, the
 * game's model file and texture list names, the super model's index; F line: LoadMDLFile and
 * LoadCharTextures). */
#define MAX_SUPER 2
static uint32_t g_x_id[MAX_SUPER], g_x_mdl[MAX_SUPER], g_x_tex[MAX_SUPER], g_x_idx[MAX_SUPER], g_x_phys[MAX_SUPER];
static int g_nx;
static uint64_t g_mdlfn_off, g_texfn_off;
static uint32_t g_mdlfn_va, g_texfn_va;
/* B line: transform as Sora's SA2 Super Sonic does (a button pressed in a jump with at least so
 * many rings; a ring a second after; back at none, or on the button again). No B line: the super
 * form from the first frame, for good. */
static uint32_t g_t_button, g_t_rings;
/* Per player: the character object the super model and textures were loaded for (g_sp_co2), the
 * super texture list, the one it replaced, the model index; whether the form is on, the animation
 * list changed and the body model it had, and the physics it had. */
static uint32_t g_sp_co2[2], g_sp_tex[2], g_sp_old[2], g_sp_idx[2], g_sp_failed[2];
static uint32_t g_sp_on[2], g_sp_list[2], g_sp_best[2];
static uint8_t g_sp_phys[2][0x84];
/* I line: LoadEmeraldManager. In the levels of H set 2 (the hunting stages), when no player is a
 * treasure hunter (CharID 4 or 5), Sonic_Main (kind 18) or the walker main (kind 11) loads the
 * emerald manager once per character object, so a speed character or a walker can hunt there. */
static uint64_t g_em_off;
static uint32_t g_em_va, g_em_co2;
/* E line: while the X line's character (CharID2) with this CharID2 is the super form, the effect
 * texture list at a VA (Sonic's SonEff) reads as a mod's own list, loaded by LoadCharTextures from
 * a name the helper writes into the guest (a pak no stage loads, e.g. CSONEFFTEX). */
static uint32_t g_e_id = 0xFFFFFFFFu, g_e_list, g_e_name_va;
static char g_e_name[32];
static uint32_t g_e_tex[2], g_e_on[2], g_e_saved[2][2];
/* O line: the game's PlaySoundProbably (sound id in ESI, then three arguments on the stack), for
 * sounds the helper plays: bank 3 (the character's voice bank) cue n is 0x3000 + n. */
static uint64_t g_snd_off;
static uint32_t g_snd_va;
/* D line: with the transformation (B line), a green flash (ScreenFadeARGB, which the game draws
 * over the screen while its alpha is not 0) for so many frames, one voice cue as it starts and
 * another as it ends; the form changes a few frames in, at the flash's brightest. */
static uint32_t g_v_cue1 = 0xFFFFFFFFu, g_v_cue2 = 0xFFFFFFFFu, g_v_frames, g_v_rgb;
static uint32_t g_tr_t[2], g_fade_ours;
#define SP_FADE 0x171CDA0u
/* N lines (kind 20, around PlayVoice): while player 1 is Sonic's CharID2 (0), these voice numbers
 * play a cue of the voice bank instead (a mod's own lines for its character); every voice number
 * the game asks for is logged once, so the numbers can be found. */
#define MAX_N 16
static uint32_t g_n_num[MAX_N], g_n_cue[MAX_N], g_nn;
static uint32_t g_vlog[64], g_nvlog;
/* a lines (kind 21): the super aura's game functions, `a <index> <off> <va>` in the order of the
 * AU_ enum below (the display it is drawn in, Super_DispDelay3, and ProcessChunkModel need only
 * their guest address); c lines, `c <CharID2> <rrggbb>`: that character's aura tinted so (a
 * constant material). g_au_live once the kind-21 wrapper is in place; per player the character
 * task given the display, the DisplaySub_Delayed4 it had, the aura's frame count and whether its
 * textures were loaded for the character object. */
enum { AU_DISPLAY, AU_LOADTEX, AU_TRANSLATE, AU_ROTX, AU_ROTY, AU_ROTZ, AU_SCALE, AU_RESETSPACE, AU_DRAW,
       AU_SAVE3D, AU_LOAD3D, AU_ON3D, AU_MATERIAL, AU_RESETMAT, AU_CALLBACK, AU_FNS };
static uint64_t g_au_off[AU_FNS];
static uint32_t g_au_va[AU_FNS];
static uint32_t g_au_cid[2], g_au_rgb[2], g_nau_c;
static uint32_t g_au_live, g_au_obj[2], g_au_old[2], g_au_tick[2], g_au_tex[2], g_au_said[2];
/* Kind 19: whether two texture lists are the same textures in the same order (by name), so the
 * super form's list can draw what the old one would (the ball): asked once per pair. */
static uint32_t g_par_a[2], g_par_b[2], g_par_r[2];
static uint8_t g_callsave[0x400];
/* Kind 11: CharObj2Base.CharID2 values read as another for the length of a call (M lines). */
#define MAX_MOVES 8
static uint32_t g_cid2_off;
static uint8_t g_mv_from[MAX_MOVES], g_mv_to[MAX_MOVES], g_mv_set[MAX_MOVES];
static int g_nmv;

/* The layout wrapper's view: the screen size the game keeps (HorizontalResolution and
 * VerticalResolution, guest floats), the aspect to lay out at, and the runtime's own HUD-at-4:3
 * state (runtime/sa2_widescreen.c: g_hud43, g_hud43_swapped, g_hud43_real), whose guards give the
 * viewport, scissor, projection, njSetScreen and the full-screen background the real width while
 * a swap is on. */
static uintptr_t g_base;
static uint32_t g_h_va, g_v_va, g_num, g_den;
static uint64_t g_hud_off, g_sw_off, g_sw_size, g_real_off;
static int g_in_layout, g_layout_said;
/* The log of how a screen draws: shut until a kind-3 or kind-8 wrapper first runs; the time it
 * opened; whether the later budget has begun; the innermost such wrapper running (its guest
 * address, "in" on each line). */
static int g_trace_gate, g_later, g_disp_left = 400;
static uint64_t g_gate_ms;
static uint32_t g_in_pc, g_tick;
static uint32_t g_var_va[MAX_VARS], g_var_n[MAX_VARS];
static int g_nvars;
typedef void *(*dispatch_fn)(uint32_t va, void *state, uint32_t pc, void *memory);
static dispatch_fn g_disp_orig;
static uint32_t g_disp_seen[1024];

/* e lines: edge table rows to allow (offset from the load address, target, source). */
#define MAX_EDGES 16
static uint64_t g_e_off[MAX_EDGES];
static uint32_t g_e_target[MAX_EDGES], g_e_source[MAX_EDGES];
static int g_ne;

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
    for (int i = 0; i < MAX_CHARS; i++) g_partner[i] = g_partner2[i] = 0xFF;
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
                if (r->wrap_kind > WRAP_AURA) continue;
                if (r->wrap_kind == WRAP_PTR ? (r->preg > 0x1FFF || (r->preg & 0xFFF) > 0x9F0) : r->preg > 0x9F0) continue;
                if ((r->wrap_kind <= 2 || (r->wrap_kind == WRAP_PTR && (r->preg & 0xFFF) >= 0x800)) && (r->preg & 3)) continue;
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
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && a < MAX_CHARS && b < MAX_CHARS) {
                if (g_partner[a] == 0xFF) g_partner[a] = (uint8_t)b;
                else g_partner2[a] = (uint8_t)b;
            }
        } else if (t == 'M') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && a < 0x100 && b < 0x100 &&
                c < 0x100 && g_nmv < MAX_MOVES) {
                g_cid2_off = (uint32_t)a;
                g_mv_from[g_nmv] = (uint8_t)b;
                g_mv_to[g_nmv] = (uint8_t)c;
                g_mv_set[g_nmv] = parse_hex(&s, &d) && d < 16 ? (uint8_t)d : 0;
                g_nmv++;
            }
        } else if (t == 'Y') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b)) {
                g_reload_off = a;
                g_reload_va = (uint32_t)b;
            }
        } else if (t == 'L') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && g_nly < MAX_LAYOUTS && b <= 2) {
                char *src = g_ly_src[g_nly], *dst = g_ly_dst[g_nly];
                size_t k = 0;
                while (*s && *s != ' ' && *s != '\n' && k + 1 < LAYOUT_PATH) src[k++] = *s++;
                src[k] = 0;
                while (*s == ' ') s++;
                k = 0;
                while (*s && *s != ' ' && *s != '\n' && k + 1 < LAYOUT_PATH) dst[k++] = *s++;
                dst[k] = 0;
                if (src[0] == '/' && dst[0] == '/') {
                    g_ly_level[g_nly] = (uint32_t)a;
                    g_ly_class[g_nly] = (uint32_t)b;
                    g_nly++;
                }
            }
        } else if (t == 'S') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d) && g_nsw < MAX_SWAPS) {
                g_sw_set[g_nsw] = (uint32_t)a;
                g_sw_id[g_nsw] = (uint32_t)b;
                g_sw_a[g_nsw] = (uint32_t)c;
                g_sw_b[g_nsw] = (uint32_t)d;
                g_nsw++;
            }
        } else if (t == 'Q') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && g_nq < MAX_POST) {
                g_q_from[g_nq] = a;
                g_q_field[g_nq] = (uint32_t)b;
                g_q_value[g_nq] = (uint32_t)c;
                g_nq++;
            }
        } else if (t == 'X') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d) && g_nx < MAX_SUPER) {
                g_x_id[g_nx] = (uint32_t)a;
                g_x_mdl[g_nx] = (uint32_t)b;
                g_x_tex[g_nx] = (uint32_t)c;
                g_x_idx[g_nx] = (uint32_t)d;
                g_x_phys[g_nx] = parse_hex(&s, &a) ? (uint32_t)a : 0xFFFFFFFFu;
                g_nx++;
            }
        } else if (t == 'I') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b)) {
                g_em_off = a;
                g_em_va = (uint32_t)b;
            }
        } else if (t == 'O') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b)) {
                g_snd_off = a;
                g_snd_va = (uint32_t)b;
            }
        } else if (t == 'D') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d) && c < 1200) {
                g_v_cue1 = (uint32_t)a;
                g_v_cue2 = (uint32_t)b;
                g_v_frames = (uint32_t)c;
                g_v_rgb = (uint32_t)d & 0xFFFFFF;
            }
        } else if (t == 'e') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && g_ne < MAX_EDGES) {
                g_e_off[g_ne] = a;
                g_e_target[g_ne] = (uint32_t)b;
                g_e_source[g_ne] = (uint32_t)c;
                g_ne++;
            }
        } else if (t == 'a') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && a < AU_FNS) {
                g_au_off[a] = b;
                g_au_va[a] = (uint32_t)c;
            }
        } else if (t == 'c') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && a < 0x100 && g_nau_c < 2) {
                g_au_cid[g_nau_c] = (uint32_t)a;
                g_au_rgb[g_nau_c] = (uint32_t)b & 0xFFFFFF;
                g_nau_c++;
            }
        } else if (t == 'N') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && g_nn < MAX_N) {
                g_n_num[g_nn] = (uint32_t)a;
                g_n_cue[g_nn] = (uint32_t)b;
                g_nn++;
            }
        } else if (t == 'E') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c)) {
                int n = 0;
                while (n < 31 && s[n] && s[n] != '\n' && s[n] != '\r' && s[n] != ' ') {
                    g_e_name[n] = s[n];
                    n++;
                }
                g_e_name[n] = 0;
                if (n) {
                    g_e_id = (uint32_t)a;
                    g_e_list = (uint32_t)b;
                    g_e_name_va = (uint32_t)c;
                }
            }
        } else if (t == 'B') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b)) {
                g_t_button = (uint32_t)a;
                g_t_rings = (uint32_t)b;
            }
        } else if (t == 'F') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d)) {
                g_mdlfn_off = a;
                g_mdlfn_va = (uint32_t)b;
                g_texfn_off = c;
                g_texfn_va = (uint32_t)d;
            }
        } else if (t == 'H') {
            if (parse_hex(&s, &a) && g_nhlevel < MAX_HLEVELS) {
                g_hset[g_nhlevel] = parse_hex(&s, &b) && b < 16 ? (uint32_t)b : 0;
                g_hlevel[g_nhlevel++] = (uint32_t)a;
            }
        } else if (t == 'J') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b)) {
                g_ret_off = a;
                g_ret_va = (uint32_t)b;
            }
        } else if (t == 'K') {
            if (parse_hex(&s, &a)) g_cp_va = (uint32_t)a;
        } else if (t == 'Z') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && g_nz < 64) {
                g_z_off[g_nz] = a;
                g_z_va[g_nz] = (uint32_t)b;
                g_nz++;
            }
        } else if (t == 'A') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &d) && c && d) {
                g_h_va = (uint32_t)a;
                g_v_va = (uint32_t)b;
                g_num = (uint32_t)c;
                g_den = (uint32_t)d;
            }
        } else if (t == 'V') {
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && g_nvars < MAX_VARS && b && b <= 16) {
                g_var_va[g_nvars] = (uint32_t)a;
                g_var_n[g_nvars] = (uint32_t)b;
                g_nvars++;
            }
        } else if (t == 'U') {
            uint64_t e;
            if (parse_hex(&s, &a) && parse_hex(&s, &b) && parse_hex(&s, &c) && parse_hex(&s, &e) &&
                (c == 1 || c == 4)) {
                g_hud_off = a;
                g_sw_off = b;
                g_sw_size = c;
                g_real_off = e;
            }
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

static void g_wr32(uint8_t *g, uint32_t a, uint32_t v) {
    g[a] = (uint8_t)v;
    g[a + 1] = (uint8_t)(v >> 8);
    g[a + 2] = (uint8_t)(v >> 16);
    g[a + 3] = (uint8_t)(v >> 24);
}

static float f_of(uint32_t b) {
    union {
        uint32_t u;
        float f;
    } x;
    x.u = b;
    return x.f;
}
static uint32_t b_of(float f) {
    union {
        uint32_t u;
        float f;
    } x;
    x.f = f;
    return x.u;
}

/* ------------------------------------------------------------------ the drawing log */

static uint8_t *guest(void) { return g_fastbase ? *(uint8_t **)g_fastbase : 0; }
static uint32_t st32(const void *state, uint32_t off) { return *(const uint32_t *)((const uint8_t *)state + off); }
static uint32_t esp_of(const void *state) { return st32(state, 0x908) + st32(state, 0x848); }

/* The guest memory the plan names (V lines), one line each. */
static void log_vars(const char *what) {
    uint8_t *g = guest();
    if (!g) return;
    for (int i = 0; i < g_nvars; i++) {
        if (!g_ok(g_var_va[i], 4 * g_var_n[i])) continue;
        begin();
        put(what);
        put(" ");
        put_hex(g_var_va[i]);
        for (uint32_t k = 0; k < g_var_n[i]; k++) {
            put(" ");
            put_hex(g_rd32(g, g_var_va[i] + 4 * k));
        }
        end();
    }
}

/* A screen function's first run (re)opens the log with fresh budgets, unless another opened it in
 * the last few seconds (the same screen): if one of them also runs somewhere else first, the screen
 * that matters still gets its lines. */
static void gate_open(uint32_t pc) {
    uint64_t t = now_ms();
    if (g_trace_gate && t < g_gate_ms + TRACE_REARM_MS) return;
    g_trace_gate = 1;
    g_gate_ms = t;
    g_later = 0;
    for (int i = 0; i < g_nwrap; i++) {
        g_wrap[i].early = TRACE_EARLY;
        g_wrap[i].late = TRACE_LATE;
    }
    if (g_disp_left < 200) g_disp_left = 200;
    begin();
    put("trace: ");
    put_hex(pc);
    put(" ran, so its screen is open: logging what it draws (@ is ms since)");
    end();
    log_vars("vars at open");
}

/* The time since the log opened, and the screen function the line comes from, if any. */
static void put_ctx(void) {
    put(" @");
    put_dec(now_ms() - g_gate_ms);
    if (g_in_pc) {
        put(" in ");
        put_hex(g_in_pc);
    }
}

/* Whether wrapper w may log a line now: its own budget as the screen opens, then, once the screen
 * has been up a few seconds (settled, not sliding in), a second one. */
static int trace_take(wrap_t *w) {
    if (!g_trace_gate) return 0;
    if (w->early > 0) {
        w->early--;
        return 1;
    }
    if (w->late <= 0) return 0;
    if (!g_later) {
        if ((++g_tick & 31u) != 0 || now_ms() < g_gate_ms + TRACE_LATER_MS) return 0;
        g_later = 1;
        begin();
        put("trace: the screen has been up ");
        put_dec(TRACE_LATER_MS);
        put(" ms; logging again");
        end();
        log_vars("vars later");
    }
    w->late--;
    return 1;
}

/* A screen function's first run: opens the log (above) and says so. */
static void say_enter(wrap_t *w, uint32_t pc) {
    if (w->said) return;
    w->said = 1;
    gate_open(pc);
    begin();
    put("enter ");
    put_hex(pc);
    put_ctx();
    end();
}

/* Kind 3: the wrapped function lays itself out for a screen of g_num:g_den when the real one is
 * wider. HorizontalResolution reads V x num/den for the length of the call, as the runtime's HUD
 * at 4:3 does for the HUD (same guards, same state, so those calls keep the real width), and is
 * put back after. A screen no wider than num:den, or a call inside the runtime's own swap, is
 * left alone. Its first run opens the drawing log. */
static void *wrap_layout(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_guest_size || !g_h_va || !g_hud_off || !g_ok(g_h_va, 4) || !g_ok(g_v_va, 4))
        return w->orig(state, pc, memory);
    uint32_t hb = g_rd32(g, g_h_va), vb = g_rd32(g, g_v_va);
    float h = f_of(hb), v = f_of(vb);
    if (!(v > 0.0f) || !(h * (float)g_den > v * (float)g_num * 1.001f)) return w->orig(state, pc, memory);
    volatile int32_t *hud = (volatile int32_t *)(g_base + g_hud_off);
    volatile uint8_t *sw8 = (volatile uint8_t *)(g_base + g_sw_off);
    volatile uint32_t *sw32 = (volatile uint32_t *)(g_base + g_sw_off);
    volatile uint32_t *real = (volatile uint32_t *)(g_base + g_real_off);
    uint32_t swapped = g_sw_size == 1 ? (*sw8 & 1u) : *sw32;
    if (swapped) return w->orig(state, pc, memory);
    int32_t saved = *hud;
    float h2 = v * (float)g_num / (float)g_den;
    if (!g_layout_said) {
        g_layout_said = 1;
        begin();
        put("laid out at ");
        put_dec(g_num);
        put(":");
        put_dec(g_den);
        put(": width ");
        put_dec((uint64_t)h);
        put(" -> ");
        put_dec((uint64_t)h2);
        put(" while it draws, the viewport and projection keeping ");
        put_dec((uint64_t)h);
        end();
    }
    say_enter(w, pc);
    *real = hb;
    if (g_sw_size == 1) *sw8 = 1;
    else *sw32 = 1;
    *hud = 1;
    g_wr32(g, g_h_va, b_of(h2));
    uint32_t outer = g_in_pc;
    g_in_pc = pc;
    g_in_layout++;
    void *r = w->orig(state, pc, memory);
    g_in_layout--;
    g_in_pc = outer;
    g_wr32(g, g_h_va, hb);
    if (g_sw_size == 1) *sw8 = 0;
    else *sw32 = 0;
    *hud = saved;
    return r;
}

/* Kind 8: a function of the screen being worked out; its first run opens the log, and lines
 * logged inside it say so. */
static void *wrap_gate(wrap_t *w, void *state, uint32_t pc, void *memory) {
    say_enter(w, pc);
    uint32_t outer = g_in_pc;
    g_in_pc = pc;
    void *r = w->orig(state, pc, memory);
    g_in_pc = outer;
    return r;
}

/* Kind 4: logs a call's return address, EAX, ECX, EDX and its first stack words (a cdecl
 * function's arguments), then makes the call. */
static void *wrap_trace(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (g && trace_take(w)) {
        uint32_t esp = esp_of(state);
        uint32_t n = w->preg > 12 ? 12 : w->preg;
        if (g_ok(esp, 4 * (n + 1))) {
            begin();
            put("trace ");
            put_hex(pc);
            put(" ret ");
            put_hex(g_rd32(g, esp));
            put(" eax ");
            put_hex(st32(state, 0x8A8));
            put(" ecx ");
            put_hex(st32(state, 0x8C8));
            put(" edx ");
            put_hex(st32(state, 0x8D8));
            put(" args");
            for (uint32_t i = 1; i <= n; i++) {
                put(" ");
                put_hex(g_rd32(g, esp + 4 * i));
            }
            put_ctx();
            end();
        }
    }
    return w->orig(state, pc, memory);
}

/* Kind 5: a Ninja 2D sprite draw (njDrawSprite2D and the like) logged with its NJS_SPRITE (position,
 * scale, angle, texture list, animation table) and the NJS_TEXANIM entry it draws (size, centre,
 * UVs, texture), and the screen width it was drawn with; the sprite pointer is the first stack
 * word (preg 0) or EBX (preg 1). */
static void *wrap_sprite(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (g && g_trace_gate) {
        uint32_t esp = esp_of(state);
        uint32_t n = st32(state, 0x8A8);
        uint32_t sp = 0;
        if (g_ok(esp, 8)) sp = w->preg == 1 ? st32(state, 0x8B8) : g_rd32(g, esp + 4);
        if (g_ok(esp, 8) && g_ok(sp, 32) && trace_take(w)) {
            begin();
            put("sprite ");
            put_hex(pc);
            put(" ret ");
            put_hex(g_rd32(g, esp));
            put(" n ");
            put_hex(n);
            put(" p");
            for (uint32_t i = 0; i < 8; i++) {      /* x y z sx sy ang tlist tanim */
                put(" ");
                put_hex(g_rd32(g, sp + 4 * i));
            }
            uint32_t ta = g_rd32(g, sp + 28);
            if (n < 4096 && g_ok(ta + n * 20, 20)) {
                put(" anim");
                for (uint32_t i = 0; i < 5; i++) {  /* sx|sy cx|cy u1|v1 u2|v2 texid|attr */
                    put(" ");
                    put_hex(g_rd32(g, ta + n * 20 + 4 * i));
                }
            }
            if (g_h_va && g_ok(g_h_va, 4)) {
                put(" H ");
                put_hex(g_rd32(g, g_h_va));
            }
            put_ctx();
            end();
        }
    }
    return w->orig(state, pc, memory);
}

/* Kind 7: a draw whose argument points at its vertices or parameters (njDrawTexture,
 * njDrawPolygon2D, njSetScreen...): the 24 words there, and with 0x1000 in preg the 8 words at the
 * first of them (a structure that points at its points). preg: a register's State offset (0x8A8
 * EAX ...) or a stack word's index. */
static void *wrap_ptr(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (g && g_trace_gate) {
        uint32_t esp = esp_of(state), src = w->preg & 0xFFFu, at = 0;
        if (src >= 0x800) at = st32(state, src);
        else if (g_ok(esp, 4 * (src + 1))) at = g_rd32(g, esp + 4 * src);
        if (g_ok(esp, 4) && g_ok(at, 96) && trace_take(w)) {
            begin();
            put("ptr ");
            put_hex(pc);
            put(" ret ");
            put_hex(g_rd32(g, esp));
            put(" eax ");
            put_hex(st32(state, 0x8A8));
            put(" ecx ");
            put_hex(st32(state, 0x8C8));
            put(" edx ");
            put_hex(st32(state, 0x8D8));
            put(" arg1 ");
            put_hex(g_ok(esp, 8) ? g_rd32(g, esp + 4) : 0);
            put(" at ");
            put_hex(at);
            for (uint32_t i = 0; i < 24; i++) {
                put(" ");
                put_hex(g_rd32(g, at + 4 * i));
            }
            uint32_t at2 = g_rd32(g, at);
            if ((w->preg & 0x1000u) && g_ok(at2, 32)) {
                put(" then ");
                put_hex(at2);
                for (uint32_t i = 0; i < 8; i++) {
                    put(" ");
                    put_hex(g_rd32(g, at2 + 4 * i));
                }
            }
            put_ctx();
            end();
        }
    }
    return w->orig(state, pc, memory);
}

/* Kind 6: the runtime's dispatch (sa2_ft_dispatch, every indirect call and jump), logging each
 * guest address it is asked for the first time once the log is open: which objects' functions a
 * screen runs. */
static void *wrap_dispatch(uint32_t va, void *state, uint32_t pc, void *memory) {
    if (g_trace_gate && g_disp_left > 0) {
        uint32_t h = (va * 2654435761u) >> 22;
        for (uint32_t i = 0; i < 1024; i++) {
            uint32_t k = (h + i) & 1023u;
            if (g_disp_seen[k] == va) break;
            if (!g_disp_seen[k]) {
                g_disp_seen[k] = va;
                g_disp_left--;
                uint8_t *g = guest();
                uint32_t esp = esp_of(state);
                begin();
                put("call ");
                put_hex(va);
                if (g && g_ok(esp, 4)) {
                    put(" ret ");
                    put_hex(g_rd32(g, esp));
                }
                put_ctx();
                end();
                break;
            }
        }
    }
    return g_disp_orig(va, state, pc, memory);
}

/* Kind 9: the port's sprite call (SOCDisplaySprite: EAX the texture, then PosX, PosY, Width, Height,
 * pri, u0, v0, u1, v1, colour on the stack). The menu backgrounds (story select, Settings) are
 * 1024x512 textures whose picture is 853x480, a 16:9 screen in the 640x480 layout; the game draws
 * them from the screen's left edge to its right one with one texel a unit, so on a screen wider
 * than 16:9 the picture ends at the 16:9 width and the empty rest of the texture shows (black).
 * Such a call -- spanning the screen, a texel a unit -- draws the picture across the whole width
 * instead: u to the picture's width, v cropped about its centre by the same factor (no stretch).
 * The arguments are put back after the call. */
static int g_fill_said;
static void *wrap_fill(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_h_va || !g_ok(g_h_va, 4) || !g_ok(g_v_va, 4)) return w->orig(state, pc, memory);
    uint32_t esp = esp_of(state);
    if (!g_ok(esp, 44)) return w->orig(state, pc, memory);
    float h = f_of(g_rd32(g, g_h_va)), v = f_of(g_rd32(g, g_v_va));
    if (!(v > 0.0f)) return w->orig(state, pc, memory);
    float sw = 480.0f * h / v;                       /* the screen's width in 640x480 units */
    const float pic = 480.0f * 16.0f / 9.0f;         /* the picture's: 853.33 */
    float x = f_of(g_rd32(g, esp + 4)), wd = f_of(g_rd32(g, esp + 12));
    float u0 = f_of(g_rd32(g, esp + 24)), v0 = f_of(g_rd32(g, esp + 28));
    float u1 = f_of(g_rd32(g, esp + 32)), v1 = f_of(g_rd32(g, esp + 36));
    float spanx = x - (320.0f - sw * 0.5f), spanw = wd - sw, tex = (u1 - u0) * 1024.0f - wd;
    if (!(sw > pic + 1.0f) || spanx < -1.0f || spanx > 1.0f || spanw < -1.5f || spanw > 1.5f || tex < -2.0f ||
        tex > 2.0f)
        return w->orig(state, pc, memory);
    float k = wd / pic, dv = v1 - v0, nv = dv / k;
    uint32_t saved[4];
    for (int i = 0; i < 4; i++) saved[i] = g_rd32(g, esp + 24 + 4 * i);
    g_wr32(g, esp + 32, b_of(u0 + pic / 1024.0f));
    g_wr32(g, esp + 28, b_of(v0 + (dv - nv) * 0.5f));
    g_wr32(g, esp + 36, b_of(v0 + (dv - nv) * 0.5f + nv));
    if (!g_fill_said) {
        g_fill_said = 1;
        begin();
        put("menu background drawn across the screen's ");
        put_dec((uint64_t)h);
        put(" px (its picture ends at the 16:9 width), from the call at ");
        put_hex(g_rd32(g, esp));
        end();
    }
    void *r = w->orig(state, pc, memory);
    for (int i = 0; i < 4; i++) g_wr32(g, esp + 24 + 4 * i, saved[i]);
    return r;
}

/* Kind 10: a character's Load function, played as a random member of its group (preg: the group)
 * each time it runs, so every stage can bring someone else of the same play style. */
static uint32_t rnd(void) {
    if (!g_rng) {
        uint64_t seed = 0;
        sys6(SYS_getrandom, (long)&seed, 8, 0, 0, 0, 0);
        g_rng = seed ^ (now_ms() * 0x9E3779B97F4A7C15ull) ^ 1;
    }
    g_rng ^= g_rng << 13;
    g_rng ^= g_rng >> 7;
    g_rng ^= g_rng << 17;
    return (uint32_t)(g_rng >> 16);
}
static void *wrap_random(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint32_t grp = w->preg & 3;
    int n = g_grp_n[grp];
    if (n <= 1) return w->orig(state, pc, memory);
    uint8_t *g = guest();
    uint32_t level = 0xFFFF, cp = 0xFFFE;
    if (g && g_guest_size && g_level_va && g_ok(g_level_va, 2)) level = g_rd16(g, g_level_va);
    if (g && g_guest_size && g_cp_va && g_ok(g_cp_va, 2)) cp = g_rd16(g, g_cp_va);
    int k, kept = 0;
    if (grp == 0 && !g_force_repick && g_pending_k >= 0 && g_pending_k < n && level == g_pending_level) {
        k = g_pending_k;   /* picked as the stage's layout loaded */
    } else if (!g_force_repick && g_last_k[grp] >= 0 && g_last_k[grp] < n && level != 0xFFFF &&
        level == g_last_level[grp] && cp == level) {
        k = g_last_k[grp];
        kept = 1;
    } else if (grp == 0 && g_force_repick && level == g_lay_level) {
        /* A restart keeps the stage's layout, which suits one kind (the class of the pick it was laid out for): pick
         * among those. */
        int c[MAX_GROUP], m = 0;
        for (int i = 0; i < n; i++)
            if (g_grp_home[0][i] == g_lay_class) c[m++] = i;
        k = m ? c[rnd() % (uint32_t)m] : (int)(rnd() % (uint32_t)n);
    } else {
        k = (int)(rnd() % (uint32_t)n);
    }
    if (grp == 0) {
        g_pending_k = -1;
        g_load_k = -1;
        g_picked_since_load = 1;
    }
    g_force_repick = 0;
    g_last_k[grp] = k;
    g_last_level[grp] = level;
    begin();
    put("random: ");
    put_hex(pc);
    put(kept ? " kept as " : " played as ");
    put_hex(g_grp_va[grp][k]);
    if (kept) put(" (a checkpoint was reached)");
    end();
    return g_grp_fn[grp][k](state, g_grp_va[grp][k], memory);
}

/* Kind 11: Sonic's action code starts a Spin Dash only for CharID2 Sonic or Shadow (a check in
 * the middle of the function), so Amy and Metal Sonic, who run the same code, have none. For the
 * length of the wrapped call each player whose CharID2 is an M line's `from` reads its `to`, and
 * is put back after (unless the game changed it meanwhile). A nested call finds it already
 * changed and leaves it to the outer one. */
static uint32_t guest_call(void *state, void *memory, uint64_t off, uint32_t va, uint32_t eax, uint32_t ecx);
static void hunt_check(void *state, void *memory, uint8_t *g) {
    if (!g_em_off || !g || !g_guest_size || !g_co2_va || !g_level_va || !g_ok(g_level_va, 2)) return;
    if (!g_ok(0x1934BE0u, 2) || g_rd16(g, 0x1934BE0u) != 16) return;   /* in game */
    uint32_t level = g_rd16(g, g_level_va);
    int hunt = 0;
    for (int i = 0; i < g_nhlevel; i++) hunt |= g_hlevel[i] == level && g_hset[i] == 2;
    if (!hunt || !g_ok(g_co2_va, 8)) return;
    uint32_t co2 = g_rd32(g, g_co2_va);
    if (!co2 || co2 == g_em_co2) return;
    g_em_co2 = co2;
    for (int p = 0; p < 2; p++) {
        uint32_t c = g_rd32(g, g_co2_va + 4 * (uint32_t)p);
        if (c && g_ok(c + g_charid_off, 1) && (g[c + g_charid_off] == 4 || g[c + g_charid_off] == 5)) return;
    }
    guest_call(state, memory, g_em_off, g_em_va, 0, 0);
    begin();
    put("hunt: the emerald manager loaded for a non-hunter in stage ");
    put_hex(level);
    end();
}

static void *wrap_moves(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    hunt_check(state, memory, g);
    if (!g || !g_guest_size || !g_co2_va || !g_nmv) return w->orig(state, pc, memory);
    uint32_t at[2] = {0, 0};
    uint8_t was[2] = {0, 0}, now[2] = {0, 0};
    for (int p = 0; p < 2; p++) {
        if (!g_ok(g_co2_va + 4 * p, 4)) continue;
        uint32_t co2 = g_rd32(g, g_co2_va + 4 * p);
        if (!co2 || !g_ok(co2 + g_cid2_off, 1)) continue;
        uint8_t id = g[co2 + g_cid2_off];
        for (int k = 0; k < g_nmv; k++) {
            if (g_mv_from[k] != id || g_mv_set[k] != (w->preg & 15)) continue;
            /* Fourth field bit 4: the player's upgrades are cleared too (and stay cleared). */
            if ((w->preg & 0x10) && g_ok(co2 + 0x24, 4)) g_wr32(g, co2 + 0x24, 0);
            at[p] = co2 + g_cid2_off;
            was[p] = id;
            now[p] = g_mv_to[k];
            g[at[p]] = now[p];
            if (!w->said) {
                w->said = 1;
                begin();
                put("moves: ");
                put_hex(pc);
                put(" runs for character ");
                put_hex(id);
                put(" as ");
                put_hex(now[p]);
                end();
            }
            break;
        }
    }
    void *r = w->orig(state, pc, memory);
    for (int p = 0; p < 2; p++)
        if (at[p] && g[at[p]] == now[p]) g[at[p]] = was[p];
    return r;
}

/* Kind 12: one of the game's restart handlers (fourth field 0: the pause menu's Restart, from the
 * stage's start; 1: the restart after a death, from a checkpoint if one was touched). Those keep
 * the character object; the game's own restart-with-reload handler (Y line) fades, tears the
 * stage down and moves to GameState 5, whose handler deletes the characters and loads them again,
 * which is where random picks. A pause Restart always goes that way; a death only before any
 * checkpoint (CheckpointRecords[0].level not the current level). */
static void *wrap_restart(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_guest_size || !g_reload_off || !g_level_va || !g_ok(g_level_va, 2))
        return w->orig(state, pc, memory);
    if (w->preg == 1 && g_cp_va && g_ok(g_cp_va, 2) && g_rd16(g, g_cp_va) == g_rd16(g, g_level_va)) {
        if (!w->said) {
            w->said = 1;
            begin();
            put("restart: ");
            put_hex(pc);
            put(" after a checkpoint keeps the character");
            end();
        }
        return w->orig(state, pc, memory);
    }
    if (!w->said) {
        w->said = 1;
        begin();
        put("restart: ");
        put_hex(pc);
        put(" goes the way of ");
        put_hex(g_reload_va);
        put(", which loads the character again");
        end();
    }
    g_force_repick = 1;
    return ((lifted_fn)(g_base + g_reload_off))(state, g_reload_va, memory);
}

/* Kind 13: the game's stage layout loader (LoadStageSETFile). A speed stage with L lines gets the
 * layout for the character about to play it: the pick is made here when the layout loads first
 * (wrap_random takes it), or taken from the pick just made; a walker's (class 2) or the others'
 * (class 0) layout files are copied over the ones the game is about to read. */
static int copy_file(const char *src, const char *dst) {
    long in = sys_open(src);
    if (in < 0) return 0;
    long n = 0, r;
    while (n < (long)sizeof g_copybuf && (r = sys_read(in, g_copybuf + n, sizeof g_copybuf - (size_t)n)) > 0) n += r;
    sys_close(in);
    if (n <= 0 || n >= (long)sizeof g_copybuf) return 0;
    long out = sys_create(dst);
    if (out < 0) return 0;
    long w = sys_write(out, g_copybuf, (size_t)n);
    sys_close(out);
    return w == n;
}
static void *wrap_setload(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_guest_size || !g_level_va || !g_ok(g_level_va, 2) || !g_nly)
        return w->orig(state, pc, memory);
    uint32_t level = g_rd16(g, g_level_va);
    int any = 0;
    for (int i = 0; i < g_nly; i++) any |= g_ly_level[i] == level;
    if (!any) return w->orig(state, pc, memory);
    if (g_grp_n[0] <= 1) {
        /* No random pick: a chosen swap. The characters load before the layout, so the class is
         * the loaded player's (CharID 4 and 5 hunters, 6 and 7 walkers). */
        uint32_t cls = 0, id = 0xFF;
        if (g_co2_va && g_ok(g_co2_va, 4)) {
            uint32_t co2 = g_rd32(g, g_co2_va);
            if (co2 && g_ok(co2 + g_charid_off, 1)) id = g[co2 + g_charid_off];
        }
        if (id == 4 || id == 5) cls = 1;
        else if (id == 6 || id == 7) cls = 2;
        int done = 0, wanted = 0;
        for (int i = 0; i < g_nly; i++) {
            if (g_ly_level[i] != level || g_ly_class[i] != cls) continue;
            wanted++;
            done += copy_file(g_ly_src[i], g_ly_dst[i]);
        }
        begin();
        put("layout: stage ");
        put_hex(level);
        put(" set out for CharID ");
        put_hex(id);
        put(" (class ");
        put_dec(cls);
        put(", ");
        put_dec((uint64_t)done);
        put(" of ");
        put_dec((uint64_t)wanted);
        put(" file(s))");
        end();
        return w->orig(state, pc, memory);
    }
    int k;
    if (g_load_k >= 0 && g_load_level == level) {
        k = g_load_k;   /* a second layout file of the same load */
    } else if (g_picked_since_load && g_last_level[0] == level && g_last_k[0] >= 0) {
        k = g_last_k[0];   /* the characters loaded first */
    } else {
        k = (int)(rnd() % (uint32_t)g_grp_n[0]);
        g_pending_k = k;
        g_pending_level = level;
    }
    g_load_k = k;
    g_load_level = level;
    uint32_t cls = g_grp_home[0][k] <= 2 ? g_grp_home[0][k] : 0;
    int done = 0, wanted = 0;
    for (int i = 0; i < g_nly; i++) {
        if (g_ly_level[i] != level || g_ly_class[i] != cls) continue;
        wanted++;
        done += copy_file(g_ly_src[i], g_ly_dst[i]);
    }
    g_lay_level = level;
    g_lay_class = cls;
    g_picked_since_load = 0;
    begin();
    put("layout: stage ");
    put_hex(level);
    put(cls == 2 ? " set out for a walker (" : cls == 1 ? " set out for a treasure hunter (" : " set out as usual (");
    put_dec((uint64_t)done);
    put(" of ");
    put_dec((uint64_t)wanted);
    put(" file(s)), for ");
    put_hex(g_grp_va[0][k]);
    end();
    return w->orig(state, pc, memory);
}

/* Kind 14: a hunter's emerald manager and level bounds (LoadEmeraldManager, Knuckles_LevelBounds)
 * belong to the hunting stages: in any other level (a hunter standing in for Sonic) the call
 * returns at once (fourth field 1: with EAX 0, "no bounds"), as Character Select Plus does. */
static void *wrap_levelgate(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_guest_size || !g_level_va || !g_ok(g_level_va, 2) || !g_ret_off)
        return w->orig(state, pc, memory);
    uint32_t level = g_rd16(g, g_level_va);
    for (int i = 0; i < g_nhlevel; i++)
        if (g_hlevel[i] == level && g_hset[i] == ((w->preg >> 4) & 15)) return w->orig(state, pc, memory);
    if (!w->said) {
        w->said = 1;
        begin();
        put("level gate: ");
        put_hex(pc);
        put(" skipped in stage ");
        put_hex(level);
        end();
    }
    if (w->preg & 1) *(uint32_t *)((uint8_t *)state + 0x8A8) = 0;
    return ((lifted_fn)(g_base + g_ret_off))(state, g_ret_va, memory);
}

/* Kind 15: the spin dash's particles (LightAttackChargingParticle and LightAttackParticle) take
 * Sonic's effect texture list for CharID 0 and Shadow's otherwise; Amy (CharID 0) and Metal Sonic
 * (CharID 1) have their own loaded instead, so the particles drew from a list with no textures.
 * While such a function runs and a player is the S line's CharID2, the two lists trade places
 * (MainMemory's SA2-Amy-Metal-SpinDash picks the list by CharID2 there instead). */
static void swap8(uint8_t *g, uint32_t a, uint32_t b) {
    for (int i = 0; i < 8; i++) {
        uint8_t t = g[a + i];
        g[a + i] = g[b + i];
        g[b + i] = t;
    }
}
static void *wrap_texswap(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_guest_size || !g_co2_va) return w->orig(state, pc, memory);
    int done[MAX_SWAPS];
    for (int k = 0; k < g_nsw; k++) {
        done[k] = 0;
        if (g_sw_set[k] != (w->preg & 15) || !g_ok(g_sw_a[k], 8) || !g_ok(g_sw_b[k], 8)) continue;
        for (int p = 0; p < 2 && !done[k]; p++) {
            if (!g_ok(g_co2_va + 4 * p, 4)) continue;
            uint32_t co2 = g_rd32(g, g_co2_va + 4 * p);
            if (co2 && g_ok(co2 + g_cid2_off, 1) && g[co2 + g_cid2_off] == g_sw_id[k]) done[k] = 1;
        }
        if (done[k]) swap8(g, g_sw_a[k], g_sw_b[k]);
    }
    void *r = w->orig(state, pc, memory);
    for (int k = g_nsw - 1; k >= 0; k--)
        if (done[k]) swap8(g, g_sw_a[k], g_sw_b[k]);
    return r;
}

/* Kind 16: after the call, a 16-bit value at a field of the structure a register (fourth field: its
 * State offset; the register is callee-saved, so still the caller's) points to. */
static void *wrap_poststore(wrap_t *w, void *state, uint32_t pc, void *memory) {
    void *r = w->orig(state, pc, memory);
    uint8_t *g = guest();
    if (!g || !g_guest_size) return r;
    uint32_t at = *(uint32_t *)((uint8_t *)state + w->preg);
    for (int k = 0; k < g_nq; k++) {
        if (g_q_from[k] != w->from) continue;
        if (at && g_ok(at + g_q_field[k], 2)) {
            g[at + g_q_field[k]] = (uint8_t)g_q_value[k];
            g[at + g_q_field[k] + 1] = (uint8_t)(g_q_value[k] >> 8);
        }
    }
    return r;
}

/* Kind 17: a function shared by every character that one character must not run outside some
 * levels (Super Sonic's and Super Shadow's main call PResetPos, which the SA2 Randomizer nops
 * there). The fourth field: bits 0-3 the H set of levels where it always runs, bits 4-11 the
 * CharIDs (bit 4 + id - 8, so 8-15) for which it returns at once elsewhere. */
static void *wrap_chargate(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    if (!g || !g_guest_size || !g_level_va || !g_co2_va || !g_ok(g_level_va, 2) || !g_ret_off)
        return w->orig(state, pc, memory);
    uint32_t level = g_rd16(g, g_level_va);
    for (int i = 0; i < g_nhlevel; i++)
        if (g_hlevel[i] == level && g_hset[i] == (w->preg & 15)) return w->orig(state, pc, memory);
    int hit = 0;
    for (int p = 0; p < 2 && !hit; p++) {
        if (!g_ok(g_co2_va + 4 * p, 4)) continue;
        uint32_t co2 = g_rd32(g, g_co2_va + 4 * p);
        if (!co2 || !g_ok(co2 + g_charid_off + 2, 1)) continue;
        /* CharID or CharID2 (two bytes on): a super form may carry its own in either. */
        for (uint32_t k = 0; k <= 2; k += 2) {
            uint32_t id = g[co2 + g_charid_off + k];
            if (id >= 8 && id < 16 && ((w->preg >> (4 + id - 8)) & 1)) hit = 1;
        }
    }
    if (!hit) return w->orig(state, pc, memory);
    if (!w->said) {
        w->said = 1;
        begin();
        put("character gate: ");
        put_hex(pc);
        put(" skipped in stage ");
        put_hex(level);
        end();
    }
    return ((lifted_fn)(g_base + g_ret_off))(state, g_ret_va, memory);
}

/* Calls a lifted function of the game from inside a wrapper, on the same State: EAX and ECX set,
 * a return address pushed on the guest stack, and every register put back after but EAX, which is
 * returned. The State's 0x800-0xBFF (the registers, flags and pc) are saved around the call. */
static uint32_t guest_call(void *state, void *memory, uint64_t off, uint32_t va, uint32_t eax, uint32_t ecx) {
    uint8_t *g = guest();
    uint8_t *st = (uint8_t *)state;
    for (int i = 0; i < 0x400; i++) g_callsave[i] = st[0x800 + i];
    uint32_t esp = *(uint32_t *)(st + 0x908) - 4;
    if (!g_ok(esp, 4)) return 0;
    g_wr32(g, esp, 0x401234);
    *(uint32_t *)(st + 0x908) = esp;
    *(uint32_t *)(st + 0x8A8) = eax;
    *(uint32_t *)(st + 0x8C8) = ecx;
    ((lifted_fn)(g_base + off))(state, va, memory);
    uint32_t r = *(uint32_t *)(st + 0x8A8);
    for (int i = 0; i < 0x400; i++) st[0x800 + i] = g_callsave[i];
    return r;
}

/* As guest_call, with ESI set and up to three 32-bit arguments pushed (the caller's to pop). */
static uint32_t guest_call_args(void *state, void *memory, uint64_t off, uint32_t va, uint32_t esi,
                                const uint32_t *args, int nargs) {
    uint8_t *g = guest();
    uint8_t *st = (uint8_t *)state;
    for (int i = 0; i < 0x400; i++) g_callsave[i] = st[0x800 + i];
    uint32_t esp = *(uint32_t *)(st + 0x908) - 4 * (uint32_t)(nargs + 1);
    if (!g_ok(esp, 4 * (uint32_t)(nargs + 1))) return 0;
    g_wr32(g, esp, 0x401234);
    for (int i = 0; i < nargs; i++) g_wr32(g, esp + 4 + 4 * (uint32_t)i, args[i]);
    *(uint32_t *)(st + 0x908) = esp;
    *(uint32_t *)(st + 0x8E8) = esi;
    ((lifted_fn)(g_base + off))(state, va, memory);
    uint32_t r = *(uint32_t *)(st + 0x8A8);
    for (int i = 0; i < 0x400; i++) st[0x800 + i] = g_callsave[i];
    return r;
}

/* Plays cue n of the voice bank (sound 0x3000 + n), as Sora's mod plays its sounds. */
static void play_cue(void *state, void *memory, uint32_t cue) {
    if (!g_snd_off || cue >= 0xFFF) return;
    static const uint32_t zero[3] = {0, 0, 0};
    guest_call_args(state, memory, g_snd_off, g_snd_va, 0x3000 + cue, zero, 3);
}

/* As guest_call, with EAX, EBX, ECX and EDX set and up to four 32-bit arguments pushed (the
 * caller's to pop). */
static uint32_t guest_call_regs(void *state, void *memory, uint64_t off, uint32_t va, uint32_t eax, uint32_t ebx,
                                uint32_t ecx, uint32_t edx, const uint32_t *args, int nargs) {
    uint8_t *g = guest();
    uint8_t *st = (uint8_t *)state;
    uint32_t esp = *(uint32_t *)(st + 0x908) - 4 * (uint32_t)(nargs + 1);
    if (nargs > 4 || !g_ok(esp, 4 * (uint32_t)(nargs + 1))) return 0;
    for (int i = 0; i < 0x400; i++) g_callsave[i] = st[0x800 + i];
    g_wr32(g, esp, 0x401234);
    for (int i = 0; i < nargs; i++) g_wr32(g, esp + 4 + 4 * (uint32_t)i, args[i]);
    *(uint32_t *)(st + 0x908) = esp;
    *(uint32_t *)(st + 0x8A8) = eax;
    *(uint32_t *)(st + 0x8B8) = ebx;
    *(uint32_t *)(st + 0x8C8) = ecx;
    *(uint32_t *)(st + 0x8D8) = edx;
    ((lifted_fn)(g_base + off))(state, va, memory);
    uint32_t r = *(uint32_t *)(st + 0x8A8);
    for (int i = 0; i < 0x400; i++) st[0x800 + i] = g_callsave[i];
    return r;
}

/* Kind 18, around Sonic_Main (Sonic's and Shadow's): Sonic and Shadow (the X lines' CharID2) become
 * their super forms as Sora's SA2 Super Sonic does it on transforming, outside Final Hazard (H set
 * 1): the game's super model (LoadMDLFile of SSONICMDL.PRS, index 328; SSHADOWMDL.PRS, 355) put in
 * CharacterModels, the super texture list (LoadCharTextures) as the player's (SonicCharObj2 +0x394,
 * the old one kept for the ball), the animation list's body model (the commonest ModelNum) changed
 * to the super model so their own motions drive it, the super physics (PhysicsArray) and
 * invincibility. With a B line they transform when the button is pressed in a jump with enough
 * rings, lose a ring a second and turn back at none (or on the button in a jump again); without
 * one they are the super form from their first frame. */
#define SP_RINGS 0x174B028u
#define SP_FRAMES 0x174B03Cu
#define SP_TIMER_STOPPED 0x174AFDAu
#define SP_GAMESTATE 0x1934BE0u
#define SP_CONTROLLERS 0x1DEFC00u
#define SP_CHAROBJ1 0x1DEA6C0u
#define SP_MODELS 0x1DE9620u
#define SP_PHYSICS 0x17391E8u
#define SP_INVINCIBLE 0x8000u

/* The super aura (a lines, c lines, kind 21). Final Hazard's Super Sonic and Super Shadow glow: a
 * model of the game's (0x0170B47C) over the SSONEFFTEX textures (0x0170ACD4; LoadSuperSonic loads
 * them with LoadTextureList from the name at 0x008BAA1C), its texture number (0x0170ACEE) cycling
 * through 3-8 for Sonic and 9-14 for Shadow. The game draws it only in Final Hazard, from
 * Super_DispDelay3 (0x0049CD50), which reads Final Hazard's data (FinalHazardPlayerData, null
 * elsewhere) and SuperSonicCharObj2. Here, as Sora's SA2 Super Sonic does it, the super form's
 * task gets Super_DispDelay3 as its DisplaySub_Delayed4 while it is the super form; outside Final
 * Hazard (H set 1) the kind-21 wrapper draws the aura itself through the game's own Ninja calls and
 * returns without the game's body: at the character's position, turned as it is (Z, X, then
 * 0x8000 - Y) and scaled, 3 units down, with a c line's colour as a constant material. */
#define AU_MAINCHAR 0x1DEA6E0u
#define AU_TEXLIST 0x170ACD4u
#define AU_TEXNAME 0x8BAA1Cu
#define AU_MODEL 0x170B47Cu
#define AU_ANIM 0x170ACEEu
#define AU_MATRIX 0x1A557FCu       /* _nj_current_matrix_ptr_ */
#define AU_MATRIX_MAX 0x2670588u   /* MatrixStack_MaxPtr */
#define AU_RENDER 0x2670544u       /* pRenderInfo: +0x20 the texture list in use */
#define AU_CONSTANT_MATERIAL 0x10u /* NJD_CONTROL_3D_CONSTANT_MATERIAL */

static int aura_ready(void) {
    for (int i = 0; i < AU_FNS; i++)
        if (!g_au_va[i] || (!g_au_off[i] && i != AU_DISPLAY && i != AU_CALLBACK)) return 0;
    return 1;
}

static void au_call(void *state, void *memory, int fn, uint32_t eax, uint32_t ebx, const uint32_t *args, int nargs) {
    guest_call_regs(state, memory, g_au_off[fn], g_au_va[fn], eax, ebx, 0, 0, args, nargs);
}

/* With the super textures: SSONEFFTEX, once per character object (as Sora's mod loads it with
 * each LoadSonic). */
static void aura_load(void *state, void *memory, int p) {
    g_au_tex[p] = 0;
    if (!g_au_live || !aura_ready()) return;
    guest_call_regs(state, memory, g_au_off[AU_LOADTEX], g_au_va[AU_LOADTEX], 0, 0, AU_TEXNAME, AU_TEXLIST, 0, 0);
    g_au_tex[p] = 1;
}

/* On the super form: its task draws the aura (DisplaySub_Delayed4, +0x28; the task's Data2, +0x40,
 * is the character object). */
static void aura_on(uint8_t *g, int p, uint32_t co2) {
    g_au_obj[p] = 0;
    if (!g_au_live || !g_au_tex[p] || !g_ok(AU_MAINCHAR + 4 * (uint32_t)p, 4)) return;
    uint32_t obj = g_rd32(g, AU_MAINCHAR + 4 * (uint32_t)p);
    if (!obj || !g_ok(obj, 0x44) || g_rd32(g, obj + 0x40) != co2) return;
    uint32_t cur = g_rd32(g, obj + 0x28);
    g_au_old[p] = cur == g_au_va[AU_DISPLAY] ? 0 : cur;
    g_wr32(g, obj + 0x28, g_au_va[AU_DISPLAY]);
    g_au_obj[p] = obj;
    g_au_said[p] = 0;
}

/* Off again: the task's own DisplaySub_Delayed4, when it is still that character's task and still
 * has ours. */
static void aura_off(uint8_t *g, int p, uint32_t co2) {
    uint32_t obj = g_au_obj[p];
    g_au_obj[p] = 0;
    if (!obj || !g_ok(AU_MAINCHAR + 4 * (uint32_t)p, 4) || g_rd32(g, AU_MAINCHAR + 4 * (uint32_t)p) != obj ||
        !g_ok(obj, 0x44) || g_rd32(g, obj + 0x40) != co2 || g_rd32(g, obj + 0x28) != g_au_va[AU_DISPLAY])
        return;
    g_wr32(g, obj + 0x28, g_au_old[p]);
}

static void aura_draw(void *state, void *memory, uint8_t *g, int p, uint32_t obj, uint32_t co2) {
    uint32_t d1 = g_rd32(g, obj + 0x34);
    if (!d1 || !g_ok(d1, 0x2C) || !g_ok(co2 + g_charid_off + 2, 1) || !g_ok(co2 + 0x178, 2)) return;
    if (g_rd16(g, co2 + 0x176) == 54 || g_rd16(g, co2 + 0x178) == 54) return;   /* the stage's end */
    if (!g_ok(AU_RENDER, 4) || !g_ok(AU_MATRIX, 4) || !g_ok(AU_MATRIX_MAX, 4) || !g_ok(AU_ANIM, 1) ||
        !g_ok(AU_TEXLIST, 8))
        return;
    uint32_t ri = g_rd32(g, AU_RENDER), m = g_rd32(g, AU_MATRIX), top = g_rd32(g, AU_MATRIX_MAX);
    if (!g_ok(ri + 0x20, 4) || !g_ok(m, 0x90) || m + 0x90 > top) return;   /* room for two pushes */
    uint32_t id2 = g[co2 + g_charid_off + 2], base = id2 == 1 ? 9 : 3;
    uint32_t n = base + g_au_tick[p] % 6;
    if (g_ok(SP_GAMESTATE, 2) && g_rd16(g, SP_GAMESTATE) == 16) g_au_tick[p]++;
    uint32_t tl = g_rd32(g, AU_TEXLIST), tn = g_rd32(g, AU_TEXLIST + 4);
    if (tn <= base + 5 || tn > 256 || !g_ok(tl, 12 * tn) || !g_rd32(g, tl + 12 * n + 8)) {
        if (!g_au_said[p]) {
            g_au_said[p] = 1;
            say("aura: SSONEFFTEX is not loaded; no aura");
        }
        return;
    }
    int tint = 0;
    uint32_t rgb = 0;
    for (uint32_t i = 0; i < g_nau_c; i++)
        if (g_au_cid[i] == id2) {
            tint = 1;
            rgb = g_au_rgb[i];
        }
    if (!g_au_said[p]) {
        g_au_said[p] = 1;
        begin();
        put("aura: player ");
        put_dec((uint64_t)p);
        put(" glows");
        if (tint) {
            put(", tinted ");
            put_hex(rgb);
        }
        end();
    }
    uint32_t oldtex = g_rd32(g, ri + 0x20), oldanim = g[AU_ANIM];
    g_wr32(g, ri + 0x20, AU_TEXLIST);
    g[AU_ANIM] = (uint8_t)n;
    for (uint32_t i = 0; i < 0x30; i++) g[m + 0x30 + i] = g[m + i];   /* njPushMatrix */
    g_wr32(g, AU_MATRIX, m + 0x30);
    uint32_t v[4] = {g_rd32(g, d1 + 0x14), g_rd32(g, d1 + 0x18), g_rd32(g, d1 + 0x1C), 0};
    au_call(state, memory, AU_TRANSLATE, m + 0x30, 0, v, 3);
    au_call(state, memory, AU_RESETSPACE, 0, 0, 0, 0);
    v[0] = g_rd32(g, d1 + 0x10);
    au_call(state, memory, AU_ROTZ, 0, m + 0x30, v, 1);
    v[0] = g_rd32(g, d1 + 0x08);
    au_call(state, memory, AU_ROTX, 0, m + 0x30, v, 1);
    v[0] = 0x8000u - g_rd32(g, d1 + 0x0C);
    au_call(state, memory, AU_ROTY, 0, m + 0x30, v, 1);
    v[0] = g_rd32(g, d1 + 0x20);
    v[1] = g_rd32(g, d1 + 0x24);
    v[2] = g_rd32(g, d1 + 0x28);
    au_call(state, memory, AU_SCALE, 0, 0, v, 3);
    for (uint32_t i = 0; i < 0x30; i++) g[m + 0x60 + i] = g[m + 0x30 + i];
    g_wr32(g, AU_MATRIX, m + 0x60);
    v[0] = 0;
    v[1] = b_of(-3.0f);
    v[2] = 0;
    au_call(state, memory, AU_TRANSLATE, m + 0x60, 0, v, 3);
    au_call(state, memory, AU_RESETSPACE, 0, 0, 0, 0);
    if (tint) {
        au_call(state, memory, AU_SAVE3D, 0, 0, 0, 0);
        au_call(state, memory, AU_ON3D, AU_CONSTANT_MATERIAL, 0, 0, 0);
        v[0] = b_of(1.0f);
        v[1] = b_of((float)(rgb >> 16 & 0xFF) / 255.0f);
        v[2] = b_of((float)(rgb >> 8 & 0xFF) / 255.0f);
        v[3] = b_of((float)(rgb & 0xFF) / 255.0f);
        au_call(state, memory, AU_MATERIAL, 0, 0, v, 4);
    }
    v[0] = AU_MODEL;
    v[1] = g_au_va[AU_CALLBACK];
    au_call(state, memory, AU_DRAW, 0, 0, v, 2);
    if (tint) {
        au_call(state, memory, AU_RESETMAT, 0, 0, 0, 0);
        au_call(state, memory, AU_LOAD3D, 0, 0, 0, 0);
    }
    g_wr32(g, AU_MATRIX, m);   /* njPopMatrix(2) */
    g[AU_ANIM] = (uint8_t)oldanim;
    g_wr32(g, ri + 0x20, oldtex);
}

/* Kind 21, around Super_DispDelay3 (the task on the stack): in Final Hazard the game's own;
 * elsewhere the aura for a player's super form whose task it is, and nothing for anything else
 * (the game's body would read Final Hazard's data, which is null there). */
static void *wrap_aura(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    uint8_t *st = (uint8_t *)state;
    if (!g || !g_guest_size || !g_ok(g_level_va, 2)) return w->orig(state, pc, memory);
    uint32_t level = g_rd16(g, g_level_va);
    for (int i = 0; i < g_nhlevel; i++)
        if (g_hlevel[i] == level && g_hset[i] == 1) return w->orig(state, pc, memory);
    uint32_t esp = *(uint32_t *)(st + 0x908);
    uint32_t obj = g_ok(esp, 8) ? g_rd32(g, esp + 4) : 0;
    for (int p = 0; p < 2 && obj; p++) {
        uint32_t co2 = g_sp_co2[p];
        if (obj != g_au_obj[p] || !g_sp_on[p] || !co2 || !g_ok(obj, 0x44) || g_rd32(g, obj + 0x40) != co2) continue;
        aura_draw(state, memory, g, p, obj, co2);
    }
    return ((lifted_fn)(g_base + g_ret_off))(state, g_ret_va, memory);
}

/* Loads the super model and textures for player p's character object; 0 when they did not load. */
static int super_load(void *state, void *memory, uint8_t *g, int p, uint32_t co2, int k) {
    uint32_t lst = guest_call(state, memory, g_mdlfn_off, g_mdlfn_va, g_x_mdl[k], 0);
    uint32_t model = 0, idx = g_x_idx[k];
    if (lst && g_ok(lst, 8)) {
        for (int i = 0; i < 1024 && g_ok(lst + 8 * (uint32_t)i, 8); i++) {
            uint32_t ix = g_rd32(g, lst + 8 * (uint32_t)i);
            if (ix == 0xFFFFFFFFu) break;
            if (ix == idx) {
                model = g_rd32(g, lst + 8 * (uint32_t)i + 4);
                break;
            }
        }
    }
    uint32_t cm = SP_MODELS + 8 * idx;
    if (!model || !g_ok(cm, 8)) {
        g_sp_failed[p] = co2;
        begin();
        put("super: no model ");
        put_dec(idx);
        put(" from the game's file; left as it is");
        end();
        return 0;
    }
    g_wr32(g, cm, idx);
    g_wr32(g, cm + 4, model);
    uint32_t tex = guest_call(state, memory, g_texfn_off, g_texfn_va, g_x_tex[k], g_x_tex[k]);
    if (!tex || !g_ok(co2 + 0x398, 4)) {
        g_sp_failed[p] = co2;
        say("super: the super texture list did not load; left as it is");
        return 0;
    }
    g_sp_tex[p] = tex;
    g_sp_co2[p] = co2;
    g_sp_idx[p] = idx;
    g_e_tex[p] = 0;
    if (g_e_list && g_x_id[k] == g_e_id && g_ok(g_e_name_va, 32) && g_ok(g_e_list, 8)) {
        for (int i = 0; i < 32; i++) g[g_e_name_va + (uint32_t)i] = (uint8_t)g_e_name[i];
        uint32_t et = guest_call(state, memory, g_texfn_off, g_texfn_va, g_e_name_va, g_e_name_va);
        if (et && g_ok(et, 8) && g_rd32(g, et) && g_rd32(g, et + 4)) {
            g_e_tex[p] = et;
            begin();
            put("super: effect textures ");
            put(g_e_name);
            put(" at ");
            put_hex(et);
            end();
        } else {
            begin();
            put("super: effect textures ");
            put(g_e_name);
            put(" did not load; the usual ones stay");
            end();
        }
    }
    aura_load(state, memory, p);
    begin();
    put("super: player ");
    put_dec((uint64_t)p);
    put(" has the super model ");
    put_dec(idx);
    put(" and textures ");
    put_hex(tex);
    end();
    return 1;
}

/* Puts every ModelNum `from` of an animation list (203 entries of 16 bytes) to `to`. */
static void super_list_set(uint8_t *g, uint32_t list, uint32_t from, uint32_t to) {
    if (!list || !g_ok(list, 16 * 203)) return;
    for (int i = 0; i < 203; i++) {
        uint32_t a = list + 16 * (uint32_t)i + 2;
        if (g_rd16(g, a) == from) {
            g[a] = (uint8_t)to;
            g[a + 1] = (uint8_t)(to >> 8);
        }
    }
}

static void super_on(uint8_t *g, int p, uint32_t co2, int k) {
    g_sp_old[p] = g_rd32(g, co2 + 0x394);
    g_wr32(g, co2 + 0x394, g_sp_tex[p]);
    uint32_t list = g_rd32(g, co2 + 0x198), idx = g_sp_idx[p];
    g_sp_list[p] = 0;
    if (list && g_ok(list, 16 * 203)) {
        /* the body's model: the commonest ModelNum among the list's entries */
        uint32_t best = 0, bestn = 0;
        for (int i = 0; i < 203; i++) {
            uint32_t m = g_rd16(g, list + 16 * (uint32_t)i + 2), n = 0;
            for (int j = 0; j < 203; j++) n += g_rd16(g, list + 16 * (uint32_t)j + 2) == m;
            if (n > bestn) {
                bestn = n;
                best = m;
            }
        }
        if (best != idx) {
            super_list_set(g, list, best, idx);
            g_sp_list[p] = list;
            g_sp_best[p] = best;
        }
    }
    if (g_ok(co2 + 0xC0, 0x84)) {
        for (int i = 0; i < 0x84; i++) g_sp_phys[p][i] = g[co2 + 0xC0 + (uint32_t)i];
        uint32_t ph = g_x_phys[k] < 32 ? SP_PHYSICS + 0x84 * g_x_phys[k] : 0;
        if (ph && g_ok(ph, 0x84))
            for (int i = 0; i < 0x84; i++) g[co2 + 0xC0 + (uint32_t)i] = g[ph + (uint32_t)i];
    }
    if (g_ok(co2 + 0x10, 2)) g[co2 + 0x11] |= (uint8_t)(SP_INVINCIBLE >> 8);
    if (g_e_tex[p] && g_ok(g_e_list, 8)) {
        g_e_saved[p][0] = g_rd32(g, g_e_list);
        g_e_saved[p][1] = g_rd32(g, g_e_list + 4);
        g_wr32(g, g_e_list, g_rd32(g, g_e_tex[p]));
        g_wr32(g, g_e_list + 4, g_rd32(g, g_e_tex[p] + 4));
        g_e_on[p] = 1;
    }
    aura_on(g, p, co2);
    g_sp_on[p] = 1;
    g_par_a[p] = g_par_b[p] = 0;   /* kind 19 looks at the two lists again */
    begin();
    put("super: player ");
    put_dec((uint64_t)p);
    put(" is the super form");
    end();
}

/* Back from the super form; `gone` when the character object is no more (only the shared
 * animation list is put back). */
static void super_off(uint8_t *g, int p, uint32_t co2, int gone) {
    aura_off(g, p, co2);
    if (g_sp_list[p]) super_list_set(g, g_sp_list[p], g_sp_idx[p], g_sp_best[p]);
    g_sp_list[p] = 0;
    g_sp_on[p] = 0;
    if (g_e_on[p] && g_e_tex[p] && g_ok(g_e_list, 8) && g_ok(g_e_tex[p], 8) &&
        g_rd32(g, g_e_list) == g_rd32(g, g_e_tex[p]) && g_rd32(g, g_e_list + 4) == g_rd32(g, g_e_tex[p] + 4)) {
        g_wr32(g, g_e_list, g_e_saved[p][0]);
        g_wr32(g, g_e_list + 4, g_e_saved[p][1]);
    }
    g_e_on[p] = 0;
    if (gone || !co2 || !g_ok(co2 + 0x398, 4)) return;
    if (g_rd32(g, co2 + 0x394) == g_sp_tex[p] && g_sp_old[p]) g_wr32(g, co2 + 0x394, g_sp_old[p]);
    if (g_ok(co2 + 0xC0, 0x84))
        for (int i = 0; i < 0x84; i++) g[co2 + 0xC0 + (uint32_t)i] = g_sp_phys[p][i];
    if (g_ok(co2 + 0x10, 2)) g[co2 + 0x11] &= (uint8_t)~(SP_INVINCIBLE >> 8);
    begin();
    put("super: player ");
    put_dec((uint64_t)p);
    put(" is back from the super form");
    end();
}

static void *wrap_supermain(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    hunt_check(state, memory, g);
    if (g && g_guest_size && g_co2_va && g_level_va && g_ok(g_level_va, 2) && g_mdlfn_off && g_texfn_off) {
        uint32_t level = g_rd16(g, g_level_va);
        int fh = 0;
        for (int i = 0; i < g_nhlevel; i++) fh |= g_hlevel[i] == level && g_hset[i] == 1;
        for (int p = 0; p < 2 && !fh; p++) {
            if (!g_ok(g_co2_va + 4 * p, 4)) continue;
            uint32_t co2 = g_rd32(g, g_co2_va + 4 * p);
            if (!co2 || !g_ok(co2 + 0x398, 4)) continue;
            if (g_sp_co2[p] && g_sp_co2[p] != co2) {
                /* a new character object (a new stage, a restart): what was loaded was for the old */
                if (g_sp_on[p]) super_off(g, p, g_sp_co2[p], 1);
                g_sp_co2[p] = 0;
                if (g_tr_t[p] && g_ok(SP_FADE, 4) && g_rd32(g, SP_FADE) == g_fade_ours) g_wr32(g, SP_FADE, 0);
                g_tr_t[p] = 0;
            }
            if (g_sp_failed[p] == co2) continue;
            uint32_t id2 = g[co2 + g_charid_off + 2];
            int k = -1;
            for (int i = 0; i < g_nx; i++)
                if (g_x_id[i] == id2) k = i;
            if (k < 0 || !g_rd32(g, co2 + 0x394)) continue;
            if (!g_t_button) {
                if (g_sp_on[p] && g_rd32(g, co2 + 0x394) == g_sp_tex[p]) continue;   /* done */
                if (g_sp_on[p]) super_off(g, p, co2, 1);   /* the textures were changed: again */
                if (!g_sp_co2[p] && !super_load(state, memory, g, p, co2, k)) continue;
                super_on(g, p, co2, k);
                continue;
            }
            /* transforming */
            if (!g_ok(SP_GAMESTATE, 2) || g_rd16(g, SP_GAMESTATE) != 16) {   /* in game */
                if (g_tr_t[p] && (g_rd16(g, SP_GAMESTATE) < 16 || g_rd16(g, SP_GAMESTATE) > 17)) {
                    /* left the stage mid-flash: the flash goes, the form is given (or not) as is */
                    if (g_ok(SP_FADE, 4) && g_rd32(g, SP_FADE) == g_fade_ours) g_wr32(g, SP_FADE, 0);
                    g_tr_t[p] = 0;
                }
                continue;
            }
            uint32_t d1 = g_ok(SP_CHAROBJ1 + 4 * (uint32_t)p, 4) ? g_rd32(g, SP_CHAROBJ1 + 4 * (uint32_t)p) : 0;
            uint32_t pad = SP_CONTROLLERS + 0x34 * (uint32_t)p;
            if (!d1 || !g_ok(d1, 8) || !g_ok(pad, 0x34) || !g_ok(SP_RINGS + 2 * (uint32_t)p, 2)) continue;
            uint32_t action = g[d1], press = g_rd32(g, pad + 0x10);
            int16_t rings = (int16_t)g_rd16(g, SP_RINGS + 2 * (uint32_t)p);
            int jumping = action == 6 || action == 13;   /* Action_Jump, Action_HomingAttack */
            if (g_tr_t[p]) {
                /* the flash: up to the brightest in 8 frames (the form changes there), down to a
                 * third by frame 40, held, and gone over the last 20 frames */
                uint32_t t = g_tr_t[p]++, F = g_v_frames, a;
                if (t <= 8) a = 0xB0 * t / 8;
                else if (t <= 40) a = 0xB0 - (0xB0 - 0x3C) * (t - 8) / 32;
                else if (t + 20 < F) a = 0x3C;
                else a = t >= F ? 0 : 0x3C * (F - t) / 20;
                int ours = g_ok(SP_FADE, 4) && g_rd32(g, SP_FADE) == g_fade_ours;
                if (ours) {
                    g_fade_ours = a ? (a << 24 | g_v_rgb) : 0;
                    g_wr32(g, SP_FADE, g_fade_ours);
                }
                if (t == 8 && !g_sp_on[p]) {
                    if (g_sp_co2[p] || super_load(state, memory, g, p, co2, k)) super_on(g, p, co2, k);
                }
                if (t >= F) {
                    g_tr_t[p] = 0;
                    if (g_v_cue2 != 0xFFFFFFFFu) play_cue(state, memory, g_v_cue2);
                }
                if (!g_sp_on[p]) continue;
            } else if (!g_sp_on[p]) {
                if (rings >= (int)g_t_rings && action == 6 && (press & g_t_button)) {
                    if (g_v_frames >= 10 && g_ok(SP_FADE, 4) && !(g_rd32(g, SP_FADE) >> 24)) {
                        /* the flash first; the form comes at its brightest */
                        g_tr_t[p] = 1;
                        g_fade_ours = g_rd32(g, SP_FADE);
                        if (g_v_cue1 != 0xFFFFFFFFu) play_cue(state, memory, g_v_cue1);
                        continue;
                    }
                    if (!g_sp_co2[p] && !super_load(state, memory, g, p, co2, k)) continue;
                    super_on(g, p, co2, k);
                }
                continue;
            }
            if (g_rd32(g, co2 + 0x394) != g_sp_tex[p] && g_rd32(g, co2 + 0x394) != g_sp_old[p]) {
                super_off(g, p, co2, 1);   /* someone else changed the textures: let go */
                continue;
            }
            if (jumping && (press & g_t_button)) {
                super_off(g, p, co2, 0);
                continue;
            }
            uint32_t frames = g_ok(SP_FRAMES, 4) ? g_rd32(g, SP_FRAMES) : 1;
            int stopped = g_ok(SP_TIMER_STOPPED, 1) ? g[SP_TIMER_STOPPED] : 0;
            if (!stopped && frames % 60 == 0 && rings > 0) {
                rings--;
                g[SP_RINGS + 2 * (uint32_t)p] = (uint8_t)rings;
                g[SP_RINGS + 2 * (uint32_t)p + 1] = (uint8_t)((uint16_t)rings >> 8);
            }
            if (rings <= 0) super_off(g, p, co2, 0);
        }
    }
    return w->orig(state, pc, memory);
}

/* Kind 19, around Sonic_Display: while a super form is drawn as something its model is not (the
 * ball, an animation on another model), the old texture list is the player's for the call. */
/* Kind 20, around PlayVoice (EDX, then the voice number on the stack): logs each number once and
 * plays an N line's cue instead while player 1 is Sonic's CharID2. */
static void *wrap_voice(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    uint8_t *st = (uint8_t *)state;
    uint32_t esp = *(uint32_t *)(st + 0x908);
    if (!g || !g_guest_size || !g_ok(esp, 8)) return w->orig(state, pc, memory);
    uint32_t num = g_rd32(g, esp + 4), ret = g_rd32(g, esp);
    int seen = 0;
    for (uint32_t i = 0; i < g_nvlog; i++) seen |= g_vlog[i] == num;
    if (!seen && g_nvlog < 64) {
        g_vlog[g_nvlog++] = num;
        begin();
        put("voice: the game asks for voice ");
        put_dec(num);
        put(" from ");
        put_hex(ret);
        end();
    }
    if (g_nn && g_ret_off && g_snd_off && g_co2_va && g_ok(g_co2_va, 4)) {
        uint32_t co2 = g_rd32(g, g_co2_va);
        if (co2 && g_ok(co2 + 4, 1) && g[co2 + g_charid_off + 2] == 0) {
            for (uint32_t i = 0; i < g_nn; i++)
                if (g_n_num[i] == num) {
                    play_cue(state, memory, g_n_cue[i]);
                    *(uint32_t *)(st + 0x8A8) = 1;
                    return ((lifted_fn)(g_base + g_ret_off))(state, g_ret_va, memory);
                }
        }
    }
    return w->orig(state, pc, memory);
}

/* Whether texture lists a and b (NJS_TEXLIST: names, count; NJS_TEXNAME: filename, attr, texaddr)
 * name the same textures in the same order: then a model made for a draws right with b. */
static int super_parallel(const uint8_t *g, int p, uint32_t a, uint32_t b) {
    if (g_par_a[p] == a && g_par_b[p] == b) return (int)g_par_r[p];
    int r = 0;
    if (g_ok(a, 8) && g_ok(b, 8)) {
        uint32_t na = g_rd32(g, a), nb = g_rd32(g, b), ca = g_rd32(g, a + 4), cb = g_rd32(g, b + 4);
        r = ca == cb && ca > 0 && ca < 1024 && g_ok(na, 12 * ca) && g_ok(nb, 12 * cb);
        for (uint32_t i = 0; r && i < ca; i++) {
            uint32_t sa = g_rd32(g, na + 12 * i), sb = g_rd32(g, nb + 12 * i);
            if (!g_ok(sa, 1) || !g_ok(sb, 1)) break;   /* names not in guest memory: the counts decide */
            for (uint32_t j = 0;; j++) {
                if (!g_ok(sa + j, 1) || !g_ok(sb + j, 1) || j > 64) { r = 0; break; }
                uint8_t x = g[sa + j], y = g[sb + j];
                if (x >= 'A' && x <= 'Z') x = (uint8_t)(x + 32);
                if (y >= 'A' && y <= 'Z') y = (uint8_t)(y + 32);
                if (x != y) { r = 0; break; }
                if (!x) break;
            }
        }
    }
    g_par_a[p] = a;
    g_par_b[p] = b;
    g_par_r[p] = (uint32_t)r;
    begin();
    put("super: texture lists ");
    put_hex(a);
    put(" and ");
    put_hex(b);
    put(r ? " name the same textures: the ball keeps the super ones" : " differ: the ball is drawn with the old ones");
    end();
    return r;
}

static void *wrap_superdisp(wrap_t *w, void *state, uint32_t pc, void *memory) {
    uint8_t *g = guest();
    int swapped[2] = {0, 0};
    if (g && g_guest_size) {
        for (int p = 0; p < 2; p++) {
            uint32_t co2 = g_sp_co2[p];
            if (!co2 || !g_ok(co2 + 0x398, 4) || g_rd32(g, co2 + 0x394) != g_sp_tex[p] || !g_sp_old[p]) continue;
            if (super_parallel(g, p, g_sp_old[p], g_sp_tex[p])) continue;
            int other = 0;
            uint32_t list = g_rd32(g, co2 + 0x198), cur = g_rd16(g, co2 + 0x178);
            if (cur < 256 && list && g_ok(list + 16 * cur + 2, 2) && g_rd16(g, list + 16 * cur + 2) != g_sp_idx[p]) other = 1;
            uint32_t d1 = g_ok(0x1DEA6C0 + 4 * (uint32_t)p, 4) ? g_rd32(g, 0x1DEA6C0 + 4 * (uint32_t)p) : 0;
            if (d1 && g_ok(d1 + 4, 2) && (g_rd16(g, d1 + 4) & 0x100) && (g[co2 + 0x360] & 0x11)) other = 1;
            if (other) {
                g_wr32(g, co2 + 0x394, g_sp_old[p]);
                swapped[p] = 1;
            }
        }
    }
    void *r = w->orig(state, pc, memory);
    for (int p = 0; p < 2; p++)
        if (swapped[p]) g_wr32(g, g_sp_co2[p] + 0x394, g_sp_tex[p]);
    return r;
}

/* ------------------------------------------------------------------ every wrapper's entry */

static void *wrap_common(int slot, void *state, uint32_t pc, void *memory) {
    wrap_t *w = &g_wrap[slot];
    switch (w->kind) {
    case WRAP_LAYOUT: return wrap_layout(w, state, pc, memory);
    case WRAP_TRACE: return wrap_trace(w, state, pc, memory);
    case WRAP_SPRITE: return wrap_sprite(w, state, pc, memory);
    case WRAP_PTR: return wrap_ptr(w, state, pc, memory);
    case WRAP_GATE: return wrap_gate(w, state, pc, memory);
    case WRAP_FILL: return wrap_fill(w, state, pc, memory);
    case WRAP_RANDOM: return wrap_random(w, state, pc, memory);
    case WRAP_MOVES: return wrap_moves(w, state, pc, memory);
    case WRAP_RESTART: return wrap_restart(w, state, pc, memory);
    case WRAP_SETLOAD: return wrap_setload(w, state, pc, memory);
    case WRAP_LEVELGATE: return wrap_levelgate(w, state, pc, memory);
    case WRAP_TEXSWAP: return wrap_texswap(w, state, pc, memory);
    case WRAP_POSTSTORE: return wrap_poststore(w, state, pc, memory);
    case WRAP_CHARGATE: return wrap_chargate(w, state, pc, memory);
    case WRAP_SUPERMAIN: return wrap_supermain(w, state, pc, memory);
    case WRAP_SUPERDISP: return wrap_superdisp(w, state, pc, memory);
    case WRAP_VOICE: return wrap_voice(w, state, pc, memory);
    case WRAP_AURA: return wrap_aura(w, state, pc, memory);
    default: break;
    }
    uint8_t *g = guest();
    if (g && g_guest_size) {
        uint32_t player = *(uint32_t *)((uint8_t *)state + w->preg);
        if (player < 8 && g_ok(g_co2_va + 4 * player, 4) && g_ok(g_level_va, 2)) {
            uint32_t co2 = g_rd32(g, g_co2_va + 4 * player);
            if (co2 && g_ok(co2 + g_charid_off, 1)) {
                uint8_t id = g[co2 + g_charid_off];
                uint8_t partner = id < MAX_CHARS ? g_partner[id] : 0xFF;
                uint32_t level = g_rd16(g, g_level_va);
                if (partner != 0xFF && id < MAX_CHARS && g_partner2[id] != 0xFF && !has_level(g, w->kind, partner, level))
                    partner = g_partner2[id];
                if (id < MAX_CHARS && !has_level(g, w->kind, id, level) &&
                    (partner == 0xFF || !has_level(g, w->kind, partner, level))) {
                    /* A stand-in from another play style: anyone whose table has the level. */
                    for (uint32_t o = 0; o < MAX_CHARS; o++)
                        if (g_table[w->kind][o] && has_level(g, w->kind, o, level)) {
                            partner = (uint8_t)o;
                            break;
                        }
                }
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

#define WRAP_FN(n) \
    static void *wrap##n(void *s, uint32_t pc, void *m) { return wrap_common(n, s, pc, m); }
WRAP_FN(0) WRAP_FN(1) WRAP_FN(2) WRAP_FN(3) WRAP_FN(4) WRAP_FN(5) WRAP_FN(6) WRAP_FN(7)
WRAP_FN(8) WRAP_FN(9) WRAP_FN(10) WRAP_FN(11) WRAP_FN(12) WRAP_FN(13) WRAP_FN(14) WRAP_FN(15)
WRAP_FN(16) WRAP_FN(17) WRAP_FN(18) WRAP_FN(19) WRAP_FN(20) WRAP_FN(21) WRAP_FN(22) WRAP_FN(23)
WRAP_FN(24) WRAP_FN(25) WRAP_FN(26) WRAP_FN(27) WRAP_FN(28) WRAP_FN(29) WRAP_FN(30) WRAP_FN(31) 
WRAP_FN(32) WRAP_FN(33) WRAP_FN(34) WRAP_FN(35) WRAP_FN(36) WRAP_FN(37) WRAP_FN(38) WRAP_FN(39) 
WRAP_FN(40) WRAP_FN(41) WRAP_FN(42) WRAP_FN(43) WRAP_FN(44) WRAP_FN(45) WRAP_FN(46) WRAP_FN(47)
static lifted_fn const g_wrap_fn[MAX_WRAPS] = {
    wrap0,  wrap1,  wrap2,  wrap3,  wrap4,  wrap5,  wrap6,  wrap7,  wrap8,  wrap9,  wrap10, wrap11,
    wrap12, wrap13, wrap14, wrap15, wrap16, wrap17, wrap18, wrap19, wrap20, wrap21, wrap22, wrap23,
    wrap24, wrap25, wrap26, wrap27, wrap28, wrap29, wrap30, wrap31, wrap32, wrap33, wrap34, wrap35,
    wrap36, wrap37, wrap38, wrap39, wrap40, wrap41, wrap42, wrap43, wrap44, wrap45, wrap46, wrap47};

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
    g_base = base;

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
            int known = g_fastbase && g_guest_size;
            if (r->wrap_kind <= 2) known = known && g_co2_va && g_level_va;
            else if (r->wrap_kind == WRAP_LAYOUT) known = known && g_h_va && g_v_va && g_hud_off && g_sw_off && g_real_off;
            else if (r->wrap_kind == WRAP_FILL) known = known && g_h_va && g_v_va;
            else if (r->wrap_kind == WRAP_MOVES) known = known && g_co2_va && (g_nmv || g_em_off);
            else if (r->wrap_kind == WRAP_RESTART) known = known && g_level_va && g_reload_off;
            else if (r->wrap_kind == WRAP_SETLOAD) known = known && g_level_va && g_nly;
            else if (r->wrap_kind == WRAP_LEVELGATE) known = known && g_level_va && g_ret_off && g_nhlevel;
            else if (r->wrap_kind == WRAP_TEXSWAP) known = known && g_co2_va && g_nsw;
            else if (r->wrap_kind == WRAP_POSTSTORE) known = known && g_nq;
            else if (r->wrap_kind == WRAP_CHARGATE) known = known && g_level_va && g_co2_va && g_ret_off;
            else if (r->wrap_kind == WRAP_SUPERMAIN) known = known && g_level_va && g_co2_va && ((g_nx && g_mdlfn_off && g_texfn_off) || g_em_off);
            else if (r->wrap_kind == WRAP_SUPERDISP) known = known && g_nx;
            else if (r->wrap_kind == WRAP_VOICE) known = known && g_co2_va && (!g_nn || (g_ret_off && g_snd_off));
            else if (r->wrap_kind == WRAP_AURA) known = known && g_level_va && g_ret_off && g_nx && aura_ready();
            if (r->wrap_kind == WRAP_RANDOM) {
                uint32_t grp = (uint32_t)r->preg & 3, va = 0;
                for (int z = 0; z < g_nz; z++)
                    if (g_z_off[z] == r->from) va = g_z_va[z];
                if (!va || g_grp_n[grp] >= MAX_GROUP) {
                    skipped(r, "the plan does not give its guest address");
                    continue;
                }
                /* Its own group, and each other group in bits 2-5 of the fourth field. */
                for (uint32_t gi = 0; gi < 4; gi++) {
                    if (gi != grp && !((r->preg >> (2 + gi)) & 1)) continue;
                    if (g_grp_n[gi] >= MAX_GROUP) continue;
                    g_grp_fn[gi][g_grp_n[gi]] = (lifted_fn)tramp[i];
                    g_grp_va[gi][g_grp_n[gi]] = va;
                    g_grp_home[gi][g_grp_n[gi]] = (uint8_t)grp;
                    g_grp_n[gi]++;
                }
                known = 1;
            }
            if (known && r->wrap_kind == WRAP_DISPATCH) {
                if (g_disp_orig) {
                    skipped(r, "only one dispatch wrapper is taken");
                    continue;
                }
                g_disp_orig = (dispatch_fn)tramp[i];
                emit_jump(&e, (uint64_t)wrap_dispatch);
                r->stub = s;
                continue;
            }
            if (!known) {
                skipped(r, "the plan does not say where the game's data is");
                continue;
            }
            wrap_t *w = &g_wrap[g_nwrap];
            w->orig = (lifted_fn)tramp[i];
            w->preg = (uint32_t)r->preg;
            w->kind = (uint32_t)r->wrap_kind;
            w->from = r->from;
            w->early = TRACE_EARLY;
            w->late = TRACE_LATE;
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
        if (r->type == KIND_WRAP && r->wrap_kind == WRAP_AURA) g_au_live = 1;
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

/* The e lines: each row's allow byte set, when the row is the plan's (a jcc-taken edge, allow 0). */
static void allow_edges(uintptr_t base, uint64_t psize) {
    for (int i = 0; i < g_ne; i++) {
        volatile uint8_t *row = (volatile uint8_t *)(base + g_e_off[i]);
        uint32_t t = rd32((const uint8_t *)row), src = rd32((const uint8_t *)row + 4);
        begin();
        if (t != g_e_target[i] || src != g_e_source[i] || row[8] != 2) {
            put("game fix left out: the edge row for ");
            put_hex(g_e_target[i]);
            put(" is not what the plan expects");
            end();
            continue;
        }
        if (row[9]) {
            put("game fix: the branch at ");
            put_hex(src);
            put(" into ");
            put_hex(t);
            put(" is already followed");
            end();
            continue;
        }
        uintptr_t p0 = (uintptr_t)(row + 9) & ~(uintptr_t)(psize - 1);
        if (sys_mprotect(p0, psize, PROT_READ | PROT_WRITE) != 0) {
            put("game fix left out: could not open the edge table for ");
            put_hex(t);
            end();
            continue;
        }
        row[9] = 1;
        sys_mprotect(p0, psize, PROT_READ);
        put("game fix: the branch at ");
        put_hex(src);
        put(" into the rest of its function (");
        put_hex(t);
        put(") is followed");
        end();
    }
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
    allow_edges(base, page_size());
    if (!g_n) {
        if (g_ne) return;
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
