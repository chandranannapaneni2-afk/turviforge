package io.forge.turviforge.jmeter;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * GUI for the TurviForge Collector listener (FR-604).
 * Appears in JMeter's Add → Listener menu as "TurviForge Collector".
 *
 * <p>Provides configuration fields for:
 * <ul>
 *   <li>Output directory</li>
 *   <li>SLA YAML file (optional)</li>
 *   <li>Baseline report-data.json (optional)</li>
 *   <li>APDEX thresholds</li>
 *   <li>Sub-sample inclusion toggle</li>
 * </ul>
 */
public class TurviForgeCollectorGui extends AbstractListenerGui {

    private static final long serialVersionUID = 1L;

    private final JTextField outputDirField = new JTextField(30);
    private final JTextField slaFileField = new JTextField(30);
    private final JTextField baselineFileField = new JTextField(30);
    private final JTextField apdexSatisfiedField = new JTextField("500", 6);
    private final JTextField apdexToleratedField = new JTextField("1500", 6);
    private final JCheckBox includeSubSamplesCheck = new JCheckBox("Include sub-samples", true);

    public TurviForgeCollectorGui() {
        super();
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // Output directory
        panel.add(createFileRow("Output directory:", outputDirField, true));
        panel.add(Box.createVerticalStrut(5));

        // SLA file
        panel.add(createFileRow("SLA file (YAML):", slaFileField, false));
        panel.add(Box.createVerticalStrut(5));

        // Baseline file
        panel.add(createFileRow("Baseline (JSON):", baselineFileField, false));
        panel.add(Box.createVerticalStrut(10));

        // APDEX thresholds
        JPanel apdexPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        apdexPanel.setBorder(BorderFactory.createTitledBorder("APDEX Thresholds (ms)"));
        apdexPanel.add(new JLabel("Satisfied:"));
        apdexPanel.add(apdexSatisfiedField);
        apdexPanel.add(Box.createHorizontalStrut(15));
        apdexPanel.add(new JLabel("Tolerated:"));
        apdexPanel.add(apdexToleratedField);
        panel.add(apdexPanel);
        panel.add(Box.createVerticalStrut(5));

        // Options
        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        optionsPanel.setBorder(BorderFactory.createTitledBorder("Options"));
        optionsPanel.add(includeSubSamplesCheck);
        panel.add(optionsPanel);

        add(makeTitlePanel(), BorderLayout.NORTH);
        add(panel, BorderLayout.CENTER);
    }

    private JPanel createFileRow(String label, JTextField field, boolean dirChooser) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> browse(field, dirChooser));
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private void browse(JTextField field, boolean dirChooser) {
        JFileChooser chooser = new JFileChooser();
        if (dirChooser) {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        } else {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        }
        String current = field.getText();
        if (current != null && !current.isBlank()) {
            chooser.setSelectedFile(new java.io.File(current));
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // --- TestElement binding ---

    @Override
    public String getStaticLabel() {
        return "TurviForge Collector";
    }

    @Override
    public String getLabelResource() {
        return getClass().getName();
    }

    @Override
    public TestElement createTestElement() {
        TurviForgeCollector collector = new TurviForgeCollector();
        modifyTestElement(collector);
        return collector;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        if (element instanceof TurviForgeCollector collector) {
            collector.setOutputDir(outputDirField.getText().trim());
            collector.setSlaFile(slaFileField.getText().trim());
            collector.setBaselineFile(baselineFileField.getText().trim());
            collector.setIncludeSubSamples(includeSubSamplesCheck.isSelected());
            try {
                collector.setApdexSatisfied(Integer.parseInt(apdexSatisfiedField.getText().trim()));
            } catch (NumberFormatException ignored) { }
            try {
                collector.setApdexTolerated(Integer.parseInt(apdexToleratedField.getText().trim()));
            } catch (NumberFormatException ignored) { }
        }
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (element instanceof TurviForgeCollector collector) {
            outputDirField.setText(collector.getOutputDir());
            slaFileField.setText(collector.getSlaFile());
            baselineFileField.setText(collector.getBaselineFile());
            includeSubSamplesCheck.setSelected(collector.isIncludeSubSamples());
            apdexSatisfiedField.setText(String.valueOf(collector.getApdexSatisfied()));
            apdexToleratedField.setText(String.valueOf(collector.getApdexTolerated()));
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        outputDirField.setText("");
        slaFileField.setText("");
        baselineFileField.setText("");
        includeSubSamplesCheck.setSelected(true);
        apdexSatisfiedField.setText("500");
        apdexToleratedField.setText("1500");
    }
}
