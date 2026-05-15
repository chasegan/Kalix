package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Main GUI builder panel for configuring optimizations.
 * Assembles the three configuration panels and provides config text generation.
 */
public class OptimisationGuiBuilder extends JPanel {

    private final ObjectiveConfigPanel objectivePanel;
    private final AlgorithmConfigPanel algorithmPanel;
    private final ParametersConfigPanel parametersPanel;
    private final Consumer<String> configTextConsumer;

    /**
     * Creates a new OptimisationGuiBuilder.
     *
     * @param configTextConsumer Callback to receive generated config text and switch to text tab
     * @param workingDirectorySupplier Supplier for the current model's working directory
     */
    public OptimisationGuiBuilder(Consumer<String> configTextConsumer, Supplier<File> workingDirectorySupplier) {
        this.configTextConsumer = configTextConsumer;

        setLayout(new BorderLayout());

        // Main panel with GridBagLayout for proper vertical space distribution
        JPanel contentPanel = new JPanel(new GridBagLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Add the three configuration panels
        objectivePanel = new ObjectiveConfigPanel(workingDirectorySupplier);
        algorithmPanel = new AlgorithmConfigPanel();
        parametersPanel = new ParametersConfigPanel();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 10, 0); // 10px bottom margin (gap between panels)

        // Algorithm panel - single unframed row at the top, never expands
        gbc.gridy = 0;
        gbc.weighty = 0.0;
        contentPanel.add(algorithmPanel, gbc);

        // Objective panel - grows with extra vertical space (1/2 share)
        gbc.gridy = 1;
        gbc.weighty = 0.5;
        contentPanel.add(objectivePanel, gbc);

        // Parameters panel - grows with extra vertical space (1/2 share)
        gbc.gridy = 2;
        gbc.weighty = 0.5;
        gbc.insets = new Insets(0, 0, 0, 0); // No bottom margin on last panel
        contentPanel.add(parametersPanel, gbc);

        add(contentPanel, BorderLayout.CENTER);
    }

    /**
     * Validates all inputs and generates config text, then switches to text editor tab.
     * Public so it can be called from OptimisationWindow button.
     */
    public void generateAndSwitchToTextEditor() {
        // Validate all panels
        if (!objectivePanel.validateInputs()) {
            return;
        }
        if (!algorithmPanel.validateInputs()) {
            return;
        }
        if (!parametersPanel.validateInputs()) {
            return;
        }

        // Generate config text
        String configText = generateConfigText();

        // Send to consumer (will set text editor content and switch tabs)
        if (configTextConsumer != null) {
            configTextConsumer.accept(configText);
        }
    }

    /**
     * Generates INI-formatted configuration text from GUI inputs in kalixcli format.
     *
     * Emits one [term.NAME] section per row plus an objective_expression in [optimisation].
     * Uses ParameterExpressionLibrary to auto-generate expressions for parameters without them.
     */
    public String generateConfigText() {
        StringBuilder sb = new StringBuilder();

        // [optimisation] section — algorithm config + objective expression
        sb.append("[optimisation]\n");
        sb.append("objective_expression = ").append(objectivePanel.getObjectiveExpression()).append("\n");

        sb.append("algorithm = ").append(algorithmPanel.getAlgorithm()).append("\n");

        // Add algorithm-specific parameters first (like population_size)
        Map<String, String> algoParams = algorithmPanel.getAlgorithmSpecificParams();
        for (Map.Entry<String, String> entry : algoParams.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }

        // Then add common algorithm parameters
        sb.append("termination_evaluations = ").append(algorithmPanel.getTerminationEvaluations()).append("\n");
        sb.append("n_threads = ").append(algorithmPanel.getThreads()).append("\n");

        // Only include random_seed if it's not empty
        String randomSeed = algorithmPanel.getRandomSeed();
        if (!randomSeed.isEmpty()) {
            sb.append("random_seed = ").append(randomSeed).append("\n");
        }

        sb.append("\n");

        // One [term.NAME] section per row, in row order
        for (ObjectiveConfigPanel.TermRow term : objectivePanel.getTerms()) {
            sb.append("[term.").append(term.name).append("]\n");
            sb.append("simulated = ").append(term.simulatedSeries).append("\n");
            sb.append("observed_file = ").append(term.observedFile).append("\n");
            sb.append("observed_series = ").append(term.observedSeries).append("\n");
            sb.append("statistic = ").append(term.statistic).append("\n");
            sb.append("\n");
        }

        // [parameters] section - generate expressions for parameters
        Map<String, String> optimizationParams = parametersPanel.getOptimizationParameters();

        // If some parameters don't have expressions, try to auto-generate them
        Map<String, String> finalParams = new java.util.LinkedHashMap<>();
        int counter = 1;
        StringBuilder warnings = new StringBuilder();

        for (Map.Entry<String, String> entry : optimizationParams.entrySet()) {
            String paramName = entry.getKey();
            String expression = entry.getValue();

            if (expression == null || expression.trim().isEmpty()) {
                // Try to auto-generate expression
                try {
                    expression = ParameterExpressionLibrary.generateExpression(paramName, counter);
                    counter++;
                } catch (ParameterExpressionLibrary.UnrecognizedParameterTypeException e) {
                    // Log warning but continue
                    String type = ParameterExpressionLibrary.detectParameterType(paramName);
                    if (type == null) {
                        type = "unknown";
                    }
                    if (!warnings.isEmpty()) {
                        warnings.append("\n");
                    }
                    warnings.append("  - ").append(paramName).append(" (type: ").append(type).append(")");
                    continue; // Skip this parameter
                }
            }

            finalParams.put(paramName, expression);
        }

        // Show warning dialog if any parameters couldn't be generated
        if (!warnings.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(this,
                "Could not auto-generate expressions for unrecognized parameter types:\n\n" + warnings +
                "\n\nThese parameters were omitted from the configuration.\nPlease add them manually in the Text Editor if needed.",
                "Unrecognized Parameter Types",
                javax.swing.JOptionPane.WARNING_MESSAGE);
        }

        if (!finalParams.isEmpty()) {
            sb.append("[parameters]\n");
            for (Map.Entry<String, String> entry : finalParams.entrySet()) {
                sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Updates the available options for simulated series combo box.
     * Delegates to ObjectiveConfigPanel.
     *
     * @param options List of output series names from the model's [outputs] section
     */
    public void updateSimulatedSeriesOptions(java.util.List<String> options) {
        objectivePanel.updateSimulatedSeriesOptions(options);
    }

    /**
     * Automatically generates expressions for all parameters.
     * Called when the Optimisation Window opens to pre-populate the parameters table.
     * Delegates to ParametersConfigPanel.
     */
    public void autoGenerateParameterExpressions() {
        parametersPanel.autoGenerateAllExpressions();
    }

    /**
     * Sets the list of optimisable parameters from kalixcli.
     * Delegates to ParametersConfigPanel.
     *
     * @param parameters List of parameter names from get_optimisable_params
     */
    public void setOptimisableParameters(java.util.List<String> parameters) {
        parametersPanel.setOptimisableParameters(parameters);
    }

    /**
     * Enables or disables all input components in the GUI builder.
     * This recursively disables all components in the three config panels.
     *
     * @param enabled true to enable components, false to disable
     */
    public void setComponentsEnabled(boolean enabled) {
        setEnabledRecursive(objectivePanel, enabled);
        setEnabledRecursive(algorithmPanel, enabled);
        setEnabledRecursive(parametersPanel, enabled);
    }

    /**
     * Recursively enables/disables all components in a container.
     */
    private void setEnabledRecursive(java.awt.Container container, boolean enabled) {
        container.setEnabled(enabled);
        for (java.awt.Component component : container.getComponents()) {
            component.setEnabled(enabled);
            if (component instanceof java.awt.Container) {
                setEnabledRecursive((java.awt.Container) component, enabled);
            }
        }
    }
}
