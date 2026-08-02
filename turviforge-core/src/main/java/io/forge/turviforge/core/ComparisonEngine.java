package io.forge.turviforge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * M5 — Comparison Engine (FR-501..504).
 * Compares a candidate report-data.json against a baseline:
 * per-label deltas, Mann-Whitney U significance from serialized histograms,
 * Cliff's delta effect size, and regression verdict.
 */
public final class ComparisonEngine {

    public static final class Config {
        public double pThreshold = 0.01;          // significance level
        public double cliffsDeltaThreshold = 0.147; // small effect size
        public double p95TolerancePct = 10.0;     // % regression tolerance for p95
        public double errorRateTolerancePp = 0.2; // percentage-point tolerance
        public double throughputTolerancePct = 10.0; // % throughput drop tolerance
    }

    public static final class LabelDelta {
        public String label;
        public long baseN, candN;
        public double baseThroughput, candThroughput, throughputDeltaPct;
        public double baseErrorRate, candErrorRate, errorRateDeltaPp;
        public double baseMean, candMean, meanDeltaPct;
        public long baseP50, candP50, baseP90, candP90, baseP95, candP95, baseP99, candP99;
        public double p95DeltaPct;
        public double baseApdex, candApdex;
        // Statistical significance
        public double mannWhitneyU, zScore, pValue;
        public double cliffsDelta;
        public boolean significant;
        public boolean regression;
        // Bayesian hierarchical posterior (HMC)
        public double probRegression;   // P(p95 shift > tolerance), partial-pooled across labels
        public double shiftPctMean;     // posterior mean % shift in p95
        public double shiftPctCiLow;    // 90% credible interval (lower)
        public double shiftPctCiHigh;   // 90% credible interval (upper)
        public boolean bayesian;        // true when posterior fields are populated
    }

    public static final class ComparisonResult {
        public String baselineTitle;
        public String baselineGeneratedAt;
        public final List<LabelDelta> labels = new ArrayList<>();
        public boolean hasRegression;
    }

    private final Config cfg;

    public ComparisonEngine(Config cfg) { this.cfg = cfg; }

    /**
     * Compare candidate model against a baseline model.
     * Both must have histogram data for significance testing.
     */
    public ComparisonResult compare(ReportModel baseline, ReportModel candidate) {
        ComparisonResult result = new ComparisonResult();
        result.baselineTitle = baseline.title;

        // Build lookup for baseline labels
        Map<String, ReportModel.LabelStats> baseMap = new TreeMap<>();
        if (baseline.total != null) baseMap.put("TOTAL", baseline.total);
        for (ReportModel.LabelStats ls : baseline.labels) baseMap.put(ls.name, ls);

        // Compare candidate labels against baseline
        List<ReportModel.LabelStats> candLabels = new ArrayList<>();
        if (candidate.total != null) candLabels.add(candidate.total);
        candLabels.addAll(candidate.labels);

        for (ReportModel.LabelStats cand : candLabels) {
            ReportModel.LabelStats base = baseMap.get(cand.name);
            if (base == null) continue; // label not in baseline, skip

            LabelDelta d = new LabelDelta();
            d.label = cand.name;
            d.baseN = base.stats.n;
            d.candN = cand.stats.n;
            d.baseThroughput = base.stats.throughput;
            d.candThroughput = cand.stats.throughput;
            d.throughputDeltaPct = pctChange(base.stats.throughput, cand.stats.throughput);
            d.baseErrorRate = base.stats.errorRate;
            d.candErrorRate = cand.stats.errorRate;
            d.errorRateDeltaPp = cand.stats.errorRate - base.stats.errorRate;
            d.baseMean = base.stats.mean;
            d.candMean = cand.stats.mean;
            d.meanDeltaPct = pctChange(base.stats.mean, cand.stats.mean);
            d.baseP50 = getPct(base, "50"); d.candP50 = getPct(cand, "50");
            d.baseP90 = getPct(base, "90"); d.candP90 = getPct(cand, "90");
            d.baseP95 = getPct(base, "95"); d.candP95 = getPct(cand, "95");
            d.baseP99 = getPct(base, "99"); d.candP99 = getPct(cand, "99");
            d.p95DeltaPct = pctChange(d.baseP95, d.candP95);
            d.baseApdex = base.stats.apdex;
            d.candApdex = cand.stats.apdex;

            // Statistical significance from histograms (FR-502)
            if (base.histogramB64 != null && cand.histogramB64 != null
                    && !base.histogramB64.isEmpty() && !cand.histogramB64.isEmpty()) {
                try {
                    LogHistogram hBase = LogHistogram.fromBase64(base.histogramB64);
                    LogHistogram hCand = LogHistogram.fromBase64(cand.histogramB64);
                    double[] mwu = mannWhitneyU(hBase, hCand);
                    d.mannWhitneyU = mwu[0];
                    d.zScore = mwu[1];
                    d.pValue = mwu[2];
                    d.cliffsDelta = cliffsDelta(hBase, hCand);
                    d.significant = d.pValue < cfg.pThreshold
                            && Math.abs(d.cliffsDelta) >= cfg.cliffsDeltaThreshold;
                } catch (Exception e) {
                    d.significant = false;
                }
            }

            // Regression verdict (FR-503): significant AND exceeds tolerance
            d.regression = d.significant && (
                    d.p95DeltaPct > cfg.p95TolerancePct
                    || d.errorRateDeltaPp > cfg.errorRateTolerancePp
                    || d.throughputDeltaPct < -cfg.throughputTolerancePct);

            if (d.regression) result.hasRegression = true;
            result.labels.add(d);
        }

        applyBayesian(result, baseline, candidate);
        return result;
    }

    /**
     * Hierarchical Bayesian comparison (HMC): partial-pools each label's
     * log-p95 shift toward a shared group distribution and reports
     * P(shift > tolerance) plus a credible interval. Populates the
     * {@code probRegression}/{@code shiftPct*} fields on each label.
     */
    private void applyBayesian(ComparisonResult result, ReportModel baseline, ReportModel candidate) {
        Map<String, ReportModel.LabelStats> baseMap = labelLookup(baseline);
        Map<String, ReportModel.LabelStats> candMap = labelLookup(candidate);

        List<String> names = new ArrayList<>();
        List<Double> dList = new ArrayList<>();
        List<Double> sList = new ArrayList<>();
        List<LabelDelta> deltas = new ArrayList<>();

        for (LabelDelta ld : result.labels) {
            ReportModel.LabelStats b = baseMap.get(ld.label);
            ReportModel.LabelStats c = candMap.get(ld.label);
            if (b == null || c == null
                    || b.histogramB64 == null || b.histogramB64.isEmpty()
                    || c.histogramB64 == null || c.histogramB64.isEmpty()) continue;
            try {
                LogHistogram hb = LogHistogram.fromBase64(b.histogramB64);
                LogHistogram hc = LogHistogram.fromBase64(c.histogramB64);
                long p95b = hb.quantile(0.95), p95c = hc.quantile(0.95);
                if (p95b <= 0 || p95c <= 0) continue;
                double seb = BayesianComparison.seLogP95(hb);
                double sec = BayesianComparison.seLogP95(hc);
                if (Double.isNaN(seb) || Double.isNaN(sec)) continue;
                double s = Math.sqrt(seb * seb + sec * sec);
                if (s <= 0) continue;
                names.add(ld.label);
                dList.add(Math.log((double) p95c / p95b));
                sList.add(s);
                deltas.add(ld);
            } catch (Exception e) {
                // skip labels with unusable histograms
            }
        }
        if (names.isEmpty()) return;

        double[] d = new double[names.size()];
        double[] s = new double[names.size()];
        for (int i = 0; i < d.length; i++) { d[i] = dList.get(i); s[i] = sList.get(i); }

        BayesianComparison.Result br = new BayesianComparison(cfg.p95TolerancePct, 42L)
                .infer(names.toArray(new String[0]), d, s);

        for (int i = 0; i < deltas.size() && i < br.labels.size(); i++) {
            LabelDelta ld = deltas.get(i);
            BayesianComparison.LabelPosterior lp = br.labels.get(i);
            ld.probRegression = lp.probRegression;
            ld.shiftPctMean = lp.shiftPctMean;
            ld.shiftPctCiLow = lp.shiftPctCiLow;
            ld.shiftPctCiHigh = lp.shiftPctCiHigh;
            ld.bayesian = true;
        }
    }

    private static Map<String, ReportModel.LabelStats> labelLookup(ReportModel m) {
        Map<String, ReportModel.LabelStats> map = new TreeMap<>();
        if (m.total != null) map.put("TOTAL", m.total);
        for (ReportModel.LabelStats ls : m.labels) map.put(ls.name, ls);
        return map;
    }

    /**
     * Mann-Whitney U test from two histograms (FR-502).
     * Computes rank sums over merged value buckets weighted by counts —
     * mathematically exact for tied-bucket data.
     * Returns [U, z-score, p-value (two-tailed)].
     */
    static double[] mannWhitneyU(LogHistogram h1, LogHistogram h2) {
        long n1 = h1.count(), n2 = h2.count();
        if (n1 == 0 || n2 == 0) return new double[]{0, 0, 1};

        // Iterate all slots, compute rank sums using mid-rank for ties
        long[] c1 = h1.countsArray();
        long[] c2 = h2.countsArray();
        int slots = c1.length;

        double rankSum1 = 0;
        long cumulative = 0;
        for (int i = 0; i < slots; i++) {
            long tied = c1[i] + c2[i];
            if (tied == 0) continue;
            // Mid-rank for this group of tied values
            double midRank = cumulative + (tied + 1) / 2.0;
            rankSum1 += c1[i] * midRank;
            cumulative += tied;
        }

        double U1 = rankSum1 - (double) n1 * (n1 + 1) / 2.0;
        double U2 = (double) n1 * n2 - U1;
        double U = Math.min(U1, U2);

        // Normal approximation for z-score
        double mu = (double) n1 * n2 / 2.0;
        double sigma = Math.sqrt((double) n1 * n2 * (n1 + n2 + 1) / 12.0);
        double z = sigma == 0 ? 0 : (U - mu) / sigma;

        // Two-tailed p-value from standard normal
        double p = 2.0 * normalCdf(-Math.abs(z));
        return new double[]{U, z, p};
    }

    /**
     * Cliff's delta effect size from two histograms.
     * Returns value in [-1, 1]; |d| >= 0.147 is "small" effect.
     */
    static double cliffsDelta(LogHistogram h1, LogHistogram h2) {
        long n1 = h1.count(), n2 = h2.count();
        if (n1 == 0 || n2 == 0) return 0;

        long[] c1 = h1.countsArray();
        long[] c2 = h2.countsArray();
        int slots = c1.length;

        // Count pairs where h1 > h2 minus pairs where h1 < h2
        long more = 0, less = 0;
        long cumBelow1 = 0, cumBelow2 = 0;
        for (int i = 0; i < slots; i++) {
            // Pairs where slot i of h1 > all below in h2
            more += c1[i] * cumBelow2;
            // Pairs where slot i of h2 > all below in h1
            less += c2[i] * cumBelow1;
            cumBelow1 += c1[i];
            cumBelow2 += c2[i];
        }
        // Note: equal pairs contribute 0 to delta
        return (double) (more - less) / ((double) n1 * n2);
    }

    /** Standard normal CDF approximation (Abramowitz & Stegun). */
    private static double normalCdf(double x) {
        if (x < -8) return 0;
        if (x > 8) return 1;
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422804014327 * Math.exp(-x * x / 2.0);
        double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
        return x > 0 ? 1.0 - p : p;
    }

    private static double pctChange(double base, double cand) {
        if (base == 0) return cand == 0 ? 0 : 100.0;
        return (cand - base) / base * 100.0;
    }

    private static long getPct(ReportModel.LabelStats ls, String q) {
        Long v = ls.stats.pct.get(q);
        return v == null ? 0 : v;
    }
}
