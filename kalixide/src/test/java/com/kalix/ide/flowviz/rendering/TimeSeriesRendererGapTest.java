package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the gap-detection decisions that drive gap-aware line breaking and orphan markers
 * (Phase 1b). The Graphics2D drawing itself isn't unit-testable, but these pure predicates are
 * where the logic could be wrong.
 */
class TimeSeriesRendererGapTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final double NaN = Double.NaN;

    @Test
    void gapThresholdScalesWithCadenceAndHonoursConnectFlag() {
        TimeSeriesData daily = new TimeSeriesData(new long[]{0, DAY, 2 * DAY, 3 * DAY}, new double[]{1, 2, 3, 4});

        assertEquals((long) (DAY * 1.5), TimeSeriesRenderer.gapThresholdFor(daily, false),
            "Break threshold should be 1.5x the daily cadence");
        assertEquals(Long.MAX_VALUE, TimeSeriesRenderer.gapThresholdFor(daily, true),
            "Connecting across gaps means never breaking on spacing");
    }

    @Test
    void irregularSeriesNeverBreaksOnSpacing() {
        // Ad-hoc spacings with no dominant base → no cadence → must never spacing-break.
        long h = 60L * 60 * 1000;
        long[] ts = {0, h, 7 * h, 50 * h, 51 * h, 200 * h, 333 * h, 334 * h};
        TimeSeriesData s = new TimeSeriesData(ts, new double[ts.length]);

        assertEquals(0, s.getNominalIntervalMillis(), "ad-hoc series has no cadence");
        assertEquals(Long.MAX_VALUE, TimeSeriesRenderer.gapThresholdFor(s, false));
    }

    @Test
    void segmentBreaksOnWideGapAndOnMissingEndpoint() {
        // Daily grid with an 8-day hole between index 2 and 3.
        TimeSeriesData s = new TimeSeriesData(
            new long[]{0, DAY, 2 * DAY, 10 * DAY, 11 * DAY}, new double[]{1, 2, 3, 4, 5});
        long thr = TimeSeriesRenderer.gapThresholdFor(s, false);

        assertFalse(TimeSeriesRenderer.segmentBreaks(s, 0, 1, thr), "adjacent daily points connect");
        assertTrue(TimeSeriesRenderer.segmentBreaks(s, 2, 3, thr), "8-day gap exceeds 1.5-day threshold");

        // NaN endpoint always breaks, regardless of spacing.
        TimeSeriesData withNaN = new TimeSeriesData(
            new long[]{0, DAY, 2 * DAY}, new double[]{1, NaN, 3});
        long thr2 = TimeSeriesRenderer.gapThresholdFor(withNaN, false);
        assertTrue(TimeSeriesRenderer.segmentBreaks(withNaN, 0, 1, thr2), "missing endpoint breaks");
        assertTrue(TimeSeriesRenderer.segmentBreaks(withNaN, 1, 2, thr2), "missing endpoint breaks");
    }

    @Test
    void orphanDetectedWhenIsolatedByMissingValues() {
        // Daily grid, valid points isolated by NaN placeholders: 1, NaN, 3, NaN, 5.
        TimeSeriesData s = new TimeSeriesData(
            new long[]{0, DAY, 2 * DAY, 3 * DAY, 4 * DAY}, new double[]{1, NaN, 3, NaN, 5});
        long thr = TimeSeriesRenderer.gapThresholdFor(s, false);

        assertTrue(TimeSeriesRenderer.isOrphan(s, 2, thr), "valid point with NaN on both sides is an orphan");
        assertTrue(TimeSeriesRenderer.isOrphan(s, 0, thr), "boundary point with NaN to the right is an orphan");
        assertFalse(TimeSeriesRenderer.isOrphan(s, 1, thr), "a missing point is never an orphan");
    }

    @Test
    void orphanDetectedWhenIsolatedByWideGaps() {
        // Mostly-daily series with one point (index 5) marooned far from its neighbours.
        long[] ts = {0, DAY, 2 * DAY, 3 * DAY, 4 * DAY, 50 * DAY, 100 * DAY, 101 * DAY, 102 * DAY};
        TimeSeriesData s = new TimeSeriesData(ts, new double[ts.length]);
        long thr = TimeSeriesRenderer.gapThresholdFor(s, false);

        assertEquals(DAY, s.getNominalIntervalMillis(), "dominant daily base despite the gaps");
        assertTrue(TimeSeriesRenderer.isOrphan(s, 5, thr), "point with wide gaps on both sides is an orphan");
        assertFalse(TimeSeriesRenderer.isOrphan(s, 1, thr), "point with daily neighbours is not an orphan");
    }

    @Test
    void contiguousSeriesHasNoOrphans() {
        TimeSeriesData s = new TimeSeriesData(new long[]{0, DAY, 2 * DAY}, new double[]{1, 2, 3});
        long thr = TimeSeriesRenderer.gapThresholdFor(s, false);

        for (int i = 0; i < s.getPointCount(); i++) {
            assertFalse(TimeSeriesRenderer.isOrphan(s, i, thr), "no orphans in a gap-free series (index " + i + ")");
        }
    }
}
