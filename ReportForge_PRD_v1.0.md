# ReportForge — Advanced JMeter Reporting Plugin
## Product Requirements Document (PRD) v1.0

| Field | Value |
|---|---|
| Product | ReportForge — Advanced Reporting Plugin for Apache JMeter |
| Document | PRD v1.0 |
| Status | Draft for review |
| Date | 26 July 2026 |
| Owner | Performance Engineering / Forge Platform |
| Companion doc | ReportForge TechSpec v1.0 |

---

## 1. Executive Summary

Apache JMeter's built-in HTML dashboard (generated from JTL result files via `-e -o` or the Tools menu) has not materially evolved in a decade. It offers a fixed set of charts, approximated percentiles, no run-to-run comparison, no SLA gating, poor performance on large result files, and a dated user experience. Every serious performance engineering team ends up exporting JTLs into Grafana, Excel, or bespoke scripts to answer basic questions the report should answer natively.

**ReportForge** is a drop-in JMeter plugin (single JAR in `lib/ext`, distributable via the jmeter-plugins.org Plugins Manager) that fully replaces the stock dashboard with a modern, accurate, analysis-grade report — while requiring zero changes to existing test plans or JTL output. It works three ways: from the JMeter GUI (Tools menu), from the command line (CI-friendly), and optionally as a live listener that computes report data during the run so the report is ready the instant the test ends.

ReportForge is also the designated report layer for the Forge performance stack (LoadStorm / ScaleForge / PerfForge): its JMeter-independent core engine and `report-data.json` contract are reused by those platforms.

---

## 2. Problem Statement

### 2.1 Current state (stock JMeter dashboard)

1. **Inaccurate percentiles.** The stock generator computes percentiles from coarse time-series buckets and interpolation, not from the full latency distribution. At p99/p99.9 — where SLAs actually live — errors of 10–30% are common.
2. **No comparison.** There is no way to diff two runs (before/after a release, baseline vs. candidate) without external tooling.
3. **No verdict.** The report is descriptive, not evaluative. There is no SLA definition, no pass/fail, no CI exit code. Teams bolt on Taurus or shell scripts just to fail a pipeline.
4. **Weak error analysis.** Errors are counted, not explained. No clustering by failure signature, no correlation of error onset with load level or latency inflection.
5. **Poor scalability.** The generator loads and re-processes the JTL in ways that struggle beyond a few GB; multi-hour soak tests routinely fail to render or take tens of minutes.
6. **Fixed, dated UX.** FreeMarker-templated static pages, non-interactive beyond basic zoom, no dark mode, no per-transaction drill-down, multi-file output that is awkward to share.
7. **Rigid configuration.** Customization requires editing `reportgenerator.properties` and FreeMarker templates — a skill nobody wants to maintain.

### 2.2 Impact

- Performance engineers spend 1–3 hours per test cycle on manual post-processing (Excel/Grafana/scripts).
- CI/CD performance gates are either absent or implemented as fragile custom scripts.
- Percentile inaccuracy causes false SLA passes at p99+.
- Reports are not decision documents; stakeholders ask "so did we pass?" and the report cannot answer.

---

## 3. Goals and Non-Goals

### 3.1 Goals

| # | Goal | Measure |
|---|---|---|
| G1 | Replace the stock HTML dashboard with a superior report for 100% of common use cases | Feature parity checklist + differentiators shipped |
| G2 | Exact percentiles at any quantile | HdrHistogram-backed, ≤1% quantile error at p99.9 |
| G3 | First-class CI/CD integration | SLA verdict, non-zero exit code, JUnit XML output |
| G4 | Handle very large result files | 10 GB JTL processed in constant memory (<512 MB heap), ≥150 MB/s parse throughput |
| G5 | Zero-friction deployment | Single JAR in `lib/ext`; installable via Plugins Manager; no test plan changes |
| G6 | Run-to-run comparison with statistical rigor | Baseline diff mode with significance testing |
| G7 | Reusable core | `core` module has no JMeter dependency; consumed by LoadStorm/ScaleForge |

### 3.2 Non-Goals (v1)

- Live real-time dashboards during the run for observers (Grafana-style streaming UI). Live *aggregation* is in scope (Phase 4); a live *web dashboard* is not.
- Test execution, scheduling, or distributed-run orchestration (LoadStorm's domain).
- Replacing JMeter's InfluxDB/Graphite backend listeners.
- Non-JMeter input formats (Gatling, k6, LoadRunner) — deferred to v2 via the parser SPI.
- Server-side/hosted report storage — the report is a self-contained artifact.

---

## 4. Personas

| Persona | Needs |
|---|---|
| **Performance Test Engineer** (primary) | Fast, accurate, drill-down report after every run; error diagnosis; shareable single file |
| **DevOps / Platform Engineer** | Headless CLI, SLA gate with exit codes, JUnit XML for pipeline UI, machine-readable JSON |
| **Performance Architect** | Baseline comparison, scalability/knee analysis, Little's Law sanity checks, capacity narrative |
| **Engineering Manager / Stakeholder** | One-glance verdict banner, executive summary page, trend vs. previous release |

---

## 5. Product Scope and Feature Set

### 5.1 Module map

1. **M1 — JTL Ingestion Engine**: adaptive, streaming parser for CSV and XML JTLs.
2. **M2 — Metrics & Analytics Engine**: HDR-histogram statistics, time-series aggregation, APDEX, concurrency reconstruction, error clustering, scalability analysis.
3. **M3 — Report Renderer**: single-file interactive HTML report + `report-data.json` + optional JUnit XML / CSV exports.
4. **M4 — SLA & Verdict Engine**: declarative thresholds, pass/fail evaluation, CI exit codes.
5. **M5 — Comparison Engine**: baseline vs. candidate diff with significance testing.
6. **M6 — JMeter Integration Layer**: GUI menu action, CLI runner, (Phase 4) live listener.
7. **M7 — Packaging & Distribution**: shaded JAR, Plugins Manager metadata, docs.

### 5.2 Functional Requirements

#### M1 — JTL Ingestion (FR-100 series)

- **FR-101** Parse CSV JTLs with arbitrary `jmeter.save.saveservice.*` column configurations by reading the header row and adapting field mapping at runtime.
- **FR-102** Parse XML JTLs (`.jtl`/`.xml`) including nested sub-samples and assertion results.
- **FR-103** Stream-parse in constant memory; never load the full file.
- **FR-104** Handle quoted fields, embedded delimiters, embedded newlines, and multi-line response messages per RFC-4180 semantics.
- **FR-105** Tolerate malformed lines: skip, count, and report them (threshold-configurable failure).
- **FR-106** Support gzip-compressed JTLs (`.jtl.gz`) transparently.
- **FR-107** Distinguish transactions (Transaction Controller samples) from raw samplers; configurable inclusion of sub-samples in statistics (parity with `generate_parent_sample` semantics, but switchable at report time without re-running the test).
- **FR-108** Merge multiple JTL files from one distributed run into a single logical run (timestamp-ordered k-way merge).
- **FR-109** Auto-detect timestamp format (epoch ms vs. formatted) and time zone handling.

#### M2 — Metrics & Analytics (FR-200 series)

- **FR-201** Per-label and rollup statistics: count, error count/rate, min/max/mean, standard deviation, throughput (req/s), bandwidth (sent/received KB/s).
- **FR-202** Exact percentiles from HdrHistogram (default p50, p75, p90, p95, p99, p99.9; any quantile user-configurable) — global and per time bucket.
- **FR-203** Latency decomposition where columns exist: connect time, latency (TTFB), elapsed; report each as its own distribution.
- **FR-204** Time-series aggregation at configurable granularity (auto-selected from run duration; 1 s–5 min buckets).
- **FR-205** APDEX per label with configurable satisfied/tolerated thresholds, including per-label overrides.
- **FR-206** Active-thread reconstruction over time from `allThreads`/`grpThreads`; overlay on all time-series charts.
- **FR-207** Error clustering: group failures by (response code, assertion name, normalized response message signature); rank clusters by frequency; show first/last occurrence and load level at onset.
- **FR-208** Error-onset correlation: mark the throughput and concurrency levels at which each error cluster first exceeds a configurable rate.
- **FR-209** Scalability analysis: throughput vs. concurrency curve, automatic knee-point (saturation) detection, and a Little's Law consistency check (N ≈ X·R) with deviation warnings.
- **FR-210** Ramp segmentation: automatically detect ramp-up / steady-state / ramp-down phases; compute steady-state-only statistics (the numbers that matter for SLAs).
- **FR-211** Outlier surfacing: top-N slowest samples per label with timestamp, thread, and response code for drill-down.

#### M3 — Report Renderer (FR-300 series)

- **FR-301** Single self-contained HTML file (all JS/CSS/data inlined) — no CDN, works offline, email-able.
- **FR-302** Report sections: Verdict banner → Executive summary → Transaction statistics table → Time-series (response times, throughput, errors, threads, bandwidth) → Percentile distribution curves → APDEX → Error analysis → Scalability analysis → Run metadata.
- **FR-303** Interactive charts: zoom, pan, series toggle, hover tooltips, per-label filter that re-filters the entire report, PNG export per chart.
- **FR-304** Transaction table: sortable, searchable, column chooser, CSV export, SLA status column with red/amber/green.
- **FR-305** Dark/light theme toggle; print-friendly stylesheet.
- **FR-306** Emit `report-data.json` (the canonical machine-readable output — schema in TechSpec §6) alongside the HTML.
- **FR-307** Emit optional JUnit-style XML (one test case per SLA rule) for pipeline-native rendering.
- **FR-308** Report renders acceptably on a laptop browser with 500+ labels and 24 h of time series (progressive rendering / data thinning in the UI layer, never in the stored data).
- **FR-309** Branding hooks: title, logo, environment metadata (build number, git SHA, environment name) injectable via CLI flags or properties.

#### M4 — SLA & Verdict (FR-400 series)

- **FR-401** Declarative SLA definitions in YAML (or JMeter properties for simple cases): per-label or glob-matched rules on any computed metric (e.g., `p95 < 800ms`, `errorRate < 0.5%`, `throughput >= 100/s`), evaluated on steady-state by default.
- **FR-402** Three-level verdict: PASS / DEGRADED (warn thresholds) / FAIL; overall verdict is worst-of.
- **FR-403** CLI exits non-zero on FAIL (configurable: also on DEGRADED) for pipeline gating.
- **FR-404** Verdict banner and per-rule evaluation table rendered in the report.

#### M5 — Comparison (FR-500 series)

- **FR-501** Compare a candidate run against a baseline `report-data.json`: per-label deltas for all key metrics with percentage change.
- **FR-502** Statistical significance per label (Mann-Whitney U on latency samples via histogram approximation); suppress "regression noise" below significance/effect-size thresholds.
- **FR-503** Regression verdict: configurable tolerances (e.g., fail if p95 regresses >10% with significance) feeding the M4 verdict engine.
- **FR-504** Comparison section in the HTML report: side-by-side stats, delta table, overlaid percentile curves.

#### M6 — JMeter Integration (FR-600 series)

- **FR-601** GUI: "Tools → ReportForge: Generate Advanced Report" menu action (MenuCreator SPI) with a file picker, options dialog, progress bar, and open-on-completion. All work off the AWT event thread.
- **FR-602** CLI: `java -jar reportforge.jar --jtl results.jtl --out report/ [--sla sla.yaml] [--baseline baseline.json] [--junit] ...` usable with or without a JMeter installation.
- **FR-603** JMeter non-GUI hook: optional property `reportforge.autogenerate=true` generates the report at test end when the plugin is installed.
- **FR-604** (Phase 4) Live listener component ("ReportForge Collector") computing streaming aggregates during the run; report written at `testEnded` with no JTL re-parse; functions correctly in distributed (controller/worker) mode.
- **FR-605** Coexist with the stock dashboard — never modify or disable existing JMeter behavior.

#### M7 — Packaging & Distribution (FR-700 series)

- **FR-701** Single shaded JAR; drop into `lib/ext`; no additional dependencies to install.
- **FR-702** Publish through jmeter-plugins.org Plugins Manager (plugin metadata, versioned releases, dependency declaration).
- **FR-703** Semantic versioning; compatibility declared for JMeter 5.5–5.6.x at v1.
- **FR-704** Documentation site: quick start, CLI reference, SLA YAML reference, report tour, FAQ; sample JMX + demo report published.

### 5.3 Out-of-scope clarifications

Report data never leaves the machine: no telemetry, no network calls at generation time (NFR-S1). AI-assisted narrative analysis (auto-written findings) is a v2 candidate, deliberately excluded from v1 to keep the artifact deterministic and air-gap friendly.

---

## 6. Non-Functional Requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-P1 | Parse throughput | ≥150 MB/s on commodity hardware (NVMe, 4 cores) |
| NFR-P2 | Memory ceiling | <512 MB heap for a 10 GB JTL, any label cardinality ≤2,000 |
| NFR-P3 | End-to-end generation time | 10 GB JTL → report in <3 min |
| NFR-P4 | Report open time | <3 s to interactive in Chrome/Firefox/Edge for typical runs |
| NFR-C1 | JMeter compatibility | 5.5, 5.6.x (test matrix in CI); Java 17+ |
| NFR-C2 | Core module | Zero JMeter dependencies (reusable by Forge platforms) |
| NFR-R1 | Determinism | Same JTL + config ⇒ byte-identical `report-data.json` |
| NFR-R2 | Robustness | Malformed input never crashes generation; degraded output with warnings |
| NFR-S1 | Privacy/security | No network access, no telemetry; response bodies excluded from report by default |
| NFR-U1 | Accessibility | WCAG AA color contrast in both themes; keyboard-navigable tables |

---

## 7. Success Metrics

- Adoption: 5,000+ Plugins Manager installs in 6 months post-launch.
- Accuracy: p99.9 within 1% of ground truth on validation corpus (vs. stock report's typical 10–30% deviation).
- Performance: NFR-P1..P3 met on the golden corpus in CI on every release.
- CI usage: ≥30% of active users invoking CLI/SLA mode (measured via docs survey, not telemetry).
- Internal: LoadStorm/ScaleForge consuming `core` + `report-data.json` without forks.

---

## 8. Release Plan

| Phase | Weeks | Contents | Exit criteria |
|---|---|---|---|
| 0 — Foundations | 1–2 | Repo, module skeleton, golden JTL corpus, `report-data.json` schema v1 frozen | Schema reviewed; corpus covers CSV/XML/distributed/malformed |
| 1 — Core engine | 2–5 | M1 + M2 (through FR-206) | NFR-P1/P2 met; stats validated vs. reference implementation |
| 2 — MVP report | 4–6 | M3 (FR-301–306), M6 GUI+CLI, M7 packaging | **MVP: installable JAR generating full report from GUI and CLI** |
| 3 — Analytics & CI | 7–9 | M4 SLA, M5 comparison, FR-207–211, JUnit output | Pipeline gate demo green/red end-to-end |
| 4 — Live mode & launch | 10–12 | FR-604 live collector, Plugins Manager submission, docs, samples | Published; distributed-mode live report validated |

---

## 9. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| JTL configuration variability breaks parsing | High | High | Adaptive header mapping (FR-101); corpus-driven tests; malformed-line tolerance (FR-105) |
| JMeter API drift across versions | Medium | Medium | Depend only on stable SPIs (MenuCreator, SampleListener); version test matrix; core is JMeter-free |
| Large-file memory blowups from high label cardinality | Medium | High | Histogram-per-label budget monitoring; label-cap with rollup bucket; documented limits |
| GUI freezes during generation | Medium | Medium | All generation on worker threads; progress dialog; cancellation support |
| Single-file HTML too heavy for huge runs | Medium | Medium | UI-side data thinning (FR-308); optional `--split-assets` escape hatch |
| Plugins Manager review/acceptance delays | Low | Medium | Ship GitHub-release JAR path in parallel; PM listing is additive |
| Statistical comparison misused (false regressions) | Medium | Medium | Effect-size + significance dual threshold (FR-502); conservative defaults; docs |

---

## 10. Open Questions

1. Product name confirmation: **ReportForge** (working name) — confirm against Forge brand registry.
2. Default APDEX thresholds (500 ms/1.5 s proposed) — align with LoadStorm defaults?
3. Should v1 bundle a Gatling/k6 parser stub behind the SPI, or keep strictly JMeter?
4. Live listener GUI element name and icon set.
5. License: Apache-2.0 (recommended for Plugins Manager ecosystem fit) vs. proprietary core.

---
*End of PRD v1.0 — companion: ReportForge TechSpec v1.0.*
