package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowStoreTest {

    private static final CsvDialect DIALECT =
        new CsvDialect(',', '"', StandardCharsets.UTF_8, 0, false, "LF");

    private static Path fileWithRows(int count) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("row").append(i).append(",value").append(i).append('\n');
        }
        Path file = Files.createTempFile("kalix-rowstore-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static RowStore store(Path file, int stride, int maxBlocks) throws IOException {
        CheckpointIndex rows = new CheckpointIndex(stride);
        CheckpointIndex lines = new CheckpointIndex(stride);
        try (SeekableByteChannel indexChannel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            CsvIndexer.index(indexChannel, 0, DIALECT, rows, lines, null, null);
        }
        return new RowStore(Files.newByteChannel(file, StandardOpenOption.READ), DIALECT, rows, maxBlocks);
    }

    @Test
    void randomAccessAcrossBlocksSurvivesEviction() throws IOException {
        try (RowStore store = store(fileWithRows(100), 8, 2)) {
            assertEquals(100, store.rowCount());
            // Jump around far more blocks than the cache holds; every read must
            // still be correct after evictions force re-fetches.
            long[] probes = {0, 57, 99, 3, 88, 12, 57, 0};
            for (long probe : probes) {
                String[] row = store.row(probe);
                assertEquals("row" + probe, row[0]);
                assertEquals("value" + probe, row[1]);
            }
        }
    }

    @Test
    void outOfRangeRowsAreNull() throws IOException {
        try (RowStore store = store(fileWithRows(10), 4, 2)) {
            assertNull(store.row(-1));
            assertNull(store.row(10));
        }
    }

    @Test
    void lockFreeReadsSeeOnlyLoadedBlocks() throws IOException {
        try (RowStore store = store(fileWithRows(20), 4, 2)) {
            assertFalse(store.isRowLoaded(0));
            assertNull(store.rowIfLoaded(0), "rowIfLoaded never triggers a load");

            store.ensureBlockLoaded(0);
            assertTrue(store.isRowLoaded(3), "same block as row 0");
            assertEquals("row3", store.rowIfLoaded(3)[0]);
            assertFalse(store.isRowLoaded(19), "different block, untouched");
        }
    }
}
