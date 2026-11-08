package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DatasetSeries;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the state guarantees by construction about the secondary Y axis: its series
 * are a subset of the visible series, so a series that leaves the plot leaves the axis and
 * a ref not visible cannot be put there; and two states with no secondary axis are equal
 * through its NaN range, which history dedup relies on.
 */
class FlowVizStateTest {

    private static final DatasetSeries A = new DatasetSeries("/tmp/x.csv", "a");
    private static final DatasetSeries B = new DatasetSeries("/tmp/x.csv", "b");

    @Test
    void theRightAxisSeriesAreASubsetOfTheVisibleSeries() {
        FlowVizState both = FlowVizState.INITIAL.withVisibleSeries(List.of(A, B))
            .withSeriesOnRightAxis(A, true).withSeriesOnRightAxis(B, true);
        assertEquals(Set.of(A, B), both.getRightAxisSeries());
        assertTrue(both.isRightAxisInUse());

        FlowVizState narrowed = both.withVisibleSeries(List.of(A));
        assertEquals(Set.of(A), narrowed.getRightAxisSeries(), "a series that leaves the plot leaves the right axis");

        FlowVizState empty = narrowed.withVisibleSeries(List.of());
        assertFalse(empty.isRightAxisInUse());
        assertFalse(empty.withVisibleSeries(List.of(A, B)).isOnRightAxis(A), "and comes back on the left");
    }

    @Test
    void movingASeriesThatIsNotVisibleChangesNothing() {
        FlowVizState s = FlowVizState.INITIAL.withVisibleSeries(List.of(A));
        assertEquals(s, s.withSeriesOnRightAxis(B, true));
    }

    @Test
    void aRightAxisIsBornAutoRangedAndItsFlagGoesWithIt() {
        FlowVizState manual = FlowVizState.INITIAL.withVisibleSeries(List.of(A, B)).withAutoRangeAll(false);
        assertFalse(manual.isAutoRange(Axis.Y));
        assertTrue(manual.isAutoRange(Axis.Y2), "no right axis: the flag waits, true, for the next one");

        FlowVizState split = manual.withSeriesOnRightAxis(A, true);
        assertTrue(split.isAutoRange(Axis.Y2), "born auto-ranged, whatever the left axis is");
        assertFalse(split.isAutoRange(Axis.Y));
        assertTrue(split.isAutoRangeAny());
        assertFalse(split.isAutoRangeAll());

        FlowVizState pinned = split.withAutoRange(Axis.Y2, false);
        assertFalse(pinned.isAutoRange(Axis.Y2));
        assertFalse(pinned.isAutoRangeAny(), "neither axis follows the data now");

        FlowVizState rejoined = pinned.withSeriesOnRightAxis(A, false);
        assertEquals(manual, rejoined, "the right axis' flag goes with the axis, so the states compare equal");
    }

    @Test
    void theAllFlagSetsEveryAxis() {
        FlowVizState s = FlowVizState.INITIAL.withVisibleSeries(List.of(A, B)).withSeriesOnRightAxis(A, true)
            .withAutoRange(Axis.Y2, false);
        assertTrue(s.withAutoRangeAll(true).isAutoRangeAll());
        assertFalse(s.withAutoRangeAll(false).isAutoRangeAny());
        assertThrows(IllegalArgumentException.class, () -> s.isAutoRange(Axis.X), "X has no range to fit");
    }

    @Test
    void statesWithNoRightAxisAreEqualThroughItsNaNRange() {
        FlowVizState s = FlowVizState.INITIAL.withVisibleSeries(List.of(A));
        assertTrue(Double.isNaN(s.getY2MinValue()));
        assertEquals(s, s.withViewport(null), "NaN compares equal to NaN here, or no entry could ever dedup");
        assertEquals(s.hashCode(), s.withViewport(null).hashCode());
    }
}
