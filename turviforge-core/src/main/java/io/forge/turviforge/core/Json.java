package io.forge.turviforge.core;

import java.util.Locale;

/** Minimal deterministic JSON writer (NFR-R1: fixed number formatting, insertion order). */
public final class Json {

    private final StringBuilder sb = new StringBuilder(1 << 20);
    private boolean needComma = false;

    public Json obj() { pre(); sb.append('{'); needComma = false; return this; }
    public Json endObj() { sb.append('}'); needComma = true; return this; }
    public Json arr() { pre(); sb.append('['); needComma = false; return this; }
    public Json endArr() { sb.append(']'); needComma = true; return this; }

    public Json key(String k) { pre(); str(k); sb.append(':'); needComma = false; return this; }

    public Json val(String v) { pre(); if (v == null) sb.append("null"); else str(v); needComma = true; return this; }
    public Json val(long v) { pre(); sb.append(v); needComma = true; return this; }
    public Json val(boolean v) { pre(); sb.append(v); needComma = true; return this; }
    public Json val(double v) {
        pre();
        if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null");
        else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append((long) v);
        else sb.append(String.format(Locale.ROOT, "%.3f", v));
        needComma = true;
        return this;
    }
    public Json nul() { pre(); sb.append("null"); needComma = true; return this; }

    public Json kv(String k, String v) { return key(k).val(v); }
    public Json kv(String k, long v) { return key(k).val(v); }
    public Json kv(String k, double v) { return key(k).val(v); }
    public Json kv(String k, boolean v) { return key(k).val(v); }

    private void pre() { if (needComma) sb.append(','); }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    @Override public String toString() { return sb.toString(); }
}
