package com.kalix.ide.editor;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.parsing.IniContinuation;

/**
 * A neutral, parser-aware view of "where is the cursor in this INI document?".
 *
 * <p>Both the right-click context-command system and the Ctrl+Space autocomplete
 * provider need to answer essentially the same questions about the caret's
 * structural location - which section it is in, which property (if any) it
 * belongs to with continuation lines resolved, whether it is past an {@code =},
 * and so on. This class computes those facts once from
 * {@code (text, caretPos, parsedModel)} so the two consumers can classify
 * cursor context against the same source of truth.</p>
 *
 * <p>The parsed model is the authoritative source for structure (sections,
 * properties, node types). When the model is null - which happens in degraded
 * states such as a parse failure or before the first parse - structural fields
 * ({@code sectionName}, {@code nodeType}, {@code onSectionHeader}) are returned
 * as null/false. Line-level facts ({@code currentLine}, {@code lineNumber},
 * {@code propertyKey} via continuation resolution) remain available because
 * they do not require the parser.</p>
 *
 * <p>Instances are immutable. Use {@link #analyze(String, int, INIModelParser.ParsedModel)}
 * to build one.</p>
 */
public final class EditorPosition {

    private final int caretPos;
    private final int lineNumber;                  // 0-indexed
    private final String currentLine;              // raw text of the cursor's line
    private final String textBeforeCursorOnLine;   // chars on this line before the caret
    private final String sectionName;              // null if outside any section
    private final String nodeType;                 // null unless in a node.* section with a discoverable type
    private final int owningPropertyLine;          // -1 if not in a property; index of the header line otherwise
    private final String propertyKey;              // null if owningPropertyLine < 0
    private final boolean caretPastEquals;         // caret is strictly past the '=' on the CURRENT line
    private final boolean onSectionHeader;         // current line matches a [section] header

    private EditorPosition(int caretPos,
                           int lineNumber,
                           String currentLine,
                           String textBeforeCursorOnLine,
                           String sectionName,
                           String nodeType,
                           int owningPropertyLine,
                           String propertyKey,
                           boolean caretPastEquals,
                           boolean onSectionHeader) {
        this.caretPos = caretPos;
        this.lineNumber = lineNumber;
        this.currentLine = currentLine;
        this.textBeforeCursorOnLine = textBeforeCursorOnLine;
        this.sectionName = sectionName;
        this.nodeType = nodeType;
        this.owningPropertyLine = owningPropertyLine;
        this.propertyKey = propertyKey;
        this.caretPastEquals = caretPastEquals;
        this.onSectionHeader = onSectionHeader;
    }

    /**
     * Analyzes the caret position in the given INI text.
     *
     * @param text         the full document text (may be null; treated as empty)
     * @param caretPos     the caret offset (clamped into range)
     * @param parsedModel  the parsed model from {@link INIModelParser}, or null
     *                     if not currently available; the analyzer falls back to
     *                     text-only scanning for section detection in that case
     * @return an immutable {@link EditorPosition} describing the caret's structural location
     */
    public static EditorPosition analyze(String text, int caretPos, INIModelParser.ParsedModel parsedModel) {
        String safeText = (text != null) ? text : "";
        int safeCaret = Math.max(0, Math.min(caretPos, safeText.length()));
        String[] lines = safeText.split("\n", -1);

        int lineNumber = countNewlines(safeText, safeCaret);
        if (lineNumber < 0 || lineNumber >= lines.length) {
            return new EditorPosition(safeCaret, 0, "", "", null, null, -1, null, false, false);
        }

        String currentLine = lines[lineNumber];
        int lineStartOffset = computeLineStartOffset(safeText, safeCaret);
        String textBeforeCursorOnLine = safeText.substring(lineStartOffset, safeCaret);

        // Section-related facts come from the parsed model only - the parser
        // is the source of truth for what counts as a section (e.g. an indented
        // "[foo]" is a value continuation, not a section header). Without a
        // model these fields are simply null/false.
        String sectionName = findSectionName(parsedModel, lineNumber);
        boolean onSectionHeader = isOnSectionHeader(parsedModel, lineNumber);
        String nodeType = findNodeType(parsedModel, sectionName);

        // Continuation-aware property resolution.
        int owningPropertyLine = IniContinuation.findOwningPropertyLine(lines, lineNumber);
        String propertyKey = null;
        if (owningPropertyLine >= 0) {
            String header = lines[owningPropertyLine];
            int eq = header.indexOf('=');
            if (eq >= 0) {
                propertyKey = header.substring(0, eq).trim();
            } else {
                // Helper contract guarantees header contains '='; defensive only.
                owningPropertyLine = -1;
            }
        }

        // Past '=' on the CURRENT line. Only meaningful when the current line
        // is itself the property header; on a continuation, the cursor is
        // already considered in-value regardless of column.
        int currentEq = currentLine.indexOf('=');
        boolean caretPastEquals = currentEq >= 0 && textBeforeCursorOnLine.length() > currentEq;

        return new EditorPosition(safeCaret, lineNumber, currentLine, textBeforeCursorOnLine,
                sectionName, nodeType, owningPropertyLine, propertyKey,
                caretPastEquals, onSectionHeader);
    }

    // --- Raw facts ----------------------------------------------------------

    public int getCaretPos() { return caretPos; }
    public int getLineNumber() { return lineNumber; }
    public String getCurrentLine() { return currentLine; }
    public String getTextBeforeCursorOnLine() { return textBeforeCursorOnLine; }
    public String getSectionName() { return sectionName; }
    public String getNodeType() { return nodeType; }
    public int getOwningPropertyLine() { return owningPropertyLine; }
    public String getPropertyKey() { return propertyKey; }
    public boolean isCaretPastEquals() { return caretPastEquals; }
    public boolean isOnSectionHeader() { return onSectionHeader; }

    // --- Derived predicates -------------------------------------------------

    /** True if the caret belongs to some property (header or continuation). */
    public boolean isInProperty() {
        return owningPropertyLine >= 0;
    }

    /** True if the current line is itself the property header (key = value). */
    public boolean isOnPropertyHeader() {
        return owningPropertyLine >= 0 && owningPropertyLine == lineNumber;
    }

    /** True if the current line is an indented continuation of a property above it. */
    public boolean isOnContinuationLine() {
        return owningPropertyLine >= 0 && owningPropertyLine != lineNumber;
    }

    /**
     * True if the cursor is in a value position - either on a continuation line
     * (always in-value) or on a property header with the caret past the {@code =}.
     */
    public boolean isInValuePosition() {
        return isOnContinuationLine() || (isOnPropertyHeader() && caretPastEquals);
    }

    // --- Helpers ------------------------------------------------------------

    private static int countNewlines(String text, int upTo) {
        int n = 0;
        int end = Math.min(upTo, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static int computeLineStartOffset(String text, int caretPos) {
        if (caretPos <= 0) return 0;
        int lastNewline = text.lastIndexOf('\n', caretPos - 1);
        return lastNewline + 1;
    }

    /**
     * Returns the section that contains the given 0-based line number, taking
     * the parser's 1-based start lines as authoritative. Returns null when the
     * model is unavailable or no section covers the line.
     */
    private static String findSectionName(INIModelParser.ParsedModel model, int lineNumber) {
        if (model == null) {
            return null;
        }
        String found = null;
        int bestStart = -1;
        for (INIModelParser.Section s : model.getSections().values()) {
            int start0 = s.getStartLine() - 1;  // parser is 1-based; we use 0-based here
            if (start0 <= lineNumber && start0 > bestStart) {
                found = s.getName();
                bestStart = start0;
            }
        }
        return found;
    }

    /**
     * True iff the given line is the first line of some section per the parsed
     * model. Avoids matching indented "[foo]" lines that the parser would treat
     * as value continuations.
     */
    private static boolean isOnSectionHeader(INIModelParser.ParsedModel model, int lineNumber) {
        if (model == null) {
            return false;
        }
        for (INIModelParser.Section s : model.getSections().values()) {
            if (s.getStartLine() - 1 == lineNumber) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the node type for a {@code node.*} section by reading its
     * {@code type = ...} property from the parsed model, or null if the model
     * is unavailable, the section is not a node section, or no type is set.
     */
    private static String findNodeType(INIModelParser.ParsedModel model, String sectionName) {
        if (model == null || sectionName == null || !sectionName.startsWith("node.")) {
            return null;
        }
        INIModelParser.Section section = model.getSections().get(sectionName);
        if (section == null) {
            return null;
        }
        INIModelParser.Property typeProp = section.getProperties().get("type");
        return (typeProp != null) ? typeProp.getValue() : null;
    }
}
