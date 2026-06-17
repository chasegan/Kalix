package com.kalix.ide.editor.commands;

import com.kalix.ide.editor.EditorPosition;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an {@link EditorPosition} to an {@link EditorContext} - i.e. classifies
 * the caret's structural location into the domain enum used by the right-click
 * command system.
 *
 * <p>All structural analysis (line number, section detection, continuation
 * resolution, etc.) is delegated to {@link EditorPosition}; this class is just
 * the per-consumer adapter that decides which {@link EditorContext.ContextType}
 * applies and what fields the command system needs populated.</p>
 */
public class ContextDetector {

    private static final Logger logger = LoggerFactory.getLogger(ContextDetector.class);
    private static final String NODE_SECTION_PREFIX = "node.";

    // Copied from INIModelParser
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*(.*)\\s*$");

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

        EditorPosition position = EditorPosition.analyze(text, caretPos, parsedModel);
        EditorContext.Builder builder = new EditorContext.Builder()
                .caretPosition(caretPos)
                .selectedText(selection)
                .lineNumber(position.getLineNumber());

        try {
            String sectionName = position.getSectionName();
            if (sectionName != null) {
                builder.sectionName(sectionName);
            }

            // Node sections get their own header / property / body context types.
            if (sectionName != null && sectionName.startsWith(NODE_SECTION_PREFIX)) {
                return classifyNodeSection(builder, position, parsedModel);
            }

            // Other section types only get content-context on non-header lines.
            // The header line itself falls through to UNKNOWN (preserving the
            // pre-consolidation behavior; there is no command that targets a
            // bare section header today).
            if (sectionName != null && !position.isOnSectionHeader()) {
                EditorContext sectionBody = classifySectionBody(builder, position, parsedModel, sectionName);
                if (sectionBody != null) {
                    return sectionBody;
                }
            }

            return builder.type(EditorContext.ContextType.UNKNOWN).build();

        } catch (Exception e) {
            logger.warn("Error detecting context", e);
            return builder.type(EditorContext.ContextType.UNKNOWN).build();
        }
    }

    private EditorContext classifyNodeSection(EditorContext.Builder builder,
                                              EditorPosition position,
                                              INIModelParser.ParsedModel parsedModel) {
        String sectionName = position.getSectionName();
        builder.nodeName(sectionName.substring(NODE_SECTION_PREFIX.length()));
        if (position.getNodeType() != null) {
            builder.nodeType(position.getNodeType());
        }

        if (position.isOnSectionHeader()) {
            return builder.type(EditorContext.ContextType.NODE_HEADER).build();
        }

        if (position.isInProperty()) {
            String key = position.getPropertyKey();
            builder.propertyKey(key).type(EditorContext.ContextType.PROPERTY);
            // Use the parser's joined, comment-stripped value if we can find
            // it. (The cursor may sit on a continuation line; the key was
            // resolved from the owning header inside EditorPosition.)
            if (parsedModel != null) {
                INIModelParser.Section section = parsedModel.getSections().get(sectionName);
                if (section != null) {
                    INIModelParser.Property prop = section.getProperties().get(key);
                    if (prop != null) {
                        builder.propertyValue(prop.getValue());
                    }
                }
            }
            return builder.build();
        }

        return builder.type(EditorContext.ContextType.NODE_SECTION).build();
    }

    private EditorContext classifySectionBody(EditorContext.Builder builder,
                                              EditorPosition position,
                                              INIModelParser.ParsedModel parsedModel,
                                              String sectionName) {
        switch (sectionName) {
            case "outputs":
                return builder.type(EditorContext.ContextType.OUTPUT_REFERENCE).build();
            case "constants":
                return builder.type(EditorContext.ContextType.CONSTANTS).build();
            case "inputs":
                String line = position.getCurrentLine().trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
                    // Parser stores input-file line numbers 1-based.
                    String filePath = findInputFileAtLine(parsedModel, position.getLineNumber() + 1);
                    if (filePath != null) {
                            builder.type(EditorContext.ContextType.INPUT_FILE)
                                    .inputFilePath(filePath);
                    }
                    // Detect input file aliases
                    Matcher matcher = KEY_VALUE_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String alias = matcher.group(1);
                        builder.type(EditorContext.ContextType.INPUT_FILE_WITH_ALIAS) // overwrite type
                                .inputFileAlias(alias);
                    }
                    return builder.build();
                    }
                return null;
            default:
                return null;
        }
    }

    /**
     * Finds the input file path at the given 1-based line number, or null.
     */
    private String findInputFileAtLine(INIModelParser.ParsedModel model, int lineNumber1Based) {
        if (model == null) {
            return null;
        }
        for (java.util.Map.Entry<String, Integer> entry : model.getInputFileLineNumbers().entrySet()) {
            if (entry.getValue() == lineNumber1Based) {
                return entry.getKey();
            }
        }
        return null;
    }
}
