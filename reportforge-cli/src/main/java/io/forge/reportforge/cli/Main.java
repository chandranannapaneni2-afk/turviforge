package io.forge.reportforge.cli;

import io.forge.reportforge.core.ComparisonEngine;
import io.forge.reportforge.core.HtmlAssembler;
import io.forge.reportforge.core.JsonEmitter;
import io.forge.reportforge.core.JsonLoader;
import io.forge.reportforge.core.JtlCsvParser;
import io.forge.reportforge.core.JtlMerger;
import io.forge.reportforge.core.JtlXmlParser;
import io.forge.reportforge.core.MetricsPipeline;
import io.forge.reportforge.core.ReportModel;
import io.forge.reportforge.core.SlaEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ReportForge CLI (FR-602/603).
 * Commands: generate, compare, validate.
 * Exit codes (frozen, FR-403): 0 PASS · 1 FAIL/DEGRADED-with-fail-on · 2 usage · 3 input · 4 internal.
 */
public final class Main {

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (UsageException e) {
            System.err.println("usage error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
        } catch (InputException e) {
            System.err.println("input error: " + e.getMessage());
            System.exit(3);
        } catch (Exception e) {
            System.err.println("internal error: " + e);
            e.printStackTrace();
            System.exit(4);
        }
    }

    static final String USAGE = """
            ReportForge — advanced JMeter report generator

            Usage:
              reportforge generate --jtl <file[,file2,...]> --out <dir> [options]
              reportforge compare --baseline <report-data.json> --candidate <report-data.json> --out <dir>
              reportforge validate --sla <sla.yaml>
              reportforge sla --jtl <file> --out <sla.yaml> [--headroom <pct>]

            Generate options:
              --jtl <file[,file2,...]>  JTL file(s); multiple = distributed merge (FR-108)
              --out <dir>               Output directory
              --sla <sla.yaml>          Evaluate SLA rules; verdict drives exit code
              --baseline <json>         Compare against a baseline report-data.json
              --junit                   Also write junit.xml (one testcase per SLA assertion)
              --title <text>            Report title
              --meta k=v                Extra metadata (repeatable): build, env, git SHA...
              --apdex-satisfied <ms>    APDEX satisfied threshold (default 500)
              --apdex-tolerated <ms>    APDEX tolerated threshold (default 1500)
              --bucket <ms>             Time-series bucket size in ms (default: auto)
              --label-cap <n>           Max labels before __OTHER__ rollup (default 2000)
              --fail-on fail|degraded   When to exit non-zero (default: fail)
              --include-subsamples      Include sub-samples in statistics (default: true)
              --exclude-subsamples      Exclude sub-samples from statistics
              --steady-state-only       Evaluate SLAs on steady-state window only

            Compare options:
              --baseline <json>         Baseline report-data.json
              --candidate <json>        Candidate report-data.json
              --out <dir>               Output directory for comparison report

            Validate options:
              --sla <sla.yaml>          Validate SLA file syntax and print parsed rules

            SLA init options (auto-generate SLA from a JTL run):
              --jtl <file>              JTL file to analyze
              --out <sla.yaml>          Output SLA file path
              --headroom <pct>          Headroom %% above observed p95 (default 50)
            """;

    static int run(String[] args) throws Exception {
        if (args.length == 0) throw new UsageException("expected command: generate | compare | validate | sla");
        return switch (args[0]) {
            case "generate" -> generate(args);
            case "compare" -> compare(args);
            case "validate" -> validate(args);
            case "sla" -> slaInit(args);
            default -> throw new UsageException("unknown command: " + args[0] + " (expected generate|compare|validate|sla)");
        };
    }

    /* --------------------------------------------------------- generate -- */

    static int generate(String[] args) throws Exception {
        List<Path> jtls = new ArrayList<>();
        Path out = null, sla = null, baseline = null;
        boolean junit = false, failOnDegraded = false;
        String title = "Performance Test Report";
        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        List<String[]> metas = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--jtl" -> { for (String p : need(args, ++i).split(",")) jtls.add(Path.of(p.trim())); }
                case "--out" -> out = Path.of(need(args, ++i));
                case "--sla" -> sla = Path.of(need(args, ++i));
                case "--baseline" -> baseline = Path.of(need(args, ++i));
                case "--junit" -> junit = true;
                case "--title" -> title = need(args, ++i);
                case "--meta" -> { String[] kv = need(args, ++i).split("=", 2); if (kv.length == 2) metas.add(kv); }
                case "--apdex-satisfied" -> cfg.apdexSatisfiedMs = Integer.parseInt(need(args, ++i));
                case "--apdex-tolerated" -> cfg.apdexToleratedMs = Integer.parseInt(need(args, ++i));
                case "--bucket" -> cfg.bucketMs = Long.parseLong(need(args, ++i));
                case "--label-cap" -> cfg.labelCap = Integer.parseInt(need(args, ++i));
                case "--fail-on" -> failOnDegraded = need(args, ++i).equalsIgnoreCase("degraded");
                case "--include-subsamples" -> cfg.includeSubSamples = true;
                case "--exclude-subsamples" -> cfg.includeSubSamples = false;
                case "--steady-state-only" -> { /* handled by SLA engine default */ }
                default -> throw new UsageException("unknown option " + args[i]);
            }
        }
        if (jtls.isEmpty()) throw new UsageException("--jtl is required");
        if (out == null) throw new UsageException("--out is required");
        for (Path p : jtls) if (!Files.isReadable(p)) throw new InputException("cannot read " + p);

        long t0 = System.nanoTime();
        MetricsPipeline pipeline = new MetricsPipeline(cfg);

        // Use merger for multi-file (k-way merge, FR-108), single-file auto-detects CSV/XML
        JtlMerger merger = new JtlMerger();
        JtlMerger.MergeResult mergeResult = merger.merge(jtls, pipeline::accept);
        long total = mergeResult.total;

        // Build a synthetic parse result for pipeline.finish()
        JtlCsvParser.Result syntheticParse = new JtlCsvParser.Result();
        syntheticParse.total = mergeResult.total;
        syntheticParse.malformed = mergeResult.malformed;

        ReportModel model = pipeline.finish(syntheticParse);
        model.title = title;
        mergeResult.sources.forEach(s -> model.sources.add(s));
        metas.forEach(kv -> model.extras.put(kv[0], kv[1]));

        int exit = 0;
        if (sla != null) {
            SlaEngine engine = SlaEngine.load(sla);
            // Apply APDEX defaults from SLA file if not overridden on CLI
            boolean apdexOverridden = false;
            for (String a : args) if (a.equals("--apdex-satisfied") || a.equals("--apdex-tolerated")) apdexOverridden = true;
            if (!apdexOverridden) {
                cfg.apdexSatisfiedMs = engine.getDefaults().apdexSatisfiedMs;
                cfg.apdexToleratedMs = engine.getDefaults().apdexToleratedMs;
            }
            model.verdict = engine.evaluate(model);
            System.out.println("[reportforge] SLA verdict: " + model.verdict.overall);
            if (model.verdict.overall == SlaEngine.Status.FAIL) exit = 1;
            if (failOnDegraded && model.verdict.overall == SlaEngine.Status.DEGRADED) exit = 1;
        }

        // Comparison against baseline (FR-501..504)
        ComparisonEngine.ComparisonResult comparison = null;
        if (baseline != null) {
            if (!Files.isReadable(baseline)) throw new InputException("cannot read baseline " + baseline);
            ReportModel baseModel = JsonLoader.load(baseline);
            ComparisonEngine compEngine = new ComparisonEngine(new ComparisonEngine.Config());
            comparison = compEngine.compare(baseModel, model);
            System.out.println("[reportforge] comparison: " +
                    (comparison.hasRegression ? "REGRESSION DETECTED" : "no significant regression"));
            if (comparison.hasRegression && exit == 0) exit = 1;
        }

        Files.createDirectories(out);
        String json = new JsonEmitter().emit(model, comparison);
        Files.writeString(out.resolve("report-data.json"), json, StandardCharsets.UTF_8);
        String html = new HtmlAssembler().assemble(json, title);
        Files.writeString(out.resolve("report.html"), html, StandardCharsets.UTF_8);
        if (junit && model.verdict != null) {
            Files.writeString(out.resolve("junit.xml"),
                    SlaEngine.junitXml(model.verdict, title), StandardCharsets.UTF_8);
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[reportforge] %,d samples -> %s (%d ms)%n", total, out.resolve("report.html"), ms);
        return exit;
    }

    /* ---------------------------------------------------------- compare -- */

    static int compare(String[] args) throws Exception {
        Path baselinePath = null, candidatePath = null, out = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--baseline" -> baselinePath = Path.of(need(args, ++i));
                case "--candidate" -> candidatePath = Path.of(need(args, ++i));
                case "--out" -> out = Path.of(need(args, ++i));
                default -> throw new UsageException("unknown option for compare: " + args[i]);
            }
        }
        if (baselinePath == null) throw new UsageException("compare requires --baseline");
        if (candidatePath == null) throw new UsageException("compare requires --candidate");
        if (out == null) throw new UsageException("compare requires --out");
        if (!Files.isReadable(baselinePath)) throw new InputException("cannot read " + baselinePath);
        if (!Files.isReadable(candidatePath)) throw new InputException("cannot read " + candidatePath);

        ReportModel baseModel = JsonLoader.load(baselinePath);
        ReportModel candModel = JsonLoader.load(candidatePath);
        ComparisonEngine engine = new ComparisonEngine(new ComparisonEngine.Config());
        ComparisonEngine.ComparisonResult result = engine.compare(baseModel, candModel);

        Files.createDirectories(out);
        // Emit comparison as JSON
        String json = new JsonEmitter().emit(candModel, result);
        Files.writeString(out.resolve("report-data.json"), json, StandardCharsets.UTF_8);
        String html = new HtmlAssembler().assemble(json, "Comparison: " + candModel.title);
        Files.writeString(out.resolve("report.html"), html, StandardCharsets.UTF_8);

        System.out.println("[reportforge] comparison complete: " +
                (result.hasRegression ? "REGRESSION" : "OK") + " -> " + out.resolve("report.html"));
        return result.hasRegression ? 1 : 0;
    }

    /* --------------------------------------------------------- validate -- */

    static int validate(String[] args) throws Exception {
        Path sla = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--sla")) sla = Path.of(need(args, ++i));
            else throw new UsageException("unknown option for validate: " + args[i]);
        }
        if (sla == null) throw new UsageException("validate requires --sla");
        if (!Files.isReadable(sla)) throw new InputException("cannot read " + sla);

        SlaEngine engine = SlaEngine.load(sla);
        System.out.println("[reportforge] SLA file valid: " + sla);
        System.out.println("  window: " + (engine.getDefaults().steadyState ? "steady-state" : "full-run"));
        System.out.println("  apdex:  satisfied=" + engine.getDefaults().apdexSatisfiedMs +
                "ms tolerated=" + engine.getDefaults().apdexToleratedMs + "ms");
        System.out.println("  rules:  " + engine.getRules().size());
        for (SlaEngine.Rule r : engine.getRules()) {
            System.out.println("    - match: \"" + r.match + "\" (" + r.assertions.size() + " assertions)");
            for (SlaEngine.Assertion a : r.assertions) {
                System.out.println("        " + a.metric + " " + a.op + " " + a.value +
                        (a.warn != null ? " (warn: " + a.warn + ")" : ""));
            }
        }
        return 0;
    }

    /* --------------------------------------------------------- sla init -- */

    static int slaInit(String[] args) throws Exception {
        Path jtl = null, out = null;
        int headroomPct = 50;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--jtl" -> jtl = Path.of(need(args, ++i));
                case "--out" -> out = Path.of(need(args, ++i));
                case "--headroom" -> headroomPct = Integer.parseInt(need(args, ++i));
                default -> throw new UsageException("unknown option for sla: " + args[i]);
            }
        }
        if (jtl == null) throw new UsageException("sla requires --jtl");
        if (out == null) throw new UsageException("sla requires --out");
        if (!Files.isReadable(jtl)) throw new InputException("cannot read " + jtl);

        // Parse JTL and compute metrics
        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        MetricsPipeline pipeline = new MetricsPipeline(cfg);
        JtlMerger merger = new JtlMerger();
        merger.merge(List.of(jtl), pipeline::accept);
        JtlCsvParser.Result syntheticParse = new JtlCsvParser.Result();
        ReportModel model = pipeline.finish(syntheticParse);

        double factor = 1.0 + headroomPct / 100.0;
        double warnFactor = 1.0 + (headroomPct * 0.4) / 100.0; // warn at 40% of headroom

        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated by ReportForge from: ").append(jtl.getFileName()).append("\n");
        sb.append("# Headroom: ").append(headroomPct).append("% above observed p95\n");
        sb.append("# Review and adjust thresholds before using in CI.\n");
        sb.append("version: 1\n\n");

        // Defaults from overall stats
        long totalP50 = model.total.stats.pct.getOrDefault("50", 500L);
        long totalP90 = model.total.stats.pct.getOrDefault("90", 1500L);
        sb.append("defaults:\n");
        sb.append("  window: full-run\n");
        sb.append("  apdex: { satisfied_ms: ").append(roundUp(totalP50, 50))
          .append(", tolerated_ms: ").append(roundUp(totalP90, 100)).append(" }\n\n");

        sb.append("rules:\n");

        // Per-label rules
        for (ReportModel.LabelStats ls : model.labels) {
            long p95 = ls.stats.pct.getOrDefault("95", 0L);
            double errRate = ls.stats.errorRate;
            long threshold = roundUp((long) (p95 * factor), 50);
            long warn = roundUp((long) (p95 * warnFactor), 50);
            double errThreshold = Math.max(0.5, Math.ceil((errRate + 0.5) * 10) / 10.0);

            sb.append("  - match: \"").append(escapeYaml(ls.name)).append("\"\n");
            sb.append("    assert:\n");
            sb.append("      - { metric: p95, op: \"<\", value: ").append(threshold)
              .append(", warn: ").append(warn).append(" }\n");
            sb.append("      - { metric: error_rate, op: \"<\", value: ").append(fmtVal(errThreshold)).append(" }\n");
        }

        // TOTAL rule
        long totalP95 = model.total.stats.pct.getOrDefault("95", 0L);
        double totalThroughput = model.total.stats.throughput;
        long totalThreshold = roundUp((long) (totalP95 * factor), 50);
        long totalWarn = roundUp((long) (totalP95 * warnFactor), 50);
        double throughputFloor = Math.max(0.1, Math.floor(totalThroughput * 0.8 * 10) / 10.0);

        sb.append("  - match: \"TOTAL\"\n");
        sb.append("    assert:\n");
        sb.append("      - { metric: p95, op: \"<\", value: ").append(totalThreshold)
          .append(", warn: ").append(totalWarn).append(" }\n");
        sb.append("      - { metric: error_rate, op: \"<\", value: ")
          .append(fmtVal(Math.max(1.0, model.total.stats.errorRate + 0.5))).append(" }\n");
        sb.append("      - { metric: throughput, op: \">=\", value: ").append(fmtVal(throughputFloor)).append(" }\n");

        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[reportforge] SLA written to " + out);
        System.out.println("  labels: " + model.labels.size() + " + TOTAL");
        System.out.println("  headroom: " + headroomPct + "% (p95 thresholds at " + (int)(factor * 100) + "% of observed)");
        System.out.println("  apdex: satisfied=" + roundUp(totalP50, 50) + "ms tolerated=" + roundUp(totalP90, 100) + "ms");
        return 0;
    }

    private static long roundUp(long value, long step) {
        return ((value + step - 1) / step) * step;
    }

    private static String fmtVal(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String escapeYaml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ----------------------------------------------------------- helpers -- */

    private static String need(String[] a, int i) {
        if (i >= a.length) throw new UsageException("missing value for " + a[i - 1]);
        return a[i];
    }

    static final class UsageException extends RuntimeException {
        UsageException(String m) { super(m); }
    }

    static final class InputException extends RuntimeException {
        InputException(String m) { super(m); }
    }
}
