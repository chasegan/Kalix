package com.kalix.ide.editor.commands;

import com.kalix.ide.filedialog.KalixFileDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * Command to insert a file path at the cursor position.
 * Opens a file picker and inserts the relative path to the chosen file.
 */
public class InsertFilePathCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(InsertFilePathCommand.class);

    private final CommandMetadata metadata;
    private final Supplier<File> modelFileSupplier;
    private final JFrame parentFrame;

    public InsertFilePathCommand(Supplier<File> modelFileSupplier, JFrame parentFrame) {
        this.modelFileSupplier = modelFileSupplier;
        this.parentFrame = parentFrame;
        this.metadata = new CommandMetadata.Builder()
            .id("insert_file_path")
            .displayName("Insert File Path")
            .description("Insert a relative file path at cursor position")
            .category("")
            .build();
    }

    @Override
    public CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        // Applicable when anywhere in the [data] section
        return context.getSectionName().isPresent()
            && context.getSectionName().get().equals("data");
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        // Get the current model file to determine starting directory
        File modelFile = modelFileSupplier.get();
        File startDirectory = null;

        if (modelFile != null && modelFile.getParentFile() != null) {
            startDirectory = modelFile.getParentFile();
        }

        // Show file picker
        java.util.Optional<File> chosen = KalixFileDialog.openFile(parentFrame)
            .title("Select File")
            .startIn(startDirectory)
            .show();
        if (chosen.isEmpty()) {
            // User cancelled
            return;
        }

        File selectedFile = chosen.get();

        // Calculate relative path
        String relativePath;
        if (modelFile != null && modelFile.getParentFile() != null) {
            try {
                Path modelDir = modelFile.getParentFile().toPath();
                Path selectedPath = selectedFile.toPath();
                Path relative = modelDir.relativize(selectedPath);
                relativePath = relative.toString();

                // Normalize path separators to forward slashes for cross-platform compatibility
                relativePath = relativePath.replace('\\', '/');
            } catch (IllegalArgumentException e) {
                // Paths on different drives (Windows) - use absolute path
                relativePath = selectedFile.getAbsolutePath().replace('\\', '/');
                logger.warn("Cannot relativize paths on different drives, using absolute path");
            }
        } else {
            // No model file loaded - use absolute path
            relativePath = selectedFile.getAbsolutePath().replace('\\', '/');
        }

        // Insert at cursor with newline
        executor.insertTextAtCursor(relativePath + "\n");

        logger.debug("Inserted file path: {}", relativePath);
    }
}
