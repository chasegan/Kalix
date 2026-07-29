package com.kalix.ide.editor.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the lookups that drive Find Next / Find Previous and the "n of m" ordinal.
 * These replaced RSTA's own navigation, so they are the only thing deciding where the
 * caret lands.
 */
class MatchScanNavigationTest {

    /** Matches starting at 10, 20 and 30, each three characters long. */
    private static MatchScan scan() {
        return new MatchScan(List.of(
            new MatchScan.MatchRange(10, 13),
            new MatchScan.MatchRange(20, 23),
            new MatchScan.MatchRange(30, 33)), false);
    }

    @Test
    @DisplayName("Forward step finds the first match at or after the offset")
    void forwardStep() {
        assertEquals(0, scan().indexOfFirstStartingAtOrAfter(0));
        assertEquals(0, scan().indexOfFirstStartingAtOrAfter(10));
        assertEquals(1, scan().indexOfFirstStartingAtOrAfter(11));
        assertEquals(2, scan().indexOfFirstStartingAtOrAfter(30));
    }

    @Test
    @DisplayName("Forward step past the last match reports none, leaving the caller to wrap")
    void forwardStepOffTheEnd() {
        assertEquals(-1, scan().indexOfFirstStartingAtOrAfter(31));
    }

    @Test
    @DisplayName("Backward step finds the last match starting strictly before the offset")
    void backwardStep() {
        assertEquals(2, scan().indexOfLastStartingBefore(40));
        assertEquals(1, scan().indexOfLastStartingBefore(30));
        assertEquals(0, scan().indexOfLastStartingBefore(20));
    }

    @Test
    @DisplayName("Backward step before the first match reports none, leaving the caller to wrap")
    void backwardStepOffTheStart() {
        assertEquals(-1, scan().indexOfLastStartingBefore(10));
        assertEquals(-1, scan().indexOfLastStartingBefore(0));
    }

    @Test
    @DisplayName("Ordinal is 1-based, and zero when nothing starts at the offset")
    void ordinal() {
        assertEquals(1, scan().ordinalOfMatchStartingAt(10));
        assertEquals(2, scan().ordinalOfMatchStartingAt(20));
        assertEquals(3, scan().ordinalOfMatchStartingAt(30));
        assertEquals(0, scan().ordinalOfMatchStartingAt(11));
        assertEquals(0, scan().ordinalOfMatchStartingAt(0));
    }

    @Test
    @DisplayName("An empty scan reports no match in either direction")
    void emptyScan() {
        MatchScan empty = MatchScan.empty();
        assertEquals(-1, empty.indexOfFirstStartingAtOrAfter(0));
        assertEquals(-1, empty.indexOfLastStartingBefore(100));
        assertEquals(0, empty.ordinalOfMatchStartingAt(0));
    }
}
