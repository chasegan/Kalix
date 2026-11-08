package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Set-axis-ranges dialog applies: a typed value range lands on its axis whether or
 * not that axis was auto-ranged, and switches only that axis' auto-range off; an untouched
 * row leaves its axis to follow the time change as any navigation does. The dialog decides
 * which rows were typed by their text, so a row left as filled is untouched even when the
 * plot has moved on underneath.
 */
class SetAxisRangesTest {

    private static final long DAY = 86_400_000L;
    private final DatasetSeries small = new DatasetSeries("/tmp/x.csv", "small");
    private final DatasetSeries large = new DatasetSeries("/tmp/x.csv", "large");
    private FlowVizPanel panel;

    @BeforeEach
    void setUp() {
        DataSet dataSet = new DataSet();
        long[] times = {0, DAY, 2 * DAY, 3 * DAY};
        dataSet.addSeries(small, new TimeSeriesData(times, new double[] {0, 5, 10, 15}));
        dataSet.addSeries(large, new TimeSeriesData(times, new double[] {1000, 1500, 2000, 2500}));
        panel = new FlowVizPanel();
        panel.setSize(800, 600);
        panel.setDataSet(dataSet);
        panel.model().update(st -> st.withVisibleSeries(List.of(small, large)));
        panel.model().update(st -> st.withSeriesOnRightAxis(large, true));
        panel.model().update(st -> st.withAutoRangeAll(true));
        panel.zoomToFit();
    }

    private ViewPort view() {
        return panel.viewportForTests();
    }

    @Test
    void aTypedLeftRangeLandsOnAnAutoRangedAxisAndSwitchesOnlyItOff() {
        ViewPort before = view();

        panel.interactionsForTests().applyAxisRanges(
            new long[] {before.getStartTimeMs(), before.getEndTimeMs()},
            new double[] {-50, 50},
            new double[] {before.getY2MinValue(), before.getY2MaxValue()},
            EnumSet.of(Axis.Y));

        assertEquals(-50, view().getMinValue(), "the typed range is shown, not refitted away");
        assertEquals(50, view().getMaxValue());
        assertFalse(panel.isAutoRange(Axis.Y), "an explicit range opts the left axis out");
        assertTrue(panel.isAutoRange(Axis.Y2), "the untouched right axis stays auto-ranged");
        assertEquals(before.getY2MinValue(), view().getY2MinValue(), "and keeps its fitted range over the same window");
    }

    @Test
    void aTypedRightRangeWithANarrowerWindowRefitsTheUntouchedLeftAxis() {
        ViewPort before = view();
        long start = DAY;
        long end = 2 * DAY;

        panel.interactionsForTests().applyAxisRanges(
            new long[] {start, end},
            new double[] {before.getMinValue(), before.getMaxValue()},
            new double[] {900, 2600},
            EnumSet.of(Axis.Y2));

        assertEquals(900, view().getY2MinValue());
        assertEquals(2600, view().getY2MaxValue());
        assertFalse(panel.isAutoRange(Axis.Y2));
        assertTrue(panel.isAutoRange(Axis.Y));
        assertEquals(start, view().getStartTimeMs());
        assertTrue(view().getMaxValue() < before.getMaxValue(), "the left axis refits to the narrower window: " + view());
    }

    @Test
    void aTimeOnlyEditIsNavigationAndLeavesEveryFlagAlone() {
        ViewPort before = view();

        panel.interactionsForTests().applyAxisRanges(
            new long[] {DAY, 2 * DAY},
            new double[] {before.getMinValue(), before.getMaxValue()},
            new double[] {before.getY2MinValue(), before.getY2MaxValue()},
            Set.of());

        assertTrue(panel.model().current().isAutoRangeAll());
        assertTrue(view().getMaxValue() < before.getMaxValue(), "both axes refit: " + view());
        assertTrue(view().getY2MaxValue() < before.getY2MaxValue());
    }

    @Test
    void aRowLeftAsFilledIsUntouchedEvenWhenThePlotMovedUnderTheDialog() {
        ViewPort shown = view();
        panel.zoomIn(); // the plot refits under the open dialog; the rows still show the old ranges

        panel.interactionsForTests().applyAxisRanges(
            new long[] {shown.getStartTimeMs(), shown.getEndTimeMs()},
            new double[] {shown.getMinValue(), shown.getMaxValue()},
            new double[] {shown.getY2MinValue(), shown.getY2MaxValue()},
            Set.of());

        assertTrue(panel.model().current().isAutoRangeAll(), "nothing was typed, so nothing is pinned");
        assertEquals(shown.getMinValue(), view().getMinValue(), "the axes refit to the window the rows gave");
    }
}
