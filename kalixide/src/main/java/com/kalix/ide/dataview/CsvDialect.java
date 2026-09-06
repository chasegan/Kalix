package com.kalix.ide.dataview;

import java.nio.charset.Charset;

/**
 * The parsing decisions made about one delimited data file: how its bytes are to
 * be interpreted. Produced once per file by {@link DialectSniffer} from the head
 * of the file and then treated as immutable truth by the indexer and parser.
 *
 * <p>Surfaced to the user verbatim (the data tab's status strip) so the
 * interpretation layer declares its assumptions instead of applying them
 * silently — the raw text view remains the ground truth either way.
 *
 * @param delimiter       the field separator (auto-detected; {@code ','} default)
 * @param quote           the quoting character ({@code '"'})
 * @param charset         the charset used to decode field bytes. V1 engine support
 *                        is ASCII-compatible charsets only (UTF-8, with or without
 *                        BOM): the byte-level scanner assumes delimiter, quote and
 *                        newline occupy single bytes that never appear inside a
 *                        multi-byte sequence. A UTF-16 BOM is still *detected* and
 *                        reported here so the host can refuse honestly.
 * @param bomLength       number of leading bytes occupied by a byte-order mark
 *                        (0 when absent); data scanning starts after it
 * @param hasHeaderRow    whether the first row looks like column names rather than
 *                        data (heuristic — see {@link DialectSniffer})
 * @param lineEndingLabel human-readable line-ending style ("LF", "CRLF", "CR"),
 *                        for display only
 */
public record CsvDialect(
    char delimiter,
    char quote,
    Charset charset,
    int bomLength,
    boolean hasHeaderRow,
    String lineEndingLabel
) {
}
