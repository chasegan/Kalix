package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import java.awt.*;

/**
 * Panel for configuring optimization objective settings.
 * Provides simple text fields for observed data name, simulated series, and objective function.
 */
public class ObjectiveConfigPanel extends JPanel {

    private final JTextField observedDataField;
    private final JTextField simulatedSeriesField;
    private final JTextField objectiveFunctionField;

    public ObjectiveConfigPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Objective"));

        // Create form panel with GridBagLayout for clean alignment
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 1: Observed Data Name
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        formPanel.add(new JLabel("Observed Data By Name:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        observedDataField = new JTextField(20);
        formPanel.add(observedDataField, gbc);

        // Row 2: Simulated Series
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Simulated Series:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        simulatedSeriesField = new JTextField(20);
        formPanel.add(simulatedSeriesField, gbc);

        // Row 3: Objective Function
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("Objective Function:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        objectiveFunctionField = new JTextField(20);
        formPanel.add(objectiveFunctionField, gbc);

        add(formPanel, BorderLayout.NORTH);
    }

    /**
     * Gets the observed data by name.
     */
    public String getObservedDataByName() {
        return observedDataField.getText().trim();
    }

    /**
     * Sets the observed data by name.
     */
    public void setObservedDataByName(String name) {
        observedDataField.setText(name);
    }

    /**
     * Gets the simulated series.
     */
    public String getSimulatedSeries() {
        return simulatedSeriesField.getText().trim();
    }

    /**
     * Sets the simulated series.
     */
    public void setSimulatedSeries(String series) {
        simulatedSeriesField.setText(series);
    }

    /**
     * Gets the objective function.
     */
    public String getObjectiveFunction() {
        return objectiveFunctionField.getText().trim();
    }

    /**
     * Sets the objective function.
     */
    public void setObjectiveFunction(String function) {
        objectiveFunctionField.setText(function);
    }

    /**
     * Validates that all required fields are filled.
     */
    public boolean validateInputs() {
        if (getObservedDataByName().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Observed Data By Name is required",
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
