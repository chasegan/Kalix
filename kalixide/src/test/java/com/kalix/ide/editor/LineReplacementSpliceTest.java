package com.kalix.ide.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the range-splice used by {@link EnhancedTextEditor#applyAtomicReplacements}:
 * only the addressed range is replaced, and a range that does not hold the expected
 * text is rejected (returns null) instead of corrupting the line.
 */
class LineReplacementSpliceTest {

    private static EnhancedTextEditor.LineReplacement rep(int column, String oldText, String newText) {
        return new EnhancedTextEditor.LineReplacement(1, column, oldText, newText);
    }

    @Test
    void splicesOnlyTheAddressedOccurrence() {
        // Two occurrences of "s"; only the one at column 7 is addressed.
        assertEquals("ds_1 = x # s",
            EnhancedTextEditor.spliceLine("ds_1 = s # s", rep(7, "s", "x")));
    }

    @Test
    void splicesWholeLineWhenRangeCoversIt() {
        assertEquals("area = 20",
            EnhancedTextEditor.spliceLine("area = 10", rep(0, "area = 10", "area = 20")));
    }

    @Test
    void rejectsMismatchedRange() {
        assertNull(EnhancedTextEditor.spliceLine("ds_1 = s", rep(0, "zz", "yy")));
        assertNull(EnhancedTextEditor.spliceLine("short", rep(3, "rtX", "x"))); // overruns the line
        assertNull(EnhancedTextEditor.spliceLine("abc", rep(-1, "a", "b")));    // negative column
    }

    @Test
    void handlesInsertionsLongerAndShorterThanTheOriginal() {
        assertEquals("ds_1 = long_name",
            EnhancedTextEditor.spliceLine("ds_1 = s", rep(7, "s", "long_name")));
        assertEquals("ds_1 = s",
            EnhancedTextEditor.spliceLine("ds_1 = long_name", rep(7, "long_name", "s")));
    }
}
