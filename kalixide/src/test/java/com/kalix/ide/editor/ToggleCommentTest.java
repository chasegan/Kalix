package com.kalix.ide.editor;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers toggleComment's pure logic and the selection restore for selections
 * spanning 3+ lines (review #25): the old implementation re-read line starts from
 * the already-edited document while comparing against pre-edit selection
 * coordinates, so later lines stopped crediting their deltas.
 */
class ToggleCommentTest {

    // --- toggleLineComment ---

    @Test
    void commentInsertsMarkerAfterLeadingWhitespace() {
        assertEquals("# flow = 1", EnhancedTextEditor.toggleLineComment("flow = 1", false));
        assertEquals("  # flow = 1", EnhancedTextEditor.toggleLineComment("  flow = 1", false));
        assertEquals("# ", EnhancedTextEditor.toggleLineComment("", false));
    }

    @Test
    void uncommentRemovesMarkerAndOneSpace() {
        assertEquals("flow = 1", EnhancedTextEditor.toggleLineComment("# flow = 1", true));
        assertEquals("flow = 1", EnhancedTextEditor.toggleLineComment("#flow = 1", true));
        assertEquals("  flow = 1", EnhancedTextEditor.toggleLineComment("  # flow = 1", true));
        assertEquals(" x", EnhancedTextEditor.toggleLineComment("#  x", true)); // only one space removed
        assertEquals("no marker", EnhancedTextEditor.toggleLineComment("no marker", true)); // unchanged
    }

    // --- areAllCommented ---

    @Test
    void blankLinesDoNotBreakAllCommented() {
        assertTrue(EnhancedTextEditor.areAllCommented(new String[] {"# a", "", "  # b"}));
        assertFalse(EnhancedTextEditor.areAllCommented(new String[] {"# a", "b"}));
    }

    // --- shiftSelectionForLineDeltas ---

    @Test
    void threePlusLineSelectionCreditsEveryLineDelta() {
        // "aaa\nbbb\nccc" commented to "# aaa\n# bbb\n# ccc":
        // lines start at 0, 4, 8; each grows by 2; selection was 0..11 (all text).
        int[] shifted = EnhancedTextEditor.shiftSelectionForLineDeltas(
            0, 11, new int[] {0, 4, 8}, new int[] {2, 2, 2});
        assertArrayEquals(new int[] {2, 17}, shifted);

        // And the inverse (uncomment of "# aaa\n# bbb\n# ccc", selection 0..17):
        // start clamps to its line start; end credits all three -2 deltas.
        shifted = EnhancedTextEditor.shiftSelectionForLineDeltas(
            0, 17, new int[] {0, 6, 12}, new int[] {-2, -2, -2});
        assertArrayEquals(new int[] {0, 11}, shifted);
    }

    @Test
    void selectionEndAtLineStartDoesNotCreditThatLine() {
        // End sits exactly at the start of the last edited line: strictly-before rule.
        int[] shifted = EnhancedTextEditor.shiftSelectionForLineDeltas(
            0, 8, new int[] {0, 4, 8}, new int[] {2, 2, 2});
        assertArrayEquals(new int[] {2, 12}, shifted);
    }

    @Test
    void endNeverPrecedesStart() {
        // Caret (empty selection) at a line start while the line shrinks.
        int[] shifted = EnhancedTextEditor.shiftSelectionForLineDeltas(
            4, 4, new int[] {4}, new int[] {-2});
        assertTrue(shifted[1] >= shifted[0]);
        assertTrue(shifted[0] >= 4); // clamped to its own line start
    }

    // --- end-to-end through a real editor ---

    @Test
    void toggleCommentRoundTripsSelectionAcrossThreeLines() {

        EnhancedTextEditor editor = new EnhancedTextEditor();
        editor.setText("aaa\nbbb\nccc");
        var textArea = editor.getTextArea();
        textArea.setSelectionStart(0);
        textArea.setSelectionEnd(11);

        editor.toggleComment();
        assertEquals("# aaa\n# bbb\n# ccc", editor.getText());
        assertEquals(2, textArea.getSelectionStart());
        assertEquals(17, textArea.getSelectionEnd());

        editor.toggleComment();
        assertEquals("aaa\nbbb\nccc", editor.getText());
        assertEquals(0, textArea.getSelectionStart());
        assertEquals(11, textArea.getSelectionEnd());

        editor.dispose();
    }
}
