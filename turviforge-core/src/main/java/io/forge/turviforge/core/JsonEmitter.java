package io.forge.turviforge.core;

import java.time.Instant;
import java.util.Map;

/** Serialises ReportModel to the canonical report-data.json (schema turviforge/1, TechSpec §6). */
public final class JsonEmitter {

    public static final String VERSION = "0.5.0";

    /** Emit without comparison data. */
    public String emit(ReportModel m) {
        return emit(m, null);
    }

    /** Emit with optional comparison result (M5). */
    public String emit(ReportModel m, ComparisonEngine.ComparisonResult comparison) {
        Json j = new Json();
        j.obj();
        j.kv("schema", "turviforge/1");

        j.key("meta").obj()
                .kv("title", m.title)
                .kv("generatedAt", Instant.now().toString())
                .kv("tool", "turviforge " + VERSION);
        j.key("source").arr();
        m.sources.forEach(j::val);
        j.endArr();
        j.key("extras").obj();
        m.extras.forEach(j::kv);
        j.endObj();
        j.key("warnings").arr();
        m.warnings.forEach(j::val);
        j.endArr();
        j.key("window").obj()
                .kv("startMs", m.startMs).kv("endMs", m.endMs)
                .key("steadyState").obj()
                .kv("startMs", m.steadyStartMs).kv("endMs", m.steadyEndMs)
                .endObj().endObj();
        j.endObj(); // meta

        j.key("labels").arr();
        for (ReportModel.LabelStats ls : m.labels) label(j, ls);
        j.endArr();

        j.key("total");
        if (m.total != null) label(j, m.total); else j.nul();

        j.key("series").obj().kv("bucketMs", m.bucketMs).key("buckets").arr();
        for (ReportModel.SeriesRow r : m.series) {
            j.obj().kv("t", r.t).kv("threads", r.threads).key("perLabel").obj();
            for (var e : r.perLabel.entrySet()) {
                ReportModel.SeriesCell c = e.getValue();
                j.key(e.getKey()).obj()
                        .kv("n", c.n).kv("err", c.err).kv("mean", c.mean)
                        .kv("p95", c.p95).kv("max", c.max).kv("kb", c.kb)
                        .kv("kbSent", c.kbSent)
                        .endObj();
            }
            j.endObj().endObj();
        }
        j.endArr().endObj();

        j.key("errors").arr();
        for (ReportModel.ErrorRow e : m.errors) {
            j.obj().kv("signature", e.signature).kv("code", e.code).kv("count", e.count)
                    .kv("firstTs", e.firstTs).kv("lastTs", e.lastTs);
            j.key("labels").arr();
            e.labels.forEach(j::val);
            j.endArr();
            j.kv("threadsAtOnset", e.threadsAtOnset)
                    .kv("throughputAtOnset", e.throughputAtOnset)
                    .endObj();
        }
        j.endArr();

        j.key("scalability").obj().key("curve").arr();
        for (ReportModel.ScalePoint p : m.scalabilityCurve) {
            j.obj().kv("threads", p.threads).kv("throughput", p.throughput).kv("p95", p.p95).endObj();
        }
        j.endArr();
        j.key("knee");
        if (m.kneeThreads > 0) {
            j.obj().kv("threads", m.kneeThreads).kv("throughput", m.kneeThroughput).endObj();
        } else j.nul();
        j.kv("littlesLawDeviation", m.littlesLawDeviation);
        j.endObj();

        j.key("sla");
        if (m.verdict != null) {
            j.obj().kv("verdict", m.verdict.overall.name()).key("rules").arr();
            for (SlaEngine.RuleResult r : m.verdict.results) {
                j.obj().kv("label", r.label).kv("metric", r.metric).kv("op", r.op)
                        .kv("threshold", r.threshold).kv("warn", r.warn)
                        .kv("actual", r.actual).kv("status", r.status.name()).endObj();
            }
            j.endArr().endObj();
        } else j.nul();

        j.key("comparison");
        if (comparison != null) {
            j.obj();
            j.key("baselineMeta").obj()
                    .kv("title", comparison.baselineTitle != null ? comparison.baselineTitle : "")
                    .endObj();
            j.kv("hasRegression", comparison.hasRegression);
            j.key("labels").arr();
            for (ComparisonEngine.LabelDelta d : comparison.labels) {
                j.obj().kv("label", d.label)
                        .kv("baseN", d.baseN).kv("candN", d.candN)
                        .kv("baseThroughput", d.baseThroughput).kv("candThroughput", d.candThroughput)
                        .kv("throughputDeltaPct", d.throughputDeltaPct)
                        .kv("baseErrorRate", d.baseErrorRate).kv("candErrorRate", d.candErrorRate)
                        .kv("errorRateDeltaPp", d.errorRateDeltaPp)
                        .kv("baseMean", d.baseMean).kv("candMean", d.candMean)
                        .kv("meanDeltaPct", d.meanDeltaPct)
                        .kv("baseP95", d.baseP95).kv("candP95", d.candP95)
                        .kv("p95DeltaPct", d.p95DeltaPct)
                        .kv("baseApdex", d.baseApdex).kv("candApdex", d.candApdex)
                        .kv("pValue", d.pValue).kv("cliffsDelta", d.cliffsDelta)
                        .kv("significant", d.significant).kv("regression", d.regression)
                        .endObj();
            }
            j.endArr().endObj();
        } else j.nul();
        j.endObj();
        return j.toString();
    }

    private void label(Json j, ReportModel.LabelStats ls) {
        j.obj().kv("name", ls.name);
        j.key("stats");
        stats(j, ls.stats);
        j.key("steadyStats");
        if (ls.steadyStats != null) stats(j, ls.steadyStats); else j.nul();
        j.key("latency");
        pctBlock(j, ls.latencyPct);
        j.key("connect");
        pctBlock(j, ls.connectPct);
        j.kv("histogram", ls.histogramB64);
        j.key("topSlowest").arr();
        for (ReportModel.SlowSample s : ls.topSlowest) {
            j.obj().kv("ts", s.ts()).kv("elapsed", s.elapsed())
                    .kv("code", s.code()).kv("thread", s.thread()).endObj();
        }
        j.endArr().endObj();
    }

    private void pctBlock(Json j, Map<String, Long> pct) {
        if (pct == null) { j.nul(); return; }
        j.obj().key("pct").obj();
        pct.forEach(j::kv);
        j.endObj().endObj();
    }

    private void stats(Json j, ReportModel.Stats s) {
        j.obj().kv("n", s.n).kv("errors", s.errors).kv("errorRate", s.errorRate)
                .kv("mean", s.mean).kv("min", s.min).kv("max", s.max)
                .kv("stdDev", s.stdDev).kv("throughput", s.throughput)
                .kv("kbRecv", s.kbRecv).kv("kbSent", s.kbSent);
        j.key("pct").obj();
        s.pct.forEach(j::kv);
        j.endObj();
        j.kv("apdex", s.apdex).endObj();
    }
}
