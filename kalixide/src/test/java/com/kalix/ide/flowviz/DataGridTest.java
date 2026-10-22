package com.kalix.ide.flowviz;

import com.kalix.ide.dataview.DataFind.Scan;
import com.kalix.ide.dataview.DataFind.Spec;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.io.TimeSeriesCsvExporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the Data page's snapshot: cells spelled exactly as Save Data writes them,
 * and the find seam answering the same contract as the CSV and pixie views.
 */
class DataGridTest {

    private static final LabelResolver LABELS = SeriesRef::baseName;
    private static final long DAY = 86_400_000L;

    @TempDir
    Path tempDir;

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay() * DAY;
    }

    private static TimeSeriesData daily(long start, double... values) {
        long[] times = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = start + i * DAY;
        }
        return new TimeSeriesData(times, values);
    }

    private static DataSet dataSet(Object... namesAndSeries) {
        DataSet ds = new DataSet();
        for (int i = 0; i < namesAndSeries.length; i += 2) {
            ds.addSeries(new DatasetSeries("f" + i, (String) namesAndSeries[i]),
                (TimeSeriesData) namesAndSeries[i + 1]);
        }
        return ds;
    }

    private static Spec text(String query) {
        return new Spec(query, false, false, true, true, null, false);
    }

    @Test
    void mixedGridsMergeRowsWithBlankAbsentCells() {
        DataGrid grid = DataGrid.of(dataSet(
            "a", daily(day(2020, 1, 1), 1.0, 2.0),
            "b", daily(day(2020, 1, 2), 3.0, 4.0)), PlotType.VALUES, LABELS);
        assertEquals(3, grid.rowCount());
        assertEquals("date", grid.indexHeader());
        assertEquals("2020-01-01", grid.indexText(0));
        assertEquals("1.0", grid.valueText(0, 0));
        assertEquals("", grid.valueText(0, 1), "b has no Jan 1 point");
        assertEquals("", grid.valueText(2, 0), "a has no Jan 3 point");
        assertEquals("4.0", grid.valueText(2, 1));
    }

    @Test
    void storedNanRendersBlankAndNeverMatches() {
        DataGrid grid = DataGrid.of(dataSet("a", daily(day(2020, 1, 1), Double.NaN, 5.0)),
            PlotType.VALUES, LABELS);
        assertEquals("", grid.valueText(0, 0));
        assertEquals(0, grid.scanForMatches(text("NaN"), -1, 0).total());
    }

    @Test
    void duplicateTimestampsShowTheFirstValidValue() {
        long t = day(2020, 1, 1);
        TimeSeriesData dup = new TimeSeriesData(new long[] {t, t, t}, new double[] {Double.NaN, 7.0, 8.0});
        DataGrid grid = DataGrid.of(dataSet("a", dup), PlotType.VALUES, LABELS);
        assertEquals(1, grid.rowCount());
        assertEquals("7.0", grid.valueText(0, 0), "the export's rule: first valid among duplicates");
    }

    @Test
    void duplicateLabelsAreNumberedAsInTheExport() {
        DataGrid grid = DataGrid.of(dataSet(
            "x", daily(day(2020, 1, 1), 1.0),
            "x", daily(day(2020, 1, 1), 2.0)), PlotType.VALUES, LABELS);
        assertEquals("x", grid.seriesHeader(0));
        assertEquals("x (2)", grid.seriesHeader(1));
    }

    @Test
    void textAndWholeCellMatching() {
        DataGrid grid = DataGrid.of(dataSet("a", daily(day(2020, 1, 1), 1.5, 21.5)),
            PlotType.VALUES, LABELS);
        assertEquals(2, grid.scanForMatches(text("1.5"), -1, 0).total());
        Spec whole = new Spec("1.5", false, true, true, true, null, false);
        Scan scan = grid.scanForMatches(whole, -1, 0);
        assertEquals(1, scan.total());
        assertEquals(0, scan.firstOverall().row());
        assertEquals(1, scan.firstOverall().column(), "series a is model column 1");
    }

    @Test
    void dateQueriesMatchByParsedDateAndFallBackToNearestLater() {
        DataGrid grid = DataGrid.of(dataSet("a", daily(day(2020, 1, 1), 1, 2, 3)),
            PlotType.VALUES, LABELS);
        Spec exact = new Spec("02/01/2020", false, false, true, true, day(2020, 1, 2), true);
        Scan scan = grid.scanForMatches(exact, -1, 0);
        assertEquals(1, scan.total());
        assertEquals(1, scan.firstOverall().row());
        assertEquals(0, scan.firstOverall().column(), "the index column");

        Spec earlier = new Spec("2019-12-25", false, false, true, true, day(2019, 12, 25), true);
        Scan none = grid.scanForMatches(earlier, -1, 0);
        assertEquals(0, none.total());
        assertEquals(0, none.nearestDateRow());
    }

    @Test
    void exceedanceIndexMatchesAsPercentileTextNotAsDates() {
        long p50 = 50 * PlotTypeTransformer.PERCENTILE_SCALE;
        TimeSeriesData exceedance = new TimeSeriesData(new long[] {p50}, new double[] {3.0});
        DataGrid grid = DataGrid.of(dataSet("a", exceedance), PlotType.EXCEEDANCE, LABELS);
        assertEquals("percentile", grid.indexHeader());
        assertEquals("50.00", grid.indexText(0));
        Scan scan = grid.scanForMatches(text("50.00"), -1, 0);
        assertEquals(1, scan.total());
        assertEquals(0, scan.firstOverall().column());

        // The percentile's fake timestamp is 50,000,000 ms after the epoch: Jan 1 1970.
        Spec date = new Spec("1970-01-01", false, false, true, true, 0L, true);
        Scan dated = grid.scanForMatches(date, -1, 0);
        assertEquals(0, dated.total(), "no parsed-date match on a percentile index");
        assertEquals(-1, dated.nearestDateRow(), "and no nearest-date landing");
    }

    @Test
    void cellsSpellExactlyWhatTheCsvExportWrites() throws IOException {
        DataSet ds = dataSet(
            "a", daily(day(2020, 1, 1), 1.0, Double.NaN, 1e-7, 123456.789),
            "b", daily(day(2020, 1, 3), -2.5, 0.0, 9.0));
        File out = tempDir.resolve("out.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, PlotType.VALUES, LABELS);
        List<String> lines = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);

        DataGrid grid = DataGrid.of(ds, PlotType.VALUES, LABELS);
        List<String> rendered = new ArrayList<>();
        rendered.add(grid.indexHeader() + "," + grid.seriesHeader(0) + "," + grid.seriesHeader(1));
        for (int row = 0; row < grid.rowCount(); row++) {
            StringBuilder line = new StringBuilder(grid.indexText(row));
            for (int s = 0; s < grid.seriesCount(); s++) {
                line.append(',').append(grid.valueText(row, s));
            }
            rendered.add(line.toString());
        }
        assertEquals(lines, rendered);
    }
}
