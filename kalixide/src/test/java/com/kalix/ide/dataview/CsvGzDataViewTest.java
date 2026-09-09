package com.kalix.ide.dataview;

import com.kalix.ide.io.CsvGzFormat;
import com.kalix.ide.preferences.PreferenceKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code .csv.gz} data-view contract (issue #374): the payload is
 * decompressed to memory — bounded while it decompresses — and served through
 * the ordinary session machinery over an in-memory channel, so the table, text
 * view and find behave exactly as they do for the plain CSV.
 */
class CsvGzDataViewTest {

    @TempDir
    Path tempDir;

    private Path gzFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
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

    @Test
    void gzOpensAsAnOrdinarySessionOverTheDecompressedBytes() throws IOException {
        Path gz = gzFile("flows.csv.gz", "Date,flow\n2020-01-01,1.5\n2020-01-02,2.5\n");
        try (DataViewSession session = DataViewOpener.openFor(gz.toFile())) {
            await("indexing complete", session::isIndexingComplete);
            assertEquals(3, session.rowCount(), "raw count includes the header row");
            await("structure known", () -> session.columnCount() > 0);
            assertEquals("flow", session.headerRow()[1]);
            session.requestRow(1);
            await("row 1 loaded", () -> session.rowIfLoaded(1) != null);
            assertArrayEquals(new String[] {"2020-01-01", "1.5"}, session.rowIfLoaded(1),
                "cells parse from the decompressed bytes exactly as from a plain file");
        }
    }

    @Test
    void overLimitGzIsRefusedWithTheHonestReason() throws IOException {
        Path gz = gzFile("big.csv.gz", "Date,flow\n2020-01-01,1.5\n".repeat(64));
        int old = PreferenceKeys.DATAVIEW_GZIP_MEMORY_LIMIT_MB.get();
        try {
            PreferenceKeys.DATAVIEW_GZIP_MEMORY_LIMIT_MB.set(0);
            CsvGzFormat.TooLargeException e = assertThrows(CsvGzFormat.TooLargeException.class,
                () -> DataViewOpener.openFor(gz.toFile()));
            assertTrue(e.getMessage().contains("in-memory limit"), e.getMessage());
        } finally {
            PreferenceKeys.DATAVIEW_GZIP_MEMORY_LIMIT_MB.set(old);
        }
    }

    @Test
    void inMemorySessionNeverClaimsAnAppend() throws IOException {
        // A fixed-size source must send every change down the rebuild path —
        // an in-place "resume" over swapped memory would render stale offsets.
        Path gz = gzFile("tail.csv.gz", "Date,flow\n2020-01-01,1.5\n");
        try (DataViewSession session = DataViewOpener.openFor(gz.toFile())) {
            await("indexing complete", session::isIndexingComplete);
            assertFalse(session.tryResumeAppend(), "no growth to resume: caller must rebuild");
        }
    }

    @Test
    void decompressBoundedRoundTripsAndRefusesHonestly() throws IOException {
        String content = "Date,q\n2020-01-01,1.0\n";
        Path gz = gzFile("small.csv.gz", content);
        byte[] bytes = CsvGzFormat.decompressBounded(gz.toFile(), 1024 * 1024);
        assertEquals(content, new String(bytes, StandardCharsets.UTF_8));

        assertThrows(CsvGzFormat.TooLargeException.class,
            () -> CsvGzFormat.decompressBounded(gz.toFile(), 4),
            "the bound is enforced during decompression");
    }

    @Test
    void extensionTestCoversTheDoubleExtensionFamily() {
        assertTrue(CsvGzFormat.isCsvGz("flows.csv.gz"));
        assertTrue(CsvGzFormat.isCsvGz("FLOWS.CSV.GZ"));
        // A .res.csv.gz matches by suffix and opens as a plain gz CSV view —
        // no special Source-result support (out of scope by decision).
        assertTrue(CsvGzFormat.isCsvGz("results.res.csv.gz"));
        assertFalse(CsvGzFormat.isCsvGz("flows.csv"));
        assertFalse(CsvGzFormat.isCsvGz("flows.gz"));
        assertFalse(CsvGzFormat.isCsvGz(null));
    }
}
