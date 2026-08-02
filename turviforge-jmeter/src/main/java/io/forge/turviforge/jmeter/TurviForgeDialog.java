package io.forge.turviforge.jmeter;

import io.forge.turviforge.core.HtmlAssembler;
import io.forge.turviforge.core.JsonEmitter;
import io.forge.turviforge.core.JsonLoader;
import io.forge.turviforge.core.JtlCsvParser;
import io.forge.turviforge.core.JtlMerger;
import io.forge.turviforge.core.ComparisonEngine;
import io.forge.turviforge.core.MetricsPipeline;
import io.forge.turviforge.core.ReportModel;
import io.forge.turviforge.core.SlaEngine;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generation dialog (FR-601). All parsing/aggregation runs on a SwingWorker,
 * never on the EDT. Supports multi-file selection for distributed merges,
 * baseline comparison, cancellation, and progress indication.
 */
public final class TurviForgeDialog {

    private static volatile boolean cancelled = false;

    public static void open() {
        JDialog d = new JDialog((java.awt.Frame) null, "TurviForge — Generate Advanced Report", true);
        JTextField jtl = new JTextField(38);
        JTextField out = new JTextField(38);
        JTextField sla = new JTextField(38);
        JTextField baseline = new JTextField(38);
        JCheckBox junit = new JCheckBox("Write junit.xml (one testcase per SLA assertion)");
        JCheckBox excludeSubs = new JCheckBox("Exclude sub-samples from statistics");
        JProgressBar bar = new JProgressBar();
        bar.setVisible(false);
        cancelled = false;

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        form.add(row("Results file(s) (.jtl / .jtl.gz) — multi-select for distributed:", jtl, d, JFileChooser.FILES_ONLY, true));
        form.add(row("Output directory:", out, d, JFileChooser.DIRECTORIES_ONLY, false));
        form.add(row("SLA file (optional, .yaml):", sla, d, JFileChooser.FILES_ONLY, false));
        form.add(row("Baseline report-data.json (optional, for comparison):", baseline, d, JFileChooser.FILES_ONLY, false));
        form.add(junit);
        form.add(excludeSubs);
        form.add(bar);

        JButton go = new JButton("Generate report");
        JButton cancel = new JButton("Cancel");
        cancel.setEnabled(false);

        cancel.addActionListener(e -> cancelled = true);

        go.addActionListener(e -> {
            go.setEnabled(false);
            cancel.setEnabled(true);
            bar.setVisible(true);
            bar.setIndeterminate(true);
            cancelled = false;

            new SwingWorker<Path, String>() {
                @Override protected Path doInBackground() throws Exception {
                    // Parse multi-file input (comma-separated or from multi-select)
                    List<Path> jtlFiles = new ArrayList<>();
                    for (String p : jtl.getText().trim().split("[,;]")) {
                        if (!p.isBlank()) jtlFiles.add(Path.of(p.trim()));
                    }
                    if (jtlFiles.isEmpty()) throw new IllegalArgumentException("No JTL file specified");

                    Path outDir = Path.of(out.getText().trim());
                    MetricsPipeline.Config cfg = new MetricsPipeline.Config();
                    cfg.includeSubSamples = !excludeSubs.isSelected();

                    MetricsPipeline pipeline = new MetricsPipeline(cfg);
                    JtlMerger merger = new JtlMerger();
                    JtlMerger.MergeResult mergeResult = merger.merge(jtlFiles, ev -> {
                        if (!cancelled) pipeline.accept(ev);
                    });

                    if (cancelled) throw new InterruptedException("Cancelled by user");

                    JtlCsvParser.Result syntheticParse = new JtlCsvParser.Result();
                    syntheticParse.total = mergeResult.total;
                    syntheticParse.malformed = mergeResult.malformed;
                    ReportModel model = pipeline.finish(syntheticParse);
                    model.title = jtlFiles.get(0).getFileName().toString();
                    mergeResult.sources.forEach(s -> model.sources.add(s));

                    if (!sla.getText().isBlank()) {
                        SlaEngine engine = SlaEngine.load(Path.of(sla.getText().trim()));
                        model.verdict = engine.evaluate(model);
                    }

                    // Baseline comparison
                    ComparisonEngine.ComparisonResult comparison = null;
                    if (!baseline.getText().isBlank()) {
                        ReportModel baseModel = JsonLoader.load(Path.of(baseline.getText().trim()));
                        ComparisonEngine compEngine = new ComparisonEngine(new ComparisonEngine.Config());
                        comparison = compEngine.compare(baseModel, model);
                    }

                    Files.createDirectories(outDir);
                    String json = new JsonEmitter().emit(model, comparison);
                    Files.writeString(outDir.resolve("report-data.json"), json, StandardCharsets.UTF_8);
                    Files.writeString(outDir.resolve("report.html"),
                            new HtmlAssembler().assemble(json, model.title), StandardCharsets.UTF_8);
                    if (junit.isSelected() && model.verdict != null) {
                        Files.writeString(outDir.resolve("junit.xml"),
                                SlaEngine.junitXml(model.verdict, model.title), StandardCharsets.UTF_8);
                    }
                    return outDir.resolve("report.html");
                }

                @Override protected void done() {
                    bar.setVisible(false);
                    go.setEnabled(true);
                    cancel.setEnabled(false);
                    try {
                        Path html = get();
                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(html.toUri());
                        d.dispose();
                    } catch (java.util.concurrent.CancellationException ce) {
                        // cancelled
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof InterruptedException) {
                            // user cancelled — no error dialog
                        } else {
                            JOptionPane.showMessageDialog(d,
                                    "Report generation failed:\n" + cause,
                                    "TurviForge", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }.execute();
        });

        JPanel south = new JPanel();
        south.add(go);
        south.add(cancel);
        d.add(form, BorderLayout.CENTER);
        d.add(south, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(null);
        d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cancelled = true;
                d.dispose();
            }
        });
        d.setVisible(true);
    }

    private static JPanel row(String label, JTextField field, JDialog owner, int mode, boolean multiSelect) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(mode);
            fc.setMultiSelectionEnabled(multiSelect);
            if (fc.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                if (multiSelect && fc.getSelectedFiles().length > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (java.io.File f : fc.getSelectedFiles()) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(f.getAbsolutePath());
                    }
                    field.setText(sb.toString());
                } else {
                    field.setText(fc.getSelectedFile().getAbsolutePath());
                }
            }
        });
        p.add(browse, BorderLayout.EAST);
        return p;
    }

    private TurviForgeDialog() { }
}
