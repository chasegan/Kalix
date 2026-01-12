package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Executes editor commands and provides high-level operations.
 * All operations work with RSyntaxTextArea's built-in undo system.
 */
public class CommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CommandExecutor.class);

    private final RSyntaxTextArea editor;
    private final JFrame parentFrame;
    private final java.util.function.Consumer<java.util.List<com.kalix.ide.editor.EnhancedTextEditor.LineReplacement>> replacementApplier;

    public CommandExecutor(RSyntaxTextArea editor, JFrame parentFrame,
                          java.util.function.Consumer<java.util.List<com.kalix.ide.editor.EnhancedTextEditor.LineReplacement>> replacementApplier) {
        this.editor = editor;
        this.parentFrame = parentFrame;
        this.replacementApplier = replacementApplier;
    }

    /**
     * Renames a node throughout the document.
     * Finds all legitimate references and renames them atomically (single undo).
     * This includes: node headers, downstream references, output references,
     * and node references within function expressions.
     *
     * @param oldName       The current node name
     * @param newName       The new node name
     * @param parsedModel   The parsed model for finding references
     * @return true if rename was successful, false if cancelled or failed
     */
    public boolean renameNode(String oldName, String newName, INIModelParser.ParsedModel parsedModel) {
        try {
            // Validate new name
            if (newName == null || newName.trim().isEmpty()) {
                showError("New name cannot be empty");
                return false;
            }

            newName = newName.trim();

            // Check if new name already exists
            if (parsedModel.getSections().containsKey("node." + newName)) {
                showError("A node named '" + newName + "' already exists");
                return false;
            }

            // Get current text
            String currentText = editor.getText();

            // Find all references and build replacement list
            List<TextReplacement> replacements = findNodeReferences(oldName, newName, parsedModel);

            if (replacements.isEmpty()) {
                showError("No references found for node '" + oldName + "'");
                return false;
            }

            // Convert TextReplacement to LineReplacement and apply atomically
            List<com.kalix.ide.editor.EnhancedTextEditor.LineReplacement> lineReplacements = new ArrayList<>();
            for (TextReplacement replacement : replacements) {
                lineReplacements.add(new com.kalix.ide.editor.EnhancedTextEditor.LineReplacement(
                    replacement.getLineNumber(),
                    replacement.getOldText(),
                    replacement.getNewText()
                ));
            }

            // Apply all replacements as a single atomic undo operation
            replacementApplier.accept(lineReplacements);

            logger.info("Renamed node '{}' to '{}' ({} references updated)", oldName, newName, replacements.size());
            return true;

        } catch (Exception e) {
            logger.error("Error renaming node", e);
            showError("Failed to rename node: " + e.getMessage());
            return false;
        }
    }

    /**
     * Finds all legitimate references to a node.
     *
     * @param oldName     The node to find references for
     * @param newName     The new name to replace with
     * @param parsedModel The parsed model
     * @return List of text replacements to perform
     */
    private List<TextReplacement> findNodeReferences(String oldName, String newName,
                                                     INIModelParser.ParsedModel parsedModel) {
        List<TextReplacement> replacements = new ArrayList<>();

        // 1. Rename the node section header: [node.OldName] -> [node.NewName]
        INIModelParser.Section nodeSection = parsedModel.getSections().get("node." + oldName);
        if (nodeSection != null) {
            replacements.add(new TextReplacement(
                nodeSection.getStartLine(),
                "[node." + oldName + "]",
                "[node." + newName + "]"
            ));
        } else {
            logger.warn("Node section 'node.{}' not found in parsed model", oldName);
        }

        // 2. Find all downstream references (ds_1, ds_2, ds_3, etc.)
        for (INIModelParser.Section section : parsedModel.getSections().values()) {
            if (!section.getName().startsWith("node.")) continue;

            for (INIModelParser.Property prop : section.getProperties().values()) {
                // Check ds_1, ds_2, ds_3, etc.
                if (prop.getKey().matches("ds_\\d+")) {
                    if (prop.getValue().trim().equals(oldName)) {
                        replacements.add(new TextReplacement(
                            prop.getLineNumber(),
                            oldName,
                            newName
                        ));
                    }
                }
            }
        }

        // 3. Find output references: node.NodeName.property
        // Output references are stored separately in the parsed model (not as section properties)
        for (String outputRef : parsedModel.getOutputReferences()) {
            // Match: node.OldName.anything
            if (outputRef.contains("node." + oldName + ".")) {
                Integer lineNumber = parsedModel.getOutputReferenceLineNumbers().get(outputRef);
                if (lineNumber != null) {
                    replacements.add(new TextReplacement(
                        lineNumber,
                        "node." + oldName + ".",
                        "node." + newName + "."
                    ));
                }
            }
        }

        // 4. Find node references in function expressions: node.NodeName.property within property values
        // These can appear in any property value, especially function_expression types
        Pattern nodeRefPattern = Pattern.compile("\\bnode\\." + Pattern.quote(oldName) + "\\.");
        for (INIModelParser.Section section : parsedModel.getSections().values()) {
            if (!section.getName().startsWith("node.")) continue;

            for (INIModelParser.Property prop : section.getProperties().values()) {
                String value = prop.getValue();
                // Check if this property value contains a reference to the old node name
                if (nodeRefPattern.matcher(value).find()) {
                    replacements.add(new TextReplacement(
                        prop.getLineNumber(),
                        "node." + oldName + ".",
                        "node." + newName + "."
                    ));
                }
            }
        }

        return replacements;
    }


    /**
     * Inserts text at the current cursor position.
     *
     * @param text The text to insert
     */
    public void insertTextAtCursor(String text) {
        try {
            int caretPos = editor.getCaretPosition();
            editor.insert(text, caretPos);
            logger.debug("Inserted {} characters at position {}", text.length(), caretPos);
        } catch (Exception e) {
            logger.error("Error inserting text at cursor", e);
            showError("Failed to insert text: " + e.getMessage());
        }
    }

    /**
     * Replaces a property value in the document, handling multi-line values.
     * The property format is: key = value (possibly with continuation lines)
     *
     * @param propertyKey The property key (e.g., "params", "dimensions")
     * @param oldValue The current property value (as returned by parser, continuation lines joined)
     * @param newValue The new property value to set
     * @param propertyLineNumber The 1-based line number where the property starts
     * @return true if replacement was successful
     */
    public boolean replacePropertyValue(String propertyKey, String oldValue, String newValue, int propertyLineNumber) {
        try {
            String text = editor.getText();
            String[] lines = text.split("\n", -1);
            int lineIndex = propertyLineNumber - 1; // Convert to 0-based

            if (lineIndex < 0 || lineIndex >= lines.length) {
                logger.warn("Invalid property line number: {}", propertyLineNumber);
                return false;
            }

            // Find the start position of the property line
            int startPos = 0;
            for (int i = 0; i < lineIndex; i++) {
                startPos += lines[i].length() + 1; // +1 for newline
            }

            // Find the end of the property value (including continuation lines)
            int endLineIndex = lineIndex;
            for (int i = lineIndex + 1; i < lines.length; i++) {
                String nextLine = lines[i];
                // Continuation line: non-empty and starts with whitespace
                if (!nextLine.isEmpty() && Character.isWhitespace(nextLine.charAt(0))) {
                    endLineIndex = i;
                } else {
                    break;
                }
            }

            // Calculate end position (end of the last continuation line)
            int endPos = startPos;
            for (int i = lineIndex; i <= endLineIndex; i++) {
                endPos += lines[i].length();
                if (i < endLineIndex) {
                    endPos += 1; // newline between lines
                }
            }

            // Build the new property text
            String newPropertyText = propertyKey + " = " + newValue;

            // Perform the replacement as an atomic edit
            editor.beginAtomicEdit();
            try {
                javax.swing.text.Document doc = editor.getDocument();
                doc.remove(startPos, endPos - startPos);
                doc.insertString(startPos, newPropertyText, null);
            } finally {
                editor.endAtomicEdit();
            }

            logger.debug("Replaced property {} value (lines {}-{})", propertyKey, propertyLineNumber, endLineIndex + 1);
            return true;

        } catch (Exception e) {
            logger.error("Error replacing property value", e);
            showError("Failed to update property: " + e.getMessage());
            return false;
        }
    }

    /**
     * Shows an error dialog.
     */
    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                parentFrame,
                message,
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }

    /**
     * Represents a text replacement at a specific line.
     */
    private static class TextReplacement {
        private final int lineNumber;
        private final String oldText;
        private final String newText;

        public TextReplacement(int lineNumber, String oldText, String newText) {
            this.lineNumber = lineNumber;
            this.oldText = oldText;
            this.newText = newText;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getOldText() {
            return oldText;
        }

        public String getNewText() {
            return newText;
        }
    }
}
