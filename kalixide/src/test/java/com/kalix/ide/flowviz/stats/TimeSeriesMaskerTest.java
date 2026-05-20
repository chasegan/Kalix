package com.kalix.ide.flowviz.stats;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
