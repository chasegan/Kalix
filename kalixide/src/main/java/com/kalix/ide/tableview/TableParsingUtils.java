package com.kalix.ide.tableview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for parsing table values from INI strings.
 */
public final class TableParsingUtils {

    // Pattern for valid numbers including scientific notation (e.g., 1e8, -3.14, .5)
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
        "-?\\d*\\.?\\d+([eE][+-]?\\d+)?"
    );

    private TableParsingUtils() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Parses numeric values from an INI property string.
     * Extracts all valid numbers, ignoring header text and non-numeric values.
     * Handles headers mixed with data, single-line or multi-line formats.
     *
     * @param iniValue The raw INI property value string
     * @return Array of numeric value strings
     */
    public static String[] parseNumericValues(String iniValue) {
        if (iniValue == null || iniValue.trim().isEmpty()) {
            return new String[0];
        }

        // Split by commas and collect all valid numeric values
        String[] parts = iniValue.split(",");
        List<String> values = new ArrayList<>();

        for (String part : parts) {
            // Remove newlines and collapse whitespace
            String trimmed = part.replaceAll("\\s+", " ").trim();
            if (!trimmed.isEmpty() && isValidNumber(trimmed)) {
                values.add(trimmed);
            }
        }

        return values.toArray(new String[0]);
    }

    /**
     * Checks if a string represents a valid number.
     * Supports integers, decimals, and scientific notation.
     *
     * @param value The string to check
     * @return true if the string is a valid number
     */
    public static boolean isValidNumber(String value) {
        return NUMBER_PATTERN.matcher(value).matches();
    }

    /**
     * Splits an INI property value into raw comma-separated cells, preserving
     * non-numeric cells (unlike {@link #parseNumericValues}, which drops them).
     * Needed for lookup tables, whose grammar includes meaningful text cells:
     * the 2D corner marker and the optional 1D header labels. A trailing comma
     * yields no empty cell.
     *
     * @param iniValue The raw INI property value string
     * @return Array of trimmed cell strings
     */
    public static String[] splitRawCells(String iniValue) {
        if (iniValue == null || iniValue.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = iniValue.split(",");
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.replaceAll("\\s+", " ").trim();
            if (!trimmed.isEmpty()) {
                cells.add(trimmed);
            }
        }
        return cells.toArray(new String[0]);
    }
}
