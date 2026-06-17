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
}
