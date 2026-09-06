package com.kalix.ide.dataview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.function.BooleanSupplier;

/**
 * The single sequential pass over a data file: one streamed read — the only
 * access pattern network shares are good at — that builds both checkpoint
 * indexes at once:
 *
 * <ul>
 * <li><b>rows</b> — logical CSV records, quote-aware: a newline inside a quoted
 *     field does not end a row (RFC 4180 multi-line cells). This index drives
 *     the table view.</li>
 * <li><b>lines</b> — physical lines, every newline counts. This index drives
 *     the raw text view, which shows what is really there.</li>
 * </ul>
 *
 * <p>Quote handling is bare parity: every quote byte toggles the in-quotes
 * state. An escaped quote ({@code ""}) toggles twice and is therefore neutral,
 * and {@link RowBlockParser} uses the same convention, so the two can never
 * disagree about where a row starts. A stray unbalanced quote makes the rest of
 * the file one long row — honest RFC behaviour; the physical-line index (and the
 * text view) are unaffected, which is exactly why both exist.
 *
 * <p>Runs on a caller-provided (background) thread; progress lands in the
 * indexes and the callback as it happens, and cancellation is checked every
 * chunk so closing a tab aborts within one read. A cancelled index simply
 * never becomes {@linkplain CheckpointIndex#isComplete() complete}.
 */
public final class CsvIndexer {

    private static final int CHUNK_BYTES = 256 * 1024;

    /** Progress callback, invoked once per chunk read (never on the EDT — caller's thread). */
    @FunctionalInterface
    public interface Progress {
        void onProgress(long rows, long lines, long indexedBytes, long totalBytes);
    }

    private CsvIndexer() {
    }

    /**
     * Indexes the file from {@code startOffset} (after any BOM, or after an
     * extended header for formats like {@code .res.csv}) to the end.
     *
     * @param cancelled polled once per chunk; return {@code true} to abort
     */
    public static void index(SeekableByteChannel channel, long startOffset, CsvDialect dialect,
                             CheckpointIndex rows, CheckpointIndex lines,
                             Progress progress, BooleanSupplier cancelled) throws IOException {
        long totalBytes = channel.size();
        rows.setTotalBytes(totalBytes);
        lines.setTotalBytes(totalBytes);
        channel.position(startOffset);

        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_BYTES);
        byte quote = (byte) dialect.quote();

        boolean inQuotes = false;
        boolean rowOpen = false;   // bytes seen since the last row boundary
        boolean lineOpen = false;
        long rowCount = 0;
        long lineCount = 0;
        long position = startOffset;

        while (true) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return; // indexes keep their partial counts and never complete
            }
            buffer.clear();
            int n = channel.read(buffer);
            if (n < 0) {
                break;
            }
            buffer.flip();
            for (int i = 0; i < n; i++) {
                byte b = buffer.get(i);
                if (!rowOpen) {
                    // A row starts only when its first byte exists, so a file ending
                    // in a newline never records a checkpoint for a phantom row.
                    if (rowCount % rows.stride() == 0) {
                        rows.addCheckpoint(position + i);
                    }
                    rowOpen = true;
                }
                if (!lineOpen) {
                    if (lineCount % lines.stride() == 0) {
                        lines.addCheckpoint(position + i);
                    }
                    lineOpen = true;
                }
                if (b == quote) {
                    inQuotes = !inQuotes;
                } else if (b == '\n') {
                    lineCount++;
                    lineOpen = false;
                    if (!inQuotes) {
                        rowCount++;
                        rowOpen = false;
                    }
                }
            }
            position += n;
            rows.onProgress(rowCount, position);
            lines.onProgress(lineCount, position);
            if (progress != null) {
                progress.onProgress(rowCount, lineCount, position, totalBytes);
            }
        }

        // A final line without a trailing newline is still a row/line.
        if (rowOpen) {
            rowCount++;
        }
        if (lineOpen) {
            lineCount++;
        }
        rows.markComplete(rowCount, position);
        lines.markComplete(lineCount, position);
        if (progress != null) {
            progress.onProgress(rowCount, lineCount, position, totalBytes);
        }
    }
}
