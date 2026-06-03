package com.kalix.ide.windows.optimisation;

import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;

/**
 * Panel for configuring optimization algorithm settings.
 *
 * Compact layout: a single row with algorithm selector, termination evaluations,
 * and a Settings button that opens a modal dialog for the less-frequently-edited
 * fields (threads, random seed, algorithm-specific metaparameters).
 */
public class AlgorithmConfigPanel extends JPanel {

    private final JComboBox<String> algorithmCombo;
    private final JTextField terminationEvalsField;

    // State held outside the visible row — edited via the Settings dialog
    private String threads = "12";
    private String randomSeed = "";
    private final Map<String, String> algorithmSpecificParams = new LinkedHashMap<>();

    // Algorithm-specific default parameters
    private static final Map<String, Map<String, String>> ALGORITHM_DEFAULTS = Map.of(
        "DE", new LinkedHashMap<>(Map.of(
            "population_size", "50",
            "de_f", "0.8",
            "de_cr", "0.9"
        )),
        "SCE", new LinkedHashMap<>(Map.of(
            "complexes", "12"
        ))
    );

    public AlgorithmConfigPanel() {
        setLayout(new BorderLayout());
        // No TitledBorder — this is a single-row strip that sits at the top of the Config tab.
        // The labels on the row make its purpose self-evident.

        JPanel row = new JPanel(new GridBagLayout());
        row.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 5, 0, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridy = 0;

        gbc.gridx = 0;
        gbc.weightx = 0.0;
        row.add(new JLabel("Algorithm:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.0;
        gbc.insets = new Insets(0, 5, 0, 2);
        algorithmCombo = new JComboBox<>(new String[]{"DE", "SCE"});
        algorithmCombo.setSelectedItem("SCE");
        algorithmCombo.addActionListener(e -> onAlgorithmChanged());
        row.add(algorithmCombo, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.insets = new Insets(0, 2, 0, 5);
        JButton settingsButton = new JButton(FontIcon.of(FontAwesomeSolid.COG, 14));
        settingsButton.setToolTipText("Algorithm settings (threads, random seed, metaparameters)");
        settingsButton.addActionListener(e -> openSettingsDialog());
        row.add(settingsButton, gbc);

        gbc.gridx = 3;
        gbc.weightx = 0.0;
        gbc.insets = new Insets(0, 20, 0, 5);
        row.add(new JLabel("Evaluations:"), gbc);

        gbc.gridx = 4;
        gbc.weightx = 0.4;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 5, 0, 5);
        terminationEvalsField = new JTextField("60000", 8);
        row.add(terminationEvalsField, gbc);

        // Right-side filler so the row doesn't centre
        gbc.gridx = 5;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);

        add(row, BorderLayout.NORTH);

        // Initialize algorithm-specific params for the default selection
        onAlgorithmChanged();
    }

    /** Refresh the algorithm-specific params when the algorithm changes. */
    private void onAlgorithmChanged() {
        String algorithm = (String) algorithmCombo.getSelectedItem();
        Map<String, String> defaults = ALGORITHM_DEFAULTS.get(algorithm);
        algorithmSpecificParams.clear();
        if (defaults != null) {
            algorithmSpecificParams.putAll(defaults);
        }
    }

    /** Open the modal Settings dialog. */
    private void openSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog(
            SwingUtilities.getWindowAncestor(this),
            (String) algorithmCombo.getSelectedItem(),
            threads,
            randomSeed,
            algorithmSpecificParams
        );
        dialog.setVisible(true);
        if (dialog.wasAccepted()) {
            threads = dialog.getThreadsValue();
            randomSeed = dialog.getRandomSeedValue();
            algorithmSpecificParams.clear();
            algorithmSpecificParams.putAll(dialog.getAlgorithmParams());
        }
    }

    public String getAlgorithm() {
        return (String) algorithmCombo.getSelectedItem();
    }

    public void setAlgorithm(String algorithm) {
        algorithmCombo.setSelectedItem(algorithm);
    }

    public String getTerminationEvaluations() {
        return terminationEvalsField.getText().trim();
    }

    public void setTerminationEvaluations(String value) {
        terminationEvalsField.setText(value);
    }

    public String getThreads() {
        return threads;
    }

    public void setThreads(String value) {
        this.threads = value != null ? value.trim() : "";
    }

    public String getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(String value) {
        this.randomSeed = value != null ? value.trim() : "";
    }

    public Map<String, String> getAlgorithmSpecificParams() {
        return new LinkedHashMap<>(algorithmSpecificParams);
    }

    /**
     * Replaces the algorithm-specific metaparameters.
     *
     * <p>Callers that are also restoring the algorithm selection should call
     * {@link #setAlgorithm(String)} first, since changing the algorithm resets
     * these parameters to that algorithm's defaults.</p>
     *
     * @param params the metaparameters to apply
     */
    public void setAlgorithmSpecificParams(Map<String, String> params) {
        algorithmSpecificParams.clear();
        if (params != null) {
            algorithmSpecificParams.putAll(params);
        }
    }

    public boolean validateInputs() {
        try {
            int termEvals = Integer.parseInt(getTerminationEvaluations());
            if (termEvals <= 0) {
                JOptionPane.showMessageDialog(this,
                    "Evaluations must be a positive integer",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
                return false;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Evaluations must be a valid integer",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }

        try {
            int t = Integer.parseInt(threads);
            if (t <= 0) {
                JOptionPane.showMessageDialog(this,
                    "Threads (in Settings) must be a positive integer",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
                return false;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Threads (in Settings) must be a valid integer",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    /** Modal dialog for the lesser-edited algorithm settings. */
    private static class SettingsDialog extends JDialog {
        private final JTextField threadsField;
        private final JTextField randomSeedField;
        private final AlgorithmParamsTableModel paramsTableModel;
        private boolean accepted = false;

        SettingsDialog(Window owner, String algorithm, String threads, String randomSeed,
                       Map<String, String> algorithmParams) {
            super(owner, "Algorithm Settings — " + algorithm, ModalityType.APPLICATION_MODAL);
            setLayout(new BorderLayout());

            JPanel main = new JPanel(new GridBagLayout());
            main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            gbc.gridx = 0; gbc.gridy = 0;
            main.add(new JLabel("Threads:"), gbc);
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            threadsField = new JTextField(threads, 10);
            main.add(threadsField, gbc);

            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            main.add(new JLabel("Random seed:"), gbc);
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            randomSeedField = new JTextField(randomSeed, 10);
            randomSeedField.setToolTipText("Leave blank for a non-deterministic seed");
            main.add(randomSeedField, gbc);

            gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
            gbc.gridwidth = 2;
            main.add(new JLabel("Other:"), gbc);

            paramsTableModel = new AlgorithmParamsTableModel();
            paramsTableModel.setParameters(algorithmParams);
            JTable paramsTable = new JTable(paramsTableModel);
            paramsTable.setRowHeight(25);
            paramsTable.setShowGrid(true);
            paramsTable.setGridColor(new Color(220, 220, 220));
            paramsTable.setPreferredScrollableViewportSize(
                new Dimension(280, paramsTable.getRowHeight() * 4));
            JScrollPane tableScrollPane = new JScrollPane(paramsTable);

            gbc.gridx = 0; gbc.gridy = 3;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            gbc.fill = GridBagConstraints.BOTH;
            gbc.gridwidth = 2;
            main.add(tableScrollPane, gbc);

            add(main, BorderLayout.CENTER);

            // OK / Cancel
            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
            JButton okButton = new JButton("OK");
            okButton.addActionListener(e -> {
                if (paramsTable.isEditing()) {
                    paramsTable.getCellEditor().stopCellEditing();
                }
                accepted = true;
                dispose();
            });
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> dispose());
            buttonRow.add(okButton);
            buttonRow.add(cancelButton);
            add(buttonRow, BorderLayout.SOUTH);

            getRootPane().setDefaultButton(okButton);
            pack();
            setLocationRelativeTo(owner);
        }

        boolean wasAccepted() { return accepted; }
        String getThreadsValue() { return threadsField.getText().trim(); }
        String getRandomSeedValue() { return randomSeedField.getText().trim(); }
        Map<String, String> getAlgorithmParams() { return paramsTableModel.getParametersAsMap(); }
    }

    /**
     * Table model for algorithm-specific parameters.
     * First column (parameter name) is non-editable, second column (value) is editable.
     */
    private static class AlgorithmParamsTableModel extends AbstractTableModel {
        private final java.util.List<String> paramNames = new ArrayList<>();
        private final java.util.List<String> paramValues = new ArrayList<>();
        private final String[] columnNames = {"Metaparameter", "Value"};

        public void setParameters(Map<String, String> params) {
            paramNames.clear();
            paramValues.clear();
            paramNames.addAll(params.keySet());
            paramValues.addAll(params.values());
            fireTableDataChanged();
        }

        public Map<String, String> getParametersAsMap() {
            Map<String, String> result = new LinkedHashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                result.put(paramNames.get(i), paramValues.get(i));
            }
            return result;
        }

        @Override
        public int getRowCount() {
            return paramNames.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                return paramNames.get(rowIndex);
            } else {
                return paramValues.get(rowIndex);
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 1) {
                paramValues.set(rowIndex, aValue.toString());
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}
