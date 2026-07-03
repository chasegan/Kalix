package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for the plain-CSV importer/exporter pair: round trips, date
 * handling, missing values, quoting, the strict comma rule, and the merged-row
 * export semantics.
 */
class TimeSeriesCsvRoundTripTest {

    @TempDir
    Path tempDir;

    private static final LabelResolver LABELS =
        ref -> ((DatasetSeries) ref).baseName();

    private static long dayMillis(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private File write(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p.toFile();
    }

    private static TimeSeriesCsvImporter.CsvImportResult parse(File f) {
        return TimeSeriesCsvImporter.parse(f, null);
    }

    // ---------------------------------------------------------------- import

    @Test
    void importsDailyDateOnlyCsv() throws Exception {
        File f = write("daily.csv", """
            Date,flow,level
            2020-01-01,1.5,10
            2020-01-02,2.5,20
            2020-01-03,3.5,30
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        List<NamedSeries> series = result.getSeries();
        assertEquals(2, series.size());
        assertEquals("flow", series.get(0).name());
        assertEquals("level", series.get(1).name());

        long[] ts = series.get(0).data().getTimestamps();
        assertEquals(3, ts.length);
        assertEquals(dayMillis("2020-01-01"), ts[0]);
        assertEquals(dayMillis("2020-01-03"), ts[2]);

        double[] flow = series.get(0).data().getValues();
        assertEquals(1.5, flow[0]);
        assertEquals(3.5, flow[2]);
        double[] level = series.get(1).data().getValues();
        assertEquals(20.0, level[1]);
    }

    @Test
    void importsDateTimeCsv() throws Exception {
        File f = write("hourly.csv", """
            Datetime,flow
            2020-01-01 00:00:00,1
            2020-01-01 01:00:00,2
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        long[] ts = result.getSeries().get(0).data().getTimestamps();
        assertEquals(LocalDateTime.of(2020, 1, 1, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            ts[1]);
    }

    @Test
    void importsSingleDigitDayMonthDates() throws Exception {
        File f = write("unpadded.csv", """
            Date,flow
            1/06/2007,1
            2/06/2007,2
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals(dayMillis("2007-06-01"), result.getSeries().get(0).data().getTimestamps()[0]);
    }

    @Test
    void missingValueTokensBecomeNaN() throws Exception {
        File f = write("missing.csv", """
            Date,a,b
            2020-01-01,,5
            2020-01-02,na,6
            2020-01-03,-,7
            2020-01-04,4,n/a
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        double[] a = result.getSeries().get(0).data().getValues();
        assertTrue(Double.isNaN(a[0]));
        assertTrue(Double.isNaN(a[1]));
        assertTrue(Double.isNaN(a[2]));
        assertEquals(4.0, a[3]);
        double[] b = result.getSeries().get(1).data().getValues();
        assertEquals(5.0, b[0]);
        assertTrue(Double.isNaN(b[3]));
    }

    @Test
    void skipsRowsWhereAllValuesMissingByDefault() throws Exception {
        File f = write("allmissing.csv", """
            Date,a,b
            2020-01-01,1,2
            2020-01-02,,
            2020-01-03,3,4
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals(2, result.getSeries().get(0).data().getPointCount());
        assertEquals(2, result.getStatistics().getValidRows());
    }

    @Test
    void quotedFieldsWithEmbeddedDelimiterParse() throws Exception {
        File f = write("quoted.csv", """
            Date,"flow, total",b
            2020-01-01,"1234.5",7
            2020-01-02,2,8
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals("flow, total", result.getSeries().get(0).name());
        assertEquals(1234.5, result.getSeries().get(0).data().getValues()[0]);
    }

    @Test
    void semicolonDelimiterDetected() throws Exception {
        File f = write("semi.csv", """
            Date;a;b
            2020-01-01;1.5;2.5
            2020-01-02;3.5;4.5
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals(2, result.getSeries().size());
        assertEquals(1.5, result.getSeries().get(0).data().getValues()[0]);
    }

    @Test
    void malformedRowsWarnAndAreSkipped() throws Exception {
        File f = write("badrows.csv", """
            Date,a
            2020-01-01,1
            2020-01-02,2,extra
            not-a-date,3
            2020-01-04,4
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals(2, result.getSeries().get(0).data().getPointCount());
        assertEquals(2, result.getWarnings().size(), () -> "warnings: " + result.getWarnings());
        // Line numbers in warnings are 1-based physical file lines.
        assertTrue(result.getWarnings().get(0).startsWith("Line 3:"));
        assertTrue(result.getWarnings().get(1).startsWith("Line 4:"));
    }

    @Test
    void emptyAndHeaderOnlyFilesAreErrors() throws Exception {
        assertTrue(parse(write("empty.csv", "")).hasErrors());
        assertTrue(parse(write("headeronly.csv", "Date,a\n")).hasErrors());
        assertTrue(parse(write("onecol.csv", "Date\n2020-01-01\n")).hasErrors());
    }

    @Test
    void dottedHeadersSplitIntoHierarchyPath() throws Exception {
        File f = write("dotted.csv", """
            Date,node.x.dsflow
            2020-01-01,1
            2020-01-02,2
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals("node.x.dsflow", result.getSeries().get(0).name());
        assertEquals(List.of("node", "x", "dsflow"), result.getSeries().get(0).path());
    }

    // ------------------------------------------------------------ comma rule

    @Test
    void commaRuleAcceptsOnlyCompleteThousandsGroupings() throws Exception {
        // Semicolon-delimited so comma-bearing values are single cells.
        File f = write("commas.csv", """
            Date;a;b;c;d
            2020-01-01;1,234,567.89;1,5;12,34;1,234
            """);
        TimeSeriesCsvImporter.CsvImportResult result = parse(f);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        assertEquals(1234567.89, result.getSeries().get(0).data().getValues()[0],
            "complete thousands groupings must parse");
        assertTrue(Double.isNaN(result.getSeries().get(1).data().getValues()[0]),
            "decimal comma '1,5' must be NaN, never 15");
        assertTrue(Double.isNaN(result.getSeries().get(2).data().getValues()[0]),
            "'12,34' is not a complete grouping — must be NaN");
        assertEquals(1234.0, result.getSeries().get(3).data().getValues()[0],
            "'1,234' is a complete grouping");
    }

    @Test
    void parseNumericValueDirectCases() {
        assertEquals(1.5, TimeSeriesCsvImporter.parseNumericValue("1.5"));
        assertEquals(-2.0, TimeSeriesCsvImporter.parseNumericValue("-2"));
        assertEquals(0.5, TimeSeriesCsvImporter.parseNumericValue(".5"));
        assertEquals(1.5e-3, TimeSeriesCsvImporter.parseNumericValue("1.5e-3"));
        assertEquals(1234567.89, TimeSeriesCsvImporter.parseNumericValue("1,234,567.89"));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("1,5")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("12,34")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("na")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("abc")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("Infinity")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("0x1p3")));
        assertTrue(Double.isNaN(TimeSeriesCsvImporter.parseNumericValue("1d")));
    }

    /** The hand-rolled validator must agree exactly with the regex it replaced. */
    @Test
    void isPlainNumberMatchesHistoricalRegex() {
        Pattern legacy = Pattern.compile("^[+-]?([0-9]*[.])?[0-9]+([eE][+-]?[0-9]+)?$");
        String[] probes = {
            "0", "1", "42", "-1", "+1", "1.5", "-1.5", "+.5", ".5", "1.", ".",
            "", "-", "+", "1e5", "1E5", "1e+5", "1e-5", "1.5e10", ".5e2", "1e",
            "1e+", "e5", "1.2.3", "1..2", "--1", "1-", "0.000001", "123456789",
            "1.0E-300", "Infinity", "NaN", "1d", "1f", "0x1p3", " 1", "1 ", "1,000"
        };
        for (String p : probes) {
            assertEquals(legacy.matcher(p).matches(), TimeSeriesCsvImporter.isPlainNumber(p),
                () -> "disagreement on: '" + p + "'");
        }
    }

    // ---------------------------------------------------------------- export

    private static TimeSeriesData daily(String startIso, double... values) {
        long start = dayMillis(startIso);
        long[] ts = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            ts[i] = start + i * 86_400_000L;
        }
        return new TimeSeriesData(ts, values);
    }

    @Test
    void exportWritesUnionOfTimestampsWithEmptyCells() throws Exception {
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "a"), daily("2020-01-01", 1.0, 2.0));
        ds.addSeries(new DatasetSeries("/x", "b"), daily("2020-01-02", 5.0, 6.0));

        File out = tempDir.resolve("union.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);

        List<String> lines = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
        assertEquals("Datetime,a,b", lines.get(0));
        assertEquals("2020-01-01,1.0,", lines.get(1));
        assertEquals("2020-01-02,2.0,5.0", lines.get(2));
        assertEquals("2020-01-03,,6.0", lines.get(3));
        assertEquals(4, lines.size());
    }

    @Test
    void exportEscapesCommaBearingHeaders() throws Exception {
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "flow, total"), daily("2020-01-01", 1.0));

        File out = tempDir.resolve("escaped.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);

        List<String> lines = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
        assertEquals("Datetime,\"flow, total\"", lines.get(0));
    }

    @Test
    void exportDuplicateTimestampsYieldOneRowFirstValidValue() throws Exception {
        long t1 = dayMillis("2020-01-01");
        long t2 = dayMillis("2020-01-02");
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "a"), new TimeSeriesData(
            new long[]{t1, t1, t2}, new double[]{Double.NaN, 5.0, 7.0}));

        File out = tempDir.resolve("dups.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);

        List<String> lines = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "duplicate timestamp must produce a single row");
        assertEquals("2020-01-01,5.0", lines.get(1));
        assertEquals("2020-01-02,7.0", lines.get(2));
    }

    @Test
    void exportSubDailySeriesUsesIsoDatetime() throws Exception {
        long start = dayMillis("2020-01-01");
        long[] ts = {start, start + 3_600_000L, start + 7_200_000L};
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "a"), new TimeSeriesData(ts, new double[]{1, 2, 3}));

        File out = tempDir.resolve("hourly.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);

        List<String> lines = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
        assertEquals("2020-01-01T01:00:00,2.0", lines.get(2));
    }

    @Test
    void exportExceedancePlotWritesPercentileColumn() throws Exception {
        // Exceedance uses fake timestamps: percentile * 1e6.
        long[] ts = {1_000_000L, 50_000_000L, 100_000_000L};
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "a"), new TimeSeriesData(ts, new double[]{9, 5, 1}));

        File out = tempDir.resolve("exceedance.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, PlotType.EXCEEDANCE, LABELS);

        List<String> lines = Files.readAllLines(out.toPath(), StandardCharsets.UTF_8);
        assertEquals("Percentile,a", lines.get(0));
        assertEquals("1.00,9.0", lines.get(1));
        assertEquals("50.00,5.0", lines.get(2));
        assertEquals("100.00,1.0", lines.get(3));
    }

    // ------------------------------------------------------------ round trip

    @Test
    void exportImportRoundTripPreservesValuesDatesAndMissing() throws Exception {
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "flow, A"),
            daily("2020-01-01", 1.5, Double.NaN, 3.5));
        ds.addSeries(new DatasetSeries("/x", "level"),
            daily("2020-01-01", 10.0, 20.0, Double.NaN));

        File out = tempDir.resolve("roundtrip.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);

        TimeSeriesCsvImporter.CsvImportResult result = parse(out);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());

        List<NamedSeries> read = result.getSeries();
        assertEquals(2, read.size());
        assertEquals("flow, A", read.get(0).name(), "quoted comma-bearing name must survive");
        assertEquals("level", read.get(1).name());

        long[] ts = read.get(0).data().getTimestamps();
        assertEquals(dayMillis("2020-01-01"), ts[0]);
        assertEquals(dayMillis("2020-01-03"), ts[2]);

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
    void roundTripPreservesUtf8HeadersAndSubDailyTimes() throws Exception {
        long start = dayMillis("2020-01-01");
        // 3+ points so cadence detection sees the hourly step and formats as ISO datetime.
        long[] ts = {start, start + 3_600_000L, start + 7_200_000L};
        DataSet ds = new DataSet();
        ds.addSeries(new DatasetSeries("/x", "flöw µ"),
            new TimeSeriesData(ts, new double[]{1.25, 2.75, 3.5}));

        File out = tempDir.resolve("utf8.csv").toFile();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);

        TimeSeriesCsvImporter.CsvImportResult result = parse(out);
        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals("flöw µ", result.getSeries().get(0).name(), "UTF-8 header must round trip");
        assertEquals(ts[1], result.getSeries().get(0).data().getTimestamps()[1]);
        assertEquals(2.75, result.getSeries().get(0).data().getValues()[1]);
    }
}
