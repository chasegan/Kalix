package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes cursor position and document structure to determine editing context.
 */
public class ContextDetector {

    private static final Logger logger = LoggerFactory.getLogger(ContextDetector.class);

    // Pattern for node section headers: [node.NodeName]
    private static final Pattern NODE_HEADER_PATTERN = Pattern.compile("^\\[node\\.([^\\]]+)\\]");

    /**
     * Detects the editing context at the given cursor position.
     *
     * @param caretPos    Current caret position
     * @param text        Full document text
     * @param selection   Currently selected text (may be empty)
     * @param parsedModel The parsed INI model (may be null if parsing failed)
     * @return EditorContext describing where the cursor is
     */
    public EditorContext detectContext(int caretPos, String text, String selection,
                                       INIModelParser.ParsedModel parsedModel) {
        EditorContext.Builder builder = new EditorContext.Builder()
            .caretPosition(caretPos)
            .selectedText(selection);

        try {
            // Find the line containing the caret
            String[] lines = text.split("\n", -1);
            int currentLine = findLineNumber(text, caretPos);
            builder.lineNumber(currentLine);

            if (currentLine < 0 || currentLine >= lines.length) {
                return builder.type(EditorContext.ContextType.UNKNOWN).build();
            }

            String line = lines[currentLine].trim();

            // Check if we're on a node header line
            Matcher nodeMatcher = NODE_HEADER_PATTERN.matcher(line);
            if (nodeMatcher.matches()) {
                String nodeName = nodeMatcher.group(1);
                builder.type(EditorContext.ContextType.NODE_HEADER)
                    .nodeName(nodeName)
                    .sectionName("node." + nodeName);

                // Try to get node type from parsed model
                if (parsedModel != null) {
                    INIModelParser.Section section = parsedModel.getSections().get("node." + nodeName);
                    if (section != null) {
                        INIModelParser.Property typeProp = section.getProperties().get("type");
                        if (typeProp != null) {
                            builder.nodeType(typeProp.getValue());
                        }
                    }
                }

                return builder.build();
            }

            // Check if we're inside a section (use parsed model if available)
            if (parsedModel != null) {
                String currentSection = findCurrentSection(parsedModel, currentLine);

                // Always set section name if we found one
                if (currentSection != null) {
                    builder.sectionName(currentSection);
                }

                if (currentSection != null && currentSection.startsWith("node.")) {
                    String nodeName = currentSection.substring(5); // Remove "node." prefix
                    builder.nodeName(nodeName);

                    INIModelParser.Section section = parsedModel.getSections().get(currentSection);
                    if (section != null) {
                        INIModelParser.Property typeProp = section.getProperties().get("type");
                        if (typeProp != null) {
                            builder.nodeType(typeProp.getValue());
                        }

                        // Check if we're on a property line
                        if (line.contains("=")) {
                            String key = line.substring(0, line.indexOf("=")).trim();
                            builder.propertyKey(key)
                                .type(EditorContext.ContextType.PROPERTY);
                            return builder.build();
                        }
                    }

                    builder.type(EditorContext.ContextType.NODE_SECTION);
                    return builder.build();
                }

                // Check if we're in outputs section
                if ("outputs".equals(currentSection)) {
                    builder.type(EditorContext.ContextType.OUTPUT_REFERENCE);
                    return builder.build();
                }

                // Check if we're in constants section
                if ("constants".equals(currentSection)) {
                    builder.type(EditorContext.ContextType.CONSTANTS);
                    return builder.build();
                }

                // Check if we're in inputs section on a file path line
                if ("inputs".equals(currentSection)) {
                    // Check if this line contains a file path (non-empty, non-comment line)
                    if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
                        // Get the actual file path from parsed model by matching line number
                        String filePath = findInputFileAtLine(parsedModel, currentLine + 1); // Convert 0-based to 1-based
                        if (filePath != null) {
                            builder.type(EditorContext.ContextType.INPUT_FILE)
                                .inputFilePath(filePath);
                            return builder.build();
                        }
                    }
                }
            }

            // Default to unknown context
            return builder.type(EditorContext.ContextType.UNKNOWN).build();

        } catch (Exception e) {
            logger.warn("Error detecting context", e);
            return builder.type(EditorContext.ContextType.UNKNOWN).build();
        }
    }

    /**
     * Finds the line number (0-indexed) for the given caret position.
     */
    private int findLineNumber(String text, int caretPos) {
        if (caretPos < 0 || caretPos > text.length()) {
            return -1;
        }

        int lineNumber = 0;
        for (int i = 0; i < caretPos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    /**
     * Finds which section the given line number belongs to.
     */
    private String findCurrentSection(INIModelParser.ParsedModel model, int lineNumber) {
        String currentSection = null;
        int closestLineBeforeCaret = -1;

        for (INIModelParser.Section section : model.getSections().values()) {
            int sectionStartLine = section.getStartLine();

            // Find the section that starts before or at the caret line
            if (sectionStartLine <= lineNumber && sectionStartLine > closestLineBeforeCaret) {
                currentSection = section.getName();
                closestLineBeforeCaret = sectionStartLine;
            }
        }

        return currentSection;
    }

    /**
     * Finds the input file path at the given line number (1-based).
     * Returns null if no input file is found at that line.
     */
    private String findInputFileAtLine(INIModelParser.ParsedModel model, int lineNumber) {
        for (java.util.Map.Entry<String, Integer> entry : model.getInputFileLineNumbers().entrySet()) {
            if (entry.getValue() == lineNumber) {
                return entry.getKey();
            }
        }
        return null;
    }
}
