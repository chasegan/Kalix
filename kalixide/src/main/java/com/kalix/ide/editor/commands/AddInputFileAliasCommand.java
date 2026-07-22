package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.function.Supplier;

/**
 * Command to add an alias for an input file.
 * Converts the file's [data] line to {@code alias = path} form and rewrites all
 * {@code data.{file}.*} references in property values and output references to
 * {@code data.{alias}.*}.
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

        // Prompt user for the alias
        String newAlias = promptForAlias(oldPath);
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
     * Prompts the user to enter an alias for the input file, suggesting the
     * sanitized filename stem (not the path, which sanitisation would mangle).
     *
     * @param currentPath The input file path being aliased
     * @return The alias, or null if cancelled
     */
    private String promptForAlias(String currentPath) {
        return (String) JOptionPane.showInputDialog(
            parentFrame,
            "Enter alias for input file '" + currentPath + "':",
            "Add File Alias",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            suggestAlias(currentPath)
        );
    }

    /**
     * Suggests an alias for a file path: the filename stem (final path component,
     * final extension dropped) in the engine's sanitized form, so the suggestion
     * survives {@code CommandExecutor.addInputFileAlias}'s sanitisation unchanged.
     * Example: {@code ./data/MyData.csv} &rarr; {@code mydata}.
     */
    static String suggestAlias(String filePath) {
        java.nio.file.Path fileName = java.nio.file.Paths.get(filePath).getFileName();
        String name = fileName != null ? fileName.toString() : filePath;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return com.kalix.ide.utils.EngineNames.sanitize(name);
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
