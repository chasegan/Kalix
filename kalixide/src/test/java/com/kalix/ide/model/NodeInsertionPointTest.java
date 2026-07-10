package com.kalix.ide.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the node-insertion rule. Offsets are asserted by splicing a marker into
 * the text at the returned offset and comparing whole documents — an assertion on a
 * bare integer says nothing about whether the result reads correctly.
 */
class NodeInsertionPointTest {

    /** Renders what the document would look like with a new node spliced in. */
    private static String spliceAt(String text, int offset) {
        return text.substring(0, offset) + "<<NEW>>" + text.substring(offset);
    }

    private static final String THREE_NODES =
        "[kalix]\n" +
        "start = 2000-01-01\n" +
        "\n" +
        "[node.a]\n" +
        "loc = 1, 1\n" +
        "\n" +
        "[node.b]\n" +
        "loc = 2, 2\n" +
        "\n" +
        "[node.c]\n" +
        "loc = 3, 3\n" +
        "\n" +
        "[outputs]\n" +
        "node.a.dsflow\n";

    // --- Clause 1: anchor above the first node ---

    @Test
    void anchorAboveFirstNodeInsertsBeforeIt() {
        int caret = THREE_NODES.indexOf("start = 2000");
        int offset = NodeInsertionPoint.forAnchor(THREE_NODES, caret);
        assertEquals(THREE_NODES.indexOf("[node.a]"), offset);
    }

    @Test
    void anchorAtOffsetZeroInsertsBeforeFirstNode() {
        int offset = NodeInsertionPoint.forAnchor(THREE_NODES, 0);
        assertEquals(THREE_NODES.indexOf("[node.a]"), offset);
    }

    // --- Clause 2: anchor within a node section ---

    @Test
    void anchorInsideANodeInsertsBelowThatNode() {
        int caret = THREE_NODES.indexOf("loc = 2, 2");
        int offset = NodeInsertionPoint.forAnchor(THREE_NODES, caret);

        assertEquals(THREE_NODES.indexOf("loc = 2, 2") + "loc = 2, 2\n".length(), offset);

        // The offset is the seam directly after node b's last content line; the blank
        // line that already separated b from c stays on the far side of it.
        String spliced = spliceAt(THREE_NODES, offset);
        assertEquals("[node.b]\nloc = 2, 2\n<<NEW>>\n[node.c]",
            spliced.substring(spliced.indexOf("[node.b]"), spliced.indexOf("[node.c]") + "[node.c]".length()));
    }

    @Test
    void anchorOnANodeHeaderLineInsertsBelowThatNode() {
        int caret = THREE_NODES.indexOf("[node.b]");
        int offset = NodeInsertionPoint.forAnchor(THREE_NODES, caret);
        assertEquals(THREE_NODES.indexOf("loc = 2, 2") + "loc = 2, 2\n".length(), offset);
    }

    // --- Clause 3: anchor after the last node ---

    @Test
    void anchorInOutputsInsertsBelowTheLastNode() {
        int caret = THREE_NODES.indexOf("node.a.dsflow");
        int offset = NodeInsertionPoint.forAnchor(THREE_NODES, caret);
        assertEquals(THREE_NODES.indexOf("loc = 3, 3") + "loc = 3, 3\n".length(), offset);
    }

    @Test
    void anchorAtEndOfTextInsertsBelowTheLastNode() {
        int offset = NodeInsertionPoint.forAnchor(THREE_NODES, THREE_NODES.length());
        assertEquals(THREE_NODES.indexOf("loc = 3, 3") + "loc = 3, 3\n".length(), offset);
    }

    // --- The comment-block footgun ---

    /**
     * A comment block between two nodes introduces the node below it. Inserting at the
     * previous section's end() would land between the comment and its node; contentEnd
     * puts the new node above the block.
     */
    @Test
    void insertionLandsAboveACommentBlockThatIntroducesTheNextNode() {
        String text =
            "[node.a]\n" +
            "loc = 1, 1\n" +
            "\n" +
            "# The gauge below is the calibration point.\n" +
            "[node.b]\n" +
            "loc = 2, 2\n";

        int caret = text.indexOf("loc = 1, 1");
        int offset = NodeInsertionPoint.forAnchor(text, caret);

        assertEquals(
            "[node.a]\n" +
            "loc = 1, 1\n" +
            "<<NEW>>\n" +
            "# The gauge below is the calibration point.\n" +
            "[node.b]\n" +
            "loc = 2, 2\n",
            spliceAt(text, offset));
    }

    /** A caret inside that comment block still resolves to "below node a". */
    @Test
    void anchorInsideACommentBlockInsertsBelowThePrecedingNode() {
        String text = "[node.a]\nloc = 1, 1\n\n# introduces b\n[node.b]\nloc = 2, 2\n";
        int caret = text.indexOf("introduces");
        int offset = NodeInsertionPoint.forAnchor(text, caret);
        assertEquals(text.indexOf("\n\n") + 1, offset);
    }

    // --- Degenerate documents ---

    @Test
    void modelWithNoNodeSectionsAppendsAtTheEnd() {
        String text = "[kalix]\nstart = 2000-01-01\n\n[outputs]\n";
        assertEquals(text.length(), NodeInsertionPoint.forAnchor(text, 0));
        assertEquals(text.length(), NodeInsertionPoint.forSelection(text, Set.of()));
    }

    @Test
    void emptyAndNullTextAreSafe() {
        assertEquals(0, NodeInsertionPoint.forAnchor("", 0));
        assertEquals(0, NodeInsertionPoint.forAnchor(null, 5));
        assertEquals(0, NodeInsertionPoint.forSelection(null, Set.of("a")));
    }

    @Test
    void outOfRangeAnchorsAreClamped() {
        assertEquals(THREE_NODES.indexOf("[node.a]"), NodeInsertionPoint.forAnchor(THREE_NODES, -100));
        assertEquals(THREE_NODES.indexOf("loc = 3, 3") + "loc = 3, 3\n".length(),
            NodeInsertionPoint.forAnchor(THREE_NODES, THREE_NODES.length() + 100));
    }

    // --- Selection (the map path) ---

    @Test
    void singleSelectionInsertsBelowThatNode() {
        int offset = NodeInsertionPoint.forSelection(THREE_NODES, Set.of("b"));
        assertEquals(THREE_NODES.indexOf("loc = 2, 2") + "loc = 2, 2\n".length(), offset);
    }

    /** A selection is a Set: "last" must mean last in document order, not iteration order. */
    @Test
    void multiSelectionInsertsBelowTheLastInDocumentOrder() {
        int expected = THREE_NODES.indexOf("loc = 3, 3") + "loc = 3, 3\n".length();
        assertEquals(expected, NodeInsertionPoint.forSelection(THREE_NODES, Set.of("a", "c")));
        assertEquals(expected, NodeInsertionPoint.forSelection(THREE_NODES, Set.of("c", "a")));
        assertEquals(expected, NodeInsertionPoint.forSelection(THREE_NODES, List.of("c", "b", "a")));
    }

    @Test
    void emptySelectionInsertsAtTheBottom() {
        int expected = THREE_NODES.indexOf("loc = 3, 3") + "loc = 3, 3\n".length();
        assertEquals(expected, NodeInsertionPoint.forSelection(THREE_NODES, Set.of()));
        assertEquals(expected, NodeInsertionPoint.forSelection(THREE_NODES, null));
    }

    @Test
    void selectionOfANameNotInTheTextFallsBackToTheBottom() {
        int expected = THREE_NODES.indexOf("loc = 3, 3") + "loc = 3, 3\n".length();
        assertEquals(expected, NodeInsertionPoint.forSelection(THREE_NODES, Set.of("ghost")));
    }

    @Test
    void selectionWithDuplicateNamesResolvesToTheLastOccurrence() {
        String text = "[node.a]\nloc = 1, 1\n\n[node.b]\nloc = 2, 2\n\n[node.a]\nloc = 3, 3\n";
        int offset = NodeInsertionPoint.forSelection(text, Set.of("a"));
        assertEquals(text.length(), offset);
    }

    /** Selecting the first node still inserts below it, not above it. */
    @Test
    void selectingTheFirstNodeInsertsBelowIt() {
        int offset = NodeInsertionPoint.forSelection(THREE_NODES, Set.of("a"));
        assertEquals(THREE_NODES.indexOf("loc = 1, 1") + "loc = 1, 1\n".length(), offset);
    }

    // --- Line endings ---

    @Test
    void crlfDocumentsInsertAfterTheFullLineTerminator() {
        String text = "[node.a]\r\nloc = 1, 1\r\n\r\n[node.b]\r\nloc = 2, 2\r\n";
        int offset = NodeInsertionPoint.forSelection(text, Set.of("a"));
        assertEquals("[node.a]\r\nloc = 1, 1\r\n<<NEW>>\r\n[node.b]\r\nloc = 2, 2\r\n", spliceAt(text, offset));
    }
}
