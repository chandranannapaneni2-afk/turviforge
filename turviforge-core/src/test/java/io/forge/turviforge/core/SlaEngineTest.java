package io.forge.turviforge.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for SlaEngine (M4). */
class SlaEngineTest {

    @Test
    void parsesFlowStyleYaml(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("sla.yaml");
        Files.writeString(yaml, """
                version: 1
                defaults:
                  window: steady-state
                  apdex: { satisfied_ms: 400, tolerated_ms: 1200 }
                rules:
                  - match: "Checkout*"
                    assert:
                      - { metric: p95, op: "<", value: 800, warn: 700 }
                  - match: "TOTAL"
                    assert:
                      - { metric: error_rate, op: "<", value: 0.5, warn: 0.1 }
                      - { metric: throughput, op: ">=", value: 100 }
                """);
        SlaEngine engine = SlaEngine.load(yaml);
        assertEquals(2, engine.getRules().size());
        assertTrue(engine.getDefaults().steadyState);
        assertEquals(400, engine.getDefaults().apdexSatisfiedMs);
        assertEquals(1200, engine.getDefaults().apdexToleratedMs);
        assertEquals("Checkout*", engine.getRules().get(0).match);
        assertEquals(1, engine.getRules().get(0).assertions.size());
        assertEquals("p95", engine.getRules().get(0).assertions.get(0).metric);
        assertEquals(800.0, engine.getRules().get(0).assertions.get(0).value);
        assertEquals(700.0, engine.getRules().get(0).assertions.get(0).warn);
    }

    @Test
    void parsesBlockStyleYaml(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("sla.yaml");
        Files.writeString(yaml, """
                rules:
                  - match: "Login"
                    assert:
                      - metric: p99
                        op: "<"
                        value: 2000
                """);
        SlaEngine engine = SlaEngine.load(yaml);
        assertEquals(1, engine.getRules().size());
        assertEquals("p99", engine.getRules().get(0).assertions.get(0).metric);
        assertEquals(2000.0, engine.getRules().get(0).assertions.get(0).value);
    }

    @Test
    void fullRunWindow(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("sla.yaml");
        Files.writeString(yaml, """
                defaults:
                  window: full-run
                rules:
                  - match: "TOTAL"
                    assert:
                      - { metric: error_rate, op: "<", value: 1.0 }
                """);
        SlaEngine engine = SlaEngine.load(yaml);
        assertFalse(engine.getDefaults().steadyState);
    }

    @Test
    void evaluatePassAndFail() {
        // Build a model with known stats
        ReportModel m = new ReportModel();
        m.total = new ReportModel.LabelStats();
        m.total.name = "TOTAL";
        m.total.stats = new ReportModel.Stats();
        m.total.stats.n = 1000;
        m.total.stats.errors = 2;
        m.total.stats.errorRate = 0.2;
        m.total.stats.throughput = 150;
        m.total.stats.pct.put("95", 500L);
        m.total.stats.apdex = 0.92;

        ReportModel.LabelStats checkout = new ReportModel.LabelStats();
        checkout.name = "Checkout";
        checkout.stats = new ReportModel.Stats();
        checkout.stats.n = 200;
        checkout.stats.pct.put("95", 900L);
        checkout.stats.errorRate = 0;
        checkout.stats.apdex = 0.8;
        m.labels.add(checkout);

        // Rules: p95 < 800 (Checkout will FAIL), error_rate < 0.5 (TOTAL will PASS)
        var rules = new java.util.ArrayList<SlaEngine.Rule>();
        SlaEngine.Rule r1 = new SlaEngine.Rule();
        r1.match = "Checkout*";
        SlaEngine.Assertion a1 = new SlaEngine.Assertion();
        a1.metric = "p95"; a1.op = "<"; a1.value = 800;
        r1.assertions.add(a1);
        rules.add(r1);

        SlaEngine.Rule r2 = new SlaEngine.Rule();
        r2.match = "TOTAL";
        SlaEngine.Assertion a2 = new SlaEngine.Assertion();
        a2.metric = "error_rate"; a2.op = "<"; a2.value = 0.5;
        r2.assertions.add(a2);
        rules.add(r2);

        SlaEngine engine = new SlaEngine(rules, false);
        SlaEngine.Verdict v = engine.evaluate(m);
        assertEquals(SlaEngine.Status.FAIL, v.overall);
        assertEquals(2, v.results.size());
        // Checkout p95=900 > 800 => FAIL
        assertEquals(SlaEngine.Status.FAIL, v.results.get(0).status);
        // TOTAL error_rate=0.2 < 0.5 => PASS
        assertEquals(SlaEngine.Status.PASS, v.results.get(1).status);
    }

    @Test
    void degradedVerdict() {
        ReportModel m = new ReportModel();
        m.total = new ReportModel.LabelStats();
        m.total.name = "TOTAL";
        m.total.stats = new ReportModel.Stats();
        m.total.stats.pct.put("95", 750L);

        var rules = new java.util.ArrayList<SlaEngine.Rule>();
        SlaEngine.Rule r = new SlaEngine.Rule();
        r.match = "TOTAL";
        SlaEngine.Assertion a = new SlaEngine.Assertion();
        a.metric = "p95"; a.op = "<"; a.value = 800; a.warn = 700.0;
        r.assertions.add(a);
        rules.add(r);

        SlaEngine engine = new SlaEngine(rules, false);
        SlaEngine.Verdict v = engine.evaluate(m);
        // 750 < 800 (pass) but 750 > 700 (warn) => DEGRADED
        assertEquals(SlaEngine.Status.DEGRADED, v.overall);
    }

    @Test
    void junitXmlOutput() {
        SlaEngine.Verdict v = new SlaEngine.Verdict();
        v.overall = SlaEngine.Status.FAIL;
        SlaEngine.RuleResult r = new SlaEngine.RuleResult();
        r.label = "Checkout"; r.metric = "p95"; r.op = "<";
        r.threshold = 800; r.actual = 950; r.status = SlaEngine.Status.FAIL;
        v.results.add(r);

        String xml = SlaEngine.junitXml(v, "Test Suite");
        assertTrue(xml.contains("<?xml"));
        assertTrue(xml.contains("failures=\"1\""));
        assertTrue(xml.contains("Checkout :: p95 &lt; 800"));
        assertTrue(xml.contains("<failure"));
    }
}
