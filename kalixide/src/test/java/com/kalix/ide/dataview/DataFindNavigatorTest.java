package com.kalix.ide.dataview;

import com.kalix.ide.dataview.DataFindNavigator.Landing;
import com.kalix.ide.dataview.DataViewSession.CellRef;
import com.kalix.ide.dataview.DataViewSession.FindScan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the find cycle: header columns first (virtual row -1), then data cells;
 * ordinals spanning both; wrap in both directions; the honest null miss; and
 * the nearest-later-date fallback.
 */
class DataFindNavigatorTest {

    private static FindScan scan(int total, CellRef firstOverall, CellRef lastOverall,
                                 CellRef firstAfter, CellRef lastBefore, long nearestDateRow) {
        return new FindScan(total, firstOverall, lastOverall, firstAfter, lastBefore, nearestDateRow);
    }

    private static final CellRef CELL_A = new CellRef(1, 1, 1);
    private static final CellRef CELL_B = new CellRef(5, 2, 2);
    private static final FindScan TWO_CELLS = scan(2, CELL_A, CELL_B, CELL_A, null, -1);

    @Test
    void forwardVisitsHeadersBeforeCells() {
        Landing landing = DataFindNavigator.choose(TWO_CELLS, List.of(1, 3), -2, 0, true, true);
        assertEquals(-1, landing.row(), "headers come first in the cycle");
        assertEquals(1, landing.column());
        assertEquals(1, landing.ordinal());
        assertEquals(4, landing.total(), "two headers + two cells");
    }

    @Test
    void forwardFromAHeaderStepsThroughHeadersThenIntoCells() {
        Landing next = DataFindNavigator.choose(TWO_CELLS, List.of(1, 3), -1, 1, true, true);
        assertEquals(-1, next.row());
        assertEquals(3, next.column());
        assertEquals(2, next.ordinal());

        Landing intoCells = DataFindNavigator.choose(TWO_CELLS, List.of(1, 3), -1, 3, true, true);
        assertEquals(1, intoCells.row(), "past the last header: the first data cell");
        assertEquals(3, intoCells.ordinal(), "ordinals continue across the boundary");
    }

    @Test
    void forwardWrapsToTheFirstHeader() {
        FindScan noneAfter = scan(2, CELL_A, CELL_B, null, CELL_B, -1);
        Landing landing = DataFindNavigator.choose(noneAfter, List.of(2), 9, 0, true, true);
        assertEquals(-1, landing.row());
        assertEquals(2, landing.column());
        assertTrue(landing.wrapped());
    }

    @Test
    void backwardPrefersThePreviousCellThenHeaders() {
        FindScan beforeExists = scan(2, CELL_A, CELL_B, null, CELL_A, -1);
        Landing cell = DataFindNavigator.choose(beforeExists, List.of(1), 5, 2, false, true);
        assertEquals(1, cell.row());
        assertFalse(cell.wrapped());

        FindScan nothingBefore = scan(2, CELL_A, CELL_B, CELL_A, null, -1);
        Landing header = DataFindNavigator.choose(nothingBefore, List.of(1, 3), 0, 0, false, true);
        assertEquals(-1, header.row(), "before the first cell: back into the headers");
        assertEquals(3, header.column(), "the largest earlier header");
    }

    @Test
    void backwardWrapsToTheLastCell() {
        FindScan scan = scan(2, CELL_A, CELL_B, CELL_A, null, -1);
        Landing landing = DataFindNavigator.choose(scan, List.of(), -2, 0, false, true);
        assertEquals(5, landing.row(), "wrap backward lands on the last match");
        assertTrue(landing.wrapped());
    }

    @Test
    void noWrapMissIsNull() {
        FindScan noneAfter = scan(2, CELL_A, CELL_B, null, CELL_B, -1);
        assertNull(DataFindNavigator.choose(noneAfter, List.of(), 9, 0, true, false));
    }

    @Test
    void nearestDateFallbackLandsOnTheDateColumn() {
        FindScan empty = scan(0, null, null, null, null, 7);
        Landing landing = DataFindNavigator.choose(empty, List.of(), -2, 0, true, true);
        assertEquals(7, landing.row());
        assertEquals(0, landing.column());
        assertTrue(landing.nearestDate());
    }
}
