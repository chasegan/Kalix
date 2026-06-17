package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.function.Supplier;

/**
 * Command to rename an input file path throughout the document.
 * Updates the file path in [inputs] section and all data.{alias}.* references
 * in property values and output references.
 */
public class AddInputFileAliasCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(AddInputFileAliasCommand.class);

    private final CommandMetadata metadata;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final JFrame parentFrame;

    public AddInputFileAliasCommand(Supplier<INIModelParser.ParsedModel> modelSupplier, JFrame parentFrame) {
        this.modelSupplier = modelSupplier;
        this.parentFrame = parentFrame;
        this.metadata = new CommandMetadata.Builder()
            .id("add_input_file_alias")
            .displayName("Add alias for file")
            .description("Add an alias for this file and convert references throughout the model to use this alias.")
            .category("")
            .build();
    }

    @Override
    public CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        return context.getType() == EditorContext.ContextType.INPUT_FILE
                && context.getInputFilePath().isPresent();
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        String oldPath = context.getInputFilePath().orElse(null);
        if (oldPath == null) {
            logger.warn("No input file path found in context");
            return;
        }

        // Prompt user for new path
        String newAlias = promptForNewName(oldPath);
        if (newAlias == null) {
            // User cancelled
            return;
        }

        // Get fresh parsed model
        INIModelParser.ParsedModel parsedModel = modelSupplier.get();
        if (parsedModel == null) {
            logger.error("Failed to parse model");
            JOptionPane.showMessageDialog(
                parentFrame,
                "Failed to parse model",
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        // Execute the rename
        boolean success = executor.addInputFileAlias(oldPath, newAlias, parsedModel);

        if (success) {
            showSuccess(oldPath, newAlias);
        }
    }

    /**
     * Prompts the user to enter a new input file path.
     *
     * @param currentPath The current input file path
     * @return The new path, or null if cancelled
     */
    private String promptForNewName(String currentPath) {
        return (String) JOptionPane.showInputDialog(
            parentFrame,
            "Enter new alias for input file '" + currentPath + "':",
            "Rename Input File",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentPath
        );
    }

    /**
     * Shows a success message after renaming.
     */
    private void showSuccess(String oldPath, String newAlias) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                parentFrame,
                "Added alias for input file '" + oldPath + "' as '" + newAlias + "'",
                "Done",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
    }
}
