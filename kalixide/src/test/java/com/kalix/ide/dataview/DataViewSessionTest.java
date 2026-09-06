package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void appendResumeLiveTailExtendsInPlace() throws IOException {
        Path file = bigCsv(1500); // final block (stride 1024) is partial
        try (DataViewSession session = DataViewSession.open(file)) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(1501, session.rowCount());

            // Cache the partial final block, then let the file grow.
            session.requestRow(1500);
            await("tail block loaded", () -> session.rowIfLoaded(1500) != null);
            StringBuilder appended = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                appended.append("2020-01-02,9.9\n");
            }
            Files.writeString(file, appended, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

            assertTrue(session.tryResumeAppend(), "pure growth resumes in place");
            await("resume complete", () -> session.isIndexingComplete() && session.rowCount() == 1601);

            // Appended rows landing in the previously-partial block must be servable.
            session.requestRow(1550);
            await("appended row loads", () -> session.rowIfLoaded(1550) != null);
            assertEquals("9.9", session.rowIfLoaded(1550)[1]);
        }
    }

    @Test
    void aRewriteOrUncleanEndRefusesResume() throws IOException {
        Path rewritten = bigCsv(50);
        try (DataViewSession session = DataViewSession.open(rewritten)) {
            await("indexing complete", session::isIndexingComplete);
            Files.writeString(rewritten, "completely,different\n1,2\n", StandardCharsets.UTF_8);
            assertFalse(session.tryResumeAppend(), "rewritten bytes need a rebuild");
        }

        Path unterminated = csvFile("a,b\nc,d"); // no trailing newline: appended bytes would extend row 1
        try (DataViewSession session = DataViewSession.open(unterminated)) {
            await("indexing complete", session::isIndexingComplete);
            Files.writeString(unterminated, "x,y\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            assertFalse(session.tryResumeAppend(), "an unclean end cannot be resumed");
        }
    }

    @Test
    void lineNumberForRowWalksQuotedNewlines() throws IOException {
        // Row 1 spans two physical lines; row 2 therefore starts on line 3.
        try (DataViewSession session = DataViewSession.open(csvFile("a,b\nc,\"x\ny\"\ne,f\n"))) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(0, session.lineNumberForRow(0));
            assertEquals(1, session.lineNumberForRow(1));
            assertEquals(3, session.lineNumberForRow(2));
        }
    }

    @Test
    void findNextRowStreamsCaseInsensitivelyFromTheCursor() throws IOException {
        try (DataViewSession session = DataViewSession.open(
                csvFile("Date,flow\n2020-01-01,AAA\n2020-01-02,bbb\n2020-01-03,aaa\n"))) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(1, session.findNextRow(0, "aaa"), "case-insensitive match");
            assertEquals(3, session.findNextRow(1, "AAA"), "continues past the cursor");
            assertEquals(-1, session.findNextRow(3, "aaa"), "no wrap-around");
            assertEquals(-1, session.findNextRow(0, "zzz"));
        }
    }
}
