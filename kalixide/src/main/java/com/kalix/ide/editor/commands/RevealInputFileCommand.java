package com.kalix.ide.editor.commands;

import com.kalix.ide.io.KalixPath;
import com.kalix.ide.io.KalixPathResolutionException;
import com.kalix.ide.utils.FileManagerLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Command to reveal an input file in the system's file manager.
 * Applicable when the cursor is on an input file path in the [inputs] section.
 * Resolves the path (including trailhead paths) and opens the containing folder.
 */
public class RevealInputFileCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(RevealInputFileCommand.class);

    private final Supplier<File> modelFileSupplier;
    private final JFrame parentFrame;

    public RevealInputFileCommand(Supplier<File> modelFileSupplier, JFrame parentFrame) {
        this.modelFileSupplier = modelFileSupplier;
        this.parentFrame = parentFrame;
    }

    @Override
    public CommandMetadata getMetadata() {
        return new CommandMetadata.Builder()
            .id("reveal_input_file")
            .displayName("Show in File Manager")
            .description("Reveal input file in the system file manager")
            .category("")
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
            String filePath = context.getInputFilePath()
                .orElseThrow(() -> new IllegalStateException("Input file path not available"));

            File modelFile = modelFileSupplier.get();
            if (modelFile == null) {
                showError("Cannot reveal file: No model file is currently loaded");
                return;
            }

            // Resolve the input file path (supports absolute, relative, and trailhead paths)
            File modelDirectory = modelFile.getParentFile();
            if (modelDirectory == null) {
                showError("Cannot determine model directory");
                return;
            }
            File inputFile;
            try {
                Path resolved = KalixPath.parse(filePath).resolve(modelDirectory.toPath());
                inputFile = resolved.toFile();
            } catch (IllegalArgumentException | KalixPathResolutionException e) {
                showError("Cannot resolve input file path: " + e.getMessage());
                return;
            }

            if (!inputFile.exists()) {
                showError("Input file not found: " + inputFile.getAbsolutePath());
                return;
            }

            // Open the file's parent directory in the system file manager
            File containingFolder = inputFile.getParentFile();
            if (containingFolder == null) {
                showError("Cannot determine containing folder for: " + inputFile.getAbsolutePath());
                return;
            }
            logger.info("Revealing input file in file manager: {}", inputFile.getAbsolutePath());
            FileManagerLauncher.openFileManagerAt(containingFolder);

        } catch (Exception e) {
            logger.error("Error revealing input file", e);
            showError("Failed to open file manager: " + e.getMessage());
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                parentFrame,
                message,
                "Reveal File Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }
}
