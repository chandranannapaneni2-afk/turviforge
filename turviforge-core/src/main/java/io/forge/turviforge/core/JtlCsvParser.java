package io.forge.turviforge.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Streaming CSV JTL parser (M1).
 *  - Adaptive header mapping (FR-101), headerless fallback (default order)
 *  - RFC-4180 quoting incl. embedded delimiters and newlines (FR-104)
 *  - Constant memory (FR-103); gzip transparent (FR-106)
 *  - Malformed-line tolerance with threshold abort (FR-105)
 */
public final class JtlCsvParser {

    public static final class Result {
        public long total;
        public long malformed;
        public final List<String> malformedSamples = new ArrayList<>();
        public ColumnMap columns;
    }

    private final double malformedAbortRatio;

    public JtlCsvParser() { this(0.01); }
    public JtlCsvParser(double malformedAbortRatio) { this.malformedAbortRatio = malformedAbortRatio; }

    public Result parse(Path file, Consumer<SampleEvent> sink) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
        in.mark(2);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        if (b0 == 0x1f && b1 == 0x8b) in = new GZIPInputStream(in, 1 << 16);
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(r, sink);
        }
    }

    public Result parse(Reader reader, Consumer<SampleEvent> sink) throws IOException {
        Result res = new Result();
        Tokenizer tok = new Tokenizer(reader);
        List<String> row = tok.nextRecord();
        if (row == null) { res.columns = ColumnMap.defaults(); return res; }

        ColumnMap cm;
        if (ColumnMap.looksLikeHeader(row)) {
            cm = new ColumnMap(row);
        } else {
            cm = ColumnMap.defaults();
            handleRow(row, cm, sink, res);        // first row was data
        }
        if (!cm.valid()) throw new IOException("JTL header missing required columns (timeStamp/elapsed/label)");
        res.columns = cm;

        while ((row = tok.nextRecord()) != null) {
            handleRow(row, cm, sink, res);
            if (res.total > 1000 && res.malformed > res.total * malformedAbortRatio) {
                throw new IOException("Malformed-line ratio exceeded threshold ("
                        + res.malformed + "/" + res.total + ") — is this a valid JTL?");
            }
        }
        return res;
    }

    private void handleRow(List<String> row, ColumnMap cm, Consumer<SampleEvent> sink, Result res) {
        res.total++;
        try {
            sink.accept(toEvent(row, cm));
        } catch (RuntimeException e) {
            res.malformed++;
            if (res.malformedSamples.size() < 10) {
                res.malformedSamples.add(abbrev(String.join(",", row)));
            }
        }
    }

    private SampleEvent toEvent(List<String> f, ColumnMap c) {
        long ts = parseTimestamp(get(f, c.timeStamp));
        int elapsed = (int) parseLong(get(f, c.elapsed));
        String label = intern(get(f, c.label));
        String code = intern(get(f, c.responseCode));
        boolean success = "true".equalsIgnoreCase(get(f, c.success));
        return SampleEvent.csv(ts, elapsed, label, code, success,
                get(f, c.failureMessage), get(f, c.responseMessage),
                optInt(f, c.latency), optInt(f, c.connect),
                optLong(f, c.bytes), optLong(f, c.sentBytes),
                (int) optLong(f, c.grpThreads), (int) optLong(f, c.allThreads),
                get(f, c.threadName), get(f, c.hostname));
    }

    private static long parseTimestamp(String s) {
        // FR-109: 13-digit numeric => epoch ms; 10-digit => epoch s.
        if (s.length() >= 10 && s.chars().allMatch(Character::isDigit)) {
            long v = Long.parseLong(s);
            return s.length() <= 10 ? v * 1000 : v;
        }
        // Formatted timestamps: try ISO-like "yyyy/MM/dd HH:mm:ss[.SSS]"
        return FormattedTs.parse(s);
    }

    private static long parseLong(String s) { return Long.parseLong(s.trim()); }
    private static int optInt(List<String> f, int i) {
        String s = get(f, i);
        if (s.isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }
    private static long optLong(List<String> f, int i) {
        String s = get(f, i);
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
    private static String get(List<String> f, int i) {
        return (i < 0 || i >= f.size()) ? "" : f.get(i);
    }
    private static String intern(String s) { return s.intern(); }
    private static String abbrev(String s) { return s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    /** RFC-4180 tokenizer over a Reader; handles quotes, escaped quotes, embedded newlines. */
    static final class Tokenizer {
        private final Reader in;
        private final char[] buf = new char[1 << 16];
        private int len = 0, pos = 0;
        private boolean eof = false;

        Tokenizer(Reader in) { this.in = in; }

        List<String> nextRecord() throws IOException {
            if (eof && pos >= len) return null;
            List<String> fields = new ArrayList<>(20);
            StringBuilder cur = new StringBuilder(64);
            boolean inQuotes = false, sawAny = false;
            while (true) {
                int ch = read();
                if (ch == -1) {
                    if (!sawAny && cur.length() == 0 && fields.isEmpty()) return null;
                    fields.add(cur.toString());
                    return fields;
                }
                sawAny = true;
                char c = (char) ch;
                if (inQuotes) {
                    if (c == '"') {
                        int nx = peek();
                        if (nx == '"') { read(); cur.append('"'); }
                        else inQuotes = false;
                    } else cur.append(c);
                } else {
                    switch (c) {
                        case '"' -> inQuotes = true;
                        case ',' -> { fields.add(cur.toString()); cur.setLength(0); }
                        case '\r' -> { /* swallow; \n follows */ }
                        case '\n' -> {
                            if (fields.isEmpty() && cur.length() == 0) { sawAny = false; continue; } // blank line
                            fields.add(cur.toString());
                            return fields;
                        }
                        default -> cur.append(c);
                    }
                }
            }
        }

        private int read() throws IOException {
            if (pos >= len) {
                if (eof) return -1;
                len = in.read(buf);
                pos = 0;
                if (len == -1) { eof = true; return -1; }
            }
            return buf[pos++];
        }

        private int peek() throws IOException {
            if (pos >= len) {
                if (eof) return -1;
                len = in.read(buf);
                pos = 0;
                if (len == -1) { eof = true; return -1; }
            }
            return buf[pos];
        }
    }

    /** Minimal formatted-timestamp fallback. */
    static final class FormattedTs {
        static long parse(String s) {
            String t = s.trim().replace('T', ' ').replace('-', '/');
            java.time.format.DateTimeFormatter[] fmts = {
                    java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS"),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                    java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss")
            };
            for (var f : fmts) {
                try {
                    return java.time.LocalDateTime.parse(t, f)
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (Exception ignored) { }
            }
            throw new IllegalArgumentException("Unparseable timestamp: " + s);
        }
    }
}
