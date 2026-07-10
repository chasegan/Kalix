package com.kalix.ide.editor.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Naming of inserted template nodes. The numeral is always present, so the first node
 * of a type looks like its siblings rather than being the odd one out.
 */
class UniqueNodeNameTest {

    @Test
    void firstNodeOfATypeIsNumberedOne() {
        assertEquals("storage_1", CommandExecutor.uniqueNodeName("storage", "[kalix]\n"));
    }

    @Test
    void emptyDocumentStillNumbersFromOne() {
        assertEquals("gauge_1", CommandExecutor.uniqueNodeName("gauge", ""));
    }

    @Test
    void subsequentNodesTakeTheNextFreeNumber() {
        String text = "[node.storage_1]\nloc = 1, 1\n\n[node.storage_2]\nloc = 2, 2\n";
        assertEquals("storage_3", CommandExecutor.uniqueNodeName("storage", text));
    }

    /** Gaps are filled, not skipped: the first free numeral wins. */
    @Test
    void gapsInTheNumberingAreReused() {
        String text = "[node.storage_1]\nloc = 1, 1\n\n[node.storage_3]\nloc = 3, 3\n";
        assertEquals("storage_2", CommandExecutor.uniqueNodeName("storage", text));
    }

    @Test
    void otherTypesDoNotAffectNumbering() {
        String text = "[node.gauge_1]\nloc = 1, 1\n\n[node.gauge_2]\nloc = 2, 2\n";
        assertEquals("storage_1", CommandExecutor.uniqueNodeName("storage", text));
    }

    /** A hand-named node that happens to collide is respected. */
    @Test
    void collidesWithHandWrittenNames() {
        String text = "[node.storage_1]\nloc = 1, 1\n";
        assertEquals("storage_2", CommandExecutor.uniqueNodeName("storage", text));
    }

    /** Names come from the section grammar, so a commented-out node is not "taken". */
    @Test
    void commentedOutNodesDoNotReserveNames() {
        String text = "# [node.storage_1]\n# loc = 1, 1\n";
        assertEquals("storage_1", CommandExecutor.uniqueNodeName("storage", text));
    }

    /** An indented header is a real section, so its name is taken. */
    @Test
    void indentedNodesReserveNames() {
        String text = "  [node.storage_1]\n  loc = 1, 1\n";
        assertEquals("storage_2", CommandExecutor.uniqueNodeName("storage", text));
    }
}
