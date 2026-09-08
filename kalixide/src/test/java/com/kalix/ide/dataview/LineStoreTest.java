package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LineStoreTest {

    private static final CsvDialect DIALECT =
        new CsvDialect(',', '"', StandardCharsets.UTF_8, 0, false, "LF");

    private static LineStore store(String content, int stride, int maxBlocks) throws IOException {
        Path file = Files.createTempFile("kalix-linestore-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        CheckpointIndex rows = new CheckpointIndex(stride);
        CheckpointIndex lines = new CheckpointIndex(stride);
        try (SeekableByteChannel indexChannel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            CsvIndexer.index(indexChannel, 0, DIALECT, rows, lines, null, null);
        }
        return new LineStore(Files.newByteChannel(file, StandardOpenOption.READ), DIALECT, lines, maxBlocks);
    }

    @Test
    void showsRawPhysicalLinesEvenInsideQuotedFields() throws IOException {
        // The table sees 2 rows; the text view must show all 3 physical lines
        // exactly as they sit on disk — quoting is not interpreted here.
        try (LineStore store = store("a,\"x\ny\"\nb,c\n", 4, 2)) {
            assertEquals(3, store.lineCount());
            assertEquals("a,\"x", store.line(0));
            assertEquals("y\"", store.line(1));
            assertEquals("b,c", store.line(2));
        }
    }

    @Test
    void stripsTrailingCarriageReturnsOnly() throws IOException {
        try (LineStore store = store("a,b\r\nc,d\r\n", 4, 2)) {
            assertEquals("a,b", store.line(0));
            assertEquals("c,d", store.line(1));
        }
    }

    @Test
    void randomAccessAcrossBlocksSurvivesEviction() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("line").append(i).append('\n');
        }
        try (LineStore store = store(sb.toString(), 4, 2)) {
            long[] probes = {0, 33, 49, 5, 20, 33};
            for (long probe : probes) {
                assertEquals("line" + probe, store.line(probe));
            }
        }
    }

    @Test
    void outOfRangeLinesAreNull() throws IOException {
        try (LineStore store = store("a\nb\n", 4, 2)) {
            assertNull(store.line(-1));
            assertNull(store.line(2));
        }
    }
}
