package com.kalix.ide.dataview;

import java.util.Arrays;

/**
 * A sparse index over one kind of item in a file — logical rows or physical
 * lines — recording the byte offset of every {@code stride}-th item plus live
 * progress counters. This is the whole per-file bookkeeping of the data viewer:
 * for a 1GB / 20M-row file at the default stride it is a few hundred KB, and it
 * turns "show row 13,401,207" into one seek plus a sub-block parse.
 *
 * <p>Written by the background {@link CsvIndexer} thread while being read by the
 * UI thread (row counts drive the growing scrollbar), so all state is behind the
 * monitor. Reads are coarse and cheap; contention is negligible next to I/O.
 *
 * <p>The index is usable while incomplete: {@link #itemCount()} only ever grows,
 * and {@link #floorCheckpoint(long)} clamps to what has been indexed so far.
 */
public final class CheckpointIndex {

    /** One checkpoint: item number {@code firstItem} starts at {@code byteOffset}. */
    public record Checkpoint(long firstItem, long byteOffset) {
    }

    private final int stride;

    private long[] offsets = new long[64];
    private int checkpointCount = 0;
    private long itemCount = 0;
    private long indexedBytes = 0;
    private long totalBytes = 0;
    private boolean complete = false;

    /**
     * @param stride record a checkpoint every this-many items. This is also the
     *               natural block size for readers ({@link RowStore} uses it), so
     *               a block load is exactly one checkpointed seek.
     */
    public CheckpointIndex(int stride) {
        if (stride <= 0) {
            throw new IllegalArgumentException("stride must be positive: " + stride);
        }
        this.stride = stride;
    }

    public int stride() {
        return stride;
    }

    /** Records that item number {@code checkpointCount * stride} starts at this offset. */
    synchronized void addCheckpoint(long byteOffset) {
        if (checkpointCount == offsets.length) {
            offsets = Arrays.copyOf(offsets, offsets.length * 2);
        }
        offsets[checkpointCount++] = byteOffset;
    }

    synchronized void onProgress(long items, long bytes) {
        this.itemCount = items;
        this.indexedBytes = bytes;
    }

    synchronized void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    synchronized void markComplete(long items, long bytes) {
        this.itemCount = items;
        this.indexedBytes = bytes;
        this.complete = true;
    }

    /**
     * Reopens a completed index for append-resume: counts and checkpoints stay
     * valid (the prefix is unchanged), only completeness clears while the
     * indexer continues from the old end.
     */
    synchronized void reopen() {
        this.complete = false;
    }

    /** Items indexed so far — the final count once {@link #isComplete()}. */
    public synchronized long itemCount() {
        return itemCount;
    }

    /** Whether the indexing pass reached the end of the file (vs. still running or cancelled). */
    public synchronized boolean isComplete() {
        return complete;
    }

    /** Absolute byte position the indexer has reached, for progress display. */
    public synchronized long indexedBytes() {
        return indexedBytes;
    }

    /** File size captured when indexing started, for progress display. */
    public synchronized long totalBytes() {
        return totalBytes;
    }

    /**
     * The nearest checkpoint at or before the given item, clamped to the indexed
     * region; a reader seeks there and parses forward. Returns {@code null} only
     * when nothing has been indexed yet.
     */
    public synchronized Checkpoint floorCheckpoint(long item) {
        if (checkpointCount == 0) {
            return null;
        }
        int idx = (int) Math.min(item / stride, checkpointCount - 1L);
        return new Checkpoint((long) idx * stride, offsets[idx]);
    }

    /**
     * The last checkpoint at or before the given byte offset (offsets ascend),
     * or {@code null} if none. Lets the row and line indexes be cross-referenced
     * through the byte positions they share ("Show in file").
     */
    public synchronized Checkpoint floorCheckpointByOffset(long byteOffset) {
        int lo = 0;
        int hi = checkpointCount - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (offsets[mid] <= byteOffset) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best < 0 ? null : new Checkpoint((long) best * stride, offsets[best]);
    }
}
