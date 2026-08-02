package io.forge.reportforge.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ComparisonEngine (M5). */
class ComparisonEngineTest {

    /** Build a histogram with uniform values in [lo, hi]. */
    private LogHistogram uniformHistogram(int lo, int hi, int count) {
        LogHistogram h = new LogHistogram();
        for (int i = 0; i < count; i++) {
            int v = lo + (int) ((long) i * (hi - lo) / Math.max(1, count - 1));
            h.record(Math.max(1, v));
        }
        return h;
    }

    @Test
    void mannWhitneyIdenticalDistributions() {
        LogHistogram a = uniformHistogram(100, 500, 1000);
        LogHistogram b = uniformHistogram(100, 500, 1000);
        double[] mwu = ComparisonEngine.mannWhitneyU(a, b);
        // Identical distributions: p-value should be high (not significant)
        assertTrue(mwu[2] > 0.05, "p-value " + mwu[2] + " should be > 0.05 for identical distributions");
    }

    @Test
    void mannWhitneyDifferentDistributions() {
        LogHistogram a = uniformHistogram(50, 200, 1000);
        LogHistogram b = uniformHistogram(400, 900, 1000);
        double[] mwu = ComparisonEngine.mannWhitneyU(a, b);
        // Very different distributions: p-value should be tiny
        assertTrue(mwu[2] < 0.001, "p-value " + mwu[2] + " should be < 0.001 for shifted distributions");
    }

    @Test
    void mannWhitneyEmptyHistogram() {
        LogHistogram empty = new LogHistogram();
        LogHistogram full = uniformHistogram(100, 500, 100);
        double[] mwu = ComparisonEngine.mannWhitneyU(empty, full);
        assertEquals(1.0, mwu[2], 0.001); // p=1 when one is empty
    }

    @Test
    void cliffsDeltaIdentical() {
        LogHistogram a = uniformHistogram(100, 500, 500);
        LogHistogram b = uniformHistogram(100, 500, 500);
        double d = ComparisonEngine.cliffsDelta(a, b);
        // Identical distributions: delta near 0
        assertTrue(Math.abs(d) < 0.1, "Cliff's delta " + d + " should be near 0 for identical distributions");
    }

    @Test
    void cliffsDeltaShifted() {
        LogHistogram a = uniformHistogram(50, 150, 500);
        LogHistogram b = uniformHistogram(500, 900, 500);
        double d = ComparisonEngine.cliffsDelta(a, b);
        // b is much larger: delta should be strongly negative (h1 < h2)
        assertTrue(d < -0.5, "Cliff's delta " + d + " should be strongly negative for right-shifted h2");
    }

    @Test
    void cliffsDeltaEmpty() {
        LogHistogram empty = new LogHistogram();
        LogHistogram full = uniformHistogram(100, 500, 100);
        assertEquals(0.0, ComparisonEngine.cliffsDelta(empty, full));
    }

    @Test
    void fullComparisonDetectsRegression() {
        // Baseline: fast responses
        ReportModel baseline = buildModel("Baseline Run", 100, 300, 0.1, 200);
        // Candidate: much slower (regression)
        ReportModel candidate = buildModel("Candidate Run", 400, 900, 2.0, 150);

        ComparisonEngine engine = new ComparisonEngine(new ComparisonEngine.Config());
        ComparisonEngine.ComparisonResult result = engine.compare(baseline, candidate);

        assertFalse(result.labels.isEmpty());
        ComparisonEngine.LabelDelta total = result.labels.stream()
                .filter(d -> d.label.equals("TOTAL")).findFirst().orElse(null);
        assertNotNull(total);
        assertTrue(total.p95DeltaPct > 10, "p95 should regress > 10%");
        assertTrue(total.significant, "Should be statistically significant");
        assertTrue(total.regression, "Should flag regression");
        assertTrue(result.hasRegression);
    }

    @Test
    void fullComparisonNoRegressionWithinTolerance() {
        // Nearly identical runs
        ReportModel baseline = buildModel("Run A", 100, 300, 0.1, 200);
        ReportModel candidate = buildModel("Run B", 105, 310, 0.12, 198);

        ComparisonEngine engine = new ComparisonEngine(new ComparisonEngine.Config());
        ComparisonEngine.ComparisonResult result = engine.compare(baseline, candidate);

        ComparisonEngine.LabelDelta total = result.labels.stream()
                .filter(d -> d.label.equals("TOTAL")).findFirst().orElse(null);
        assertNotNull(total);
        // Small shift may or may not be significant, but should NOT be regression
        assertFalse(total.regression, "Small deltas within tolerance should not flag regression");
        assertFalse(result.hasRegression);
    }

    @Test
    void comparisonSkipsLabelsNotInBaseline() {
        ReportModel baseline = new ReportModel();
        baseline.title = "Base";
        baseline.total = makeLabelStats("TOTAL", 100, 300, 0.0, 100);

        ReportModel candidate = new ReportModel();
        candidate.title = "Cand";
        candidate.total = makeLabelStats("TOTAL", 100, 300, 0.0, 100);
        candidate.labels.add(makeLabelStats("NewLabel", 100, 300, 0.0, 100));

        ComparisonEngine engine = new ComparisonEngine(new ComparisonEngine.Config());
        ComparisonEngine.ComparisonResult result = engine.compare(baseline, candidate);

        // Only TOTAL should be compared (NewLabel not in baseline)
        assertEquals(1, result.labels.size());
        assertEquals("TOTAL", result.labels.get(0).label);
    }

    // --- Helpers ---

    private ReportModel buildModel(String title, int lo, int hi, double errorRate, double throughput) {
        ReportModel m = new ReportModel();
        m.title = title;
        m.total = makeLabelStats("TOTAL", lo, hi, errorRate, throughput);
        m.labels.add(makeLabelStats("API", lo, hi, errorRate, throughput));
        return m;
    }

    private ReportModel.LabelStats makeLabelStats(String name, int lo, int hi, double errorRate, double throughput) {
        ReportModel.LabelStats ls = new ReportModel.LabelStats();
        ls.name = name;
        ls.stats = new ReportModel.Stats();
        ls.stats.n = 1000;
        ls.stats.errors = (long) (errorRate * 10);
        ls.stats.errorRate = errorRate;
        ls.stats.throughput = throughput;
        ls.stats.mean = (lo + hi) / 2.0;
        ls.stats.apdex = 0.85;

        LogHistogram h = uniformHistogram(lo, hi, 1000);
        ls.stats.pct.put("50", h.quantile(0.50));
        ls.stats.pct.put("90", h.quantile(0.90));
        ls.stats.pct.put("95", h.quantile(0.95));
        ls.stats.pct.put("99", h.quantile(0.99));
        ls.histogramB64 = h.toBase64();
        return ls;
    }
}
