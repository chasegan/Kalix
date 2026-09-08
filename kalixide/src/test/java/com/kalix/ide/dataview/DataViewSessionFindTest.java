package com.kalix.ide.dataview;

import com.kalix.ide.dataview.DataFind.Scan;
import com.kalix.ide.dataview.DataFind.Spec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the unified find scan: cell-grained text matching with case /
 * whole-cell / scope options, navigation boundaries and ordinals from one
 * pass, parsed-date matching at day granularity, and the nearest-later-date
 * fallback (the old "Find date" jump, preserved).
 */
class DataViewSessionFindTest {

    private static Path csvFile(String content) throws IOException {
        Path file = Files.createTempFile("kalix-find-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static DataViewSession openComplete(String content) throws IOException {
        DataViewSession session = DataViewSession.open(csvFile(content));
        await("indexing", session::isIndexingComplete);
        return session;
    }

    private static Spec text(String query) {
        return new Spec(query, false, false, true, true, null, false);
    }

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay() * 86_400_000L;
    }

    @Test
    void boundariesAndOrdinalsFromOnePass() throws IOException {
        try (DataViewSession session = openComplete(
                "date,v\n2020-01-01,aaa\n2020-01-02,bAAAb\n2020-01-03,x\n")) {
            Scan scan = session.scanForMatches(text("aaa"), 1, Integer.MAX_VALUE);
            assertEquals(2, scan.total(), "case-insensitive by default");
            assertEquals(1, scan.firstOverall().row());
            assertEquals(1, scan.firstOverall().ordinal());
            assertEquals(2, scan.lastOverall().row());
            assertEquals(2, scan.lastOverall().ordinal());
            assertEquals(2, scan.firstAfter().row(), "next match after row 1");
            assertEquals(1, scan.lastBefore().row(), "previous match before the origin");
        }
    }

    @Test
    void matchCaseAndWholeCellNarrow() throws IOException {
        try (DataViewSession session = openComplete(
                "date,v\n2020-01-01,aaa\n2020-01-02,bAAAb\n")) {
            Spec cased = new Spec("AAA", true, false, true, true, null, false);
            assertEquals(1, session.scanForMatches(cased, -2, 0).total());
            Spec whole = new Spec("aaa", false, true, true, true, null, false);
            Scan scan = session.scanForMatches(whole, -2, 0);
            assertEquals(1, scan.total(), "whole cell: bAAAb is not aaa");
            assertEquals(1, scan.firstOverall().row());
        }
    }

    @Test
    void scopesExcludeColumns() throws IOException {
        try (DataViewSession session = openComplete("date,v\n2020-01-01,aaa\n")) {
            Spec noValues = new Spec("aaa", false, false, true, false, null, false);
            assertEquals(0, session.scanForMatches(noValues, -2, 0).total());
            Spec noDates = new Spec("2020-01-01", false, false, false, true, null, false);
            assertEquals(0, session.scanForMatches(noDates, -2, 0).total());
        }
    }

    @Test
    void headerRowNeverMatchesAsACell() throws IOException {
        try (DataViewSession session = openComplete("date,flow\n2020-01-01,1\n")) {
            assertEquals(0, session.scanForMatches(text("flow"), -2, 0).total(),
                "the header row belongs to the Columns scope, resolved by the caller");
        }
    }

    @Test
    void dateQueriesMatchByParsedDayNotSpelling() throws IOException {
        try (DataViewSession session = openComplete("Date,v\n1/01/2020,1\n5/01/2020,2\n")) {
            // The file spells dates d/M/yyyy; the query is ISO. Text match fails,
            // parsed-date match at day granularity succeeds.
            Spec spec = new Spec("2020-01-05", false, false, true, true, day(2020, 1, 5), true);
            Scan scan = session.scanForMatches(spec, -2, 0);
            assertEquals(1, scan.total());
            assertEquals(2, scan.firstOverall().row());
            assertEquals(0, scan.firstOverall().column());
            assertEquals(2, scan.nearestDateRow());
        }
    }

    @Test
    void nearestLaterDateSurvivesAsFallback() throws IOException {
        try (DataViewSession session = openComplete("date,v\n2020-01-01,1\n2020-01-05,2\n")) {
            Spec spec = new Spec("2020-01-03", false, false, true, true, day(2020, 1, 3), true);
            Scan scan = session.scanForMatches(spec, -2, 0);
            assertEquals(0, scan.total(), "no exact match for Jan 3");
            assertEquals(2, scan.nearestDateRow(), "the first row at or after it");
        }
    }

    @Test
    void pastTheEndHasNoNearestDate() throws IOException {
        try (DataViewSession session = openComplete("date,v\n2020-01-01,1\n")) {
            Spec spec = new Spec("2021-01-01", false, false, true, true, day(2021, 1, 1), true);
            Scan scan = session.scanForMatches(spec, -2, 0);
            assertEquals(0, scan.total());
            assertEquals(-1, scan.nearestDateRow());
            assertNull(scan.firstOverall());
        }
    }

    @Test
    void quotedDelimitersStayInsideTheirCell() throws IOException {
        try (DataViewSession session = openComplete("date,name\n2020-01-01,\"a,b\"\n")) {
            Scan scan = session.scanForMatches(text("a,b"), -2, 0);
            assertEquals(1, scan.total());
            assertEquals(1, scan.firstOverall().column());
        }
    }

    @Test
    void escapedQuotesScanAsTheTableDisplaysThem() throws IOException {
        try (DataViewSession session = openComplete(
                "date,msg\n2020-01-01,\"He said \"\"hi\"\"\"\n")) {
            // The table displays: He said "hi" — the scan must match that text.
            Scan scan = session.scanForMatches(text("said \"hi\""), -2, 0);
            assertEquals(1, scan.total(), "the scanned text must be the displayed text");
        }
    }

    @Test
    void finalRowWithoutNewlineIsSearched() throws IOException {
        try (DataViewSession session = openComplete("date,v\n2020-01-01,1\n2020-01-02,zz")) {
            Scan scan = session.scanForMatches(text("zz"), -2, 0);
            assertEquals(1, scan.total());
            assertEquals(2, scan.firstOverall().row());
        }
    }
}
