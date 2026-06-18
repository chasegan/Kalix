package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceResCsvExporterTest {

    @TempDir
    Path tempDir;

    /** LabelResolver projecting a DatasetSeries to its baseName (the series label). */
    private static final LabelResolver LABELS = new LabelResolver() {
        @Override
        public String labelFor(SeriesRef ref) {
            return ((DatasetSeries) ref).baseName();
        }
    };

    private static long millis(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static TimeSeriesData series(double[] values) {
        long[] ts = { millis("2020-01-01"), millis("2020-01-02"), millis("2020-01-03") };
        return new TimeSeriesData(ts, values);
    }

    @Test
    void roundTripsNamesAndValuesIncludingNaN() throws Exception {
        DataSet dataSet = new DataSet();
        // One name carries a comma to exercise CSV escaping through the Name field and header.
        dataSet.addSeries(new DatasetSeries("/x", "Flow, A"),
            series(new double[] { 1.5, Double.NaN, 3.5 }));
        dataSet.addSeries(new DatasetSeries("/x", "Level B"),
            series(new double[] { 10.0, 20.0, Double.NaN }));

        File out = tempDir.resolve("out.res.csv").toFile();
        SourceResCsvExporter.export(dataSet, out, LABELS);

        SourceResCsvImporter.ResCsvImportResult result = SourceResCsvImporter.parse(out);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        List<NamedSeries> read = result.getSeries();
        assertEquals(2, read.size());
        assertEquals("Flow, A", read.get(0).name(), "comma-bearing name must survive escaping");
        assertEquals("Level B", read.get(1).name());

        double[] a = read.get(0).data().getValues();
        assertEquals(1.5, a[0]);
        assertTrue(Double.isNaN(a[1]), "exported NaN (empty cell) must read back as NaN");
        assertEquals(3.5, a[2]);

        double[] b = read.get(1).data().getValues();
        assertEquals(10.0, b[0]);
        assertEquals(20.0, b[1]);
        assertTrue(Double.isNaN(b[2]));
    }

    @Test
    void writesMarkersAndEmptyCellsForNaN() throws Exception {
        DataSet dataSet = new DataSet();
        dataSet.addSeries(new DatasetSeries("/x", "S1"),
            series(new double[] { 1.0, Double.NaN, 3.0 }));

        File out = tempDir.resolve("markers.res.csv").toFile();
        SourceResCsvExporter.export(dataSet, out, LABELS);

        List<String> lines = Files.readAllLines(out.toPath());
        assertTrue(lines.contains("EOM"));
        assertTrue(lines.contains("EOC"));
        assertTrue(lines.contains("EOH"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Missing data value,")));
        // The NaN row writes an empty cell (trailing comma, nothing after it).
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("2020-01-02,") && l.endsWith(",")),
            "NaN should be written as an empty cell");
    }

    @Test
    void emptyDatasetIsRejected() {
        DataSet empty = new DataSet();
        File out = tempDir.resolve("empty.res.csv").toFile();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> SourceResCsvExporter.export(empty, out, LABELS));
    }
}
