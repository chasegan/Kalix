package com.kalix.ide.managers.optimisation;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationResult;
import com.kalix.ide.models.optimisation.OptimisationStatus;
import com.kalix.ide.windows.MinimalEditorWindow;
import com.kalix.ide.diff.DiffWindow;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages the display and export of optimisation results.
 * Handles optimised model viewing, comparison, and saving.
 */
public class OptimisationResultsManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationResultsManager.class);

    // Text area configuration
    private static final int TEXT_AREA_ROWS = 30;
    private static final int TEXT_AREA_COLUMNS = 80;

    // Default messages
    private static final String MSG_READY = "# Optimisation ready to start...";
    private static final String MSG_KNUTH = "# If you optimize everything, you will always be unhappy. - Donald Knuth";

    private final KalixIniTextArea optimisedModelEditor;
    private final RTextScrollPane scrollPane;

    private Supplier<File> workingDirectorySupplier;
    private Supplier<String> originalModelSupplier;
    private Consumer<String> statusUpdater;

    /**
     * Creates a new OptimisationResultsManager.
     */
    public OptimisationResultsManager() {
        // Create read-only text editor for results
        this.optimisedModelEditor = new KalixIniTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS);
        this.optimisedModelEditor.setEditable(false);
        this.optimisedModelEditor.setText(MSG_READY);

        // Create scroll pane
        this.scrollPane = new RTextScrollPane(optimisedModelEditor);
    }

    /**
     * Gets the optimised model editor component.
     *
     * @return The text editor
     */
    public KalixIniTextArea getOptimisedModelEditor() {
        return optimisedModelEditor;
    }

    /**
     * Gets the scroll pane containing the editor.
     *
     * @return The scroll pane
     */
    public RTextScrollPane getScrollPane() {
        return scrollPane;
    }

    /**
     * Displays results for an optimisation.
     *
     * @param info The optimisation info
     */
    public void displayResults(OptimisationInfo info) {
        if (info == null) {
            optimisedModelEditor.setText(MSG_READY);
            return;
        }

        OptimisationStatus status = info.getStatus();
        OptimisationResult result = info.getResult();

        if (status == OptimisationStatus.DONE && result != null) {
            // Show optimised model if available
            if (result.getOptimisedModelIni() != null &&
                !result.getOptimisedModelIni().isEmpty()) {
                optimisedModelEditor.setText(result.getOptimisedModelIni());
            } else {
                // Show summary if no model available
                optimisedModelEditor.setText(result.formatSummary());
            }
            optimisedModelEditor.setCaretPosition(0); // Scroll to top
        } else if (status == OptimisationStatus.ERROR) {
            displayError(info, result);
        } else if (status == OptimisationStatus.STOPPED) {
            optimisedModelEditor.setText(MSG_KNUTH);
        } else if (status == OptimisationStatus.RUNNING ||
                   status == OptimisationStatus.LOADING) {
            displayRunningStatus(info);
        } else {
            optimisedModelEditor.setText(MSG_READY);
        }
    }

    /**
     * Displays error information.
     */
    private void displayError(OptimisationInfo info, OptimisationResult result) {
        StringBuilder errorText = new StringBuilder();
        errorText.append("# OPTIMISATION FAILED\n");
        errorText.append("# ").append("=".repeat(60)).append("\n");
        errorText.append("# Name: ").append(info.getName()).append("\n");
        errorText.append("# Session: ").append(info.getSessionKey()).append("\n");

        if (result != null) {
            if (result.getMessage() != null) {
                errorText.append("# Error: ").append(result.getMessage()).append("\n");
            }
            if (result.getStartTime() != null) {
                errorText.append("# Started: ")
                    .append(result.getStartTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append("\n");
            }
        }

        errorText.append("# ").append("=".repeat(60)).append("\n");
        errorText.append("\n# Check the configuration and try again.\n");

        optimisedModelEditor.setText(errorText.toString());
    }

    /**
     * Displays running status.
     */
    private void displayRunningStatus(OptimisationInfo info) {
        StringBuilder text = new StringBuilder();
        text.append("# OPTIMISATION IN PROGRESS\n");
        text.append("# ").append("=".repeat(60)).append("\n");
        text.append("# Name: ").append(info.getName()).append("\n");

        OptimisationResult result = info.getResult();
        if (result != null) {
            if (result.getCurrentProgress() != null) {
                text.append("# Progress: ").append(result.getCurrentProgress()).append("%\n");
            }
            if (result.getBestObjective() != null) {
                text.append("# Current Best: ").append(
                    String.format("%.6f", result.getBestObjective())).append("\n");
            }
            if (result.getEvaluations() != null) {
                text.append("# Evaluations: ").append(result.getEvaluations()).append("\n");
            }
        }

        text.append("# ").append("=".repeat(60)).append("\n");
        optimisedModelEditor.setText(text.toString());
    }

    /**
     * Shows the optimised model in a new window.
     *
     * @param info The optimisation info
     * @param parentFrame The parent frame
     */
    public void showOptimisedModel(OptimisationInfo info, JFrame parentFrame) {
        if (info == null || info.getResult() == null) {
            JOptionPane.showMessageDialog(parentFrame,
                "No optimised model available.",
                "No Results",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String modelText = info.getResult().getOptimisedModelIni();
        if (modelText == null || modelText.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame,
                "Optimised model text is empty.",
                "Empty Model",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Create window with optimised model
        MinimalEditorWindow editorWindow = new MinimalEditorWindow(modelText, true);
        editorWindow.setTitle("Optimised Model - " + info.getName());
        editorWindow.setSize(800, 600);
        editorWindow.setLocationRelativeTo(parentFrame);
        editorWindow.setVisible(true);
    }

    /**
     * Compares original and optimised models.
     * Shows the difference between the model used to start the optimisation
     * and the resulting optimised model.
     *
     * @param info The optimisation info
     * @param parentFrame The parent frame
     */
    public void compareModels(OptimisationInfo info, JFrame parentFrame) {
        if (info == null || info.getResult() == null) {
            JOptionPane.showMessageDialog(parentFrame,
                "No optimised model available for comparison.",
                "No Results",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String optimisedModel = info.getResult().getOptimisedModelIni();
        if (optimisedModel == null || optimisedModel.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame,
                "Optimised model is empty.",
                "Empty Model",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Get the original model from the OptimisationProgram
        String originalModel = null;
        if (info.getSession() != null &&
            info.getSession().getActiveProgram() instanceof com.kalix.ide.cli.OptimisationProgram) {
            com.kalix.ide.cli.OptimisationProgram program =
                (com.kalix.ide.cli.OptimisationProgram) info.getSession().getActiveProgram();
            originalModel = program.getModelText();
        }

        if (originalModel == null || originalModel.isEmpty()) {
            JOptionPane.showMessageDialog(parentFrame,
                "Original model not available from optimisation session.",
                "No Original Model",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Create diff window
        // DiffWindow constructor takes: (thisModel, referenceModel, title, leftHeader, rightHeader)
        // It displays referenceModel on the left and thisModel on the right
        DiffWindow diffWindow = new DiffWindow(
            optimisedModel,  // thisModel - displayed on right
            originalModel,   // referenceModel - displayed on left
            "Compare Models - " + info.getName(),
            "Original Model (Before Optimisation)",  // left header
            "Optimised Model (After Optimisation)"   // right header
        );
        diffWindow.setLocationRelativeTo(parentFrame);
        diffWindow.setVisible(true);
    }

    /**
     * Saves optimisation results to a file.
     *
     * @param info The optimisation info
     * @param parentFrame The parent frame
     */
    public void saveResults(OptimisationInfo info, JFrame parentFrame) {
        if (info == null || info.getResult() == null) {
            JOptionPane.showMessageDialog(parentFrame,
                "No results to save.",
                "No Results",
                JOptionPane.INFORMATION_MESSAGE);
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

        // Add file filters
        fileChooser.addChoosableFileFilter(
            new FileNameExtensionFilter("INI Files (*.ini)", "ini"));
        fileChooser.addChoosableFileFilter(
            new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        fileChooser.addChoosableFileFilter(
            new FileNameExtensionFilter("All Files (*.*)", "*"));

        // Suggest filename
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String suggestedName = String.format("optimised_%s_%s.ini",
            info.getName().replaceAll("\\s+", "_"), timestamp);
        fileChooser.setSelectedFile(new File(suggestedName));

        int result = fileChooser.showSaveDialog(parentFrame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Determine what to save
            String contentToSave;
            if (selectedFile.getName().endsWith(".ini") &&
                info.getResult().getOptimisedModelIni() != null) {
                contentToSave = info.getResult().getOptimisedModelIni();
            } else {
                // Save summary for non-INI files or if no model available
                contentToSave = formatFullResults(info);
            }

            try {
                Files.writeString(selectedFile.toPath(), contentToSave);
                if (statusUpdater != null) {
                    statusUpdater.accept("Results saved to " + selectedFile.getName());
                }
                logger.info("Saved results to {}", selectedFile.getAbsolutePath());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(parentFrame,
                    "Failed to save results: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
                logger.error("Failed to save results", ex);
            }
        }
    }

    /**
     * Formats complete results for saving.
     */
    private String formatFullResults(OptimisationInfo info) {
        StringBuilder sb = new StringBuilder();
        OptimisationResult result = info.getResult();

        // Header
        sb.append("# OPTIMISATION RESULTS\n");
        sb.append("# ").append("=".repeat(70)).append("\n");
        sb.append("# Generated: ").append(LocalDateTime.now()
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        sb.append("# Name: ").append(info.getName()).append("\n");
        sb.append("# Session: ").append(info.getSessionKey()).append("\n");
        sb.append("# ").append("=".repeat(70)).append("\n\n");

        // Results summary
        sb.append(result.formatSummary()).append("\n");

        // Configuration used
        if (info.getConfigSnapshot() != null) {
            sb.append("\n# CONFIGURATION USED:\n");
            sb.append("# ").append("-".repeat(70)).append("\n");
            sb.append(info.getConfigSnapshot()).append("\n");
        }

        // Optimised model if available
        if (result.getOptimisedModelIni() != null) {
            sb.append("\n# OPTIMISED MODEL:\n");
            sb.append("# ").append("-".repeat(70)).append("\n");
            sb.append(result.getOptimisedModelIni()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Exports results as CSV.
     *
     * @param info The optimisation info
     * @param file The file to export to
     * @throws IOException If export fails
     */
    public void exportResultsAsCsv(OptimisationInfo info, File file) throws IOException {
        if (info == null || info.getResult() == null) {
            throw new IllegalArgumentException("No results to export");
        }

        StringBuilder csv = new StringBuilder();
        csv.append("Parameter,Value\n");

        OptimisationResult result = info.getResult();

        // Add basic info
        csv.append("Name,").append(info.getName()).append("\n");
        csv.append("Best Objective,").append(result.getBestObjective()).append("\n");
        csv.append("Evaluations,").append(result.getEvaluations()).append("\n");
        csv.append("Generations,").append(result.getGenerations()).append("\n");

        // Add parameters
        if (result.getParametersPhysical() != null) {
            csv.append("\nOptimised Parameters:\n");
            result.getParametersPhysical().forEach((param, value) ->
                csv.append(param).append(",").append(value).append("\n"));
        }

        Files.writeString(file.toPath(), csv.toString());
    }

    // Setters for dependencies
    public void setWorkingDirectorySupplier(Supplier<File> supplier) {
        this.workingDirectorySupplier = supplier;
    }

    public void setOriginalModelSupplier(Supplier<String> supplier) {
        this.originalModelSupplier = supplier;
    }

    public void setStatusUpdater(Consumer<String> updater) {
        this.statusUpdater = updater;
    }

    /**
     * Updates the optimised model display based on the optimisation status and result.
     *
     * @param optInfo The optimisation info
     */
    public void updateOptimisedModelDisplay(OptimisationInfo optInfo) {
        if (optInfo == null) {
            optimisedModelEditor.setText(MSG_READY);
            return;
        }

        OptimisationStatus status = optInfo.getStatus();
        OptimisationResult result = optInfo.getResult();

        if (result != null && status == OptimisationStatus.DONE) {
            // Show optimised model if available
            if (result.getOptimisedModelIni() != null && !result.getOptimisedModelIni().isEmpty()) {
                optimisedModelEditor.setText(result.getOptimisedModelIni());
                optimisedModelEditor.setCaretPosition(0); // Scroll to top
            } else {
                // Fallback: show summary if no model available
                optimisedModelEditor.setText(result.formatSummary());
            }
        } else if (status == OptimisationStatus.RUNNING || status == OptimisationStatus.LOADING) {
            // Show simple quote while optimization is running
            optimisedModelEditor.setText(MSG_KNUTH);
        } else if (status == OptimisationStatus.ERROR) {
            // Show error
            StringBuilder errorText = new StringBuilder();
            errorText.append("# OPTIMISATION FAILED\n\n");
            if (result != null && result.getMessage() != null) {
                errorText.append("# Error: ").append(result.getMessage()).append("\n");
            } else {
                errorText.append("# Optimisation failed with unknown error\n");
            }
            optimisedModelEditor.setText(errorText.toString());
        } else {
            // Starting/Ready state
            optimisedModelEditor.setText(MSG_READY);
        }
    }
}