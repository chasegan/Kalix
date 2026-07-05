package com.kalix.ide.preferences.ui;

import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.Insets;

/**
 * Data and visualization preferences page: export precision, FlowViz display
 * options, and the STDIO wire format.
 *
 * <p>(Formerly the misnamed {@code CompressionPreferencePanel}.)
 */
public class DataVisualizationPreferencePage extends AbstractPreferencePage {

    private final Runnable onFlowVizPreferencesChanged;

    private JCheckBox precision64CheckBox;
    private JCheckBox showCoordinatesCheckBox;
    private JCheckBox autoYModeCheckBox;
    private JTextField logScaleMinField;
    private JComboBox<String> stdioFormatComboBox;

    /**
     * @param onFlowVizPreferencesChanged notified after any FlowViz-relevant
     *                                    preference changes, so open windows can reload
     */
    public DataVisualizationPreferencePage(Runnable onFlowVizPreferencesChanged) {
        super("Data & Visualization");
        this.onFlowVizPreferencesChanged = onFlowVizPreferencesChanged;
        initializePanel();
    }

    @Override
    public String id() {
        return "data-visualization";
    }

    @Override
    public String treePath() {
        return "Run Management/Data & Visualization";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // 64-bit precision setting
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = GridBagConstraints.REMAINDER; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        precision64CheckBox = new JCheckBox("Use 64-bit precision for data export");
        precision64CheckBox.setToolTipText("Higher accuracy but larger file sizes; 32-bit is sufficient for most applications");
        precision64CheckBox.setSelected(PreferenceKeys.FLOWVIZ_PRECISION64.get());
        precision64CheckBox.addActionListener(e -> {
            PreferenceKeys.FLOWVIZ_PRECISION64.set(precision64CheckBox.isSelected());

            // Notify callback to update FlowViz windows
            onFlowVizPreferencesChanged.run();
        });
        formPanel.add(precision64CheckBox, gbc);

        // Show coordinates setting
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = GridBagConstraints.REMAINDER; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        showCoordinatesCheckBox = new JCheckBox("Show coordinates in FlowViz");
        showCoordinatesCheckBox.setToolTipText("Display current cursor position in FlowViz charts");
        showCoordinatesCheckBox.setSelected(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES.get());
        showCoordinatesCheckBox.addActionListener(e -> {
            PreferenceKeys.FLOWVIZ_SHOW_COORDINATES.set(showCoordinatesCheckBox.isSelected());

            // Notify callback to update FlowViz windows
            onFlowVizPreferencesChanged.run();
        });
        formPanel.add(showCoordinatesCheckBox, gbc);

        // Auto-Y mode setting
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = GridBagConstraints.REMAINDER; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        autoYModeCheckBox = new JCheckBox("Enable Auto-Y mode in FlowViz");
        autoYModeCheckBox.setToolTipText("Automatically adjust Y-axis scaling in FlowViz charts");
        autoYModeCheckBox.setSelected(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE.get());
        autoYModeCheckBox.addActionListener(e -> {
            PreferenceKeys.FLOWVIZ_AUTO_Y_MODE.set(autoYModeCheckBox.isSelected());

            // Notify callback to update FlowViz windows
            onFlowVizPreferencesChanged.run();
        });
        formPanel.add(autoYModeCheckBox, gbc);

        // Log scale minimum threshold setting
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Log scale auto-zoom minimum:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        logScaleMinField = new JTextField(String.valueOf(
            PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD.get()), 10);
        logScaleMinField.setToolTipText("Minimum Y value for log scale auto-zoom (prevents excessive zoom-out from tiny values)");
        commitOnFocusLostAndClose(logScaleMinField, this::saveLogScaleMinimum);
        formPanel.add(logScaleMinField, gbc);

        // STDIO data format setting (pixie vs csv for get_result responses)
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("STDIO data format:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        stdioFormatComboBox = new JComboBox<>(new String[]{"pixie", "csv"});
        stdioFormatComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("pixie".equals(value)) {
                    setText("Compressed (pixie, recommended)");
                } else if ("csv".equals(value)) {
                    setText("Plain text (csv, debug)");
                }
                return this;
            }
        });
        stdioFormatComboBox.setToolTipText(
            "Wire format for timeseries results from kalixcli. 'pixie' uses Gorilla compression "
            + "(smaller, faster); 'csv' is human-readable plain text (larger, slower).");
        stdioFormatComboBox.setSelectedItem(
            PreferenceKeys.STDIO_DATA_FORMAT.get());
        stdioFormatComboBox.addActionListener(e -> {
            String selected = (String) stdioFormatComboBox.getSelectedItem();
            if (selected != null) {
                PreferenceKeys.STDIO_DATA_FORMAT.set(selected);
            }
        });
        formPanel.add(stdioFormatComboBox, gbc);

        add(formPanel, BorderLayout.NORTH);
    }

    private void saveLogScaleMinimum() {
        try {
            double value = Double.parseDouble(logScaleMinField.getText().trim());
            if (value > 0 && value != PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD.get()) {
                PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD.set(value);

                // Notify callback to update FlowViz windows
                onFlowVizPreferencesChanged.run();
            }
        } catch (NumberFormatException ex) {
            // Invalid input - don't save
        }
    }
}
