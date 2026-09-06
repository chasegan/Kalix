package com.kalix.ide.dataview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Random access to the rows of a delimited file that never lives in memory:
 * a bounded LRU cache of parsed row blocks over a {@link CheckpointIndex}. A
 * block is exactly one index stride, so any row is one checkpointed seek plus
 * a sub-block parse away, and steady-state heap is the cache bound — the same
 * few tens of MB whether the file is 10KB or 1GB.
 *
 * <p>Synchronous by design: callers on the EDT must not call {@link #row(long)}
 * for uncached blocks — the async orchestration (background fetch, placeholder
 * cell, repaint on arrival, prefetch) is the view layer's job. This class is
 * the correctness core it builds on, and is safe to call from any single
 * background fetcher at a time (methods are synchronized; the channel is
 * repositioned per block load).
 *
 * <p>Usable while indexing is still running: rows beyond the indexed region
 * simply do not exist yet ({@link #row(long)} returns {@code null}).
 */
public final class RowStore implements AutoCloseable {

    private final SeekableByteChannel channel;
    private final CsvDialect dialect;
    private final CheckpointIndex rowIndex;
    private final Map<Long, List<String[]>> cache;

    /**
     * @param maxCachedBlocks bound on retained parsed blocks (LRU eviction);
     *                        block size is the index stride
     */
    public RowStore(SeekableByteChannel channel, CsvDialect dialect,
                    CheckpointIndex rowIndex, int maxCachedBlocks) {
        this.channel = channel;
        this.dialect = dialect;
        this.rowIndex = rowIndex;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, List<String[]>> eldest) {
                return size() > maxCachedBlocks;
            }
        };
    }

    /** Rows indexed so far; grows while the indexer runs. */
    public long rowCount() {
        return rowIndex.itemCount();
    }

    /** Whether the given row's block is already parsed (a cheap EDT-safe probe). */
    public synchronized boolean isRowLoaded(long rowNumber) {
        return cache.containsKey(rowNumber / rowIndex.stride());
    }

    /**
     * The fields of the given row, or {@code null} if the row is out of range
     * (including "not indexed yet").
     *
     * @throws UncheckedIOException if the file cannot be read (vanished,
     *                              truncated, share dropped)
     */
    public synchronized String[] row(long rowNumber) {
        if (rowNumber < 0 || rowNumber >= rowIndex.itemCount()) {
            return null;
        }
        long block = rowNumber / rowIndex.stride();
        List<String[]> rows = cache.get(block);
        if (rows == null) {
            rows = loadBlock(block);
            cache.put(block, rows);
        }
        int within = (int) (rowNumber - block * rowIndex.stride());
        return within < rows.size() ? rows.get(within) : null;
    }

    private List<String[]> loadBlock(long block) {
        long firstRow = block * rowIndex.stride();
        CheckpointIndex.Checkpoint checkpoint = rowIndex.floorCheckpoint(firstRow);
        if (checkpoint == null) {
            return List.of();
        }
        // Normally the checkpoint IS the block start (stride == block size); the
        // skip handles a floor into an earlier checkpoint near the indexing frontier.
        int skip = (int) (firstRow - checkpoint.firstItem());
        try {
            List<String[]> parsed = RowBlockParser.parse(
                channel, checkpoint.byteOffset(), dialect, skip + rowIndex.stride());
            return skip == 0 ? parsed : parsed.subList(Math.min(skip, parsed.size()), parsed.size());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading data block at row " + firstRow, e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
