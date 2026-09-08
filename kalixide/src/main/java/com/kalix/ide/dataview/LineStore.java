package com.kalix.ide.dataview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link RowStore} counterpart for <em>physical lines</em>: random access
 * to the raw text of the file for the ground-truth text view. A line is exactly
 * what sits between newlines on disk — quoting is not interpreted here at all
 * (that is the whole point of the raw view; the quote-aware row projection is
 * {@link RowStore}'s job). Trailing {@code \r} is stripped for display.
 *
 * <p>Same concurrency contract as {@code RowStore}: {@link #lineIfLoaded(long)}
 * / {@link #isLineLoaded(long)} are lock-free EDT-safe reads,
 * {@link #ensureBlockLoaded(long)} does the I/O on a background thread, and
 * eviction is insertion-order beyond the cap.
 */
public final class LineStore implements AutoCloseable {

    private static final int CHUNK_BYTES = 64 * 1024;

    private final SeekableByteChannel channel;
    private final CsvDialect dialect;
    private final CheckpointIndex lineIndex;
    private final int maxCachedBlocks;

    private final Map<Long, List<String>> blocks = new ConcurrentHashMap<>();
    private final Deque<Long> insertionOrder = new ArrayDeque<>();

    public LineStore(SeekableByteChannel channel, CsvDialect dialect,
                     CheckpointIndex lineIndex, int maxCachedBlocks) {
        this.channel = channel;
        this.dialect = dialect;
        this.lineIndex = lineIndex;
        this.maxCachedBlocks = maxCachedBlocks;
    }

    /** Lines indexed so far; grows while the indexer runs. */
    public long lineCount() {
        return lineIndex.itemCount();
    }

    public long blockOf(long lineNumber) {
        return lineNumber / lineIndex.stride();
    }

    /** Whether the given line's block is already parsed. Lock-free; EDT-safe. */
    public boolean isLineLoaded(long lineNumber) {
        return blocks.containsKey(blockOf(lineNumber));
    }

    /** The line's text if cached, else {@code null}. Lock-free; never does I/O. */
    public String lineIfLoaded(long lineNumber) {
        if (lineNumber < 0 || lineNumber >= lineIndex.itemCount()) {
            return null;
        }
        List<String> lines = blocks.get(blockOf(lineNumber));
        if (lines == null) {
            return null;
        }
        int within = (int) (lineNumber - blockOf(lineNumber) * lineIndex.stride());
        return within < lines.size() ? lines.get(within) : null;
    }

    /**
     * Reads and caches one block of lines (no-op if cached). Blocking I/O —
     * background threads only.
     */
    public synchronized void ensureBlockLoaded(long block) {
        if (blocks.containsKey(block)) {
            return;
        }
        long firstLine = block * lineIndex.stride();
        CheckpointIndex.Checkpoint checkpoint = lineIndex.floorCheckpoint(firstLine);
        if (checkpoint == null) {
            return;
        }
        int skip = (int) (firstLine - checkpoint.firstItem());
        List<String> parsed;
        try {
            parsed = readLines(checkpoint.byteOffset(), skip + lineIndex.stride());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading text block at line " + firstLine, e);
        }
        List<String> lines = skip == 0
            ? parsed
            : parsed.subList(Math.min(skip, parsed.size()), parsed.size());
        blocks.put(block, lines);
        insertionOrder.addLast(block);
        while (insertionOrder.size() > maxCachedBlocks) {
            blocks.remove(insertionOrder.removeFirst());
        }
    }

    /** Drops one cached block — see {@link RowStore#evictBlock(long)}. */
    synchronized void evictBlock(long block) {
        if (blocks.remove(block) != null) {
            insertionOrder.remove(block);
        }
    }

    /**
     * Synchronous convenience for tests and bulk copy. Synchronized so ensure+read
     * happens under the load monitor: a concurrent load cannot evict this block
     * between the two (a racing scroll during a large clipboard copy).
     */
    public synchronized String line(long lineNumber) {
        if (lineNumber < 0 || lineNumber >= lineIndex.itemCount()) {
            return null;
        }
        ensureBlockLoaded(blockOf(lineNumber));
        return lineIfLoaded(lineNumber);
    }

    private List<String> readLines(long offset, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>();
        channel.position(offset);
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_BYTES);
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        boolean lineHasContent = false;

        long bytesConsumed = 0;
        outer:
        while (true) {
            if (bytesConsumed > RowBlockParser.MAX_BLOCK_BYTES) {
                break; // a file with no newlines must not accumulate into memory
            }
            buffer.clear();
            int n = channel.read(buffer);
            if (n < 0) {
                break;
            }
            bytesConsumed += n;
            buffer.flip();
            for (int i = 0; i < n; i++) {
                byte b = buffer.get(i);
                lineHasContent = true;
                if (b == '\n') {
                    lines.add(decode(line));
                    lineHasContent = false;
                    if (lines.size() >= maxLines) {
                        break outer;
                    }
                } else {
                    line.write(b);
                }
            }
        }
        if (lineHasContent && lines.size() < maxLines) {
            lines.add(decode(line)); // final line without trailing newline
        }
        return lines;
    }

    private String decode(ByteArrayOutputStream line) {
        String text = line.toString(dialect.charset());
        line.reset();
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
