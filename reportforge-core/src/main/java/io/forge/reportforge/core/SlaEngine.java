package io.forge.reportforge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * M4 — SLA definitions and verdict (FR-401..404).
 *
 * Parses the documented SLA YAML subset without external dependencies:
 * <pre>
 *   version: 1
 *   defaults:
 *     window: steady-state
 *     apdex: { satisfied_ms: 500, tolerated_ms: 1500 }
 *   rules:
 *     - match: "Checkout*"
 *       assert:
 *         - { metric: p95, op: "<", value: 800, warn: 700 }
 *         - metric: error_rate
 *           op: "<"
 *           value: 0.5
 * </pre>
 * Both flow-style ({...}) and block-style assertions are supported.
 */
public final class SlaEngine {

    public enum Status { PASS, DEGRADED, FAIL }

    public static final class Assertion {
        public String metric; public String op = "<"; public double value; public Double warn;
    }

    public static final class Rule {
        public String match;
        public final List<Assertion> assertions = new ArrayList<>();
    }

    public static final class RuleResult {
        public String label, metric, op;
        public double threshold, actual;
        public double warn = Double.NaN;
        public Status status;
    }

    public static final class Verdict {
        public Status overall = Status.PASS;
        public final List<RuleResult> results = new ArrayList<>();
    }

    /** Parsed defaults from the SLA file. */
    public static final class Defaults {
        public boolean steadyState = true;
        public int apdexSatisfiedMs = 500;
        public int apdexToleratedMs = 1500;
    }

    private final List<Rule> rules;
    private final boolean steadyStateDefault;
    private final Defaults defaults;

    public SlaEngine(List<Rule> rules, boolean steadyStateDefault) {
        this.rules = rules;
        this.steadyStateDefault = steadyStateDefault;
        this.defaults = new Defaults();
        this.defaults.steadyState = steadyStateDefault;
    }

    private SlaEngine(List<Rule> rules, Defaults defaults) {
        this.rules = rules;
        this.steadyStateDefault = defaults.steadyState;
        this.defaults = defaults;
    }

    public Defaults getDefaults() { return defaults; }
    public List<Rule> getRules() { return rules; }

    /* ----------------------------------------------------------- parse -- */

    public static SlaEngine load(Path yaml) throws IOException {
        List<Rule> rules = new ArrayList<>();
        Defaults defaults = new Defaults();
        Rule cur = null;
        Assertion curA = null;
        boolean inAssert = false;
        boolean inDefaults = false;
        boolean inRules = false;

        for (String raw : Files.readAllLines(yaml)) {
            String line = stripComment(raw);
            String t = line.trim();
            if (t.isEmpty()) continue;

            // Top-level keys
            if (!line.startsWith(" ") && !line.startsWith("\t") && !t.startsWith("-")) {
                if (t.startsWith("version:")) { inDefaults = false; inRules = false; continue; }
                if (t.startsWith("defaults:")) { inDefaults = true; inRules = false; continue; }
                if (t.startsWith("rules:")) { inDefaults = false; inRules = true; continue; }
                inDefaults = false;
            }

            // Defaults block
            if (inDefaults && !inRules) {
                if (t.startsWith("window:") && t.contains("full-run")) defaults.steadyState = false;
                if (t.contains("satisfied_ms:")) {
                    defaults.apdexSatisfiedMs = parseIntAfter(t, "satisfied_ms:");
                }
                if (t.contains("tolerated_ms:")) {
                    defaults.apdexToleratedMs = parseIntAfter(t, "tolerated_ms:");
                }
                continue;
            }

            // Rules block
            if (t.startsWith("- match:")) {
                cur = new Rule();
                cur.match = unquote(t.substring(t.indexOf(':') + 1).trim());
                rules.add(cur);
                inAssert = false;
                curA = null;
            } else if (t.equals("assert:")) {
                inAssert = true;
            } else if (inAssert && cur != null && t.startsWith("- {")) {
                Assertion a = parseFlow(t.substring(t.indexOf('{')));
                cur.assertions.add(a);
            } else if (inAssert && cur != null && t.startsWith("- metric:")) {
                curA = new Assertion();
                curA.metric = unquote(t.substring(t.indexOf(':') + 1).trim());
                cur.assertions.add(curA);
            } else if (curA != null && t.startsWith("op:")) {
                curA.op = unquote(t.substring(3).trim());
            } else if (curA != null && t.startsWith("value:")) {
                curA.value = Double.parseDouble(t.substring(6).trim());
            } else if (curA != null && t.startsWith("warn:")) {
                curA.warn = Double.parseDouble(t.substring(5).trim());
            }
        }
        return new SlaEngine(rules, defaults);
    }

    private static int parseIntAfter(String s, String key) {
        String v = s.substring(s.indexOf(key) + key.length()).trim();
        // Extract leading integer (handles flow-style like "400, tolerated_ms: 1200 }")
        StringBuilder num = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (Character.isDigit(c) || (c == '-' && num.isEmpty())) num.append(c);
            else break;
        }
        try { return Integer.parseInt(num.toString()); } catch (NumberFormatException e) { return 500; }
    }

    private static Assertion parseFlow(String flow) {
        Assertion a = new Assertion();
        String body = flow.replaceAll("[{}]", "");
        for (String part : body.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim(), v = unquote(kv[1].trim());
            switch (k) {
                case "metric" -> a.metric = v;
                case "op" -> a.op = v;
                case "value" -> a.value = Double.parseDouble(v);
                case "warn" -> a.warn = Double.parseDouble(v);
            }
        }
        return a;
    }

    private static String stripComment(String s) {
        int i = s.indexOf('#');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"")
                || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /* -------------------------------------------------------- evaluate -- */

    public Verdict evaluate(ReportModel m) {
        Verdict v = new Verdict();
        for (Rule rule : rules) {
            Pattern p = glob(rule.match);
            List<ReportModel.LabelStats> targets = new ArrayList<>();
            if (rule.match.equalsIgnoreCase("TOTAL")) {
                if (m.total != null) targets.add(m.total);
            } else {
                for (ReportModel.LabelStats ls : m.labels) {
                    if (p.matcher(ls.name).matches()) targets.add(ls);
                }
            }
            for (ReportModel.LabelStats ls : targets) {
                ReportModel.Stats st = steadyStateDefault && ls.steadyStats != null
                        ? ls.steadyStats : ls.stats;
                for (Assertion a : rule.assertions) {
                    RuleResult r = new RuleResult();
                    r.label = ls.name;
                    r.metric = a.metric;
                    r.op = a.op;
                    r.threshold = a.value;
                    if (a.warn != null) r.warn = a.warn;
                    r.actual = metric(st, a.metric);
                    boolean pass = compare(r.actual, a.op, a.value);
                    if (!pass) r.status = Status.FAIL;
                    else if (a.warn != null && !compare(r.actual, a.op, a.warn)) r.status = Status.DEGRADED;
                    else r.status = Status.PASS;
                    if (r.status.ordinal() > v.overall.ordinal()) v.overall = r.status;
                    v.results.add(r);
                }
            }
        }
        return v;
    }

    private static double metric(ReportModel.Stats s, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "mean" -> s.mean;
            case "min" -> s.min;
            case "max" -> s.max;
            case "error_rate", "errorrate" -> s.errorRate;
            case "throughput" -> s.throughput;
            case "apdex" -> s.apdex;
            default -> {
                if (n.startsWith("p")) {
                    String q = n.substring(1);
                    if (q.equals("999")) q = "99.9";
                    Long v = s.pct.get(q);
                    if (v != null) yield v;
                }
                throw new IllegalArgumentException("Unknown SLA metric: " + name);
            }
        };
    }

    private static boolean compare(double actual, String op, double threshold) {
        return switch (op) {
            case "<" -> actual < threshold;
            case "<=" -> actual <= threshold;
            case ">" -> actual > threshold;
            case ">=" -> actual >= threshold;
            case "==" -> actual == threshold;
            default -> throw new IllegalArgumentException("Unknown op: " + op);
        };
    }

    private static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString());
    }

    /* ------------------------------------------------------- junit xml -- */

    public static String junitXml(Verdict v, String suiteName) {
        long failures = v.results.stream().filter(r -> r.status == Status.FAIL).count();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(xml(suiteName))
          .append("\" tests=\"").append(v.results.size())
          .append("\" failures=\"").append(failures).append("\">\n");
        for (RuleResult r : v.results) {
            String name = r.label + " :: " + r.metric + " " + r.op + " " + fmt(r.threshold);
            sb.append("  <testcase name=\"").append(xml(name)).append("\" classname=\"reportforge.sla\"");
            if (r.status == Status.FAIL) {
                sb.append(">\n    <failure message=\"actual ").append(fmt(r.actual))
                  .append(" violates ").append(xml(r.op)).append(" ").append(fmt(r.threshold))
                  .append("\"/>\n  </testcase>\n");
            } else {
                sb.append("/>\n");
            }
        }
        sb.append("</testsuite>\n");
        return sb.toString();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.3f", d);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
