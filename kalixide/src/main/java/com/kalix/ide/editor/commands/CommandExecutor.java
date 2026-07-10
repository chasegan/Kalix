package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.model.NodeInsertionPoint;
import com.kalix.ide.model.NodeSectionLocator;
import com.kalix.ide.utils.EngineNames;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
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
        String trimmed = newName == null ? "" : newName.trim();
        return applyRename("rename node", "node '" + oldName + "'",
            () -> {
                if (trimmed.isEmpty()) {
                    return "New name cannot be empty";
                }
                if (parsedModel.getSections().containsKey("node." + trimmed)) {
                    return "A node named '" + trimmed + "' already exists";
                }
                return null;
            },
            () -> findNodeReferences(editor.getText(), oldName, trimmed, parsedModel),
            count -> logger.info("Renamed node '{}' to '{}' ({} references updated)", oldName, trimmed, count));
    }

    /**
     * Renames an input file path throughout the document.
     * Finds all legitimate references and renames them atomically (single undo).
     * This includes: the file path in [inputs], and all data.{alias}.* references
     * in property values and output references.
     *
     * @param oldPath     The current input file path
     * @param newPath     The new input file path
     * @param parsedModel The parsed model for finding references
     * @return true if rename was successful, false if cancelled or failed
     */
    public boolean renameInputFile(String oldPath, String newPath, INIModelParser.ParsedModel parsedModel) {
        String trimmed = newPath == null ? "" : newPath.trim();
        return applyRename("rename input file", "input file '" + oldPath + "'",
            () -> {
                if (trimmed.isEmpty()) {
                    return "New path cannot be empty";
                }
                if (parsedModel.getInputFileLineNumbers().containsKey(trimmed)) {
                    return "Input file '" + trimmed + "' already exists";
                }
                return null;
            },
            () -> findInputFileReferences(editor.getText(), oldPath, trimmed, parsedModel),
            count -> {
                String oldFileSanitised = EngineNames.sanitizeFileName(oldPath);
                String newFileSanitised = EngineNames.sanitizeFileName(trimmed);
                logger.info("Renamed input file '{}' to '{}' (alias: {} -> {}, {} references updated)",
                    oldPath, trimmed, oldFileSanitised, newFileSanitised, count);
            });
    }

    /**
     * Renames an input file alias throughout the document.
     * Finds all legitimate references and renames them atomically (single undo).
     * This includes: the file path in [inputs], and all data.{alias}.* references
     * in property values and output references.
     *
     * @param oldAlias    The current input file alias
     * @param newAlias    The new input file alias
     * @param parsedModel The parsed model for finding references
     * @return true if rename was successful, false if cancelled or failed
     */
    public boolean renameInputFileAlias(String oldAlias, String newAlias, INIModelParser.ParsedModel parsedModel) {
        String trimmed = newAlias == null ? "" : newAlias.trim();
        return applyRename("rename input file", "input file '" + oldAlias + "'",
            () -> {
                if (trimmed.isEmpty()) {
                    return "New alias cannot be empty";
                }
                if (parsedModel.getInputFileAliases().containsKey(trimmed)) {
                    return "Input file alias '" + trimmed + "' already exists";
                }
                return null;
            },
            () -> findInputFileAliasReferences(editor.getText(), oldAlias, trimmed, parsedModel),
            count -> {
                String oldAliasSanitised = EngineNames.sanitize(oldAlias);
                String newAliasSanitised = EngineNames.sanitize(trimmed);
                logger.info("Renamed input file alias '{}' to '{}' ({} -> {}, {} references updated)",
                        oldAlias, trimmed, oldAliasSanitised, newAliasSanitised, count);
            });
    }

    /**
     * Renames an input file to use a new alias throughout the document.
     * Finds all legitimate references and renames them atomically (single undo).
     * This includes: the file path in [inputs], and all data.{alias}.* references
     * in property values and output references.
     *
     * @param oldPath    The current input file path
     * @param newAlias    The new input file alias
     * @param parsedModel The parsed model for finding references
     * @return true if rename was successful, false if cancelled or failed
     */
    public boolean addInputFileAlias(String oldPath, String newAlias, INIModelParser.ParsedModel parsedModel) {
        String trimmed = newAlias == null ? "" : newAlias.trim();
        // Aliases are sanitised with the engine's plain name rule (not the filename-derivation
        // rule): the engine runs user aliases straight through sanitize_name
        // (src/timeseries_input.rs). Empty is checked on the raw trimmed value, before sanitising.
        String sanitised = EngineNames.sanitize(trimmed);
        return applyRename("rename input file", "input file '" + oldPath + "'",
            () -> {
                if (trimmed.isEmpty()) {
                    return "New alias cannot be empty";
                }
                if (parsedModel.getInputFileAliases().containsKey(sanitised)) {
                    return "Input file alias '" + sanitised + "' already exists";
                }
                return null;
            },
            () -> findInputFileReferencesAddAlias(editor.getText(), oldPath, sanitised, parsedModel),
            count -> {
                String oldAliasSanitised = EngineNames.sanitizeFileName(oldPath);
                String newAliasSanitised = EngineNames.sanitize(sanitised);
                logger.info("Renamed input file alias '{}' to '{}' ({} -> {}, {} references updated)",
                        oldPath, sanitised, oldAliasSanitised, newAliasSanitised, count);
            });
    }

    /**
     * Shared skeleton for the rename operations: validate, find references, apply atomically as a
     * single undo, and log — with uniform "no references" / failure error dialogs. Only the three
     * variable steps are supplied by each caller.
     *
     * @param renameVerb   phrase for the failure dialog ("Failed to &lt;renameVerb&gt;") and error log
     * @param noRefEntity  entity phrase for the "No references found for &lt;noRefEntity&gt;" dialog
     * @param validate     returns an error message to show and abort, or {@code null} to proceed
     * @param finder       produces the range-anchored replacements (called only if validation passed)
     * @param logSuccess   logs the successful rename, given the number of references updated
     * @return true if the rename was applied, false if it was rejected or failed
     */
    private boolean applyRename(String renameVerb, String noRefEntity,
                                Supplier<String> validate,
                                Supplier<List<TextReplacement>> finder,
                                IntConsumer logSuccess) {
        try {
            String error = validate.get();
            if (error != null) {
                showError(error);
                return false;
            }

            List<TextReplacement> replacements = finder.get();
            if (replacements.isEmpty()) {
                showError("No references found for " + noRefEntity);
                return false;
            }

            // Apply all replacements as a single atomic undo operation.
            applyReplacements(replacements);

            logSuccess.accept(replacements.size());
            return true;

        } catch (Exception e) {
            logger.error("Error during {}", renameVerb, e);
            showError("Failed to " + renameVerb + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Finds all legitimate references to a node, each anchored to the exact column
     * range of the match so that replacement cannot touch look-alike text elsewhere
     * on the line (keys, comments, other names sharing a substring).
     *
     * @param text        The current document text
     * @param oldName     The node to find references for
     * @param newName     The new name to replace with
     * @param parsedModel The parsed model
     * @return List of range-anchored text replacements to perform
     */
    List<TextReplacement> findNodeReferences(String text, String oldName, String newName,
                                             INIModelParser.ParsedModel parsedModel) {
        List<TextReplacement> replacements = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        // 1. Rename the node section header: [node.OldName] -> [node.NewName]
        INIModelParser.Section nodeSection = parsedModel.getSections().get("node." + oldName);
        if (nodeSection != null) {
            String headerLine = lineAt(lines, nodeSection.getStartLine());
            String oldHeader = "[node." + oldName + "]";
            int column = headerLine != null ? headerLine.indexOf(oldHeader) : -1;
            if (column >= 0) {
                replacements.add(new TextReplacement(
                    nodeSection.getStartLine(),
                    column,
                    oldHeader,
                    "[node." + newName + "]"
                ));
            } else {
                logger.warn("Header '[node.{}]' not found on line {}", oldName, nodeSection.getStartLine());
            }
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
                        addValueSpanReplacement(replacements, lines, prop.getLineNumber(), oldName, newName);
                    }
                }
            }
        }

        // 3. Find output references: node.NodeName.property
        // Output references are stored separately in the parsed model (not as section properties)
        Pattern nodeRefPattern = Pattern.compile("\\bnode\\." + Pattern.quote(oldName) + "\\.");
        String nodeRefReplacement = "node." + newName + ".";
        for (String outputRef : parsedModel.getOutputReferences()) {
            // Match: node.OldName.anything
            if (nodeRefPattern.matcher(outputRef).find()) {
                Integer lineNumber = parsedModel.getOutputReferenceLineNumbers().get(outputRef);
                if (lineNumber != null) {
                    addPatternMatches(replacements, lines, lineNumber, nodeRefPattern, false, nodeRefReplacement);
                }
            }
        }

        // 4. Find node references in function expressions: node.NodeName.property within property values
        // These can appear in any property value, especially function_expression types
        for (INIModelParser.Section section : parsedModel.getSections().values()) {
            if (!section.getName().startsWith("node.")) continue;

            for (INIModelParser.Property prop : section.getProperties().values()) {
                // Check if this property value contains a reference to the old node name
                if (nodeRefPattern.matcher(prop.getValue()).find()) {
                    addPatternMatches(replacements, lines, prop.getLineNumber(), nodeRefPattern, true, nodeRefReplacement);
                }
            }
        }

        return replacements;
    }

    /**
     * Finds all legitimate references to an input file.
     *
     * @param text        The current document text
     * @param oldPath     The old file path
     * @param newPath     The new file path
     * @param parsedModel The parsed model
     * @return List of range-anchored text replacements to perform
     */
    List<TextReplacement> findInputFileReferences(String text, String oldPath, String newPath,
                                                  INIModelParser.ParsedModel parsedModel) {
        List<TextReplacement> replacements = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        String oldPathSanitised = EngineNames.sanitizeFileName(oldPath);
        String newPathSanitised = EngineNames.sanitizeFileName(newPath);

        // 1. Replace the input file path in [inputs] section
        addInputsLinePathReplacement(replacements, lines, parsedModel, oldPath, newPath);

        // 2 & 3. Rewrite data.{name}.* references in property values and output references
        addDataReferenceReplacements(replacements, lines, parsedModel, oldPathSanitised, newPathSanitised);

        return replacements;
    }

    /**
     * Finds all references to an input file, converting it to aliased form.
     *
     * @param text        The current document text
     * @param oldPath     The old file path
     * @param newAlias    The new alias (already engine-sanitised)
     * @param parsedModel The parsed model
     * @return List of range-anchored text replacements to perform
     */
    List<TextReplacement> findInputFileReferencesAddAlias(String text, String oldPath, String newAlias,
                                                          INIModelParser.ParsedModel parsedModel) {
        List<TextReplacement> replacements = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        String oldPathSanitised = EngineNames.sanitizeFileName(oldPath);
        String newAliasSanitised = EngineNames.sanitize(newAlias);

        // 1. Replace the input file path in [inputs] section with alias = path format
        addInputsLinePathReplacement(replacements, lines, parsedModel, oldPath, newAlias + " = " + oldPath);

        // 2 & 3. Rewrite data.{name}.* references in property values and output references
        addDataReferenceReplacements(replacements, lines, parsedModel, oldPathSanitised, newAliasSanitised);

        return replacements;
    }

    /**
     * Finds all legitimate references to an input file alias.
     *
     * @param text        The current document text
     * @param oldAlias    The old file alias
     * @param newAlias    The new file alias
     * @param parsedModel The parsed model
     * @return List of range-anchored text replacements to perform
     */
    List<TextReplacement> findInputFileAliasReferences(String text, String oldAlias, String newAlias,
                                                       INIModelParser.ParsedModel parsedModel) {
        List<TextReplacement> replacements = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        String oldAliasSanitised = EngineNames.sanitize(oldAlias);
        String newAliasSanitised = EngineNames.sanitize(newAlias);

        // 1. Replace the alias key on its "alias = path" line in the [inputs] section.
        // Anchored to the key position so a path that happens to contain the alias
        // text is never rewritten.
        Integer inputLineNumber = parsedModel.getInputFileAliasLineNumbers().get(oldAlias);
        String aliasLine = inputLineNumber != null ? lineAt(lines, inputLineNumber) : null;
        if (aliasLine != null) {
            Matcher keyMatcher = Pattern.compile("^\\s*(" + Pattern.quote(oldAlias) + ")\\s*=").matcher(aliasLine);
            if (keyMatcher.find()) {
                replacements.add(new TextReplacement(
                        inputLineNumber,
                        keyMatcher.start(1),
                        oldAlias,
                        newAlias
                ));
            } else {
                logger.warn("Alias key '{}' not found on line {}", oldAlias, inputLineNumber);
            }
        } else {
            logger.warn("Input file alias '{}' not found in parsed model", oldAlias);
        }

        // 2 & 3. Rewrite data.{alias}.* references in property values and output references
        addDataReferenceReplacements(replacements, lines, parsedModel, oldAliasSanitised, newAliasSanitised);

        return replacements;
    }

    /**
     * Adds the replacement of an input file path on its [inputs] line, anchored to
     * the exact position of the path text (searched backwards so the path portion of
     * an "alias = path" line is matched, never the alias).
     */
    private void addInputsLinePathReplacement(List<TextReplacement> replacements, String[] lines,
                                              INIModelParser.ParsedModel parsedModel,
                                              String oldPath, String newText) {
        Integer inputLineNumber = parsedModel.getInputFileLineNumbers().get(oldPath);
        String inputLine = inputLineNumber != null ? lineAt(lines, inputLineNumber) : null;
        if (inputLine == null) {
            logger.warn("Input file path '{}' not found in parsed model", oldPath);
            return;
        }
        int end = commentStartColumn(inputLine);
        int column = inputLine.lastIndexOf(oldPath, end - oldPath.length());
        if (column >= 0) {
            replacements.add(new TextReplacement(inputLineNumber, column, oldPath, newText));
        } else {
            logger.warn("Input file path '{}' not found on line {}", oldPath, inputLineNumber);
        }
    }

    /**
     * Adds range-anchored replacements for every {@code data.<oldName>.} reference in
     * node property values and output references.
     */
    private void addDataReferenceReplacements(List<TextReplacement> replacements, String[] lines,
                                              INIModelParser.ParsedModel parsedModel,
                                              String oldNameSanitised, String newNameSanitised) {
        Pattern dataRefPattern = Pattern.compile("\\bdata\\." + Pattern.quote(oldNameSanitised) + "\\.");
        String dataRefReplacement = "data." + newNameSanitised + ".";

        // Property values (e.g., data.patterns_csv.by_name.pattern_1 in expressions)
        for (INIModelParser.Section section : parsedModel.getSections().values()) {
            if (!section.getName().startsWith("node.")) continue;

            for (INIModelParser.Property prop : section.getProperties().values()) {
                // Check if this property value contains a reference to the old name
                if (dataRefPattern.matcher(prop.getValue()).find()) {
                    addPatternMatches(replacements, lines, prop.getLineNumber(), dataRefPattern, true, dataRefReplacement);
                }
            }
        }

        // Output references: data.{name}.*
        for (String outputRef : parsedModel.getOutputReferences()) {
            if (dataRefPattern.matcher(outputRef).find()) {
                Integer lineNumber = parsedModel.getOutputReferenceLineNumbers().get(outputRef);
                if (lineNumber != null) {
                    addPatternMatches(replacements, lines, lineNumber, dataRefPattern, false, dataRefReplacement);
                }
            }
        }
    }

    /**
     * Adds a replacement for a property whose entire (trimmed) value is the old name
     * (e.g. {@code ds_1 = oldName}), anchored to the value span: after the first
     * {@code =}, before any inline comment, whitespace-trimmed. The key and any
     * comment on the same line are untouchable by construction.
     */
    private void addValueSpanReplacement(List<TextReplacement> replacements, String[] lines,
                                         int lineNumber, String oldName, String newName) {
        String line = lineAt(lines, lineNumber);
        if (line == null) {
            return;
        }
        int eq = line.indexOf('=');
        if (eq < 0) {
            return; // value on a continuation line; nothing to rename on this line
        }
        int end = commentStartColumn(line);
        int valueStart = eq + 1;
        while (valueStart < end && Character.isWhitespace(line.charAt(valueStart))) {
            valueStart++;
        }
        int valueEnd = end;
        while (valueEnd > valueStart && Character.isWhitespace(line.charAt(valueEnd - 1))) {
            valueEnd--;
        }
        if (line.substring(valueStart, valueEnd).equals(oldName)) {
            replacements.add(new TextReplacement(lineNumber, valueStart, oldName, newName));
        }
    }

    /**
     * Adds one range-anchored replacement per match of {@code pattern} on the given
     * line, searching only up to any inline comment and — when {@code valueRegionOnly}
     * — only after the first {@code =}.
     */
    private void addPatternMatches(List<TextReplacement> replacements, String[] lines, int lineNumber,
                                   Pattern pattern, boolean valueRegionOnly, String newText) {
        String line = lineAt(lines, lineNumber);
        if (line == null) {
            return;
        }
        int from = 0;
        if (valueRegionOnly) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                return; // reference sits on a continuation line; out of reach of line-based rename
            }
            from = eq + 1;
        }
        int end = commentStartColumn(line);
        if (from >= end) {
            return;
        }
        Matcher matcher = pattern.matcher(line).region(from, end);
        while (matcher.find()) {
            replacements.add(new TextReplacement(lineNumber, matcher.start(), matcher.group(), newText));
        }
    }

    /**
     * Returns the raw text of a 1-based line number, or {@code null} if out of range.
     */
    private static String lineAt(String[] lines, int lineNumber) {
        int index = lineNumber - 1;
        return (index >= 0 && index < lines.length) ? lines[index] : null;
    }

    /**
     * Returns the column where an inline comment ({@code #} or {@code ;}) starts on
     * the line, or the line length if there is none. Mirrors
     * {@code INIModelParser.removeComments}, so ranges computed here address the same
     * region the parser read values from.
     */
    private static int commentStartColumn(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '#' || c == ';') {
                return i;
            }
        }
        return line.length();
    }

    /**
     * Converts range-anchored {@link TextReplacement}s to editor
     * {@link com.kalix.ide.editor.EnhancedTextEditor.LineReplacement}s and applies
     * them as a single atomic undo operation.
     */
    private void applyReplacements(List<TextReplacement> replacements) {
        List<com.kalix.ide.editor.EnhancedTextEditor.LineReplacement> lineReplacements = new ArrayList<>();
        for (TextReplacement replacement : replacements) {
            lineReplacements.add(new com.kalix.ide.editor.EnhancedTextEditor.LineReplacement(
                    replacement.getLineNumber(),
                    replacement.getStartColumn(),
                    replacement.getOldText(),
                    replacement.getNewText()
            ));
        }
        replacementApplier.accept(lineReplacements);
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
     * Inserts a new node template from a map right-click: the node's {@code loc} is the
     * clicked world location, while its <em>section</em> goes below the last selected
     * node (or at the bottom when nothing is selected). Text position is the logical
     * calculation sequence, not a geographic statement, so the two are independent.
     *
     * @param nodeType          The template key (e.g. "gr4j", "storage")
     * @param worldX            The map x-coordinate to insert into the template's {@code loc}
     * @param worldY            The map y-coordinate to insert into the template's {@code loc}
     * @param selectedNodeNames The currently selected nodes (may be empty or null)
     * @return true if the template was inserted, false on failure
     */
    public boolean insertNodeTemplateAtLocation(String nodeType, double worldX, double worldY,
                                                 java.util.Collection<String> selectedNodeNames) {
        String text = editor.getText();
        return insertNodeTemplate(nodeType, worldX, worldY, text,
            NodeInsertionPoint.forSelection(text, selectedNodeNames));
    }

    /**
     * Inserts a new node template from the text-editor context menu: the section goes
     * relative to the caret (see {@link NodeInsertionPoint}), at the given world
     * location — typically the centre of the map view, since a text-editor invocation
     * knows no click location.
     *
     * @param nodeType The template key (e.g. "gr4j", "storage")
     * @param worldX   The map x-coordinate to insert into the template's {@code loc}
     * @param worldY   The map y-coordinate to insert into the template's {@code loc}
     * @return true if the template was inserted, false on failure
     */
    public boolean insertNodeTemplateNearCursor(String nodeType, double worldX, double worldY) {
        String text = editor.getText();
        return insertNodeTemplate(nodeType, worldX, worldY, text,
            NodeInsertionPoint.forAnchor(text, editor.getCaretPosition()));
    }

    private boolean insertNodeTemplate(String nodeType, double worldX, double worldY, String text, int offset) {
        try {
            String uniqueName = generateUniqueNodeName(nodeType, text);
            String templateText = buildTemplateText(nodeType, uniqueName, worldX, worldY);
            insertTemplateAt(offset, templateText);
            logger.info("Inserted node template '{}' as '{}' at ({}, {})", nodeType, uniqueName, worldX, worldY);
            return true;
        } catch (Exception e) {
            logger.error("Error inserting node template", e);
            showError("Failed to insert node template: " + e.getMessage());
            return false;
        }
    }

    /**
     * Picks a unique node name for a newly-inserted template: {@code nodeType},
     * or {@code nodeType_2}, {@code nodeType_3}, ... if that's already taken.
     *
     * <p>Existing names come from the shared section grammar, not from the linter's
     * parser: this is text surgery, and the text is the only thing that is definitely
     * up to date.</p>
     */
    private String generateUniqueNodeName(String nodeType, String text) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (NodeSectionLocator.NodeSection section : NodeSectionLocator.findAll(text)) {
            taken.add(section.nodeName());
        }
        String candidate = nodeType;
        int suffix = 2;
        while (taken.contains(candidate)) {
            candidate = nodeType + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    /**
     * Builds the INI text for a node template: joins its lines, replaces the
     * header with {@code [node.<uniqueName>]}, and substitutes the
     * {@code %%X%%}/{@code %%Y%%} coordinate placeholders.
     */
    private String buildTemplateText(String nodeType, String uniqueName, double x, double y) {
        List<String> lines = NodeTemplateCatalog.getNodeTypes().get(nodeType);
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Unknown node type: " + nodeType);
        }
        String joined = String.join("\n", lines);
        joined = joined.replaceFirst("\\[node\\.[^\\]]+\\]", "[node." + uniqueName + "]");
        String xText = String.format(java.util.Locale.ROOT, "%.2f", x);
        String yText = String.format(java.util.Locale.ROOT, "%.2f", y);
        joined = joined.replace("%%X%%", xText).replace("%%Y%%", yText);
        return joined;
    }

    /**
     * Applies {@link TemplateSplice} to the document as a single atomic edit, so the
     * insertion undoes in one step.
     */
    private void insertTemplateAt(int offset, String templateText) throws javax.swing.text.BadLocationException {
        TemplateSplice.Splice splice = TemplateSplice.compute(editor.getText(), offset, templateText);

        editor.beginAtomicEdit();
        try {
            javax.swing.text.Document doc = editor.getDocument();
            doc.remove(splice.start(), splice.end() - splice.start());
            doc.insertString(splice.start(), splice.text(), null);
        } finally {
            editor.endAtomicEdit();
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
     * Represents a range-anchored text replacement: on line {@code lineNumber},
     * {@code oldText} beginning at column {@code startColumn} is replaced by
     * {@code newText}.
     */
    static class TextReplacement {
        private final int lineNumber;
        private final int startColumn; // 0-based column of oldText within the line
        private final String oldText;
        private final String newText;

        public TextReplacement(int lineNumber, int startColumn, String oldText, String newText) {
            this.lineNumber = lineNumber;
            this.startColumn = startColumn;
            this.oldText = oldText;
            this.newText = newText;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public int getStartColumn() {
            return startColumn;
        }

        public String getOldText() {
            return oldText;
        }

        public String getNewText() {
            return newText;
        }
    }
}
