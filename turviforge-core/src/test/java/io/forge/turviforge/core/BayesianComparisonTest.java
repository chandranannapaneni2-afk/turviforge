package io.forge.turviforge.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the HMC sampler and the Bayesian hierarchical comparison. */
class BayesianComparisonTest {

    /* ---------------- HMC sampler correctness ---------------- */

    @Test
    void hmcRecoversGaussianMean() {
        // Target: 1-D Normal(3, 1)
        HamiltonianMC.Target t = (q, g) -> {
            g[0] = -(q[0] - 3.0);
            return -0.5 * (q[0] - 3.0) * (q[0] - 3.0);
        };
        double[][] draws = new HamiltonianMC(42).warmup(500).samples(3000).sample(t, new double[]{0});
        double mean = 0;
        for (double[] d : draws) mean += d[0];
        mean /= draws.length;
        assertEquals(3.0, mean, 0.2, "HMC should recover the mean of Normal(3,1)");
    }

    @Test
    void hmcRecoversGaussianStdDev() {
        HamiltonianMC.Target t = (q, g) -> {
            g[0] = -(q[0] - 3.0);
            return -0.5 * (q[0] - 3.0) * (q[0] - 3.0);
        };
        double[][] draws = new HamiltonianMC(42).warmup(500).samples(3000).sample(t, new double[]{0});
        double mean = 0;
        for (double[] d : draws) mean += d[0];
        mean /= draws.length;
        double var = 0;
        for (double[] d : draws) var += (d[0] - mean) * (d[0] - mean);
        var /= draws.length;
        assertEquals(1.0, Math.sqrt(var), 0.25, "HMC should recover the sd of Normal(3,1)");
    }

    /* ---------------- End-to-end comparison ---------------- */

    @Test
    void detectsClearRegression() {
        ReportModel baseline = buildModel(100, 300);
        ReportModel candidate = buildModel(400, 900); // ~3x slowdown
        ComparisonEngine.ComparisonResult r =
                new ComparisonEngine(new ComparisonEngine.Config()).compare(baseline, candidate);
        ComparisonEngine.LabelDelta total = find(r, "TOTAL");
        assertTrue(total.bayesian, "Bayesian posterior should be populated");
        assertTrue(total.probRegression > 0.9,
                "P(regression) " + total.probRegression + " should be > 0.9 for a 3x slowdown");
        assertTrue(total.shiftPctMean > 50, "Posterior mean shift should be large and positive");
    }

    @Test
    void identicalRunsLowRegressionProbability() {
        ReportModel baseline = buildModel(100, 300);
        ReportModel candidate = buildModel(100, 300);
        ComparisonEngine.ComparisonResult r =
                new ComparisonEngine(new ComparisonEngine.Config()).compare(baseline, candidate);
        ComparisonEngine.LabelDelta total = find(r, "TOTAL");
        assertTrue(total.bayesian, "Bayesian posterior should be populated");
        assertTrue(total.probRegression < 0.2,
                "P(regression) " + total.probRegression + " should be low for identical runs");
    }

    /* ---------------- Partial pooling ---------------- */

    @Test
    void partialPoolingShrinksNoisyLabel() {
        // Four tight labels agreeing near zero, one noisy label observed at +0.6 (~82%)
        // with a very large standard error. Partial pooling must shrink it toward the group.
        String[] labels = {"A", "B", "C", "D", "noisy"};
        double[] d = {0.0, 0.02, -0.01, 0.01, 0.6};
        double[] s = {0.02, 0.02, 0.02, 0.02, 1.0};
        BayesianComparison.Result r = new BayesianComparison(10.0, 42L).infer(labels, d, s);
        BayesianComparison.LabelPosterior noisy = r.labels.get(4);
        assertTrue(noisy.shiftPctMean < 50.0,
                "Noisy label shift " + noisy.shiftPctMean + "% should be shrunk toward the ~0 group (raw ~82%)");
    }

    /* ---------------- SE estimation ---------------- */

    @Test
    void seLogP95IsPositiveAndSmall() {
        LogHistogram h = uniformHistogram(100, 500, 5000);
        double se = BayesianComparison.seLogP95(h);
        assertTrue(se > 0 && Double.isFinite(se), "SE of log-p95 should be positive and finite");
        assertTrue(se < 0.5, "SE should be small for 5000 samples, got " + se);
    }

    // --- helpers ---

    private static ComparisonEngine.LabelDelta find(ComparisonEngine.ComparisonResult r, String label) {
        return r.labels.stream().filter(x -> x.label.equals(label)).findFirst().orElseThrow();
    }

    private static ReportModel buildModel(int lo, int hi) {
        ReportModel m = new ReportModel();
        m.title = "Run";
        m.total = makeLabelStats("TOTAL", lo, hi);
        m.labels.add(makeLabelStats("API", lo, hi));
        return m;
    }

    private static ReportModel.LabelStats makeLabelStats(String name, int lo, int hi) {
        ReportModel.LabelStats ls = new ReportModel.LabelStats();
        ls.name = name;
        ls.stats = new ReportModel.Stats();
        ls.stats.n = 1000;
        ls.stats.errorRate = 0.0;
        ls.stats.throughput = 200;
        ls.stats.mean = (lo + hi) / 2.0;
        ls.stats.apdex = 0.9;
        LogHistogram h = uniformHistogram(lo, hi, 1000);
        ls.stats.pct.put("50", h.quantile(0.50));
        ls.stats.pct.put("95", h.quantile(0.95));
        ls.histogramB64 = h.toBase64();
        return ls;
    }

    private static LogHistogram uniformHistogram(int lo, int hi, int count) {
        LogHistogram h = new LogHistogram();
        for (int i = 0; i < count; i++) {
            int v = lo + (int) ((long) i * (hi - lo) / Math.max(1, count - 1));
            h.record(Math.max(1, v));
        }
        return h;
    }
}
