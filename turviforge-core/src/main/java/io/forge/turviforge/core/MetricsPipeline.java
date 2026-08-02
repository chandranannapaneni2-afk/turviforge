package io.forge.turviforge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * M2 — single-pass metrics engine. Consumes SampleEvents, produces a ReportModel.
 * Deterministic: single-threaded reduction, sorted emission (NFR-R1).
 */
public final class MetricsPipeline {

    public static final String TOTAL = "TOTAL";
    private static final int TOP_SLOWEST = 20;

    public static final class Config {
        public long bucketMs = 0;            // 0 = auto
        public int apdexSatisfiedMs = 500;
        public int apdexToleratedMs = 1500;
        public int labelCap = 2000;
        public boolean includeSubSamples = true;  // FR-107: include sub-samples in stats
        public Map<String, int[]> apdexOverrides = new HashMap<>(); // label -> [sat, tol]
    }

    static final class Acc {
        final LogHistogram elapsed = new LogHistogram();
        final LogHistogram latency = new LogHistogram();
        final LogHistogram connect = new LogHistogram();
        long n, errors, bytes, sentBytes, apdexSat, apdexTol;
        long minTs = Long.MAX_VALUE, maxTs = 0;
        final PriorityQueue<SampleEvent> slowest =
                new PriorityQueue<>(Comparator.comparingInt(SampleEvent::elapsedMs));
        boolean hasLatency, hasConnect;
    }

    static final class Bucket {
        long n, errors, sumElapsed, bytes, sentBytes;
        long maxElapsed;
        int maxThreads;
        long apdexSat, apdexTol, apdexN;
        final LogHistogram hist = new LogHistogram();
    }

    private final Config cfg;
    private final Map<String, Acc> accs = new HashMap<>();
    private final Map<String, TreeMap<Long, Bucket>> series = new HashMap<>();
    private final Map<String, ErrorCluster> clusters = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private long minTs = Long.MAX_VALUE, maxTs = 0;
    private boolean labelOverflow = false;

    public MetricsPipeline(Config cfg) { this.cfg = cfg; }

    /* -------------------------------------------------- ingest -- */

    public void accept(SampleEvent e) {
        // FR-107: skip sub-samples if configured
        if (e.isSubSample() && !cfg.includeSubSamples) return;
        String label = label(e.label());
        for (String key : new String[]{label, TOTAL}) {
            Acc a = accs.computeIfAbsent(key, k -> new Acc());
            long end = e.timestampMs() + e.elapsedMs();
            a.n++;
            a.elapsed.record(e.elapsedMs());
            if (e.latencyMs() >= 0) { a.latency.record(e.latencyMs()); a.hasLatency = true; }
            if (e.connectMs() >= 0) { a.connect.record(e.connectMs()); a.hasConnect = true; }
            if (!e.success()) a.errors++;
            a.bytes += e.bytes();
            a.sentBytes += e.sentBytes();
            int[] th = cfg.apdexOverrides.getOrDefault(key,
                    new int[]{cfg.apdexSatisfiedMs, cfg.apdexToleratedMs});
            if (e.success()) {
                if (e.elapsedMs() <= th[0]) a.apdexSat++;
                else if (e.elapsedMs() <= th[1]) a.apdexTol++;
            }
            a.minTs = Math.min(a.minTs, e.timestampMs());
            a.maxTs = Math.max(a.maxTs, end);
            a.slowest.offer(e);
            if (a.slowest.size() > TOP_SLOWEST) a.slowest.poll();
        }
        minTs = Math.min(minTs, e.timestampMs());
        maxTs = Math.max(maxTs, e.timestampMs() + e.elapsedMs());

        // Time series: store at 1s granularity for runs ≤2h, else at target bucket directly.
        long effectiveBucket = (maxTs - minTs > 2 * 3_600_000L && cfg.bucketMs > 0) ? cfg.bucketMs : 1000;
        long slot = e.timestampMs() / effectiveBucket * effectiveBucket;
        int[] th = cfg.apdexOverrides.getOrDefault(label,
                new int[]{cfg.apdexSatisfiedMs, cfg.apdexToleratedMs});
        for (String key : new String[]{label, TOTAL}) {
            Bucket b = series.computeIfAbsent(key, k -> new TreeMap<>())
                             .computeIfAbsent(slot, k -> new Bucket());
            b.n++;
            if (!e.success()) b.errors++;
            b.sumElapsed += e.elapsedMs();
            b.maxElapsed = Math.max(b.maxElapsed, e.elapsedMs());
            b.bytes += e.bytes();
            b.sentBytes += e.sentBytes();
            b.maxThreads = Math.max(b.maxThreads, e.allThreads());
            b.hist.record(e.elapsedMs());
            // Per-bucket APDEX counters for steady-state computation
            b.apdexN++;
            if (e.success()) {
                if (e.elapsedMs() <= th[0]) b.apdexSat++;
                else if (e.elapsedMs() <= th[1]) b.apdexTol++;
            }
        }

        if (!e.success()) {
            String sig = ErrorCluster.signature(e);
            ErrorCluster c = clusters.computeIfAbsent(sig, ErrorCluster::new);
            c.hit(e);
        }
    }

    private String label(String l) {
        if (accs.containsKey(l) || accs.size() < cfg.labelCap) return l;
        if (!labelOverflow) {
            labelOverflow = true;
            warnings.add("Label cap (" + cfg.labelCap + ") exceeded; overflow labels rolled into __OTHER__");
        }
        return "__OTHER__";
    }

    /* -------------------------------------------------- finish -- */

    public ReportModel finish(JtlCsvParser.Result parse) {
        if (parse != null && parse.malformed > 0) {
            warnings.add(parse.malformed + " malformed line(s) skipped of " + parse.total);
        }
        long runMs = Math.max(1, maxTs - minTs);
        long bucketMs = cfg.bucketMs > 0 ? cfg.bucketMs : autoBucket(runMs);

        ReportModel m = new ReportModel();
        m.startMs = minTs == Long.MAX_VALUE ? 0 : minTs;
        m.endMs = maxTs;
        m.bucketMs = bucketMs;
        m.warnings.addAll(warnings);

        // Re-bucket 1s slots to target granularity, deterministically.
        Map<String, TreeMap<Long, Bucket>> rebucketed = new LinkedHashMap<>();
        for (var entry : new TreeMap<>(series).entrySet()) {
            TreeMap<Long, Bucket> out = new TreeMap<>();
            for (var slotEntry : entry.getValue().entrySet()) {
                long key = (slotEntry.getKey() - m.startMs) / bucketMs * bucketMs + m.startMs;
                Bucket dst = out.computeIfAbsent(key, k -> new Bucket());
                Bucket s = slotEntry.getValue();
                dst.n += s.n; dst.errors += s.errors; dst.sumElapsed += s.sumElapsed;
                dst.maxElapsed = Math.max(dst.maxElapsed, s.maxElapsed);
                dst.bytes += s.bytes; dst.sentBytes += s.sentBytes;
                dst.maxThreads = Math.max(dst.maxThreads, s.maxThreads);
                dst.apdexSat += s.apdexSat; dst.apdexTol += s.apdexTol; dst.apdexN += s.apdexN;
                dst.hist.merge(s.hist);
            }
            rebucketed.put(entry.getKey(), out);
        }

        // Steady state from TOTAL thread series (FR-210): longest run within ±5% of modal plateau.
        long[] steady = detectSteadyState(rebucketed.get(TOTAL), bucketMs);
        m.steadyStartMs = steady[0];
        m.steadyEndMs = steady[1];

        // Per-label stats.
        for (var e : new TreeMap<>(accs).entrySet()) {
            String label = e.getKey();
            Acc a = e.getValue();
            ReportModel.LabelStats ls = new ReportModel.LabelStats();
            ls.name = label;
            ls.stats = stats(a, a.elapsed);
            ls.histogramB64 = a.elapsed.toBase64();
            if (a.hasLatency) ls.latencyPct = pct(a.latency);
            if (a.hasConnect) ls.connectPct = pct(a.connect);
            ls.steadyStats = steadyStats(rebucketed.get(label), a, steady);
            List<SampleEvent> slow = new ArrayList<>(a.slowest);
            slow.sort(Comparator.comparingInt(SampleEvent::elapsedMs).reversed());
            for (SampleEvent s : slow) {
                ls.topSlowest.add(new ReportModel.SlowSample(
                        s.timestampMs(), s.elapsedMs(), s.responseCode(), s.threadName()));
            }
            if (label.equals(TOTAL)) m.total = ls; else m.labels.add(ls);
        }

        // Series rows (TOTAL threads used for the overlay).
        TreeMap<Long, Bucket> totalSeries = rebucketed.getOrDefault(TOTAL, new TreeMap<>());
        for (var t : totalSeries.entrySet()) {
            ReportModel.SeriesRow row = new ReportModel.SeriesRow();
            row.t = t.getKey();
            row.threads = t.getValue().maxThreads;
            for (var lbl : rebucketed.entrySet()) {
                Bucket b = lbl.getValue().get(t.getKey());
                if (b == null) continue;
                ReportModel.SeriesCell c = new ReportModel.SeriesCell();
                c.n = b.n; c.err = b.errors;
                c.mean = b.n == 0 ? 0 : (double) b.sumElapsed / b.n;
                c.p95 = b.hist.quantile(0.95);
                c.max = b.maxElapsed;
                c.kb = b.bytes / 1024.0;
                c.kbSent = b.sentBytes / 1024.0;
                row.perLabel.put(lbl.getKey(), c);
            }
            m.series.add(row);
        }

        // Error clusters ranked by count (FR-207/208), onset metrics from series.
        clusters.values().stream()
                .sorted(Comparator.comparingLong((ErrorCluster c) -> c.count).reversed())
                .limit(50)
                .forEach(c -> m.errors.add(c.toModel(totalSeries, bucketMs)));

        // Scalability: throughput by concurrency level + knee (FR-209) + Little's Law.
        buildScalability(m, totalSeries, bucketMs);
        return m;
    }

    private ReportModel.Stats steadyStats(TreeMap<Long, Bucket> lblSeries, Acc a, long[] steady) {
        if (lblSeries == null || steady[1] <= steady[0]) return null;
        LogHistogram h = new LogHistogram();
        long n = 0, errors = 0, bytes = 0, sentBytes = 0;
        long apdexSat = 0, apdexTol = 0, apdexN = 0;
        for (var e : lblSeries.subMap(steady[0], true, steady[1], false).entrySet()) {
            h.merge(e.getValue().hist);
            n += e.getValue().n; errors += e.getValue().errors;
            bytes += e.getValue().bytes; sentBytes += e.getValue().sentBytes;
            apdexSat += e.getValue().apdexSat; apdexTol += e.getValue().apdexTol;
            apdexN += e.getValue().apdexN;
        }
        if (n == 0) return null;
        ReportModel.Stats s = baseStats(h);
        s.n = n;
        s.errors = errors;
        s.errorRate = 100.0 * errors / n;
        double durS = Math.max(0.001, (steady[1] - steady[0]) / 1000.0);
        s.throughput = n / durS;
        s.kbRecv = bytes / 1024.0 / durS;
        s.kbSent = sentBytes / 1024.0 / durS;
        s.apdex = apdexN == 0 ? 0 : (apdexSat + apdexTol / 2.0) / apdexN;
        return s;
    }

    private ReportModel.Stats stats(Acc a, LogHistogram h) {
        ReportModel.Stats s = baseStats(h);
        s.n = a.n;
        s.errors = a.errors;
        s.errorRate = a.n == 0 ? 0 : 100.0 * a.errors / a.n;
        double durS = Math.max(0.001, (a.maxTs - a.minTs) / 1000.0);
        s.throughput = a.n / durS;
        s.kbRecv = a.bytes / 1024.0 / durS;
        s.kbSent = a.sentBytes / 1024.0 / durS;
        s.apdex = a.n == 0 ? 0 : (a.apdexSat + a.apdexTol / 2.0) / a.n;
        return s;
    }

    private ReportModel.Stats baseStats(LogHistogram h) {
        ReportModel.Stats s = new ReportModel.Stats();
        s.mean = h.mean();
        s.min = h.min();
        s.max = h.max();
        s.stdDev = h.stdDev();
        s.pct = pct(h);
        return s;
    }

    private Map<String, Long> pct(LogHistogram h) {
        Map<String, Long> p = new LinkedHashMap<>();
        p.put("50", h.quantile(0.50));
        p.put("75", h.quantile(0.75));
        p.put("90", h.quantile(0.90));
        p.put("95", h.quantile(0.95));
        p.put("99", h.quantile(0.99));
        p.put("99.9", h.quantile(0.999));
        return p;
    }

    private static long autoBucket(long runMs) {
        if (runMs <= 10 * 60_000L) return 1_000;
        if (runMs <= 2 * 3_600_000L) return 10_000;
        if (runMs <= 12 * 3_600_000L) return 60_000;
        return 300_000;
    }

    private long[] detectSteadyState(TreeMap<Long, Bucket> total, long bucketMs) {
        if (total == null || total.size() < 3) return new long[]{minTs, maxTs};
        List<Map.Entry<Long, Bucket>> rows = new ArrayList<>(total.entrySet());
        int plateau = rows.stream().mapToInt(r -> r.getValue().maxThreads).max().orElse(0);
        if (plateau <= 0) return new long[]{minTs, maxTs};
        long bestStart = minTs, bestEnd = maxTs, bestLen = -1, curStart = -1;
        for (int i = 0; i <= rows.size(); i++) {
            boolean in = i < rows.size()
                    && Math.abs(rows.get(i).getValue().maxThreads - plateau) <= Math.max(1, plateau * 0.05);
            if (in && curStart < 0) curStart = rows.get(i).getKey();
            if (!in && curStart >= 0) {
                long end = rows.get(i - 1).getKey() + bucketMs;
                if (end - curStart > bestLen) { bestLen = end - curStart; bestStart = curStart; bestEnd = end; }
                curStart = -1;
            }
        }
        return bestLen > 0 ? new long[]{bestStart, bestEnd} : new long[]{minTs, maxTs};
    }

    private void buildScalability(ReportModel m, TreeMap<Long, Bucket> total, long bucketMs) {
        if (total == null || total.isEmpty()) return;
        // Aggregate throughput and p95 per concurrency level.
        TreeMap<Integer, long[]> byThreads = new TreeMap<>(); // threads -> [samples, buckets]
        TreeMap<Integer, LogHistogram> hists = new TreeMap<>();
        for (Bucket b : total.values()) {
            if (b.maxThreads <= 0) continue;
            byThreads.computeIfAbsent(b.maxThreads, k -> new long[2]);
            long[] v = byThreads.get(b.maxThreads);
            v[0] += b.n; v[1] += 1;
            hists.computeIfAbsent(b.maxThreads, k -> new LogHistogram()).merge(b.hist);
        }
        double bucketS = bucketMs / 1000.0;
        List<double[]> curve = new ArrayList<>(); // threads, throughput, p95
        for (var e : byThreads.entrySet()) {
            double thr = e.getValue()[0] / (e.getValue()[1] * bucketS);
            curve.add(new double[]{e.getKey(), thr, hists.get(e.getKey()).quantile(0.95)});
        }
        for (double[] p : curve) {
            ReportModel.ScalePoint sp = new ReportModel.ScalePoint();
            sp.threads = (int) p[0]; sp.throughput = p[1]; sp.p95 = (long) p[2];
            m.scalabilityCurve.add(sp);
        }
        // Kneedle-style knee: max distance from the secant on the normalised curve.
        if (curve.size() >= 3) {
            double x0 = curve.get(0)[0], x1 = curve.get(curve.size() - 1)[0];
            double y0 = curve.get(0)[1], y1 = curve.get(curve.size() - 1)[1];
            double best = -1; int kneeIdx = -1;
            for (int i = 1; i < curve.size() - 1; i++) {
                double xn = (curve.get(i)[0] - x0) / Math.max(1e-9, x1 - x0);
                double yn = (curve.get(i)[1] - y0) / Math.max(1e-9, y1 - y0);
                double d = yn - xn;
                if (d > best) { best = d; kneeIdx = i; }
            }
            if (kneeIdx > 0 && best > 0.02) {
                m.kneeThreads = (int) curve.get(kneeIdx)[0];
                m.kneeThroughput = curve.get(kneeIdx)[1];
            }
        }
        // Little's Law on steady state: N ≈ X * R.
        if (m.total != null && m.steadyEndMs > m.steadyStartMs) {
            double durS = (m.steadyEndMs - m.steadyStartMs) / 1000.0;
            long n = 0; long sumElapsed = 0; double weightedThreads = 0; int rows = 0;
            for (var e : total.subMap(m.steadyStartMs, true, m.steadyEndMs, false).entrySet()) {
                n += e.getValue().n; sumElapsed += e.getValue().sumElapsed;
                weightedThreads += e.getValue().maxThreads; rows++;
            }
            if (n > 0 && rows > 0) {
                double x = n / durS;
                double r = (sumElapsed / (double) n) / 1000.0;
                double nBar = weightedThreads / rows;
                double predicted = x * r;
                m.littlesLawDeviation = nBar <= 0 ? 0 : Math.abs(predicted - nBar) / nBar;
            }
        }
        // Little's Law residual trace (conservation monitor): per-bucket N - X*R.
        // A positive residual means more threads are in the system than throughput x
        // response-time predicts -> invisible queueing (pool saturation, GC, retries).
        for (var e : total.entrySet()) {
            Bucket b = e.getValue();
            if (b.n <= 0 || b.maxThreads <= 0) continue;
            double x = b.n / bucketS;                          // throughput (req/s)
            double r = (b.sumElapsed / (double) b.n) / 1000.0; // mean response (s)
            ReportModel.LittleRow lr = new ReportModel.LittleRow();
            lr.t = e.getKey();
            lr.threads = b.maxThreads;
            lr.predicted = x * r;
            lr.residual = b.maxThreads - lr.predicted;
            m.littleLaw.add(lr);
        }
    }

    /** Error cluster with normalised signatures (FR-207). */
    static final class ErrorCluster {
        private static final Pattern NUMBERS = Pattern.compile("(?:[0-9a-fA-F]{4,}|\\d+)");
        private static final Pattern WS = Pattern.compile("\\s+");
        final String signature;
        String code = "";
        long count, firstTs = Long.MAX_VALUE, lastTs;
        final Map<String, Long> labels = new HashMap<>();

        ErrorCluster(String signature) { this.signature = signature; }

        static String signature(SampleEvent e) {
            String msg = e.failureMessage() == null || e.failureMessage().isEmpty()
                    ? e.responseMessage() : e.failureMessage();
            if (msg == null) msg = "";
            msg = NUMBERS.matcher(msg.toLowerCase()).replaceAll("#");
            msg = WS.matcher(msg).replaceAll(" ").trim();
            if (msg.length() > 120) msg = msg.substring(0, 120);
            return e.responseCode() + " | " + msg;
        }

        void hit(SampleEvent e) {
            count++;
            code = e.responseCode();
            firstTs = Math.min(firstTs, e.timestampMs());
            lastTs = Math.max(lastTs, e.timestampMs());
            labels.merge(e.label(), 1L, Long::sum);
        }

        ReportModel.ErrorRow toModel(TreeMap<Long, Bucket> totalSeries, long bucketMs) {
            ReportModel.ErrorRow r = new ReportModel.ErrorRow();
            r.signature = signature;
            r.code = code;
            r.count = count;
            r.firstTs = firstTs;
            r.lastTs = lastTs;
            labels.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5).forEach(e -> r.labels.add(e.getKey()));
            long onsetKey = totalSeries.isEmpty() ? firstTs
                    : (firstTs - totalSeries.firstKey()) / bucketMs * bucketMs + totalSeries.firstKey();
            Bucket b = totalSeries.get(onsetKey);
            if (b != null) {
                r.threadsAtOnset = b.maxThreads;
                r.throughputAtOnset = b.n / (bucketMs / 1000.0);
            }
            return r;
        }
    }
}
