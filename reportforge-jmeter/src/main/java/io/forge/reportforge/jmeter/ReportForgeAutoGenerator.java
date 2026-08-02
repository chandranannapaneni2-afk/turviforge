package io.forge.reportforge.jmeter;

import io.forge.reportforge.core.HtmlAssembler;
import io.forge.reportforge.core.JsonEmitter;
import io.forge.reportforge.core.JtlCsvParser;
import io.forge.reportforge.core.JtlMerger;
import io.forge.reportforge.core.MetricsPipeline;
import io.forge.reportforge.core.ReportModel;
import io.forge.reportforge.core.SlaEngine;

import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.util.JMeterUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Non-GUI auto-generation hook (FR-603).
 * Activated by setting the JMeter property {@code reportforge.autogenerate=true}.
 * On test end, resolves the result file from standard JMeter configuration and
 * generates the ReportForge report without requiring a test-plan element.
 */
public class ReportForgeAutoGenerator implements TestStateListener {

    private static final String PROP_ENABLED = "reportforge.autogenerate";
    private static final String PROP_SLA = "reportforge.sla";
    private static final String PROP_OUT = "reportforge.outdir";

    @Override
    public void testStarted() {
        // no-op
    }

    @Override
    public void testStarted(String host) {
        // no-op
    }

    @Override
    public void testEnded() {
        if (!isEnabled()) return;
        try {
            generateReport();
        } catch (Exception e) {
            System.err.println("[ReportForge] auto-generation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void testEnded(String host) {
        // In distributed mode, only generate on the controller (no host suffix)
        if (host == null || host.isEmpty()) {
            testEnded();
        }
    }

    private boolean isEnabled() {
        return "true".equalsIgnoreCase(JMeterUtils.getProperty(PROP_ENABLED));
    }

    private void generateReport() throws Exception {
        // Resolve result file from standard JMeter property
        String resultFile = JMeterUtils.getProperty("jmeter.save.saveservice.output_format");
        String jtlPath = JMeterUtils.getProperty("resultcollector.filename");
        if (jtlPath == null || jtlPath.isBlank()) {
            // Fallback: check -l command-line result
            jtlPath = System.getProperty("jmeter.result");
        }
        if (jtlPath == null || jtlPath.isBlank()) {
            System.err.println("[ReportForge] cannot determine result file; set 'resultcollector.filename' property");
            return;
        }

        Path jtl = Path.of(jtlPath);
        if (!Files.isReadable(jtl)) {
            System.err.println("[ReportForge] result file not readable: " + jtl);
            return;
        }

        // Output directory
        String outProp = JMeterUtils.getProperty(PROP_OUT);
        Path outDir = outProp != null && !outProp.isBlank()
                ? Path.of(outProp)
                : jtl.getParent().resolve("reportforge-report");

        System.out.println("[ReportForge] auto-generating report from " + jtl + " -> " + outDir);

        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        MetricsPipeline pipeline = new MetricsPipeline(cfg);
        JtlMerger merger = new JtlMerger();
        merger.merge(List.of(jtl), pipeline::accept);

        JtlCsvParser.Result syntheticParse = new JtlCsvParser.Result();
        ReportModel model = pipeline.finish(syntheticParse);
        model.title = jtl.getFileName().toString();
        model.sources.add(jtl.getFileName().toString());

        // Optional SLA evaluation
        String slaProp = JMeterUtils.getProperty(PROP_SLA);
        if (slaProp != null && !slaProp.isBlank()) {
            Path slaPath = Path.of(slaProp);
            if (Files.isReadable(slaPath)) {
                SlaEngine engine = SlaEngine.load(slaPath);
                model.verdict = engine.evaluate(model);
                System.out.println("[ReportForge] SLA verdict: " + model.verdict.overall);
            }
        }

        Files.createDirectories(outDir);
        String json = new JsonEmitter().emit(model);
        Files.writeString(outDir.resolve("report-data.json"), json, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("report.html"),
                new HtmlAssembler().assemble(json, model.title), StandardCharsets.UTF_8);
        if (model.verdict != null) {
            Files.writeString(outDir.resolve("junit.xml"),
                    SlaEngine.junitXml(model.verdict, model.title), StandardCharsets.UTF_8);
        }
        System.out.println("[ReportForge] report written to " + outDir.resolve("report.html"));
    }
}
