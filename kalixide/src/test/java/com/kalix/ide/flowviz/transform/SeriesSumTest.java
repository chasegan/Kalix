package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesSumTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long[] DAYS = {0, DAY_MS, 2 * DAY_MS};

    private static TimeSeriesData series(double... values) {
        return new TimeSeriesData(DAYS, values);
    }

    @Test
    void sumsPointByPoint() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(1, 2, 3));
        sum.add(series(10, 20, 30));
        sum.add(series(0.5, 0.5, 0.5));

        TimeSeriesData result = sum.result();
        assertArrayEquals(DAYS, result.getTimestamps());
        assertArrayEquals(new double[]{11.5, 22.5, 33.5}, result.getValues(), 1e-12);
        assertEquals(3, sum.count());
    }

    @Test
    void pointMissingInAnyInputIsMissingInSum() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(1, Double.NaN, 3));
        sum.add(series(1, 2, Double.POSITIVE_INFINITY));
        sum.add(series(Double.NaN, 2, 3));

        boolean[] valid = sum.result().getValidPoints();
        assertFalse(valid[0]);
        assertFalse(valid[1]);
        assertFalse(valid[2]);
    }

    @Test
    void missingPointStaysMissingWhenLaterInputsAreValid() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(Double.NaN, 1, 1));
        sum.add(series(5, 1, 1));

        TimeSeriesData result = sum.result();
        assertTrue(Double.isNaN(result.getValues()[0]));
        assertEquals(2, result.getValues()[1]);
    }

    @Test
    void singleInputIsCopiedNotShared() {
        TimeSeriesData input = series(1, 2, 3);
        SeriesSum sum = new SeriesSum();
        sum.add(input);

        TimeSeriesData result = sum.result();
        assertArrayEquals(input.getValues(), result.getValues());
        assertNotSame(input.getTimestamps(), result.getTimestamps());
        assertNotSame(input.getValues(), result.getValues());
    }

    @Test
    void differentLengthIsRejected() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(1, 2, 3));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> sum.add(new TimeSeriesData(new long[]{0, DAY_MS}, new double[]{1, 2})));
        assertTrue(e.getMessage().contains("2 points"), e.getMessage());
    }

    @Test
    void differentTimestampIsRejectedAndNamed() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(1, 2, 3));
        long[] shifted = {0, DAY_MS, 3 * DAY_MS};
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> sum.add(new TimeSeriesData(shifted, new double[]{1, 2, 3})));
        assertTrue(e.getMessage().contains("1970-01-04T00:00:00Z"), e.getMessage());
        assertTrue(e.getMessage().contains("point 3"), e.getMessage());
    }

    @Test
    void rejectedInputLeavesSumUnchanged() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(1, 2, 3));
        assertThrows(IllegalArgumentException.class,
            () -> sum.add(new TimeSeriesData(new long[]{0}, new double[]{9})));
        assertEquals(1, sum.count());
        assertArrayEquals(new double[]{1, 2, 3}, sum.result().getValues());
    }

    @Test
    void emptySumIsRejected() {
        assertThrows(IllegalStateException.class, () -> new SeriesSum().result());
    }

    @Test
    void resultIsOneShot() {
        SeriesSum sum = new SeriesSum();
        sum.add(series(1, 2, 3));
        sum.result();
        assertThrows(IllegalStateException.class, sum::result);
        assertThrows(IllegalStateException.class, () -> sum.add(series(1, 2, 3)));
    }
}
