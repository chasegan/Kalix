package com.kalix.ide.io;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared date authority: ladder order (day-first beats month-first),
 * date-only vs date-time capture, epoch math, and the invalid sentinel. The
 * importer and the data viewer's extractor both stand on these exact rules.
 */
class CsvDatesTest {

    @Test
    void detectsIsoDateAsDateOnly() {
        CsvDates.Spec spec = CsvDates.detect("2023-01-15");
        assertNotNull(spec);
        assertTrue(spec.dateOnly());
        assertEquals(LocalDate.of(2023, 1, 15).toEpochDay() * 86_400_000L,
            CsvDates.parseMillis("2023-01-15", spec));
    }

    @Test
    void detectsDateTimeAsNotDateOnly() {
        CsvDates.Spec spec = CsvDates.detect("2023-01-15 14:30:00");
        assertNotNull(spec);
        assertFalse(spec.dateOnly());
        assertEquals(LocalDateTime.of(2023, 1, 15, 14, 30, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            CsvDates.parseMillis("2023-01-15 14:30:00", spec));
    }

    @Test
    void dayFirstBeatsMonthFirstOnAmbiguousSlashes() {
        // "05/01/2023" parses under both d/M and M/d; the ladder demotes the
        // non-standard month-first form, so this must read as 5 January.
        CsvDates.Spec spec = CsvDates.detect("05/01/2023");
        assertNotNull(spec);
        assertEquals(LocalDate.of(2023, 1, 5).toEpochDay() * 86_400_000L,
            CsvDates.parseMillis("05/01/2023", spec));
    }

    @Test
    void unpaddedDaysParse() {
        CsvDates.Spec spec = CsvDates.detect("1/06/2007");
        assertNotNull(spec);
        assertEquals(LocalDate.of(2007, 6, 1).toEpochDay() * 86_400_000L,
            CsvDates.parseMillis("1/06/2007", spec));
    }

    @Test
    void epochMathIsUtcMidnight() {
        CsvDates.Spec spec = CsvDates.detect("1970-01-02");
        assertNotNull(spec);
        assertEquals(86_400_000L, CsvDates.parseMillis("1970-01-02", spec));
    }

    @Test
    void unparseableValueYieldsSentinel() {
        CsvDates.Spec spec = CsvDates.detect("2023-01-15");
        assertEquals(CsvDates.INVALID_TS, CsvDates.parseMillis("not-a-date", spec));
        assertEquals(CsvDates.INVALID_TS, CsvDates.parseMillis("", spec));
    }

    @Test
    void garbageDetectsAsNull() {
        assertNull(CsvDates.detect("flow_cumecs"));
        assertNull(CsvDates.detect(""));
        assertNull(CsvDates.detect(null));
    }
    /**
     * Stochastic engine output spans proleptic year 0000. {@code yyyy} is year-of-era,
     * which has no year 0: the dashed date-only form only worked by falling through to
     * the ISO formatter, and the engine's sub-daily "0000-01-01 00:00:00" matched
     * nothing at all. Every rung now uses the proleptic year, {@code uuuu}.
     */
    @Test
    void year0000ParsesInEveryLadderForm() {
        long year0 = LocalDate.of(0, 1, 1).toEpochDay() * 86_400_000L;
        for (String text : new String[] {"0000-01-01", "0000/01/01", "01/01/0000", "1/1/0000"}) {
            CsvDates.Spec spec = CsvDates.detect(text);
            assertNotNull(spec, text);
            assertTrue(spec.dateOnly(), text);
            assertEquals(year0, CsvDates.parseMillis(text, spec), text);
        }
        for (String text : new String[] {"0000-01-01 00:00:00", "0000-01-01 00:00", "0000-01-01T00:00:00"}) {
            CsvDates.Spec spec = CsvDates.detect(text);
            assertNotNull(spec, text);
            assertFalse(spec.dateOnly(), text);
            assertEquals(year0, CsvDates.parseMillis(text, spec), text);
        }
    }

    /** A 0000..9999 file detects on its first row and must keep parsing to the last. */
    @Test
    void specDetectedOnYear0000RowParsesYear9999Row() {
        CsvDates.Spec spec = CsvDates.detect("0000-01-01 06:00:00");
        assertNotNull(spec);
        assertEquals(LocalDateTime.of(9999, 12, 31, 6, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            CsvDates.parseMillis("9999-12-31 06:00:00", spec));
    }
}
