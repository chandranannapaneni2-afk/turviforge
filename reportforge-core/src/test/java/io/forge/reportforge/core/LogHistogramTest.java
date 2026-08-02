package io.forge.reportforge.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for LogHistogram accuracy and serialization. */
class LogHistogramTest {

    @Test
    void quantileAccuracyWithinOnePercent() {
        LogHistogram h = new LogHistogram();
        // Record values 1..10000
        for (int i = 1; i <= 10000; i++) h.record(i);
        assertEquals(10000, h.count());

        // p99 should be ~9900; allow ≤1% error
        long p99 = h.quantile(0.99);
        double err = Math.abs(p99 - 9900.0) / 9900.0;
        assertTrue(err <= 0.01, "p99 error " + (err * 100) + "% exceeds 1%: got " + p99);

        // p99.9 should be ~9990
        long p999 = h.quantile(0.999);
        double err999 = Math.abs(p999 - 9990.0) / 9990.0;
        assertTrue(err999 <= 0.01, "p99.9 error " + (err999 * 100) + "% exceeds 1%: got " + p999);
    }

    @Test
    void mergeIsCommutative() {
        LogHistogram a = new LogHistogram();
        LogHistogram b = new LogHistogram();
        for (int i = 0; i < 500; i++) { a.record(i * 3); b.record(i * 7 + 1); }
        LogHistogram ab = new LogHistogram(); ab.merge(a); ab.merge(b);
        LogHistogram ba = new LogHistogram(); ba.merge(b); ba.merge(a);
        assertEquals(ab.count(), ba.count());
        assertEquals(ab.quantile(0.50), ba.quantile(0.50));
        assertEquals(ab.quantile(0.95), ba.quantile(0.95));
        assertEquals(ab.quantile(0.99), ba.quantile(0.99));
    }

    @Test
    void serializationRoundTrip() {
        LogHistogram h = new LogHistogram();
        for (int i = 1; i <= 5000; i++) h.record(i * 2);
        String b64 = h.toBase64();
        assertNotNull(b64);
        assertFalse(b64.isEmpty());

        LogHistogram restored = LogHistogram.fromBase64(b64);
        assertEquals(h.count(), restored.count());
        assertEquals(h.quantile(0.50), restored.quantile(0.50));
        assertEquals(h.quantile(0.95), restored.quantile(0.95));
        assertEquals(h.quantile(0.99), restored.quantile(0.99));
    }

    @Test
    void emptyHistogram() {
        LogHistogram h = new LogHistogram();
        assertEquals(0, h.count());
        assertEquals(0, h.min());
        assertEquals(0, h.max());
        assertEquals(0, h.quantile(0.99));
        assertEquals(0.0, h.mean());
    }

    @Test
    void singleValue() {
        LogHistogram h = new LogHistogram();
        h.record(42);
        assertEquals(1, h.count());
        assertEquals(42, h.min());
        assertEquals(42, h.max());
        assertEquals(42, h.quantile(0.5));
        assertEquals(42, h.quantile(0.99));
    }
}
