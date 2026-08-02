/* TurviForge report UI — vanilla JS + ECharts, fully offline. */
(function () {
  "use strict";

  const D = JSON.parse(document.getElementById("rf-data").textContent);
  const root = document.getElementById("root");
  const charts = [];
  let theme = "dark";
  let filterLabel = "__ALL__";

  /* ---------------- helpers ---------------- */
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const fmtMs = (v) => v >= 10000 ? (v / 1000).toFixed(1) + " s" : Math.round(v) + " ms";
  const fmtN = (v) => v.toLocaleString("en-US");
  const fmt1 = (v) => (Math.round(v * 10) / 10).toLocaleString("en-US");
  const fmtT = (t) => new Date(t).toLocaleTimeString("en-GB");
  const el = (html) => {
    const t = document.createElement("template");
    t.innerHTML = html.trim();
    return t.content.firstChild;
  };
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  function palette() {
    return {
      ink: css("--ink"), muted: css("--muted"), line: css("--line"),
      trace: css("--trace"), amber: css("--amber"), red: css("--red"), green: css("--green"),
    };
  }

  function baseOpt() {
    const p = palette();
    return {
      animation: false,
      textStyle: { color: p.muted, fontFamily: "ui-monospace, Menlo, Consolas, monospace", fontSize: 11 },
      grid: { left: 56, right: 56, top: 34, bottom: 46 },
      legend: { textStyle: { color: p.muted, fontSize: 11 }, top: 2, icon: "rect", itemWidth: 10, itemHeight: 3 },
      tooltip: { trigger: "axis", backgroundColor: theme === "dark" ? "#1a2334" : "#fff",
                 borderColor: p.line, textStyle: { color: p.ink, fontSize: 11 } },
      toolbox: { right: 4, feature: { saveAsImage: { title: "PNG" }, dataZoom: { title: { zoom: "zoom", back: "reset" } } } },
      xAxis: { type: "time", axisLine: { lineStyle: { color: p.line } }, splitLine: { show: false },
               axisLabel: { color: p.muted, formatter: (v) => fmtT(v) } },
      yAxis: { type: "value", axisLine: { show: false }, splitLine: { lineStyle: { color: p.line } },
               axisLabel: { color: p.muted } },
    };
  }

  function chart(id, optFn) {
    const dom = document.getElementById(id);
    const c = echarts.init(dom, null, { renderer: "canvas" });
    c.setOption(optFn());
    charts.push({ c, optFn });
    return c;
  }

  /* ---------------- data prep ---------------- */
  const total = D.total;
  const labels = D.labels;
  const buckets = D.series.buckets;
  const steady = D.meta.window.steadyState;
  const verdict = D.sla ? D.sla.verdict : null;
  const comparison = D.comparison;

  /* LTTB downsampling for large datasets (FR-308) */
  function lttb(data, threshold) {
    if (data.length <= threshold) return data;
    const sampled = [data[0]];
    const bucketSize = (data.length - 2) / (threshold - 2);
    let a = 0;
    for (let i = 0; i < threshold - 2; i++) {
      const rangeStart = Math.floor((i + 1) * bucketSize) + 1;
      const rangeEnd = Math.min(Math.floor((i + 2) * bucketSize) + 1, data.length);
      let avgX = 0, avgY = 0, count = 0;
      for (let j = rangeStart; j < rangeEnd; j++) { avgX += data[j][0]; avgY += data[j][1]; count++; }
      avgX /= count; avgY /= count;
      const start = Math.floor(i * bucketSize) + 1;
      const end = Math.floor((i + 1) * bucketSize) + 1;
      let maxArea = -1, maxIdx = start;
      for (let j = start; j < end; j++) {
        const area = Math.abs((data[a][0] - avgX) * (data[j][1] - data[a][1]) - (data[a][0] - data[j][0]) * (avgY - data[a][1]));
        if (area > maxArea) { maxArea = area; maxIdx = j; }
      }
      sampled.push(data[maxIdx]);
      a = maxIdx;
    }
    sampled.push(data[data.length - 1]);
    return sampled;
  }
  const MAX_POINTS = 2000; // thin series beyond this

  function seriesFor(label) {
    const key = label === "__ALL__" ? "TOTAL" : label;
    return buckets.map((b) => ({ t: b.t, c: b.perLabel[key] || null, threads: b.threads }));
  }

  /* ---------------- header + verdict ---------------- */
  const vColorVar = verdict === "FAIL" ? "--red" : verdict === "DEGRADED" ? "--amber" : verdict === "PASS" ? "--green" : "--muted";
  const durS = (D.meta.window.endMs - D.meta.window.startMs) / 1000;
  const extras = Object.entries(D.meta.extras).map(([k, v]) => `<span>${esc(k)}=${esc(v)}</span>`).join("");

  root.appendChild(el(`
    <div class="hdr">
      <span class="brand">TURVIFORGE</span>
      <h1>${esc(D.meta.title)}</h1>
      <div class="spacer"></div>
      <button id="themeBtn" type="button">Switch to light</button>
    </div>`));
  root.appendChild(el(`
    <div class="meta">
      <span>${esc(D.meta.source.join(", "))}</span>
      <span>${new Date(D.meta.window.startMs).toLocaleString("en-GB")}</span>
      <span>duration ${Math.round(durS)}s</span>
      <span>bucket ${D.series.bucketMs / 1000}s</span>
      ${extras}
    </div>`));

  // Signature element: verdict strip with embedded run timeline (threads trace + steady window).
  const tl = buckets.map((b) => b.threads);
  const tlMax = Math.max(1, ...tl);
  const W = 600, H = 44;
  const x = (i) => (i / Math.max(1, buckets.length - 1)) * W;
  const y = (v) => H - 4 - (v / tlMax) * (H - 10);
  const path = tl.map((v, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const sx0 = buckets.length ? x(buckets.findIndex((b) => b.t >= steady.startMs)) : 0;
  let sIdx1 = buckets.length - 1;
  for (let i = buckets.length - 1; i >= 0; i--) if (buckets[i].t < steady.endMs) { sIdx1 = i; break; }
  const sx1 = x(Math.max(0, sIdx1));
  const passCount = D.sla ? D.sla.rules.filter((r) => r.status === "PASS").length : 0;
  const ruleCount = D.sla ? D.sla.rules.length : 0;

  root.appendChild(el(`
    <div class="verdict" style="--vcolor: var(${vColorVar})">
      <div>
        <div class="word">${verdict || "REPORT"}</div>
        <div class="sub">${verdict ? "SLA verdict · steady-state window" : "no SLA file supplied"}</div>
      </div>
      <div class="timeline">
        <svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-label="thread timeline">
          <rect x="${sx0.toFixed(1)}" y="0" width="${Math.max(0, sx1 - sx0).toFixed(1)}" height="${H}"
                fill="var(--trace)" opacity="0.10"></rect>
          <path d="${path}" fill="none" stroke="var(--trace)" stroke-width="1.5"></path>
          <text x="${((sx0 + sx1) / 2).toFixed(1)}" y="11" text-anchor="middle"
                font-size="9" font-family="ui-monospace,monospace" fill="var(--muted)">steady state</text>
        </svg>
      </div>
      <div class="rules">${D.sla ? `<b>${passCount}</b>/${ruleCount} rules pass` : `${labels.length} transactions`}</div>
    </div>`));

  /* ---------------- warnings ---------------- */
  if (D.meta.warnings.length) {
    root.appendChild(el(`<div class="warnbox">⚠ ${D.meta.warnings.map(esc).join(" · ")}</div>`));
  }

  /* ---------------- KPI row ---------------- */
  const s = total.stats;
  const errCls = s.errorRate > 5 ? "bad" : s.errorRate > 1 ? "warn" : "good";
  const apdexCls = s.apdex >= 0.94 ? "good" : s.apdex >= 0.7 ? "warn" : "bad";
  root.appendChild(el(`
    <div class="kpis">
      <div class="kpi"><div class="v">${fmtN(s.n)}</div><div class="k">samples</div></div>
      <div class="kpi"><div class="v">${fmt1(s.throughput)}<span class="u">/s</span></div><div class="k">throughput</div></div>
      <div class="kpi ${errCls}"><div class="v">${s.errorRate.toFixed(2)}<span class="u">%</span></div><div class="k">errors</div></div>
      <div class="kpi"><div class="v">${fmtMs(s.pct["95"])}</div><div class="k">p95</div></div>
      <div class="kpi"><div class="v">${fmtMs(s.pct["99"])}</div><div class="k">p99</div></div>
      <div class="kpi ${apdexCls}"><div class="v">${s.apdex.toFixed(3)}</div><div class="k">apdex</div></div>
    </div>`));

  /* ---------------- controls ---------------- */
  const options = ['<option value="__ALL__">All transactions (TOTAL)</option>']
    .concat(labels.map((l) => `<option value="${esc(l.name)}">${esc(l.name)}</option>`)).join("");
  const controls = el(`
    <div class="controls">
      <label for="labelSel">Transaction</label>
      <select id="labelSel">${options}</select>
    </div>`);
  root.appendChild(controls);

  /* ---------------- chart panels ---------------- */
  root.appendChild(el(`<div class="panel"><h2>Response time over time</h2><div id="rtChart" class="chart tall"></div></div>`));
  root.appendChild(el(`
    <div class="row2">
      <div class="panel"><h2>Throughput &amp; errors</h2><div id="tpChart" class="chart"></div></div>
      <div class="panel"><h2>Percentile profile</h2><div id="pctChart" class="chart"></div></div>
    </div>`));
  root.appendChild(el(`
    <div class="row2">
      <div class="panel"><h2>Bandwidth (KB/s)</h2><div id="bwChart" class="chart"></div></div>
      <div class="panel"><h2>Active threads</h2><div id="thChart" class="chart"></div></div>
    </div>`));

  function rtOption() {
    const p = palette();
    const rows = seriesFor(filterLabel);
    const mk = (name, sel, color, width) => {
      let data = rows.filter((r) => r.c).map((r) => [r.t, sel(r.c)]);
      data = lttb(data, MAX_POINTS);
      return { name, type: "line", showSymbol: false, lineStyle: { width, color }, itemStyle: { color }, data };
    };
    const o = baseOpt();
    o.series = [
      mk("mean", (c) => Math.round(c.mean), p.trace, 2),
      mk("p95", (c) => c.p95, p.amber, 1.5),
      mk("max", (c) => c.max, p.red, 1),
      { name: "threads", type: "line", yAxisIndex: 1, showSymbol: false, step: "middle",
        lineStyle: { width: 1, color: p.muted, type: "dashed" }, itemStyle: { color: p.muted },
        data: lttb(rows.map((r) => [r.t, r.threads]), MAX_POINTS) },
    ];
    o.yAxis = [o.yAxis, { type: "value", name: "threads", axisLine: { show: false },
      splitLine: { show: false }, axisLabel: { color: p.muted } }];
    o.yAxis[0].name = "ms";
    return o;
  }

  function tpOption() {
    const p = palette();
    const rows = seriesFor(filterLabel);
    const bs = D.series.bucketMs / 1000;
    const o = baseOpt();
    o.series = [
      { name: "req/s", type: "line", showSymbol: false, areaStyle: { opacity: 0.12 },
        lineStyle: { width: 1.5, color: p.trace }, itemStyle: { color: p.trace },
        data: rows.filter((r) => r.c).map((r) => [r.t, +(r.c.n / bs).toFixed(2)]) },
      { name: "errors/s", type: "bar", yAxisIndex: 0, itemStyle: { color: p.red, opacity: 0.85 },
        barMaxWidth: 4, data: rows.filter((r) => r.c).map((r) => [r.t, +(r.c.err / bs).toFixed(2)]) },
    ];
    return o;
  }

  function pctOption() {
    const p = palette();
    const qs = ["50", "75", "90", "95", "99", "99.9"];
    const pick = filterLabel === "__ALL__" ? [total].concat(labels.slice(0, 7))
                                           : [total, labels.find((l) => l.name === filterLabel)].filter(Boolean);
    const colors = [p.trace, p.amber, p.green, p.red, "#7f9cf5", "#e879a6", "#a3e635", "#f97316"];
    const o = baseOpt();
    o.xAxis = { type: "category", data: qs.map((q) => "p" + q),
      axisLine: { lineStyle: { color: p.line } }, axisLabel: { color: p.muted } };
    o.yAxis.name = "ms";
    o.series = pick.map((l, i) => ({
      name: l.name === "TOTAL" ? "TOTAL" : l.name,
      type: "line", symbol: "circle", symbolSize: 5,
      lineStyle: { width: l.name === "TOTAL" ? 2.5 : 1.2, color: colors[i % colors.length] },
      itemStyle: { color: colors[i % colors.length] },
      data: qs.map((q) => l.stats.pct[q]),
    }));
    return o;
  }

  /* ---------------- scalability panel ---------------- */
  if (D.scalability.curve.length >= 2) {
    root.appendChild(el(`<div class="panel"><h2>Scalability — throughput vs concurrency</h2><div id="scChart" class="chart"></div></div>`));
  }
  function scOption() {
    const p = palette();
    const o = baseOpt();
    o.xAxis = { type: "value", name: "threads", axisLine: { lineStyle: { color: p.line } },
      splitLine: { show: false }, axisLabel: { color: p.muted } };
    o.yAxis.name = "req/s";
    o.series = [{
      name: "throughput", type: "line", symbol: "circle", symbolSize: 6, smooth: true,
      lineStyle: { width: 2, color: p.trace }, itemStyle: { color: p.trace },
      data: D.scalability.curve.map((c) => [c.threads, +c.throughput.toFixed(2)]),
      markPoint: D.scalability.knee ? {
        symbol: "pin", symbolSize: 42, itemStyle: { color: p.amber },
        label: { formatter: "knee", fontSize: 10, color: "#111" },
        data: [{ coord: [D.scalability.knee.threads, +D.scalability.knee.throughput.toFixed(2)] }],
      } : undefined,
    }];
    return o;
  }

  /* ---------------- transaction table ---------------- */
  const cols = [
    ["name", "Transaction"], ["n", "Samples"], ["errorRate", "Err %"], ["mean", "Mean"],
    ["p50", "p50"], ["p95", "p95"], ["p99", "p99"], ["max", "Max"],
    ["throughput", "req/s"], ["apdex", "Apdex"], ["sla", "SLA"],
  ];
  const slaByLabel = {};
  if (D.sla) {
    for (const r of D.sla.rules) {
      const cur = slaByLabel[r.label];
      const rank = { PASS: 0, DEGRADED: 1, FAIL: 2 };
      if (!cur || rank[r.status] > rank[cur]) slaByLabel[r.label] = r.status;
    }
  }
  let sortKey = "p95", sortDir = -1;
  let tableFilter = "";
  function rowVal(l, k) {
    switch (k) {
      case "name": return l.name;
      case "n": return l.stats.n;
      case "errorRate": return l.stats.errorRate;
      case "mean": return l.stats.mean;
      case "p50": return l.stats.pct["50"];
      case "p95": return l.stats.pct["95"];
      case "p99": return l.stats.pct["99"];
      case "max": return l.stats.max;
      case "throughput": return l.stats.throughput;
      case "apdex": return l.stats.apdex;
      case "sla": return slaByLabel[l.name] || "";
      default: return 0;
    }
  }
  const tablePanel = el(`<div class="panel"><h2>Transaction statistics</h2>
    <div class="table-toolbar">
      <input id="tableSearch" type="text" placeholder="Search transactions...">
      <button id="csvExport" type="button">Export CSV</button>
    </div>
    <div class="tablewrap"><table id="txTable"></table></div></div>`);
  root.appendChild(tablePanel);
  function renderTable() {
    let rows = labels.slice();
    if (tableFilter) rows = rows.filter((l) => l.name.toLowerCase().includes(tableFilter));
    rows.sort((a, b) => {
      const va = rowVal(a, sortKey), vb = rowVal(b, sortKey);
      return (va > vb ? 1 : va < vb ? -1 : 0) * sortDir;
    });
    const head = "<tr>" + cols.map(([k, t]) =>
      `<th data-k="${k}">${t}${k === sortKey ? ` <span class="dir">${sortDir > 0 ? "▲" : "▼"}</span>` : ""}</th>`).join("") + "</tr>";
    const body = rows.map((l) => {
      const st = l.stats;
      const tag = slaByLabel[l.name] ? `<span class="tag ${slaByLabel[l.name]}">${slaByLabel[l.name]}</span>` : "—";
      return `<tr>
        <td class="name" title="${esc(l.name)}">${esc(l.name)}</td>
        <td>${fmtN(st.n)}</td><td>${st.errorRate.toFixed(2)}</td><td>${Math.round(st.mean)}</td>
        <td>${st.pct["50"]}</td><td>${st.pct["95"]}</td><td>${st.pct["99"]}</td><td>${st.max}</td>
        <td>${fmt1(st.throughput)}</td><td>${st.apdex.toFixed(3)}</td><td>${tag}</td></tr>`;
    }).join("");
    const totRow = (() => {
      const st = total.stats;
      return `<tr style="font-weight:700">
        <td class="name">TOTAL</td><td>${fmtN(st.n)}</td><td>${st.errorRate.toFixed(2)}</td>
        <td>${Math.round(st.mean)}</td><td>${st.pct["50"]}</td><td>${st.pct["95"]}</td>
        <td>${st.pct["99"]}</td><td>${st.max}</td><td>${fmt1(st.throughput)}</td>
        <td>${st.apdex.toFixed(3)}</td><td>${slaByLabel["TOTAL"] ? `<span class="tag ${slaByLabel["TOTAL"]}">${slaByLabel["TOTAL"]}</span>` : "—"}</td></tr>`;
    })();
    document.getElementById("txTable").innerHTML = head + body + totRow;
    document.querySelectorAll("#txTable th").forEach((th) => th.addEventListener("click", () => {
      const k = th.dataset.k;
      if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = -1; }
      renderTable();
    }));
  }
  // CSV export
  function exportCsv() {
    const header = cols.map(([, t]) => t).join(",");
    const rows = labels.map((l) => cols.map(([k]) => {
      const v = rowVal(l, k);
      return typeof v === "string" && v.includes(",") ? `"${v}"` : v;
    }).join(","));
    const csv = [header, ...rows].join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "transactions.csv";
    a.click();
  }

  /* ---------------- SLA + errors ---------------- */
  if (D.sla) {
    const rules = D.sla.rules.map((r) => `<tr>
      <td class="name">${esc(r.label)}</td><td style="text-align:left">${esc(r.metric)} ${esc(r.op)} ${r.threshold}</td>
      <td>${fmt1(r.actual)}</td><td><span class="tag ${r.status}">${r.status}</span></td></tr>`).join("");
    root.appendChild(el(`<div class="panel"><h2>SLA evaluation</h2><div class="tablewrap"><table>
      <tr><th>Label</th><th style="text-align:left">Rule</th><th>Actual</th><th>Status</th></tr>${rules}</table></div></div>`));
  }

  if (D.errors.length) {
    const rows = D.errors.map((e) => `<tr>
      <td class="errsig" title="${esc(e.signature)}">${esc(e.signature)}</td>
      <td>${fmtN(e.count)}</td><td>${fmtT(e.firstTs)}</td><td>${fmtT(e.lastTs)}</td>
      <td>${e.threadsAtOnset}</td><td>${fmt1(e.throughputAtOnset)}</td>
      <td style="text-align:left">${esc(e.labels.join(", "))}</td></tr>`).join("");
    root.appendChild(el(`<div class="panel"><h2>Error clusters</h2><div class="tablewrap"><table>
      <tr><th style="text-align:left">Signature</th><th>Count</th><th>First</th><th>Last</th>
      <th>Threads @ onset</th><th>req/s @ onset</th><th style="text-align:left">Top labels</th></tr>${rows}</table></div></div>`));
  }

  root.appendChild(el(`<footer>generated by ${esc(D.meta.tool)} · ${esc(D.meta.generatedAt)} · schema ${esc(D.schema)}</footer>`));

  /* ---------------- bandwidth + threads charts ---------------- */
  function bwOption() {
    const p = palette();
    const rows = seriesFor(filterLabel);
    const bs = D.series.bucketMs / 1000;
    const o = baseOpt();
    o.series = [
      { name: "recv KB/s", type: "line", showSymbol: false, areaStyle: { opacity: 0.1 },
        lineStyle: { width: 1.5, color: p.trace }, itemStyle: { color: p.trace },
        data: lttb(rows.filter((r) => r.c).map((r) => [r.t, +(r.c.kb / bs).toFixed(2)]), MAX_POINTS) },
      { name: "sent KB/s", type: "line", showSymbol: false,
        lineStyle: { width: 1.5, color: p.amber }, itemStyle: { color: p.amber },
        data: lttb(rows.filter((r) => r.c && r.c.kbSent).map((r) => [r.t, +(r.c.kbSent / bs).toFixed(2)]), MAX_POINTS) },
    ];
    o.yAxis.name = "KB/s";
    return o;
  }
  function thOption() {
    const p = palette();
    const o = baseOpt();
    o.series = [{
      name: "active threads", type: "line", showSymbol: false, step: "middle",
      areaStyle: { opacity: 0.08 },
      lineStyle: { width: 2, color: p.trace }, itemStyle: { color: p.trace },
      data: lttb(buckets.map((b) => [b.t, b.threads]), MAX_POINTS),
    }];
    o.yAxis.name = "threads";
    return o;
  }

  /* ---------------- comparison panel (M5) ---------------- */
  if (comparison && comparison.labels && comparison.labels.length) {
    const cmpRows = comparison.labels.map((d) => {
      const deltaCls = (v) => v > 0.5 ? "delta-pos" : v < -0.5 ? "delta-neg" : "delta-neutral";
      return `<tr>
        <td class="name">${esc(d.label)}</td>
        <td>${d.baseN.toLocaleString()}</td><td>${d.candN.toLocaleString()}</td>
        <td>${Math.round(d.baseP95)}</td><td>${Math.round(d.candP95)}</td>
        <td class="${deltaCls(d.p95DeltaPct)}">${d.p95DeltaPct >= 0 ? "+" : ""}${d.p95DeltaPct.toFixed(1)}%</td>
        <td>${d.baseErrorRate.toFixed(2)}</td><td>${d.candErrorRate.toFixed(2)}</td>
        <td class="${deltaCls(d.errorRateDeltaPp)}">${d.errorRateDeltaPp >= 0 ? "+" : ""}${d.errorRateDeltaPp.toFixed(2)}pp</td>
        <td>${d.pValue < 0.001 ? "<0.001" : d.pValue.toFixed(3)}</td>
        <td>${d.cliffsDelta.toFixed(3)}</td>
        <td>${d.regression ? '<span class="regression-badge">REGRESSION</span>' : d.significant ? "sig" : "—"}</td>
      </tr>`;
    }).join("");
    root.appendChild(el(`<div class="panel"><h2>Baseline comparison ${comparison.hasRegression ? '— <span class="regression-badge">REGRESSION DETECTED</span>' : ""}</h2>
      <div class="tablewrap"><table>
        <tr><th>Label</th><th>Base N</th><th>Cand N</th><th>Base p95</th><th>Cand p95</th><th>Δ p95</th>
        <th>Base Err%</th><th>Cand Err%</th><th>Δ Err</th><th>p-value</th><th>Cliff's δ</th><th>Verdict</th></tr>
        ${cmpRows}</table></div></div>`));
  }

  /* ---------------- wire up ---------------- */
  document.documentElement.dataset.theme = theme;
  chart("rtChart", rtOption);
  chart("tpChart", tpOption);
  chart("pctChart", pctOption);
  chart("bwChart", bwOption);
  chart("thChart", thOption);
  if (document.getElementById("scChart")) chart("scChart", scOption);
  renderTable();

  document.getElementById("tableSearch").addEventListener("input", (e) => {
    tableFilter = e.target.value.toLowerCase();
    renderTable();
  });
  document.getElementById("csvExport").addEventListener("click", exportCsv);
  document.getElementById("labelSel").addEventListener("change", (e) => {
    filterLabel = e.target.value;
    charts.forEach(({ c, optFn }) => c.setOption(optFn(), true));
  });
  document.getElementById("themeBtn").addEventListener("click", (e) => {
    theme = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = theme;
    e.target.textContent = theme === "dark" ? "Switch to light" : "Switch to dark";
    charts.forEach(({ c, optFn }) => c.setOption(optFn(), true));
  });
  window.addEventListener("resize", () => charts.forEach(({ c }) => c.resize()));
})();
