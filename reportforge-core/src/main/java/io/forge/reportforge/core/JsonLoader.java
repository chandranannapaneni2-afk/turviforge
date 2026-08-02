package io.forge.reportforge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser to load a report-data.json back into a ReportModel
 * for comparison (M5). Handles only the reportforge/1 schema subset needed
 * by ComparisonEngine: labels, stats, percentiles, and histogram payloads.
 */
public final class JsonLoader {

    public static ReportModel load(Path file) throws IOException {
        String json = Files.readString(file);
        return parse(json);
    }

    public static ReportModel parse(String json) {
        Parser p = new Parser(json);
        ReportModel m = new ReportModel();
        p.skipWs();
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readKey();
            switch (key) {
                case "schema" -> p.readString();
                case "meta" -> parseMeta(p, m);
                case "labels" -> { p.expect('['); while (p.peek() != ']') { m.labels.add(parseLabel(p)); } p.expect(']'); }
                case "total" -> { if (p.peek() == 'n') p.readNull(); else m.total = parseLabel(p); }
                case "series" -> skipValue(p);
                case "errors" -> skipValue(p);
                case "scalability" -> skipValue(p);
                case "sla" -> skipValue(p);
                case "comparison" -> skipValue(p);
                default -> skipValue(p);
            }
            p.comma();
        }
        return m;
    }

    private static void parseMeta(Parser p, ReportModel m) {
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readKey();
            switch (key) {
                case "title" -> m.title = p.readString();
                case "source" -> { p.expect('['); while (p.peek() != ']') m.sources.add(p.readString()); p.expect(']'); }
                case "extras" -> { p.expect('{'); while (p.peek() != '}') { String k = p.readKey(); m.extras.put(k, p.readString()); p.comma(); } p.expect('}'); }
                case "warnings" -> { p.expect('['); while (p.peek() != ']') m.warnings.add(p.readString()); p.expect(']'); }
                default -> skipValue(p);
            }
            p.comma();
        }
        p.expect('}');
    }

    private static ReportModel.LabelStats parseLabel(Parser p) {
        ReportModel.LabelStats ls = new ReportModel.LabelStats();
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readKey();
            switch (key) {
                case "name" -> ls.name = p.readString();
                case "stats" -> ls.stats = parseStats(p);
                case "steadyStats" -> { if (p.peek() == 'n') p.readNull(); else ls.steadyStats = parseStats(p); }
                case "latency" -> { if (p.peek() == 'n') p.readNull(); else ls.latencyPct = parsePctBlock(p); }
                case "connect" -> { if (p.peek() == 'n') p.readNull(); else ls.connectPct = parsePctBlock(p); }
                case "histogram" -> { if (p.peek() == 'n') { p.readNull(); ls.histogramB64 = null; } else ls.histogramB64 = p.readString(); }
                case "topSlowest" -> skipValue(p);
                default -> skipValue(p);
            }
            p.comma();
        }
        p.expect('}');
        return ls;
    }

    private static ReportModel.Stats parseStats(Parser p) {
        ReportModel.Stats s = new ReportModel.Stats();
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readKey();
            switch (key) {
                case "n" -> s.n = (long) p.readNumber();
                case "errors" -> s.errors = (long) p.readNumber();
                case "errorRate" -> s.errorRate = p.readNumber();
                case "mean" -> s.mean = p.readNumber();
                case "min" -> s.min = (long) p.readNumber();
                case "max" -> s.max = (long) p.readNumber();
                case "stdDev" -> s.stdDev = p.readNumber();
                case "throughput" -> s.throughput = p.readNumber();
                case "kbRecv" -> s.kbRecv = p.readNumber();
                case "kbSent" -> s.kbSent = p.readNumber();
                case "apdex" -> s.apdex = p.readNumber();
                case "pct" -> { p.expect('{'); while (p.peek() != '}') { String q = p.readKey(); s.pct.put(q, (long) p.readNumber()); p.comma(); } p.expect('}'); }
                default -> skipValue(p);
            }
            p.comma();
        }
        p.expect('}');
        return s;
    }

    private static Map<String, Long> parsePctBlock(Parser p) {
        Map<String, Long> pct = new LinkedHashMap<>();
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readKey();
            if (key.equals("pct")) {
                p.expect('{');
                while (p.peek() != '}') { String q = p.readKey(); pct.put(q, (long) p.readNumber()); p.comma(); }
                p.expect('}');
            } else skipValue(p);
            p.comma();
        }
        p.expect('}');
        return pct;
    }

    private static void skipValue(Parser p) {
        p.skipWs();
        char c = p.peek();
        switch (c) {
            case '"' -> p.readString();
            case '{' -> { p.expect('{'); int d = 1; while (d > 0) { char x = p.next(); if (x == '{') d++; else if (x == '}') d--; else if (x == '"') p.skipString(); } }
            case '[' -> { p.expect('['); int d = 1; while (d > 0) { char x = p.next(); if (x == '[') d++; else if (x == ']') d--; else if (x == '"') p.skipString(); } }
            case 'n' -> p.readNull();
            case 't', 'f' -> p.readBool();
            default -> p.readNumber();
        }
    }

    /** Minimal character-level JSON parser. */
    static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s; }

        void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { skipWs(); return i < s.length() ? s.charAt(i) : 0; }
        char next() { return s.charAt(i++); }

        void expect(char c) { skipWs(); if (i < s.length() && s.charAt(i) == c) i++; }

        void comma() { skipWs(); if (i < s.length() && s.charAt(i) == ',') i++; }

        String readKey() { skipWs(); String k = readString(); skipWs(); if (i < s.length() && s.charAt(i) == ':') i++; return k; }

        String readString() {
            skipWs();
            if (peek() == 'n') { readNull(); return null; }
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') { char e = s.charAt(i++); sb.append(switch (e) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> e; }); }
                else sb.append(c);
            }
            return sb.toString();
        }

        void skipString() { while (i < s.length()) { char c = s.charAt(i++); if (c == '\\') i++; else if (c == '"') break; } }

        double readNumber() {
            skipWs();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-' || s.charAt(i) == '+' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) i++;
            try { return Double.parseDouble(s.substring(start, i)); } catch (Exception e) { return 0; }
        }

        void readNull() { skipWs(); if (i + 3 < s.length() && s.startsWith("null", i)) i += 4; }
        void readBool() { skipWs(); if (i < s.length() && s.charAt(i) == 't') i += 4; else i += 5; }
    }
}
