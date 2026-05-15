package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel for configuring the optimisation objective as a list of terms plus a composite expression.
 *
 * Each row is one term: (name, simulated series, observed file, observed series, statistic).
 * Term values are combined via the objective expression at the bottom of the panel.
 */
public class ObjectiveConfigPanel extends JPanel {

    /** Statistic names accepted by the Kalix backend (lower-better losses in [0, ∞)). */
    public static final String[] STATISTIC_NAMES = {
        "ONE_MINUS_NSE",
        "ONE_MINUS_LNSE",
        "ONE_MINUS_KGE",
        "ONE_MINUS_PEARS_R",
        "RMSE",
        "MAE",
        "SDEB",
        "ABS_PBIAS"
    };

    private static final String DEFAULT_STATISTIC = "SDEB";

    /** Identifier pattern used to find variable references in the objective expression. */
    private static final Pattern IDENT_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Reserved identifiers that should not be flagged as unknown variables in the expression. */
    private static final Set<String> RESERVED_IDENTIFIERS = new HashSet<>(java.util.Arrays.asList(
        // Built-in math functions exposed by Kalix's expression parser
        "abs", "min", "max", "sqrt", "exp", "log", "ln", "pow", "sin", "cos", "tan", "if"
    ));

    private final java.util.function.Supplier<java.io.File> workingDirectorySupplier;

    private final TermsTableModel termsTableModel;
    private final JTable termsTable;
    private final JButton addButton;
    private final JButton removeButton;
    private final JButton browseButton;
    private final JTextField expressionField;
    private final JLabel expressionStatusLabel;

    /** Full list of simulated-series options pulled from the model's [outputs] section. */
    private List<String> simulatedSeriesOptions = new ArrayList<>();

    public ObjectiveConfigPanel(java.util.function.Supplier<java.io.File> workingDirectorySupplier) {
        this.workingDirectorySupplier = workingDirectorySupplier;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Objective Builder"));

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        termsTableModel = new TermsTableModel();
        termsTable = new JTable(termsTableModel);
        termsTable.setRowHeight(25);
        termsTable.setShowGrid(true);
        termsTable.setGridColor(new Color(220, 220, 220));
        termsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        termsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        // Show ~6 rows by default so the panel is usable even with a few terms
        termsTable.setPreferredScrollableViewportSize(
            new Dimension(700, termsTable.getRowHeight() * 6));
        termsTable.setFillsViewportHeight(true);
        configureColumns();

        JScrollPane tableScrollPane = new JScrollPane(termsTable);
        mainPanel.add(tableScrollPane, BorderLayout.CENTER);

        // Button row below the table
        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        addButton = new JButton("+ New term");
        addButton.setToolTipText("Add a new term");
        addButton.addActionListener(e -> addTerm());
        buttonsRow.add(addButton);

        removeButton = new JButton("Remove selected");
        removeButton.setToolTipText("Remove the selected term (must keep at least one)");
        removeButton.addActionListener(e -> removeSelectedTerm());
        buttonsRow.add(removeButton);

        browseButton = new JButton("Browse for observed file…");
        browseButton.setToolTipText("Pick the observed-data CSV file for the selected term");
        browseButton.addActionListener(e -> browseForSelectedRow());
        buttonsRow.add(browseButton);

        // Expression row at the bottom
        JPanel expressionRow = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        expressionRow.add(new JLabel("Objective:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        expressionField = new JTextField("term1");
        expressionField.setToolTipText("Expression over term names, e.g. 'term1' or 'term1 + 0.5 * term2'");
        expressionRow.add(expressionField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        expressionStatusLabel = new JLabel(" ");
        expressionRow.add(expressionStatusLabel, gbc);

        // Live validation as the user types
        expressionField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { revalidateExpression(); }
            @Override public void removeUpdate(DocumentEvent e) { revalidateExpression(); }
            @Override public void changedUpdate(DocumentEvent e) { revalidateExpression(); }
        });

        // Re-validate when terms change too (renamed/added/removed)
        termsTableModel.addTableModelListener(e -> revalidateExpression());

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(buttonsRow, BorderLayout.NORTH);
        southPanel.add(expressionRow, BorderLayout.SOUTH);
        mainPanel.add(southPanel, BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);

        // Start with one term so the panel is never empty
        addTerm();
    }

    private void configureColumns() {
        TableColumn nameCol = termsTable.getColumnModel().getColumn(TermsTableModel.COL_NAME);
        nameCol.setPreferredWidth(80);

        TableColumn simulatedCol = termsTable.getColumnModel().getColumn(TermsTableModel.COL_SIMULATED);
        simulatedCol.setPreferredWidth(220);
        simulatedCol.setCellEditor(new DefaultCellEditor(makeEditableCombo()));

        TableColumn observedFileCol = termsTable.getColumnModel().getColumn(TermsTableModel.COL_OBSERVED_FILE);
        observedFileCol.setPreferredWidth(200);

        TableColumn seriesCol = termsTable.getColumnModel().getColumn(TermsTableModel.COL_OBSERVED_SERIES);
        seriesCol.setPreferredWidth(80);

        TableColumn statisticCol = termsTable.getColumnModel().getColumn(TermsTableModel.COL_STATISTIC);
        statisticCol.setPreferredWidth(140);
        JComboBox<String> statisticCombo = new JComboBox<>(STATISTIC_NAMES);
        statisticCombo.setEditable(false);
        statisticCol.setCellEditor(new DefaultCellEditor(statisticCombo));
    }

    /** Editable combo box for the simulated-series column, refreshed when model outputs change. */
    private JComboBox<String> makeEditableCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        for (String option : simulatedSeriesOptions) {
            combo.addItem(option);
        }
        return combo;
    }

    private void addTerm() {
        int n = termsTableModel.getRowCount();
        String defaultName = nextDefaultName();
        TermRow row = new TermRow(defaultName, "", "", "1", DEFAULT_STATISTIC);
        termsTableModel.addRow(row);
        termsTable.setRowSelectionInterval(n, n);

        // If this is the first term, seed the expression field with its name
        if (n == 0) {
            expressionField.setText(defaultName);
        }
    }

    private String nextDefaultName() {
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < termsTableModel.getRowCount(); i++) {
            existing.add(termsTableModel.getRow(i).name);
        }
        int n = 1;
        while (existing.contains("term" + n)) {
            n++;
        }
        return "term" + n;
    }

    private void removeSelectedTerm() {
        int selected = termsTable.getSelectedRow();
        if (selected < 0) {
            return;
        }
        if (termsTableModel.getRowCount() <= 1) {
            JOptionPane.showMessageDialog(this,
                "At least one term is required.",
                "Cannot remove",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        termsTableModel.removeRow(selected);
    }

    private void browseForSelectedRow() {
        int selected = termsTable.getSelectedRow();
        if (selected < 0) {
            JOptionPane.showMessageDialog(this,
                "Select a term row first.",
                "No row selected",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Observed Data File");

        javax.swing.filechooser.FileNameExtensionFilter csvFilter =
            new javax.swing.filechooser.FileNameExtensionFilter("CSV Files (*.csv)", "csv");
        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.setFileFilter(csvFilter);

        if (workingDirectorySupplier != null) {
            java.io.File workingDir = workingDirectorySupplier.get();
            if (workingDir != null && workingDir.exists()) {
                fileChooser.setCurrentDirectory(workingDir);
            }
        }

        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File selectedFile = fileChooser.getSelectedFile();

        String pathToUse = selectedFile.getAbsolutePath();
        if (workingDirectorySupplier != null) {
            java.io.File workingDir = workingDirectorySupplier.get();
            if (workingDir != null && workingDir.exists()) {
                try {
                    java.nio.file.Path workingPath = workingDir.toPath();
                    java.nio.file.Path selectedPath = selectedFile.toPath();
                    pathToUse = workingPath.relativize(selectedPath).toString();
                } catch (IllegalArgumentException e) {
                    // Different drives on Windows — fall back to absolute path
                    pathToUse = selectedFile.getAbsolutePath();
                }
            }
        }

        termsTableModel.setValueAt(pathToUse, selected, TermsTableModel.COL_OBSERVED_FILE);
    }

    /** Update the autocomplete options used for the simulated-series column. */
    public void updateSimulatedSeriesOptions(List<String> options) {
        this.simulatedSeriesOptions = new ArrayList<>(options);
        // Rebuild the cell editor with the new option set
        TableColumn simulatedCol = termsTable.getColumnModel().getColumn(TermsTableModel.COL_SIMULATED);
        simulatedCol.setCellEditor(new DefaultCellEditor(makeEditableCombo()));
    }

    /** Returns an immutable snapshot of the current term rows. */
    public List<TermRow> getTerms() {
        // Make sure any in-progress editing has been committed
        if (termsTable.isEditing()) {
            termsTable.getCellEditor().stopCellEditing();
        }
        List<TermRow> out = new ArrayList<>(termsTableModel.getRowCount());
        for (int i = 0; i < termsTableModel.getRowCount(); i++) {
            out.add(termsTableModel.getRow(i).copy());
        }
        return out;
    }

    /** Replace all term rows with the given list. */
    public void setTerms(List<TermRow> terms) {
        termsTableModel.replaceAll(terms);
    }

    public String getObjectiveExpression() {
        return expressionField.getText().trim();
    }

    public void setObjectiveExpression(String expr) {
        expressionField.setText(expr != null ? expr : "");
    }

    /** Show a red border on the expression field if it references an unknown term name. */
    private void revalidateExpression() {
        String expression = expressionField.getText();
        Set<String> definedTerms = new HashSet<>();
        for (int i = 0; i < termsTableModel.getRowCount(); i++) {
            definedTerms.add(termsTableModel.getRow(i).name);
        }

        List<String> unknownIdentifiers = new ArrayList<>();
        Matcher m = IDENT_PATTERN.matcher(expression);
        while (m.find()) {
            String ident = m.group();
            if (RESERVED_IDENTIFIERS.contains(ident)) {
                continue;
            }
            if (!definedTerms.contains(ident)) {
                unknownIdentifiers.add(ident);
            }
        }

        if (expression.trim().isEmpty()) {
            expressionField.setBorder(UIManager.getBorder("TextField.border"));
            expressionStatusLabel.setText("(empty)");
            expressionStatusLabel.setForeground(Color.GRAY);
        } else if (unknownIdentifiers.isEmpty()) {
            expressionField.setBorder(UIManager.getBorder("TextField.border"));
            expressionStatusLabel.setText(" ");
        } else {
            expressionField.setBorder(BorderFactory.createLineBorder(new Color(200, 0, 0)));
            expressionStatusLabel.setText("Unknown: " + String.join(", ", unknownIdentifiers));
            expressionStatusLabel.setForeground(new Color(200, 0, 0));
        }
    }

    /** Validates that every row has the required fields and the expression is non-empty + clean. */
    public boolean validateInputs() {
        if (termsTable.isEditing()) {
            termsTable.getCellEditor().stopCellEditing();
        }
        if (termsTableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this,
                "At least one term is required.",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }

        Set<String> names = new HashSet<>();
        for (int i = 0; i < termsTableModel.getRowCount(); i++) {
            TermRow row = termsTableModel.getRow(i);
            if (row.name.isEmpty()) {
                showError("Term name is required (row " + (i + 1) + ")");
                return false;
            }
            if (!names.add(row.name)) {
                showError("Duplicate term name: '" + row.name + "'");
                return false;
            }
            if (row.simulatedSeries.isEmpty()) {
                showError("Simulated series is required for term '" + row.name + "'");
                return false;
            }
            if (row.observedFile.isEmpty()) {
                showError("Observed file is required for term '" + row.name + "'");
                return false;
            }
            if (row.observedSeries.isEmpty()) {
                showError("Observed series is required for term '" + row.name + "'");
                return false;
            }
            if (row.statistic.isEmpty()) {
                showError("Statistic is required for term '" + row.name + "'");
                return false;
            }
        }

        if (getObjectiveExpression().isEmpty()) {
            showError("Objective expression is required");
            return false;
        }
        return true;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Validation Error", JOptionPane.ERROR_MESSAGE);
    }

    // ===== Inner types =====

    /** Plain data class for one term row. */
    public static class TermRow {
        public String name;
        public String simulatedSeries;
        public String observedFile;
        public String observedSeries;
        public String statistic;

        public TermRow(String name, String simulatedSeries, String observedFile,
                       String observedSeries, String statistic) {
            this.name = name;
            this.simulatedSeries = simulatedSeries;
            this.observedFile = observedFile;
            this.observedSeries = observedSeries;
            this.statistic = statistic;
        }

        public TermRow copy() {
            return new TermRow(name, simulatedSeries, observedFile, observedSeries, statistic);
        }
    }

    private static class TermsTableModel extends AbstractTableModel {
        static final int COL_NAME = 0;
        static final int COL_SIMULATED = 1;
        static final int COL_OBSERVED_FILE = 2;
        static final int COL_OBSERVED_SERIES = 3;
        static final int COL_STATISTIC = 4;

        private static final String[] COLUMN_NAMES = {
            "Name", "Simulated series", "Observed file", "Series", "Statistic"
        };

        private final List<TermRow> rows = new ArrayList<>();

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMN_NAMES.length; }
        @Override public String getColumnName(int column) { return COLUMN_NAMES[column]; }
        @Override public boolean isCellEditable(int row, int col) { return true; }

        TermRow getRow(int i) { return rows.get(i); }

        void addRow(TermRow row) {
            rows.add(row);
            int idx = rows.size() - 1;
            fireTableRowsInserted(idx, idx);
        }

        void removeRow(int i) {
            rows.remove(i);
            fireTableRowsDeleted(i, i);
        }

        void replaceAll(List<TermRow> newRows) {
            rows.clear();
            for (TermRow r : newRows) {
                rows.add(r.copy());
            }
            fireTableDataChanged();
        }

        @Override
        public Object getValueAt(int row, int col) {
            TermRow r = rows.get(row);
            switch (col) {
                case COL_NAME: return r.name;
                case COL_SIMULATED: return r.simulatedSeries;
                case COL_OBSERVED_FILE: return r.observedFile;
                case COL_OBSERVED_SERIES: return r.observedSeries;
                case COL_STATISTIC: return r.statistic;
                default: return "";
            }
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            TermRow r = rows.get(row);
            String s = value != null ? value.toString() : "";
            switch (col) {
                case COL_NAME: r.name = s.trim(); break;
                case COL_SIMULATED: r.simulatedSeries = s.trim(); break;
                case COL_OBSERVED_FILE: r.observedFile = s.trim(); break;
                case COL_OBSERVED_SERIES: r.observedSeries = s.trim(); break;
                case COL_STATISTIC: r.statistic = s.trim(); break;
            }
            fireTableCellUpdated(row, col);
        }
    }
}
