package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DatasetSeries;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void statesWithNoRightAxisAreEqualThroughItsNaNRange() {
        FlowVizState s = FlowVizState.INITIAL.withVisibleSeries(List.of(A));
        assertTrue(Double.isNaN(s.getY2MinValue()));
        assertEquals(s, s.withViewport(null), "NaN compares equal to NaN here, or no entry could ever dedup");
        assertEquals(s.hashCode(), s.withViewport(null).hashCode());
    }
}
