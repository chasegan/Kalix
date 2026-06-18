package com.kalix.ide.io;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared constants and helpers for the Source "result" CSV format ({@code .res.csv}).
 *
 * <p>A {@code .res.csv} file produced by eWater Source has an extended header before the
 * familiar date-plus-columns CSV body. The header is divided by three marker lines:</p>
 *
 * <pre>
 *   File version,3                       ┐ file metadata (key,value) — the parser honours
 *   Missing data value,-9999             ┘ the missing-data sentinel declared here
 *   EOM                                  ◄ end of file metadata
 *   Project name,...                     ┐ provenance metadata (key,value); varies by version
 *   Source version,...                   │
 *   Simulation time,...                  │
 *   Field,Units,RunName,...,Name,...     ◄ schema row for the series-attribute table (EOC-1)
 *   EOC                                  ◄ end of the configuration block
 *   77                                   ◄ series count N (EOC+1)
 *   1,ML,Latest Run,...,<Name>,...       ┐ N series-attribute rows (EOC+2 ..)
 *   ...                                  ┘
 *   Date,1&gt;Site&gt;Element,2&gt;...        ◄ ordinary CSV column header (Date + N columns)
 *   EOH                                  ◄ end of header
 *   1889-01-01,0,0,1012.82,...           ┐ data rows: date column + N value columns
 * </pre>
 *
 * <p>The format is marker-driven rather than offset-driven — the preamble varies between
 * format versions, so readers locate {@code EOC}/{@code EOH} rather than assuming fixed
 * line numbers.</p>
 */
public final class SourceResCsvFormat {

    /** The double extension that identifies this format. */
    public static final String EXTENSION = ".res.csv";

    /** Marker terminating the file-metadata block. */
    public static final String MARKER_EOM = "EOM";
    /** Marker terminating the configuration block; the next line is the series count. */
    public static final String MARKER_EOC = "EOC";
    /** Marker terminating the header; data rows follow immediately. */
    public static final String MARKER_EOH = "EOH";

    /** Preamble key whose value is the missing-data sentinel (e.g. {@code -9999}). */
    public static final String KEY_MISSING_VALUE = "Missing data value";
    /** Preamble key whose value is the integer format version. */
    public static final String KEY_FILE_VERSION = "File version";

    /** Series-attribute column holding the integer series index (links table row to data column). */
    public static final String ATTR_FIELD = "Field";
    /** Series-attribute column holding the descriptive series name used as the plot label. */
    public static final String ATTR_NAME = "Name";

    /** Sentinel the writer declares (and never emits — NaN is written as an empty cell). */
    public static final double DEFAULT_MISSING_VALUE = -9999.0;

    private SourceResCsvFormat() {
        // Utility class — no instantiation
    }

    /**
     * @return true if {@code fileName} carries the {@code .res.csv} double extension.
     */
    public static boolean isResCsv(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(EXTENSION);
    }

    /**
     * Splits one CSV line into fields, honouring double-quoted segments (a quote toggles
     * quoting; an embedded comma inside quotes is literal). Trailing empty fields are kept,
     * so a line ending in {@code ,} yields a final empty field.
     */
    public static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
