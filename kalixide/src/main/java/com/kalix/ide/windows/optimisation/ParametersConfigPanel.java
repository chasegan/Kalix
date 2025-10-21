package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel for configuring parameters to optimize.
 * Provides a fixed list of parameter names with editable expression fields.
 * Blank expressions indicate the parameter should not be optimized.
 */
public class ParametersConfigPanel extends JPanel {

    private final JTable paramsTable;
    private final OptimizationParamsTableModel paramsTableModel;
    private final JButton generateButton;
    private final JButton clearButton;

    // Hard-coded list of calibratable parameters
    private static final String[] PARAMETER_NAMES = {
        "node.mygr4jnode.x1",
        "node.mygr4jnode.x2",
        "node.mygr4jnode.x3",
        "node.mygr4jnode.x4"
    };

    public ParametersConfigPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Model Parameters"));

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Button panel (above table)
        JPanel buttonPanel = new JPanel(new BorderLayout());
        JPanel buttonsLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        generateButton = new JButton("Generate Expressions for Selected");
        generateButton.setToolTipText("Generate calibration expressions for selected parameters");
        generateButton.addActionListener(e -> generateExpressionsForSelected());
        buttonsLeft.add(generateButton);

        clearButton = new JButton("Clear Expressions for Selected");
        clearButton.setToolTipText("Clear expressions for selected rows");
        clearButton.addActionListener(e -> clearSelectedExpressions());
        buttonsLeft.add(clearButton);

        buttonPanel.add(buttonsLeft, BorderLayout.WEST);
        mainPanel.add(buttonPanel, BorderLayout.NORTH);

        // Table with parameter names and expressions
        paramsTableModel = new OptimizationParamsTableModel(PARAMETER_NAMES);
        paramsTable = new JTable(paramsTableModel);
        paramsTable.setRowHeight(25);
        paramsTable.setShowGrid(true);
        paramsTable.setGridColor(new Color(220, 220, 220));
        paramsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane tableScrollPane = new JScrollPane(paramsTable);
        tableScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        mainPanel.add(tableScrollPane, BorderLayout.CENTER);

        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Pattern to match gene indices in expressions: g(number)
     * Negative lookbehind ensures 'g' is not preceded by a word character or dot
     */
    private static final Pattern GENE_INDEX_PATTERN = Pattern.compile("(?<![.\\w])g\\((\\d+)\\)");

    /**
     * Extracts all used gene indices from all expressions in the table.
     * Parses expressions to find all g(n) patterns and collects the numbers.
     *
     * @return Set of all gene indices currently in use
     */
    private Set<Integer> getUsedGeneIndices() {
        Set<Integer> usedIndices = new HashSet<>();

        for (int i = 0; i < paramsTableModel.getRowCount(); i++) {
            String expression = paramsTableModel.getExpressionAt(i);
            if (expression != null && !expression.isEmpty()) {
                Matcher matcher = GENE_INDEX_PATTERN.matcher(expression);
                while (matcher.find()) {
                    try {
                        int geneIndex = Integer.parseInt(matcher.group(1));
                        usedIndices.add(geneIndex);
                    } catch (NumberFormatException e) {
                        // Skip invalid numbers
                    }
                }
            }
        }

        return usedIndices;
    }

    /**
     * Finds the next unused gene index starting from 1.
     * Searches for the first positive integer not in the used set.
     *
     * @param usedIndices Set of currently used gene indices
     * @return The first unused gene index (starting from 1)
     */
    private int getNextUnusedGeneIndex(Set<Integer> usedIndices) {
        int index = 1;
        while (usedIndices.contains(index)) {
            index++;
        }
        return index;
    }

    /**
     * Generates default expressions for selected rows that currently have blank expressions.
     * Uses ParameterExpressionLibrary to generate type-specific lin_range expressions.
     * Gene indices are assigned using the first unused integer starting from 1.
     */
    private void generateExpressionsForSelected() {
        int[] selectedRows = paramsTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                "Please select one or more parameters",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Get all currently used gene indices
        Set<Integer> usedIndices = getUsedGeneIndices();
        StringBuilder unrecognizedParams = new StringBuilder();

        for (int row : selectedRows) {
            String currentExpression = paramsTableModel.getExpressionAt(row);
            if (currentExpression.isEmpty()) {
                String paramName = paramsTableModel.getParameterNameAt(row);

                try {
                    // Find next unused gene index
                    int geneIndex = getNextUnusedGeneIndex(usedIndices);

                    // Generate expression using the library
                    String expression = ParameterExpressionLibrary.generateExpression(paramName, geneIndex);
                    paramsTableModel.setExpressionAt(row, expression);

                    // Mark this index as used
                    usedIndices.add(geneIndex);
                } catch (ParameterExpressionLibrary.UnrecognizedParameterTypeException e) {
                    // Collect unrecognized parameters to show in a single dialog
                    String type = ParameterExpressionLibrary.detectParameterType(paramName);
                    if (type == null) {
                        type = "unknown";
                    }
                    if (unrecognizedParams.length() > 0) {
                        unrecognizedParams.append("\n");
                    }
                    unrecognizedParams.append("Parameter: ").append(paramName)
                                      .append(" (type: ").append(type).append(")");
                }
            }
        }

        // Show dialog if any parameters were unrecognized
        if (unrecognizedParams.length() > 0) {
            JOptionPane.showMessageDialog(this,
                "Could not recognize parameter types:\n\n" + unrecognizedParams.toString() +
                "\n\nPlease specify expressions manually for these parameters.",
                "Unrecognized Parameter Types",
                JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Clears expressions for selected rows.
     */
    private void clearSelectedExpressions() {
        int[] selectedRows = paramsTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                "Please select one or more parameters",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        for (int row : selectedRows) {
            paramsTableModel.setExpressionAt(row, "");
        }
    }

    /**
     * Gets all parameters with non-blank expressions as a map.
     * Only parameters being optimized are included.
     */
    public Map<String, String> getOptimizationParameters() {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < paramsTableModel.getRowCount(); i++) {
            String paramName = paramsTableModel.getParameterNameAt(i);
            String expression = paramsTableModel.getExpressionAt(i);
            if (!expression.isEmpty()) {
                result.put(paramName, expression);
            }
        }
        return result;
    }

    /**
     * Sets the expression for a specific parameter.
     */
    public void setParameterExpression(String paramName, String expression) {
        paramsTableModel.setExpression(paramName, expression);
    }

    /**
     * Clears all parameter expressions.
     */
    public void clearAllExpressions() {
        for (int i = 0; i < paramsTableModel.getRowCount(); i++) {
            paramsTableModel.setExpressionAt(i, "");
        }
    }

    /**
     * Validates parameter inputs (currently no validation needed).
     */
    public boolean validateInputs() {
        // Check if at least one parameter has an expression
        if (getOptimizationParameters().isEmpty()) {
            int result = JOptionPane.showConfirmDialog(this,
                "No parameters are configured for optimization.\nContinue anyway?",
                "Warning",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
            return result == JOptionPane.YES_OPTION;
        }
        return true;
    }

    /**
     * Table model for optimization parameters.
     * First column (parameter name) is non-editable and fixed.
     * Second column (expression) is editable.
     * Blank expressions mean the parameter is not being optimized.
     */
    private static class OptimizationParamsTableModel extends AbstractTableModel {
        private final String[] parameterNames;
        private final String[] expressions;
        private final String[] columnNames = {"Parameter", "Expression"};

        public OptimizationParamsTableModel(String[] parameterNames) {
            this.parameterNames = parameterNames.clone();
            this.expressions = new String[parameterNames.length];
            Arrays.fill(expressions, "");
        }

        public String getParameterNameAt(int row) {
            return parameterNames[row];
        }

        public String getExpressionAt(int row) {
            return expressions[row];
        }

        public void setExpressionAt(int row, String expression) {
            expressions[row] = expression;
            fireTableCellUpdated(row, 1);
        }

        public void setExpression(String paramName, String expression) {
            for (int i = 0; i < parameterNames.length; i++) {
                if (parameterNames[i].equals(paramName)) {
                    setExpressionAt(i, expression);
                    break;
                }
            }
        }

        @Override
        public int getRowCount() {
            return parameterNames.length;
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
                return parameterNames[rowIndex];
            } else {
                return expressions[rowIndex];
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Only expression column (column 1) is editable
            return columnIndex == 1;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 1) {
                expressions[rowIndex] = aValue.toString();
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}
