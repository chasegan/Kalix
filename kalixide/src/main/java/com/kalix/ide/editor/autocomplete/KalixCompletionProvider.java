package com.kalix.ide.editor.autocomplete;

import com.kalix.ide.io.DataSourceHeaderReader;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.schema.ParameterDefinition;
import com.kalix.ide.linter.schema.SectionDefinition;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-aware completion provider for Kalix INI model files.
 * Dynamically rebuilds completions on each invocation based on cursor context,
 * providing section headers, property names, node references, and output recorders.
 */
public class KalixCompletionProvider extends DefaultCompletionProvider {

    private static final Logger logger = LoggerFactory.getLogger(KalixCompletionProvider.class);
    private static final Pattern SECTION_HEADER_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)]\\s*$");

    private final LinterSchema schema;
    private final Supplier<INIModelParser.ParsedModel> modelSupplier;
    private final InputDataRegistry inputDataRegistry;

    private CompletionContext currentContext;

    // Anchor offset for value contexts: computed by scanning left from the caret
    // for contiguous word characters (alphanumeric, '.', '_'). Recomputed on each
    // invocation so it stays correct across multiple completion sessions.
    private int valueAnchorOffset = -1;

    enum ContextType {
        LINE_START,
        OUTPUT_RECORDERS,
        DOWNSTREAM_REFERENCE,
        GENERAL_VALUE,
        UNKNOWN
    }

    static class CompletionContext {
        ContextType type = ContextType.UNKNOWN;
        String sectionName;
        String nodeType;
        String propertyKey;
    }

    public KalixCompletionProvider(LinterSchema schema,
                                   Supplier<INIModelParser.ParsedModel> modelSupplier,
                                   InputDataRegistry inputDataRegistry) {
        this.schema = schema;
        this.modelSupplier = modelSupplier;
        this.inputDataRegistry = inputDataRegistry;
    }

    @Override
    public List<Completion> getCompletions(JTextComponent tc) {
        clear();

        currentContext = detectContext(tc);

        // Manage the value anchor for value contexts.
        // Always recompute by scanning left for word characters so the anchor
        // stays correct across multiple completion sessions on the same line.
        boolean isValueContext = currentContext.type == ContextType.DOWNSTREAM_REFERENCE
                || currentContext.type == ContextType.GENERAL_VALUE;
        if (isValueContext) {
            valueAnchorOffset = computeWordStartOffset(tc, tc.getCaretPosition());
        } else {
            valueAnchorOffset = -1;
        }

        switch (currentContext.type) {
            case LINE_START:
                addSectionHeaderCompletions();
                if (currentContext.sectionName != null) {
                    addPropertyCompletions(currentContext.sectionName, currentContext.nodeType);
                }
                break;

            case OUTPUT_RECORDERS:
                addOutputRecorderCompletions();
                break;

            case DOWNSTREAM_REFERENCE:
                addDownstreamNodeCompletions();
                break;

            case GENERAL_VALUE:
                addGeneralValueCompletions();
                break;

            default:
                break;
        }

        // Use substring matching instead of the library's prefix-only binary search.
        // This allows typing "Hinz" to match "node.0034_Hinze_Dam.volume".
        String enteredText = getAlreadyEnteredText(tc);
        List<Completion> results;

        if (enteredText == null || enteredText.isEmpty()) {
            results = new ArrayList<>(completions);
        } else {
            String filterLower = enteredText.toLowerCase();
            results = new ArrayList<>();
            for (Completion c : completions) {
                if (c.getReplacementText().toLowerCase().contains(filterLower)) {
                    results.add(c);
                }
            }
        }

        // Return a placeholder when no matches so the popup stays open.
        // The user can backspace to see real completions reappear.
        if (results.isEmpty() && currentContext.type != ContextType.UNKNOWN) {
            List<Completion> placeholder = new ArrayList<>();
            BasicCompletion noMatch = new BasicCompletion(this,
                    enteredText != null ? enteredText : "", "No matches");
            placeholder.add(noMatch);
            return placeholder;
        }

        return results;
    }

    @Override
    public String getAlreadyEnteredText(JTextComponent tc) {
        if (currentContext == null) {
            return super.getAlreadyEnteredText(tc);
        }

        try {
            int caretPos = tc.getCaretPosition();
            String textUpToCaret = tc.getText(0, caretPos);
            int lineStart = textUpToCaret.lastIndexOf('\n') + 1;
            String lineBeforeCursor = textUpToCaret.substring(lineStart);

            switch (currentContext.type) {
                case LINE_START:
                    return lineBeforeCursor.stripLeading();

                case OUTPUT_RECORDERS:
                    return lineBeforeCursor.stripLeading();

                case DOWNSTREAM_REFERENCE:
                case GENERAL_VALUE:
                    if (valueAnchorOffset >= 0 && caretPos >= valueAnchorOffset) {
                        return tc.getText(valueAnchorOffset, caretPos - valueAnchorOffset);
                    }
                    return "";

                default:
                    return super.getAlreadyEnteredText(tc);
            }
        } catch (BadLocationException e) {
            return super.getAlreadyEnteredText(tc);
        }
    }

    @Override
    protected boolean isValidChar(char ch) {
        return super.isValidChar(ch) || ch == '[' || ch == ']' || ch == '.' || ch == '=';
    }

    /**
     * Scans left from the given offset to find the start of a contiguous word
     * (alphanumeric, '.', '_' characters). Returns the offset where the word starts.
     * If no word chars are found (e.g., cursor is after a space), returns caretPos itself.
     */
    private int computeWordStartOffset(JTextComponent tc, int caretPos) {
        try {
            String text = tc.getText(0, caretPos);
            int i = text.length() - 1;
            while (i >= 0) {
                char ch = text.charAt(i);
                if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_') {
                    i--;
                } else {
                    break;
                }
            }
            return i + 1;
        } catch (BadLocationException e) {
            return caretPos;
        }
    }

    // --- Context detection ---

    private CompletionContext detectContext(JTextComponent tc) {
        CompletionContext ctx = new CompletionContext();
        try {
            int caretPos = tc.getCaretPosition();
            String fullText = tc.getText(0, tc.getDocument().getLength());
            String textUpToCaret = fullText.substring(0, caretPos);
            String[] allLines = fullText.split("\n", -1);

            // Find which line the caret is on
            int lineIndex = 0;
            int pos = 0;
            for (int i = 0; i < allLines.length; i++) {
                int lineEnd = pos + allLines[i].length();
                if (caretPos <= lineEnd) {
                    lineIndex = i;
                    break;
                }
                pos = lineEnd + 1; // +1 for newline
            }

            String currentLine = allLines[lineIndex];
            int lineStartOffset = textUpToCaret.lastIndexOf('\n') + 1;
            String textBeforeCursorOnLine = textUpToCaret.substring(lineStartOffset);

            // Find current section
            ctx.sectionName = findCurrentSectionName(allLines, lineIndex);

            // Determine if we're in value position (after '=')
            int equalsIndex = currentLine.indexOf('=');
            boolean inValuePosition = equalsIndex >= 0 && textBeforeCursorOnLine.length() > equalsIndex;

            if ("outputs".equals(ctx.sectionName) && !inValuePosition) {
                // In outputs section: offer recorder completions (bare lines, no key=value)
                ctx.type = ContextType.OUTPUT_RECORDERS;
                return ctx;
            }

            if (!inValuePosition) {
                // At line start / key position
                ctx.type = ContextType.LINE_START;

                // Resolve node type if in a node section
                if (ctx.sectionName != null && ctx.sectionName.startsWith("node.")) {
                    ctx.nodeType = findNodeTypeInSection(allLines, lineIndex);
                }
                return ctx;
            }

            // In value position: determine what kind of value
            ctx.propertyKey = currentLine.substring(0, equalsIndex).trim();

            // Resolve node type for dsnode detection
            if (ctx.sectionName != null && ctx.sectionName.startsWith("node.")) {
                ctx.nodeType = findNodeTypeInSection(allLines, lineIndex);

                // Check if property is a dsnode param
                if (ctx.nodeType != null) {
                    NodeTypeDefinition nodeDef = schema.getNodeType(ctx.nodeType);
                    if (nodeDef != null && nodeDef.dsnodeParams.contains(ctx.propertyKey)) {
                        ctx.type = ContextType.DOWNSTREAM_REFERENCE;
                        return ctx;
                    }
                }
            }

            // General value context
            ctx.type = ContextType.GENERAL_VALUE;

        } catch (Exception e) {
            logger.warn("Error detecting completion context", e);
            ctx.type = ContextType.UNKNOWN;
        }
        return ctx;
    }

    private String findCurrentSectionName(String[] lines, int lineIndex) {
        for (int i = lineIndex; i >= 0; i--) {
            Matcher m = SECTION_HEADER_PATTERN.matcher(lines[i]);
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    /**
     * Finds the node type by scanning the current section for a "type = xxx" line.
     */
    private String findNodeTypeInSection(String[] lines, int currentLineIndex) {
        // Find the section header start
        int sectionStart = -1;
        for (int i = currentLineIndex; i >= 0; i--) {
            if (SECTION_HEADER_PATTERN.matcher(lines[i]).matches()) {
                sectionStart = i;
                break;
            }
        }
        if (sectionStart < 0) {
            return null;
        }

        // Scan forward from section header looking for "type = xxx"
        for (int i = sectionStart + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            // Stop at next section header
            if (SECTION_HEADER_PATTERN.matcher(lines[i]).matches()) {
                break;
            }
            if (line.startsWith("type") && line.contains("=")) {
                String value = line.substring(line.indexOf('=') + 1).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    // --- Completion builders ---

    private void addSectionHeaderCompletions() {
        // Standard sections from schema
        for (Map.Entry<String, SectionDefinition> entry : schema.getSections().entrySet()) {
            String sectionName = entry.getKey();
            SectionDefinition sectionDef = entry.getValue();
            String headerText = "[" + sectionName + "]";

            BasicCompletion completion = new BasicCompletion(this, headerText + "\n",
                    sectionDef.required ? "required section" : "section",
                    formatSectionDescription(sectionName, sectionDef));
            addCompletion(completion);
        }

        // Node section prefix
        BasicCompletion nodeCompletion = new BasicCompletion(this, "[node.",
                "node section",
                "<html><b>[node.&lt;type&gt;.&lt;name&gt;]</b><br><br>"
                        + "Define a new model node.<br>"
                        + "Format: <code>[node.name]</code><br>"
                        + "Then add <code>type = &lt;node_type&gt;</code> on the next line.</html>");
        addCompletion(nodeCompletion);
    }

    private void addPropertyCompletions(String sectionName, String nodeType) {
        if (sectionName.startsWith("node.") && nodeType != null) {
            NodeTypeDefinition nodeDef = schema.getNodeType(nodeType);
            if (nodeDef == null) {
                return;
            }

            for (String param : nodeDef.requiredParams) {
                if ("type".equals(param)) {
                    continue; // type is already set
                }
                addPropertyCompletion(param, nodeDef, "required");
            }
            for (String param : nodeDef.optionalParams) {
                addPropertyCompletion(param, nodeDef, "optional");
            }
            for (String param : nodeDef.dsnodeParams) {
                addPropertyCompletion(param, nodeDef, "downstream link");
            }
        } else if ("kalix".equals(sectionName)) {
            SectionDefinition kalixSection = schema.getSection("kalix");
            if (kalixSection != null && kalixSection.properties != null) {
                for (SectionDefinition.PropertyDefinition prop : kalixSection.properties.values()) {
                    BasicCompletion completion = new BasicCompletion(this, prop.name + " = ",
                            prop.required ? "required" : "optional",
                            "<html><b>" + prop.name + "</b>"
                                    + (prop.type != null ? "<br>Type: <code>" + prop.type + "</code>" : "")
                                    + "</html>");
                    addCompletion(completion);
                }
            }
        }
        // inputs/outputs/constants sections don't have schema-defined properties
    }

    private void addPropertyCompletion(String paramName, NodeTypeDefinition nodeDef, String category) {
        ParameterDefinition paramDef = nodeDef.getParameterDefinition(paramName);
        String description = formatParameterDescription(paramName, paramDef, category);

        BasicCompletion completion = new BasicCompletion(this, paramName + " = ",
                category, description);
        addCompletion(completion);
    }

    private void addOutputRecorderCompletions() {
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            return;
        }

        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            String nodeName = node.getNodeName();
            String nodeType = node.getNodeType();
            if (nodeType == null) {
                continue;
            }

            NodeTypeDefinition nodeDef = schema.getNodeType(nodeType);
            if (nodeDef == null) {
                continue;
            }

            for (String recorder : nodeDef.allowedOutputs) {
                String completionText = "node." + nodeName + "." + recorder;
                BasicCompletion completion = new BasicCompletion(this, completionText,
                        nodeType,
                        formatRecorderDescription(nodeName, nodeType, recorder));
                addCompletion(completion);
            }
        }
    }

    private void addDownstreamNodeCompletions() {
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            return;
        }

        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            String nodeName = node.getNodeName();
            String nodeType = node.getNodeType();

            BasicCompletion completion = new BasicCompletion(this, nodeName,
                    nodeType != null ? nodeType : "node",
                    "<html><b>" + nodeName + "</b>"
                            + (nodeType != null ? "<br>Type: " + nodeType : "")
                            + "</html>");
            addCompletion(completion);
        }
    }

    private void addGeneralValueCompletions() {
        INIModelParser.ParsedModel model = modelSupplier.get();
        if (model == null) {
            return;
        }

        // Offer all node.name.recorder combinations
        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            String nodeName = node.getNodeName();
            String nodeType = node.getNodeType();
            if (nodeType == null) {
                continue;
            }

            NodeTypeDefinition nodeDef = schema.getNodeType(nodeType);
            if (nodeDef == null) {
                continue;
            }

            for (String recorder : nodeDef.allowedOutputs) {
                String completionText = "node." + nodeName + "." + recorder;
                BasicCompletion completion = new BasicCompletion(this, completionText,
                        nodeType,
                        formatRecorderDescription(nodeName, nodeType, recorder));
                addCompletion(completion);
            }
        }

        // Data series references from [inputs] section
        addDataSeriesCompletions(model);
    }

    private void addDataSeriesCompletions(INIModelParser.ParsedModel model) {
        List<String> inputFiles = model.getInputFiles();
        if (inputFiles == null) {
            return;
        }

        // Trigger a non-blocking refresh check on the registry
        if (inputDataRegistry != null) {
            inputDataRegistry.refresh(inputFiles);
        }

        for (String filePath : inputFiles) {
            String cleansedName = cleanseFileName(filePath);
            if (cleansedName.isEmpty()) {
                continue;
            }

            // by_index.1 completion (always available)
            String byIndexText = "data." + cleansedName + ".by_index.1";
            BasicCompletion byIndexCompletion = new BasicCompletion(this, byIndexText,
                    "data reference",
                    "<html><b>" + byIndexText + "</b>"
                            + "<br><br>Data source: " + cleansedName
                            + "<br>File: " + filePath
                            + "<br><br>Change the index number to select"
                            + "<br>a different series from this file.</html>");
            addCompletion(byIndexCompletion);

            // by_name completions from cached file headers
            if (inputDataRegistry != null) {
                InputDataRegistry.CachedDataSource cached =
                        inputDataRegistry.getDataSources().get(filePath);
                if (cached != null) {
                    for (String seriesName : cached.getSeriesNames()) {
                        String byNameText = "data." + cleansedName + ".by_name." + seriesName;
                        BasicCompletion byNameCompletion = new BasicCompletion(this, byNameText,
                                "data reference",
                                "<html><b>" + byNameText + "</b>"
                                        + "<br><br>Data source: " + cleansedName
                                        + "<br>File: " + filePath
                                        + "<br>Series: " + seriesName + "</html>");
                        addCompletion(byNameCompletion);
                    }
                }
            }
        }
    }

    /**
     * Extracts the filename from a path and cleanses it for use as a data reference.
     * All non-alphanumeric characters are replaced with underscores.
     * e.g., "./some/folder/GS_daily-flows(all flows).csv" → "GS_daily_flows_all_flows__csv"
     */
    static String cleanseFileName(String filePath) {
        // Extract filename from path
        String fileName = filePath;
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            fileName = filePath.substring(lastSlash + 1);
        }
        return DataSourceHeaderReader.cleanseName(fileName);
    }

    // --- Description formatting ---

    private String formatParameterDescription(String paramName, ParameterDefinition paramDef, String category) {
        StringBuilder html = new StringBuilder("<html><b>");
        html.append(paramName).append("</b> <i>(").append(category).append(")</i>");

        if (paramDef != null) {
            if (paramDef.description != null && !paramDef.description.isEmpty()) {
                html.append("<br><br>").append(paramDef.description);
            }
            if (paramDef.type != null && !paramDef.type.isEmpty()) {
                html.append("<br>Type: <code>").append(paramDef.type).append("</code>");
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

        html.append("</html>");
        return html.toString();
    }

    private String formatRecorderDescription(String nodeName, String nodeType, String recorder) {
        return "<html><b>node." + nodeName + "." + recorder + "</b>"
                + "<br><br>Node: " + nodeName
                + "<br>Type: " + nodeType
                + "<br>Recorder: " + recorder
                + "</html>";
    }

    private String formatSectionDescription(String sectionName, SectionDefinition sectionDef) {
        StringBuilder html = new StringBuilder("<html><b>[" + sectionName + "]</b>");
        if (sectionDef.required) {
            html.append(" <i>(required)</i>");
        }
        if (sectionDef.properties != null && !sectionDef.properties.isEmpty()) {
            html.append("<br><br>Properties:");
            for (String propName : sectionDef.properties.keySet()) {
                html.append("<br>&nbsp;&nbsp;").append(propName);
            }
        }
        html.append("</html>");
        return html.toString();
    }

    private String formatNumber(Double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.intValue());
        }
        return String.valueOf(value);
    }
}
