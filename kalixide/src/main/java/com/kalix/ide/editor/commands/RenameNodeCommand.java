package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.function.Supplier;

/**
 * Command to rename a node throughout the document.
 * Updates the node section header, all downstream references, output references,
 * and node references within function expressions.
 */
public class RenameNodeCommand implements EditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(RenameNodeCommand.class);

    private final CommandMetadata metadata;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final JFrame parentFrame;

    public RenameNodeCommand(Supplier<INIModelParser.ParsedModel> modelSupplier, JFrame parentFrame) {
        this.modelSupplier = modelSupplier;
        this.parentFrame = parentFrame;
        this.metadata = new CommandMetadata.Builder()
            .id("rename_node")
            .displayName("Rename")
            .description("Rename this node and all its references")
            .category("")
            .build();
    }

    @Override
    public CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isApplicable(EditorContext context) {
        // Only applicable when cursor is on a node header line
        return context.getType() == EditorContext.ContextType.NODE_HEADER
            && context.getNodeName().isPresent();
    }

    @Override
    public void execute(EditorContext context, CommandExecutor executor) {
        String oldName = context.getNodeName().orElse(null);
        if (oldName == null) {
            logger.warn("No node name found in context");
            return;
        }

        // Prompt user for new name
        String newName = promptForNewName(oldName);
        if (newName == null) {
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
        boolean success = executor.renameNode(oldName, newName, parsedModel);

        if (success) {
            showSuccess(oldName, newName);
        }
    }

    /**
     * Prompts the user to enter a new node name.
     *
     * @param currentName The current node name
     * @return The new name, or null if cancelled
     */
    private String promptForNewName(String currentName) {
        return (String) JOptionPane.showInputDialog(
            parentFrame,
            "Enter new name for node '" + currentName + "':",
            "Rename Node",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );
    }

    /**
     * Shows a success message after renaming.
     */
    private void showSuccess(String oldName, String newName) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                parentFrame,
                "Renamed '" + oldName + "' to '" + newName + "'",
                "Done",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
    }
}
