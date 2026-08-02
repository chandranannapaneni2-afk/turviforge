# ReportForge — Advanced JMeter Reporting Plugin
## Technical Specification (TechSpec) v1.0

| Field | Value |
|---|---|
| Document | TechSpec v1.0 |
| Status | Draft for review |
| Date | 26 July 2026 |
| Companion doc | ReportForge PRD v1.0 |
| Target platform | Java 17+, Apache JMeter 5.5–5.6.x |

---

## 1. Architecture Overview

### 1.1 Module structure (Maven multi-module)

```
reportforge/
├── reportforge-core/          # ZERO JMeter deps. Parser, metrics, SLA, comparison, JSON emit.
├── reportforge-render/        # HTML assembly: inlines the built UI bundle + report-data.json
├── reportforge-jmeter/        # JMeter adapter: MenuCreator GUI action, listener (Ph4), properties
├── reportforge-cli/           # Standalone CLI (picocli), shaded runnable JAR
├── reportforge-ui/            # TypeScript + ECharts SPA, built to a single JS/CSS bundle
├── reportforge-dist/          # Shading/assembly: lib/ext plugin JAR + standalone CLI JAR
└── reportforge-testdata/      # Golden JTL corpus + expected report-data.json fixtures
```

Dependency rule: `core` depends on nothing JMeter; `jmeter` and `cli` depend on `core` + `render`; `ui` is built by Node at build time and embedded as a classpath resource in `render`. This isolation is what lets LoadStorm/ScaleForge consume `core` directly (NFR-C2).

### 1.2 Data flow

```
JTL file(s) ──► [M1 StreamingJtlReader] ──► SampleEvent stream
                                             │
                              ┌──────────────┴───────────────┐
                              ▼ (single pass, fan-out)        ▼
                    [M2 MetricsPipeline]              [M2 ErrorClusterer]
                    per-label accumulators             signature buckets
                    HDR histograms (global +           first/last/onset
                    per time bucket), APDEX,
                    threads, throughput
                              │
                              ▼
                    ReportModel (in-memory, bounded)
                              │
             ┌────────────────┼───────────────────┬─────────────┐
             ▼                ▼                   ▼             ▼
     [M4 SlaEvaluator] [M5 Comparator]   report-data.json   JUnit XML
             │                │                   │
             └───────┬────────┘                   │
                     ▼                            ▼
               Verdict object ──────────► [M3 HtmlAssembler]
                                          UI bundle + JSON → single report.html
```

Everything downstream of the parser consumes `SampleEvent`, and everything downstream of the pipeline consumes `ReportModel` / `report-data.json`. The JSON is the **canonical contract** (§6); the HTML is a skin over it.

### 1.3 Key third-party dependencies

| Library | Use | Notes |
|---|---|---|
| HdrHistogram (org.hdrhistogram) | Exact percentiles, mergeable, log-compressed | 2 significant digits default; ~40 KB/histogram |
| Jackson (streaming) | JSON emit; XML JTL via Woodstox/StAX | Streaming only — no full-tree loads |
| picocli | CLI | Annotation-based, GraalVM-friendly |
| SnakeYAML | SLA config | Safe-load only |
| Apache Commons CSV — **not used** | | Custom parser (§3.2) for throughput + adaptive columns |
| ECharts 5 | Charts | Inlined, no CDN |
| Preact + TypeScript | Report SPA | Small bundle (~50 KB gz vs React) |

Shading: all Java deps relocated under `io.forge.reportforge.shaded.*` in the JMeter plugin JAR to avoid classpath collisions with JMeter's own Jackson/commons versions. (This is the classic `lib/ext` failure mode; relocation is mandatory.)

---

## 2. M6 — JMeter Integration Layer

### 2.1 GUI menu action (MenuCreator SPI)

JMeter discovers `org.apache.jmeter.gui.plugin.MenuCreator` implementations on the classpath and adds their menu items. We register under the Tools menu:

```java
public class ReportForgeMenuCreator implements MenuCreator {
    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.TOOLS) return new JMenuItem[0];
        JMenuItem item = new JMenuItem("ReportForge: Generate Advanced Report");
        item.addActionListener(e -> ReportForgeDialog.open());
        return new JMenuItem[]{item};
    }
    // ... getTopLevelMenus(): none; localeChanged(): relabel
}
```

`ReportForgeDialog` (Swing): JTL file picker (multi-select for distributed merges), output directory, SLA file picker, baseline picker, checkboxes (JUnit XML, include sub-samples, steady-state-only SLAs), Generate button.

Threading contract (FR-601): the dialog submits a `ReportJob` to a single-thread `ExecutorService`; progress callbacks (`bytesRead/totalBytes`, phase name) marshalled to the EDT via `SwingUtilities.invokeLater`; cancel sets an interrupt flag checked at parser batch boundaries (every 64K samples). On success: `Desktop.getDesktop().browse(report.html)`.

### 2.2 CLI (reportforge-cli)

```
reportforge generate --jtl results.jtl[,worker2.jtl,...] --out ./report \
    [--sla sla.yaml] [--baseline prior/report-data.json] \
    [--junit] [--title "Checkout API — RC 2.4"] [--meta build=1234 --meta env=perf1] \
    [--apdex-satisfied 500 --apdex-tolerated 1500] \
    [--bucket auto|1s|5s|1m] [--include-subsamples] [--steady-state-only] \
    [--fail-on fail|degraded] [--label-cap 2000]

reportforge compare --baseline a/report-data.json --candidate b/report-data.json --out ./diff
reportforge validate --sla sla.yaml
```

Exit codes: `0` PASS, `1` FAIL (or DEGRADED with `--fail-on degraded`), `2` usage error, `3` input error (unparseable beyond threshold), `4` internal error. These are load-bearing for CI (FR-403) and frozen in v1.

### 2.3 Non-GUI auto-generation hook

`reportforge-jmeter` also ships a tiny `TestStateListener` registered only when `reportforge.autogenerate=true`: on `testEnded`, it resolves the result file from `resultcollector` configuration and invokes the same `ReportJob` (FR-603). No test-plan element required.

### 2.4 Phase 4 — Live Collector (SampleListener)

A GUI-addable listener ("ReportForge Collector") implementing `SampleListener`, `TestStateListener`, `Clearable`:

- `sampleOccurred(SampleEvent)` → route to the same `MetricsPipeline` used offline (identical code path ⇒ identical numbers).
- Per-label `Recorder` (HdrHistogram's lock-free writer) to keep the sampler threads unblocked; a scheduled flusher (1 s) drains into interval + cumulative histograms.
- Distributed mode: `sampleOccurred` fires on the controller for remoted samples in standard mode, so aggregation naturally centralizes; document the caveat for `mode=Statistical` (pre-aggregated samples degrade histogram fidelity — we detect and warn).
- `testEnded(host)` → after last host, assemble `ReportModel` and render. Report available seconds after test end; JTL never re-read.

Back-pressure budget: pipeline work per sample ≤2 µs target; measured in JMH benchmarks (§9.3).

---

## 3. M1 — JTL Ingestion Engine

### 3.1 Format detection

First 4 KB sniff: `<?xml` / `<testResults` ⇒ XML path (StAX); otherwise CSV path. `.gz` suffix or gzip magic bytes ⇒ wrap in `GZIPInputStream` (FR-106).

### 3.2 CSV parser (custom, adaptive)

Why custom: Commons CSV allocates heavily per record; we need ≥150 MB/s (NFR-P1) plus JMeter-specific column adaptivity.

- **Header adaptation (FR-101):** read header row; map known JMeter column names (`timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect`, plus assertion/hostname/sub-result extras) to a `ColumnMap` of indices. Unknown columns preserved as opaque extras (surfaced in drill-down). Missing optional columns ⇒ the dependent features degrade gracefully with a report warning (e.g., no `Connect` ⇒ no connect-time series).
- **No-header JTLs:** fall back to `jmeter.save.saveservice` default order; overridable via `--columns` flag.
- **Tokenizer:** single-pass byte-level scanner over a 1 MB direct buffer; RFC-4180 quote handling incl. embedded newlines (FR-104); zero-copy field slices decoded lazily (labels interned; numeric fields parsed from bytes without String allocation).
- **Malformed lines (FR-105):** exception per line ⇒ counter + first-10 samples retained for the report's warnings panel; abort if `malformed/total > threshold` (default 1%).
- **Timestamps (FR-109):** first data row sniffed — 13-digit numeric ⇒ epoch ms; otherwise try configured/`ISO`/`MM/dd/yy HH:mm:ss` patterns.

### 3.3 XML parser

StAX cursor over `<sample>`/`<httpSample>` elements; sub-samples handled by depth tracking — a child sample is attached to its parent per FR-107 policy (roll up vs. report separately, decided at report time from the retained parent/child flags). Assertion results captured for error clustering.

### 3.4 Distributed merge (FR-108)

K-way merge by timestamp across N readers using a min-heap; emits one ordered `SampleEvent` stream so time-bucketing and thread-overlay remain correct. Host name (when present) preserved per event for a per-injector breakdown panel.

### 3.5 SampleEvent (core record)

```java
record SampleEvent(long timestampMs, int elapsedMs, String label,
                   String responseCode, boolean success, String failureMessage,
                   int latencyMs, int connectMs, long bytes, long sentBytes,
                   int grpThreads, int allThreads, String threadName,
                   String hostname, boolean isTransaction, boolean isSubSample,
                   String parentLabel) {}
```

---

## 4. M2 — Metrics & Analytics Engine

### 4.1 Accumulator layout

Per label (plus one `__TOTAL__` rollup):

```
LabelAccumulator
 ├─ cumulative HdrHistogram (elapsed)          [1 ms–1 h range, 2 sig. digits]
 ├─ cumulative HdrHistogram (latency)          [if column present]
 ├─ cumulative HdrHistogram (connect)          [if column present]
 ├─ counters: n, errors, bytes, sentBytes, apdexSat, apdexTol
 ├─ minTs, maxTs
 └─ ring of top-N slowest samples (N=20, min-heap by elapsed)   [FR-211]
```

Per time bucket (granularity auto: run ≤10 min ⇒ 1 s; ≤2 h ⇒ 10 s; ≤12 h ⇒ 1 m; else 5 m; overridable):

```
BucketRow[label][bucket] → {n, errors, sumElapsed, interval HdrHistogram (coarser: 1 sig. digit),
                            bytes, sentBytes, maxAllThreads}
```

Memory control (NFR-P2): per-label interval histograms use HdrHistogram's packed/auto-resize form (~4–8 KB each at 1 sig. digit). Worst case 2,000 labels × 1,440 buckets is prevented by a **bucket-histogram budget**: if labels × buckets exceeds a limit, per-bucket percentiles fall back to per-bucket mean/max + cumulative-histogram percentiles (report notes the downgrade). Label cap (default 2,000): overflow labels roll into `__OTHER__` with a warning (PRD risk table).

### 4.2 Derived analytics

- **APDEX (FR-205):** counters incremented at ingest against satisfied/tolerated thresholds (global + per-label overrides from SLA file).
- **Thread overlay (FR-206):** per-bucket max `allThreads`.
- **Ramp segmentation (FR-210):** on the thread-count series, find longest window where thread count is within ±5% of its plateau value → steady-state window; all SLA evaluation defaults to this window (separate steady-state accumulators are built in the same single pass using a two-pass-avoidance trick: buckets are tagged post-hoc and steady-state stats are recomputed by merging interval histograms of steady buckets — this is why interval histograms exist).
- **Knee detection (FR-209):** on the (concurrency → throughput) curve aggregated per concurrency level, apply the Kneedle algorithm (max distance from secant line on the normalized curve); report knee concurrency and the throughput ceiling. Little's Law check: for steady state, compare measured mean concurrency N̄ vs X̄·R̄; deviation >20% ⇒ warning (usually indicates think-time/timer accounting or pacing issues).
- **Error clustering (FR-207/208):** signature = `(responseCode, assertionName, normalize(failureMessage or responseMessage))` where `normalize` lowercases, strips digits/UUIDs/hex ≥4 chars → `#`, collapses whitespace, truncates to 120 chars. HashMap of signature → {count, firstTs, lastTs, sampleLabels(top 5), threadsAtOnset, throughputAtOnset}. Onset metrics read from the bucket in which the cluster first exceeded 0.1% of that bucket's samples.

### 4.3 Determinism (NFR-R1)

Single-threaded reduction per label (parser may read ahead on one thread, pipeline consumes on one thread; the fan-out in §1.2 is logical, not concurrent in v1 offline mode). Histogram iteration order fixed; JSON emitted with sorted keys and fixed double formatting (`%.3f`). Live mode (Phase 4) relaxes byte-identity but must match offline results within histogram quantization.

---

## 5. M4 — SLA Engine & M5 — Comparison Engine

### 5.1 SLA YAML

```yaml
version: 1
defaults:
  window: steady-state          # or: full-run
  apdex: { satisfied_ms: 500, tolerated_ms: 1500 }
rules:
  - match: "Checkout*"          # glob on label; "TOTAL" = rollup
    assert:
      - metric: p95            # any of: p50..p999, mean, max, error_rate, throughput, apdex
        op: "<"
        value: 800             # ms; error_rate in %, throughput in req/s, apdex 0..1
        warn: 700              # optional DEGRADED band
  - match: "TOTAL"
    assert:
      - { metric: error_rate, op: "<", value: 0.5, warn: 0.1 }
      - { metric: throughput, op: ">=", value: 100 }
```

Evaluation: each `(rule, label, assertion)` triple → one result {PASS|DEGRADED|FAIL, actual, threshold}; overall verdict = worst-of (FR-402). Emitted into `report-data.json`, the verdict banner, and JUnit XML (one `<testcase>` per triple; failures carry actual-vs-threshold text) (FR-307/404).

### 5.2 Comparison (FR-501–504)

Input: two `report-data.json` files (schema-version checked). Per common label:

- Deltas for n, throughput, error_rate, mean, p50/p90/p95/p99, apdex (absolute + %).
- **Significance:** exact per-sample data is gone, but cumulative HDR histograms are serialized in the JSON (compressed base64, §6). Mann-Whitney U is computed from the two histograms directly (rank sums over merged value buckets weighted by counts — mathematically exact for tied-bucket data). Report U, z-score, p-value.
- **Effect size:** Cliff's delta from the same histogram pair. A regression is flagged only if `p < 0.01` **and** |delta| ≥ 0.147 (small effect) **and** metric change exceeds the tolerance in comparison config (default: p95 +10%, error_rate +0.2 pp, throughput −10%). Dual gating is the anti-noise mechanism promised in PRD FR-502.
- Output feeds M4 as synthetic rules ⇒ single unified verdict.

---

## 6. `report-data.json` — Canonical Schema (v1, frozen at Phase 0)

```jsonc
{
  "schema": "reportforge/1",
  "meta": { "title": "...", "generatedAt": "...", "tool": "reportforge x.y.z",
            "source": ["results.jtl"], "extras": {"build":"1234","env":"perf1"},
            "warnings": ["..."], "window": {"startMs":0,"endMs":0,
            "steadyState": {"startMs":0,"endMs":0}} },
  "labels": [
    { "name": "Checkout",
      "stats":       { "n":0,"errors":0,"errorRate":0.0,"mean":0.0,"min":0,"max":0,
                       "stdDev":0.0,"throughput":0.0,"kbRecv":0.0,"kbSent":0.0,
                       "pct": {"50":0,"75":0,"90":0,"95":0,"99":0,"99.9":0},
                       "apdex":0.0 },
      "steadyStats": { /* same shape, steady-state window */ },
      "latency":  { "pct": {...} }, "connect": { "pct": {...} },
      "histogram": "<base64 HdrHistogram V2 compressed>",   // enables comparison + re-quantiling
      "topSlowest": [ {"ts":0,"elapsed":0,"code":"200","thread":"..."} ]
    }
  ],
  "total": { /* same shape as a label */ },
  "series": { "bucketMs": 10000,
    "buckets": [ {"t":0, "perLabel": {"Checkout": {"n":0,"err":0,"mean":0,"p95":0,
                  "kbRecv":0.0}}, "threads":0, "hits":0 } ] },
  "errors":  [ {"signature":"...", "code":"500","count":0,"firstTs":0,"lastTs":0,
                "labels":["..."],"threadsAtOnset":0,"throughputAtOnset":0.0} ],
  "scalability": { "curve": [{"threads":0,"throughput":0.0,"p95":0}],
                   "knee": {"threads":0,"throughput":0.0}, "littlesLawDeviation":0.0 },
  "sla":     { "verdict":"PASS", "rules":[ {...} ] },
  "comparison": null | { "baselineMeta": {...}, "labels":[ {...deltas, p, cliffsDelta...} ] }
}
```

Compatibility policy: additive changes only within `reportforge/1`; consumers must ignore unknown fields. This is the LoadStorm/ScaleForge integration surface.

---

## 7. M3 — Report Renderer

### 7.1 Assembly

`reportforge-render` holds the built UI bundle (`app.js`, `app.css`) as classpath resources. Assembly writes:

```
<!doctype html><html><head><style>/* app.css */</style></head>
<body><div id="root"></div>
<script id="rf-data" type="application/json">{report-data.json}</script>
<script>/* app.js */</script></body></html>
```

One file, no network (FR-301, NFR-S1). `--split-assets` writes JSON + JS separately for pathological sizes. `report-data.json` and optional `junit.xml` always written alongside (FR-306/307).

### 7.2 UI (Preact + ECharts)

Sections per FR-302; global label filter re-renders every panel from the embedded JSON. Time-series thinning: if buckets × visible series > 50K points, LTTB downsampling in the UI only (stored data untouched — FR-308). Theme toggle via CSS variables; `window.print` stylesheet. Charts export PNG via ECharts `getDataURL`. No frameworks with runtime CDN assumptions; bundle budget ≤450 KB raw so a typical report stays under ~2 MB + data.

---

## 8. Packaging & Distribution (M7)

- **Plugin JAR** (`reportforge-jmeter-x.y.z.jar`): shaded, relocated deps, contains MenuCreator service registration (`META-INF/services/org.apache.jmeter.gui.plugin.MenuCreator`) and (Ph4) the listener GUI class. Install = copy to `lib/ext` or Plugins Manager.
- **CLI JAR** (`reportforge-cli-x.y.z.jar`): runnable, no JMeter required.
- **Plugins Manager**: publish repository metadata (id `reportforge`, versions, `depends`, `downloadUrl`) and submit to jmeter-plugins.org index per their contribution process; GitHub Releases carry both JARs regardless (PRD risk mitigation).
- Reproducible builds (fixed timestamps) so releases are verifiable.

---

## 9. Testing & Quality Strategy

### 9.1 Corpus-driven correctness

`reportforge-testdata`: ~40 golden JTLs — CSV default columns, CSV custom columns, headerless, XML with sub-samples + assertions, distributed pair, gzip, malformed (7 corruption modes), 0-error, all-error, single-label, 2,500-label overflow, 10 GB synthetic (generated, not committed). Each has an expected `report-data.json`; CI asserts byte-identity (NFR-R1).

### 9.2 Statistical validation

Reference implementation in Python (numpy exact percentiles) over the corpus; assert HDR results within quantization bounds; Mann-Whitney U validated against scipy on histogram-reconstructed samples.

### 9.3 Performance CI

JMH: tokenizer MB/s, pipeline ns/sample, live-recorder ns/sample. Nightly: 10 GB run with `-Xmx512m` asserting NFR-P1..P3; JFR capture on regression.

### 9.4 Compatibility matrix

GitHub Actions matrix: JMeter 5.5 / 5.6.3 × Java 17 / 21 × headless CLI + scripted GUI smoke (AssertJ-Swing opens dialog, generates from a small JTL).

---

## 10. Sequenced Work Breakdown (maps to PRD §8)

| Wk | Deliverable |
|---|---|
| 1 | Repo + modules + CI skeleton; corpus generator; schema v1 draft |
| 2 | Schema frozen; CSV tokenizer + ColumnMap; malformed handling |
| 3 | XML path; gzip; k-way merge; SampleEvent complete; parser perf pass 1 |
| 4 | MetricsPipeline: accumulators, buckets, APDEX, threads; determinism harness |
| 5 | JSON emit; Python reference validation green; NFR-P1/P2 gate |
| 6 | UI MVP (summary, table, core time-series); HtmlAssembler; MenuCreator dialog; CLI `generate`; shaded JARs — **MVP release 0.5** |
| 7 | SLA YAML + evaluator + verdict UI + JUnit XML + exit codes |
| 8 | Comparison engine (MWU/Cliff's from histograms) + `compare` command + diff UI |
| 9 | Error clustering + onset; ramp segmentation + steady-state stats; knee + Little's Law; **0.8** |
| 10 | Live Collector listener; distributed validation; parity tests vs offline |
| 11 | Docs site, SLA reference, sample JMX + demo report; a11y + print pass |
| 12 | Plugins Manager submission; GitHub release **1.0**; announcement |

---

## 11. Security & Privacy

No outbound network at any stage (NFR-S1). Response bodies/URLs excluded from `report-data.json` by default (`--include-urls` opt-in; bodies never included). YAML safe-load. Paths sanitized; report contains no absolute paths unless `--meta` supplies them. Shaded deps pinned + OWASP dependency-check in CI.

## 12. Future (v2 candidates)

Parser SPI opened to Gatling/k6/LoadRunner formats; AI narrative findings (Claude API, opt-in, feeding from `report-data.json`); trend store (N-run history) — natural handoff point to ScaleForge/PerfForge rather than growing a server into the plugin.

---
*End of TechSpec v1.0.*
