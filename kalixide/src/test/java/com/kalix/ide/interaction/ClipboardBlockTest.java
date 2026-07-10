package com.kalix.ide.interaction;

import com.kalix.ide.interaction.ClipboardEntry.NodeSectionData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the text a paste inserts: renaming, link rewriting, coordinate translation
 * and joining. Several of these pin failures of the ad-hoc regexes this class replaced —
 * a column-0-anchored ds pattern, an unanchored loc pattern, and a rename that could
 * cascade through its own output.
 */
class ClipboardBlockTest {

    private static NodeSectionData section(String name, String text, double x, double y, int order) {
        return new NodeSectionData(name, text, x, y, order);
    }

    // --- Copy suffix ---

    @Test
    void firstFreeCopySuffixIsChosen() {
        assertEquals("_copy1", ClipboardBlock.copySuffix(Set.of("a", "b"), List.of("a")));
    }

    @Test
    void copySuffixSkipsCollisions() {
        assertEquals("_copy3",
            ClipboardBlock.copySuffix(Set.of("a", "a_copy1", "a_copy2"), List.of("a")));
    }

    /** One suffix serves the whole paste, so it must clear every copied name at once. */
    @Test
    void copySuffixClearsEveryCopiedName() {
        assertEquals("_copy2",
            ClipboardBlock.copySuffix(Set.of("a", "b", "b_copy1"), List.of("a", "b")));
    }

    // --- Renaming ---

    @Test
    void headerIsRenamed() {
        String text = "[node.a]\ntype = inflow\nloc = 1, 2\n";
        assertEquals("[node.a_copy1]\ntype = inflow\nloc = 1, 2\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1")));
    }

    /** An indented header is a real section, and its indentation must survive. */
    @Test
    void indentedHeaderIsRenamedAndKeepsItsIndent() {
        String text = "  [node.a]\n  loc = 1, 2\n";
        assertEquals("  [node.a_copy1]\n  loc = 1, 2\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1")));
    }

    @Test
    void linksToOtherPastedNodesAreRewritten() {
        String text = "[node.a]\nloc = 1, 2\nds_1 = b\n";
        assertEquals("[node.a_copy1]\nloc = 1, 2\nds_1 = b_copy1\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1", "b", "b_copy1")));
    }

    /** A link out of the paste still points at the original node. */
    @Test
    void linksToNodesOutsideThePasteAreLeftAlone() {
        String text = "[node.a]\nloc = 1, 2\nds_1 = downstream\n";
        assertEquals("[node.a_copy1]\nloc = 1, 2\nds_1 = downstream\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1")));
    }

    /** The old ds pattern anchored '^ds_' at column zero, so an indented link was missed. */
    @Test
    void indentedLinkIsRewritten() {
        String text = "[node.a]\nloc = 1, 2\n  ds_1 = b\n";
        assertEquals("[node.a_copy1]\nloc = 1, 2\n  ds_1 = b_copy1\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1", "b", "b_copy1")));
    }

    /** The old ds pattern required end-of-line after the target, so a comment blocked it. */
    @Test
    void linkWithAnInlineCommentIsRewritten() {
        String text = "[node.a]\nloc = 1, 2\nds_1 = b # to the gauge\n";
        assertEquals("[node.a_copy1]\nloc = 1, 2\nds_1 = b_copy1 # to the gauge\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1", "b", "b_copy1")));
    }

    @Test
    void commentedOutLinkIsNotRewritten() {
        String text = "[node.a]\nloc = 1, 2\n# ds_1 = b\n";
        assertEquals("[node.a_copy1]\nloc = 1, 2\n# ds_1 = b\n",
            ClipboardBlock.renameNodes(text, "a", Map.of("a", "a_copy1", "b", "b_copy1")));
    }

    /**
     * Renaming {@code a} to {@code a_copy1} while {@code a_copy1} is itself being pasted
     * must not cascade. The old code applied each mapping entry to the output of the
     * last, and was saved only by anchoring the target at end-of-line; computing every
     * edit against the original text makes that safety structural rather than lucky.
     */
    @Test
    void renamesDoNotCascadeThroughEachOther() {
        String text = "[node.a]\nloc = 1, 2\nds_1 = a_copy1\n";
        Map<String, String> renames = Map.of("a", "a_copy1", "a_copy1", "a_copy1_copy1");
        assertEquals("[node.a_copy1]\nloc = 1, 2\nds_1 = a_copy1_copy1\n",
            ClipboardBlock.renameNodes(text, "a", renames));
    }

    /** A cut keeps its names: no mapping, no edits. */
    @Test
    void anEmptyRenameMapLeavesTheTextAlone() {
        String text = "[node.a]\nloc = 1, 2\nds_1 = b\n";
        assertEquals(text, ClipboardBlock.renameNodes(text, "a", Map.of()));
    }

    // --- Coordinates ---

    @Test
    void coordinatesAreRewrittenPreservingTheSeparator() {
        String text = "[node.a]\nloc = 1, 2\n";
        assertEquals("[node.a]\nloc = 11.00, 22.00\n",
            ClipboardBlock.withCoordinates(text, "a", 11, 22));

        String tight = "[node.a]\nloc=1,2\n";
        assertEquals("[node.a]\nloc=11.00,22.00\n",
            ClipboardBlock.withCoordinates(tight, "a", 11, 22));
    }

    /** The old loc pattern was unanchored, so it matched inside 'refloc'. */
    @Test
    void aKeyEndingInLocIsNotMistakenForTheLocProperty() {
        String text = "[node.a]\nrefloc = 5, 5\nloc = 1, 2\n";
        assertEquals("[node.a]\nrefloc = 5, 5\nloc = 9.00, 9.00\n",
            ClipboardBlock.withCoordinates(text, "a", 9, 9));
    }

    @Test
    void aCommentedLocIsNotTheLocProperty() {
        String text = "[node.a]\n# loc = 5, 5\nloc = 1, 2\n";
        assertEquals("[node.a]\n# loc = 5, 5\nloc = 9.00, 9.00\n",
            ClipboardBlock.withCoordinates(text, "a", 9, 9));
    }

    @Test
    void aSectionWithNoLocIsUnchanged() {
        String text = "[node.a]\ntype = gauge\n";
        assertEquals(text, ClipboardBlock.withCoordinates(text, "a", 9, 9));
    }

    @Test
    void coordinatesOfReadsTheLocValue() {
        assertEquals(1.5, ClipboardBlock.coordinatesOf("[node.a]\nloc = 1.5, -2.5\n", "a")[0]);
        assertEquals(-2.5, ClipboardBlock.coordinatesOf("[node.a]\nloc = 1.5, -2.5\n", "a")[1]);
    }

    @Test
    void coordinatesOfReturnsNullWithoutALoc() {
        assertNull(ClipboardBlock.coordinatesOf("[node.a]\ntype = gauge\n", "a"));
    }

    // --- The whole block ---

    @Test
    void copyBuildsARenamedTranslatedBlockJoinedByOneBlankLine() {
        List<NodeSectionData> sections = List.of(
            section("a", "[node.a]\nloc = 0, 0\nds_1 = b\n\n", 0, 0, 0),
            section("b", "[node.b]\nloc = 10, 0\n\n", 10, 0, 1));

        // Each section is stripped of its trailing blank lines and joined by exactly one.
        assertEquals(
            "[node.a_copy1]\nloc = 100.00, 50.00\nds_1 = b_copy1"
                + "\n\n"
                + "[node.b_copy1]\nloc = 110.00, 50.00",
            ClipboardBlock.build(sections, "_copy1", 100, 50));
    }

    /** A cut translates coordinates but keeps every name, including its links. */
    @Test
    void cutKeepsNamesAndOnlyMovesCoordinates() {
        List<NodeSectionData> sections = List.of(
            section("a", "[node.a]\nloc = 0, 0\nds_1 = b\n", 0, 0, 0));

        assertEquals("[node.a]\nloc = 5.00, 5.00\nds_1 = b",
            ClipboardBlock.build(sections, null, 5, 5));
    }

    /** Trailing blank lines belong to the seam, which SectionSplice owns. */
    @Test
    void trailingWhitespaceIsStrippedFromEachSection() {
        List<NodeSectionData> sections = List.of(
            section("a", "[node.a]\nloc = 0, 0\n\n\n", 0, 0, 0));
        assertEquals("[node.a]\nloc = 0.00, 0.00", ClipboardBlock.build(sections, null, 0, 0));
    }
}
