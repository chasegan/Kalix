package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel for configuring optimization objective settings.
 * Provides simple text fields for observed data name, simulated series, and objective function.
 */
public class ObjectiveConfigPanel extends JPanel {

    private final JTextField observedDataFileField;
    private final JTextField seriesField;
    private final JComboBox<String> simulatedSeriesCombo;
    private final JComboBox<String> objectiveFunctionCombo;
    private final java.util.function.Supplier<java.io.File> workingDirectorySupplier;

    // Store the full list of simulated series options for filtering
    private List<String> allSimulatedSeriesOptions = new ArrayList<>();
    private boolean isUpdatingComboBox = false;

    public ObjectiveConfigPanel(java.util.function.Supplier<java.io.File> workingDirectorySupplier) {
        this.workingDirectorySupplier = workingDirectorySupplier;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Objective"));

        // Create form panel with GridBagLayout for clean alignment
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 1: Observed Data File and Series
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        formPanel.add(new JLabel("Observed data file:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.5;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        observedDataFileField = new JTextField(20);
        formPanel.add(observedDataFileField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        JButton browseButton = new JButton("...");
        browseButton.setToolTipText("Browse for data file");
        browseButton.addActionListener(e -> browseForDataFile());
        formPanel.add(browseButton, gbc);

        gbc.gridx = 3;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Series:"), gbc);

        gbc.gridx = 4;
        gbc.weightx = 0.5;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        seriesField = new JTextField("1", 10);  // Default to "1"
        formPanel.add(seriesField, gbc);

        // Row 2: Simulated Series
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Simulated Series:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 4;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        simulatedSeriesCombo = new JComboBox<>();
        simulatedSeriesCombo.setEditable(true);
        setupComboBoxFiltering();
        formPanel.add(simulatedSeriesCombo, gbc);

        // Row 3: Objective Function
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Objective Function:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 4;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        String[] objectiveFunctions = {"SDEB", "PEARS_R", "NSE", "LNSE", "RMSE", "MAE", "KGE", "PBIAS"};
        objectiveFunctionCombo = new JComboBox<>(objectiveFunctions);
        objectiveFunctionCombo.setEditable(true);
        objectiveFunctionCombo.setSelectedItem("NSE"); // Default to NSE
        formPanel.add(objectiveFunctionCombo, gbc);

        add(formPanel, BorderLayout.NORTH);
    }

    /**
     * Sets up filtering functionality for the simulated series combo box.
     * As the user types, the dropdown filters to show only matching items.
     */
    private void setupComboBoxFiltering() {
        JTextComponent editor = (JTextComponent) simulatedSeriesCombo.getEditor().getEditorComponent();

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterComboBox();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterComboBox();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterComboBox();
            }

            private void filterComboBox() {
                if (isUpdatingComboBox) {
                    return; // Avoid infinite loops
                }

                SwingUtilities.invokeLater(() -> {
                    String text = editor.getText();
                    if (text == null) {
                        text = "";
                    }

                    // Filter the options based on user input (case-insensitive, match anywhere)
                    String filterText = text.toLowerCase();
                    List<String> filteredOptions = new ArrayList<>();

                    for (String option : allSimulatedSeriesOptions) {
                        if (option.toLowerCase().contains(filterText)) {
                            filteredOptions.add(option);
                        }
                    }

                    // Update combo box with filtered items
                    isUpdatingComboBox = true;
                    try {
                        simulatedSeriesCombo.removeAllItems();
                        for (String option : filteredOptions) {
                            simulatedSeriesCombo.addItem(option);
                        }

                        // Restore user's text
                        editor.setText(text);

                        // Show popup if there are matches and text is not empty
                        if (!filteredOptions.isEmpty() && !text.isEmpty()) {
                            simulatedSeriesCombo.setPopupVisible(true);
                        }
                    } finally {
                        isUpdatingComboBox = false;
                    }
                });
            }
        });
    }

    /**
     * Opens a file chooser dialog to select the observed data file.
     * Starts in the current model's directory (convention throughout the application).
     */
    private void browseForDataFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Observed Data File");

        // Set file filter for CSV files (common data format)
        javax.swing.filechooser.FileNameExtensionFilter csvFilter =
            new javax.swing.filechooser.FileNameExtensionFilter("CSV Files (*.csv)", "csv");
        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.setFileFilter(csvFilter);

        // Start in the current model's directory (application convention)
        if (workingDirectorySupplier != null) {
            java.io.File workingDir = workingDirectorySupplier.get();
            if (workingDir != null && workingDir.exists()) {
                fileChooser.setCurrentDirectory(workingDir);
            }
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            java.io.File selectedFile = fileChooser.getSelectedFile();

            // Calculate relative path from working directory
            String pathToUse = selectedFile.getAbsolutePath();
            if (workingDirectorySupplier != null) {
                java.io.File workingDir = workingDirectorySupplier.get();
                if (workingDir != null && workingDir.exists()) {
                    try {
                        java.nio.file.Path workingPath = workingDir.toPath();
                        java.nio.file.Path selectedPath = selectedFile.toPath();
                        java.nio.file.Path relativePath = workingPath.relativize(selectedPath);
                        pathToUse = relativePath.toString();
                    } catch (IllegalArgumentException e) {
                        // If paths are on different drives (Windows), use absolute path
                        pathToUse = selectedFile.getAbsolutePath();
                    }
                }
            }

            observedDataFileField.setText(pathToUse);
        }
    }

    /**
     * Gets the observed data file path.
     */
    public String getObservedDataFile() {
        return observedDataFileField.getText().trim();
    }

    /**
     * Sets the observed data file path.
     */
    public void setObservedDataFile(String filePath) {
        observedDataFileField.setText(filePath);
    }

    /**
     * Gets the series identifier (column name or index).
     */
    public String getSeries() {
        return seriesField.getText().trim();
    }

    /**
     * Sets the series identifier.
     */
    public void setSeries(String series) {
        seriesField.setText(series);
    }

    /**
     * Gets the simulated series.
     */
    public String getSimulatedSeries() {
        Object selected = simulatedSeriesCombo.getSelectedItem();
        return selected != null ? selected.toString().trim() : "";
    }

    /**
     * Sets the simulated series.
     */
    public void setSimulatedSeries(String series) {
        simulatedSeriesCombo.setSelectedItem(series);
    }

    /**
     * Updates the available options for simulated series.
     * This should be called when the model changes.
     */
    public void updateSimulatedSeriesOptions(java.util.List<String> options) {
        // Store the full list for filtering
        allSimulatedSeriesOptions = new ArrayList<>(options);

        // Remember current selection
        String currentSelection = getSimulatedSeries();

        // Clear and repopulate
        isUpdatingComboBox = true;
        try {
            simulatedSeriesCombo.removeAllItems();
            for (String option : options) {
                simulatedSeriesCombo.addItem(option);
            }

            // Restore selection if it still exists, otherwise default to first item
            if (!currentSelection.isEmpty() && options.contains(currentSelection)) {
                simulatedSeriesCombo.setSelectedItem(currentSelection);
            } else if (!options.isEmpty()) {
                simulatedSeriesCombo.setSelectedIndex(0); // Default to first item
            }
        } finally {
            isUpdatingComboBox = false;
        }
    }

    /**
     * Gets the objective function.
     */
    public String getObjectiveFunction() {
        Object selected = objectiveFunctionCombo.getSelectedItem();
        return selected != null ? selected.toString().trim() : "";
    }

    /**
     * Sets the objective function.
     */
    public void setObjectiveFunction(String function) {
        objectiveFunctionCombo.setSelectedItem(function);
    }

    /**
     * Validates that all required fields are filled.
     */
    public boolean validateInputs() {
        if (getObservedDataFile().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Observed data file is required",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (getSimulatedSeries().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Simulated Series is required",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (getObjectiveFunction().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Objective Function is required",
                "Validation Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }
}
