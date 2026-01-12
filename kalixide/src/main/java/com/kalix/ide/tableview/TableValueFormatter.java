package com.kalix.ide.tableview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats table values for INI file output with support for:
 * - Inline (single line) formatting
 * - Multi-line formatting with column alignment
 * - Comment preservation from original values
 */
public class TableValueFormatter {

    private static final Pattern COMMENT_PATTERN = Pattern.compile("(.*?)(\\s*[;#].*)$");

    private final List<String> originalLineComments = new ArrayList<>();
    private String headerLine = null;

    /**
     * Parses the original INI value to extract line comments.
     * Call this before formatting to preserve comments.
     *
     * @param originalValue The original property value from the INI file
     */
    public void parseOriginalComments(String originalValue) {
        originalLineComments.clear();
        headerLine = null;

        if (originalValue == null || originalValue.isEmpty()) {
            return;
        }

        String[] lines = originalValue.split("\n");
        boolean isFirstDataLine = true;

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines
            if (trimmed.isEmpty()) {
                continue;
            }

            // Check if this is a header line (contains text like column names)
            if (trimmed.matches(".*[a-zA-Z\\[\\]].*")) {
                // Extract comment from header if present
                Matcher matcher = COMMENT_PATTERN.matcher(line);
                if (matcher.matches()) {
                    headerLine = matcher.group(1).trim();
                    // Header comment not preserved separately
                } else {
                    headerLine = trimmed;
                }
                continue;
            }

            // Data line - extract any trailing comment
            Matcher matcher = COMMENT_PATTERN.matcher(line);
            if (matcher.matches()) {
                originalLineComments.add(matcher.group(2)); // Keep the comment including leading space
            } else {
                originalLineComments.add(null); // No comment on this line
            }
        }
    }

    /**
     * Formats values as a single inline string.
     *
     * @param values 2D array of values (for VERTICAL: [n][1], for HORIZONTAL: [rows][cols])
     * @param orientation The display orientation
     * @return Comma-separated values on a single line
     */
    public String formatInline(String[][] values, DisplayOrientation orientation) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        if (orientation == DisplayOrientation.VERTICAL) {
            for (String[] row : values) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(row[0].trim());
                first = false;
            }
        } else {
            for (String[] row : values) {
                for (String cell : row) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(cell.trim());
                    first = false;
                }
            }
        }

        return sb.toString();
    }

    /**
     * Formats values as multi-line string with column alignment.
     *
     * @param values 2D array of values
     * @param orientation The display orientation
     * @param valuesPerLine Number of values per line
     * @param headerLine Optional header line (for HORIZONTAL tables like dimensions)
     * @param continuationIndent Number of spaces to indent continuation lines (aligns with first value)
     * @return Formatted multi-line string with aligned columns
     */
    public String formatMultiLine(String[][] values, DisplayOrientation orientation,
                                   int valuesPerLine, String headerLine, int continuationIndent) {
        if (orientation == DisplayOrientation.VERTICAL) {
            return formatMultiLineVertical(values, valuesPerLine, continuationIndent);
        } else {
            return formatMultiLineHorizontal(values, headerLine, continuationIndent);
        }
    }

    /**
     * Formats VERTICAL orientation (params) as multi-line with alignment.
     */
    private String formatMultiLineVertical(String[][] values, int valuesPerLine, int continuationIndent) {
        // Flatten values
        List<String> flatValues = new ArrayList<>();
        for (String[] row : values) {
            flatValues.add(row[0].trim());
        }

        // Calculate column widths for alignment
        int[] columnWidths = calculateColumnWidths(flatValues, valuesPerLine);

        // Create indent string
        String indent = " ".repeat(continuationIndent);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < flatValues.size(); i++) {
            if (i > 0) {
                sb.append(", ");
                if (i % valuesPerLine == 0) {
                    // Add comment from original if available
                    int lineIndex = (i / valuesPerLine) - 1;
                    appendComment(sb, lineIndex);
                    sb.append("\n").append(indent);
                }
            }

            String value = flatValues.get(i);
            int colIndex = i % valuesPerLine;

            // Pad value for alignment (except last value on each line)
            if (colIndex < valuesPerLine - 1 && i < flatValues.size() - 1) {
                sb.append(padValue(value, columnWidths[colIndex]));
            } else {
                sb.append(value);
            }
        }

        // Add final comment if available
        int lastLineIndex = (flatValues.size() - 1) / valuesPerLine;
        appendComment(sb, lastLineIndex);

        return sb.toString();
    }

    /**
     * Formats HORIZONTAL orientation (dimensions) as multi-line with alignment.
     */
    private String formatMultiLineHorizontal(String[][] values, String headerLine, int continuationIndent) {
        if (values.length == 0) {
            return "";
        }

        int numCols = values[0].length;

        // Parse header column names if provided
        String[] headerColumns = null;
        if (headerLine != null && !headerLine.isEmpty()) {
            headerColumns = parseHeaderColumns(headerLine);
        }

        // Calculate column widths (considering both header and data)
        int[] columnWidths = new int[numCols];

        // Include header column widths
        if (headerColumns != null) {
            for (int col = 0; col < headerColumns.length && col < numCols; col++) {
                columnWidths[col] = headerColumns[col].trim().length();
            }
        }

        // Include data column widths
        for (String[] row : values) {
            for (int col = 0; col < row.length && col < numCols; col++) {
                columnWidths[col] = Math.max(columnWidths[col], row[col].trim().length());
            }
        }

        // Create indent string
        String indent = " ".repeat(continuationIndent);

        StringBuilder sb = new StringBuilder();

        // Add formatted header line if provided
        if (headerColumns != null) {
            for (int col = 0; col < headerColumns.length; col++) {
                String colName = headerColumns[col].trim();
                if (col < headerColumns.length - 1) {
                    sb.append(padValue(colName, columnWidths[col]));
                } else {
                    sb.append(colName);
                }
                sb.append(", ");
            }
            sb.append("\n");
        }

        // Format each data row
        for (int rowIdx = 0; rowIdx < values.length; rowIdx++) {
            sb.append(indent);
            String[] row = values[rowIdx];

            for (int col = 0; col < row.length; col++) {
                String value = row[col].trim();
                // Pad all but last column for alignment
                if (col < row.length - 1) {
                    sb.append(padValue(value, columnWidths[col]));
                } else {
                    sb.append(value);
                }
                sb.append(", ");
            }

            // Add comment from original if available
            appendComment(sb, rowIdx);

            if (rowIdx < values.length - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Parses header line into individual column names.
     */
    private String[] parseHeaderColumns(String headerLine) {
        // Remove trailing comma if present
        String cleaned = headerLine.trim();
        if (cleaned.endsWith(",")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.split(",");
    }

    /**
     * Calculate column widths for alignment.
     */
    private int[] calculateColumnWidths(List<String> values, int valuesPerLine) {
        int[] widths = new int[valuesPerLine];

        for (int i = 0; i < values.size(); i++) {
            int col = i % valuesPerLine;
            widths[col] = Math.max(widths[col], values.get(i).length());
        }

        return widths;
    }

    /**
     * Pads a value to the specified width (left-aligned).
     */
    private String padValue(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        int padding = width - value.length();
        return value + " ".repeat(padding);
    }

    /**
     * Appends a preserved comment to the StringBuilder if available.
     */
    private void appendComment(StringBuilder sb, int lineIndex) {
        if (lineIndex >= 0 && lineIndex < originalLineComments.size()) {
            String comment = originalLineComments.get(lineIndex);
            if (comment != null) {
                sb.append(comment);
            }
        }
    }

    /**
     * Gets the parsed header line from the original value.
     */
    public String getParsedHeaderLine() {
        return headerLine;
    }
}
