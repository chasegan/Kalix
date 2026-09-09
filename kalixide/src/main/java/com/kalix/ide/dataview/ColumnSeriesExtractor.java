package com.kalix.ide.dataview;

import com.kalix.ide.io.CsvDates;
import com.kalix.ide.io.TimeSeriesCsvImporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Materialises whole data-file columns for plotting: one sequential streamed
 * pass on its own channel (the {@code findNextRow} pattern — never through the
 * block caches, which a multi-million-row read would thrash), parsing the date
 * column plus the requested value columns straight into primitive arrays.
 *
 * <p>Field grammar matches {@link RowBlockParser} / {@link CsvIndexer} exactly
 * (quote parity, {@code ""} escapes, CRLF normalisation), so the extractor and
 * the table always agree on what a row is. Date parsing is {@link CsvDates} —
 * the same ladder as the time-series importer — and value parsing is
 * {@link TimeSeriesCsvImporter#parseNumericValue}, so a value plots exactly as
 * it would import. Only requested fields ever accumulate bytes; everything else
 * streams past.
 *
 * <p>Blocking I/O — background threads only.
 */
public final class ColumnSeriesExtractor {

    /** Non-blank data rows probed for a date format before refusing the file. */
    private static final int DATE_PROBE_ROWS = 20;
    private static final int CHUNK_BYTES = 256 * 1024;
    private static final int INITIAL_CAPACITY = 1024;
    /**
     * Bail-out bound per accumulated field. A stray unbalanced quote makes one
     * "row" span the rest of the file (honest RFC behaviour — the indexer
     * agrees), and without a cap a wanted field would accumulate gigabytes.
     * Mirrors {@link RowBlockParser#MAX_BLOCK_BYTES}'s defence; checked per
     * chunk, so the overshoot is at most {@link #CHUNK_BYTES}.
     */
    static final int MAX_FIELD_BYTES = 1_000_000;

    /**
     * One extraction's outcome. Either a refusal (with the honest reason — the
     * plot header shows it verbatim) or the data: shared timestamps plus one
     * value array per requested column, parallel to the request order. Rows
     * whose non-empty date failed to parse after detection are skipped and
     * counted in {@code badDateRows}; blank rows are skipped silently.
     */
    public record Result(String refusal, long[] timestamps, double[][] columns, long badDateRows) {
        public boolean refused() {
            return refusal != null;
        }

        static Result refuse(String reason) {
            return new Result(reason, null, null, 0);
        }
    }

    /** Per-pass mutable parse state shared by the loop and the row consumer. */
    private static final class ParseState {
        CsvDates.Spec spec;
        long probedRows;
        long badDateRows;
    }

    private ColumnSeriesExtractor() {
    }

    /**
     * Streams the file once and extracts the requested columns.
     *
     * @param source          the data file's byte source (disk, or a decompressed copy in memory)
     * @param dialect         its sniffed dialect
     * @param dataStartOffset byte offset where tabular data begins (past any BOM
     *                        or extended format header)
     * @param dataEndOffset   exclusive end of the region the caller's index vouches
     *                        for — the session's indexed byte count. The pass reads
     *                        exactly {@code [dataStartOffset, dataEndOffset)} and never
     *                        continues into bytes written after that index was built,
     *                        so a rewrite landing mid-read cannot be stitched onto the
     *                        old file's rows (a series of one file's row 1 and another's
     *                        row 2 once reached the plot this way). Bytes past the extent
     *                        are the next index pass's business, and that pass triggers
     *                        the next extraction. Counting mirrors the indexer's: a row
     *                        left unterminated at the extent's end is a row only when
     *                        the extent is the whole file.
     * @param skipHeaderRow   whether the first data-region row is a header
     * @param columnIndices   0-based column indices to extract (column 0 is the
     *                        date axis, always read — do not request it)
     * @param maxRows         hard bound on materialised rows; exceeding it is a
     *                        refusal, never a silent truncation (the caller's
     *                        row-count pre-check can be stale while indexing runs)
     * @param cancelled       polled per data row; a cancelled extraction returns
     *                        {@code null} and its partial work is discarded
     * @return the result; or {@code null} when cancelled, or when the file no longer
     *         holds the extent (it shrank under us, so the index it came from is stale)
     */
    public static Result extract(ByteSource source, CsvDialect dialect, long dataStartOffset, long dataEndOffset,
                                 boolean skipHeaderRow, int[] columnIndices, long maxRows,
                                 BooleanSupplier cancelled) throws IOException {
        int maxWanted = 0;
        for (int index : columnIndices) {
            if (index <= 0) {
                throw new IllegalArgumentException("column 0 is the date axis; request data columns only");
            }
            maxWanted = Math.max(maxWanted, index);
        }
        int[] slotByColumn = new int[maxWanted + 1];
        Arrays.fill(slotByColumn, -1);
        for (int slot = 0; slot < columnIndices.length; slot++) {
            slotByColumn[columnIndices[slot]] = slot;
        }

        Accumulator out = new Accumulator(columnIndices.length);
        double[] staged = new double[columnIndices.length];
        Arrays.fill(staged, Double.NaN);
        ParseState state = new ParseState();
        boolean headerPending = skipHeaderRow;

        try (SeekableByteChannel channel = source.openChannel()) {
            long size = channel.size();
            if (size < dataEndOffset) {
                return null; // the indexed extent is gone: whoever indexed it is stale
            }
            boolean extentIsWholeFile = dataEndOffset == size;
            long remaining = Math.max(0, dataEndOffset - dataStartOffset);
            channel.position(dataStartOffset);
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK_BYTES);
            byte delimiter = (byte) dialect.delimiter();
            byte quote = (byte) dialect.quote();

            ByteArrayOutputStream field = new ByteArrayOutputStream(64);
            String dateText = null;
            int fieldIndex = 0;
            boolean inQuotes = false;
            boolean quotePending = false; // saw a quote while quoted: escape or close?
            boolean rowHasContent = false;

            reading:
            while (remaining > 0) {
                // Poll per chunk as well as per row: a runaway row (stray quote)
                // has no row boundaries, and dispose() must still stop the pass.
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return null;
                }
                if (field.size() > MAX_FIELD_BYTES) {
                    return Result.refuse(String.format(
                        "a field exceeds %,d bytes — likely an unbalanced quote", MAX_FIELD_BYTES));
                }
                buffer.clear();
                if (remaining < buffer.capacity()) {
                    buffer.limit((int) remaining); // never read past the extent
                }
                int n = channel.read(buffer);
                if (n < 0) {
                    return null; // shrank mid-read: same verdict as at open
                }
                remaining -= n;
                buffer.flip();
                for (int i = 0; i < n; i++) {
                    byte b = buffer.get(i);
                    rowHasContent = true;
                    boolean wanted = fieldIndex == 0
                        || (fieldIndex <= maxWanted && slotByColumn[fieldIndex] >= 0);

                    if (quotePending) {
                        quotePending = false;
                        if (b == quote) {
                            if (wanted) {
                                field.write(quote); // "" -> literal quote, still quoted
                            }
                            continue;
                        }
                        inQuotes = false; // the pending quote closed the field; b is structural again
                    }

                    if (inQuotes) {
                        if (b == quote) {
                            quotePending = true;
                        } else if (wanted) {
                            field.write(b);
                        }
                    } else if (b == quote) {
                        inQuotes = true;
                    } else if (b == delimiter) {
                        dateText = endField(field, fieldIndex, slotByColumn, staged, dialect, dateText);
                        fieldIndex++;
                    } else if (b == '\n') {
                        dateText = endField(field, fieldIndex, slotByColumn, staged, dialect, dateText);
                        if (headerPending) {
                            headerPending = false;
                        } else {
                            if (cancelled != null && cancelled.getAsBoolean()) {
                                return null;
                            }
                            if (!consumeRow(state, dateText, staged, out)) {
                                return Result.refuse("the first column does not parse as dates");
                            }
                        }
                        fieldIndex = 0;
                        dateText = null;
                        rowHasContent = false;
                        Arrays.fill(staged, Double.NaN);
                        if (out.size() > maxRows) {
                            break reading; // one row past the cap: evidence for the refusal below
                        }
                    } else if (b == '\r') {
                        continue; // dropped outside quotes (CRLF); preserved inside quotes above
                    } else if (wanted) {
                        field.write(b);
                    }
                }
            }

            // A final row without a trailing newline is a row only where the file
            // ends (the indexer's rule too). Cut short by the extent instead, it is a
            // row the index has not finished; the pass that finishes it re-extracts.
            if (rowHasContent && extentIsWholeFile && out.size() <= maxRows) {
                dateText = endField(field, fieldIndex, slotByColumn, staged, dialect, dateText);
                if (!headerPending && !consumeRow(state, dateText, staged, out)) {
                    return Result.refuse("the first column does not parse as dates");
                }
            }
        }

        if (state.spec == null && state.probedRows > 0) {
            return Result.refuse("the first column does not parse as dates");
        }
        if (out.size() > maxRows) {
            // The caller's row-count pre-check may have run against a still-growing
            // index; the file itself is the authority. Refuse rather than publish a
            // silent truncation that contradicts that pre-check later.
            return Result.refuse(String.format("more than %,d data rows", maxRows));
        }
        return new Result(null, out.timestamps(), out.columns(), state.badDateRows);
    }

    /**
     * Closes the current field: column 0 becomes the row's date text, requested
     * columns parse straight into their staged slot, everything else is dropped.
     * Returns the (possibly updated) date text.
     */
    private static String endField(ByteArrayOutputStream field, int fieldIndex, int[] slotByColumn,
                                   double[] staged, CsvDialect dialect, String dateText) {
        if (fieldIndex == 0) {
            String text = field.toString(dialect.charset());
            field.reset();
            return text;
        }
        if (fieldIndex < slotByColumn.length && slotByColumn[fieldIndex] >= 0) {
            staged[slotByColumn[fieldIndex]] =
                TimeSeriesCsvImporter.parseNumericValue(field.toString(dialect.charset()).trim());
        }
        field.reset();
        return dateText;
    }

    /**
     * Consumes one completed data row: detects the date format on early rows,
     * then appends parsed rows. Blank dates are skipped silently (blank lines);
     * non-empty unparseable dates count as bad rows once the format is known.
     *
     * @return false when the probe budget is exhausted without finding a format
     *         (the caller refuses)
     */
    private static boolean consumeRow(ParseState state, String dateText, double[] staged, Accumulator out) {
        String date = dateText == null ? "" : dateText.trim();
        if (date.isEmpty()) {
            return true; // blank line / empty date cell: not data, not evidence
        }
        if (state.spec == null) {
            state.spec = CsvDates.detect(date);
            if (state.spec == null) {
                state.probedRows++;
                return state.probedRows <= DATE_PROBE_ROWS;
            }
            // Probe rows that failed before the format was found were data rows
            // this pass dropped — count them so the skipped-rows note is honest.
            state.badDateRows += state.probedRows;
            state.probedRows = 0;
        }
        long timestamp = CsvDates.parseMillis(date, state.spec);
        if (timestamp == CsvDates.INVALID_TS) {
            state.badDateRows++;
            return true;
        }
        out.add(timestamp, staged);
        return true;
    }

    /** Growable primitive columns: timestamps plus one double[] per requested column. */
    private static final class Accumulator {
        private long[] timestamps = new long[INITIAL_CAPACITY];
        private final double[][] columns;
        private int size;

        Accumulator(int slots) {
            columns = new double[slots][];
            for (int i = 0; i < slots; i++) {
                columns[i] = new double[INITIAL_CAPACITY];
            }
        }

        void add(long timestamp, double[] values) {
            if (size == timestamps.length) {
                int capacity = timestamps.length + (timestamps.length >> 1);
                timestamps = Arrays.copyOf(timestamps, capacity);
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = Arrays.copyOf(columns[i], capacity);
                }
            }
            timestamps[size] = timestamp;
            for (int i = 0; i < columns.length; i++) {
                columns[i][size] = values[i];
            }
            size++;
        }

        int size() {
            return size;
        }

        long[] timestamps() {
            return Arrays.copyOf(timestamps, size);
        }

        double[][] columns() {
            double[][] trimmed = new double[columns.length][];
            for (int i = 0; i < columns.length; i++) {
                trimmed[i] = Arrays.copyOf(columns[i], size);
            }
            return trimmed;
        }
    }
}
