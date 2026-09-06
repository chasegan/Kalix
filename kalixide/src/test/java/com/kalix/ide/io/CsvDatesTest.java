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
}
