package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link DataViewSession#findDateRow}: first row at or after the target
 * (chronological files), miss semantics past the end, and the honest -1 for a
 * non-date first column.
 */
class DataViewSessionFindDateTest {

    private static Path csvFile(String content) throws IOException {
        Path file = Files.createTempFile("kalix-find-date-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay() * 86_400_000L;
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

    @Test
    void landsOnTheFirstRowAtOrAfterTheTarget() throws IOException {
        try (DataViewSession session = openComplete(
                "date,v\n2020-01-01,1\n2020-01-05,2\n2020-01-09,3\n")) {
            // Exact hit: file row 2 (row 0 is the header).
            assertEquals(2, session.findDateRow(day(2020, 1, 5)));
            // Between rows: the next row at or after.
            assertEquals(3, session.findDateRow(day(2020, 1, 6)));
            // Before everything: the first data row.
            assertEquals(1, session.findDateRow(day(2019, 6, 1)));
        }
    }

    @Test
    void pastTheEndIsAMiss() throws IOException {
        try (DataViewSession session = openComplete("date,v\n2020-01-01,1\n2020-01-02,2\n")) {
            assertEquals(-1, session.findDateRow(day(2021, 1, 1)));
        }
    }

    @Test
    void nonDateFirstColumnIsAMiss() throws IOException {
        try (DataViewSession session = openComplete("id,v\nrow1,1\nrow2,2\n")) {
            assertEquals(-1, session.findDateRow(day(2020, 1, 1)));
        }
    }

    @Test
    void finalRowWithoutNewlineIsFindable() throws IOException {
        try (DataViewSession session = openComplete("date,v\n2020-01-01,1\n2020-01-02,2")) {
            assertEquals(2, session.findDateRow(day(2020, 1, 2)));
        }
    }
}
