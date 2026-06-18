package com.kalix.ide.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the extended header of a {@code .res.csv} file (see {@link SourceResCsvFormat}).
 *
 * <p>{@link #parse(BufferedReader)} consumes the reader through the {@code EOH} marker,
 * leaving it positioned at the first data line so a caller can stream the (large) data
 * region directly afterwards. The header itself is small relative to the data, so it is
 * read fully into memory.</p>
 *
 * <p>The parser is shared by the full-data importer ({@link SourceResCsvImporter}) and the
 * names-only header reader ({@link SourceResCsvHeaderReader}).</p>
 */
public final class SourceResCsvHeader {

    private final double missingValue;
    private final List<String> seriesNames;

    private SourceResCsvHeader(double missingValue, List<String> seriesNames) {
        this.missingValue = missingValue;
        this.seriesNames = seriesNames;
    }

    /**
     * The missing-data sentinel declared in the preamble ({@code Missing data value,...}),
     * or {@link Double#NaN} if none was declared. A data cell equal to this value is read
     * as NaN.
     */
    public double getMissingValue() {
        return missingValue;
    }

    /**
     * Series names in data-column order (the {@code Date} column excluded), each taken from
     * the {@code Name} attribute of the series whose {@code Field} index matches the data
     * column. Falls back to the raw column-header token when no matching attribute is found.
     */
    public List<String> getSeriesNames() {
        return seriesNames;
    }

    /** @return the number of data (value) columns. */
    public int getSeriesCount() {
        return seriesNames.size();
    }

    /**
     * Reads and parses the header region, consuming the reader through {@code EOH}.
     *
     * @param reader positioned at the very start of the file
     * @return the parsed header; the reader is left at the first data line
     * @throws ResCsvFormatException if a required marker is missing or malformed
     * @throws IOException if the underlying read fails
     */
    public static SourceResCsvHeader parse(BufferedReader reader) throws IOException {
        // --- Configuration block: everything up to EOC. The last non-blank line of this
        //     block is the schema row for the attribute table; the missing-data sentinel is
        //     declared somewhere within it as a "key,value" pair. ---
        List<String> configBlock = new ArrayList<>();
        String line;
        boolean sawEoc = false;
        while ((line = reader.readLine()) != null) {
            if (line.trim().equals(SourceResCsvFormat.MARKER_EOC)) {
                sawEoc = true;
                break;
            }
            configBlock.add(line);
        }
        if (!sawEoc) {
            throw new ResCsvFormatException("Missing '" + SourceResCsvFormat.MARKER_EOC
                + "' marker — not a valid .res.csv header");
        }

        double missingValue = extractMissingValue(configBlock);
        List<String> attrHeader = lastNonBlank(configBlock);
        if (attrHeader == null) {
            throw new ResCsvFormatException("No series-attribute schema row before '"
                + SourceResCsvFormat.MARKER_EOC + "'");
        }
        int fieldCol = indexOfColumn(attrHeader, SourceResCsvFormat.ATTR_FIELD, 0);
        int nameCol = indexOfColumn(attrHeader, SourceResCsvFormat.ATTR_NAME, -1);

        // --- Series count (EOC+1). ---
        String countLine = reader.readLine();
        if (countLine == null) {
            throw new ResCsvFormatException("File ends immediately after '"
                + SourceResCsvFormat.MARKER_EOC + "'");
        }
        int seriesCount;
        try {
            seriesCount = Integer.parseInt(countLine.trim());
        } catch (NumberFormatException e) {
            throw new ResCsvFormatException("Expected a series count after '"
                + SourceResCsvFormat.MARKER_EOC + "' but found: " + countLine);
        }

        // --- N series-attribute rows → map of Field index → Name. ---
        Map<Integer, String> fieldToName = new HashMap<>();
        for (int i = 0; i < seriesCount; i++) {
            String row = reader.readLine();
            if (row == null) {
                throw new ResCsvFormatException("File ended after " + i + " of " + seriesCount
                    + " series-attribute rows");
            }
            List<String> cells = SourceResCsvFormat.splitCsvLine(row);
            int field = parseFieldIndex(cells, fieldCol, i + 1);
            if (nameCol >= 0 && nameCol < cells.size()) {
                fieldToName.put(field, cells.get(nameCol).trim());
            }
        }

        // --- Lines between the attribute rows and EOH. The last non-blank one is the
        //     ordinary CSV column header (Date, then one token per series). ---
        List<String> headerCandidates = new ArrayList<>();
        boolean sawEoh = false;
        while ((line = reader.readLine()) != null) {
            if (line.trim().equals(SourceResCsvFormat.MARKER_EOH)) {
                sawEoh = true;
                break;
            }
            if (!line.trim().isEmpty()) {
                headerCandidates.add(line);
            }
        }
        if (!sawEoh) {
            throw new ResCsvFormatException("Missing '" + SourceResCsvFormat.MARKER_EOH + "' marker");
        }
        if (headerCandidates.isEmpty()) {
            throw new ResCsvFormatException("No column header row before '"
                + SourceResCsvFormat.MARKER_EOH + "'");
        }

        List<String> columnHeader = SourceResCsvFormat.splitCsvLine(
            headerCandidates.get(headerCandidates.size() - 1));
        List<String> seriesNames = resolveSeriesNames(columnHeader, fieldToName);

        return new SourceResCsvHeader(missingValue, seriesNames);
    }

    /** Scans the config block for the {@code Missing data value} key; NaN if absent. */
    private static double extractMissingValue(List<String> configBlock) {
        for (String raw : configBlock) {
            List<String> cells = SourceResCsvFormat.splitCsvLine(raw);
            if (cells.size() >= 2
                    && cells.get(0).trim().equalsIgnoreCase(SourceResCsvFormat.KEY_MISSING_VALUE)) {
                try {
                    return Double.parseDouble(cells.get(1).trim());
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
        }
        return Double.NaN;
    }

    /** The last non-blank line of {@code block}, split into fields; null if all blank. */
    private static List<String> lastNonBlank(List<String> block) {
        for (int i = block.size() - 1; i >= 0; i--) {
            if (!block.get(i).trim().isEmpty()) {
                return SourceResCsvFormat.splitCsvLine(block.get(i));
            }
        }
        return null;
    }

    /** Case-insensitive column lookup in a header row; {@code fallback} if not present. */
    private static int indexOfColumn(List<String> header, String name, int fallback) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return fallback;
    }

    /** The {@code Field} integer for an attribute row, falling back to its 1-based position. */
    private static int parseFieldIndex(List<String> cells, int fieldCol, int positional) {
        if (fieldCol >= 0 && fieldCol < cells.size()) {
            try {
                return Integer.parseInt(cells.get(fieldCol).trim());
            } catch (NumberFormatException ignored) {
                // fall through to positional
            }
        }
        return positional;
    }

    /**
     * Maps each data column (header tokens after the first "Date" token) to a series name.
     * Each token looks like {@code <index>>Site>Element}; the leading integer links it to a
     * {@code Field} in the attribute table. The attribute {@code Name} is preferred; the raw
     * token is the fallback.
     */
    private static List<String> resolveSeriesNames(List<String> columnHeader,
                                                   Map<Integer, String> fieldToName) {
        List<String> names = new ArrayList<>();
        for (int col = 1; col < columnHeader.size(); col++) {
            String token = columnHeader.get(col).trim();
            String resolved = null;

            int gt = token.indexOf('>');
            String indexPart = (gt >= 0) ? token.substring(0, gt).trim() : token;
            try {
                int field = Integer.parseInt(indexPart);
                resolved = fieldToName.get(field);
            } catch (NumberFormatException ignored) {
                // not an indexed token — fall back to the raw token below
            }

            names.add((resolved != null && !resolved.isEmpty()) ? resolved : token);
        }
        return names;
    }
}
