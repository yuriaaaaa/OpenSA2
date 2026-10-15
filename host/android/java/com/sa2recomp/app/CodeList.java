package com.sa2recomp.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod loader's text code list (Codes.lst, the format SA Mod Manager reads: "Code" or "Patch"
 * blocks of opcode lines, Author/Description/Category lines; doc/codes.md in X-Hax/SA-Mod-Manager).
 * Parsed into trees of Lines that CodeEngine runs against the game's memory.
 */
final class CodeList {

    static final class Line {
        String op;            // the opcode's name, as written
        String base;          // write, add, ..., ifeq, read (readreg), conv, nop, kbkey, else
        boolean reg;          // the value is a register number
        boolean signed;
        boolean isFloat;
        int width;            // 8, 16 or 32 (32 for float)
        long address;
        boolean pointer;
        int[] offsets = new int[0];
        long value;           // raw 32-bit pattern
        int repeat = 1;
        List<Line> yes = new ArrayList<>();
        List<Line> no = new ArrayList<>();

        boolean isIf() {
            return base.startsWith("if");
        }
    }

    static final class Code {
        String name = "";
        String author = "";
        String description = "";
        String category = "";
        boolean patch;
        boolean required;
        String source = "";   // "Codes.lst" or a mod's name
        final List<Line> lines = new ArrayList<>();
    }

    private static final Pattern OP = Pattern.compile(
            "^(write|add|sub|mul|div|mod|shl|shr|rol|ror|and|or|xor|iflteq|ifgteq|ifeq|ifne|iflt|ifgt|ifmask|read)"
            + "(reg)?([us])?(8|16|32|float)$");
    private static final Pattern CONV = Pattern.compile("^(s8tos32|s16tos32|s32tofloat|u32tofloat|floattos32|floattou32)$");

    private CodeList() {}

    static List<Code> parse(File f, String source, List<String> errors) {
        List<Code> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            Code code = null;
            // Each frame: the list lines are added to, and the "else" list of an if (null for a code).
            Deque<List<Line>[]> stack = new ArrayDeque<>();
            String raw;
            int n = 0;
            while ((raw = r.readLine()) != null) {
                n++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;
                String[] s = line.split("\\s+");
                switch (s[0]) {
                    case "Code":
                    case "Patch": {
                        code = new Code();
                        code.patch = s[0].equals("Patch");
                        code.source = source;
                        int[] end = new int[1];
                        code.name = quoted(s, 1, end);
                        for (int i = end[0]; i < s.length; i++) {
                            if (s[i].startsWith(";")) break;
                            if (s[i].equals("Required")) code.required = true;
                        }
                        out.add(code);
                        stack.clear();
                        @SuppressWarnings("unchecked")
                        List<Line>[] top = new List[] {code.lines, null};
                        stack.push(top);
                        break;
                    }
                    case "Author":
                        if (code != null) code.author = quoted(s, 1, null);
                        break;
                    case "Description":
                        if (code != null) code.description = (code.description.isEmpty() ? "" : code.description + "\n") + quoted(s, 1, null);
                        break;
                    case "Category":
                        if (code != null) code.category = quoted(s, 1, null);
                        break;
                    case "else":
                        if (stack.isEmpty() || stack.peek()[1] == null) {
                            errors.add(source + " line " + n + ": else without if");
                            break;
                        }
                        List<Line>[] f2 = stack.pop();
                        @SuppressWarnings("unchecked")
                        List<Line>[] el = new List[] {f2[1], null};
                        stack.push(el);
                        break;
                    case "endif":
                        if (stack.size() < 2) {
                            errors.add(source + " line " + n + ": endif without if");
                            break;
                        }
                        stack.pop();
                        break;
                    default: {
                        if (code == null || stack.isEmpty()) {
                            errors.add(source + " line " + n + ": a code line outside a code");
                            break;
                        }
                        Line l = parseLine(s, errors, source, n);
                        if (l == null) break;
                        stack.peek()[0].add(l);
                        if (l.isIf()) {
                            @SuppressWarnings("unchecked")
                            List<Line>[] fr = new List[] {l.yes, l.no};
                            stack.push(fr);
                        }
                    }
                }
            }
        } catch (IOException e) {
            errors.add(source + ": " + e.getMessage());
        }
        return out;
    }

    private static String quoted(String[] s, int from, int[] end) {
        if (from >= s.length) {
            if (end != null) end[0] = from;
            return "";
        }
        StringBuilder b = new StringBuilder(s[from]);
        int i = from + 1;
        if (s[from].startsWith("\"") && !(s[from].length() > 1 && s[from].endsWith("\""))) {
            for (; i < s.length; i++) {
                b.append(' ').append(s[i]);
                if (s[i].endsWith("\"")) {
                    i++;
                    break;
                }
            }
        } else if (!s[from].startsWith("\"")) {
            // Unquoted text: the rest of the line.
            for (; i < s.length; i++) b.append(' ').append(s[i]);
        }
        if (end != null) end[0] = i;
        String t = b.toString();
        if (t.startsWith("\"")) t = t.substring(1);
        if (t.endsWith("\"")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static Line parseLine(String[] s, List<String> errors, String source, int n) {
        Line l = new Line();
        l.op = s[0];
        String op = s[0].toLowerCase(Locale.ROOT);
        Matcher m = OP.matcher(op);
        if (m.matches()) {
            l.base = m.group(1);
            l.reg = m.group(2) != null || l.base.equals("read");
            l.signed = "s".equals(m.group(3));
            l.isFloat = "float".equals(m.group(4));
            l.width = l.isFloat ? 32 : Integer.parseInt(m.group(4));
        } else if (CONV.matcher(op).matches()) {
            l.base = "conv";
            l.width = 32;
        } else if (op.equals("writenop") || op.equals("writenopreg")) {
            l.base = "nop";
            l.reg = op.endsWith("reg");
            l.width = 8;
        } else if (op.equals("writejump") || op.equals("writecall")) {
            // An x86 jump or call written into the code: like writenop, nothing the recompiled game runs.
            l.base = "nop";
            l.width = 32;
        } else if (op.equals("ifkbkey")) {
            l.base = "ifkbkey";
            l.width = 32;
        } else {
            errors.add(source + " line " + n + ": unknown opcode " + s[0]);
            return null;
        }
        if (s.length < 2) {
            errors.add(source + " line " + n + ": no address");
            return null;
        }
        try {
            String a = s[1];
            if (a.startsWith("p")) {
                l.pointer = true;
                String[] parts = a.substring(1).split("\\|");
                l.address = parseAddr(parts[0]);
                l.offsets = new int[parts.length - 1];
                for (int i = 1; i < parts.length; i++) l.offsets[i - 1] = (int) Long.parseLong(parts[i], 16);
            } else {
                l.address = parseAddr(a);
            }
            int it = 2;
            if (!l.base.equals("conv")) {
                if (s.length <= it) {
                    errors.add(source + " line " + n + ": no value");
                    return null;
                }
                l.value = parseValue(s[it++], l);
            }
            if (it < s.length && s[it].startsWith("x")) l.repeat = Math.max(1, Integer.parseInt(s[it].substring(1)));
        } catch (NumberFormatException e) {
            errors.add(source + " line " + n + ": bad number in " + String.join(" ", s));
            return null;
        }
        return l;
    }

    private static long parseAddr(String a) {
        if (a.startsWith("r")) return Long.parseLong(a.substring(1));
        return Long.parseLong(a, 16) & 0xFFFFFFFFL;
    }

    private static long parseValue(String v, Line l) {
        if (l.base.equals("ifkbkey")) return 0;
        if (v.startsWith("0x") || v.startsWith("0X")) return Long.parseLong(v.substring(2), 16) & 0xFFFFFFFFL;
        if (l.isFloat && !l.reg) return Float.floatToRawIntBits(Float.parseFloat(v)) & 0xFFFFFFFFL;
        return Long.parseLong(v) & 0xFFFFFFFFL;
    }
}
