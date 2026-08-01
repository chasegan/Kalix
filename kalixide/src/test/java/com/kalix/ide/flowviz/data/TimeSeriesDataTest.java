package com.kalix.ide.flowviz.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSeriesDataTest {

    /** Epoch millis for a LocalDateTime treated as UTC, matching TimeSeriesData's own conversion. */
    private static long ms(LocalDateTime dt) {
        return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @Test
    void contiguousDailySeriesIsContiguousWithDailyCadence() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 200;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusDays(i);
            values[i] = i;
        }
        TimeSeriesData series = new TimeSeriesData(dates, values);

        assertTrue(series.isContiguous(), "Gap-free daily series should be contiguous (O(1) fast path)");
        assertEquals(DAY_MS, series.getNominalIntervalMillis(), "Nominal cadence should be one day");
        assertTrue(series.hasRegularInterval(), "Legacy alias should still report contiguity");
    }

    /**
     * The cadence/contiguity split: a regular daily series with a gap (dropped rows) past the
     * first 100 points must NOT be contiguous — getIndexRange()'s arithmetic fast path is only
     * valid on a gap-free grid, and treating this as contiguous drifts the index by the number of
     * dropped points, skipping the leading visible points after the gap. But its nominal cadence
     * (one day) must be RETAINED, since a gap-aware renderer needs it to recognise the gap.
     */
    @Test
    void dailySeriesWithGapIsNotContiguousButRetainsDailyCadence() {
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

        assertFalse(series.isContiguous(),
            "A series with a gap (even past the first 100 points) must not use the regular fast path");
        assertEquals(DAY_MS, series.getNominalIntervalMillis(),
            "Daily cadence must be retained despite the gap, so gap-aware rendering can use it");
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

    /**
     * Genuinely ad-hoc timestamps (not a regular grid, even with gaps) must report no cadence,
     * so a gap-aware renderer never breaks their line on spacing. The intervals here share no
     * dominant base, distinguishing them from a regular-with-gaps series.
     */
    @Test
    void adHocSeriesHasNoNominalCadence() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        // Irregular spacings with no dominant repeating base interval.
        long[] offsetsHours = {0, 1, 7, 50, 51, 200, 333, 334};
        LocalDateTime[] dates = new LocalDateTime[offsetsHours.length];
        double[] values = new double[offsetsHours.length];
        for (int i = 0; i < offsetsHours.length; i++) {
            dates[i] = start.plusHours(offsetsHours[i]);
            values[i] = i;
        }
        TimeSeriesData series = new TimeSeriesData(dates, values);

        assertFalse(series.isContiguous(), "Ad-hoc series is not a gap-free grid");
        assertEquals(0, series.getNominalIntervalMillis(), "Ad-hoc series must report no cadence");
    }

    /**
     * Monthly data has intrinsically varying intervals (28–31 days), so it must classify as
     * irregular (no fixed cadence) — we do not want spacing-based gap detection on calendar
     * aggregations.
     */
    @Test
    void monthlySeriesHasNoNominalCadence() {
        LocalDateTime start = LocalDateTime.of(2020, 1, 1, 0, 0);
        int n = 24;
        LocalDateTime[] dates = new LocalDateTime[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            dates[i] = start.plusMonths(i);
            values[i] = i;
        }
        TimeSeriesData series = new TimeSeriesData(dates, values);

        assertFalse(series.isContiguous(), "Monthly spacing varies, so not a contiguous fixed grid");
        assertEquals(0, series.getNominalIntervalMillis(),
            "Monthly data must report no fixed cadence (intervals vary 28–31 days)");
    }

    // ---- getIndexRange boundary semantics (half-open [startIndex, endIndex), end-inclusive
    // in time: a point lying exactly on the viewport end must be inside the range) ----

    private static final long HOUR_MS = 60L * 60 * 1000;

    /** Ad-hoc (genuinely irregular) series: forces the binary-search path in getIndexRange. */
    private static TimeSeriesData adHocSeries() {
        long[] offsetsHours = {0, 1, 7, 50, 51, 200, 333, 334};
        long[] ts = new long[offsetsHours.length];
        double[] v = new double[offsetsHours.length];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = offsetsHours[i] * HOUR_MS;
            v[i] = i;
        }
        TimeSeriesData series = new TimeSeriesData(ts, v);
        assertFalse(series.isContiguous(), "precondition: series must take the binary-search path");
        return series;
    }

    /** Reference oracle: exclusive end bound = number of timestamps <= endTime. */
    private static int expectedEndExclusive(long[] ts, long endTime) {
        int i = 0;
        while (i < ts.length && ts[i] <= endTime) i++;
        return i;
    }

    /** Reference oracle: inclusive start bound = first index with timestamp >= startTime. */
    private static int expectedStartInclusive(long[] ts, long startTime) {
        int i = 0;
        while (i < ts.length && ts[i] < startTime) i++;
        return i;
    }

    /**
     * The regression: a viewport whose end lies EXACTLY on a point of a non-contiguous series
     * must include that point. Before the fix, binarySearchTimestamp returned the index OF the
     * matching point, which getIndexRange treats as an exclusive end — silently dropping it,
     * inconsistent with the contiguous fast path's +1.
     */
    @Test
    void irregularSeriesViewportEndExactlyOnPointIncludesThatPoint() {
        TimeSeriesData series = adHocSeries();

        // End exactly on the last point (the classic "zoom to fit drops the last point" case).
        TimeSeriesData.IndexRange range = series.getIndexRange(0, 334 * HOUR_MS);
        assertEquals(0, range.startIndex);
        assertEquals(8, range.endIndex, "point exactly at viewport end must be included");

        // End exactly on a mid-series point.
        range = series.getIndexRange(7 * HOUR_MS, 51 * HOUR_MS);
        assertEquals(2, range.startIndex, "start exactly on a point includes it");
        assertEquals(5, range.endIndex, "end exactly on the 51h point must include index 4");
    }

    @Test
    void irregularSeriesViewportEndBetweenPointsIncludesLastEarlierPoint() {
        TimeSeriesData series = adHocSeries();

        // End at 100h sits between the points at 51h (index 4) and 200h (index 5).
        TimeSeriesData.IndexRange range = series.getIndexRange(0, 100 * HOUR_MS);
        assertEquals(0, range.startIndex);
        assertEquals(5, range.endIndex, "range must stop after the 51h point (index 4)");
    }

    @Test
    void irregularSeriesViewportEndPastSeriesEndIncludesAllPoints() {
        TimeSeriesData series = adHocSeries();

        TimeSeriesData.IndexRange range = series.getIndexRange(-10 * HOUR_MS, 1000 * HOUR_MS);
        assertEquals(0, range.startIndex);
        assertEquals(8, range.endIndex, "viewport spanning the whole series includes every point");
    }

    @Test
    void irregularSeriesViewportEntirelyBeforeSeriesStartIsEmpty() {
        TimeSeriesData series = adHocSeries();

        TimeSeriesData.IndexRange range = series.getIndexRange(-10 * HOUR_MS, -5 * HOUR_MS);
        assertTrue(range.isEmpty(), "viewport ending before the first point must be empty");
        assertEquals(0, range.size());
    }

    /**
     * The phantom-segment regression: a query window entirely OUTSIDE the series (before the
     * first sample or after the last) must be empty on both index paths. The contiguous fast
     * path used to clamp startIndex to pointCount-1, handing the last sample to any
     * beyond-the-end query — the LOD column partition then dropped the last point into the
     * final pixel column, and "Draw across gaps" bridged it to the right-hand plot edge as a
     * phantom segment (auto-Y similarly picked up an out-of-view point).
     */
    @Test
    void viewportEntirelyOutsideSeriesIsEmptyOnBothPaths() {
        int n = 10;
        long[] gridTs = new long[n];
        double[] gridV = new double[n];
        for (int i = 0; i < n; i++) {
            gridTs[i] = i * DAY_MS;
            gridV[i] = i;
        }
        TimeSeriesData grid = new TimeSeriesData(gridTs, gridV);
        assertTrue(grid.isContiguous(), "precondition: series must take the fast path");

        for (TimeSeriesData series : new TimeSeriesData[]{grid, adHocSeries()}) {
            long[] ts = series.getTimestamps();
            long first = ts[0];
            long last = ts[ts.length - 1];
            String path = series.isContiguous() ? "fast path" : "binary-search path";

            // Just past the end (the LOD trailing-column query) and far past it.
            assertTrue(series.getIndexRange(last + 1, last + DAY_MS).isEmpty(),
                "window just past the series end must be empty on " + path);
            assertTrue(series.getIndexRange(last + 365 * DAY_MS, last + 730 * DAY_MS).isEmpty(),
                "window far past the series end must be empty on " + path);

            // Entirely before the first sample, including just before it.
            assertTrue(series.getIndexRange(first - DAY_MS, first - 1).isEmpty(),
                "window ending just before the first sample must be empty on " + path);
        }
    }

    /**
     * Both index paths — the contiguous O(1) arithmetic fast path and the binary-search path —
     * must implement the same half-open, end-inclusive-in-time semantics. Each is checked
     * against the same reference oracle: end bounds everywhere (exactly on a point, between
     * points, past the end), start bounds on-grid and out-of-range. (For a start strictly
     * between points the fast path deliberately reaches one point further back — floor division
     * keeps the line segment entering the viewport — so mid-interval starts are not compared.)
     */
    @Test
    void contiguousFastPathMatchesBinarySearchSemanticsOnSharedOracle() {
        // Contiguous daily grid: takes the fast path.
        int n = 10;
        long[] gridTs = new long[n];
        double[] gridV = new double[n];
        for (int i = 0; i < n; i++) {
            gridTs[i] = i * DAY_MS;
            gridV[i] = i;
        }
        TimeSeriesData grid = new TimeSeriesData(gridTs, gridV);
        assertTrue(grid.isContiguous(), "precondition: series must take the fast path");

        TimeSeriesData adHoc = adHocSeries();

        for (TimeSeriesData series : new TimeSeriesData[]{grid, adHoc}) {
            long[] ts = series.getTimestamps();
            long first = ts[0];
            long last = ts[ts.length - 1];

            // End positions: every point (exact match), midway between consecutive points,
            // before the start, and past the end.
            java.util.List<Long> ends = new java.util.ArrayList<>();
            for (long t : ts) ends.add(t);
            for (int i = 0; i + 1 < ts.length; i++) ends.add((ts[i] + ts[i + 1]) / 2);
            // Any end before the first sample is empty on both paths (the fast path
            // short-circuits before its truncating integer division can misfire).
            ends.add(first - 2 * DAY_MS);
            ends.add(first - 1);
            ends.add(last + 1);
            ends.add(last + 365 * DAY_MS);

            for (long end : ends) {
                TimeSeriesData.IndexRange range = series.getIndexRange(first, end);
                assertEquals(expectedEndExclusive(ts, end), range.endIndex,
                    "endIndex for end=" + end + " on "
                        + (series.isContiguous() ? "fast path" : "binary-search path"));
            }

            // Start positions: on-grid (every point) and before the series start.
            for (long start : ts) {
                TimeSeriesData.IndexRange range = series.getIndexRange(start, last);
                assertEquals(expectedStartInclusive(ts, start), range.startIndex,
                    "startIndex for start=" + start + " on "
                        + (series.isContiguous() ? "fast path" : "binary-search path"));
            }
            TimeSeriesData.IndexRange range = series.getIndexRange(first - DAY_MS, last);
            assertEquals(0, range.startIndex, "start before the series clamps to 0");
        }
    }

    // ---- Densification (representation B) ----

    @Test
    void densifyFillsDroppedGapsWithNaNAndRestoresContiguity() {
        // Daily points at days 0,1,2 then 5,6 — days 3 and 4 were dropped.
        TimeSeriesData gappy = new TimeSeriesData(
            new long[]{0, DAY_MS, 2 * DAY_MS, 5 * DAY_MS, 6 * DAY_MS},
            new double[]{10, 11, 12, 15, 16});
        assertFalse(gappy.isContiguous(), "precondition: series has a gap");

        TimeSeriesData dense = gappy.densified();

        assertTrue(dense.isContiguous(), "densified grid is gap-free, so contiguous");
        assertEquals(7, dense.getPointCount(), "full daily grid days 0..6");
        assertEquals(5, dense.getValidPointCount(), "the 5 original values remain valid");

        double[] v = dense.getValues();
        boolean[] valid = dense.getValidPoints();
        assertEquals(10, v[0], 1e-9);
        assertEquals(12, v[2], 1e-9);
        assertFalse(valid[3], "dropped day 3 is now a NaN slot");
        assertFalse(valid[4], "dropped day 4 is now a NaN slot");
        assertEquals(15, v[5], 1e-9, "value lands on its correct grid slot after the gap");
        assertEquals(16, v[6], 1e-9);
    }

    @Test
    void densifyIsNoOpForContiguousIrregularAndOverCapSeries() {
        TimeSeriesData contiguous = new TimeSeriesData(
            new long[]{0, DAY_MS, 2 * DAY_MS}, new double[]{1, 2, 3});
        assertSame(contiguous, contiguous.densified(), "already contiguous → unchanged instance");

        long h = 60L * 60 * 1000;
        TimeSeriesData adHoc = new TimeSeriesData(
            new long[]{0, h, 7 * h, 50 * h, 200 * h, 333 * h}, new double[6]);
        assertSame(adHoc, adHoc.densified(), "no cadence → nothing to densify");

        // Daily grid with a huge gap; cap of 10 forbids materialising the ~1000-slot grid.
        TimeSeriesData bigGap = new TimeSeriesData(
            new long[]{0, DAY_MS, 2 * DAY_MS, 1000 * DAY_MS}, new double[]{1, 2, 3, 4});
        assertSame(bigGap, bigGap.densified(10), "over-cap → left gap-bearing");
    }
}
