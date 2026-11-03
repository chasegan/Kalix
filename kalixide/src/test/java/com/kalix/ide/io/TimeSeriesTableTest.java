package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the rows of the table Save Data writes and the Data page shows. */
class TimeSeriesTableTest {

    private static final long DAY = 86_400_000L;

    private static TimeSeriesData daily(long start, int points) {
        long[] times = new long[points];
        double[] values = new double[points];
        for (int i = 0; i < points; i++) {
            times[i] = start + i * DAY;
            values[i] = i;
        }
        return new TimeSeriesData(times, values);
    }

    private static TimeSeriesTable table(TimeSeriesData... series) {
        return table(PlotType.VALUES, series);
    }

    private static TimeSeriesTable table(PlotType plotType, TimeSeriesData... series) {
        DataSet ds = new DataSet();
        for (int i = 0; i < series.length; i++) {
            ds.addSeries(new DatasetSeries("f", "s" + i), series[i]);
        }
        return new TimeSeriesTable(ds, plotType, null);
    }

    @Test
    void alignedSeriesShareTheirRows() {
        long[] rows = table(daily(0, 4), daily(0, 4), daily(0, 4)).rowTimes();
        assertArrayEquals(new long[] {0, DAY, 2 * DAY, 3 * DAY}, rows);
    }

    @Test
    void seriesOnDifferentGridsAddRowsBeyondTheLongest() {
        // Three disjoint grids: the union is three times the longest series, so the
        // row buffer, sized to the longest, has to grow twice.
        long[] rows = table(daily(0, 3), daily(10 * DAY, 3), daily(20 * DAY, 3)).rowTimes();
        assertEquals(9, rows.length);
        assertEquals(0, rows[0]);
        assertEquals(22 * DAY, rows[8]);
    }

    @Test
    void exceedanceFirstColumnIsThePercentile() {
        long p12 = (long) (12.345 * PlotTypeTransformer.PERCENTILE_SCALE);
        TimeSeriesTable table = table(PlotType.EXCEEDANCE,
            new TimeSeriesData(new long[] {p12}, new double[] {3.0}));
        assertEquals("percentile", table.firstHeader());
        assertEquals("12.35", table.firstCell(p12));
    }

    @Test
    void doubleMassFirstColumnIsTheCumulativeReference() {
        // The transform stores the cumulative reference as a fake timestamp. Read as a
        // date it would be 1970-01-15: the column decodes it back to the number plotted.
        long cumRef = (long) (1234.5 * PlotTypeTransformer.NUMERIC_SCALE);
        TimeSeriesTable table = table(PlotType.DOUBLE_MASS,
            new TimeSeriesData(new long[] {cumRef}, new double[] {3.0}));
        assertEquals("cumulative reference", table.firstHeader());
        assertEquals("1234.5", table.firstCell(cumRef));
    }

    @Test
    void anEmptyTableHasNoRows() {
        TimeSeriesTable empty = new TimeSeriesTable(new DataSet(), null, null);
        assertEquals(0, empty.rowTimes().length);
        assertEquals("date", empty.firstHeader());
        assertEquals(PlotType.VALUES, empty.plotType());
    }
}
