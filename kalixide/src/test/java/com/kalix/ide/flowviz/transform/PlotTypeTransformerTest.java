package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotTypeTransformerTest {

    private static final long HOUR_MS = 3_600_000L;
    private static final long T0 = 1_577_836_800_000L; // 2020-01-01T00:00Z

    private static final SeriesRef REF_A = new DatasetSeries("/test/a.csv", "a");
    private static final SeriesRef REF_B = new DatasetSeries("/test/b.csv", "b");

    private static DataSet dataSetOf(TimeSeriesData a, TimeSeriesData b) {
        DataSet ds = new DataSet();
        ds.addSeries(REF_A, a);
        ds.addSeries(REF_B, b);
        return ds;
    }

    // ---------------------------------------------------------------- exceedance

    @Test
    void exceedanceDropsInvalidPointsAndStaysWithin100Percent() {
        long[] ts = new long[5];
        for (int i = 0; i < 5; i++) ts[i] = T0 + i * HOUR_MS;
        double[] values = {5.0, Double.NaN, 3.0, 1.0, Double.NaN};
        DataSet input = new DataSet();
        input.addSeries(REF_A, new TimeSeriesData(ts, values));

        DataSet result = PlotTypeTransformer.transform(input, PlotType.EXCEEDANCE, List.of(REF_A));
        TimeSeriesData out = result.getSeries(REF_A);

        assertEquals(3, out.getPointCount(), "Invalid points must be dropped entirely");

        // No fake timestamp may exceed 100% — invalid points previously leaked in at 101%+
        // and stretched the plot's X-bounds.
        for (long fakeTs : out.getTimestamps()) {
            assertTrue(fakeTs <= 100_000_000L,
                "Exceedance position beyond 100%: " + fakeTs);
            assertTrue(fakeTs >= 0L);
        }

        // Values sorted descending along increasing exceedance probability.
        assertArrayEquals(new double[] {5.0, 3.0, 1.0}, out.getValues(), 1e-12);

        // Cunnane positions with n=3: (r - 0.4) / 3.2 * 100, encoded * 1e6.
        assertEquals((long) ((0.6 / 3.2) * 100.0 * 1_000_000), out.getTimestamps()[0]);
        assertEquals((long) ((1.6 / 3.2) * 100.0 * 1_000_000), out.getTimestamps()[1]);
        assertEquals((long) ((2.6 / 3.2) * 100.0 * 1_000_000), out.getTimestamps()[2]);
    }

    @Test
    void exceedanceSortsLargeSeriesDescending() {
        int n = 10_000;
        long[] ts = new long[n];
        double[] values = new double[n];
        Random r = new Random(7);
        for (int i = 0; i < n; i++) {
            ts[i] = T0 + i * HOUR_MS;
            values[i] = r.nextDouble() * 1000 - 500;
        }
        DataSet input = new DataSet();
        input.addSeries(REF_A, new TimeSeriesData(ts, values));

        TimeSeriesData out = PlotTypeTransformer
            .transform(input, PlotType.EXCEEDANCE, List.of(REF_A))
            .getSeries(REF_A);

        assertEquals(n, out.getPointCount());
        double[] outValues = out.getValues();
        long[] outTs = out.getTimestamps();
        for (int i = 1; i < n; i++) {
            assertTrue(outValues[i] <= outValues[i - 1], "values must be descending");
            assertTrue(outTs[i] > outTs[i - 1], "positions must be strictly increasing");
        }
        assertTrue(outTs[n - 1] <= 100_000_000L);
    }

    // ------------------------------------------------- difference vs brute force

    /** Brute-force reference alignment mirroring the original hash-map implementation. */
    private static double[] bruteForceDifference(TimeSeriesData reference, TimeSeriesData series) {
        Map<Long, Double> refMap = new HashMap<>();
        long[] refTs = reference.getTimestamps();
        double[] refValues = reference.getValues();
        boolean[] refValid = reference.getValidPoints();
        for (int i = 0; i < refTs.length; i++) {
            refMap.put(refTs[i], refValid[i] ? refValues[i] : Double.NaN);
        }

        long[] ts = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] valid = series.getValidPoints();
        double[] out = new double[ts.length];
        for (int i = 0; i < ts.length; i++) {
            Double refValue = refMap.get(ts[i]);
            out[i] = (refValue == null || !valid[i] || Double.isNaN(refValue))
                ? Double.NaN
                : values[i] - refValue;
        }
        return out;
    }

    /** Two series on deliberately mismatched grids, both with scattered NaNs. */
    private static TimeSeriesData[] mismatchedPair() {
        Random r = new Random(123);

        // Reference: every 2 hours, some NaN.
        int nRef = 500;
        long[] refTs = new long[nRef];
        double[] refValues = new double[nRef];
        for (int i = 0; i < nRef; i++) {
            refTs[i] = T0 + i * 2 * HOUR_MS;
            refValues[i] = r.nextInt(10) == 0 ? Double.NaN : r.nextDouble() * 100;
        }

        // Series: every 3 hours with an offset start, some NaN — only every 6th hour
        // coincides with the reference grid.
        int nSer = 400;
        long[] serTs = new long[nSer];
        double[] serValues = new double[nSer];
        for (int i = 0; i < nSer; i++) {
            serTs[i] = T0 - 24 * HOUR_MS + i * 3 * HOUR_MS;
            serValues[i] = r.nextInt(8) == 0 ? Double.NaN : r.nextDouble() * 100 - 50;
        }

        return new TimeSeriesData[] {
            new TimeSeriesData(refTs, refValues),
            new TimeSeriesData(serTs, serValues)
        };
    }

    @Test
    void differenceMatchesBruteForceOnMismatchedTimestamps() {
        TimeSeriesData[] pair = mismatchedPair();
        DataSet input = dataSetOf(pair[0], pair[1]);

        DataSet result = PlotTypeTransformer.transform(
            input, PlotType.DIFFERENCE, List.of(REF_A, REF_B));

        TimeSeriesData outB = result.getSeries(REF_B);
        assertArrayEquals(pair[1].getTimestamps(), outB.getTimestamps(),
            "Difference must preserve the series' own timestamps");
        assertArrayEquals(bruteForceDifference(pair[0], pair[1]), outB.getValues(), 0.0);

        // Reference vs itself: zero at valid points, NaN at its own invalid points.
        TimeSeriesData outA = result.getSeries(REF_A);
        boolean[] refValid = pair[0].getValidPoints();
        for (int i = 0; i < outA.getPointCount(); i++) {
            if (refValid[i]) {
                assertEquals(0.0, outA.getValues()[i], 0.0);
            } else {
                assertTrue(Double.isNaN(outA.getValues()[i]));
            }
        }
    }

    @Test
    void cumulativeDifferenceMatchesBruteForceOnMismatchedTimestamps() {
        TimeSeriesData[] pair = mismatchedPair();
        DataSet input = dataSetOf(pair[0], pair[1]);

        DataSet result = PlotTypeTransformer.transform(
            input, PlotType.CUMULATIVE_DIFFERENCE, List.of(REF_A, REF_B));

        // Brute-force: cumsum of the pointwise differences, skipping NaN.
        double[] diffs = bruteForceDifference(pair[0], pair[1]);
        double[] expected = new double[diffs.length];
        double running = 0.0;
        for (int i = 0; i < diffs.length; i++) {
            if (Double.isNaN(diffs[i])) {
                expected[i] = Double.NaN;
            } else {
                running += diffs[i];
                expected[i] = running;
            }
        }

        assertArrayEquals(expected, result.getSeries(REF_B).getValues(), 0.0);
    }

    // ------------------------------------------------- double mass vs brute force

    @Test
    void doubleMassMatchesBruteForceOnMismatchedTimestamps() {
        TimeSeriesData[] pair = mismatchedPair();
        DataSet input = dataSetOf(pair[0], pair[1]);

        DataSet result = PlotTypeTransformer.transform(
            input, PlotType.DOUBLE_MASS, List.of(REF_A, REF_B));
        TimeSeriesData out = result.getSeries(REF_B);

        // Brute-force mirror of the original map-based implementation.
        Map<Long, Double> refMap = new HashMap<>();
        long[] refTs = pair[0].getTimestamps();
        double[] refValues = pair[0].getValues();
        boolean[] refValid = pair[0].getValidPoints();
        for (int i = 0; i < refTs.length; i++) {
            if (refValid[i]) {
                refMap.put(refTs[i], refValues[i]);
            }
        }

        long[] ts = pair[1].getTimestamps();
        double[] values = pair[1].getValues();
        boolean[] valid = pair[1].getValidPoints();
        java.util.List<Long> expectedX = new java.util.ArrayList<>();
        java.util.List<Double> expectedY = new java.util.ArrayList<>();
        double cumRef = 0.0;
        double cumSeries = 0.0;
        for (int i = 0; i < ts.length; i++) {
            if (valid[i] && refMap.containsKey(ts[i])) {
                cumRef += refMap.get(ts[i]);
                cumSeries += values[i];
                expectedX.add((long) (cumRef * PlotTypeTransformer.NUMERIC_SCALE));
                expectedY.add(cumSeries);
            }
        }

        assertTrue(expectedX.size() > 0, "test construction sanity: some common valid points");
        assertEquals(expectedX.size(), out.getPointCount());
        for (int i = 0; i < expectedX.size(); i++) {
            assertEquals(expectedX.get(i), out.getTimestamps()[i]);
            assertEquals(expectedY.get(i), out.getValues()[i], 0.0);
        }
    }

    // --------------------------------------------------------- cumulative & misc

    @Test
    void cumulativePreservesTimestampsAndSkipsNaN() {
        long[] ts = {T0, T0 + HOUR_MS, T0 + 2 * HOUR_MS, T0 + 3 * HOUR_MS};
        double[] values = {1.0, Double.NaN, 2.0, 3.0};
        DataSet input = new DataSet();
        input.addSeries(REF_A, new TimeSeriesData(ts, values));

        TimeSeriesData out = PlotTypeTransformer
            .transform(input, PlotType.CUMULATIVE, List.of(REF_A))
            .getSeries(REF_A);

        assertArrayEquals(ts, out.getTimestamps(), "epoch-ms timestamps preserved exactly");
        assertEquals(1.0, out.getValues()[0], 0.0);
        assertTrue(Double.isNaN(out.getValues()[1]));
        assertEquals(3.0, out.getValues()[2], 0.0, "NaN must not reset the running total");
        assertEquals(6.0, out.getValues()[3], 0.0);
    }

    @Test
    void residualMassSumsDeviationFromMean() {
        long[] ts = {T0, T0 + HOUR_MS, T0 + 2 * HOUR_MS, T0 + 3 * HOUR_MS};
        double[] values = {1.0, 2.0, 3.0, 4.0}; // mean 2.5
        DataSet input = new DataSet();
        input.addSeries(REF_A, new TimeSeriesData(ts, values));

        TimeSeriesData out = PlotTypeTransformer
            .transform(input, PlotType.RESIDUAL_MASS, List.of(REF_A))
            .getSeries(REF_A);

        assertArrayEquals(ts, out.getTimestamps());
        assertArrayEquals(new double[] {-1.5, -2.0, -1.5, 0.0}, out.getValues(), 1e-12);
    }

    @Test
    void valuesPlotTypeReturnsInputUnchanged() {
        long[] ts = {T0, T0 + HOUR_MS};
        DataSet input = new DataSet();
        input.addSeries(REF_A, new TimeSeriesData(ts, new double[] {1.0, 2.0}));

        assertSame(input, PlotTypeTransformer.transform(input, PlotType.VALUES, List.of(REF_A)));
    }
}
