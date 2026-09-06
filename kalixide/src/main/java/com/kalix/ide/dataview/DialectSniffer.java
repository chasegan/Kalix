package com.kalix.ide.dataview;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a {@link CsvDialect} from the head of a file (the first chunk, read
 * once — typically 64KB). Everything here is a decision about interpretation,
 * never a mutation: the sniffed dialect is shown to the user on the data tab so
 * a wrong guess is visible rather than silent.
 *
 * <p>Delimiter detection mirrors the approach used elsewhere in Kalix
 * ({@code TimeSeriesCsvImporter} / {@code CsvHeaderReader}): count the candidate
 * characters {@code , ; \t |} outside quotes on the first non-blank line and
 * take the most frequent, ties resolved by that candidate order. A {@code .csv}
 * full of semicolons or tabs therefore views the same way it already imports.
 *
 * <p>Header detection compares the first two non-blank lines: the first row is
 * called a header when none of its fields parse as a number while the second
 * row has at least one that does. A headerless file of {@code date,value} rows
 * is thus not mistaken for a header (the numeric value column decides).
 */
public final class DialectSniffer {

    private static final char[] DELIMITER_CANDIDATES = {',', ';', '\t', '|'};
    private static final char QUOTE = '"';

    private DialectSniffer() {
    }

    /**
     * Sniffs the dialect from the first bytes of the file.
     *
     * @param head the leading bytes of the file (any length; more gives better
     *             detection, 64KB is plenty)
     */
    public static CsvDialect sniff(byte[] head) {
        int bomLength = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            bomLength = 3;
        } else if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            bomLength = 2;
            charset = StandardCharsets.UTF_16BE; // detected for honest reporting; not viewable in v1
        } else if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            bomLength = 2;
            charset = StandardCharsets.UTF_16LE;
        }

        String text = new String(head, bomLength, head.length - bomLength, StandardCharsets.UTF_8);

        String lineEndingLabel = text.contains("\r\n") ? "CRLF"
            : text.contains("\n") ? "LF"
            : text.contains("\r") ? "CR"
            : "LF";

        String line1 = null;
        String line2 = null;
        for (String line : text.split("\r\n|\n|\r", -1)) {
            if (line.isBlank()) {
                continue;
            }
            if (line1 == null) {
                line1 = line;
            } else {
                line2 = line;
                break;
            }
        }

        char delimiter = line1 != null ? detectDelimiter(line1) : ',';

        boolean hasHeader = false;
        if (line1 != null) {
            String[] fields1 = splitFields(line1, delimiter);
            if (line2 != null) {
                String[] fields2 = splitFields(line2, delimiter);
                hasHeader = noneNumeric(fields1) && anyNumeric(fields2);
            } else {
                hasHeader = noneNumeric(fields1);
            }
        }

        return new CsvDialect(delimiter, QUOTE, charset, bomLength, hasHeader, lineEndingLabel);
    }

    /** Most frequent candidate outside quotes; ties resolved by candidate order. */
    private static char detectDelimiter(String line) {
        char best = ',';
        int maxCount = 0;
        for (char candidate : DELIMITER_CANDIDATES) {
            int count = 0;
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == QUOTE) {
                    inQuotes = !inQuotes;
                } else if (c == candidate && !inQuotes) {
                    count++;
                }
            }
            if (count > maxCount) {
                maxCount = count;
                best = candidate;
            }
        }
        return best;
    }

    /** Minimal quote-aware split, sufficient for the numeric-shape heuristic. */
    private static String[] splitFields(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == QUOTE) {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private static boolean noneNumeric(String[] fields) {
        for (String field : fields) {
            if (isNumeric(field)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyNumeric(String[] fields) {
        for (String field : fields) {
            if (isNumeric(field)) {
                return true;
            }
        }
        return false;
    }

    /** True for anything Double can parse — covers scientific notation. */
    private static boolean isNumeric(String field) {
        String trimmed = field.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(trimmed);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
