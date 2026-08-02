package io.forge.turviforge.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for MetricsPipeline (M2). */
class MetricsPipelineTest {

    private SampleEvent ev(long ts, int elapsed, String label, boolean success, int threads) {
        return SampleEvent.csv(ts, elapsed, label, success ? "200" : "500", success,
                success ? "" : "error", success ? "OK" : "Internal Server Error",
                elapsed - 20, 5, 1024, 256, threads, threads, "TG1 1-1", "host1");
    }

    @Test
    void basicStats() {
        MetricsPipeline p = new MetricsPipeline(new MetricsPipeline.Config());
        long base = 1700000000000L;
        for (int i = 0; i < 100; i++) {
            p.accept(ev(base + i * 1000, 100 + i, "Login", true, 10));
        }
        ReportModel m = p.finish(null);
        assertNotNull(m.total);
        assertEquals(100, m.total.stats.n);
        assertEquals(0, m.total.stats.errors);
        assertTrue(m.total.stats.mean > 100);
        assertTrue(m.total.stats.pct.containsKey("95"));
        assertEquals(1, m.labels.size());
        assertEquals("Login", m.labels.get(0).name);
    }

    @Test
    void errorTracking() {
        MetricsPipeline p = new MetricsPipeline(new MetricsPipeline.Config());
        long base = 1700000000000L;
        for (int i = 0; i < 90; i++) p.accept(ev(base + i * 100, 100, "API", true, 5));
        for (int i = 0; i < 10; i++) p.accept(ev(base + 9000 + i * 100, 5000, "API", false, 5));
        ReportModel m = p.finish(null);
        assertEquals(100, m.total.stats.n);
        assertEquals(10, m.total.stats.errors);
        assertEquals(10.0, m.total.stats.errorRate, 0.01);
        assertFalse(m.errors.isEmpty());
    }

    @Test
    void labelCapOverflow() {
        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        cfg.labelCap = 3;
        MetricsPipeline p = new MetricsPipeline(cfg);
        long base = 1700000000000L;
        for (int i = 0; i < 5; i++) {
            p.accept(ev(base + i * 100, 100, "Label" + i, true, 1));
        }
        ReportModel m = p.finish(null);
        // 3 real labels + __OTHER__ = 4 (TOTAL is separate)
        assertTrue(m.labels.size() <= 4);
        assertTrue(m.warnings.stream().anyMatch(w -> w.contains("Label cap")));
    }

    @Test
    void steadyStateDetection() {
        MetricsPipeline p = new MetricsPipeline(new MetricsPipeline.Config());
        long base = 1700000000000L;
        // Ramp up: 10s at increasing threads
        for (int i = 0; i < 10; i++) p.accept(ev(base + i * 1000, 100, "X", true, i + 1));
        // Steady: 60s at 50 threads
        for (int i = 0; i < 60; i++) p.accept(ev(base + 10000 + i * 1000, 100, "X", true, 50));
        // Ramp down: 10s
        for (int i = 0; i < 10; i++) p.accept(ev(base + 70000 + i * 1000, 100, "X", true, 50 - i * 5));
        ReportModel m = p.finish(null);
        // Steady state should be detected in the middle region
        assertTrue(m.steadyEndMs > m.steadyStartMs);
    }

    @Test
    void subSampleExclusion() {
        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        cfg.includeSubSamples = false;
        MetricsPipeline p = new MetricsPipeline(cfg);
        long base = 1700000000000L;
        // Normal event
        p.accept(SampleEvent.csv(base, 100, "Parent", "200", true, "", "OK", 80, 5, 1024, 256, 10, 10, "T", "h"));
        // Sub-sample event (should be excluded)
        p.accept(new SampleEvent(base + 10, 50, "Child", "200", true, "", "OK", 40, 3, 512, 128, 10, 10, "T", "h", false, true, "Parent"));
        ReportModel m = p.finish(null);
        assertEquals(1, m.total.stats.n); // only parent counted
    }

    @Test
    void apdexComputation() {
        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        cfg.apdexSatisfiedMs = 100;
        cfg.apdexToleratedMs = 300;
        MetricsPipeline p = new MetricsPipeline(cfg);
        long base = 1700000000000L;
        // 50 satisfied (≤100ms), 30 tolerated (≤300ms), 20 frustrated (>300ms)
        for (int i = 0; i < 50; i++) p.accept(ev(base + i * 100, 80, "A", true, 1));
        for (int i = 0; i < 30; i++) p.accept(ev(base + 5000 + i * 100, 200, "A", true, 1));
        for (int i = 0; i < 20; i++) p.accept(ev(base + 8000 + i * 100, 500, "A", true, 1));
        ReportModel m = p.finish(null);
        // APDEX = (50 + 30/2) / 100 = 0.65
        assertEquals(0.65, m.total.stats.apdex, 0.01);
    }

    @Test
    void scalabilityCurveAndKnee() {
        MetricsPipeline p = new MetricsPipeline(new MetricsPipeline.Config());
        long base = 1700000000000L;
        // Simulate increasing concurrency with degrading throughput
        int t = 0;
        for (int threads = 10; threads <= 100; threads += 10) {
            for (int i = 0; i < 20; i++) {
                int elapsed = 50 + threads * 2; // latency grows with threads
                p.accept(ev(base + t * 1000, elapsed, "Svc", true, threads));
                t++;
            }
        }
        ReportModel m = p.finish(null);
        assertFalse(m.scalabilityCurve.isEmpty());
        assertTrue(m.scalabilityCurve.size() >= 5);
    }
}
