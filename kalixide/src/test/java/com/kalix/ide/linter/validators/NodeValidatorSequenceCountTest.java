package com.kalix.ide.linter.validators;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The value count of a number sequence, now a comma count rather than a regex
 * replace-and-split per property per lint pass. Cases mirror what the old
 * {@code replaceAll("[,\\s]+$", "").split("\\s*,\\s*").length} produced.
 */
class NodeValidatorSequenceCountTest {

    @Test
    void countsCommaSeparatedValues() {
        assertEquals(4, NodeValidator.countSequenceValues("1, 2, 3, 4"));
        assertEquals(4, NodeValidator.countSequenceValues("1,2,3,4"));
        assertEquals(1, NodeValidator.countSequenceValues("42"));
    }

    @Test
    void ignoresTrailingCommasAndWhitespace() {
        assertEquals(4, NodeValidator.countSequenceValues("1, 2, 3, 4,"));
        assertEquals(4, NodeValidator.countSequenceValues("1, 2, 3, 4, \t"));
        assertEquals(4, NodeValidator.countSequenceValues("1, 2, 3, 4,,,"));
    }

    @Test
    void interiorEmptySlotsStillCount() {
        // "1,,2" split three ways before; the format check reports the gap.
        assertEquals(3, NodeValidator.countSequenceValues("1,,2"));
        assertEquals(2, NodeValidator.countSequenceValues(",1"));
    }

    @Test
    void emptySequenceCountsAsOne() {
        // As "".split(...) did; the format check has already rejected it.
        assertEquals(1, NodeValidator.countSequenceValues(""));
        assertEquals(1, NodeValidator.countSequenceValues(" , "));
    }
}
