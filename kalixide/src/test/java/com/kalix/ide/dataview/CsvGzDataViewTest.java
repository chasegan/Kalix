package com.kalix.ide.dataview;

import com.kalix.ide.io.CompressedCsvFormat;
import com.kalix.ide.preferences.PreferenceKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code .csv.gz} data-view contract, the gzip sibling
 * of {@link CsvZipDataViewTest}: decompressed to memory, bounded while it
 * decompresses, served over an in-memory channel, and written reproducibly.
 */
class CsvGzDataViewTest {

    @TempDir
    Path tempDir;

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private Path gzFile(String name, String content) throws IOException {
        return Files.write(tempDir.resolve(name), gzip(content));
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
    void gzOpensAsAnInMemorySessionThatNeverClaimsAnAppend() throws IOException {
        Path gz = gzFile("tail.csv.gz", "Date,flow\n2020-01-01,1.5\n");
        try (DataViewSession session = DataViewOpener.openFor(gz.toFile())) {
            await("indexing complete", session::isIndexingComplete);
            assertFalse(session.tryResumeAppend(), "no growth to resume: caller must rebuild");
        }
    }

    @Test
    void overLimitGzIsRefusedWithTheHonestReason() throws IOException {
        Path gz = gzFile("big.csv.gz", "Date,flow\n2020-01-01,1.5\n".repeat(64));
        int old = PreferenceKeys.DATAVIEW_ZIP_MEMORY_LIMIT_MB.get();
        try {
            PreferenceKeys.DATAVIEW_ZIP_MEMORY_LIMIT_MB.set(0);
            CompressedCsvFormat.TooLargeException e = assertThrows(CompressedCsvFormat.TooLargeException.class,
                () -> DataViewOpener.openFor(gz.toFile()));
            assertTrue(e.getMessage().contains("in-memory limit"), e.getMessage());
        } finally {
            PreferenceKeys.DATAVIEW_ZIP_MEMORY_LIMIT_MB.set(old);
        }
    }

    @Test
    void decompressBoundedRoundTripsAndRefusesHonestly() throws IOException {
        String content = "Date,q\n2020-01-01,1.0\n";
        Path gz = gzFile("small.csv.gz", content);
        byte[] bytes = CompressedCsvFormat.decompressBounded(gz.toFile(), 1024 * 1024);
        assertEquals(content, new String(bytes, StandardCharsets.UTF_8));

        assertThrows(CompressedCsvFormat.TooLargeException.class,
            () -> CompressedCsvFormat.decompressBounded(gz.toFile(), 4),
            "the bound is enforced during decompression");
    }

    @Test
    void concatenatedMembersReadAsOneStream() throws IOException {
        // `cat a.gz b.gz` is valid gzip; gzip, pandas and the engine read it whole.
        Path gz = tempDir.resolve("two.csv.gz");
        byte[] first = gzip("Date,q\n2020-01-01,1.0\n");
        byte[] second = gzip("2020-01-02,2.0\n");
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        Files.write(gz, both);
        assertEquals("Date,q\n2020-01-01,1.0\n2020-01-02,2.0\n",
            new String(CompressedCsvFormat.decompressBounded(gz.toFile(), 1024), StandardCharsets.UTF_8));
    }

    @Test
    void writerIsGzipAndReproducible() throws IOException {
        Path a = tempDir.resolve("a.csv.gz");
        Path b = tempDir.resolve("b.csv.gz");
        for (Path out : new Path[] {a, b}) {
            try (Writer writer = CompressedCsvFormat.newUtf8Writer(out.toFile())) {
                writer.write("Date,q\n2020-01-01,3.0\n");
            }
        }
        byte[] bytes = Files.readAllBytes(a);
        assertEquals((byte) 0x1f, bytes[0], "gzip magic");
        assertEquals((byte) 0x8b, bytes[1], "gzip magic");
        assertArrayEquals(bytes, Files.readAllBytes(b),
            "no MTIME or file name in the header: identical content, identical bytes");
        assertEquals("Date,q\n2020-01-01,3.0\n",
            new String(CompressedCsvFormat.decompressBounded(a.toFile(), 1024), StandardCharsets.UTF_8));
    }

    @Test
    void aStreamThatIsNotGzipIsRefusedAndClosed() {
        // Closing is checked directly: on Windows an NIO stream allows delete
        // while open, so a delete-after test would pass even with a leak.
        boolean[] closed = {false};
        InputStream raw = new ByteArrayInputStream("Date,q\n2020-01-01,1.0\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        assertThrows(IOException.class, () -> CompressedCsvFormat.wrapDecompressing("bogus.csv.gz", raw));
        assertTrue(closed[0], "the raw stream is closed when it is not gzip");
    }

    @Test
    void anEmptyOrCutShortGzSaysWhy() throws IOException {
        // GZIPInputStream's own EOFException has no message; a dialog showing "null" says nothing.
        Path empty = Files.write(tempDir.resolve("empty.csv.gz"), new byte[0]);
        IOException e = assertThrows(IOException.class,
            () -> CompressedCsvFormat.decompressBounded(empty.toFile(), 1024));
        assertNotNull(e.getMessage(), "empty file");

        byte[] whole = gzip("Date,q\n2020-01-01,1.0\n");
        Path cut = Files.write(tempDir.resolve("cut.csv.gz"), Arrays.copyOf(whole, whole.length - 4));
        e = assertThrows(IOException.class, () -> CompressedCsvFormat.decompressBounded(cut.toFile(), 1024));
        assertNotNull(e.getMessage(), "trailer cut short");
    }

    @Test
    void aPlainCsvIsReadAsItIs() throws IOException {
        Path csv = Files.writeString(tempDir.resolve("plain.csv"), "Date,q\n2020-01-01,1.0\n");
        assertEquals("Date,q\n2020-01-01,1.0\n",
            new String(CompressedCsvFormat.decompressBounded(csv.toFile(), 1024), StandardCharsets.UTF_8));
    }

    @Test
    void extensionTestCoversBothFormats() {
        assertEquals(".csv.gz", CompressedCsvFormat.extensionOf("FLOWS.CSV.GZ"));
        assertEquals(".csv.zip", CompressedCsvFormat.extensionOf("flows.csv.zip"));
        assertTrue(CompressedCsvFormat.isCompressedCsv("results.res.csv.gz"));
        assertFalse(CompressedCsvFormat.isCompressedCsv("flows.csv"));
        assertFalse(CompressedCsvFormat.isCompressedCsv("archive.tar.gz"));
        assertFalse(CompressedCsvFormat.isCompressedCsv(null));
        assertNull(CompressedCsvFormat.extensionOf("notes.gz"));
        assertEquals("rain", CompressedCsvFormat.stripExtension("rain.csv.gz"));
        assertEquals("Rain", CompressedCsvFormat.stripExtension("Rain.CSV.ZIP"));
        assertEquals("rain.csv", CompressedCsvFormat.stripExtension("rain.csv"));
        assertTrue(CompressedCsvFormat.isCsvOrCompressedCsv("Rain.CSV"));
        assertTrue(CompressedCsvFormat.isCsvOrCompressedCsv("rain.csv.gz"));
        assertFalse(CompressedCsvFormat.isCsvOrCompressedCsv("rain.gz"));
        assertFalse(CompressedCsvFormat.isCsvOrCompressedCsv(null));
    }
}
