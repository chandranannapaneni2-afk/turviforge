package io.forge.turviforge.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bayesian hierarchical run-to-run comparison, sampled with Hamiltonian Monte
 * Carlo (see {@link HamiltonianMC}).
 *
 * <p>Each label's log-p95 shift {@code δ_ℓ} is a draw from a shared group
 * distribution (partial pooling):
 * <pre>
 *   d_ℓ | δ_ℓ ~ Normal(δ_ℓ, s_ℓ)      // observed shift, SE from quantile theory
 *   δ_ℓ | μ,τ ~ Normal(μ, τ)          // hierarchical prior — borrows strength
 *   μ         ~ Normal(0, σ_μ)         // across labels; small/fat-tailed labels
 *   τ         ~ HalfNormal(σ_τ)        // are regularised toward the group
 * </pre>
 *
 * <p>Unlike a Mann-Whitney p-value, this yields a directly actionable statement:
 * "92% probability this build regressed Checkout p95 by more than 10%", with a
 * credible interval on the shift.
 */
public final class BayesianComparison {

    public static final class LabelPosterior {
        public String label;
        public double probRegression;   // P(δ > log(1+tolerance))
        public double shiftPctMean;     // posterior mean % shift in p95
        public double shiftPctCiLow;    // 90% credible interval (lower)
        public double shiftPctCiHigh;   // 90% credible interval (upper)
    }

    public static final class Result {
        public final List<LabelPosterior> labels = new ArrayList<>();
        public double groupMeanShiftPct;  // posterior mean of the group shift μ, in %
    }

    private final double tolerancePct;
    private final long seed;
    private int warmup = 500;
    private int samples = 2000;

    public BayesianComparison(double tolerancePct, long seed) {
        this.tolerancePct = tolerancePct;
        this.seed = seed;
    }

    public BayesianComparison iterations(int warmup, int samples) {
        this.warmup = warmup; this.samples = samples; return this;
    }

    /**
     * Run the hierarchical inference.
     *
     * @param labels label names (parallel with {@code d} and {@code s})
     * @param d      observed log-p95 shift per label, log(cand/base)
     * @param s      standard error of each shift
     */
    public Result infer(String[] labels, double[] d, double[] s) {
        final int L = labels.length;
        Result r = new Result();
        if (L == 0) return r;
        final double thresh = Math.log(1.0 + tolerancePct / 100.0);
        final double sigmaMu = 1.0, sigmaTau = 0.5;

        // Initial state: z at 0 (so δ = d), μ at the mean shift, τ modest.
        double[] q0 = new double[L + 2];
        double sum = 0;
        for (int l = 0; l < L; l++) sum += d[l];
        q0[L] = sum / L;
        q0[L + 1] = Math.log(0.3);

        // Sample in standardised coordinates z_ℓ = (δ_ℓ − d_ℓ)/s_ℓ so the
        // likelihood is exactly N(0,1) and the posterior is O(1)-scale. This keeps
        // the leapfrog integrator well-conditioned even when the per-label SE is
        // tiny (large n), where sampling δ directly would freeze the chain.
        HamiltonianMC.Target target = (qq, grad) -> {
            double mu = qq[L];
            double eta = qq[L + 1];
            double tau2 = Math.exp(2 * eta);
            double logp = 0, sumSqDev = 0, sumDevOverTau2 = 0;
            for (int l = 0; l < L; l++) {
                double delta = d[l] + s[l] * qq[l];            // δ_ℓ = d_ℓ + s_ℓ z_ℓ
                logp += -0.5 * qq[l] * qq[l];                  // likelihood: z_ℓ ~ N(0,1)
                double dev = delta - mu;                        // hierarchical prior
                logp += -0.5 * dev * dev / tau2;
                sumSqDev += dev * dev;
                sumDevOverTau2 += dev / tau2;
                grad[l] = -qq[l] - s[l] * dev / tau2;
            }
            logp += -L * eta;                                   // Σ -log τ
            logp += -0.5 * mu * mu / (sigmaMu * sigmaMu);       // μ prior
            logp += -tau2 / (2 * sigmaTau * sigmaTau) + eta;    // HalfNormal(τ) + Jacobian
            grad[L] = sumDevOverTau2 - mu / (sigmaMu * sigmaMu);
            grad[L + 1] = sumSqDev / tau2 - (L - 1) - tau2 / (sigmaTau * sigmaTau);
            return logp;
        };

        double[][] draws = new HamiltonianMC(seed)
                .warmup(warmup).samples(samples)
                .sample(target, q0);

        // Per-label posterior summaries (transform z back to δ = d + s·z)
        double[] shiftSamples = new double[samples];
        for (int l = 0; l < L; l++) {
            int above = 0;
            double mean = 0;
            for (int k = 0; k < samples; k++) {
                double v = d[l] + s[l] * draws[k][l];
                shiftSamples[k] = v;
                mean += v;
                if (v > thresh) above++;
            }
            Arrays.sort(shiftSamples);
            LabelPosterior lp = new LabelPosterior();
            lp.label = labels[l];
            lp.probRegression = (double) above / samples;
            lp.shiftPctMean = (Math.exp(mean / samples) - 1) * 100.0;
            lp.shiftPctCiLow = (Math.exp(quantile(shiftSamples, 0.05)) - 1) * 100.0;
            lp.shiftPctCiHigh = (Math.exp(quantile(shiftSamples, 0.95)) - 1) * 100.0;
            r.labels.add(lp);
        }

        // Group-level shift (μ)
        double g = 0;
        for (double[] draw : draws) g += Math.exp(draw[L]);
        r.groupMeanShiftPct = (g / samples - 1) * 100.0;
        return r;
    }

    /** Standard error of log(p95) via the asymptotic quantile variance + delta method. */
    static double seLogP95(LogHistogram h) {
        long n = h.count();
        if (n < 20) return Double.NaN;
        double p = 0.95;
        double f = densityAt(h, p);
        long p95 = h.quantile(p);
        if (f <= 0 || p95 <= 0) return Double.NaN;
        double varQ = p * (1 - p) / (n * f * f);   // asymptotic variance of the p95 estimator
        return Math.sqrt(varQ) / p95;              // delta method: SE(log X) ≈ SE(X)/X
    }

    /** Approximate density (probability mass per ms) at quantile {@code p}. */
    static double densityAt(LogHistogram h, double p) {
        long n = h.count();
        if (n == 0) return 0;
        long[] c = h.countsArray();
        long target = Math.max(1, (long) Math.ceil(p * n));
        long seen = 0;
        for (int slot = 0; slot < c.length; slot++) {
            seen += c[slot];
            if (seen >= target && c[slot] > 0) {
                return (double) c[slot] / (n * slotWidth(slot));
            }
        }
        return 0;
    }

    /** Width (ms) of a histogram slot. */
    static double slotWidth(int slot) {
        if (slot < 128) return 1.0;
        int shift = slot / 128 - 1;
        return (double) (1L << shift);
    }

    private static double quantile(double[] sorted, double q) {
        int idx = (int) Math.min(sorted.length - 1, Math.max(0, Math.round(q * (sorted.length - 1))));
        return sorted[idx];
    }
}
