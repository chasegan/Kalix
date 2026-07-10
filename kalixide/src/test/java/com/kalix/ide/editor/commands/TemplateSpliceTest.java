package com.kalix.ide.editor.commands;

import com.kalix.ide.model.NodeInsertionPoint;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the blank-line normalisation around an inserted node template. These pin
 * the bugs the insertion logic has already had once: templates landing at EOF, and
 * blank lines accumulating on repeated insertion at the same seam.
 */
class TemplateSpliceTest {

    private static final String TEMPLATE = "[node.gauge]\ntype=gauge\nloc=1.00,2.00";

    /** Splice the template in at the offset the real code would choose for this caret. */
    private static String insertAtCaret(String text, int caret) {
        int offset = NodeInsertionPoint.forAnchor(text, caret);
        return TemplateSplice.applyTo(text, TemplateSplice.compute(text, offset, TEMPLATE));
    }

    private static String insertAt(String text, int offset) {
        return TemplateSplice.applyTo(text, TemplateSplice.compute(text, offset, TEMPLATE));
    }

    // --- Blank lines around the seam ---

    @Test
    void leavesOneBlankLineOnEachSideBetweenSections() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        int caret = text.indexOf("loc = 1, 1");
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n[node.b]\nloc = 2, 2\n",
            insertAtCaret(text, caret));
    }

    @Test
    void collapsesAnOversizedGapRatherThanAddingToIt() {
        String text = "[node.a]\nloc = 1, 1\n\n\n\n\n[node.b]\nloc = 2, 2\n";
        int caret = text.indexOf("loc = 1, 1");
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n[node.b]\nloc = 2, 2\n",
            insertAtCaret(text, caret));
    }

    @Test
    void insertsABlankLineWhereThereWasNone() {
        String text = "[node.a]\nloc = 1, 1\n[node.b]\nloc = 2, 2\n";
        int caret = text.indexOf("loc = 1, 1");
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n[node.b]\nloc = 2, 2\n",
            insertAtCaret(text, caret));
    }

    /**
     * The bug 29392e9 fixed forwards, checked in both directions. Repeatedly inserting
     * at the same seam must not grow the gap — including the seam above the first node,
     * which the old one-sided normalisation never touched.
     */
    @Test
    void repeatedInsertionAtTheSameSeamIsIdempotentInGapSize() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n";
        String once = insertAt(text, NodeInsertionPoint.forAnchor(text, text.indexOf("loc = 1, 1")));
        String twice = insertAt(once, NodeInsertionPoint.forAnchor(once, once.indexOf("loc = 1, 1")));

        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n" + TEMPLATE + "\n\n[node.b]\nloc = 2, 2\n",
            twice);
    }

    @Test
    void repeatedInsertionAboveTheFirstNodeDoesNotGrowTheGapAbove() {
        String text = "[kalix]\nstart = 2000-01-01\n\n[node.a]\nloc = 1, 1\n";
        String once = insertAtCaret(text, text.indexOf("start"));
        String twice = insertAtCaret(once, once.indexOf("start"));

        assertEquals(
            "[kalix]\nstart = 2000-01-01\n\n" + TEMPLATE + "\n\n" + TEMPLATE + "\n\n[node.a]\nloc = 1, 1\n",
            twice);
    }

    // --- Document boundaries ---

    @Test
    void atEndOfDocumentEndsWithASingleNewline() {
        String text = "[node.a]\nloc = 1, 1\n";
        assertEquals("[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n", insertAt(text, text.length()));
    }

    @Test
    void trailingBlankLinesAtEndOfDocumentAreCollapsed() {
        String text = "[node.a]\nloc = 1, 1\n\n\n\n";
        assertEquals("[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n", insertAt(text, text.length()));
    }

    @Test
    void atStartOfDocumentHasNoLeadingBlankLine() {
        String text = "[node.a]\nloc = 1, 1\n";
        assertEquals(TEMPLATE + "\n\n[node.a]\nloc = 1, 1\n", insertAt(text, 0));
    }

    @Test
    void emptyDocumentYieldsTemplateAndNewline() {
        assertEquals(TEMPLATE + "\n", insertAt("", 0));
    }

    @Test
    void whitespaceOnlyDocumentIsReplacedEntirely() {
        assertEquals(TEMPLATE + "\n", insertAt("\n\n\n", 1));
    }

    @Test
    void offsetsOutsideTheDocumentAreClamped() {
        String text = "[node.a]\nloc = 1, 1\n";
        assertEquals("[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n", insertAt(text, text.length() + 50));
        assertEquals(TEMPLATE + "\n\n[node.a]\nloc = 1, 1\n", insertAt(text, -50));
    }

    /**
     * A naive whitespace scan runs from the seam straight through the next line's
     * indentation. The removed span must stop at that line's start.
     */
    @Test
    void indentationOfTheFollowingLineIsPreserved() {
        String text = "[node.a]\nloc = 1, 1\n\n    [node.b]\n    loc = 2, 2\n";
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n    [node.b]\n    loc = 2, 2\n",
            insertAt(text, text.indexOf("\n\n") + 1));
    }

    /** Symmetrically, trailing spaces on the preceding content line are not eaten. */
    @Test
    void trailingSpacesOnThePrecedingLineArePreserved() {
        String text = "[node.a]\nloc = 1, 1   \n\n[node.b]\n";
        assertEquals(
            "[node.a]\nloc = 1, 1   \n\n" + TEMPLATE + "\n\n[node.b]\n",
            insertAt(text, text.indexOf("\n\n") + 1));
    }

    // --- End to end, through the insertion rule ---

    /**
     * The indented-header regression. The old ad-hoc regex anchored '[' to column zero,
     * so an indented header below the caret was invisible and the template fell to EOF.
     * The shared grammar trims, so it lands below the caret's node.
     */
    @Test
    void indentedHeaderBelowTheCaretNoLongerSendsTheTemplateToEof() {
        String text = "[node.a]\nloc = 1, 1\n\n  [node.b]\n  loc = 2, 2\n";
        String result = insertAtCaret(text, text.indexOf("loc = 1, 1"));
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n  [node.b]\n  loc = 2, 2\n",
            result);
    }

    /** A comment block introducing the next node stays attached to it. */
    @Test
    void commentBlockIntroducingTheNextNodeStaysWithThatNode() {
        String text = "[node.a]\nloc = 1, 1\n\n# calibration point\n[node.b]\nloc = 2, 2\n";
        String result = insertAtCaret(text, text.indexOf("loc = 1, 1"));
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n" + TEMPLATE + "\n\n# calibration point\n[node.b]\nloc = 2, 2\n",
            result);
    }

    /** The map path: below the last selected node, wherever the click was. */
    @Test
    void selectionDrivenInsertionLandsBelowTheLastSelectedNode() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n\n[node.c]\nloc = 3, 3\n";
        int offset = NodeInsertionPoint.forSelection(text, Set.of("a", "b"));
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n\n" + TEMPLATE + "\n\n[node.c]\nloc = 3, 3\n",
            insertAt(text, offset));
    }

    @Test
    void withNothingSelectedInsertionLandsAtTheBottom() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n\n[outputs]\nnode.a.dsflow\n";
        int offset = NodeInsertionPoint.forSelection(text, Set.of());
        assertEquals(
            "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n\n" + TEMPLATE + "\n\n[outputs]\nnode.a.dsflow\n",
            insertAt(text, offset));
    }
}
