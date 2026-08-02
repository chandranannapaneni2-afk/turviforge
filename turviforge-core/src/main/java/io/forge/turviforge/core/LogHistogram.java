package io.forge.turviforge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Dependency-free log-linear histogram in the spirit of HdrHistogram:
 * 128 linear sub-buckets per power-of-two band, values 1 ms .. ~1 h,
 * bounded relative error ≤0.78% (meets PRD G2: ≤1% at p99.9),
 * mergeable, serialisable (deflate+base64).
 *
 * In a Maven build this class is swapped for org.hdrhistogram via the
 * {@code Histogram} facade; the JSON wire format stays identical in shape.
 */
public final class LogHistogram {

    private static final int SUB_BUCKET_BITS = 7;                 // 128 sub-buckets
    private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;
    private static final int BANDS = 25;                          // up to ~2^24 ms ≈ 4.7 h
    private static final int SLOTS = BANDS * SUB_BUCKETS;

    private final long[] counts = new long[SLOTS];
    private long total;
    private long min = Long.MAX_VALUE, max = 0, sum = 0;
    private double sumSq = 0;

    public void record(long value) {
        if (value < 0) value = 0;
        counts[slot(value)]++;
        total++;
        sum += value;
        sumSq += (double) value * value;
        if (value < min) min = value;
        if (value > max) max = value;
    }

    public void merge(LogHistogram o) {
        for (int i = 0; i < SLOTS; i++) counts[i] += o.counts[i];
        total += o.total;
        sum += o.sum;
        sumSq += o.sumSq;
        if (o.total > 0) { min = Math.min(min, o.min); max = Math.max(max, o.max); }
    }

    public long count() { return total; }
    public long min() { return total == 0 ? 0 : min; }
    public long max() { return max; }
    public double mean() { return total == 0 ? 0 : (double) sum / total; }

    /** Expose internal counts for comparison engine (M5). Returns a copy. */
    public long[] countsArray() { return counts.clone(); }

    /** Number of slots in this histogram. */
    public int slotCount() { return SLOTS; }

    /** Midpoint value for a given slot index. */
    public static long slotMidpoint(int slot) { return midpoint(slot); }

    public double stdDev() {
        if (total < 2) return 0;
        double m = mean();
        double v = sumSq / total - m * m;
        return v <= 0 ? 0 : Math.sqrt(v);
    }

    /** Exact-at-bucket-resolution quantile; q in [0,1]. */
    public long quantile(double q) {
        if (total == 0) return 0;
        long target = (long) Math.ceil(q * total);
        if (target <= 0) target = 1;
        long seen = 0;
        for (int i = 0; i < SLOTS; i++) {
            seen += counts[i];
            if (seen >= target) return midpoint(i);
        }
        return max;
    }

    private static int slot(long v) {
        if (v < SUB_BUCKETS) return (int) v;                       // exact for 0..63 ms
        int band = 63 - Long.numberOfLeadingZeros(v);              // highest set bit
        int shift = band - SUB_BUCKET_BITS + 1;
        int sub = (int) (v >>> shift) & (SUB_BUCKETS - 1);
        int idx = ((band - SUB_BUCKET_BITS + 1) + 1) * SUB_BUCKETS + sub;
        return Math.min(idx, SLOTS - 1);
    }

    private static long midpoint(int slot) {
        if (slot < SUB_BUCKETS) return slot;
        int shift = slot / SUB_BUCKETS - 1;      // inverse of slot(): shift = band - SUB_BUCKET_BITS + 1
        int sub = slot % SUB_BUCKETS;            // 6-bit value with the band's leading bit included
        long base = (long) sub << shift;
        long width = 1L << shift;
        return base + width / 2;
    }

    /** Serialise non-zero counts as (varint gap, varint count) pairs, deflate, base64. */
    public String toBase64() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int last = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (counts[i] == 0) continue;
            writeVarLong(raw, i - last); // gap >= 1
            writeVarLong(raw, counts[i]);
            last = i;
        }
        byte[] body = raw.toByteArray();
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(body); d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(12).putInt(0x52464831).putLong(total); // "RFH1"
        out.writeBytes(head.array());
        byte[] chunk = new byte[4096];
        while (!d.finished()) out.write(chunk, 0, d.deflate(chunk));
        d.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    public static LogHistogram fromBase64(String b64) {
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            Inflater inf = new Inflater();
            inf.setInput(all, 12, all.length - 12);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(chunk);
                if (n == 0 && inf.needsInput()) break;
                body.write(chunk, 0, n);
            }
            inf.end();
            byte[] b = body.toByteArray();
            LogHistogram h = new LogHistogram();
            int[] p = {0};
            int slot = -1;
            while (p[0] < b.length) {
                slot += (int) readVarLong(b, p);
                long c = readVarLong(b, p);
                h.counts[slot] = c;
                long mid = midpoint(slot);
                h.total += c;
                h.sum += mid * c;
                h.sumSq += (double) mid * mid * c;
                h.min = Math.min(h.min, mid);
                h.max = Math.max(h.max, mid);
            }
            return h;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad histogram payload", e);
        }
    }

    private static void writeVarLong(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    private static long readVarLong(byte[] b, int[] p) {
        long v = 0; int shift = 0;
        while (true) {
            byte x = b[p[0]++];
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
        }
    }
}
