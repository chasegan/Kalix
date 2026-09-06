package com.kalix.ide.dataview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Random access to the rows of a delimited file that never lives in memory:
 * a bounded cache of parsed row blocks over a {@link CheckpointIndex}. A block
 * is exactly one index stride, so any row is one checkpointed seek plus a
 * sub-block parse away, and steady-state heap is the cache bound — the same
 * few tens of MB whether the file is 10KB or 1GB.
 *
 * <p>Split for EDT safety: {@link #rowIfLoaded(long)} and
 * {@link #isRowLoaded(long)} are lock-free reads of completed blocks and are
 * what the UI calls; {@link #ensureBlockLoaded(long)} does the I/O and belongs
 * on a background thread ({@link DataViewSession}'s fetch executor). The
 * synchronous {@link #row(long)} convenience combines the two for tests and
 * background use. Eviction is insertion-order (FIFO) beyond the block cap —
 * approximate LRU is deliberately traded for lock-free reads; prefetch hides
 * the occasional re-fetch when scrolling back.
 *
 * <p>Usable while indexing is still running: rows beyond the indexed region
 * simply do not exist yet ({@code null}).
 */
public final class RowStore implements AutoCloseable {

    private final SeekableByteChannel channel;
    private final CsvDialect dialect;
    private final CheckpointIndex rowIndex;
    private final int maxCachedBlocks;

    /** Completed blocks; read lock-free from any thread. */
    private final Map<Long, List<String[]>> blocks = new ConcurrentHashMap<>();
    /** Insertion order for eviction; touched only under the load monitor. */
    private final Deque<Long> insertionOrder = new ArrayDeque<>();

    /**
     * @param maxCachedBlocks bound on retained parsed blocks (insertion-order
     *                        eviction); block size is the index stride
     */
    public RowStore(SeekableByteChannel channel, CsvDialect dialect,
                    CheckpointIndex rowIndex, int maxCachedBlocks) {
        this.channel = channel;
        this.dialect = dialect;
        this.rowIndex = rowIndex;
        this.maxCachedBlocks = maxCachedBlocks;
    }

    /** Rows indexed so far; grows while the indexer runs. */
    public long rowCount() {
        return rowIndex.itemCount();
    }

    /** The block a row belongs to. */
    public long blockOf(long rowNumber) {
        return rowNumber / rowIndex.stride();
    }

    /** Whether the given row's block is already parsed. Lock-free; EDT-safe. */
    public boolean isRowLoaded(long rowNumber) {
        return blocks.containsKey(blockOf(rowNumber));
    }

    /**
     * The fields of the given row if its block is cached, else {@code null}
     * (out of range, not indexed yet, or simply not loaded). Lock-free; EDT-safe
     * — never triggers I/O.
     */
    public String[] rowIfLoaded(long rowNumber) {
        if (rowNumber < 0 || rowNumber >= rowIndex.itemCount()) {
            return null;
        }
        List<String[]> rows = blocks.get(blockOf(rowNumber));
        if (rows == null) {
            return null;
        }
        int within = (int) (rowNumber - blockOf(rowNumber) * rowIndex.stride());
        return within < rows.size() ? rows.get(within) : null;
    }

    /**
     * Parses and caches one block (a no-op if already cached). Does blocking
     * I/O — background threads only, never the EDT. Loads serialize on this
     * store's monitor.
     *
     * @throws UncheckedIOException if the file cannot be read (vanished,
     *                              truncated, share dropped)
     */
    public synchronized void ensureBlockLoaded(long block) {
        if (blocks.containsKey(block)) {
            return;
        }
        long firstRow = block * rowIndex.stride();
        CheckpointIndex.Checkpoint checkpoint = rowIndex.floorCheckpoint(firstRow);
        if (checkpoint == null) {
            return; // nothing indexed yet
        }
        // Normally the checkpoint IS the block start (stride == block size); the
        // skip handles a floor into an earlier checkpoint near the indexing frontier.
        int skip = (int) (firstRow - checkpoint.firstItem());
        List<String[]> parsed;
        try {
            parsed = RowBlockParser.parse(
                channel, checkpoint.byteOffset(), dialect, skip + rowIndex.stride());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading data block at row " + firstRow, e);
        }
        List<String[]> rows = skip == 0
            ? parsed
            : parsed.subList(Math.min(skip, parsed.size()), parsed.size());
        blocks.put(block, rows);
        insertionOrder.addLast(block);
        while (insertionOrder.size() > maxCachedBlocks) {
            blocks.remove(insertionOrder.removeFirst());
        }
    }

    /**
     * Synchronous access: loads the block if needed, then reads the row. For
     * tests and background use (bulk copy); the UI path is
     * {@link #rowIfLoaded(long)} + a scheduled {@link #ensureBlockLoaded(long)}.
     */
    public String[] row(long rowNumber) {
        if (rowNumber < 0 || rowNumber >= rowIndex.itemCount()) {
            return null;
        }
        ensureBlockLoaded(blockOf(rowNumber));
        return rowIfLoaded(rowNumber);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
