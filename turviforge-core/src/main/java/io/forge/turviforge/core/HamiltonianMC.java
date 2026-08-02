package io.forge.turviforge.core;

import java.util.Random;

/**
 * Hamiltonian Monte Carlo sampler — leapfrog integrator with Metropolis
 * accept/reject and a simple warm-up step-size adaptation. Pure Java, zero
 * dependencies. Powers the Bayesian hierarchical comparison engine so the
 * product stays a self-contained JAR (no PyMC/NumPyro service required).
 *
 * Deterministic for a fixed seed, keeping reports reproducible.
 */
public final class HamiltonianMC {

    /** Target distribution: returns the log density (unnormalised) and fills {@code grad}. */
    public interface Target {
        double logDensityAndGrad(double[] q, double[] grad);
    }

    private final Random rng;
    private int leapfrogSteps = 20;
    private int warmup = 500;
    private int samples = 2000;
    private double stepSize = 0.1;

    public HamiltonianMC(long seed) { this.rng = new Random(seed); }

    public HamiltonianMC leapfrogSteps(int l) { this.leapfrogSteps = Math.max(1, l); return this; }
    public HamiltonianMC warmup(int w) { this.warmup = Math.max(0, w); return this; }
    public HamiltonianMC samples(int s) { this.samples = Math.max(1, s); return this; }
    public HamiltonianMC stepSize(double e) { this.stepSize = e; return this; }

    /**
     * Draw posterior samples starting from {@code init}.
     * Returns {@code double[samples][dim]} (post-warm-up draws).
     */
    public double[][] sample(Target target, double[] init) {
        final int dim = init.length;
        double[] q = init.clone();
        double[] grad = new double[dim];
        double[] p = new double[dim];
        double[] qProp = new double[dim];
        double[] gradProp = new double[dim];
        double eps = stepSize;

        double curLogP = target.logDensityAndGrad(q, grad);
        if (Double.isNaN(curLogP)) curLogP = Double.NEGATIVE_INFINITY;

        double[][] out = new double[samples][dim];
        long accepted = 0;
        int total = warmup + samples;

        for (int it = 0; it < total; it++) {
            // Sample momentum ~ N(0, I)
            for (int i = 0; i < dim; i++) p[i] = rng.nextGaussian();
            double kinetic0 = 0.5 * dot(p, p);

            // Leapfrog integration
            System.arraycopy(q, 0, qProp, 0, dim);
            System.arraycopy(grad, 0, gradProp, 0, dim);
            boolean diverged = false;

            for (int i = 0; i < dim; i++) p[i] += 0.5 * eps * gradProp[i];
            for (int step = 0; step < leapfrogSteps; step++) {
                for (int i = 0; i < dim; i++) qProp[i] += eps * p[i];
                double lp = target.logDensityAndGrad(qProp, gradProp);
                if (Double.isNaN(lp) || Double.isInfinite(lp)) { diverged = true; break; }
                if (step < leapfrogSteps - 1) {
                    for (int i = 0; i < dim; i++) p[i] += eps * gradProp[i];
                }
            }
            if (!diverged) {
                for (int i = 0; i < dim; i++) p[i] += 0.5 * eps * gradProp[i];
            }

            double propLogP = diverged ? Double.NEGATIVE_INFINITY
                    : target.logDensityAndGrad(qProp, gradProp);
            double kinetic1 = 0.5 * dot(p, p);
            double logAccept = (propLogP - kinetic1) - (curLogP - kinetic0);

            boolean accept = !diverged
                    && !Double.isNaN(propLogP)
                    && Math.log(rng.nextDouble()) < logAccept;
            if (accept) {
                double[] tq = q; q = qProp; qProp = tq;
                double[] tg = grad; grad = gradProp; gradProp = tg;
                curLogP = propLogP;
                accepted++;
            }

            // Simple step-size adaptation during warm-up (target ~0.6-0.85)
            if (it < warmup) {
                double rate = (double) accepted / (it + 1);
                if (rate < 0.60) eps *= 0.90;
                else if (rate > 0.85) eps *= 1.05;
            } else {
                out[it - warmup] = q.clone();
            }
        }
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
