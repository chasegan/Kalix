package com.kalix.ide.windows.optimisation;

import com.kalix.ide.document.OpenModel;
import com.kalix.ide.document.WorkspaceView;
import com.kalix.ide.managers.optimisation.OptimisationConfigModel;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.List;
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
    private final ModelSelectorPanel modelSelectorPanel;
    private final Consumer<String> configTextConsumer;
    private final JLabel iniLockedBanner;

    /**
     * Creates a new OptimisationGuiBuilder.
     *
     * @param configTextConsumer Callback to receive generated config text and switch to text tab
     * @param workspace The open models the optimisation may target
     */
    public OptimisationGuiBuilder(Consumer<String> configTextConsumer,
                                  WorkspaceView workspace) {
        this.configTextConsumer = configTextConsumer;

        setLayout(new BorderLayout());

        // Main panel with GridBagLayout for proper vertical space distribution
        JPanel contentPanel = new JPanel(new GridBagLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Add the three configuration panels
        // The selector comes first: observed-data paths are browsed for, and stored
        // relative to, the *target* model's folder — not whichever tab is in front.
        modelSelectorPanel = new ModelSelectorPanel(workspace);
        objectivePanel = new ObjectiveConfigPanel(this::getTargetWorkingDirectory);
        algorithmPanel = new AlgorithmConfigPanel();
        parametersPanel = new ParametersConfigPanel();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 10, 0); // 10px bottom margin (gap between panels)

        // Top strip - one visual row: the target model, then the algorithm settings.
        // The selector is a sibling of the algorithm panel rather than a child, so that
        // setComponentsEnabled (which disables the config *form*) cannot grey out the
        // model choice — which model an optimisation targets is not part of its config.
        JPanel topStrip = new JPanel(new BorderLayout());
        topStrip.add(modelSelectorPanel, BorderLayout.WEST);
        topStrip.add(algorithmPanel, BorderLayout.CENTER);

        gbc.gridy = 0;
        gbc.weighty = 0.0;
        contentPanel.add(topStrip, gbc);

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

        // Banner shown when the optimisation has been locked to INI-text editing.
        iniLockedBanner = new JLabel(
            "This optimisation is configured via the Config INI tab — the form below is locked.");
        iniLockedBanner.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        iniLockedBanner.setFont(iniLockedBanner.getFont().deriveFont(Font.ITALIC));
        iniLockedBanner.setVisible(false);
        add(iniLockedBanner, BorderLayout.NORTH);
    }

    /**
     * The target-model selector at the head of the tab.
     *
     * <p>Deliberately not part of {@link OptimisationConfigModel}: the model an
     * optimisation runs against is a property of the optimisation (and of the kalixcli
     * session it owns), not of the config INI the form generates.</p>
     */
    public ModelSelectorPanel getModelSelectorPanel() {
        return modelSelectorPanel;
    }

    /** The selected model, or {@code null} if no model is open. */
    public OpenModel getSelectedModel() {
        return modelSelectorPanel.getSelectedModel();
    }

    /**
     * The folder the selected model's relative paths resolve against, or {@code null}
     * if nothing usable is selected. This is what kalixcli is given as its working
     * directory, and what file dialogs in this window open at.
     */
    public File getTargetWorkingDirectory() {
        OpenModel selected = modelSelectorPanel.getSelectedModel();
        return selected != null ? selected.getWorkingDirectory() : null;
    }

    /**
     * Shows or hides the banner explaining that the GUI form is locked because
     * the optimisation is now configured via INI text.
     *
     * @param visible true to show the banner
     */
    public void setIniLockedBannerVisible(boolean visible) {
        iniLockedBanner.setVisible(visible);
        revalidate();
        repaint();
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
     * Captures the current state of all three configuration panels into a
     * structured {@link OptimisationConfigModel}.
     *
     * @return a new model reflecting the live GUI form state
     */
    public OptimisationConfigModel captureToModel() {
        OptimisationConfigModel model = new OptimisationConfigModel();
        model.setTerms(objectivePanel.getTerms());
        model.setObjectiveExpression(objectivePanel.getObjectiveExpression());
        model.setAlgorithm(algorithmPanel.getAlgorithm());
        model.setTerminationEvaluations(algorithmPanel.getTerminationEvaluations());
        model.setThreads(algorithmPanel.getThreads());
        model.setRandomSeed(algorithmPanel.getRandomSeed());
        model.setAlgorithmSpecificParams(algorithmPanel.getAlgorithmSpecificParams());
        model.setParameters(parametersPanel.getAllParameters());
        return model;
    }

    /**
     * Populates all three configuration panels from a structured
     * {@link OptimisationConfigModel}, restoring the GUI form to a previously
     * captured state.
     *
     * @param model the model to load (a no-op if null)
     */
    public void loadFromModel(OptimisationConfigModel model) {
        if (model == null) {
            return;
        }
        objectivePanel.setTerms(model.getTerms());
        objectivePanel.setObjectiveExpression(model.getObjectiveExpression());
        // Set the algorithm first: changing it resets the algorithm-specific
        // params to defaults, so the explicit set below must come after.
        algorithmPanel.setAlgorithm(model.getAlgorithm());
        algorithmPanel.setTerminationEvaluations(model.getTerminationEvaluations());
        algorithmPanel.setThreads(model.getThreads());
        algorithmPanel.setRandomSeed(model.getRandomSeed());
        algorithmPanel.setAlgorithmSpecificParams(model.getAlgorithmSpecificParams());
        parametersPanel.setParameters(model.getParameters());
    }

    /**
     * Generates INI-formatted configuration text from the live GUI form state.
     *
     * @return the generated configuration text
     */
    public String generateConfigText() {
        return generateConfigText(captureToModel());
    }

    /**
     * Generates INI-formatted configuration text from a structured config model,
     * in kalixcli format.
     *
     * <p>Emits one [term.NAME] section per row plus an objective_expression in
     * [optimisation]. Only parameters with a non-blank expression are written to
     * the [parameters] section.</p>
     *
     * @param model the config model to render
     * @return the generated configuration text
     */
    public String generateConfigText(OptimisationConfigModel model) {
        StringBuilder sb = new StringBuilder();

        // [optimisation] section — algorithm config + objective expression
        sb.append("[optimisation]\n");
        sb.append("objective_expression = ").append(model.getObjectiveExpression()).append("\n");
        sb.append("algorithm = ").append(model.getAlgorithm()).append("\n");

        // Algorithm-specific parameters first (like population_size)
        for (Map.Entry<String, String> entry : model.getAlgorithmSpecificParams().entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }

        // Then common algorithm parameters
        sb.append("termination_evaluations = ").append(model.getTerminationEvaluations()).append("\n");
        sb.append("n_threads = ").append(model.getThreads()).append("\n");

        // Only include random_seed if it's not empty
        if (!model.getRandomSeed().isEmpty()) {
            sb.append("random_seed = ").append(model.getRandomSeed()).append("\n");
        }

        sb.append("\n");

        // One [term.NAME] section per row, in row order
        for (ObjectiveConfigPanel.TermRow term : model.getTerms()) {
            sb.append("[term.").append(term.name).append("]\n");
            sb.append("simulated = ").append(term.simulatedSeries).append("\n");
            sb.append("observed_file = ").append(term.observedFile).append("\n");
            sb.append("observed_series = ").append(term.observedSeries).append("\n");
            sb.append("statistic = ").append(term.statistic).append("\n");
            sb.append("\n");
        }

        // [parameters] section — only parameters with a non-blank expression
        List<OptimisationConfigModel.ParamEntry> params = model.getParameters();
        boolean anyOptimised = params.stream().anyMatch(p -> !p.expression.trim().isEmpty());
        if (anyOptimised) {
            sb.append("[parameters]\n");
            for (OptimisationConfigModel.ParamEntry param : params) {
                if (!param.expression.trim().isEmpty()) {
                    sb.append(param.name).append(" = ").append(param.expression).append("\n");
                }
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
