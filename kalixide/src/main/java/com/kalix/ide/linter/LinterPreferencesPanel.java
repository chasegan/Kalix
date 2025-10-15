package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * Preferences panel for configuring linter settings including rule management and schema selection.
 */
public class LinterPreferencesPanel extends JPanel implements LinterManager.ValidationCompletionListener {

    private SchemaManager schemaManager;
    private LinterManager linterManager;

    // UI Components
    private JCheckBox enableLintingCheckBox;
    private JTextField schemaPathField;
    private JButton browseSchemaButton;
    private JButton useDefaultButton;
    private JButton reloadSchemaButton;
    private JButton exportDefaultSchemaButton;
    private JLabel schemaStatusLabel;
    private JTable rulesTable;
    private RulesTableModel rulesTableModel;

    public LinterPreferencesPanel(SchemaManager schemaManager) {
        super(new BorderLayout());
        this.schemaManager = schemaManager;
        this.linterManager = null;
        setBorder(BorderFactory.createTitledBorder("Model Linting Settings"));
        initializePanel();
    }

    public LinterPreferencesPanel(SchemaManager schemaManager, LinterManager linterManager) {
        super(new BorderLayout());
        this.schemaManager = schemaManager;
        this.linterManager = linterManager;
        setBorder(BorderFactory.createTitledBorder("Model Linting Settings"));
        initializePanel();

        // Register as validation listener to get real-time updates
        if (linterManager != null) {
            linterManager.addValidationListener(this);
        }
    }

    private void initializePanel() {
        JPanel contentPanel = new JPanel(new GridBagLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Enable linting checkbox
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;
        enableLintingCheckBox = new JCheckBox("Enable model linting");
        enableLintingCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.LINTER_ENABLED, true));
        enableLintingCheckBox.addActionListener(e -> onLintingEnabledChanged());
        contentPanel.add(enableLintingCheckBox, gbc);

        // Schema file section
        gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        contentPanel.add(new JLabel("Schema File:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        schemaPathField = new JTextField(PreferenceManager.getFileString(PreferenceKeys.LINTER_SCHEMA_PATH, ""));
        schemaPathField.setToolTipText("Leave empty to use the default embedded schema");
        contentPanel.add(schemaPathField, gbc);

        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        browseSchemaButton = new JButton("Browse...");
        browseSchemaButton.addActionListener(this::browseSchemaFile);
        contentPanel.add(browseSchemaButton, gbc);

        // Schema controls
        gbc.gridx = 0; gbc.gridy = 2;
        useDefaultButton = new JButton("Use Default");
        useDefaultButton.addActionListener(this::useDefaultSchema);
        contentPanel.add(useDefaultButton, gbc);

        gbc.gridx = 1;
        reloadSchemaButton = new JButton("Reload Schema");
        reloadSchemaButton.addActionListener(this::reloadSchema);
        contentPanel.add(reloadSchemaButton, gbc);

        gbc.gridx = 2;
        exportDefaultSchemaButton = new JButton("Export Default Schema");
        exportDefaultSchemaButton.addActionListener(this::exportDefaultSchema);
        contentPanel.add(exportDefaultSchemaButton, gbc);

        // Schema status
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        schemaStatusLabel = new JLabel();
        updateSchemaStatus();
        contentPanel.add(schemaStatusLabel, gbc);

        // Rules table
        gbc.gridy = 4; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        JPanel rulesPanel = createRulesPanel();
        contentPanel.add(rulesPanel, gbc);

        add(contentPanel, BorderLayout.CENTER);
        updateControlStates();
    }

    private JPanel createRulesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Validation Rules"));

        rulesTableModel = new RulesTableModel();
        rulesTable = new JTable(rulesTableModel);
        rulesTable.setRowHeight(25);
        rulesTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        rulesTable.getColumnModel().getColumn(1).setPreferredWidth(300);
        rulesTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        rulesTable.getColumnModel().getColumn(3).setPreferredWidth(60);

        // Custom renderer for severity column
        rulesTable.getColumnModel().getColumn(2).setCellRenderer(new SeverityRenderer());

        JScrollPane scrollPane = new JScrollPane(rulesTable);
        scrollPane.setPreferredSize(new Dimension(0, 200));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Rules control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton enableAllButton = new JButton("Enable All");
        enableAllButton.addActionListener(e -> setAllRulesEnabled(true));
        buttonPanel.add(enableAllButton);

        JButton disableAllButton = new JButton("Disable All");
        disableAllButton.addActionListener(e -> setAllRulesEnabled(false));
        buttonPanel.add(disableAllButton);

        JButton resetButton = new JButton("Reset to Defaults");
        resetButton.addActionListener(e -> resetRulesToDefaults());
        buttonPanel.add(resetButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void onLintingEnabledChanged() {
        boolean enabled = enableLintingCheckBox.isSelected();
        PreferenceManager.setFileBoolean(PreferenceKeys.LINTER_ENABLED, enabled);

        // Update schema manager to propagate the change
        Set<String> disabledRules = rulesTableModel != null ?
            rulesTableModel.getDisabledRules() :
            schemaManager.getDisabledRules();

        schemaManager.updatePreferences(
            enabled,
            schemaPathField.getText().trim(),
            disabledRules
        );

        updateControlStates();
    }

    private void browseSchemaFile(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Linter Schema File");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));

        String currentPath = schemaPathField.getText().trim();
        if (!currentPath.isEmpty()) {
            fileChooser.setSelectedFile(new File(currentPath));
        }

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String newPath = fileChooser.getSelectedFile().getAbsolutePath();
            schemaPathField.setText(newPath);
            saveSchemaPath();
        }
    }

    private void useDefaultSchema(ActionEvent e) {
        schemaPathField.setText("");
        saveSchemaPath();
    }

    private void reloadSchema(ActionEvent e) {
        saveSchemaPath();
        schemaManager.reloadSchema();
        updateSchemaStatus();
        rulesTableModel.refreshData();
    }

    private void saveSchemaPath() {
        String path = schemaPathField.getText().trim();
        PreferenceManager.setFileString(PreferenceKeys.LINTER_SCHEMA_PATH, path);
    }

    private void updateSchemaStatus() {
        if (schemaManager.isSchemaLoaded()) {
            String path = schemaManager.getCurrentSchemaPath();
            String version = schemaManager.getSchemaVersion();

            // Get validation timing if linterManager is available
            String timingInfo = "";
            if (linterManager != null) {
                long timeMs = linterManager.getLastValidationTimeMs();
                if (timeMs > 0) {
                    timingInfo = " in " + timeMs + " ms";
                }
            }

            if (path.isEmpty()) {
                schemaStatusLabel.setText("Status: Linting with embedded schema v" + version + timingInfo);
                schemaStatusLabel.setForeground(new Color(0, 128, 0));
            } else {
                schemaStatusLabel.setText("Status: Linting with custom schema v" + version + timingInfo);
                schemaStatusLabel.setForeground(new Color(0, 128, 0));
            }
        } else {
            schemaStatusLabel.setText("Status: Schema failed to load");
            schemaStatusLabel.setForeground(Color.RED);
        }
    }

    private void updateControlStates() {
        boolean lintingEnabled = enableLintingCheckBox.isSelected();

        schemaPathField.setEnabled(lintingEnabled);
        browseSchemaButton.setEnabled(lintingEnabled);
        useDefaultButton.setEnabled(lintingEnabled);
        reloadSchemaButton.setEnabled(lintingEnabled);
        exportDefaultSchemaButton.setEnabled(lintingEnabled);
        rulesTable.setEnabled(lintingEnabled);
    }

    private void setAllRulesEnabled(boolean enabled) {
        rulesTableModel.setAllRulesEnabled(enabled);
        saveRuleStates();
    }

    private void resetRulesToDefaults() {
        rulesTableModel.resetToDefaults();
        saveRuleStates();
    }

    private void saveRuleStates() {
        Set<String> disabledRules = rulesTableModel.getDisabledRules();
        schemaManager.updatePreferences(
            enableLintingCheckBox.isSelected(),
            schemaPathField.getText().trim(),
            disabledRules
        );
    }

    private void exportDefaultSchema(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Default Linting Schema");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));
        fileChooser.setSelectedFile(new File("linting_rules.json"));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            
            // Ensure .json extension
            if (!file.getName().toLowerCase().endsWith(".json")) {
                file = new File(file.getAbsolutePath() + ".json");
            }

            try {
                String schemaContent = LinterSchema.getDefaultSchemaContent();
                Files.writeString(file.toPath(), schemaContent);
                JOptionPane.showMessageDialog(this,
                    "Default schema exported successfully to " + file.getName(),
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                    "Error exporting schema: " + ex.getMessage(),
                    "Export Error",
                    JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Error reading default schema: " + ex.getMessage(),
                    "Export Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Table model for validation rules
    private class RulesTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Rule Name", "Description", "Severity", "Enabled"};
        private List<RuleRow> rules = new ArrayList<>();

        public RulesTableModel() {
            refreshData();
        }

        public void refreshData() {
            rules.clear();
            if (schemaManager.isSchemaLoaded()) {
                LinterSchema schema = schemaManager.getCurrentSchema();
                Map<String, ValidationRule> validationRules = schema.getValidationRules();

                for (ValidationRule rule : validationRules.values()) {
                    rules.add(new RuleRow(rule));
                }
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rules.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 3: return Boolean.class;
                default: return String.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 3; // Only enabled column is editable
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RuleRow rule = rules.get(rowIndex);
            switch (columnIndex) {
                case 0: return rule.name;
                case 1: return rule.description;
                case 2: return rule.severity;
                case 3: return rule.enabled;
                default: return null;
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 3 && value instanceof Boolean) {
                RuleRow rule = rules.get(rowIndex);
                rule.enabled = (Boolean) value;
                fireTableCellUpdated(rowIndex, columnIndex);
                saveRuleStates();
            }
        }

        public void setAllRulesEnabled(boolean enabled) {
            for (RuleRow rule : rules) {
                rule.enabled = enabled;
            }
            fireTableDataChanged();
        }

        public void resetToDefaults() {
            for (RuleRow rule : rules) {
                rule.enabled = true; // Default all rules to enabled
            }
            fireTableDataChanged();
        }

        public Set<String> getDisabledRules() {
            Set<String> disabled = new HashSet<>();
            for (RuleRow rule : rules) {
                if (!rule.enabled) {
                    disabled.add(rule.name);
                }
            }
            return disabled;
        }

        private class RuleRow {
            String name;
            String description;
            String severity;
            boolean enabled;

            public RuleRow(ValidationRule rule) {
                this.name = rule.getName();
                this.description = rule.getDescription();
                this.severity = rule.getSeverity().name();
                this.enabled = rule.isEnabled();
            }
        }
    }

    // Custom renderer for severity column
    private static class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if ("ERROR".equals(value)) {
                setForeground(isSelected ? Color.WHITE : Color.RED);
                setFont(getFont().deriveFont(Font.BOLD));
            } else if ("WARNING".equals(value)) {
                setForeground(isSelected ? Color.WHITE : new Color(255, 140, 0)); // Orange
                setFont(getFont().deriveFont(Font.BOLD));
            } else {
                setForeground(isSelected ? Color.WHITE : Color.BLACK);
                setFont(getFont().deriveFont(Font.PLAIN));
            }

            return this;
        }
    }

    /**
     * ValidationCompletionListener implementation - updates timing display in real-time.
     */
    @Override
    public void onValidationCompleted(com.kalix.ide.linter.model.ValidationResult result, long validationTimeMs) {
        // Update status label on EDT
        SwingUtilities.invokeLater(this::updateSchemaStatus);
    }

    /**
     * Cleanup when panel is no longer needed.
     */
    public void dispose() {
        if (linterManager != null) {
            linterManager.removeValidationListener(this);
        }
    }
}