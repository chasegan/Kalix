package com.kalix.ide.dataview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One open data file: the orchestrator that ties the {@code dataview} engine to
 * Swing without ever letting I/O near the EDT. Owns the sniffed dialect, both
 * checkpoint indexes, the row and line stores, a background indexer thread and
 * a single background fetch thread; publishes everything to the UI through
 * EDT-delivered {@link Listener} events.
 *
 * <p>The contract the views build on:
 * <ul>
 * <li>{@code *IfLoaded} reads are lock-free and EDT-safe — a view renders what
 *     is there and calls {@link #requestRow(long)} / {@link #requestLine(long)}
 *     for what is not; the block arrives via {@code onRowBlockLoaded} /
 *     {@code onLineBlockLoaded} and the view repaints. Requests are deduplicated
 *     per block and each fetch prefetches its neighbours, so continuous
 *     scrolling stays ahead of the viewport.</li>
 * <li>Progress events are throttled (~10/sec) so a fast local index does not
 *     flood the EDT; the final event carries {@code complete}.</li>
 * <li>{@code onStructureKnown} fires once, when the header/first row has been
 *     read and {@link #columnCount()} / {@link #headerRow()} become real.</li>
 * <li>{@link #close()} aborts the indexer within one chunk, stops the fetch
 *     thread, and closes every channel. Late I/O failures after close are
 *     expected and quietly ignored.</li>
 * </ul>
 *
 * <p>{@link #open(Path)} does blocking I/O (the head read for sniffing) — call
 * it off the EDT.
 */
public final class DataViewSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DataViewSession.class);

    /** Checkpoint stride == block size for both rows and lines. */
    public static final int STRIDE = 1024;
    /** Cache bound per store; at the default stride ≈ 64K rows / lines retained. */
    public static final int MAX_CACHED_BLOCKS = 64;
    private static final int HEAD_BYTES = 64 * 1024;
    private static final long PROGRESS_THROTTLE_NANOS = 100_000_000L; // 100ms

    /** All methods are delivered on the EDT. Implement what you need. */
    public interface Listener {
        default void onProgress(long rows, long lines, long indexedBytes, long totalBytes, boolean complete) {
        }

        default void onStructureKnown() {
        }

        default void onRowBlockLoaded(long firstRow, int count) {
        }

        default void onLineBlockLoaded(long firstLine, int count) {
        }
    }

    private final Path file;
    private final CsvDialect dialect;
    /** Where the tabular data begins (past any BOM or extended format header). */
    private final long indexStartOffset;
    /** Externally supplied column names (.res.csv), or {@code null} to use the data's own header. */
    private final String[] presetColumnNames;
    private final CheckpointIndex rowIndex;
    private final CheckpointIndex lineIndex;
    private final RowStore rowStore;
    private final LineStore lineStore;
    private final SeekableByteChannel indexerChannel;
    private final Thread indexerThread;
    private final ExecutorService fetchExecutor;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<Long> pendingRowBlocks = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingLineBlocks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean structureRequested = new AtomicBoolean(false);

    private volatile boolean closed = false;
    private volatile boolean indexingComplete = false;
    private volatile String[] headerRow; // set once by the structure fetch
    private volatile int columnCount;
    private long lastProgressNotifyNanos; // indexer thread only

    private DataViewSession(Path file, CsvDialect dialect, long indexStartOffset,
                            String[] presetColumnNames,
                            CheckpointIndex rowIndex, CheckpointIndex lineIndex,
                            RowStore rowStore, LineStore lineStore,
                            SeekableByteChannel indexerChannel) {
        this.file = file;
        this.dialect = dialect;
        this.indexStartOffset = indexStartOffset;
        this.presetColumnNames = presetColumnNames;
        if (presetColumnNames != null) {
            this.columnCount = presetColumnNames.length;
        }
        this.rowIndex = rowIndex;
        this.lineIndex = lineIndex;
        this.rowStore = rowStore;
        this.lineStore = lineStore;
        this.indexerChannel = indexerChannel;
        this.fetchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kalix-dataview-fetch");
            t.setDaemon(true);
            return t;
        });
        this.indexerThread = new Thread(this::runIndexer, "kalix-dataview-indexer");
        this.indexerThread.setDaemon(true);
    }

    /**
     * Sniffs the dialect from the head of the file, opens the channels and
     * starts the background index pass. Blocking I/O — call off the EDT.
     */
    public static DataViewSession open(Path file) throws IOException {
        return open(file, 0L, null);
    }

    /**
     * Variant for formats whose tabular data starts mid-file with externally
     * known column names (e.g. {@code .res.csv}: the extended header supplies
     * the names, and {@code dataStartOffset} points just past its EOH marker).
     * The dialect is sniffed from the data region; preset column names force
     * "no header row in the data".
     */
    public static DataViewSession open(Path file, long dataStartOffset,
                                       String[] presetColumnNames) throws IOException {
        byte[] head;
        try (SeekableByteChannel headChannel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            headChannel.position(dataStartOffset);
            ByteBuffer buffer = ByteBuffer.allocate(HEAD_BYTES);
            int n = headChannel.read(buffer);
            head = new byte[Math.max(0, n)];
            buffer.flip();
            buffer.get(head);
        }
        CsvDialect dialect = DialectSniffer.sniff(head);
        if (presetColumnNames != null && dialect.hasHeaderRow()) {
            dialect = new CsvDialect(dialect.delimiter(), dialect.quote(), dialect.charset(),
                dialect.bomLength(), false, dialect.lineEndingLabel());
        }

        CheckpointIndex rowIndex = new CheckpointIndex(STRIDE);
        CheckpointIndex lineIndex = new CheckpointIndex(STRIDE);
        RowStore rowStore = new RowStore(
            Files.newByteChannel(file, StandardOpenOption.READ), dialect, rowIndex, MAX_CACHED_BLOCKS);
        LineStore lineStore = new LineStore(
            Files.newByteChannel(file, StandardOpenOption.READ), dialect, lineIndex, MAX_CACHED_BLOCKS);
        SeekableByteChannel indexerChannel = Files.newByteChannel(file, StandardOpenOption.READ);

        DataViewSession session = new DataViewSession(
            file, dialect, dataStartOffset + dialect.bomLength(), presetColumnNames,
            rowIndex, lineIndex, rowStore, lineStore, indexerChannel);
        session.indexerThread.start();
        return session;
    }

    // --- State reads (all EDT-safe) ---

    public Path file() {
        return file;
    }

    public CsvDialect dialect() {
        return dialect;
    }

    /** Raw row count indexed so far — includes the header row when present. */
    public long rowCount() {
        return rowIndex.itemCount();
    }

    public long lineCount() {
        return lineIndex.itemCount();
    }

    public boolean isIndexingComplete() {
        return indexingComplete;
    }

    public long indexedBytes() {
        return rowIndex.indexedBytes();
    }

    public long totalBytes() {
        return rowIndex.totalBytes();
    }

    /** Header fields, or {@code null} until structure is known / when there is no header. */
    public String[] headerRow() {
        return dialect.hasHeaderRow() ? headerRow : null;
    }

    /** Field count of the header (or first row); 0 until structure is known. */
    public int columnCount() {
        return columnCount;
    }

    /**
     * Column names for the table: preset names (formats with an external
     * header, e.g. {@code .res.csv}), else the in-data header row, else
     * {@code null} (generic names).
     */
    public String[] columnNames() {
        if (presetColumnNames != null) {
            return presetColumnNames;
        }
        return dialect.hasHeaderRow() ? headerRow : null;
    }

    /** Whether row 0 of the data region is a header row (hidden by the table). */
    public boolean headerRowInData() {
        return dialect.hasHeaderRow();
    }

    public String[] rowIfLoaded(long fileRow) {
        return rowStore.rowIfLoaded(fileRow);
    }

    public String lineIfLoaded(long line) {
        return lineStore.lineIfLoaded(line);
    }

    // --- Async requests (EDT-safe, non-blocking) ---

    /** Schedules the row's block (and its neighbours) for background load. */
    public void requestRow(long fileRow) {
        if (fileRow < 0 || fileRow >= rowIndex.itemCount()) {
            return;
        }
        scheduleRowBlock(rowStore.blockOf(fileRow), true);
    }

    /** Schedules the line's block (and its neighbours) for background load. */
    public void requestLine(long line) {
        if (line < 0 || line >= lineIndex.itemCount()) {
            return;
        }
        scheduleLineBlock(lineStore.blockOf(line), true);
    }

    /**
     * Reads a range of physical lines synchronously (for clipboard copy).
     * Blocking I/O — background threads only; the caller bounds the range.
     */
    public List<String> linesBlocking(long from, long toExclusive) {
        List<String> lines = new ArrayList<>();
        long end = Math.min(toExclusive, lineIndex.itemCount());
        for (long i = Math.max(0, from); i < end; i++) {
            String line = lineStore.line(i);
            lines.add(line != null ? line : "");
        }
        return lines;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void close() {
        closed = true;
        fetchExecutor.shutdownNow();
        closeQuietly(rowStore::close);
        closeQuietly(lineStore::close);
        closeQuietly(indexerChannel::close);
        listeners.clear();
    }

    // --- Internals ---

    private interface Closer {
        void close() throws IOException;
    }

    private static void closeQuietly(Closer closer) {
        try {
            closer.close();
        } catch (IOException ignored) {
            // Tearing down; nothing useful to do.
        }
    }

    private void runIndexer() {
        try {
            CsvIndexer.index(indexerChannel, indexStartOffset, dialect,
                rowIndex, lineIndex, this::onIndexProgress, () -> closed);
            if (!closed) {
                indexingComplete = true;
                maybeRequestStructure();
                notifyEdt(l -> l.onProgress(rowIndex.itemCount(), lineIndex.itemCount(),
                    rowIndex.indexedBytes(), rowIndex.totalBytes(), true));
            }
        } catch (IOException e) {
            if (!closed) {
                logger.warn("Indexing failed for {}: {}", file, e.getMessage());
            }
        }
    }

    /** Indexer-thread callback; throttled before touching the EDT. */
    private void onIndexProgress(long rows, long lines, long indexedBytes, long totalBytes) {
        maybeRequestStructure();
        long now = System.nanoTime();
        if (now - lastProgressNotifyNanos < PROGRESS_THROTTLE_NANOS) {
            return;
        }
        lastProgressNotifyNanos = now;
        notifyEdt(l -> l.onProgress(rows, lines, indexedBytes, totalBytes, false));
    }

    /** Loads the header/first row once, as soon as any row exists. */
    private void maybeRequestStructure() {
        if (rowIndex.itemCount() > 0 && structureRequested.compareAndSet(false, true)) {
            submit(() -> {
                rowStore.ensureBlockLoaded(0);
                String[] first = rowStore.rowIfLoaded(0);
                if (first != null) {
                    headerRow = first;
                    columnCount = first.length;
                    notifyEdt(Listener::onStructureKnown);
                    notifyEdt(l -> l.onRowBlockLoaded(0, blockCount(rowIndex, 0)));
                }
            });
        }
    }

    private void scheduleRowBlock(long block, boolean prefetchNeighbours) {
        if (block < 0 || block * STRIDE >= rowIndex.itemCount()
            || rowStore.isRowLoaded(block * STRIDE) || !pendingRowBlocks.add(block)) {
            return;
        }
        submit(() -> {
            try {
                rowStore.ensureBlockLoaded(block);
                notifyEdt(l -> l.onRowBlockLoaded(block * STRIDE, blockCount(rowIndex, block)));
            } finally {
                pendingRowBlocks.remove(block);
            }
            if (prefetchNeighbours) {
                scheduleRowBlock(block + 1, false);
                scheduleRowBlock(block - 1, false);
            }
        });
    }

    private void scheduleLineBlock(long block, boolean prefetchNeighbours) {
        if (block < 0 || block * STRIDE >= lineIndex.itemCount()
            || lineStore.isLineLoaded(block * STRIDE) || !pendingLineBlocks.add(block)) {
            return;
        }
        submit(() -> {
            try {
                lineStore.ensureBlockLoaded(block);
                notifyEdt(l -> l.onLineBlockLoaded(block * STRIDE, blockCount(lineIndex, block)));
            } finally {
                pendingLineBlocks.remove(block);
            }
            if (prefetchNeighbours) {
                scheduleLineBlock(block + 1, false);
                scheduleLineBlock(block - 1, false);
            }
        });
    }

    private static int blockCount(CheckpointIndex index, long block) {
        return (int) Math.min(STRIDE, index.itemCount() - block * STRIDE);
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        try {
            fetchExecutor.submit(() -> {
                if (closed) {
                    return;
                }
                try {
                    task.run();
                } catch (RuntimeException e) {
                    if (!closed) {
                        logger.warn("Data fetch failed for {}: {}", file, e.getMessage());
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closed concurrently; nothing to do.
        }
    }

    private void notifyEdt(Consumer<Listener> event) {
        if (closed) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            for (Listener listener : listeners) {
                event.accept(listener);
            }
        });
    }
}
