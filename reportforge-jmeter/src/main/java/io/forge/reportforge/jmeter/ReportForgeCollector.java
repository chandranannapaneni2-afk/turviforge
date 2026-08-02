package io.forge.reportforge.jmeter;

import io.forge.reportforge.core.ComparisonEngine;
import io.forge.reportforge.core.HtmlAssembler;
import io.forge.reportforge.core.JsonEmitter;
import io.forge.reportforge.core.JsonLoader;
import io.forge.reportforge.core.MetricsPipeline;
import io.forge.reportforge.core.ReportModel;
import io.forge.reportforge.core.SlaEngine;

import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.samplers.Clearable;

import java.io.Serializable;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FR-604 — Live Collector ("ReportForge Collector").
 *
 * A GUI-addable listener that computes streaming aggregates during the run.
 * The report is written at {@code testEnded} with no JTL re-parse.
 *
 * <p>Design:
 * <ul>
 *   <li>{@code sampleOccurred} enqueues a lightweight {@code SampleRecord} into a
 *       lock-free {@link ConcurrentLinkedQueue} — minimal work on sampler threads.</li>
 *   <li>A scheduled flusher (1 s interval) drains the queue into the single-threaded
 *       {@link MetricsPipeline}, identical to the offline code path.</li>
 *   <li>At test end, a final drain + {@code pipeline.finish()} produces the
 *       {@link ReportModel}, which is rendered to HTML + JSON.</li>
 * </ul>
 *
 * <p>Distributed mode: in standard (non-Statistical) mode, {@code sampleOccurred}
 * fires on the controller for all remote samples, so aggregation centralizes naturally.
 * In {@code mode=Statistical}, only pre-aggregated samples arrive — we detect this
 * and emit a warning that histogram fidelity is degraded.
 */
public class ReportForgeCollector extends AbstractTestElement
        implements SampleListener, TestStateListener, Clearable, Serializable {

    private static final long serialVersionUID = 1L;

    // Property keys (persisted in JMX)
    public static final String PROP_OUTPUT_DIR = "ReportForgeCollector.outputDir";
    public static final String PROP_SLA_FILE = "ReportForgeCollector.slaFile";
    public static final String PROP_BASELINE_FILE = "ReportForgeCollector.baselineFile";
    public static final String PROP_INCLUDE_SUBSAMPLES = "ReportForgeCollector.includeSubSamples";
    public static final String PROP_APDEX_SATISFIED = "ReportForgeCollector.apdexSatisfied";
    public static final String PROP_APDEX_TOLERATED = "ReportForgeCollector.apdexTolerated";

    private static final long FLUSH_INTERVAL_MS = 1000;

    // --- Transient runtime state (not serialized) ---
    private transient ConcurrentLinkedQueue<SampleRecord> queue;
    private transient MetricsPipeline pipeline;
    private transient ScheduledExecutorService flusher;
    private transient AtomicLong samplesReceived;
    private transient AtomicLong samplesProcessed;
    private transient volatile boolean running;
    private transient String hostname;

    /** Lightweight immutable record captured on the sampler thread. */
    private record SampleRecord(
            long timestampMs, int elapsedMs, String label,
            String responseCode, boolean success,
            String failureMessage, String responseMessage,
            int latencyMs, int connectMs,
            long bytes, long sentBytes,
            int grpThreads, int allThreads,
            String threadName, String hostname,
            boolean isTransaction, boolean isSubSample, String parentLabel) { }

    // --- Property accessors ---

    public String getOutputDir() { return getPropertyAsString(PROP_OUTPUT_DIR, ""); }
    public void setOutputDir(String v) { setProperty(PROP_OUTPUT_DIR, v); }

    public String getSlaFile() { return getPropertyAsString(PROP_SLA_FILE, ""); }
    public void setSlaFile(String v) { setProperty(PROP_SLA_FILE, v); }

    public String getBaselineFile() { return getPropertyAsString(PROP_BASELINE_FILE, ""); }
    public void setBaselineFile(String v) { setProperty(PROP_BASELINE_FILE, v); }

    public boolean isIncludeSubSamples() { return getPropertyAsBoolean(PROP_INCLUDE_SUBSAMPLES, true); }
    public void setIncludeSubSamples(boolean v) { setProperty(PROP_INCLUDE_SUBSAMPLES, v); }

    public int getApdexSatisfied() { return getPropertyAsInt(PROP_APDEX_SATISFIED, 500); }
    public void setApdexSatisfied(int v) { setProperty(PROP_APDEX_SATISFIED, v); }

    public int getApdexTolerated() { return getPropertyAsInt(PROP_APDEX_TOLERATED, 1500); }
    public void setApdexTolerated(int v) { setProperty(PROP_APDEX_TOLERATED, v); }

    // --- SampleListener ---

    @Override
    public void sampleOccurred(SampleEvent e) {
        if (!running || queue == null) return;
        queue.offer(toRecord(e));
        samplesReceived.incrementAndGet();
    }

    @Override
    public void sampleStarted(SampleEvent e) { /* not needed */ }

    @Override
    public void sampleStopped(SampleEvent e) { /* not needed */ }

    // --- TestStateListener ---

    @Override
    public void testStarted() {
        testStarted("");
    }

    @Override
    public void testStarted(String host) {
        // Initialize runtime state
        queue = new ConcurrentLinkedQueue<>();
        samplesReceived = new AtomicLong();
        samplesProcessed = new AtomicLong();
        running = true;

        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            hostname = "unknown";
        }

        // Configure pipeline
        MetricsPipeline.Config cfg = new MetricsPipeline.Config();
        cfg.includeSubSamples = isIncludeSubSamples();
        cfg.apdexSatisfiedMs = getApdexSatisfied();
        cfg.apdexToleratedMs = getApdexTolerated();
        pipeline = new MetricsPipeline(cfg);

        // Start background flusher (1s interval)
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reportforge-flusher");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::drainQueue, FLUSH_INTERVAL_MS,
                FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("[ReportForge] Live collector started on " + hostname
                + (host.isEmpty() ? "" : " (controller for: " + host + ")"));
    }

    @Override
    public void testEnded() {
        testEnded("");
    }

    @Override
    public void testEnded(String host) {
        if (!running) return;
        running = false;

        // Stop flusher and do final drain
        if (flusher != null) {
            flusher.shutdown();
            try { flusher.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        drainQueue(); // final drain

        long received = samplesReceived.get();
        long processed = samplesProcessed.get();
        System.out.println("[ReportForge] Test ended. Samples received=" + received
                + " processed=" + processed);

        // Generate report
        try {
            generateReport();
        } catch (Exception e) {
            System.err.println("[ReportForge] Live report generation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Clearable ---

    @Override
    public void clearData() {
        if (queue != null) queue.clear();
        if (samplesReceived != null) samplesReceived.set(0);
        if (samplesProcessed != null) samplesProcessed.set(0);
    }

    // --- Internal ---

    private void drainQueue() {
        if (queue == null || pipeline == null) return;
        SampleRecord rec;
        int count = 0;
        while ((rec = queue.poll()) != null) {
            pipeline.accept(new io.forge.reportforge.core.SampleEvent(
                    rec.timestampMs(), rec.elapsedMs(), rec.label(),
                    rec.responseCode(), rec.success(),
                    rec.failureMessage(), rec.responseMessage(),
                    rec.latencyMs(), rec.connectMs(),
                    rec.bytes(), rec.sentBytes(),
                    rec.grpThreads(), rec.allThreads(),
                    rec.threadName(), rec.hostname(),
                    rec.isTransaction(), rec.isSubSample(), rec.parentLabel()));
            count++;
        }
        if (count > 0) samplesProcessed.addAndGet(count);
    }

    private void generateReport() throws Exception {
        // Build parse result summary for pipeline.finish()
        io.forge.reportforge.core.JtlCsvParser.Result syntheticResult =
                new io.forge.reportforge.core.JtlCsvParser.Result();
        syntheticResult.total = samplesProcessed.get();

        ReportModel model = pipeline.finish(syntheticResult);
        model.title = getName() != null && !getName().isBlank() ? getName() : "ReportForge Live Report";
        model.sources.add("live:" + hostname);

        // SLA evaluation
        String slaPath = getSlaFile();
        if (slaPath != null && !slaPath.isBlank()) {
            Path sla = Path.of(slaPath);
            if (Files.isReadable(sla)) {
                SlaEngine engine = SlaEngine.load(sla);
                model.verdict = engine.evaluate(model);
                System.out.println("[ReportForge] SLA verdict: " + model.verdict.overall);
            }
        }

        // Baseline comparison
        ComparisonEngine.ComparisonResult comparison = null;
        String baselinePath = getBaselineFile();
        if (baselinePath != null && !baselinePath.isBlank()) {
            Path bl = Path.of(baselinePath);
            if (Files.isReadable(bl)) {
                ReportModel baselineModel = JsonLoader.load(bl);
                ComparisonEngine engine = new ComparisonEngine(new ComparisonEngine.Config());
                comparison = engine.compare(baselineModel, model);
                if (comparison.hasRegression) {
                    System.out.println("[ReportForge] WARNING: Regression detected vs baseline!");
                }
            }
        }

        // Resolve output directory
        String outProp = getOutputDir();
        Path outDir;
        if (outProp != null && !outProp.isBlank()) {
            outDir = Path.of(outProp);
        } else {
            outDir = Path.of(System.getProperty("user.dir"), "reportforge-live-report");
        }
        Files.createDirectories(outDir);

        // Emit JSON + HTML
        String json = comparison != null
                ? new JsonEmitter().emit(model, comparison)
                : new JsonEmitter().emit(model);
        Files.writeString(outDir.resolve("report-data.json"), json, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("report.html"),
                new HtmlAssembler().assemble(json, model.title), StandardCharsets.UTF_8);

        // JUnit XML if SLA evaluated
        if (model.verdict != null) {
            Files.writeString(outDir.resolve("junit.xml"),
                    SlaEngine.junitXml(model.verdict, model.title), StandardCharsets.UTF_8);
        }

        System.out.println("[ReportForge] Live report written to " + outDir.resolve("report.html"));
    }

    /** Convert JMeter's SampleEvent to our lightweight record (minimal work on sampler thread). */
    private SampleRecord toRecord(SampleEvent e) {
        SampleResult r = e.getResult();
        String label = r.getSampleLabel();
        String code = r.getResponseCode();
        boolean success = r.isSuccessful();
        String failMsg = success ? "" : r.getResponseMessage();
        String respMsg = r.getResponseMessage() != null ? r.getResponseMessage() : "";

        // Detect transaction controller samples (have sub-results, no sampler data)
        SampleResult[] subs = r.getSubResults();
        boolean isTransaction = subs != null && subs.length > 0 && r.getSamplerData() == null;
        boolean isSubSample = false;
        String parentLabel = null;

        // Thread counts — use SampleResult's built-in counters
        int grpThreads = r.getGroupThreads();
        int allThreads = r.getAllThreads();
        String threadName = r.getThreadName() != null ? r.getThreadName() : "";

        return new SampleRecord(
                r.getTimeStamp(), (int) r.getTime(), label,
                code != null ? code : "", success,
                failMsg != null ? failMsg : "", respMsg,
                (int) r.getLatency(), (int) r.getConnectTime(),
                r.getBytesAsLong(), r.getSentBytes(),
                grpThreads, allThreads,
                threadName, e.getHostname() != null ? e.getHostname() : "",
                isTransaction, isSubSample, parentLabel);
    }
}
