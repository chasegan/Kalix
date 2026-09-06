package com.kalix.ide.dataview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a bounded run of CSV rows starting at a known row boundary (a
 * {@link CheckpointIndex} checkpoint). This is the only code that turns bytes
 * into cell strings, and it is only ever asked for a screenful-scale block —
 * the file as a whole is never parsed into memory.
 *
 * <p>Field grammar is RFC 4180-flavoured: fields separated by the dialect
 * delimiter; a quote toggles quoted mode (matching {@link CsvIndexer}'s parity
 * convention exactly, so parser and indexer always agree on row boundaries);
 * {@code ""} inside a quoted field is an escaped quote; delimiters and newlines
 * inside quotes are literal. A bare {@code \r} outside quotes is dropped
 * (CRLF normalisation); inside quotes it is preserved as-is.
 *
 * <p>Bytes are accumulated per field and decoded with the dialect charset at
 * field end, so multi-byte UTF-8 sequences are never split — the structural
 * bytes (delimiter, quote, newline) are ASCII and cannot occur inside a
 * multi-byte sequence.
 */
public final class RowBlockParser {

    private static final int CHUNK_BYTES = 64 * 1024;

    private RowBlockParser() {
    }

    /**
     * Parses up to {@code maxRows} rows starting at {@code offset}, which must
     * be a row boundary (a checkpoint, or the data start).
     */
    public static List<String[]> parse(SeekableByteChannel channel, long offset,
                                       CsvDialect dialect, int maxRows) throws IOException {
        List<String[]> rows = new ArrayList<>();
        if (maxRows <= 0) {
            return rows;
        }
        channel.position(offset);

        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_BYTES);
        byte delimiter = (byte) dialect.delimiter();
        byte quote = (byte) dialect.quote();

        ByteArrayOutputStream field = new ByteArrayOutputStream(64);
        List<String> row = new ArrayList<>();
        boolean inQuotes = false;
        boolean quotePending = false; // saw a quote while quoted: escape or close?
        boolean rowHasContent = false;

        outer:
        while (true) {
            buffer.clear();
            int n = channel.read(buffer);
            if (n < 0) {
                break;
            }
            buffer.flip();
            for (int i = 0; i < n; i++) {
                byte b = buffer.get(i);
                rowHasContent = true;

                if (quotePending) {
                    quotePending = false;
                    if (b == quote) {
                        field.write(quote); // "" -> literal quote, still quoted
                        continue;
                    }
                    inQuotes = false; // the pending quote closed the field; b is structural again
                }

                if (inQuotes) {
                    if (b == quote) {
                        quotePending = true;
                    } else {
                        field.write(b);
                    }
                } else if (b == quote) {
                    inQuotes = true;
                } else if (b == delimiter) {
                    endField(field, row, dialect);
                } else if (b == '\n') {
                    endField(field, row, dialect);
                    rows.add(row.toArray(new String[0]));
                    row.clear();
                    rowHasContent = false;
                    if (rows.size() >= maxRows) {
                        break outer;
                    }
                } else if (b == '\r') {
                    // dropped outside quotes (CRLF); preserved inside quotes above
                    continue;
                } else {
                    field.write(b);
                }
            }
        }

        // Final row without a trailing newline.
        if (rowHasContent && rows.size() < maxRows) {
            endField(field, row, dialect);
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    private static void endField(ByteArrayOutputStream field, List<String> row, CsvDialect dialect) {
        row.add(field.toString(dialect.charset()));
        field.reset();
    }
}
