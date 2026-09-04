package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.parsing.IniSyntax;
import com.kalix.ide.editor.EditorPosition;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.schema.ParameterDefinition;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Point;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Shows a hover tooltip with property help (description, type, constraints, required/optional
 * status) when the pointer rests on a property key in a node section. The dwell/positioning/
 * lifecycle machinery lives in {@link DwellTooltipSupport}; this class supplies the cheap
 * hot-path candidate check and the deferred property analysis.
 *
 * <p>Defers to validation tooltips when both would be shown on the same line.
 */
public class PropertyHoverTooltipManager extends DwellTooltipSupport {

    // Resolved per lookup (not captured at construction) so hover help follows
    // schema reloads instead of describing the startup schema forever.
    private final SchemaManager schemaManager;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final IntPredicate hasValidationIssue; // Function to check if a line has validation issue

    // Cached icon for property help tooltips
    private final FontIcon helpIcon = FontIcon.of(FontAwesomeSolid.INFO_CIRCLE, 12, new Color(70, 130, 180));

    public PropertyHoverTooltipManager(RSyntaxTextArea textArea,
                                      SchemaManager schemaManager,
                                      Supplier<INIModelParser.ParsedModel> modelSupplier,
                                      IntPredicate hasValidationIssue) {
        super(textArea, BoxLayout.X_AXIS, new Color(255, 255, 240)); // Light ivory background
        this.schemaManager = schemaManager;
        this.modelSupplier = modelSupplier;
        this.hasValidationIssue = hasValidationIssue;
    }

    @Override
    protected Target probe(Point point) {
        return candidateFor(point); // a Candidate (which is a Target), or null
    }

    @Override
    protected boolean populate(Target target) {
        PropertyInfo propertyInfo = analyzeCandidate((Candidate) target);
        if (propertyInfo == null) {
            return false;
        }
        buildTooltipContent(propertyInfo);
        return true;
    }

    /**
     * Property information extracted from mouse position.
     */
    private static class PropertyInfo {
        String propertyKey;
        String nodeType;
        String sectionName;
        int line; // 1-based
        boolean isRequired;
        boolean isOptional;
        boolean isDsnode;

        PropertyInfo(String propertyKey, String nodeType, String sectionName, int line,
                    boolean isRequired, boolean isOptional, boolean isDsnode) {
            this.propertyKey = propertyKey;
            this.nodeType = nodeType;
            this.sectionName = sectionName;
            this.line = line;
            this.isRequired = isRequired;
            this.isOptional = isOptional;
            this.isDsnode = isDsnode;
        }
    }

    /**
     * A cheap mouse-move hit: a position that MIGHT be a property key, identified
     * from the single line's text alone. Confirmed by the full analysis in the
     * dwell-timer callback.
     */
    private static class Candidate implements Target {
        final int line;   // 1-based
        final int offset; // document offset under the pointer

        Candidate(int line, int offset) {
            this.line = line;
            this.offset = offset;
        }

        @Override
        public int line() {
            return line;
        }
    }

    /**
     * Cheap per-mouse-move check: resolves the pointer to a document offset and
     * line via the document's element tree, bails if a validation tooltip takes
     * precedence, and inspects only that line's text. No document copy, no parse.
     */
    private Candidate candidateFor(Point point) {
        int offset = textArea.viewToModel2D(point);
        if (offset < 0) {
            return null;
        }
        try {
            javax.swing.text.Document doc = textArea.getDocument();
            javax.swing.text.Element root = doc.getDefaultRootElement();
            int lineIndex = root.getElementIndex(offset);
            javax.swing.text.Element lineElement = root.getElement(lineIndex);
            int lineStart = lineElement.getStartOffset();
            int line = lineIndex + 1; // 1-based

            // Check if validation tooltip should take precedence
            if (hasValidationIssue.test(line)) {
                return null; // Let validation tooltip show instead
            }

            String lineText = doc.getText(lineStart, lineElement.getEndOffset() - lineStart);
            if (!isPossiblePropertyKeyPosition(lineText, offset - lineStart)) {
                return null;
            }
            return new Candidate(line, offset);
        } catch (BadLocationException e) {
            return null;
        }
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
     * Full analysis of a candidate position, deferred to the dwell timer so it
     * never runs on the raw mouse-move path: copies the document, resolves the
     * parsed model, and classifies the position via {@link EditorPosition}.
     * Returns null if the position is not a known property key of a typed node.
     */
    private PropertyInfo analyzeCandidate(Candidate candidate) {
        String fullText = textArea.getText();
        INIModelParser.ParsedModel model = modelSupplier.get();
        EditorPosition position = EditorPosition.analyze(fullText, candidate.offset, model);

        // Check if we're on a property header and NOT in value position
        if (!position.isOnPropertyHeader() || position.isInValuePosition()) {
            return null; // Not on property key
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

        // Get node type definition to determine required/optional status
        LinterSchema schema = schemaManager.getCurrentSchema();
        if (schema == null) {
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

        return new PropertyInfo(propertyKey, nodeType, sectionName, candidate.line,
                              isRequired, isOptional, isDsnode);
    }

    private void buildTooltipContent(PropertyInfo propertyInfo) {
        // Clear previous content
        tooltipPanel.removeAll();

        // Add help icon
        JLabel iconLabel = new JLabel(helpIcon);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        tooltipPanel.add(iconLabel);

        // Build tooltip HTML content
        String htmlContent = formatPropertyTooltip(propertyInfo);

        // Create label with HTML content
        JLabel contentLabel = new JLabel(htmlContent);
        contentLabel.setFont(contentLabel.getFont().deriveFont(12f));
        tooltipPanel.add(contentLabel);
    }

    /**
     * Formats the property tooltip content with description, type, constraints, and status.
     */
    private String formatPropertyTooltip(PropertyInfo propertyInfo) {
        StringBuilder html = new StringBuilder("<html>");

        // Property name and status badge
        html.append("<b>").append(propertyInfo.propertyKey).append("</b>");

        String status;
        if (propertyInfo.isRequired) {
            status = "required";
        } else if (propertyInfo.isDsnode) {
            status = "downstream link";
        } else {
            status = "optional";
        }
        html.append(" <i>(").append(status).append(")</i>");

        // Get parameter definition from schema
        LinterSchema schema = schemaManager.getCurrentSchema();
        NodeTypeDefinition nodeDef = (schema != null) ? schema.getNodeType(propertyInfo.nodeType) : null;
        if (nodeDef != null) {
            ParameterDefinition paramDef = nodeDef.getParameterDefinition(propertyInfo.propertyKey);
            if (paramDef != null) {
                // Description
                if (paramDef.description != null && !paramDef.description.isEmpty()) {
                    html.append("<br><br>").append(paramDef.description);
                }

                // Type
                if (paramDef.type != null && !paramDef.type.isEmpty()) {
                    html.append("<br>Type: <code>").append(paramDef.type).append("</code>");
                }

                // Min constraint
                if (paramDef.min != null) {
                    html.append("<br>Min: ").append(formatNumber(paramDef.min));
                }

                // Max constraint
                if (paramDef.max != null) {
                    html.append("<br>Max: ").append(formatNumber(paramDef.max));
                }

                // Expected count
                if (paramDef.count != null) {
                    html.append("<br>Expected values: ").append(paramDef.count);
                }
            }
        }

        html.append("</html>");
        return html.toString();
    }

    /**
     * Formats a number for display, removing unnecessary decimal places.
     */
    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.format("%d", (long) value);
        } else {
            return String.format("%s", value);
        }
    }
}
