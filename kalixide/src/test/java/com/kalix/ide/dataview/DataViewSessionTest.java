package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataViewSessionTest {

    private static Path csvFile(String content) throws IOException {
        Path file = Files.createTempFile("kalix-session-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Path bigCsv(int dataRows) throws IOException {
        StringBuilder sb = new StringBuilder("Date,flow\n");
        for (int i = 0; i < dataRows; i++) {
            sb.append("2020-01-01,").append(i).append(".5\n");
        }
        return csvFile(sb.toString());
    }

    /** Polls a condition to become true within a deadline (async pipeline settling). */
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

    @Test
    void indexesAndExposesCountsAndDialect() throws IOException {
        try (DataViewSession session = DataViewSession.open(csvFile("Date,flow\n2020-01-01,1.5\n2020-01-02,2.5\n"))) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(3, session.rowCount(), "raw count includes the header row");
            assertEquals(3, session.lineCount());
            assertEquals(',', session.dialect().delimiter());
            assertTrue(session.dialect().hasHeaderRow());
        }
    }

    @Test
    void structureBecomesKnownWithHeaderNames() throws IOException {
        try (DataViewSession session = DataViewSession.open(csvFile("Date,flow\n2020-01-01,1.5\n"))) {
            await("structure known", () -> session.columnCount() > 0);
            assertEquals(2, session.columnCount());
            assertEquals("Date", session.headerRow()[0]);
            assertEquals("flow", session.headerRow()[1]);
        }
    }

    @Test
    void rowsBeyondTheFirstBlockLoadOnRequest() throws IOException {
        try (DataViewSession session = DataViewSession.open(bigCsv(3000))) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(3001, session.rowCount());

            long target = 2500; // block 2 at the session stride of 1024
            assertNull(session.rowIfLoaded(target), "not loaded until requested");
            session.requestRow(target);
            await("row block loaded", () -> session.rowIfLoaded(target) != null);
            assertEquals("2020-01-01", session.rowIfLoaded(target)[0]);
        }
    }

    @Test
    void linesLoadOnRequestAndBlockingCopyReads() throws IOException {
        try (DataViewSession session = DataViewSession.open(bigCsv(3000))) {
            await("indexing complete", session::isIndexingComplete);

            long target = 2500;
            session.requestLine(target);
            await("line block loaded", () -> session.lineIfLoaded(target) != null);
            assertEquals("2020-01-01,2499.5", session.lineIfLoaded(target));

            assertEquals(2, session.linesBlocking(0, 2).size());
            assertEquals("Date,flow", session.linesBlocking(0, 2).get(0));
        }
    }

    @Test
    void closeIsIdempotentAndSafeMidIndex() throws IOException {
        DataViewSession session = DataViewSession.open(bigCsv(50_000));
        session.close(); // likely mid-index; must abort cleanly
        session.close(); // idempotent
    }
}
