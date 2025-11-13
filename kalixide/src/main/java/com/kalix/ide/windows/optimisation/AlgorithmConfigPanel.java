package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;

/**
 * Panel for configuring optimization algorithm settings.
 * Provides algorithm selection, common parameters, and algorithm-specific parameters table.
 */
public class AlgorithmConfigPanel extends JPanel {

    private final JComboBox<String> algorithmCombo;
    private final JTextField terminationEvalsField;
    private final JTextField threadsField;
    private final JTextField randomSeedField;
    private final JTable paramsTable;
    private final AlgorithmParamsTableModel paramsTableModel;

    // Algorithm-specific default parameters
    private static final Map<String, Map<String, String>> ALGORITHM_DEFAULTS = Map.of(
        "DE", Map.of(
            "population_size", "50",
            "de_f", "0.8",
            "de_cr", "0.9"
        ),
        "SCE", Map.of(
            "complexes", "12"
        )
    );

    public AlgorithmConfigPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Algorithm"));

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Left column: Algorithm selection and common parameters
        JPanel leftPanel = new JPanel(new GridBagLayout());
        GridBagConstraints leftGbc = new GridBagConstraints();
        leftGbc.insets = new Insets(5, 5, 5, 5);
        leftGbc.anchor = GridBagConstraints.WEST;

        // Algorithm selection
        leftGbc.gridx = 0;
        leftGbc.gridy = 0;
        leftGbc.weightx = 0.0;
        leftPanel.add(new JLabel("Algorithm:"), leftGbc);

        leftGbc.gridx = 1;
        leftGbc.weightx = 1.0;
        leftGbc.fill = GridBagConstraints.HORIZONTAL;
        algorithmCombo = new JComboBox<>(new String[]{"DE", "SCE"});
        algorithmCombo.setSelectedItem("SCE"); // Default to SCE
        algorithmCombo.addActionListener(e -> updateAlgorithmSpecificParams());
        leftPanel.add(algorithmCombo, leftGbc);

        // Termination Evaluations
        leftGbc.gridx = 0;
        leftGbc.gridy = 1;
        leftGbc.weightx = 0.0;
        leftGbc.fill = GridBagConstraints.NONE;
        leftPanel.add(new JLabel("Termination Evaluations:"), leftGbc);

        leftGbc.gridx = 1;
        leftGbc.weightx = 1.0;
        leftGbc.fill = GridBagConstraints.HORIZONTAL;
        terminationEvalsField = new JTextField("10000");
        leftPanel.add(terminationEvalsField, leftGbc);

        // Threads
        leftGbc.gridx = 0;
        leftGbc.gridy = 2;
        leftGbc.weightx = 0.0;
        leftGbc.fill = GridBagConstraints.NONE;
        leftPanel.add(new JLabel("Threads:"), leftGbc);

        leftGbc.gridx = 1;
        leftGbc.weightx = 1.0;
        leftGbc.fill = GridBagConstraints.HORIZONTAL;
        threadsField = new JTextField("12");
        leftPanel.add(threadsField, leftGbc);

        // Random Seed
        leftGbc.gridx = 0;
        leftGbc.gridy = 3;
        leftGbc.weightx = 0.0;
        leftGbc.fill = GridBagConstraints.NONE;
        leftPanel.add(new JLabel("Random Seed:"), leftGbc);

        leftGbc.gridx = 1;
        leftGbc.weightx = 1.0;
        leftGbc.fill = GridBagConstraints.HORIZONTAL;
        randomSeedField = new JTextField("");
        leftPanel.add(randomSeedField, leftGbc);

        // Right column: Algorithm-specific parameters table
        paramsTableModel = new AlgorithmParamsTableModel();
        paramsTable = new JTable(paramsTableModel);
        paramsTable.setRowHeight(25);
        paramsTable.setShowGrid(true);
        paramsTable.setGridColor(new Color(220, 220, 220));
        JScrollPane tableScrollPane = new JScrollPane(paramsTable);
        tableScrollPane.setPreferredSize(new Dimension(250, 120));

        // Add left and right panels to main panel
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.5;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        mainPanel.add(leftPanel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.5;
        mainPanel.add(tableScrollPane, gbc);

        add(mainPanel, BorderLayout.CENTER);

        // Initialize with DE defaults
        updateAlgorithmSpecificParams();
    }

    /**
     * Updates the algorithm-specific parameters table based on selected algorithm.
     */
    private void updateAlgorithmSpecificParams() {
        String algorithm = (String) algorithmCombo.getSelectedItem();
        Map<String, String> defaults = ALGORITHM_DEFAULTS.get(algorithm);
        if (defaults != null) {
            paramsTableModel.setParameters(defaults);
        }
    }

    /**
     * Gets the selected algorithm.
     */
    public String getAlgorithm() {
        return (String) algorithmCombo.getSelectedItem();
    }

    /**
     * Sets the selected algorithm.
     */
    public void setAlgorithm(String algorithm) {
        algorithmCombo.setSelectedItem(algorithm);
    }

    /**
     * Gets the termination evaluations.
     */
    public String getTerminationEvaluations() {
        return terminationEvalsField.getText().trim();
    }

    /**
     * Sets the termination evaluations.
     */
    public void setTerminationEvaluations(String value) {
        terminationEvalsField.setText(value);
    }

    /**
     * Gets the number of threads.
     */
    public String getThreads() {
        return threadsField.getText().trim();
    }

    /**
     * Sets the number of threads.
     */
    public void setThreads(String value) {
        threadsField.setText(value);
    }

    /**
     * Gets the random seed.
     */
    public String getRandomSeed() {
        return randomSeedField.getText().trim();
    }

    /**
     * Sets the random seed.
     */
    public void setRandomSeed(String value) {
        randomSeedField.setText(value);
    }

    /**
     * Gets all algorithm-specific parameters as a map.
     */
    public Map<String, String> getAlgorithmSpecificParams() {
        return paramsTableModel.getParametersAsMap();
    }

    /**
     * Validates that all fields contain valid values.
     */
    public boolean validateInputs() {
        // Validate termination evaluations is a positive integer
        try {
            int termEvals = Integer.parseInt(getTerminationEvaluations());
            if (termEvals <= 0) {
                JOptionPane.showMessageDialog(this,
                    "Termination Evaluations must be a positive integer",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
                return false;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Termination Evaluations must be a valid integer",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // Validate threads is a positive integer
        try {
            int threads = Integer.parseInt(getThreads());
            if (threads <= 0) {
                JOptionPane.showMessageDialog(this,
                    "Threads must be a positive integer",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
                return false;
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this,
                "Threads must be a valid integer",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }

        return true;
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
            // Only value column (column 1) is editable
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
