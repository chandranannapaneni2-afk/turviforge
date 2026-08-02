# TurviForge — Advanced Reporting Plugin for Apache JMeter

Replaces the stock JTL-based HTML dashboard with an accurate, interactive,
CI-ready report. Single self-contained `report.html`, canonical
`report-data.json` (schema `turviforge/1`), SLA verdicts with exit codes,
and JUnit XML for pipelines.

**Status: v0.5.0 (MVP milestone).**

## What you get

- **Exact-grade percentiles** — log-linear histogram (≤1% relative error at
  p99.9, validated against exact sorts), not bucket interpolation.
- **Steady-state detection** — ramp-up/down automatically excluded from SLA math.
- **SLA gates** — declarative YAML, PASS/DEGRADED/FAIL verdict, exit code 1 on
  FAIL, `junit.xml` for pipeline UIs.
- **Error intelligence** — failures clustered by normalized signature
  (IDs/hex/numbers collapsed), with threads & throughput at onset.
- **Scalability panel** — throughput-vs-concurrency curve, knee detection,
  Little's Law consistency check.
- **One file** — charts (ECharts), data, and styles inlined; opens offline;
  dark/light themes; print-friendly.

## Quick start (CLI — no JMeter required)

```bash
java -jar turviforge-cli.jar generate \
  --jtl results.jtl --out report/ \
  --sla samples/sla.yaml --junit \
  --title "Checkout API — RC 2.4" --meta build=1842 --meta env=perf-1
echo $?   # 0 PASS · 1 FAIL · 2 usage · 3 input · 4 internal
```

Options: `--apdex-satisfied/--apdex-tolerated`, `--bucket <ms>`,
`--label-cap <n>`, `--fail-on fail|degraded`. Gzip (`.jtl.gz`) is transparent;
multiple `--jtl a.jtl,b.jtl` files merge (distributed runs).

## JMeter GUI

Copy `turviforge-jmeter-<ver>.jar` into `JMETER_HOME/lib/ext`, restart, then
**Tools → TurviForge: Generate Advanced Report**. Generation runs off the
event thread; the report opens in your browser on completion.

## SLA YAML

```yaml
version: 1
defaults:
  window: steady-state          # or full-run
rules:
  - match: "Checkout*"          # glob; "TOTAL" = rollup
    assert:
      - { metric: p95, op: "<", value: 800, warn: 700 }
      - { metric: error_rate, op: "<", value: 0.5 }
```

Metrics: `p50 p75 p90 p95 p99 p999 mean min max error_rate throughput apdex`.

## Building

Full build (Maven): `mvn package` → CLI fat jar + `lib/ext` plugin jar.
The `turviforge-core` module has **zero JMeter dependencies** and is the
reuse surface for LoadStorm / ScaleForge (`report-data.json` is the contract).

Sandbox/offline build without Maven:

```bash
./build.sh    # javac + jar; produces dist/turviforge-cli.jar
```

## Repo layout

```
turviforge-core/      parser, histogram, metrics, SLA, JSON, HTML assembly (no JMeter)
turviforge-cli/       command line entry point
turviforge-jmeter/    MenuCreator GUI action + dialog (compiles against ApacheJMeter_core)
turviforge-ui/        report front-end (vanilla JS + ECharts, inlined at build)
turviforge-testdata/  deterministic synthetic JTL generator
samples/               example sla.yaml
```

## Roadmap (per PRD)

Phase 3: baseline comparison (Mann-Whitney U + Cliff's delta from serialized
histograms), XML JTL input, sub-sample policy. **Done.**
Phase 4: live SampleListener collector, Plugins Manager listing. **Done.**

## Contributions

Contributions are not currently accepted unless accompanied by a
separate contributor license agreement approved by the repository owner.

## License

Copyright © 2026 Chandran Annapaneni. All rights reserved.

TurviForge is source-available under the [PolyForm Noncommercial
License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).

You may use, study, modify, and distribute TurviForge only for
purposes permitted by that license.

Commercial use—including providing TurviForge as part of a paid
product or service, using it to deliver paid services, or using it
for internal business purposes—requires a separate commercial
license from the copyright owner.

For commercial licensing, contact: chandranannapaneni2@gmail.com

See [LICENSE](LICENSE) for the complete terms.

### Third-party software

TurviForge includes Apache ECharts, which remains licensed separately
under the Apache License 2.0. The TurviForge license does not restrict
rights granted directly under third-party licenses. See
[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
