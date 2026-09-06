package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the column extractor: single-pass primitive materialisation, importer
 * parity for values (missing markers, thousands grouping), the honest refusal
 * on a non-date first column, and the bad-date/blank-line accounting.
 */
class ColumnSeriesExtractorTest {

    @TempDir
    Path tempDir;

    private static final CsvDialect HEADERED =
        new CsvDialect(',', '"', StandardCharsets.UTF_8, 0, true, "LF");
    private static final CsvDialect HEADERLESS =
        new CsvDialect(',', '"', StandardCharsets.UTF_8, 0, false, "LF");

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("data.csv");
        Files.writeString(file, content);
        return file;
    }

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay() * 86_400_000L;
    }

    @Test
    void extractsRequestedColumnsInRequestOrder() throws IOException {
        Path file = write("""
            date,a,b,c
            2020-01-01,1.5,10,x
            2020-01-02,2.5,20,y
            2020-01-03,na,30,z
            """);
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {2, 1}, Long.MAX_VALUE, null);
        assertNotNull(r);
        assertFalse(r.refused());
        assertEquals(3, r.timestamps().length);
        assertEquals(day(2020, 1, 1), r.timestamps()[0]);
        assertEquals(day(2020, 1, 3), r.timestamps()[2]);
        // Request order: slot 0 = column 2 ("b"), slot 1 = column 1 ("a").
        assertEquals(10.0, r.columns()[0][0]);
        assertEquals(2.5, r.columns()[1][1]);
        // "na" is a missing marker — importer parity.
        assertTrue(Double.isNaN(r.columns()[1][2]));
        assertEquals(0, r.badDateRows());
    }

    @Test
    void nonNumericColumnBecomesNaNs() throws IOException {
        Path file = write("date,label\n2020-01-01,north\n2020-01-02,south\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, null);
        assertFalse(r.refused());
        assertTrue(Double.isNaN(r.columns()[0][0]));
        assertTrue(Double.isNaN(r.columns()[0][1]));
    }

    @Test
    void quotedThousandsGroupingParsesLikeTheImporter() throws IOException {
        Path file = write("date,flow\n2020-01-01,\"1,234.5\"\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, null);
        assertFalse(r.refused());
        assertEquals(1234.5, r.columns()[0][0]);
    }

    @Test
    void badDateRowIsSkippedAndCounted() throws IOException {
        Path file = write("date,v\n2020-01-01,1\nnot-a-date,2\n2020-01-03,3\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, null);
        assertFalse(r.refused());
        assertEquals(2, r.timestamps().length);
        assertEquals(1, r.badDateRows());
        assertEquals(3.0, r.columns()[0][1]);
    }

    @Test
    void blankLinesAreSkippedSilently() throws IOException {
        Path file = write("date,v\n2020-01-01,1\n\n2020-01-02,2\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, null);
        assertFalse(r.refused());
        assertEquals(2, r.timestamps().length);
        assertEquals(0, r.badDateRows(), "a blank line is not a bad date");
    }

    @Test
    void refusesWhenFirstColumnIsNotDates() throws IOException {
        StringBuilder content = new StringBuilder("id,v\n");
        for (int i = 0; i < 30; i++) {
            content.append("row").append(i).append(",1\n");
        }
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            write(content.toString()), HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, null);
        assertTrue(r.refused());
        assertNotNull(r.refusal());
    }

    @Test
    void headerlessFileStartsAtRowZero() throws IOException {
        Path file = write("2020-01-01,1\n2020-01-02,2\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERLESS, 0, false, new int[] {1}, Long.MAX_VALUE, null);
        assertFalse(r.refused());
        assertEquals(2, r.timestamps().length);
        assertEquals(1.0, r.columns()[0][0]);
    }

    @Test
    void finalRowWithoutTrailingNewlineIsIncluded() throws IOException {
        Path file = write("date,v\n2020-01-01,1\n2020-01-02,2");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, null);
        assertEquals(2, r.timestamps().length);
        assertEquals(2.0, r.columns()[0][1]);
    }

    @Test
    void maxRowsBoundsTheMaterialisation() throws IOException {
        Path file = write("date,v\n2020-01-01,1\n2020-01-02,2\n2020-01-03,3\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, 2, null);
        assertFalse(r.refused());
        assertEquals(2, r.timestamps().length);
    }

    @Test
    void cancelledExtractionReturnsNull() throws IOException {
        Path file = write("date,v\n2020-01-01,1\n2020-01-02,2\n");
        assertNull(ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {1}, Long.MAX_VALUE, () -> true));
    }

    @Test
    void crlfAndQuotedDelimitersFollowTheTableGrammar() throws IOException {
        Path file = write("date,name,v\r\n2020-01-01,\"a,b\",7\r\n");
        ColumnSeriesExtractor.Result r = ColumnSeriesExtractor.extract(
            file, HEADERED, 0, true, new int[] {2}, Long.MAX_VALUE, null);
        assertFalse(r.refused());
        assertEquals(1, r.timestamps().length);
        assertEquals(7.0, r.columns()[0][0]);
    }
}
