package com.kalix.ide.linter.ui;

import org.junit.jupiter.api.Test;

import static com.kalix.ide.linter.ui.PropertyHoverTooltipManager.isPossiblePropertyKeyPosition;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the cheap per-mouse-move property-key check (review #73): the hot
 * path must reject positions that cannot be a property key from the single
 * line's text alone, so the full document analysis only runs in the dwell
 * timer for plausible candidates.
 */
class PropertyHoverTooltipManagerCheapCheckTest {

    @Test
    void keyPositionOnPropertyLineIsCandidate() {
        assertTrue(isPossiblePropertyKeyPosition("type = storage\n", 0));
        assertTrue(isPossiblePropertyKeyPosition("type = storage\n", 3));
        assertTrue(isPossiblePropertyKeyPosition("loc=10, 20\n", 2));
    }

    @Test
    void valuePositionIsNotCandidate() {
        // '=' at index 5; anything at or past it is the value side
        assertFalse(isPossiblePropertyKeyPosition("type = storage\n", 5));
        assertFalse(isPossiblePropertyKeyPosition("type = storage\n", 10));
    }

    @Test
    void nonPropertyLinesAreNotCandidates() {
        assertFalse(isPossiblePropertyKeyPosition("", 0), "empty line");
        assertFalse(isPossiblePropertyKeyPosition("\n", 0), "blank line");
        assertFalse(isPossiblePropertyKeyPosition("[node.storage1]\n", 3), "section header");
        assertFalse(isPossiblePropertyKeyPosition("; comment = looks like one\n", 3), "comment");
        assertFalse(isPossiblePropertyKeyPosition("# comment = looks like one\n", 3), "hash comment");
        assertFalse(isPossiblePropertyKeyPosition("   3, 4\n", 4), "continuation line");
        assertFalse(isPossiblePropertyKeyPosition("node.outlet.dsflow\n", 4), "bare outputs line (no '=')");
    }

    @Test
    void commentedOutAssignmentIsNotCandidate() {
        // comment marker before the '=': not a property header
        assertFalse(isPossiblePropertyKeyPosition("key ; note = 1\n", 1));
    }

    @Test
    void negativeColumnIsRejected() {
        assertFalse(isPossiblePropertyKeyPosition("type = storage\n", -1));
    }
}
