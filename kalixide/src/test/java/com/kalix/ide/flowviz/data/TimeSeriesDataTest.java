package com.kalix.ide.flowviz.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TimeSeriesDataTest {

    /** Epoch millis for a LocalDateTime treated as UTC, matching TimeSeriesData's own conversion. */
    private static long ms(LocalDateTime dt) {
        return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void contiguousDailySeriesIsDetectedRegular() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 200;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = i;
        }
        TimeSeriesData series = new TimeSeriesData(dates, values);

        assertTrue(series.hasRegularInterval(), "Gap-free daily series should use the regular fast path");
    }

    /**
     * Regression test for the index-drift bug: a series that is regular for its first 100+ points
     * but has a gap later (missing rows dropped) was misclassified as regular because only the
     * first 100 intervals were sampled. getIndexRange()'s arithmetic fast path then returned
     * indices offset by the number of dropped points, skipping the leading visible points of any
     * viewport after the gap. The series must be classified irregular and indexed via binary
     * search so the bounds stay correct.
     */
    @Test
    void seriesWithGapPastFirst100PointsIsDetectedIrregular() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);

        // 150 contiguous daily points, then drop 10 days (a gap), then 150 more daily points.
        int before = 150;
        int dropped = 10;
        int after = 150;
        int n = before + after;

        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        int idx = 0;
        for (int i = 0; i < before; i++) {
            dates[idx] = start.plusDays(i);
            values[idx] = idx;
            idx++;
        }
        for (int i = 0; i < after; i++) {
            // Skip `dropped` days to create the gap.
            dates[idx] = start.plusDays(before + dropped + i);
            values[idx] = idx;
            idx++;
        }
        TimeSeriesData series = new TimeSeriesData(dates, values);

        assertFalse(series.hasRegularInterval(),
            "A series with a gap (even past the first 100 points) must not use the regular fast path");
    }

    /**
     * The whole point of the fix: getIndexRange must return correct array bounds for a viewport
     * lying entirely after the gap, so the renderer iterates every visible point. Before the fix,
     * startIndex was overestimated by the number of dropped points and leading points were skipped.
     */
    @Test
    void getIndexRangeReturnsCorrectBoundsForViewportAfterGap() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);

        int before = 150;
        int dropped = 10;
        int after = 150;
        int n = before + after;

        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        int idx = 0;
        for (int i = 0; i < before; i++) {
            dates[idx] = start.plusDays(i);
            values[idx] = idx;
            idx++;
        }
        for (int i = 0; i < after; i++) {
            dates[idx] = start.plusDays(before + dropped + i);
            values[idx] = idx;
            idx++;
        }
        TimeSeriesData series = new TimeSeriesData(dates, values);

        // Viewport covering the first 5 post-gap points (array indices before..before+4), with
        // edges sitting half an interval off-grid — the realistic zoom case, and unambiguous for
        // a half-open [startIndex, endIndex) range.
        long halfDay = 12 * 60 * 60 * 1000L;
        long viewStart = ms(dates[before]) - halfDay;
        long viewEnd = ms(dates[before + 4]) + halfDay;

        TimeSeriesData.IndexRange range = series.getIndexRange(viewStart, viewEnd);

        // startIndex must land on (or just before) the first post-gap point — never skip past it.
        // This is the core regression: before the fix it was overestimated by `dropped`.
        assertTrue(range.startIndex <= before,
            "startIndex must not skip the first visible post-gap point (got " + range.startIndex
                + ", expected <= " + before + ")");
        // endIndex (exclusive) must reach past the last visible point in the window.
        assertTrue(range.endIndex >= before + 5,
            "endIndex must include the last visible point (got " + range.endIndex + ")");

        // Every array index whose timestamp falls within the viewport must be inside the range.
        for (int i = 0; i < n; i++) {
            long t = ms(dates[i]);
            if (t >= viewStart && t <= viewEnd) {
                assertTrue(i >= range.startIndex && i < range.endIndex,
                    "Visible point at index " + i + " must be inside the returned range ["
                        + range.startIndex + ", " + range.endIndex + ")");
            }
        }
    }
}
