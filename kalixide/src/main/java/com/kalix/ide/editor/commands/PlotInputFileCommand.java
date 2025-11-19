package com.kalix.ide.editor.commands;

import com.kalix.ide.flowviz.FlowVizWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.util.function.Supplier;

/**
 * Command to plot an input file in a FlowViz window.
 * Applicable when the cursor is on an input file path in the [inputs] section.
 */
public class PlotInputFileCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(PlotInputFileCommand.class);

    private final Supplier<File> modelFileSupplier;
    private final JFrame parentFrame;

    public PlotInputFileCommand(Supplier<File> modelFileSupplier, JFrame parentFrame) {
        this.modelFileSupplier = modelFileSupplier;
        this.parentFrame = parentFrame;
    }

    @Override
    public CommandMetadata getMetadata() {
        return new CommandMetadata.Builder()
            .id("plot_input_file")
            .displayName("Plot")
            .description("Plot input file data in FlowViz")
            .category("") // Empty category = appears at root level, not in submenu
            .build();
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        return context.getType() == EditorContext.ContextType.INPUT_FILE
            && context.getInputFilePath().isPresent();
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        try {
            // Get the input file path from context
            String relativeFilePath = context.getInputFilePath()
                .orElseThrow(() -> new IllegalStateException("Input file path not available"));

            // Get the current model file to resolve relative path
            File modelFile = modelFileSupplier.get();
            if (modelFile == null) {
                showError("Cannot plot: No model file is currently loaded");
                return;
            }

            // Resolve the input file relative to the model file's directory
            File modelDirectory = modelFile.getParentFile();
            File inputFile = new File(modelDirectory, relativeFilePath);

            // Check if the file exists
            if (!inputFile.exists()) {
                showError("Input file not found: " + inputFile.getAbsolutePath());
                return;
            }

            // Create FlowViz window with the input file
            logger.info("Opening FlowViz window for input file: {}", inputFile.getAbsolutePath());
            FlowVizWindow.createWindowWithFile(inputFile);

        } catch (Exception e) {
            logger.error("Error plotting input file", e);
            showError("Failed to plot input file: " + e.getMessage());
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                parentFrame,
                message,
                "Plot Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }
}
