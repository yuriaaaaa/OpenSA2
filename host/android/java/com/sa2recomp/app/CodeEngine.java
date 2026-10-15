package com.sa2recomp.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the mod loader's codes against the running game, from the play screen's process.
 *
 * The game is a separate process (libsa2game.so, started by the native session) of the app's own
 * user, and its guest memory is one host allocation of SA2_GUEST_END bytes where guest address a
 * is at base + a (runtime/guest_memory.h). This class finds that process and that allocation
 * (/proc/PID/maps: the mapping with the PE image's "MZ" at base + 0x400000) and reads and writes
 * it through /proc/PID/mem, so every code that changes the game's data works as on Windows:
 * rings, lives, upgrades, timers, sizes and so on, run once a frame (about 60 times a second).
 *
 * What cannot work: the game's x86 code was recompiled ahead of time, so a write into an
 * executable section of the image (most "Patch" entries, and every writenop) changes bytes the
 * recompiled game never runs. Those writes are skipped and named in the log instead.
 */
final class CodeEngine {
    private static final String TAG = "opensa2-codes";
    private static final long GUEST_END = 0x20000000L;
    private static final long IMAGE_BASE = 0x400000L;

    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final File logFile;
    private volatile List<CodeList.Code> codes = new ArrayList<>();
    private volatile boolean running;
    private Thread thread;

    private int pid = -1;
    private long base = -1;
    private RandomAccessFile mem;
    private long[][] exec = new long[0][];          // executable sections, guest [start, end)
    private final Set<String> warned = new HashSet<>();
    private final Set<CodeList.Code> patched = new HashSet<>();
    private final List<long[]> regs = new ArrayList<>();
    private String state = "waiting for the game";

    CodeEngine(File logFile) {
        this.logFile = logFile;
    }

    String state() {
        return state;
    }

    List<String> logLines() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /** The codes to run (enabled ones and required ones); takes effect at the next frame. */
    void setCodes(List<CodeList.Code> list) {
        codes = new ArrayList<>(list);
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "opensa2-codes");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void say(String s) {
        Log.i(TAG, s);
        log.add(s);
        if (logFile != null) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(logFile, true)) {
                out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
    }

    private void loop() {
        say("codes: " + OpenSa2.nowIso() + ", " + codes.size() + " code(s) on");
        while (running) {
            try {
                if (mem == null) {
                    if (!attach()) {
                        Thread.sleep(500);
                        continue;
                    }
                }
                List<CodeList.Code> now = codes;
                regs.clear();
                regs.add(new long[16]);
                for (CodeList.Code c : now) {
                    if (c.patch) {
                        if (patched.add(c)) {
                            run(c, c.lines);
                            say("patch applied once: " + c.name);
                        }
                    } else {
                        run(c, c.lines);
                    }
                }
                Thread.sleep(16);
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                say("codes: the game's memory went away (" + e.getMessage() + ")");
                detach();
                state = "the game ended";
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            } catch (RuntimeException e) {
                say("codes: " + e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        detach();
    }

    private void detach() {
        if (mem != null) {
            try {
                mem.close();
            } catch (IOException ignored) {
            }
        }
        mem = null;
        pid = -1;
        base = -1;
        patched.clear();
    }

    // ------------------------------------------------------------ finding the game

    private static int findGamePid() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return -1;
        int self = android.os.Process.myPid();
        for (File p : procs) {
            String n = p.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            int id;
            try {
                id = Integer.parseInt(n);
            } catch (NumberFormatException e) {
                continue;
            }
            if (id == self) continue;
            String cmd = OpenSa2.readText(new File(p, "cmdline"));
            if (cmd != null && cmd.contains("libsa2game.so")) return id;
        }
        return -1;
    }

    private boolean attach() throws IOException {
        int id = findGamePid();
        if (id < 0) return false;
        List<long[]> candidates = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + id + "/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\\s+");
                if (f.length < 2 || !f[1].startsWith("rw")) continue;
                String[] ab = f[0].split("-");
                long a = Long.parseUnsignedLong(ab[0], 16), b = Long.parseUnsignedLong(ab[1], 16);
                if (b - a >= IMAGE_BASE + 0x1000) candidates.add(new long[] {a, b});
            }
        } catch (IOException e) {
            if (!"maps".equals(state)) say("codes: cannot read the game's memory map: " + e.getMessage());
            state = "maps";
            return false;
        }
        RandomAccessFile f;
        try {
            f = new RandomAccessFile("/proc/" + id + "/mem", "rw");
        } catch (IOException e) {
            if (!"denied".equals(state)) {
                say("codes: Android refused access to the game's memory (" + e.getMessage()
                    + "); codes cannot run on this device");
            }
            state = "denied";
            return false;
        }
        // The guest allocation: the PE image (MZ) at base + 0x400000, inside one rw mapping.
        for (long[] c : candidates) {
            try {
                f.seek(c[0] + IMAGE_BASE);
                if (f.read() == 'M' && f.read() == 'Z') {
                    pid = id;
                    base = c[0];
                    mem = f;
                    readSections();
                    state = "running";
                    say(String.format("codes: attached to the game (pid %d, guest base 0x%x, %d code section(s))",
                                      id, base, exec.length));
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        f.close();
        return false;
    }

    private void readSections() throws IOException {
        long pe = IMAGE_BASE + rd(IMAGE_BASE + 0x3C, 32);
        int count = (int) rd(pe + 6, 16);
        int optSize = (int) rd(pe + 20, 16);
        long sec = pe + 24 + optSize;
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < count && i < 96; i++, sec += 40) {
            long vsize = rd(sec + 8, 32), va = rd(sec + 12, 32), flags = rd(sec + 36, 32);
            if ((flags & 0x20000000L) != 0 || (flags & 0x20L) != 0) out.add(new long[] {IMAGE_BASE + va, IMAGE_BASE + va + vsize});
        }
        exec = out.toArray(new long[0][]);
    }

    private boolean inCode(long a) {
        for (long[] s : exec) if (a >= s[0] && a < s[1]) return true;
        return false;
    }

    // ------------------------------------------------------------ memory

    private long rd(long a, int width) throws IOException {
        if (a < 16) return regs.get(regs.size() - 1)[(int) a] & mask(width);
        if (a < 0x1000 || a + width / 8 > GUEST_END) throw new IOException("guest address 0x" + Long.toHexString(a));
        mem.seek(base + a);
        long v = 0;
        for (int i = 0; i < width / 8; i++) {
            int b = mem.read();
            if (b < 0) throw new IOException("short read");
            v |= ((long) b) << (8 * i);
        }
        return v;
    }

    private void wr(CodeList.Code c, long a, int width, long v) throws IOException {
        if (a < 16) {
            regs.get(regs.size() - 1)[(int) a] = v & mask(width);
            return;
        }
        if (a < 0x1000 || a + width / 8 > GUEST_END) return;
        if (inCode(a)) {
            if (warned.add(c.name)) say("skipped (changes x86 code, which the recompiled game does not run): " + c.name);
            return;
        }
        byte[] b = new byte[width / 8];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (v >>> (8 * i));
        mem.seek(base + a);
        mem.write(b);
    }

    private static long mask(int width) {
        return width == 32 ? 0xFFFFFFFFL : (1L << width) - 1;
    }

    private static long sx(long v, int width) {
        int shift = 64 - width;
        return (v << shift) >> shift;
    }

    /** GetAddress (mod-loader-common CodeParser.cpp); -1 for a null pointer on the way. */
    private long address(CodeList.Line l) throws IOException {
        long a = l.address;
        if (!l.pointer) return a;
        a = rd(a, 32);
        if (l.offsets.length == 0 || a == 0) return a == 0 ? -1 : a;
        for (int i = 0; i < l.offsets.length - 1; i++) {
            a = rd((a + l.offsets[i]) & 0xFFFFFFFFL, 32);
            if (a == 0) return -1;
        }
        return (a + l.offsets[l.offsets.length - 1]) & 0xFFFFFFFFL;
    }

    // ------------------------------------------------------------ running

    private void run(CodeList.Code c, List<CodeList.Line> lines) throws IOException {
        for (CodeList.Line l : lines) {
            long a = address(l);
            if (a < 0 && !l.base.equals("ifkbkey")) {
                if (!l.no.isEmpty()) run(c, l.no);
                continue;
            }
            if (l.isIf()) {
                if (cond(l, a)) run(c, l.yes);
                else run(c, l.no);
                continue;
            }
            step(c, l, a);
        }
    }

    private long operand(CodeList.Line l) {
        if (l.reg) return regs.get(regs.size() - 1)[(int) (l.value & 15)];
        return l.value;
    }

    private boolean cond(CodeList.Line l, long a) throws IOException {
        if (l.base.equals("ifkbkey")) return false;   // no keyboard on a phone
        int step = l.width / 8;
        boolean ok = true;
        long want = operand(l);
        for (int i = 0; i < l.repeat; i++, a += step) {
            long have = rd(a, l.width);
            int cmp;
            if (l.isFloat) {
                cmp = Float.compare(Float.intBitsToFloat((int) have), Float.intBitsToFloat((int) want));
            } else if (l.signed) {
                cmp = Long.compare(sx(have, l.width), sx(want & mask(l.width), l.width));
            } else {
                cmp = Long.compare(have, want & mask(l.width));
            }
            switch (l.base) {
                case "ifeq": ok &= cmp == 0; break;
                case "ifne": ok &= cmp != 0; break;
                case "iflt": ok &= cmp < 0; break;
                case "iflteq": ok &= cmp <= 0; break;
                case "ifgt": ok &= cmp > 0; break;
                case "ifgteq": ok &= cmp >= 0; break;
                case "ifmask": ok &= (have & want & mask(l.width)) == (want & mask(l.width)); break;
                default: ok = false;
            }
        }
        return ok;
    }

    private void step(CodeList.Code c, CodeList.Line l, long a) throws IOException {
        switch (l.base) {
            case "nop":
                if (warned.add(c.name)) say("skipped (changes x86 code, which the recompiled game does not run): " + c.name);
                return;
            case "conv": {
                long v = rd(a, 32);
                long out;
                switch (l.op.toLowerCase()) {
                    case "s8tos32": out = sx(v & 0xFF, 8); break;
                    case "s16tos32": out = sx(v & 0xFFFF, 16); break;
                    case "s32tofloat": out = Float.floatToRawIntBits((float) (int) v); break;
                    case "u32tofloat": out = Float.floatToRawIntBits((float) v); break;
                    case "floattos32": out = (int) Float.intBitsToFloat((int) v); break;
                    default: out = (long) Float.intBitsToFloat((int) v); break;
                }
                wr(c, a, 32, out);
                return;
            }
            case "read": {
                long v = rd(a, l.width);
                regs.get(regs.size() - 1)[(int) (l.value & 15)] = v;
                return;
            }
            default:
                break;
        }
        int step = l.width / 8;
        long val = operand(l);
        for (int i = 0; i < l.repeat; i++, a += step) {
            if (l.base.equals("write")) {
                wr(c, a, l.width, val);
                continue;
            }
            long have = rd(a, l.width);
            long out;
            if (l.isFloat) {
                float x = Float.intBitsToFloat((int) have), y = Float.intBitsToFloat((int) val);
                float r;
                switch (l.base) {
                    case "add": r = x + y; break;
                    case "sub": r = x - y; break;
                    case "mul": r = x * y; break;
                    case "div": r = y == 0 ? x : x / y; break;
                    default: r = x;
                }
                out = Float.floatToRawIntBits(r);
            } else {
                long m = mask(l.width);
                long x = l.signed ? sx(have, l.width) : have, y = l.signed ? sx(val & m, l.width) : (val & m);
                int sh = (int) (val & 31) % l.width;
                switch (l.base) {
                    case "add": out = x + y; break;
                    case "sub": out = x - y; break;
                    case "mul": out = x * y; break;
                    case "div": out = y == 0 ? x : x / y; break;
                    case "mod": out = y == 0 ? x : x % y; break;
                    case "shl": out = have << (val & 63); break;
                    case "shr": out = l.signed ? sx(have, l.width) >> (val & 63) : have >>> (val & 63); break;
                    case "rol": out = (have << sh) | (have >>> (l.width - sh)); break;
                    case "ror": out = (have >>> sh) | (have << (l.width - sh)); break;
                    case "and": out = have & val; break;
                    case "or": out = have | val; break;
                    case "xor": out = have ^ val; break;
                    default: out = have;
                }
                out &= m;
            }
            wr(c, a, l.width, out);
        }
    }
}
