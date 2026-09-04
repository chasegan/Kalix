package com.kalix.ide.linter.ui;

import com.kalix.ide.editor.EditorPosition;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.parsing.IniSyntax;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.schema.ParameterDefinition;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The hover tip for a property key in a node section: description, type,
 * constraints, and required/optional/downstream status from the schema.
 * Content only — the tip's window, timing and dismissal belong to
 * {@link HoverTipSupplier}, which asks this source only when the line carries
 * no validation issue.
 *
 * <p>Two-stage lookup, because {@link javax.swing.ToolTipManager} asks for tip
 * text on every mouse move while a tip is showing: a cheap check on the single
 * line's text says whether the pointer could be on a key at all, and the full
 * analysis (document copy, parsed model, {@link EditorPosition}) runs only past
 * that gate. Its results are memoised per line for as long as the parsed model
 * and the schema are the same objects — a document edit yields a new parsed
 * model and a schema reload a new schema, so the memo can never describe a
 * type line that has since changed. Resting on a key costs one analysis, not
 * one per pixel, and sweeping back over lines already seen costs none.</p>
 */
public final class PropertyTooltips implements HoverTipSupplier.Source {

    private static final String HELP_COLOUR = "#4682b4";

    private final RSyntaxTextArea textArea;
    // Resolved per lookup (not captured at construction) so hover help follows
    // schema reloads instead of describing the startup schema forever.
    private final SchemaManager schemaManager;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;

    // Memo of analyses, valid for one (parsed model, schema) pair: line -> tip,
    // with a null value recording "no help at this line".
    private INIModelParser.ParsedModel memoModel;
    private LinterSchema memoSchema;
    private final Map<Integer, String> memoByLine = new HashMap<>();

    public PropertyTooltips(RSyntaxTextArea textArea,
                            SchemaManager schemaManager,
                            Supplier<INIModelParser.ParsedModel> modelSupplier) {
        this.textArea = textArea;
        this.schemaManager = schemaManager;
        this.modelSupplier = modelSupplier;
    }

    @Override
    public String tipAt(int line, int offset) {
        String lineText;
        int column;
        try {
            Document doc = textArea.getDocument();
            Element root = doc.getDefaultRootElement();
            Element lineElement = root.getElement(line - 1);
            if (lineElement == null) {
                return null; // past the last line
            }
            int lineStart = lineElement.getStartOffset();
            lineText = doc.getText(lineStart, lineElement.getEndOffset() - lineStart);
            column = offset - lineStart;
        } catch (BadLocationException e) {
            return null;
        }

        // Cheap gate, on the single line's text alone.
        if (!isPossiblePropertyKeyPosition(lineText, column)) {
            return null;
        }

        INIModelParser.ParsedModel model = modelSupplier.get();
        LinterSchema schema = schemaManager.getCurrentSchema();
        if (model == null || schema == null) {
            return null; // nothing to describe with; and no memo, since neither object can change identity
        }
        if (model != memoModel || schema != memoSchema) {
            memoByLine.clear();
            memoModel = model;
            memoSchema = schema;
        }
        if (memoByLine.containsKey(line)) {
            return memoByLine.get(line);
        }
        String tip = analyse(offset, model, schema);
        memoByLine.put(line, tip);
        return tip;
    }

    /**
     * True if {@code column} within {@code lineText} could sit on a property KEY:
     * the line must look like a {@code key = value} header (not blank, not a
     * continuation, comment, or section header; an '=' before any comment marker)
     * and the column must fall before the '='. Package-private for tests.
     */
    static boolean isPossiblePropertyKeyPosition(String lineText, int column) {
        if (lineText == null || lineText.isEmpty() || column < 0) {
            return false;
        }
        char first = lineText.charAt(0);
        if (Character.isWhitespace(first) || first == '#' || first == '[') {
            return false;
        }
        int equals = IniSyntax.stripComment(lineText).indexOf('=');
        if (equals < 0) {
            return false; // no '=' before any comment: not a property header
        }
        return column < equals; // on the key portion, not the value
    }

    /**
     * Full analysis of a position past the cheap gate: copies the document,
     * resolves the parsed model, and classifies the position via
     * {@link EditorPosition}. Returns the tip HTML, or null if the position is
     * not a known property key of a typed node.
     */
    private String analyse(int offset, INIModelParser.ParsedModel model, LinterSchema schema) {
        String fullText = textArea.getText();
        EditorPosition position = EditorPosition.analyze(fullText, offset, model);

        // Check if we're on a property header and NOT in value position
        if (!position.isOnPropertyHeader() || position.isInValuePosition()) {
            return null;
        }

        String propertyKey = position.getPropertyKey();
        String nodeType = position.getNodeType();
        String sectionName = position.getSectionName();
        if (propertyKey == null || sectionName == null) {
            return null;
        }

        // Only show tooltips for node sections with a valid type
        if (!sectionName.startsWith("node.") || nodeType == null) {
            return null;
        }

        NodeTypeDefinition nodeDef = schema.getNodeType(nodeType);
        if (nodeDef == null) {
            return null;
        }

        boolean isRequired = nodeDef.requiredParams.contains(propertyKey);
        boolean isOptional = nodeDef.optionalParams.contains(propertyKey);
        boolean isDsnode = nodeDef.dsnodeParams.contains(propertyKey);

        // Only show tooltips for known properties
        if (!isRequired && !isOptional && !isDsnode) {
            return null;
        }

        String status = isRequired ? "required" : isDsnode ? "downstream link" : "optional";
        return html(propertyKey, status, nodeDef.getParameterDefinition(propertyKey));
    }

    /**
     * The tip HTML: name and status, then whatever the schema says about the
     * parameter. Package-private for tests.
     */
    static String html(String propertyKey, String status, ParameterDefinition paramDef) {
        StringBuilder html = new StringBuilder("<html>");
        html.append("<font color='").append(HELP_COLOUR).append("'><b>&#x24D8;</b></font>&nbsp; ");
        html.append("<b>").append(HoverTipSupplier.escapeHtml(propertyKey)).append("</b>");
        html.append(" <i>(").append(status).append(")</i>");

        if (paramDef != null) {
            if (paramDef.description != null && !paramDef.description.isEmpty()) {
                html.append("<br><br>").append(HoverTipSupplier.escapeHtml(paramDef.description));
            }
            if (paramDef.type != null && !paramDef.type.isEmpty()) {
                html.append("<br>Type: <code>").append(HoverTipSupplier.escapeHtml(paramDef.type)).append("</code>");
            }
            if (paramDef.min != null) {
                html.append("<br>Min: ").append(formatNumber(paramDef.min));
            }
            if (paramDef.max != null) {
                html.append("<br>Max: ").append(formatNumber(paramDef.max));
            }
            if (paramDef.count != null) {
                html.append("<br>Expected values: ").append(paramDef.count);
            }
        }

        return html.append("</html>").toString();
    }

    /** Formats a number for display, removing unnecessary decimal places. */
    private static String formatNumber(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }
}
