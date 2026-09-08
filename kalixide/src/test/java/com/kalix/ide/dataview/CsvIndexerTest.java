package com.kalix.ide.dataview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvIndexerTest {

    private static final CsvDialect DIALECT =
        new CsvDialect(',', '"', StandardCharsets.UTF_8, 0, false, "LF");

    private static SeekableByteChannel channelOf(String content) throws IOException {
        Path file = Files.createTempFile("kalix-indexer-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return Files.newByteChannel(file, StandardOpenOption.READ);
    }

    private record Indexed(CheckpointIndex rows, CheckpointIndex lines) {
    }

    private static Indexed index(String content, int stride) throws IOException {
        CheckpointIndex rows = new CheckpointIndex(stride);
        CheckpointIndex lines = new CheckpointIndex(stride);
        try (SeekableByteChannel ch = channelOf(content)) {
            CsvIndexer.index(ch, 0, DIALECT, rows, lines, null, null);
        }
        return new Indexed(rows, lines);
    }

    @Test
    void countsRowsAndLines() throws IOException {
        Indexed ix = index("h1,h2\n1,2\n3,4\n", 16);
        assertEquals(3, ix.rows().itemCount());
        assertEquals(3, ix.lines().itemCount());
        assertTrue(ix.rows().isComplete());
        assertTrue(ix.lines().isComplete());
    }

    @Test
    void quotedNewlinesMakeFewerRowsThanLines() throws IOException {
        Indexed ix = index("a,\"x\ny\"\nb,c\n", 16);
        assertEquals(2, ix.rows().itemCount(), "the quoted newline does not end a row");
        assertEquals(3, ix.lines().itemCount(), "the text view still sees every physical line");
    }

    @Test
    void finalRowWithoutTrailingNewlineCounts() throws IOException {
        Indexed ix = index("a,b\nc,d", 16);
        assertEquals(2, ix.rows().itemCount());
        assertEquals(2, ix.lines().itemCount());
        assertTrue(ix.rows().isComplete());
    }

    @Test
    void emptyFileIndexesToZeroAndCompletes() throws IOException {
        Indexed ix = index("", 16);
        assertEquals(0, ix.rows().itemCount());
        assertTrue(ix.rows().isComplete());
    }

    @Test
    void cancellationAbortsWithoutCompleting() throws IOException {
        CheckpointIndex rows = new CheckpointIndex(16);
        CheckpointIndex lines = new CheckpointIndex(16);
        try (SeekableByteChannel ch = channelOf("a,b\nc,d\n")) {
            CsvIndexer.index(ch, 0, DIALECT, rows, lines, null, () -> true);
        }
        assertFalse(rows.isComplete(), "a cancelled index never claims completeness");
        assertEquals(0, rows.itemCount());
    }

    @Test
    void checkpointsLandOnRowBoundariesTheParserCanUse() throws IOException {
        // Rows "r0\n".."r5\n" are 3 bytes each; with stride 2, checkpoint for row 4
        // must be byte offset 12, and parsing from it must yield r4.
        Indexed ix = index("r0\nr1\nr2\nr3\nr4\nr5\n", 2);
        CheckpointIndex.Checkpoint cp = ix.rows().floorCheckpoint(4);
        assertEquals(4, cp.firstItem());
        assertEquals(12, cp.byteOffset());

        try (SeekableByteChannel ch = channelOf("r0\nr1\nr2\nr3\nr4\nr5\n")) {
            List<String[]> parsed = RowBlockParser.parse(ch, cp.byteOffset(), DIALECT, 2);
            assertEquals("r4", parsed.get(0)[0]);
            assertEquals("r5", parsed.get(1)[0]);
        }
    }

    @Test
    void noPhantomCheckpointBeyondAFinalNewline() throws IOException {
        // Two rows of "x\n" (2 bytes each), stride 1: exactly two checkpoints
        // (offsets 0 and 2), none for a nonexistent third row after the trailing
        // newline.
        Indexed ix = index("a\nb\n", 1);
        assertEquals(2, ix.rows().itemCount());
        assertEquals(1, ix.rows().floorCheckpoint(1).firstItem());
        assertEquals(2, ix.rows().floorCheckpoint(1).byteOffset());
        // floor beyond the end clamps to the last real checkpoint
        assertEquals(1, ix.rows().floorCheckpoint(99).firstItem());
    }
}
