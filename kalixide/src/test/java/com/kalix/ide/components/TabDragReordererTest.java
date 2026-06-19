package com.kalix.ide.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure drop-gap geometry in {@link TabDragReorderer}. The drag interaction
 * itself is exercised manually (it involves a live glass pane), but the gap→destination
 * translation is where the off-by-one mistakes hide, so it's pinned down here.
 *
 * <p>A "gap" is a position between tabs, 0..tabCount: gap {@code g} means "land before the tab
 * currently at index {@code g}" (or at the very end when {@code g == tabCount}).
 */
class TabDragReordererTest {

    // ---- no-ops: the tab wouldn't actually move ----

    @Test
    void gapAtOwnIndexIsNoOp() {
        // Dropping into the gap just left of yourself is staying put.
        assertEquals(-1, TabDragReorderer.destinationIndex(2, 2));
    }

    @Test
    void gapJustAfterOwnIndexIsNoOp() {
        // Dropping into the gap just right of yourself is also staying put, because removing the
        // tab first shifts that gap back onto the original index.
        assertEquals(-1, TabDragReorderer.destinationIndex(2, 3));
    }

    @Test
    void negativeGapIsNoOp() {
        assertEquals(-1, TabDragReorderer.destinationIndex(2, -1));
    }

    // ---- moving left (towards the start) ----

    @Test
    void movingToTheStart() {
        assertEquals(0, TabDragReorderer.destinationIndex(3, 0));
    }

    @Test
    void movingLeftByOne() {
        assertEquals(1, TabDragReorderer.destinationIndex(3, 1));
    }

    // ---- moving right (towards the end); the gap is past the removal point, so it shifts back one ----

    @Test
    void movingRightAccountsForRemovalShift() {
        // from=0, gap=2 → after removing tab 0, land at index 1.
        assertEquals(1, TabDragReorderer.destinationIndex(0, 2));
    }

    @Test
    void movingToTheEnd() {
        // 5 tabs, drag the first to the end gap (5) → final index 4.
        assertEquals(4, TabDragReorderer.destinationIndex(0, 5));
    }
}
