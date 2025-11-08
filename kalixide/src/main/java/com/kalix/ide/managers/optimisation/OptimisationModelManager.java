package com.kalix.ide.managers.optimisation;

import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationResult;
import com.kalix.ide.diff.DiffWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages model-related operations for optimisation.
 * Handles model display, comparison, copying, and saving.
 */
public class OptimisationModelManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationModelManager.class);

    private final Supplier<File> workingDirectorySupplier;
    private final Supplier<String> currentModelTextSupplier;
    private final Consumer<String> modelTextSetter;
    private Consumer<String> statusUpdater;

    /**
     * Creates a new OptimisationModelManager.
     *
     * @param workingDirectorySupplier Supplier for the working directory
     * @param currentModelTextSupplier Supplier for the current model text
     * @param modelTextSetter Consumer to set the model text in the main editor
     */
    public OptimisationModelManager(Supplier<File> workingDirectorySupplier,
                                   Supplier<String> currentModelTextSupplier,
                                   Consumer<String> modelTextSetter) {
        this.workingDirectorySupplier = workingDirectorySupplier;
        this.currentModelTextSupplier = currentModelTextSupplier;
        this.modelTextSetter = modelTextSetter;
    }

    /**
     * Sets the status updater callback.
     *
     * @param statusUpdater The status updater
     */
    public void setStatusUpdater(Consumer<String> statusUpdater) {
        this.statusUpdater = statusUpdater;
    }

    /**
     * Shows the original model for the selected optimisation.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void showOriginalModel(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getSession() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation selected",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Get original model from the program
        String originalModel = null;
        if (optInfo.getSession().getActiveProgram() instanceof com.kalix.ide.cli.OptimisationProgram) {
            com.kalix.ide.cli.OptimisationProgram program =
                (com.kalix.ide.cli.OptimisationProgram) optInfo.getSession().getActiveProgram();
            originalModel = program.getModelText();
        }

        if (originalModel == null || originalModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No original model found for this optimisation",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Create a dialog to show the model
        JDialog dialog = new JDialog((Window) SwingUtilities.getWindowAncestor(parent),
            "Original Model - " + optInfo.getName(), JDialog.ModalityType.MODELESS);

        JTextArea textArea = new JTextArea(originalModel);
        textArea.setEditable(false);
        textArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new java.awt.Dimension(800, 600));

        dialog.add(scrollPane);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /**
     * Shows the optimised model for the selected optimisation.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void showOptimisedModel(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String optimisedModel = optInfo.getResult().getOptimisedModelIni();
        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No optimised model found",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Create a dialog to show the model
        JDialog dialog = new JDialog((Window) SwingUtilities.getWindowAncestor(parent),
            "Optimised Model - " + optInfo.getName(), JDialog.ModalityType.MODELESS);

        JTextArea textArea = new JTextArea(optimisedModel);
        textArea.setEditable(false);
        textArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new java.awt.Dimension(800, 600));

        dialog.add(scrollPane);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /**
     * Copies the optimised model to the main editor.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void copyOptimisedModelToMain(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String optimisedModel = optInfo.getResult().getOptimisedModelIni();
        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No optimised model found",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Confirm with user
        int response = JOptionPane.showConfirmDialog(parent,
            "This will replace the current model in the main editor.\nAre you sure you want to continue?",
            "Replace Current Model",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (response == JOptionPane.YES_OPTION) {
            // Set the model text in the main editor
            modelTextSetter.accept(optimisedModel);

            if (statusUpdater != null) {
                statusUpdater.accept("Optimised model copied to main editor");
            }
            logger.info("Copied optimised model to main editor");
        }
    }

    /**
     * Compares the optimised model with the current main model.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void compareOptimisedModelWithMain(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        OptimisationResult result = optInfo.getResult();
        String optimisedModel = result.getOptimisedModelIni();

        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No optimised model found",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String currentModel = currentModelTextSupplier.get();
        if (currentModel == null || currentModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No current model to compare with",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Create DiffWindow for comparison
        DiffWindow diffWindow = new DiffWindow(
            currentModel,
            optimisedModel,
            "Compare Models",
            "Current Model",
            "Optimised Model (" + optInfo.getName() + ")"
        );

        if (statusUpdater != null) {
            statusUpdater.accept("Comparing models");
        }
    }

    /**
     * Compares the original and optimised models for an optimisation.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void compareOriginalWithOptimised(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Get original model from the program
        String originalModel = null;
        if (optInfo.getSession().getActiveProgram() instanceof com.kalix.ide.cli.OptimisationProgram) {
            com.kalix.ide.cli.OptimisationProgram program =
                (com.kalix.ide.cli.OptimisationProgram) optInfo.getSession().getActiveProgram();
            originalModel = program.getModelText();
        }
        String optimisedModel = optInfo.getResult().getOptimisedModelIni();

        if (originalModel == null || originalModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No original model found",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No optimised model found",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Create DiffWindow for comparison
        DiffWindow diffWindow = new DiffWindow(
            originalModel,
            optimisedModel,
            "Compare Models - " + optInfo.getName(),
            "Original Model",
            "Optimised Model"
        );

        if (statusUpdater != null) {
            statusUpdater.accept("Comparing original and optimised models");
        }
    }

    /**
     * Saves the optimised model to a file.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void saveOptimisedModelAs(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String optimisedModel = optInfo.getResult().getOptimisedModelIni();
        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "No optimised model to save",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Optimised Model");

        // Set initial directory
        if (workingDirectorySupplier != null) {
            File workingDir = workingDirectorySupplier.get();
            if (workingDir != null) {
                fileChooser.setCurrentDirectory(workingDir);
            }
        }

        // Set file filter
        FileNameExtensionFilter filter = new FileNameExtensionFilter(
            "INI Files (*.ini)", "ini");
        fileChooser.setFileFilter(filter);

        // Suggest a filename
        fileChooser.setSelectedFile(new File("optimised_model.ini"));

        int result = fileChooser.showSaveDialog(parent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Ensure .ini extension
            if (!selectedFile.getName().toLowerCase().endsWith(".ini")) {
                selectedFile = new File(selectedFile.getAbsolutePath() + ".ini");
            }

            // Check if file exists
            if (selectedFile.exists()) {
                int confirm = JOptionPane.showConfirmDialog(parent,
                    "File already exists. Overwrite?",
                    "Confirm Overwrite",
                    JOptionPane.YES_NO_OPTION);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            try {
                Files.writeString(selectedFile.toPath(), optimisedModel);
                if (statusUpdater != null) {
                    statusUpdater.accept("Optimised model saved to " + selectedFile.getName());
                }
                logger.info("Saved optimised model to {}", selectedFile.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent,
                    "Failed to save model: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
                logger.error("Failed to save optimised model", ex);
            }
        }
    }

    /**
     * Saves optimisation results to a file.
     *
     * @param optInfo The optimisation info
     * @param parent The parent component for dialogs
     */
    public void saveOptimisationResults(OptimisationInfo optInfo, JComponent parent) {
        if (optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(parent,
                "No optimisation result available",
                "Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Optimisation Results");

        // Set initial directory
        if (workingDirectorySupplier != null) {
            File workingDir = workingDirectorySupplier.get();
            if (workingDir != null) {
                fileChooser.setCurrentDirectory(workingDir);
            }
        }

        // Set file filter
        FileNameExtensionFilter filter = new FileNameExtensionFilter(
            "Text Files (*.txt)", "txt");
        fileChooser.setFileFilter(filter);

        // Suggest a filename
        fileChooser.setSelectedFile(new File("optimisation_results.txt"));

        int result = fileChooser.showSaveDialog(parent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Ensure .txt extension
            if (!selectedFile.getName().toLowerCase().endsWith(".txt")) {
                selectedFile = new File(selectedFile.getAbsolutePath() + ".txt");
            }

            // Check if file exists
            if (selectedFile.exists()) {
                int confirm = JOptionPane.showConfirmDialog(parent,
                    "File already exists. Overwrite?",
                    "Confirm Overwrite",
                    JOptionPane.YES_NO_OPTION);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            try {
                String resultsText = formatOptimisationResults(optInfo);
                Files.writeString(selectedFile.toPath(), resultsText);
                if (statusUpdater != null) {
                    statusUpdater.accept("Results saved to " + selectedFile.getName());
                }
                logger.info("Saved results to {}", selectedFile.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parent,
                    "Failed to save results: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
                logger.error("Failed to save results", ex);
            }
        }
    }

    /**
     * Formats optimisation results for saving to file.
     *
     * @param optInfo The optimisation info
     * @return Formatted results text
     */
    private String formatOptimisationResults(OptimisationInfo optInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Optimisation Results\n");
        sb.append("====================\n\n");
        sb.append("Name: ").append(optInfo.getName()).append("\n");
        sb.append("Status: ").append(optInfo.getStatus()).append("\n");

        OptimisationResult result = optInfo.getResult();
        if (result != null) {
            sb.append("\nResults:\n");
            sb.append("--------\n");
            sb.append("Best Objective: ").append(result.getBestObjective()).append("\n");
            sb.append("Evaluations: ").append(result.getEvaluations()).append("\n");
            sb.append("Generations: ").append(result.getGenerations()).append("\n");
            sb.append("Success: ").append(result.isSuccess()).append("\n");

            if (result.getMessage() != null) {
                sb.append("Message: ").append(result.getMessage()).append("\n");
            }

            if (result.getParametersPhysical() != null) {
                sb.append("\nOptimised Parameters:\n");
                sb.append("--------------------\n");
                result.getParametersPhysical().forEach((key, value) ->
                    sb.append(key).append(": ").append(value).append("\n"));
            }

            if (result.getStartTime() != null) {
                sb.append("\nTiming:\n");
                sb.append("-------\n");
                sb.append("Start: ").append(result.getStartTime()).append("\n");
                sb.append("End: ").append(result.getEndTime()).append("\n");
            }
        }

        if (optInfo.getConfigSnapshot() != null) {
            sb.append("\nConfiguration:\n");
            sb.append("-------------\n");
            sb.append(optInfo.getConfigSnapshot());
        }

        return sb.toString();
    }
}