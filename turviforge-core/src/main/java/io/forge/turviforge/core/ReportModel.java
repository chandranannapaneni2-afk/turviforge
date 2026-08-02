package io.forge.turviforge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory report model — 1:1 with report-data.json (schema turviforge/1). */
public final class ReportModel {

    public String title = "TurviForge Report";
    public final Map<String, String> extras = new LinkedHashMap<>();
    public final List<String> sources = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public long startMs, endMs, steadyStartMs, steadyEndMs, bucketMs;

    public LabelStats total;
    public final List<LabelStats> labels = new ArrayList<>();
    public final List<SeriesRow> series = new ArrayList<>();
    public final List<ErrorRow> errors = new ArrayList<>();
    public final List<ScalePoint> scalabilityCurve = new ArrayList<>();
    public int kneeThreads = -1;
    public double kneeThroughput = -1;
    public double littlesLawDeviation = -1;
    public final List<LittleRow> littleLaw = new ArrayList<>();

    public SlaEngine.Verdict verdict;   // set by SLA engine, may stay null

    public static final class Stats {
        public long n, errors, min, max;
        public double errorRate, mean, stdDev, throughput, kbRecv, kbSent, apdex;
        public Map<String, Long> pct = new LinkedHashMap<>();
    }

    public static final class LabelStats {
        public String name;
        public Stats stats;
        public Stats steadyStats;              // nullable
        public Map<String, Long> latencyPct;   // nullable
        public Map<String, Long> connectPct;   // nullable
        public String histogramB64;
        public final List<SlowSample> topSlowest = new ArrayList<>();
    }

    public record SlowSample(long ts, int elapsed, String code, String thread) { }

    public static final class SeriesCell {
        public long n, err, p95, max;
        public double mean, kb, kbSent;
    }

    public static final class SeriesRow {
        public long t;
        public int threads;
        public final Map<String, SeriesCell> perLabel = new LinkedHashMap<>();
    }

    public static final class ErrorRow {
        public String signature, code;
        public long count, firstTs, lastTs;
        public final List<String> labels = new ArrayList<>();
        public int threadsAtOnset;
        public double throughputAtOnset;
    }

    public static final class ScalePoint {
        public int threads;
        public double throughput;
        public long p95;
    }

    /** Per-bucket Little's Law residual: N (threads) vs X·R (predicted concurrency). */
    public static final class LittleRow {
        public long t;
        public int threads;      // actual concurrency N
        public double predicted;  // X * R (throughput x mean response time)
        public double residual;   // N - X*R; positive => invisible queueing
    }
}
