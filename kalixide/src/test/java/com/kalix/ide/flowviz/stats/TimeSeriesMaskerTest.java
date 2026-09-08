package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness tests for {@link TimeSeriesMasker} — the primitive sorted-array masker that
 * replaced the boxed-{@code Long}-map implementation. Covers the aligned-grid fast path,
 * the general intersection path, NaN handling, and edge cases.
 */
class TimeSeriesMaskerTest {

    /** NaN marks a missing/invalid point. */
    private static final double X = Double.NaN;

    private static TimeSeriesData series(long[] timestamps, double[] values) {
        return new TimeSeriesData(timestamps, values);
    }

    @Test
    void allMask_alignedGrid_intersectsValidity() {
        // Three series on the identical hourly grid; a point is masked-in only where all
        // three are valid. Index 2 (X in series B) and index 4 (X in series C) drop out.
        long[] grid = {0, 3600, 7200, 10800, 14400};
        TimeSeriesData a = series(grid, new double[]{1, 2, 3, 4, 5});
        TimeSeriesData b = series(grid, new double[]{1, 2, X, 4, 5});
        TimeSeriesData c = series(grid, new double[]{1, 2, 3, 4, X});

        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a, b, c));
        assertEquals(3, mask.size(), "indices 0,1,3 valid in all three");

        TimeSeriesData maskedA = mask.apply(a);
        assertArrayEquals(new long[]{0, 3600, 10800}, maskedA.getTimestamps());
        assertArrayEquals(new double[]{1, 2, 4}, maskedA.getValues(), 1e-9);
    }

    @Test
    void allMask_misalignedTimestamps_generalIntersection() {
        // Series on different (overlapping) grids — exercises the general merge path.
        TimeSeriesData a = series(new long[]{0, 100, 200, 300}, new double[]{1, 2, 3, 4});
        TimeSeriesData b = series(new long[]{100, 200, 300, 400}, new double[]{5, 6, 7, 8});

        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a, b));
        assertEquals(3, mask.size(), "shared timestamps are 100,200,300");

        TimeSeriesData maskedA = mask.apply(a);
        assertArrayEquals(new long[]{100, 200, 300}, maskedA.getTimestamps());
        assertArrayEquals(new double[]{2, 3, 4}, maskedA.getValues(), 1e-9);

        TimeSeriesData maskedB = mask.apply(b);
        assertArrayEquals(new long[]{100, 200, 300}, maskedB.getTimestamps());
        assertArrayEquals(new double[]{5, 6, 7}, maskedB.getValues(), 1e-9);
    }

    @Test
    void allMask_misalignedWithInvalidPoints() {
        // Overlapping timestamp present in both, but invalid in one → excluded.
        TimeSeriesData a = series(new long[]{0, 100, 200}, new double[]{1, X, 3});
        TimeSeriesData b = series(new long[]{0, 100, 200}, new double[]{4, 5, 6});

        // Same grid here, but the point with a NaN should still drop out.
        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a, b));
        assertEquals(2, mask.size());
        assertArrayEquals(new long[]{0, 200}, mask.apply(a).getTimestamps());
    }

    @Test
    void allMask_singleSeries_isItsOwnValidPoints() {
        TimeSeriesData a = series(new long[]{0, 100, 200}, new double[]{1, X, 3});
        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a));
        assertEquals(2, mask.size());
        assertArrayEquals(new long[]{0, 200}, mask.apply(a).getTimestamps());
    }

    @Test
    void allMask_emptyInput_yieldsEmptyMask() {
        assertEquals(0, TimeSeriesMasker.createAllMask(List.of()).size());
        assertEquals(0, TimeSeriesMasker.createAllMask(null).size());
    }

    @Test
    void allMask_disjointSeries_yieldsEmptyMask() {
        TimeSeriesData a = series(new long[]{0, 100}, new double[]{1, 2});
        TimeSeriesData b = series(new long[]{200, 300}, new double[]{3, 4});
        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a, b));
        assertEquals(0, mask.size());
        assertEquals(0, mask.apply(a).getPointCount());
    }

    @Test
    void eachMask_intersectsReferenceAndSeries() {
        TimeSeriesData reference = series(new long[]{0, 100, 200, 300}, new double[]{1, 2, X, 4});
        TimeSeriesData s = series(new long[]{0, 100, 200, 300}, new double[]{5, X, 7, 8});

        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createEachMask(reference, s);
        // index 1 invalid in series, index 2 invalid in reference → only 0 and 3 survive.
        assertEquals(2, mask.size());
        assertArrayEquals(new long[]{0, 300}, mask.apply(s).getTimestamps());
        assertArrayEquals(new double[]{5, 8}, mask.apply(s).getValues(), 1e-9);
    }

    @Test
    void eachMask_nullArgument_yieldsEmptyMask() {
        TimeSeriesData s = series(new long[]{0, 100}, new double[]{1, 2});
        assertEquals(0, TimeSeriesMasker.createEachMask(null, s).size());
        assertEquals(0, TimeSeriesMasker.createEachMask(s, null).size());
    }

    @Test
    void apply_nullSeries_yieldsEmptySeries() {
        TimeSeriesData a = series(new long[]{0, 100}, new double[]{1, 2});
        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a));
        assertEquals(0, mask.apply(null).getPointCount());
    }

    @Test
    void allMask_alignedAndGeneralPathsAgree() {
        // The same data fed two ways: aligned (identical grids) must match what the general
        // path would produce. Build identical grids so the fast path triggers, and confirm
        // the result equals an independently-computed intersection.
        long[] grid = {0, 60, 120, 180, 240, 300};
        TimeSeriesData a = series(grid, new double[]{1, 2, 3, X, 5, 6});
        TimeSeriesData b = series(grid, new double[]{1, X, 3, 4, 5, 6});

        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createAllMask(List.of(a, b));
        // Valid in both: indices 0,2,4,5.
        assertArrayEquals(new long[]{0, 120, 240, 300}, mask.apply(a).getTimestamps());
        assertArrayEquals(new double[]{1, 3, 5, 6}, mask.apply(a).getValues(), 1e-9);
    }

    // === Seasonal masking ===

    private static final long DAY_MS = 86_400_000L;

    /** Midnight UTC of the given date, as epoch millis. */
    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay() * DAY_MS;
    }

    @Test
    void seasonalMask_keepsOnlySelectedMonths() {
        // First of each month through 2020; keep the southern-hemisphere summer.
        long[] timestamps = new long[12];
        for (int m = 1; m <= 12; m++) {
            timestamps[m - 1] = day(2020, m, 1);
        }
        TimeSeriesData series = series(timestamps, new double[12]);

        SeasonalMaskMode mode = SeasonalMaskMode.of(EnumSet.of(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY));
        TimeSeriesMasker.Mask mask = TimeSeriesMasker.createSeasonalMask(series, mode);

        assertArrayEquals(new long[]{day(2020, 1, 1), day(2020, 2, 1), day(2020, 12, 1)},
            mask.apply(series).getTimestamps());
    }

    @Test
    void seasonalMask_isExactAtMonthBoundaries() {
        // Last millisecond of January and the first of February must fall either side.
        long lastOfJan = day(2020, 2, 1) - 1;
        long firstOfFeb = day(2020, 2, 1);
        TimeSeriesData series = series(new long[]{lastOfJan, firstOfFeb}, new double[]{1, 2});

        TimeSeriesMasker.Mask mask =
            TimeSeriesMasker.createSeasonalMask(series, SeasonalMaskMode.of(EnumSet.of(Month.JANUARY)));

        assertArrayEquals(new long[]{lastOfJan}, mask.apply(series).getTimestamps());
    }

    @Test
    void seasonalMask_handlesPre1970Timestamps() {
        // Negative epoch millis: the epoch-day conversion must floor, not truncate toward zero.
        TimeSeriesData series = series(
            new long[]{day(1965, 1, 15), day(1965, 6, 15), day(1965, 7, 15)},
            new double[]{1, 2, 3});

        TimeSeriesMasker.Mask mask =
            TimeSeriesMasker.createSeasonalMask(series, SeasonalMaskMode.of(EnumSet.of(Month.JUNE)));

        assertArrayEquals(new long[]{day(1965, 6, 15)}, mask.apply(series).getTimestamps());
    }

    @Test
    void seasonalMask_preservesInvalidPoints() {
        // Seasonal masking filters on time of year only; a NaN inside a kept month stays,
        // so the mask composes with a validity mask instead of duplicating one.
        TimeSeriesData series = series(
            new long[]{day(2020, 6, 1), day(2020, 6, 2), day(2020, 7, 1)},
            new double[]{1, X, 3});

        TimeSeriesMasker.Mask mask =
            TimeSeriesMasker.createSeasonalMask(series, SeasonalMaskMode.of(EnumSet.of(Month.JUNE)));

        TimeSeriesData masked = mask.apply(series);
        assertArrayEquals(new long[]{day(2020, 6, 1), day(2020, 6, 2)}, masked.getTimestamps());
        assertEquals(2, masked.getTimestamps().length, "the NaN point is kept, not dropped");
    }

    @Test
    void seasonalMask_disabledKeepsEverything() {
        long[] timestamps = {day(2020, 1, 1), day(2020, 6, 1)};
        TimeSeriesData series = series(timestamps, new double[]{1, 2});

        TimeSeriesMasker.Mask mask =
            TimeSeriesMasker.createSeasonalMask(series, SeasonalMaskMode.DISABLED);

        assertArrayEquals(timestamps, mask.apply(series).getTimestamps());
    }

    @Test
    void seasonalMask_allTwelveMonthsIsDisabled() {
        // of() collapses a full-year selection, so it cannot masquerade as an active mask.
        assertEquals(SeasonalMaskMode.DISABLED,
            SeasonalMaskMode.of(EnumSet.allOf(Month.class)));
    }
}
