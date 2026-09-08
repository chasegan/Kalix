package com.kalix.ide.dataview;

import com.kalix.ide.dataview.DataFind.Scan;
import com.kalix.ide.dataview.DataFind.Spec;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the pixie side of the find seam: the in-memory scan answers the SAME
 * contract as the CSV stream — shared text rules, parsed-date matching off
 * the stored timestamps, the nearest-later-date fallback, ordinals from the
 * shared collector — and absent (blank) cells can never match.
 */
class PixieDataSessionFindTest {

    @TempDir
    Path tempDir;

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

    private static TimeSeriesData daily(LocalDateTime start, double... values) {
        LocalDateTime[] times = new LocalDateTime[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = start.plusDays(i);
        }
        return new TimeSeriesData(times, values);
    }

    private PixieDataSession open(String name, List<NamedSeries> series) throws IOException {
        String base = tempDir.resolve(name).toString();
        new PixieWriter().writeToFile(base, series, true);
        PixieDataSession session = new PixieDataSession(new File(base + ".pxt"), () -> 100_000);
        await("decode", session::isLoaded);
        return session;
    }

    private static Spec text(String query) {
        return new Spec(query, false, false, true, true, null, false);
    }

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay() * 86_400_000L;
    }

    @Test
    void valueTextMatchesWithSharedOrdinals() throws IOException {
        PixieDataSession session = open("values", List.of(
            new NamedSeries("a", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.5, 2.0, 1.5)),
            new NamedSeries("b", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 9.0, 1.5, 9.0))));
        Scan scan = session.scanForMatches(text("1.5"), 0, Integer.MAX_VALUE);
        assertEquals(3, scan.total());
        assertEquals(0, scan.firstOverall().row());
        assertEquals(1, scan.firstOverall().column(), "series a is model column 1");
        assertEquals(1, scan.firstAfter().row(), "next match after the origin row");
        assertEquals(2, scan.firstAfter().column(), "b's 1.5 on Jan 2");
        assertEquals(1, scan.lastBefore().ordinal());
        session.dispose();
    }

    @Test
    void dateQueriesMatchByTimestampNotSpelling() throws IOException {
        PixieDataSession session = open("dates", List.of(
            new NamedSeries("a", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1, 2, 3))));
        // "02/01/2020" never appears as text (the table spells 2020-01-02), but
        // the parsed day matches straight off the stored timestamp.
        Spec spec = new Spec("02/01/2020", false, false, true, true, day(2020, 1, 2), true);
        Scan scan = session.scanForMatches(spec, -1, 0);
        assertEquals(1, scan.total());
        assertEquals(1, scan.firstOverall().row());
        assertEquals(0, scan.firstOverall().column(), "the date column");
        session.dispose();
    }

    @Test
    void nearestLaterDateFallbackIsReported() throws IOException {
        PixieDataSession session = open("nearest", List.of(
            new NamedSeries("a", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1, 2))));
        Spec spec = new Spec("2019-12-25", false, false, true, true, day(2019, 12, 25), true);
        Scan scan = session.scanForMatches(spec, -1, 0);
        assertEquals(0, scan.total(), "no exact Dec 25");
        assertEquals(0, scan.nearestDateRow(), "the first row at or after it");
        session.dispose();
    }

    @Test
    void absentCellsInMixedTimeBasesNeverMatch() throws IOException {
        PixieDataSession session = open("mixed", List.of(
            new NamedSeries("a", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 7.0)),
            new NamedSeries("b", daily(LocalDateTime.of(2020, 1, 2, 0, 0), 7.0))));
        Scan scan = session.scanForMatches(text("7"), -1, 0);
        assertEquals(2, scan.total(), "one real 7 per series; blanks match nothing");
        assertEquals(1, scan.firstOverall().column());
        assertEquals(2, scan.lastOverall().column());
        session.dispose();
    }

    @Test
    void scopesRestrictExactlyAsInCsv() throws IOException {
        PixieDataSession session = open("scopes", List.of(
            new NamedSeries("a", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 2020.0))));
        Spec valuesOnly = new Spec("2020", false, false, false, true, null, false);
        assertEquals(1, session.scanForMatches(valuesOnly, -1, 0).total(), "only the value cell");
        Spec datesOnly = new Spec("2020", false, false, true, false, null, false);
        assertEquals(1, session.scanForMatches(datesOnly, -1, 0).total(), "only the date cell");
        session.dispose();
    }
}
