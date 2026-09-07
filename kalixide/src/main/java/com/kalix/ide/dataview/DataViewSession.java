package com.kalix.ide.dataview;

import com.kalix.ide.io.CsvDates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    /** Physical lines occupied by an extended format header before the data region (.res.csv). */
    private final long headerLinesBeforeData;
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

    // --- Append-resume state (see tryResumeAppend) ---
    /** Whether the last index pass ended at a row boundary with quotes closed. */
    private volatile boolean cleanEnd = false;
    /** The last bytes of the indexed region, to verify an append changed nothing behind it. */
    private volatile byte[] tailSample;
    private volatile long tailSampleOffset;
    private final AtomicBoolean resumeRunning = new AtomicBoolean(false);

    private DataViewSession(Path file, CsvDialect dialect, long indexStartOffset,
                            String[] presetColumnNames, long headerLinesBeforeData,
                            CheckpointIndex rowIndex, CheckpointIndex lineIndex,
                            RowStore rowStore, LineStore lineStore,
                            SeekableByteChannel indexerChannel) {
        this.file = file;
        this.dialect = dialect;
        this.indexStartOffset = indexStartOffset;
        this.presetColumnNames = presetColumnNames;
        this.headerLinesBeforeData = headerLinesBeforeData;
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
        this.indexerThread = new Thread(() -> runIndexer(indexStartOffset, false), "kalix-dataview-indexer");
        this.indexerThread.setDaemon(true);
    }

    /**
     * Sniffs the dialect from the head of the file, opens the channels and
     * starts the background index pass. Blocking I/O — call off the EDT.
     */
    public static DataViewSession open(Path file) throws IOException {
        return open(file, 0L, null, 0L);
    }

    /**
     * Variant for formats whose tabular data starts mid-file with externally
     * known column names (e.g. {@code .res.csv}: the extended header supplies
     * the names, and {@code dataStartOffset} points just past its EOH marker).
     * The dialect is sniffed from the data region; preset column names force
     * "no header row in the data".
     */
    public static DataViewSession open(Path file, long dataStartOffset, String[] presetColumnNames,
                                       long headerLinesBeforeData) throws IOException {
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
        RowStore rowStore = null;
        LineStore lineStore = null;
        SeekableByteChannel indexerChannel;
        try {
            rowStore = new RowStore(
                Files.newByteChannel(file, StandardOpenOption.READ), dialect, rowIndex, MAX_CACHED_BLOCKS);
            lineStore = new LineStore(
                Files.newByteChannel(file, StandardOpenOption.READ), dialect, lineIndex, MAX_CACHED_BLOCKS);
            indexerChannel = Files.newByteChannel(file, StandardOpenOption.READ);
        } catch (IOException e) {
            // A failed open must not leak the channels that did open.
            if (rowStore != null) {
                closeQuietly(rowStore::close);
            }
            if (lineStore != null) {
                closeQuietly(lineStore::close);
            }
            throw e;
        }

        DataViewSession session = new DataViewSession(
            file, dialect, dataStartOffset + dialect.bomLength(), presetColumnNames,
            headerLinesBeforeData, rowIndex, lineIndex, rowStore, lineStore, indexerChannel);
        session.indexerThread.start();
        return session;
    }

    // --- State reads (all EDT-safe) ---

    public CsvDialect dialect() {
        return dialect;
    }

    /** The backing file — package-private, for the column extractor. */
    Path filePath() {
        return file;
    }

    /** Byte offset where tabular data begins (past any BOM or extended header) — package-private. */
    long dataStartOffset() {
        return indexStartOffset;
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

    /**
     * Physical lines occupied by an extended format header before the data
     * region (0 for plain CSV) — for mapping data-region lines onto an editor
     * that shows the whole file.
     */
    public long headerLinesBeforeData() {
        return headerLinesBeforeData;
    }

    /**
     * The physical line (data-region numbering) on which the given row starts.
     * Quoted newlines make rows and lines diverge, so this walks the byte
     * offsets both indexes share: seek the row's checkpoint, scan to the row's
     * first byte, then count newlines from the nearest line checkpoint. At most
     * two sub-stride scans. Blocking I/O — background threads only.
     */
    public long lineNumberForRow(long row) throws IOException {
        CheckpointIndex.Checkpoint rowCheckpoint = rowIndex.floorCheckpoint(row);
        if (rowCheckpoint == null) {
            return 0;
        }
        try (SeekableByteChannel probe = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long rowStart = rowCheckpoint.byteOffset();
            if (row > rowCheckpoint.firstItem()) {
                rowStart = scanToRowStart(probe, rowCheckpoint.byteOffset(), row - rowCheckpoint.firstItem());
            }
            CheckpointIndex.Checkpoint lineCheckpoint = lineIndex.floorCheckpointByOffset(rowStart);
            if (lineCheckpoint == null) {
                return 0;
            }
            return lineCheckpoint.firstItem() + countNewlines(probe, lineCheckpoint.byteOffset(), rowStart);
        }
    }

    /** Byte offset where the {@code rowsToSkip}-th row after {@code from} starts (quote-aware). */
    private long scanToRowStart(SeekableByteChannel probe, long from, long rowsToSkip) throws IOException {
        probe.position(from);
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        byte quote = (byte) dialect.quote();
        boolean inQuotes = false;
        long remaining = rowsToSkip;
        long position = from;
        while (true) {
            buffer.clear();
            int n = probe.read(buffer);
            if (n < 0) {
                return position;
            }
            buffer.flip();
            for (int i = 0; i < n; i++) {
                byte b = buffer.get(i);
                if (b == quote) {
                    inQuotes = !inQuotes;
                } else if (b == '\n' && !inQuotes) {
                    remaining--;
                    if (remaining == 0) {
                        return position + i + 1;
                    }
                }
            }
            position += n;
        }
    }

    /** Newlines in {@code [from, toExclusive)}. */
    private static long countNewlines(SeekableByteChannel probe, long from, long toExclusive) throws IOException {
        probe.position(from);
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long count = 0;
        long position = from;
        while (position < toExclusive) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), toExclusive - position));
            int n = probe.read(buffer);
            if (n < 0) {
                break;
            }
            buffer.flip();
            for (int i = 0; i < n; i++) {
                if (buffer.get(i) == '\n') {
                    count++;
                }
            }
            position += n;
        }
        return count;
    }

    /**
     * What the unified Find is looking for. {@code dateMillis} is non-null when
     * the query itself parses as a date (via the shared {@link CsvDates}
     * ladder): date-column cells then also match by <em>parsed</em> date — at
     * day granularity for a date-only query — so "1/6/2020" finds
     * "2020-06-01" however the file spells it.
     */
    public record FindSpec(String query, boolean matchCase, boolean wholeCell,
                           boolean inDates, boolean inValues, Long dateMillis, boolean dateOnly) {
    }

    /** One matching cell (file row, model column) and its 1-based ordinal among all matches. */
    public record CellRef(long row, int column, int ordinal) {
    }

    /**
     * Everything one pass can say about a spec's matches: the total, the
     * boundary matches for navigating from {@code (fromRow, fromColumn)} in
     * either direction (wrap decisions belong to the caller), and — when the
     * query is a date — the first row at or after it: the "nearest later
     * date" fallback landing ({@code -1} when none).
     */
    public record FindScan(int total, CellRef firstOverall, CellRef lastOverall,
                           CellRef firstAfter, CellRef lastBefore, long nearestDateRow) {
    }

    /**
     * Streams the data region once and reports every navigation-relevant match
     * for the spec — the usual pattern: one sequential pass on its own channel,
     * the block caches untouched, cell grammar agreeing with the indexer
     * (quote parity). The header row is never a cell match (the Columns scope
     * is resolved against {@link #columnNames()} by the caller). Blocking I/O —
     * background threads only; a 1GB file takes a few seconds.
     */
    public FindScan scanForMatches(FindSpec spec, long fromRow, int fromColumn) throws IOException {
        ScanState state = new ScanState();
        long headerRows = dialect.hasHeaderRow() ? 1 : 0;
        String needle = spec.matchCase()
            ? spec.query().trim() : spec.query().trim().toLowerCase(Locale.ROOT);
        try (SeekableByteChannel probe = Files.newByteChannel(file, StandardOpenOption.READ)) {
            probe.position(indexStartOffset);
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            ByteArrayOutputStream field = new ByteArrayOutputStream(64);
            byte quote = (byte) dialect.quote();
            byte delimiter = (byte) dialect.delimiter();
            boolean inQuotes = false;
            boolean quotePending = false; // saw a quote while quoted: escape or close?
            long row = 0;
            int column = 0;
            while (true) {
                buffer.clear();
                int n = probe.read(buffer);
                if (n < 0) {
                    break;
                }
                buffer.flip();
                for (int i = 0; i < n; i++) {
                    byte b = buffer.get(i);
                    // RowBlockParser's exact grammar ("" -> a literal quote, \r
                    // preserved inside quotes), so the scanned text IS the
                    // displayed text — a cell must be findable by exactly what
                    // the table shows, escaped quotes included.
                    if (quotePending) {
                        quotePending = false;
                        if (b == quote) {
                            writeCapped(field, quote);
                            continue;
                        }
                        inQuotes = false; // the pending quote closed the field
                    }
                    if (inQuotes) {
                        if (b == quote) {
                            quotePending = true;
                        } else {
                            writeCapped(field, b);
                        }
                    } else if (b == quote) {
                        inQuotes = true;
                    } else if (b == '\n') {
                        offerCell(state, spec, needle, row, column,
                            field.toString(dialect.charset()), headerRows, fromRow, fromColumn);
                        field.reset();
                        column = 0;
                        row++;
                    } else if (b == delimiter) {
                        offerCell(state, spec, needle, row, column,
                            field.toString(dialect.charset()), headerRows, fromRow, fromColumn);
                        field.reset();
                        column++;
                    } else if (b != '\r') {
                        writeCapped(field, b);
                    }
                }
            }
            if (field.size() > 0 || column > 0) {
                // Final row without a trailing newline.
                offerCell(state, spec, needle, row, column,
                    field.toString(dialect.charset()), headerRows, fromRow, fromColumn);
            }
        }
        return new FindScan(state.total, state.firstOverall, state.lastOverall,
            state.firstAfter, state.lastBefore, state.nearestDateRow);
    }

    /** Mutable trackers for one {@link #scanForMatches} pass. */
    private static final class ScanState {
        int total;
        CellRef firstOverall;
        CellRef lastOverall;
        CellRef firstAfter;
        CellRef lastBefore;
        long nearestDateRow = -1;
        CsvDates.Spec fileDateSpec;
    }

    /**
     * Find-scan field cap: a defensive bound against runaway quotes, not a
     * display-parity guarantee — a cell longer than this scans truncated, so a
     * whole-cell match on a &gt;4KB cell can miss. Accepted: such cells are
     * pathological, and the bound keeps a stray quote from accumulating the file.
     */
    private static final int MAX_FIND_FIELD_BYTES = 4096;

    private static void writeCapped(ByteArrayOutputStream field, byte b) {
        if (field.size() < MAX_FIND_FIELD_BYTES) {
            field.write(b);
        }
    }

    /** Evaluates one completed cell against the spec and folds it into the trackers. */
    private static void offerCell(ScanState state, FindSpec spec, String needle, long row, int column,
                                  String text, long headerRows, long fromRow, int fromColumn) {
        if (row < headerRows) {
            return;
        }
        String cell = text.trim();
        Long parsedDate = null;
        if (column == 0 && spec.dateMillis() != null && !cell.isEmpty()) {
            if (state.fileDateSpec == null) {
                state.fileDateSpec = CsvDates.detect(cell);
            }
            if (state.fileDateSpec != null) {
                long parsed = CsvDates.parseMillis(cell, state.fileDateSpec);
                if (parsed != CsvDates.INVALID_TS) {
                    parsedDate = parsed;
                    if (state.nearestDateRow < 0 && parsed >= spec.dateMillis()) {
                        state.nearestDateRow = row;
                    }
                }
            }
        }
        if (column == 0 ? !spec.inDates() : !spec.inValues()) {
            return;
        }
        boolean match = false;
        if (!cell.isEmpty() && !needle.isEmpty()) {
            String haystack = spec.matchCase() ? cell : cell.toLowerCase(Locale.ROOT);
            match = spec.wholeCell() ? haystack.equals(needle) : haystack.contains(needle);
        }
        if (!match && parsedDate != null) {
            match = spec.dateOnly()
                ? Math.floorDiv(parsedDate, 86_400_000L) == Math.floorDiv(spec.dateMillis(), 86_400_000L)
                : parsedDate.longValue() == spec.dateMillis();
        }
        if (!match) {
            return;
        }
        state.total++;
        CellRef ref = new CellRef(row, column, state.total);
        if (state.firstOverall == null) {
            state.firstOverall = ref;
        }
        state.lastOverall = ref;
        if (row > fromRow || (row == fromRow && column > fromColumn)) {
            if (state.firstAfter == null) {
                state.firstAfter = ref;
            }
        } else if (row < fromRow || column < fromColumn) {
            state.lastBefore = ref;
        }
    }

    // (findDateRow and findNextRow were absorbed by scanForMatches: the unified
    // Find covers text, value and parsed-date matching in one pass, and the old
    // "first row at or after a date" jump survives as its nearestDateRow.)

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

    private void runIndexer(long fromOffset, boolean resumePass) {
        try {
            CsvIndexer.Outcome outcome = CsvIndexer.index(indexerChannel, fromOffset, dialect,
                rowIndex, lineIndex, this::onIndexProgress, () -> closed);
            if (!closed) {
                cleanEnd = outcome.cleanEnd();
                captureTailSample();
                indexingComplete = true;
                maybeRequestStructure();
                notifyEdt(l -> l.onProgress(rowIndex.itemCount(), lineIndex.itemCount(),
                    rowIndex.indexedBytes(), rowIndex.totalBytes(), true));
            }
        } catch (IOException e) {
            if (!closed) {
                logger.warn("Indexing failed for {}: {}", file, e.getMessage());
            }
        } finally {
            if (resumePass) {
                // Only a resume pass owns the flag; the initial pass clearing it
                // could let a full rebuild race a live resume.
                resumeRunning.set(false);
            }
        }
        // A change event that landed while this pass was finishing was answered
        // with "the running tail will pick it up" — untrue once the reader hit
        // EOF. Chain one more resume if the file already grew past what we
        // indexed, so a simulation's final rows are never silently missed.
        if (!closed && indexingComplete) {
            try {
                if (Files.size(file) > rowIndex.indexedBytes()) {
                    tryResumeAppend();
                }
            } catch (IOException ignored) {
                // The next external event will handle it.
            }
        }
    }

    /** Remembers the tail of the indexed region so an append can be verified as pure growth. */
    private void captureTailSample() {
        try {
            long end = rowIndex.indexedBytes();
            int length = (int) Math.max(0, Math.min(4096, end - indexStartOffset));
            ByteBuffer buffer = ByteBuffer.allocate(length);
            indexerChannel.position(end - length);
            while (buffer.hasRemaining() && indexerChannel.read(buffer) >= 0) {
                // fill
            }
            tailSample = buffer.array();
            tailSampleOffset = end - length;
        } catch (IOException e) {
            tailSample = null; // resume unavailable; a change will rebuild instead
        }
    }

    /**
     * Attempts to continue indexing after the file grew — the "live tail" of a
     * running simulation appending results. Succeeds when the file is strictly
     * larger, the indexed region ended cleanly (newline, quotes closed) and its
     * tail bytes are unchanged: indexing resumes from the old end, every count,
     * checkpoint and cached block stays valid, and the views simply keep growing.
     *
     * <p>Blocking I/O (a stat plus a small verification read) — background
     * threads only; the completed pass re-checks the file size itself, so a
     * growth event answered "already tailing" is never lost.
     *
     * @return true when the change was handled here (or a resume is already
     *         running); false when the caller must rebuild the session instead
     */
    public boolean tryResumeAppend() {
        if (closed || resumeRunning.get()) {
            return true; // nothing to do / the running tail re-checks size at its end
        }
        if (!indexingComplete || !cleanEnd || tailSample == null) {
            return false;
        }
        if (rowIndex.itemCount() == 0) {
            // A file opened while empty sniffed its dialect from zero bytes;
            // the first real content must re-sniff via a rebuild, not lock the
            // guessed dialect in by resuming.
            return false;
        }
        long oldEnd = rowIndex.indexedBytes();
        try {
            if (Files.size(file) <= oldEnd) {
                return false; // shrunk or same size: not an append
            }
            byte[] current = new byte[tailSample.length];
            try (SeekableByteChannel probe = Files.newByteChannel(file, StandardOpenOption.READ)) {
                probe.position(tailSampleOffset);
                ByteBuffer buffer = ByteBuffer.wrap(current);
                while (buffer.hasRemaining() && probe.read(buffer) >= 0) {
                    // fill
                }
                if (buffer.hasRemaining()) {
                    return false; // truncated under us
                }
            }
            if (!Arrays.equals(current, tailSample)) {
                return false; // rewritten behind us, not appended
            }
        } catch (IOException e) {
            return false;
        }
        if (!resumeRunning.compareAndSet(false, true)) {
            return true;
        }
        // The old final blocks may be cached partial; appended items landing in
        // the same block must re-parse rather than be served short.
        if (rowIndex.itemCount() > 0) {
            rowStore.evictBlock(rowStore.blockOf(rowIndex.itemCount() - 1));
        }
        if (lineIndex.itemCount() > 0) {
            lineStore.evictBlock(lineStore.blockOf(lineIndex.itemCount() - 1));
        }
        indexingComplete = false;
        rowIndex.reopen();
        lineIndex.reopen();
        Thread resumer = new Thread(() -> runIndexer(oldEnd, true), "kalix-dataview-indexer-resume");
        resumer.setDaemon(true);
        resumer.start();
        return true;
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
                boolean known = false;
                try {
                    rowStore.ensureBlockLoaded(0);
                    String[] first = rowStore.rowIfLoaded(0);
                    if (first != null) {
                        if (presetColumnNames == null) {
                            // Preset names (.res.csv) already fixed the column count;
                            // a ragged first data row must not narrow the table.
                            headerRow = first;
                            columnCount = first.length;
                        }
                        known = true;
                        notifyEdt(Listener::onStructureKnown);
                        notifyEdt(l -> l.onRowBlockLoaded(0, blockCount(rowIndex, 0)));
                    }
                } finally {
                    if (!known) {
                        // Transient I/O failure: allow the next progress event to retry
                        // rather than leaving the table 0-column forever.
                        structureRequested.set(false);
                    }
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
        } catch (RejectedExecutionException ignored) {
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
