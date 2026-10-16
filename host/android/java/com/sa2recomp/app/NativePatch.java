package com.sa2recomp.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native patches: the part of the mod loader's x86 patching that can be reproduced in the
 * recompiled game, applied at the start of each Play by libopensa2patch.so
 * (host/android/native/opensa2_patch.c, which explains the mechanism).
 *
 * The game's x86 code is not run: each guest function is a native ARM64 function named
 * sub_&lt;guest address&gt; in libsa2game.so, and most calls are direct. So a patch can only be
 * reproduced when it replaces a whole function from its first byte:
 *
 *  - a code list "Patch" that writes `ret` (C3) at a function's entry, or a writejump from one
 *    function's entry to another's (other writes into the same function, now dead, are allowed);
 *  - the character swap (CharactersActivity): Load&lt;Character&gt; functions share one signature
 *    (void(int player), cdecl), so LoadSonic can become LoadShadow. When a character lands in a
 *    level its own position tables lack (Shadow in City Escape), the start, end and mission 2/3
 *    end lookups are wrapped to read the tables of the character it stands in for.
 *
 * Anything else (a patch in the middle of a function, a writecall, a DLL's hooks) cannot be, and
 * says so. The plan is computed here, in the launcher, from libsa2game.so's own symbol table (so
 * it follows whatever build is installed), and written to files/native-patches.txt; the play
 * screen hands it to the game process (arm()).
 */
final class NativePatch {
    private NativePatch() {}

    static final String HELPER = "libopensa2patch.so";
    static final String GAME = "libsa2game.so";
    static final String PLAN = "native-patches.txt";

    // ------------------------------------------------------------------ the game's addresses
    // (the Steam sonic2app.exe the release targets; sa2-mod-loader's SA2Variables.h/SA2Functions.h)

    /** A lifted function whose x86 body is a bare `ret` (the code list's own "do nothing" target). */
    private static final int RET_FN = 0x6FE430;
    private static final int LOAD_START_POSITION = 0x43D8E0;     // usercall: player in ECX
    private static final int LOAD_END_POSITION = 0x43DF30;       // usercall: player in EAX
    private static final int LOAD_END_MISSION23 = 0x43DD50;      // usercall: player in EAX
    private static final int STATE_EAX = 0x8A8, STATE_ECX = 0x8C8;   // remill's x86 State
    private static final int CURRENT_LEVEL = 0x1934B70, MAIN_CHAR_OBJ2 = 0x1DE9600, CHARID_OFFSET = 1;
    private static final int LEVEL_INVALID = 0x5B, GUEST_SIZE = 0x20000000;
    /** Per kind (start, end, mission 2/3 end): {CharID, table address}, and the entry size. */
    private static final int[][][] TABLES = {
            {{0, 0x1746F20}, {1, 0x17472C0}, {4, 0x17475D8}, {5, 0x17478F0}, {2, 0x1747BE8}, {7, 0x1747C40},
             {6, 0x1748008}, {9, 0x1748378}, {10, 0x1748400}},
            {{0, 0x1748488}, {1, 0x1748850}, {4, 0x1748B98}, {5, 0x1748EB0}, {7, 0x17491C8}, {6, 0x1749568},
             {9, 0x17498B0}, {10, 0x1749938}},
            {{0, 0x174A280}, {1, 0x174A3A0}, {4, 0x174A460}, {5, 0x174A540}, {7, 0x174A600}, {6, 0x174A6E0}},
    };
    private static final int[] TABLE_ENTRY = {0x2C, 0x2C, 0x20};
    /** The story select: its display and main (laid out at 16:9 on wider screens, an experiment),
     * the menu function that runs it (Menu_StorySelect), and the screen size the game keeps. */
    private static final int STORY_SELECT_DISPLAY = 0x67C0F0, STORY_SELECT_EXEC = 0x67BDF0, STORY_SELECT_MENU = 0x670A90;
    private static final int H_RES = 0x174BA64, V_RES = 0x174BA68;
    /** How the story select draws, logged to game.log (kind, stack words or pointer source, address):
     * the port's sprite call, the full-screen background, the viewport, screen rectangle and two more
     * functions the runtime's HUD guards cover, the menu function the story select and other menus
     * share, njDrawSprite2D (sprite on the stack) and its EBX variant, njDrawTextureEx (points at
     * EAX), njDrawTexture (ECX), njDrawPolygon (first stack word), njDrawPolygon2D (EAX, followed to
     * its points) and njSetScreen (EAX). */
    private static final int[][] STORY_TRACES = {
        {0x41DC80, 4, 10}, {0x41B0C0, 4, 6}, {0x420210, 4, 6}, {0x420390, 4, 4}, {0x4200F0, 4, 6}, {0x42B230, 4, 6},
        {0x666F90, 4, 8}, {0x77D0B0, 5, 0}, {0x66EF40, 5, 1}, {0x77F6B0, 7, 0x8A8}, {0x77F510, 7, 0x8C8},
        {0x77F7F0, 7, 1}, {0x490FA0, 7, 0x18A8}, {0x77E980, 7, 0x8A8}};
    /** Guest memory logged as the story select opens and once it has settled: the screen size,
     * the port's other copy of it (0x2670328) and its neighbours, the current menu, the frame count. */
    private static final int[][] STORY_VARS = {{0x174BA60, 4}, {0x2670318, 8}, {0x1D7BB10, 1}, {0x174B038, 3}};
    static final String MENU_PREF = "menu_169";

    /** A playable character: its Load function (void(int player)) and its CharID. */
    static final class Chara {
        final String key, name, group;
        final int load, charId;

        Chara(String key, String name, String group, int load, int charId) {
            this.key = key;
            this.name = name;
            this.group = group;
            this.load = load;
            this.charId = charId;
        }
    }

    /** The characters, by play style. The first two of each group are the story characters, whose
     * Load functions are the ones a swap replaces. */
    static final Chara[] CHARAS = {
            new Chara("sonic", "Sonic", "speed", 0x716E00, 0),
            new Chara("shadow", "Shadow", "speed", 0x717360, 1),
            new Chara("amy", "Amy", "speed", 0x7170E0, 0),
            new Chara("metalsonic", "Metal Sonic", "speed", 0x717640, 1),
            new Chara("knuckles", "Knuckles", "hunt", 0x728110, 4),
            new Chara("rouge", "Rouge", "hunt", 0x728460, 5),
            new Chara("tikal", "Tikal", "hunt", 0x7288B0, 4),
            new Chara("chaos", "Chaos Zero", "hunt", 0x728B60, 5),
            new Chara("mechtails", "Tails (Cyclone)", "mech", 0x740EB0, 6),
            new Chara("mecheggman", "Eggman (Egg Walker)", "mech", 0x740C50, 7),
            new Chara("chaowalker", "Chao Walker", "mech", 0x741110, 6),
            new Chara("darkchaowalker", "Dark Chao Walker", "mech", 0x7412F0, 7),
    };
    /** The story characters a swap can replace. */
    static final String[] SLOTS = {"sonic", "shadow", "knuckles", "rouge", "mechtails", "mecheggman"};

    static Chara chara(String key) {
        for (Chara c : CHARAS) if (c.key.equals(key)) return c;
        return null;
    }

    static List<Chara> group(String g) {
        List<Chara> out = new ArrayList<>();
        for (Chara c : CHARAS) if (c.group.equals(g)) out.add(c);
        return out;
    }

    static String swapPref(String slot) {
        return "swap_" + slot;
    }

    /** The character each story character is played as (itself when not swapped). */
    static Map<String, String> swaps(Context c) {
        SharedPreferences p = OpenSa2.prefs(c);
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : SLOTS) {
            String v = p.getString(swapPref(s), s);
            Chara from = chara(s), to = chara(v);
            out.put(s, to != null && to.group.equals(from.group) ? v : s);
        }
        return out;
    }

    // ------------------------------------------------------------------ libsa2game.so's symbols

    /** The lifted functions of a libsa2game.so: guest address -> {symbol value, size}. */
    static final class Lib {
        final File file;
        final Map<Integer, long[]> fns = new HashMap<>();
        int[] entries = new int[0];          // the guest addresses, sorted
        final Map<String, long[]> named = new HashMap<>();
        long[][] loads = new long[0][];      // PT_LOAD: {vaddr, offset, filesz}

        Lib(File f) {
            file = f;
        }

        boolean has(int va) {
            return fns.containsKey(va);
        }

        /** The next lifted function's guest address after `va`, or Integer.MAX_VALUE. */
        int nextAfter(int va) {
            int i = Arrays.binarySearch(entries, va);
            i = i >= 0 ? i + 1 : -i - 1;
            return i < entries.length ? entries[i] : Integer.MAX_VALUE;
        }

        /** The guest address of the lifted function holding `va` (the nearest at or below). */
        int containing(int va) {
            int i = Arrays.binarySearch(entries, va);
            if (i >= 0) return entries[i];
            i = -i - 2;
            return i >= 0 ? entries[i] : -1;
        }

        byte[] bytes(long vaddr, int n) throws IOException {
            for (long[] l : loads) {
                if (vaddr >= l[0] && vaddr + n <= l[0] + l[2]) {
                    byte[] b = new byte[n];
                    try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
                        r.seek(vaddr - l[0] + l[1]);
                        r.readFully(b);
                    }
                    return b;
                }
            }
            throw new IOException("address 0x" + Long.toHexString(vaddr) + " is not in the file");
        }
    }

    private static Lib sLib;
    private static long sLibStamp;

    /** The installed game's symbols (cached while the file is unchanged); null without a game. */
    static synchronized Lib lib(Context c) {
        File f = new File(c.getApplicationInfo().nativeLibraryDir, GAME);
        if (!f.isFile()) return null;
        long stamp = f.length() ^ (f.lastModified() << 1);
        if (sLib != null && sLibStamp == stamp) return sLib;
        try {
            sLib = read(f);
            sLibStamp = stamp;
        } catch (IOException e) {
            sLib = null;
        }
        return sLib;
    }

    /** Reads an ELF64 file's PT_LOAD headers and its .symtab. */
    static Lib read(File f) throws IOException {
        Lib lib = new Lib(f);
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[64];
            r.readFully(h);
            if (h[0] != 0x7F || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' || h[4] != 2 || h[5] != 1)
                throw new IOException("not a 64-bit little-endian ELF");
            long phoff = le64(h, 0x20), shoff = le64(h, 0x28);
            int phentsize = le16(h, 0x36), phnum = le16(h, 0x38), shentsize = le16(h, 0x3A), shnum = le16(h, 0x3C);
            List<long[]> loads = new ArrayList<>();
            byte[] ph = new byte[phentsize * phnum];
            r.seek(phoff);
            r.readFully(ph);
            for (int i = 0; i < phnum; i++) {
                int o = i * phentsize;
                if (le32(ph, o) == 1) loads.add(new long[] {le64(ph, o + 16), le64(ph, o + 8), le64(ph, o + 32)});
            }
            lib.loads = loads.toArray(new long[0][]);
            if (shnum <= 0 || shnum > 4096) throw new IOException("no section headers");
            byte[] sh = new byte[shentsize * shnum];
            r.seek(shoff);
            r.readFully(sh);
            for (int i = 0; i < shnum; i++) {
                int o = i * shentsize;
                if (le32(sh, o + 4) != 2) continue;                       // SHT_SYMTAB
                long off = le64(sh, o + 24), size = le64(sh, o + 32);
                int link = le32(sh, o + 40);
                int lo = link * shentsize;
                long stroff = le64(sh, lo + 24), strsize = le64(sh, lo + 32);
                if (size > (64 << 20) || strsize > (64 << 20)) throw new IOException("symbol table too large");
                byte[] sym = new byte[(int) size], str = new byte[(int) strsize];
                r.seek(off);
                r.readFully(sym);
                r.seek(stroff);
                r.readFully(str);
                for (int k = 0; k + 24 <= sym.length; k += 24) {
                    int name = le32(sym, k);
                    int type = sym[k + 4] & 0xF;
                    int shndx = le16(sym, k + 6);
                    if (shndx == 0 || name <= 0 || name >= str.length) continue;
                    long value = le64(sym, k + 8), sz = le64(sym, k + 16);
                    if (type == 2 && str[name] == 's' && name + 4 < str.length && str[name + 1] == 'u'
                        && str[name + 2] == 'b' && str[name + 3] == '_') {
                        int e = name + 4;
                        long va = 0;
                        int digits = 0;
                        while (e < str.length && str[e] != 0) {
                            int d = Character.digit((char) str[e], 16);
                            if (d < 0) {
                                digits = -1;
                                break;
                            }
                            va = va * 16 + d;
                            e++;
                            digits++;
                        }
                        if (digits > 0 && digits <= 8) lib.fns.put((int) va, new long[] {value, sz});
                        continue;
                    }
                    String n = cstr(str, name);
                    if (n.equals("sa2_guest_fast_base") || n.equals("__remill_read_memory_32")
                        || n.equals("__remill_function_return") || n.equals("g_hud43")
                        || n.equals("g_hud43_swapped") || n.equals("g_hud43_real") || n.equals("sa2_ft_dispatch"))
                        lib.named.put(n, new long[] {value, sz});
                }
            }
        }
        int[] e = new int[lib.fns.size()];
        int i = 0;
        for (int k : lib.fns.keySet()) e[i++] = k;
        Arrays.sort(e);
        lib.entries = e;
        if (lib.fns.isEmpty()) throw new IOException("no lifted functions in the symbol table");
        return lib;
    }

    /** A lifted function whose body is x86 `ret`: RET_FN when it is one (checked), else the first found. */
    static int retFunction(Lib lib) {
        if (isRetOnly(lib, RET_FN)) return RET_FN;
        for (int va : lib.entries) {
            long[] f = lib.fns.get(va);
            if (f[1] == 72 && isRetOnly(lib, va)) return va;
        }
        return -1;
    }

    /** The ret-only body this build gives `ret`: ESP += 4, EIP = the popped return address. */
    private static final int[] RET_BODY = {
            0xa9be57fe, 0xb9490815, 0xb9484808, 0xa9014ff4, 0xaa0003f3, 0xaa0203e0, 0xaa0203f4, 0x0b150101,
            0 /* bl __remill_read_memory_32 */, 0x2a0003e1, 0x110012a8, 0xaa1303e0, 0xb9090a68, 0xaa1403e2,
            0xb909aa61, 0xa9414ff4, 0xa8c257fe, 0 /* b __remill_function_return */};

    static boolean isRetOnly(Lib lib, int va) {
        long[] f = lib.fns.get(va);
        long[] rd = lib.named.get("__remill_read_memory_32"), fr = lib.named.get("__remill_function_return");
        if (f == null || f[1] != 72 || rd == null || fr == null) return false;
        try {
            byte[] b = lib.bytes(f[0], 72);
            for (int i = 0; i < 18; i++) {
                int w = le32(b, 4 * i);
                if (i == 8) {
                    if ((w & 0xFC000000) != 0x94000000 || branchTarget(f[0] + 32, w) != rd[0]) return false;
                } else if (i == 17) {
                    if ((w & 0xFC000000) != 0x14000000 || branchTarget(f[0] + 68, w) != fr[0]) return false;
                } else if (w != RET_BODY[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long branchTarget(long pc, int w) {
        long imm = w & 0x03FFFFFF;
        if ((imm & 0x02000000) != 0) imm -= 1 << 26;
        return pc + imm * 4;
    }

    // ------------------------------------------------------------------ the guest's code sections

    /** The executable sections of the game's sonic2app.exe, guest [start, end); null when not found. */
    static long[][] codeRanges() {
        File root = OpenSa2.findGameRoot();
        File exe = root == null ? null : OpenSa2.exeIn(root);
        if (exe == null || !exe.isFile()) return null;
        try (RandomAccessFile r = new RandomAccessFile(exe, "r")) {
            byte[] h = new byte[4096];
            int n = r.read(h);
            if (n < 0x40 || h[0] != 'M' || h[1] != 'Z') return null;
            int pe = le32(h, 0x3C);
            if (pe < 0 || pe + 24 > n || le32(h, pe) != 0x4550) return null;
            int count = le16(h, pe + 6), opt = le16(h, pe + 20);
            long imageBase = le32(h, pe + 24 + 28) & 0xFFFFFFFFL;
            List<long[]> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int s = pe + 24 + opt + 40 * i;
                if (s + 40 > n) break;
                long vsize = le32(h, s + 8) & 0xFFFFFFFFL, va = le32(h, s + 12) & 0xFFFFFFFFL;
                int ch = le32(h, s + 36);
                if ((ch & 0x20000000) != 0 || (ch & 0x20) != 0) out.add(new long[] {imageBase + va, imageBase + va + vsize});
            }
            return out.toArray(new long[0][]);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean inCode(long a, long[][] code, Lib lib) {
        if (code != null) {
            for (long[] s : code) if (a >= s[0] && a < s[1]) return true;
            return false;
        }
        // No exe to read: the lifted functions' span stands in for .text.
        return a >= 0x401000 && lib.entries.length > 0 && a < lib.entries[lib.entries.length - 1] + 0x1000L;
    }

    // ------------------------------------------------------------------ code list patches

    /** What a code needs natively: the redirects, or why it cannot have them. */
    static final class CodeSupport {
        final List<int[]> redirects = new ArrayList<>();   // {from va, to va}
        String why;               // null when every write into code is reproduced (or there is none)
        boolean touchesCode;      // it writes into the guest's code at all
    }

    /** How the native helper can reproduce `c`'s writes into the game's x86 code. */
    static CodeSupport support(Lib lib, CodeList.Code c, long[][] code) {
        CodeSupport s = new CodeSupport();
        int ret = -2;
        List<CodeList.Line> lines = new ArrayList<>();
        for (CodeList.Line l : c.lines) {
            if (l.base.equals("read")) continue;
            if (l.isIf() || l.pointer || l.reg) {
                if (writesCode(l, code, lib)) {
                    s.touchesCode = true;
                    s.why = "a conditional or pointer write into the game's code";
                    return s;
                }
                continue;
            }
            if (inCode(l.address, code, lib)) lines.add(l);
        }
        if (lines.isEmpty()) return s;
        s.touchesCode = true;
        // Whole functions turned off at their entry make any other writes inside them moot.
        List<int[]> dead = new ArrayList<>();
        for (CodeList.Line l : lines) {
            int a = (int) l.address;
            if (l.base.equals("write") && l.width == 8 && (l.value & 0xFF) == 0xC3 && l.repeat == 1 && lib.has(a)) {
                if (ret == -2) ret = retFunction(lib);
                if (ret < 0) {
                    s.why = "this build of the game has no plain-return function to send it to";
                    return s;
                }
                s.redirects.add(new int[] {a, ret});
                dead.add(new int[] {a, lib.nextAfter(a)});
            } else if (l.op.equalsIgnoreCase("writejump") && lib.has(a) && lib.has((int) l.value)) {
                s.redirects.add(new int[] {a, (int) l.value});
                dead.add(new int[] {a, lib.nextAfter(a)});
            }
        }
        for (CodeList.Line l : lines) {
            int a = (int) l.address;
            boolean handled = false;
            for (int[] r : s.redirects) if (r[0] == a) handled = true;
            for (int[] d : dead) if (a > d[0] && a < d[1]) handled = true;
            if (handled) continue;
            String at = String.format(Locale.ROOT, "0x%X", a);
            if (l.op.equalsIgnoreCase("writecall"))
                s.why = "it adds a call inside a function (at " + at + ")";
            else if (l.op.equalsIgnoreCase("writejump"))
                s.why = "it jumps from the middle of a function (at " + at + ")";
            else
                s.why = "it changes instructions in the middle of a function (at " + at + ")";
            s.redirects.clear();
            return s;
        }
        return s;
    }

    private static boolean writesCode(CodeList.Line l, long[][] code, Lib lib) {
        if (!l.isIf()) return !l.pointer && !l.base.equals("read") && inCode(l.address, code, lib);
        for (CodeList.Line k : l.yes) if (writesCode(k, code, lib)) return true;
        for (CodeList.Line k : l.no) if (writesCode(k, code, lib)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the plan

    /** The plan for the next Play: what it holds, for the launcher log and the game menu. */
    static final class Plan {
        final List<String> said = new ArrayList<>();
        final List<String> codes = new ArrayList<>();     // the code names reproduced natively
        String text = "";                                 // the plan file the helper reads
        int patches;
    }

    /**
     * Works out the patches for the next Play from the codes switched on and the character
     * swaps, and writes (or removes) files/native-patches.txt. Never throws: a problem is said.
     */
    static Plan prepare(Context c) {
        File out = new File(c.getFilesDir(), PLAN);
        out.delete();
        Map<String, String> swaps = swaps(c);
        boolean anySwap = false;
        for (Map.Entry<String, String> e : swaps.entrySet()) anySwap |= !e.getKey().equals(e.getValue());
        List<CodeList.Code> codes = ModManager.codesToRun(c, new ArrayList<>());
        boolean menu = OpenSa2.prefs(c).getBoolean(MENU_PREF, true);
        if (!anySwap && codes.isEmpty() && !menu) return new Plan();
        if (!new File(c.getApplicationInfo().nativeLibraryDir, HELPER).isFile()) {
            Plan p = new Plan();
            p.said.add("Native patches: this build has no " + HELPER + ", so code patches and character swaps are off");
            return p;
        }
        Lib lib = lib(c);
        if (lib == null) {
            Plan p = new Plan();
            p.said.add("Native patches: could not read the game's symbols; code patches and character swaps are off");
            return p;
        }
        Plan plan = build(lib, codes, swaps, codeRanges(), menu);
        if (plan.patches == 0) return plan;
        String refused = probe(c);
        if (refused != null) {
            plan.said.add("Native patches: Android will not load the helper into the game on this phone (" + refused
                          + "), so code patches and character swaps are off");
            plan.patches = 0;
            return plan;
        }
        try {
            OpenSa2.writeBytes(out, plan.text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            out.delete();
            plan.said.add("Native patches: could not write the plan (" + e.getMessage() + ")");
            plan.patches = 0;
        }
        return plan;
    }

    /**
     * Whether this phone's dynamic linker takes the helper as an LD_PRELOAD for a program in the
     * app's library folder: runs the installer executable that sits beside the game
     * (libsa2install.so --help, same folder, same linker rules) with the helper preloaded and no
     * plan, so the helper does nothing. A linker that refuses a preload either stops the program
     * ("CANNOT LINK EXECUTABLE") or warns; the game would do the same, so then no plan is armed.
     * Returns the linker's words, or null when it loaded (or the check could not be made).
     */
    static String probe(Context c) {
        File dir = new File(c.getApplicationInfo().nativeLibraryDir);
        File exe = new File(dir, "libsa2install.so"), helper = new File(dir, HELPER);
        if (!exe.isFile() || !helper.isFile()) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "--help");
            pb.environment().put("LD_PRELOAD", helper.getAbsolutePath());
            pb.environment().remove("OPENSA2_PATCHES");
            pb.redirectErrorStream(true);
            pb.directory(c.getCacheDir());
            Process p = pb.start();
            byte[] out = OpenSa2.readAll(p.getInputStream(), 64 << 10);
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroy();
                return null;
            }
            for (String l : new String(out, StandardCharsets.UTF_8).split("\n")) {
                if (l.contains("CANNOT LINK") || (l.contains(HELPER) && (l.contains("not") || l.contains("WARNING"))))
                    return l.trim();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The plan for these codes and swaps against `lib` (`code`: the guest's code sections, or null;
     * `menu`: the story select's wide-screen experiment and its drawing log). */
    static Plan build(Lib lib, List<CodeList.Code> codes, Map<String, String> swaps, long[][] code, boolean menu) {
        Plan plan = new Plan();
        Map<Integer, String[]> redirects = new LinkedHashMap<>();   // from -> {to, label}
        for (CodeList.Code cd : codes) {
            CodeSupport s = support(lib, cd, code);
            if (!s.touchesCode) continue;
            if (s.why != null) {
                plan.said.add("Code \"" + cd.name + "\" cannot work here: " + s.why);
                continue;
            }
            int added = 0;
            for (int[] r : s.redirects) {
                if (redirects.containsKey(r[0])) continue;
                redirects.put(r[0], new String[] {Integer.toString(r[1]), "code " + cd.name});
                added++;
            }
            if (added > 0) {
                plan.codes.add(cd.name);
                plan.said.add("Code \"" + cd.name + "\": applied at start (native patch)");
            }
        }
        // Character swaps: the story character's Load function becomes the chosen one's.
        int[] partner = new int[32];
        Arrays.fill(partner, -1);
        boolean needWrap = false;
        for (Map.Entry<String, String> e : swaps.entrySet()) {
            Chara from = chara(e.getKey()), to = chara(e.getValue());
            if (from == null || to == null || from == to) continue;
            if (!lib.has(from.load) || !lib.has(to.load)) {
                plan.said.add("Character swap " + from.name + " -> " + to.name + ": not in this build of the game");
                continue;
            }
            redirects.put(from.load, new String[] {Integer.toString(to.load), "swap " + from.name + " -> " + to.name});
            plan.said.add("Character swap: " + from.name + " is played as " + to.name);
            if (to.charId != from.charId) {
                partner[to.charId] = from.charId;
                needWrap = true;
            }
        }
        StringBuilder b = new StringBuilder("opensa2-patch 1\n");
        b.append("# made by the OpenSA2 launcher from ").append(lib.file.getName()).append('\n');
        try {
            for (Map.Entry<Integer, String[]> e : redirects.entrySet()) {
                int from = e.getKey(), to = Integer.parseInt(e.getValue()[0]);
                long[] f = lib.fns.get(from), t = lib.fns.get(to);
                b.append(String.format(Locale.ROOT, "R %x %x %x %x %s %s\n", f[0], f[1], t[0], to,
                                       hex(lib.bytes(f[0], 16)), e.getValue()[1]));
                plan.patches++;
            }
            long[] fb = lib.named.get("sa2_guest_fast_base");
            if (fb != null && (needWrap || menu)) b.append(String.format(Locale.ROOT, "G %x %x\n", fb[0], GUEST_SIZE));
            if (menu) {
                // The story select draws its background, title and button hints in a 16:9 frame
                // from the screen's left edge. The experiment: its display and main read the width
                // of a 16:9 screen (as the runtime's HUD at 4:3 does for the HUD, its guards keeping
                // the viewport and projection at the real width). Laying out the display alone
                // changed nothing on a device (0.1.3), so the trace below is what the fix needs.
                long[] hud = lib.named.get("g_hud43"), sw = lib.named.get("g_hud43_swapped"),
                       real = lib.named.get("g_hud43_real"), disp = lib.fns.get(STORY_SELECT_DISPLAY);
                if (fb == null || hud == null || sw == null || real == null || disp == null
                    || (sw[1] != 1 && sw[1] != 4) || redirects.containsKey(STORY_SELECT_DISPLAY)) {
                    plan.said.add("Story select at 16:9: this build of the game lacks what it needs, so it is left as it is");
                } else {
                    b.append(String.format(Locale.ROOT, "A %x %x %x %x\n", H_RES, V_RES, 16, 9));
                    b.append(String.format(Locale.ROOT, "U %x %x %x %x\n", hud[0], sw[0], sw[1], real[0]));
                    b.append(String.format(Locale.ROOT, "W %x %x %x %x %s %s\n", disp[0], disp[1], 3, 0,
                                           hex(lib.bytes(disp[0], 16)), "story select at 16:9"));
                    plan.patches++;
                    long[] exec = lib.fns.get(STORY_SELECT_EXEC);
                    if (exec != null && !redirects.containsKey(STORY_SELECT_EXEC)) {
                        b.append(String.format(Locale.ROOT, "W %x %x %x %x %s %s\n", exec[0], exec[1], 3, 0,
                                               hex(lib.bytes(exec[0], 16)), "story select main at 16:9"));
                        plan.patches++;
                    }
                    plan.said.add("Story select (wide screens): an experimental fix, and a record of how it draws "
                                  + "in game.log for the next one (Settings to turn off)");
                    // How the screen draws, logged to game.log from the moment the story select runs
                    // (a budget of lines per function as it opens, and again once it has been up a few
                    // seconds), to finish this: its draws and their arguments, the guest values the
                    // layout can come from, and the first call of each function the game dispatches to
                    // (the objects on screen).
                    long[] menuFn = lib.fns.get(STORY_SELECT_MENU);
                    if (menuFn != null && !redirects.containsKey(STORY_SELECT_MENU)) {
                        b.append(String.format(Locale.ROOT, "W %x %x %x %x %s %s\n", menuFn[0], menuFn[1], 8, 0,
                                               hex(lib.bytes(menuFn[0], 16)), "story select menu (opens the drawing log)"));
                        plan.patches++;
                    }
                    for (int[] v : STORY_VARS) b.append(String.format(Locale.ROOT, "V %x %x\n", v[0], v[1]));
                    for (int[] t : STORY_TRACES) {
                        long[] f = lib.fns.get(t[0]);
                        if (f == null || redirects.containsKey(t[0])) continue;
                        b.append(String.format(Locale.ROOT, "W %x %x %x %x %s %s\n", f[0], f[1], t[1], t[2],
                                               hex(lib.bytes(f[0], 16)), String.format(Locale.ROOT, "trace 0x%X", t[0])));
                        plan.patches++;
                    }
                    long[] dsp = lib.named.get("sa2_ft_dispatch");
                    if (dsp != null) {
                        b.append(String.format(Locale.ROOT, "W %x %x %x %x %s %s\n", dsp[0], dsp[1], 6, 0,
                                               hex(lib.bytes(dsp[0], 16)), "trace dispatch"));
                        plan.patches++;
                    }
                }
            }
            if (needWrap && fb == null) {
                plan.said.add("Character swap: this build does not expose the game's memory, so swapped characters "
                              + "may start at the wrong place");
            } else if (needWrap) {
                b.append(String.format(Locale.ROOT, "C %x %x %x %x\n", CURRENT_LEVEL, MAIN_CHAR_OBJ2, CHARID_OFFSET,
                                       LEVEL_INVALID));
                for (int k = 0; k < TABLES.length; k++)
                    for (int[] t : TABLES[k]) b.append(String.format(Locale.ROOT, "T %x %x %x %x\n", k, t[0], t[1], TABLE_ENTRY[k]));
                for (int id = 0; id < partner.length; id++)
                    if (partner[id] >= 0) b.append(String.format(Locale.ROOT, "P %x %x\n", id, partner[id]));
                int[][] wraps = {{LOAD_START_POSITION, 0, STATE_ECX}, {LOAD_END_POSITION, 1, STATE_EAX},
                                 {LOAD_END_MISSION23, 2, STATE_EAX}};
                String[] names = {"start position", "end position", "mission 2/3 end position"};
                for (int k = 0; k < wraps.length; k++) {
                    long[] f = lib.fns.get(wraps[k][0]);
                    if (f == null || redirects.containsKey(wraps[k][0])) continue;
                    b.append(String.format(Locale.ROOT, "W %x %x %x %x %s %s\n", f[0], f[1], wraps[k][1], wraps[k][2],
                                           hex(lib.bytes(f[0], 16)), names[k]));
                    plan.patches++;
                }
            }
        } catch (IOException e) {
            plan.said.add("Native patches: could not read the game's code (" + e.getMessage() + ")");
            plan.patches = 0;
            return plan;
        }
        for (String n : plan.codes) b.append("# code ").append(n).append('\n');
        plan.text = b.toString();
        return plan;
    }

    /**
     * In the play screen's process, before the session starts the game: points the game at the
     * helper and the plan (LD_PRELOAD, OPENSA2_PATCHES; the session copies its environment into
     * the game's), or clears both when there is no plan.
     */
    static void arm(Context c) {
        File plan = new File(c.getFilesDir(), PLAN);
        File helper = new File(c.getApplicationInfo().nativeLibraryDir, HELPER);
        try {
            if (plan.isFile() && plan.length() > 0 && helper.isFile()) {
                android.system.Os.setenv("OPENSA2_PATCHES", plan.getAbsolutePath(), true);
                android.system.Os.setenv("LD_PRELOAD", helper.getAbsolutePath(), true);
            } else {
                android.system.Os.unsetenv("OPENSA2_PATCHES");
                android.system.Os.unsetenv("LD_PRELOAD");
            }
        } catch (Exception ignored) {
        }
    }

    /** The code names the current plan reproduces natively (for the code engine's log). */
    static List<String> plannedCodes(Context c) {
        List<String> out = new ArrayList<>();
        String t = OpenSa2.readText(new File(c.getFilesDir(), PLAN));
        if (t == null) return out;
        for (String l : t.split("\n")) if (l.startsWith("# code ")) out.add(l.substring(7));
        return out;
    }

    /** The helper's own lines from a session's game.log ("opensa2-patch: ..."), which it writes
     * before the game starts, so at the head of the file. */
    static List<String> report(File gameLog) {
        List<String> out = new ArrayList<>();
        String t = null;
        if (gameLog != null && gameLog.isFile()) {
            try (RandomAccessFile r = new RandomAccessFile(gameLog, "r")) {
                byte[] b = new byte[(int) Math.min(r.length(), 32 << 10)];
                r.readFully(b);
                t = new String(b, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        if (t == null) return out;
        for (String l : t.split("\n")) {
            if (l.startsWith("opensa2-patch: ")) out.add(l.substring(15));
            else if (l.contains(HELPER) || l.contains("CANNOT LINK")) out.add(l.trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ bytes

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return s.toString();
    }

    private static String cstr(byte[] b, int o) {
        int e = o;
        while (e < b.length && b[e] != 0) e++;
        return new String(b, o, e - o, StandardCharsets.ISO_8859_1);
    }

    static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    static long le64(byte[] b, int o) {
        return (le32(b, o) & 0xFFFFFFFFL) | ((long) le32(b, o + 4) << 32);
    }
}
