package com.kalix.ide.dataview;

import com.kalix.ide.io.CsvZipFormat;
import com.kalix.ide.preferences.PreferenceKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code .csv.zip} data-view contract (issue #409): the archive's
 * single entry is decompressed to memory — bounded while it decompresses —
 * and served through the ordinary session machinery over an in-memory
 * channel, so the table, text view and find behave exactly as they do for
 * the plain CSV. Multi-entry archives are refused (pandas parity).
 */
class CsvZipDataViewTest {

    @TempDir
    Path tempDir;

    private Path zipFile(String name, String... entries) throws IOException {
        // entries alternate: innerName, content, innerName, content...
        Path file = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
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
    void zipOpensAsAnOrdinarySessionOverTheDecompressedBytes() throws IOException {
        Path zip = zipFile("flows.csv.zip", "flows.csv", "Date,flow\n2020-01-01,1.5\n2020-01-02,2.5\n");
        try (DataViewSession session = DataViewOpener.openFor(zip.toFile())) {
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
    void multiEntryArchiveIsRefusedLikePandas() throws IOException {
        Path zip = zipFile("two.csv.zip",
            "a.csv", "Date,q\n2020-01-01,1.0\n",
            "b.csv", "Date,q\n2020-01-01,2.0\n");
        IOException e = assertThrows(IOException.class,
            () -> DataViewOpener.openFor(zip.toFile()));
        assertTrue(e.getMessage().contains("exactly one file"), e.getMessage());
    }

    @Test
    void overLimitZipIsRefusedWithTheHonestReason() throws IOException {
        Path zip = zipFile("big.csv.zip", "big.csv", "Date,flow\n2020-01-01,1.5\n".repeat(64));
        int old = PreferenceKeys.DATAVIEW_ZIP_MEMORY_LIMIT_MB.get();
        try {
            PreferenceKeys.DATAVIEW_ZIP_MEMORY_LIMIT_MB.set(0);
            CsvZipFormat.TooLargeException e = assertThrows(CsvZipFormat.TooLargeException.class,
                () -> DataViewOpener.openFor(zip.toFile()));
            assertTrue(e.getMessage().contains("in-memory limit"), e.getMessage());
        } finally {
            PreferenceKeys.DATAVIEW_ZIP_MEMORY_LIMIT_MB.set(old);
        }
    }

    @Test
    void inMemorySessionNeverClaimsAnAppend() throws IOException {
        // A fixed-size source must send every change down the rebuild path —
        // an in-place "resume" over swapped memory would render stale offsets.
        Path zip = zipFile("tail.csv.zip", "tail.csv", "Date,flow\n2020-01-01,1.5\n");
        try (DataViewSession session = DataViewOpener.openFor(zip.toFile())) {
            await("indexing complete", session::isIndexingComplete);
            assertFalse(session.tryResumeAppend(), "no growth to resume: caller must rebuild");
        }
    }

    @Test
    void decompressBoundedRoundTripsAndRefusesHonestly() throws IOException {
        String content = "Date,q\n2020-01-01,1.0\n";
        Path zip = zipFile("small.csv.zip", "small.csv", content);
        byte[] bytes = CsvZipFormat.decompressBounded(zip.toFile(), 1024 * 1024);
        assertEquals(content, new String(bytes, StandardCharsets.UTF_8));

        assertThrows(CsvZipFormat.TooLargeException.class,
            () -> CsvZipFormat.decompressBounded(zip.toFile(), 4),
            "the bound is enforced during decompression");
    }

    @Test
    void writerReadsBackAndPinsTheEntryName() throws IOException {
        Path out = tempDir.resolve("written.csv.zip");
        try (var writer = CsvZipFormat.newUtf8Writer(out.toFile())) {
            writer.write("Date,q\n2020-01-01,3.0\n");
        }
        assertEquals(java.util.List.of("written.csv"), CsvZipFormat.fileEntryNames(out.toFile()),
            "the single entry is named like the archive minus .zip (pandas' convention)");
        assertEquals("Date,q\n2020-01-01,3.0\n",
            new String(CsvZipFormat.decompressBounded(out.toFile(), 1024), StandardCharsets.UTF_8));
    }

    @Test
    void extensionTestCoversTheDoubleExtensionFamily() {
        assertTrue(CsvZipFormat.isCsvZip("flows.csv.zip"));
        assertTrue(CsvZipFormat.isCsvZip("FLOWS.CSV.ZIP"));
        // A .res.csv.zip matches by suffix and opens as a plain zip CSV view —
        // no special Source-result support (out of scope by decision).
        assertTrue(CsvZipFormat.isCsvZip("results.res.csv.zip"));
        assertFalse(CsvZipFormat.isCsvZip("flows.csv"));
        assertFalse(CsvZipFormat.isCsvZip("archive.zip"),
            "a plain zip is an archive, not a dataset");
        assertFalse(CsvZipFormat.isCsvZip(null));
    }
}
