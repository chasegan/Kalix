package com.kalix.ide.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one physical line of a delimited data file into styled spans — the
 * single authority both text surfaces share: the RSTA token maker (editable
 * files below the gate) and the big-file virtual text view consume the same
 * segmentation, so the two can never colour the same line differently.
 *
 * <p>Roles map onto the six existing syntax-theme slots; a per-column rotating
 * palette is a deliberate later step (it needs theme design work first).
 * Per-line by design: a quoted field spanning lines colours imperfectly past
 * its first line — accepted for v1, and the glyphs are always intact.
 */
public final class CsvLineStylist {

    /** What a span of the line is; consumers map roles onto theme slots. */
    public enum Role { VALUE, DELIMITER, HEADER, DATE_AXIS, MISSING, MARKER }

    /** One coloured run. Spans are contiguous and cover the whole line. */
    public record Span(int start, int endExclusive, Role role) {
    }

    private final char delimiter;
    private final char quote;

    public CsvLineStylist(char delimiter, char quote) {
        this.delimiter = delimiter;
        this.quote = quote;
    }

    /**
     * Styles one line. {@code headerLine} marks the file's header row (its
     * fields all take {@link Role#HEADER}); otherwise column 0 is the date
     * axis, missing-value markers are flagged (exactly the set the importer
     * treats as missing), and everything else is a value. A Source
     * {@code .res.csv} marker line ({@code EOM}/{@code EOC}/{@code EOH}) is
     * one {@link Role#MARKER} span.
     */
    public List<Span> style(String line, boolean headerLine) {
        List<Span> spans = new ArrayList<>();
        if (line.isEmpty()) {
            return spans;
        }
        String trimmed = line.trim();
        if (trimmed.equals(SourceResCsvFormat.MARKER_EOM)
                || trimmed.equals(SourceResCsvFormat.MARKER_EOC)
                || trimmed.equals(SourceResCsvFormat.MARKER_EOH)) {
            spans.add(new Span(0, line.length(), Role.MARKER));
            return spans;
        }
        boolean inQuotes = false;
        int fieldStart = 0;
        int fieldIndex = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == quote) {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                if (i > fieldStart) {
                    spans.add(fieldSpan(line, fieldStart, i, fieldIndex, headerLine));
                }
                spans.add(new Span(i, i + 1, Role.DELIMITER));
                fieldStart = i + 1;
                fieldIndex++;
            }
        }
        if (fieldStart < line.length()) {
            spans.add(fieldSpan(line, fieldStart, line.length(), fieldIndex, headerLine));
        }
        return spans;
    }

    private Span fieldSpan(String line, int start, int endExclusive, int fieldIndex, boolean headerLine) {
        Role role;
        if (headerLine) {
            role = Role.HEADER;
        } else if (fieldIndex == 0) {
            role = Role.DATE_AXIS;
        } else if (TimeSeriesCsvImporter.isMissingValue(stripQuotes(line.substring(start, endExclusive)))) {
            role = Role.MISSING;
        } else {
            role = Role.VALUE;
        }
        return new Span(start, endExclusive, role);
    }

    /** Quotes don't make a value non-missing: {@code ""} is still empty. */
    private String stripQuotes(String field) {
        String trimmed = field.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == quote
                && trimmed.charAt(trimmed.length() - 1) == quote) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
