package com.kalix.ide.windows.optimisation;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.function.Consumer;

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
     */
    public OptimisationGuiBuilder(Consumer<String> configTextConsumer) {
        this.configTextConsumer = configTextConsumer;

        setLayout(new BorderLayout());

        // Main scrollable panel for all sections
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Add the three configuration panels
        objectivePanel = new ObjectiveConfigPanel();
        algorithmPanel = new AlgorithmConfigPanel();
        parametersPanel = new ParametersConfigPanel();

        // Set maximum width to prevent panels from stretching too wide
        Dimension maxSize = new Dimension(Integer.MAX_VALUE, objectivePanel.getPreferredSize().height);
        objectivePanel.setMaximumSize(maxSize);
        algorithmPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        // Parameters panel should expand to fill remaining vertical space
        parametersPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        contentPanel.add(objectivePanel);
        contentPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        contentPanel.add(algorithmPanel);
        contentPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        contentPanel.add(parametersPanel);

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
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
     * Uses ParameterExpressionLibrary to auto-generate expressions for parameters without them.
     */
    public String generateConfigText() {
        StringBuilder sb = new StringBuilder();

        // [General] section (kalixcli format)
        sb.append("[General]\n");
        sb.append("observed_data_by_name = ").append(objectivePanel.getObservedDataByName()).append("\n");
        sb.append("simulated_series = ").append(objectivePanel.getSimulatedSeries()).append("\n");
        sb.append("objective_function = ").append(objectivePanel.getObjectiveFunction()).append("\n");
        sb.append("\n");

        // [Algorithm] section
        sb.append("[Algorithm]\n");
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

        // [Parameters] section - generate expressions for parameters
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
                    if (warnings.length() > 0) {
                        warnings.append("\n");
                    }
                    warnings.append("  - ").append(paramName).append(" (type: ").append(type).append(")");
                    continue; // Skip this parameter
                }
            }

            finalParams.put(paramName, expression);
        }

        // Show warning dialog if any parameters couldn't be generated
        if (warnings.length() > 0) {
            javax.swing.JOptionPane.showMessageDialog(this,
                "Could not auto-generate expressions for unrecognized parameter types:\n\n" + warnings.toString() +
                "\n\nThese parameters were omitted from the configuration.\nPlease add them manually in the Text Editor if needed.",
                "Unrecognized Parameter Types",
                javax.swing.JOptionPane.WARNING_MESSAGE);
        }

        if (!finalParams.isEmpty()) {
            sb.append("[Parameters]\n");
            for (Map.Entry<String, String> entry : finalParams.entrySet()) {
                sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Loads default values for demonstration purposes.
     */
    public void loadDefaults() {
        // Set objective defaults
        objectivePanel.setObservedDataByName("../data.csv.ObsFlow");
        objectivePanel.setSimulatedSeries("node.mygr4jnode.ds_1");
        objectivePanel.setObjectiveFunction("NSE");

        // Set algorithm defaults
        algorithmPanel.setAlgorithm("DE");
        algorithmPanel.setTerminationEvaluations("5000");
        algorithmPanel.setThreads("4");
        algorithmPanel.setRandomSeed(""); // Blank = use random initial seed

        // Set some parameter expressions as examples (using kalixcli format)
        parametersPanel.setParameterExpression("node.mygr4jnode.x1", "lin_range(g(1), 10, 2000)");
        parametersPanel.setParameterExpression("node.mygr4jnode.x2", "lin_range(g(2), -8, 6)");
    }

    /**
     * Clears all inputs.
     */
    public void clearAll() {
        objectivePanel.setObservedDataByName("");
        objectivePanel.setSimulatedSeries("");
        objectivePanel.setObjectiveFunction("");
        parametersPanel.clearAllExpressions();
    }
}
