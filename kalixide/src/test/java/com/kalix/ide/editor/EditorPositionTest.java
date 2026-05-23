package com.kalix.ide.editor;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPositionTest {

    /** Caret position immediately after the given marker substring. */
    private static int after(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) {
            throw new IllegalArgumentException("Marker not found: " + marker);
        }
        return idx + marker.length();
    }

    private static EditorPosition analyze(String text, int caretPos) {
        return EditorPosition.analyze(text, caretPos, INIModelParser.parse(text));
    }

    private static EditorPosition analyzeWithoutModel(String text, int caretPos) {
        return EditorPosition.analyze(text, caretPos, null);
    }

    // --- Basic facts --------------------------------------------------------

    @Test
    void caretAtStartOfFile_lineZero() {
        EditorPosition p = analyze("[node.foo]\ntype = gr4j", 0);
        assertEquals(0, p.getLineNumber());
        assertEquals(0, p.getCaretPos());
        assertEquals("[node.foo]", p.getCurrentLine());
        assertEquals("", p.getTextBeforeCursorOnLine());
    }

    @Test
    void caretMidLine_textBeforeCursorMatches() {
        String text = "[node.foo]\ntype = gr4j";
        int caret = after(text, "type = gr");
        EditorPosition p = analyze(text, caret);
        assertEquals(1, p.getLineNumber());
        assertEquals("type = gr4j", p.getCurrentLine());
        assertEquals("type = gr", p.getTextBeforeCursorOnLine());
    }

    @Test
    void caretClampedIntoRange() {
        EditorPosition p = analyze("abc", 999);
        assertEquals(3, p.getCaretPos());
    }

    @Test
    void nullText_treatedAsEmpty() {
        EditorPosition p = EditorPosition.analyze(null, 0, null);
        assertEquals(0, p.getCaretPos());
        assertEquals(0, p.getLineNumber());
        assertNull(p.getSectionName());
        assertFalse(p.isInProperty());
    }

    // --- Section detection (unified rule) ----------------------------------

    @Test
    void cursorOnSectionHeaderLine_isInThatSection() {
        // This is the unified rule: cursor on a [section] line counts as inside it.
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "[node");
        EditorPosition p = analyze(text, caret);
        assertEquals("node.foo", p.getSectionName());
        assertTrue(p.isOnSectionHeader());
    }

    @Test
    void cursorInsidePropertyLine_findsSection() {
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "type = ");
        EditorPosition p = analyze(text, caret);
        assertEquals("node.foo", p.getSectionName());
        assertFalse(p.isOnSectionHeader());
    }

    @Test
    void cursorInOutputsSection_returnsOutputs() {
        String text = "[outputs]\nnode.foo.flow\n";
        int caret = after(text, "node.foo.flow");
        EditorPosition p = analyze(text, caret);
        assertEquals("outputs", p.getSectionName());
    }

    @Test
    void cursorOnInputsHeaderLine_returnsInputs() {
        // Off-by-one fix from ContextDetector: cursor on a non-node section
        // header is now correctly inside the section.
        String text = "[inputs]\ndata.csv\n";
        int caret = after(text, "[inp");
        EditorPosition p = analyze(text, caret);
        assertEquals("inputs", p.getSectionName());
    }

    @Test
    void cursorBeforeAnySection_returnsNullSection() {
        EditorPosition p = analyze("\n\n[node.foo]\ntype = gr4j\n", 0);
        assertNull(p.getSectionName());
    }

    @Test
    void sectionDetectionWithoutModel_returnsNull() {
        // Option B: parser is the source of truth. Without a model we return
        // null for structural facts rather than risk disagreeing with the
        // parser via an independent text-scan.
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "type = ");
        EditorPosition p = analyzeWithoutModel(text, caret);
        assertNull(p.getSectionName());
        assertNull(p.getNodeType());
        assertFalse(p.isOnSectionHeader());
        // Line-level facts that don't need the parser still work:
        assertEquals(1, p.getLineNumber());
        assertEquals("type", p.getPropertyKey());
        assertTrue(p.isInValuePosition());
    }

    @Test
    void indentedSectionLikeLine_notTreatedAsSection() {
        // Inside a multi-line value, an indented "[foo]" is a continuation of
        // the property above per parser semantics. With Option B we trust the
        // parser, so onSectionHeader stays false and the cursor remains in the
        // surrounding section.
        String text = "[node.foo]\nx1 = 1,\n    [bogus]\nx2 = 2\n";
        int caret = after(text, "    [bogus]");
        EditorPosition p = analyze(text, caret);
        assertEquals("node.foo", p.getSectionName());
        assertFalse(p.isOnSectionHeader());
    }

    // --- Node type ----------------------------------------------------------

    @Test
    void nodeType_resolvedFromModel() {
        String text = "[node.foo]\ntype = gr4j\nx1 = 100\n";
        int caret = after(text, "x1 = ");
        EditorPosition p = analyze(text, caret);
        assertEquals("gr4j", p.getNodeType());
    }

    @Test
    void nodeType_nullForNonNodeSection() {
        String text = "[outputs]\nnode.foo.flow\n";
        int caret = after(text, "node.foo.flow");
        EditorPosition p = analyze(text, caret);
        assertNull(p.getNodeType());
    }

    @Test
    void nodeType_nullWhenModelMissing() {
        String text = "[node.foo]\ntype = gr4j\nx1 = 100\n";
        int caret = after(text, "x1 = ");
        EditorPosition p = analyzeWithoutModel(text, caret);
        assertNull(p.getNodeType());
    }

    // --- Property resolution (including continuations) ---------------------

    @Test
    void cursorOnPropertyHeader_resolvesItself() {
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "type = ");
        EditorPosition p = analyze(text, caret);
        assertTrue(p.isInProperty());
        assertTrue(p.isOnPropertyHeader());
        assertFalse(p.isOnContinuationLine());
        assertEquals("type", p.getPropertyKey());
        assertEquals(1, p.getOwningPropertyLine());
    }

    @Test
    void cursorOnContinuationLine_resolvesToOwningHeader() {
        String text = "[node.foo]\nx1 = 1,\n    2,\n    3\n";
        int caret = after(text, "    2");
        EditorPosition p = analyze(text, caret);
        assertTrue(p.isInProperty());
        assertFalse(p.isOnPropertyHeader());
        assertTrue(p.isOnContinuationLine());
        assertEquals("x1", p.getPropertyKey());
        assertEquals(1, p.getOwningPropertyLine());
    }

    @Test
    void cursorOnBlankLine_notInProperty() {
        String text = "[node.foo]\nx1 = 1\n\nx2 = 2\n";
        int caret = after(text, "x1 = 1\n");
        EditorPosition p = analyze(text, caret);
        assertFalse(p.isInProperty());
        assertNull(p.getPropertyKey());
        assertEquals(-1, p.getOwningPropertyLine());
    }

    @Test
    void cursorOnSectionHeader_notInProperty() {
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "[node");
        EditorPosition p = analyze(text, caret);
        assertFalse(p.isInProperty());
    }

    // --- Value position -----------------------------------------------------

    @Test
    void caretBeforeEquals_notInValuePosition() {
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "[node.foo]\nty");
        EditorPosition p = analyze(text, caret);
        assertTrue(p.isOnPropertyHeader());
        assertFalse(p.isCaretPastEquals());
        assertFalse(p.isInValuePosition());
    }

    @Test
    void caretRightAfterEquals_inValuePosition() {
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "type =");  // immediately after '='
        EditorPosition p = analyze(text, caret);
        assertTrue(p.isCaretPastEquals());
        assertTrue(p.isInValuePosition());
    }

    @Test
    void caretExactlyAtEquals_notInValuePosition() {
        // Cursor sits at the '=' position itself (left of it), so length == eqIdx.
        String text = "[node.foo]\ntype = gr4j\n";
        int caret = after(text, "type ");  // just before the '='
        EditorPosition p = analyze(text, caret);
        assertFalse(p.isCaretPastEquals());
        assertFalse(p.isInValuePosition());
    }

    @Test
    void caretOnContinuation_inValuePositionRegardlessOfColumn() {
        String text = "[node.foo]\nx1 = 1,\n    2,\n";
        // Caret in the leading whitespace of the continuation line.
        int caret = after(text, "x1 = 1,\n  ");
        EditorPosition p = analyze(text, caret);
        assertTrue(p.isOnContinuationLine());
        assertTrue(p.isInValuePosition());
    }

    // --- Cross-checks against the bug we just fixed ------------------------

    @Test
    void multiLineValue_continuationLineHasRightPropertyKey() {
        // Mirrors the autocomplete bug: on line 3, ctx.propertyKey should still
        // be "x1" so dsnode / value completions can fire.
        String text = "[node.foo]\ntype = gr4j\nx1 = 1,\n    2,\n    3\n";
        int caret = after(text, "    2,\n   ");
        EditorPosition p = analyze(text, caret);
        assertEquals("x1", p.getPropertyKey());
        assertEquals("gr4j", p.getNodeType());
        assertEquals("node.foo", p.getSectionName());
        assertTrue(p.isInValuePosition());
    }

    @Test
    void flushLeftCommentBreaksContinuation_perParserRule() {
        // From the regression example: a flush-left comment terminates the
        // chain, so the indented line after it is not owned by the header.
        String text = "[node.foo]\nrain = 4 + a\n   + 1234\n# log(3)\n   4\n";
        int caret = after(text, "# log(3)\n   ");
        EditorPosition p = analyze(text, caret);
        assertFalse(p.isInProperty());
        assertEquals("node.foo", p.getSectionName());
    }
}
