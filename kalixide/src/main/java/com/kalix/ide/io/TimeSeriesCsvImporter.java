package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * Utility class for importing time series data from CSV files.
 *
 * <p>This class provides comprehensive CSV import functionality including:</p>
 * <ul>
 *   <li>Automatic delimiter detection (comma, semicolon, tab, pipe)</li>
 *   <li>Flexible date/time format detection and parsing</li>
 *   <li>Robust handling of missing values and malformed data</li>
 *   <li>Progress reporting for large files via SwingWorker</li>
 *   <li>Detailed error reporting and parsing statistics</li>
 *   <li>Support for multiple concurrent import operations</li>
 * </ul>
 *
 * <p><strong>CSV Format Requirements:</strong></p>
 * <ul>
 *   <li>First column: Date/time values (various formats supported)</li>
 *   <li>Remaining columns: Numeric time series data</li>
 *   <li>First row: Column headers (optional but recommended)</li>
 *   <li>Missing values: "", "na", "nan", "null", etc. (converted to NaN)</li>
 * </ul>
 *
 * <p><strong>Supported Date Formats:</strong></p>
 * <ul>
 *   <li>ISO formats: "2023-01-15", "2023-01-15T14:30:00"</li>
 *   <li>US formats: "01/15/2023", "01/15/2023 14:30:00"</li>
 *   <li>European formats: "15/01/2023", "15/01/2023 14:30:00"</li>
 *   <li>And many other common variations</li>
 * </ul>
 *
 * <p><strong>Performance:</strong> the file is streamed line-by-line (never held in
 * memory whole) with byte-based progress from a {@link CountingInputStream}; values
 * accumulate into growable primitive {@code double[]} columns (no boxing, no per-row
 * lists); and the date format — including whether it is date-only or date-time — is
 * detected once up front so steady-state row parsing throws no exceptions.</p>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * CsvImportTask task = new CsvImportTask(csvFile, new CsvImportOptions()) {
 *     &#64;Override
 *     protected void done() {
 *         try {
 *             CsvImportResult result = get();
 *             if (!result.hasErrors()) {
 *                 List&lt;NamedSeries&gt; series = result.getSeries();
 *                 // Process imported data...
 *             }
 *         } catch (Exception e) {
 *             // Handle import error...
 *         }
 *     }
 * };
 * task.execute();
 * </pre>
 *
 * <p>For headless/direct use (tests, batch tools), call
 * {@link #parse(File, CsvImportOptions, IntConsumer, BooleanSupplier)} — the SwingWorker
 * task is a thin wrapper around it.</p>
 *
 * <p><strong>Thread Safety:</strong> This class is stateless and thread-safe.
 * Multiple import operations can run concurrently.</p>
 *
 * @since 1.0
 * @see TimeSeriesCsvExporter
 * @see DataSet
 * @see TimeSeriesData
 */
public class TimeSeriesCsvImporter {

    /**
     * Common date/time patterns supported by the importer.
     * Patterns are tried in order until one succeeds.
     * Non-standard month-first formats (M/d/yyyy) are demoted to the end.
     *
     * <p>Day/month fields use single-letter tokens ({@code d}, {@code M}) rather
     * than {@code dd}/{@code MM} so that both zero-padded ("01/06/2007") and
     * unpadded ("1/06/2007") values parse — Java's parser treats {@code dd} as
     * requiring exactly two digits, which rejects single-digit days.</p>
     */
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-M-d HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("d/M/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("d/M/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("M/d/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("M/d/yyyy")
    };

    /**
     * Patterns that represent missing or invalid values.
     * These are converted to NaN in the imported data.
     */
    private static final Set<String> MISSING_VALUE_PATTERNS = Set.of(
        "", "na", "nan", "null", "n/a", "#n/a", "missing", "-", "--", "?"
    );

    /**
     * Commas are accepted in numbers only as complete thousands groupings
     * ("1,234,567.89"). See {@link #parseNumericValue}.
     */
    private static final Pattern THOUSANDS_GROUPED_PATTERN =
        Pattern.compile("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?([eE][+-]?\\d+)?");

    /** Number of leading lines sampled for delimiter and date-format detection. */
    private static final int SAMPLE_LINES = 10;

    /** Sentinel for "could not parse timestamp" from {@link #parseTimestampMillis}. */
    private static final long INVALID_TS = Long.MIN_VALUE;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private TimeSeriesCsvImporter() {
        // Utility class - no instantiation
    }

    /**
     * Result of a CSV import operation.
     *
     * <p>Contains the imported dataset, any warnings or errors that occurred
     * during parsing, and detailed statistics about the import process.</p>
     */
    public static class CsvImportResult {
        private final List<NamedSeries> series;
        private final List<String> warnings;
        private final List<String> errors;
        private final ImportStatistics statistics;

        /**
         * Creates a new import result.
         *
         * @param series the imported series (name + nameless data); empty if errors occurred
         * @param warnings non-fatal issues encountered during import
         * @param errors fatal errors that prevented successful import
         * @param statistics detailed import statistics
         */
        public CsvImportResult(List<NamedSeries> series, List<String> warnings, List<String> errors, ImportStatistics statistics) {
            this.series = new ArrayList<>(series);
            this.warnings = new ArrayList<>(warnings);
            this.errors = new ArrayList<>(errors);
            this.statistics = statistics;
        }

        /**
         * @return the imported series, each pairing the CSV column name with its data
         */
        public List<NamedSeries> getSeries() { return series; }

        /**
         * @return total data points across all imported series
         */
        public int getTotalPointCount() {
            return series.stream().mapToInt(s -> s.data().getPointCount()).sum();
        }

        /**
         * @return list of non-fatal warnings encountered during import
         */
        public List<String> getWarnings() { return warnings; }

        /**
         * @return list of fatal errors that prevented successful import
         */
        public List<String> getErrors() { return errors; }

        /**
         * @return detailed statistics about the import process
         */
        public ImportStatistics getStatistics() { return statistics; }

        /**
         * @return true if any fatal errors occurred during import
         */
        public boolean hasErrors() { return !errors.isEmpty(); }

        /**
         * @return true if any warnings were generated during import
         */
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    /**
     * Statistics collected during the CSV import process.
     *
     * <p>Provides detailed information about the import operation including
     * performance metrics and data quality indicators.</p>
     */
    public static class ImportStatistics {
        private final int totalRows;
        private final int validRows;
        private final int headerRows;
        private final int seriesCount;
        private final long parseTimeMs;
        private final DateTimeFormatter detectedDateFormat;

        /**
         * Creates new import statistics.
         *
         * @param totalRows total number of rows processed
         * @param validRows number of rows with valid data
         * @param headerRows number of header rows (typically 1)
         * @param seriesCount number of time series imported
         * @param parseTimeMs time taken to parse the file (milliseconds)
         * @param detectedDateFormat the date format that was auto-detected
         */
        public ImportStatistics(int totalRows, int validRows, int headerRows, int seriesCount,
                              long parseTimeMs, DateTimeFormatter detectedDateFormat) {
            this.totalRows = totalRows;
            this.validRows = validRows;
            this.headerRows = headerRows;
            this.seriesCount = seriesCount;
            this.parseTimeMs = parseTimeMs;
            this.detectedDateFormat = detectedDateFormat;
        }

        /** @return total number of rows processed */
        public int getTotalRows() { return totalRows; }

        /** @return number of rows with valid data */
        public int getValidRows() { return validRows; }

        /** @return number of header rows */
        public int getHeaderRows() { return headerRows; }

        /** @return number of time series imported */
        public int getSeriesCount() { return seriesCount; }

        /** @return parse time in milliseconds */
        public long getParseTimeMs() { return parseTimeMs; }

        /** @return the auto-detected date format */
        public DateTimeFormatter getDetectedDateFormat() { return detectedDateFormat; }
    }

    /**
     * SwingWorker task for importing CSV data with progress reporting.
     *
     * <p>This abstract class provides the framework for background CSV import
     * operations — a thin wrapper around
     * {@link #parse(File, CsvImportOptions, IntConsumer, BooleanSupplier)}. Subclasses
     * implement the SwingWorker lifecycle methods to handle progress updates and
     * completion.</p>
     *
     * <p><strong>Progress Reporting:</strong> The task publishes progress values
     * from 0-100 representing the percentage of file bytes processed.</p>
     */
    public static abstract class CsvImportTask extends SwingWorker<CsvImportResult, Integer> {
        /** The CSV file to import */
        protected final File csvFile;
        /** Import options and configuration */
        protected final CsvImportOptions options;

        /**
         * Creates a new CSV import task.
         *
         * @param csvFile the CSV file to import
         * @param options import options (null for defaults)
         */
        public CsvImportTask(File csvFile, CsvImportOptions options) {
            this.csvFile = csvFile;
            this.options = options != null ? options : new CsvImportOptions();
        }

        @Override
        protected CsvImportResult doInBackground() throws Exception {
            return parse(csvFile, options, this::setProgress, this::isCancelled);
        }
    }

    /**
     * Parses a CSV file with no progress reporting or cancellation.
     *
     * @param csvFile the CSV file to import
     * @param options import options (null for defaults)
     * @return the import result (never null; fatal problems are reported via
     *         {@link CsvImportResult#getErrors()} rather than thrown)
     */
    public static CsvImportResult parse(File csvFile, CsvImportOptions options) {
        return parse(csvFile, options, null, null);
    }

    /**
     * Parses a CSV file.
     *
     * <p>This method handles the complete import process including:</p>
     * <ol>
     *   <li>Delimiter detection from a sample of leading lines</li>
     *   <li>Header parsing and validation</li>
     *   <li>Date format detection (formatter + date-only vs. date-time)</li>
     *   <li>Streaming data parse with byte-based progress reporting</li>
     *   <li>Time series creation and validation</li>
     * </ol>
     *
     * @param csvFile the CSV file to import
     * @param options import options (null for defaults)
     * @param progress optional sink for 0–100 byte-based progress; may be null
     * @param cancelled optional cancellation check polled per data row; may be null
     * @return the import result (never null; fatal problems — including I/O failures —
     *         are reported via {@link CsvImportResult#getErrors()} rather than thrown)
     */
    public static CsvImportResult parse(File csvFile, CsvImportOptions options,
                                        IntConsumer progress, BooleanSupplier cancelled) {
        long startTime = System.currentTimeMillis();
        CsvImportOptions opts = options != null ? options : new CsvImportOptions();

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<NamedSeries> series = new ArrayList<>();

        long fileLength = Math.max(1, csvFile.length());

        try {
            CountingInputStream counter = new CountingInputStream(new FileInputStream(csvFile));
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(counter, StandardCharsets.UTF_8))) {

                // Sample the leading lines once for delimiter + date-format detection,
                // then stream the rest — the file is never held in memory whole.
                List<String> sample = new ArrayList<>(SAMPLE_LINES);
                String line;
                while (sample.size() < SAMPLE_LINES && (line = reader.readLine()) != null) {
                    sample.add(line);
                }

                if (sample.isEmpty()) {
                    errors.add("CSV file is empty");
                    return createResult(series, warnings, errors, startTime, null, 0, 0, 0);
                }

                // Detect delimiter
                char delimiter = detectDelimiter(sample);

                // Parse header
                String[] headers = parseLine(sample.get(0), delimiter);
                if (headers.length < 2) {
                    errors.add("CSV must have at least 2 columns (time + at least one data series)");
                    return createResult(series, warnings, errors, startTime, null, 0, 0, 0);
                }

                // Detect date format — the formatter AND whether it is date-only — once,
                // using the sampled data rows, so per-row parsing never relies on
                // exception-driven fallback in the steady state.
                DateFormatSpec dateFormat = detectDateFormat(sample, delimiter);
                if (dateFormat == null) {
                    errors.add("Could not detect date format in first column");
                    return createResult(series, warnings, errors, startTime, null, 0, 0, 0);
                }

                // Prepare primitive column accumulators (no boxing, no per-row lists)
                int seriesCount = headers.length - 1;
                ColumnAccumulator columns = new ColumnAccumulator(seriesCount);
                double[] rowValues = new double[seriesCount];

                // Parse data rows: first the remaining sampled lines, then the stream
                int validRows = 0;
                int totalRows = 1;   // header line
                int sampleIdx = 1;
                int lastProgress = -1;

                while (true) {
                    String dataLine;
                    if (sampleIdx < sample.size()) {
                        dataLine = sample.get(sampleIdx++);
                    } else {
                        dataLine = reader.readLine();
                        if (dataLine == null) break;
                    }
                    int fileLine = ++totalRows;  // 1-based physical line number

                    if (cancelled != null && cancelled.getAsBoolean()) {
                        return createResult(series, warnings, errors, startTime,
                            dateFormat.formatter(), totalRows, validRows, 0);
                    }

                    if (progress != null) {
                        int pct = (int) (counter.getCount() * 100 / fileLength);
                        if (pct != lastProgress) {
                            lastProgress = pct;
                            progress.accept(Math.min(100, pct));
                        }
                    }

                    if (dataLine.isBlank()) continue;

                    String[] values = parseLine(dataLine, delimiter);
                    if (values.length != headers.length) {
                        warnings.add(String.format("Line %d: Expected %d columns, found %d",
                            fileLine, headers.length, values.length));
                        continue;
                    }

                    // Parse timestamp
                    long timestamp = parseTimestampMillis(values[0].trim(), dateFormat);
                    if (timestamp == INVALID_TS) {
                        warnings.add(String.format("Line %d: Could not parse date/time '%s'",
                            fileLine, values[0]));
                        continue;
                    }

                    // Parse data values
                    boolean hasValidData = false;
                    for (int i = 1; i < values.length; i++) {
                        double value = parseNumericValue(values[i].trim());
                        rowValues[i - 1] = value;
                        if (!Double.isNaN(value)) {
                            hasValidData = true;
                        }
                    }

                    if (hasValidData || !opts.skipRowsWithAllMissingValues) {
                        columns.add(timestamp, rowValues);
                        validRows++;
                    }
                }

                // Create TimeSeriesData objects
                if (validRows > 0) {
                    long[] timestampArray = columns.timestamps();

                    for (int i = 0; i < seriesCount; i++) {
                        String seriesName = headers[i + 1].trim();
                        if (seriesName.isEmpty()) {
                            seriesName = "Series " + (i + 1);
                        }

                        TimeSeriesData data = new TimeSeriesData(timestampArray, columns.column(i));
                        // Split dotted column headers (e.g. "node.x.dsflow" from a saved run)
                        // into hierarchy segments so reloaded result CSVs nest like the run.
                        series.add(NamedSeries.dotted(seriesName, data));

                        // Validate series
                        if (data.getValidPointCount() == null || data.getValidPointCount() == 0) {
                            warnings.add("Series '" + seriesName + "' contains no valid data points");
                        }
                    }
                } else {
                    errors.add("No valid data rows found");
                }

                if (progress != null) {
                    progress.accept(100);
                }
                return createResult(series, warnings, errors, startTime,
                    dateFormat.formatter(), totalRows, validRows, seriesCount);
            }
        } catch (Exception e) {
            errors.add("Parse error: " + e.getMessage());
            return createResult(series, warnings, errors, startTime, null, 0, 0, 0);
        }
    }

    /**
     * Creates an import result with the given parameters.
     */
    private static CsvImportResult createResult(List<NamedSeries> series, List<String> warnings, List<String> errors,
                                                long startTime, DateTimeFormatter dateFormat, int totalRows,
                                                int validRows, int seriesCount) {
        long parseTimeMs = System.currentTimeMillis() - startTime;
        ImportStatistics stats = new ImportStatistics(totalRows, validRows, 1, seriesCount, parseTimeMs, dateFormat);
        return new CsvImportResult(series, warnings, errors, stats);
    }

    /**
     * Accumulates parsed rows into one growable primitive array per column —
     * timestamps plus one {@code double[]} per series. All columns grow in lockstep,
     * so a single size/capacity pair covers them.
     */
    private static final class ColumnAccumulator {
        private long[] timestamps;
        private final double[][] columns;
        private int size;

        ColumnAccumulator(int seriesCount) {
            int initialCapacity = 1024;
            timestamps = new long[initialCapacity];
            columns = new double[seriesCount][];
            for (int i = 0; i < seriesCount; i++) {
                columns[i] = new double[initialCapacity];
            }
        }

        void add(long timestamp, double[] rowValues) {
            if (size == timestamps.length) {
                int newCapacity = timestamps.length + (timestamps.length >> 1);
                timestamps = Arrays.copyOf(timestamps, newCapacity);
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = Arrays.copyOf(columns[i], newCapacity);
                }
            }
            timestamps[size] = timestamp;
            for (int i = 0; i < columns.length; i++) {
                columns[i][size] = rowValues[i];
            }
            size++;
        }

        /** @return the accumulated timestamps, trimmed to size */
        long[] timestamps() {
            return Arrays.copyOf(timestamps, size);
        }

        /** @return column {@code i}'s accumulated values, trimmed to size */
        double[] column(int i) {
            return Arrays.copyOf(columns[i], size);
        }
    }

    /**
     * Detects the most likely CSV delimiter from a sample of lines.
     *
     * @param sampleLines first few lines of the CSV file
     * @return the detected delimiter character
     */
    private static char detectDelimiter(List<String> sampleLines) {
        char[] candidates = {',', ';', '\t', '|'};
        int maxCount = 0;
        char bestDelimiter = ',';

        for (char delimiter : candidates) {
            int totalCount = 0;
            int consistentLines = 0;
            Integer expectedColumns = null;

            for (String line : sampleLines) {
                if (line.trim().isEmpty()) continue;

                int count = countDelimiters(line, delimiter);
                totalCount += count;

                int columns = count + 1;
                if (expectedColumns == null) {
                    expectedColumns = columns;
                    consistentLines = 1;
                } else if (expectedColumns.equals(columns)) {
                    consistentLines++;
                }
            }

            // Prefer delimiter with consistent column count across lines
            int score = totalCount * consistentLines;
            if (score > maxCount) {
                maxCount = score;
                bestDelimiter = delimiter;
            }
        }

        return bestDelimiter;
    }

    /**
     * Counts occurrences of a delimiter character, respecting quoted strings.
     */
    private static int countDelimiters(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                count++;
            }
        }

        return count;
    }

    /**
     * Parses a CSV line into individual fields, handling quoted strings properly.
     */
    private static String[] parseLine(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }

    /**
     * The detected date format: the formatter plus whether values are date-only
     * (parsed via {@link LocalDate}) or full date-times. Capturing date-only-ness at
     * detection time means steady-state parsing takes the right branch directly instead
     * of throwing and catching a {@link DateTimeParseException} on every row of a
     * date-only file (the common daily case).
     */
    private record DateFormatSpec(DateTimeFormatter formatter, boolean dateOnly) {}

    /**
     * Attempts to detect the date format used in the CSV file from sampled lines.
     */
    private static DateFormatSpec detectDateFormat(List<String> sampleLines, char delimiter) {
        if (sampleLines.size() < 2) return null;

        // Try parsing first few data rows with different formats
        for (int lineNum = 1; lineNum < Math.min(6, sampleLines.size()); lineNum++) {
            String[] values = parseLine(sampleLines.get(lineNum), delimiter);
            if (values.length == 0) continue;

            String dateString = values[0].trim();
            if (dateString.isEmpty()) continue;

            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    LocalDateTime.parse(dateString, formatter);
                    return new DateFormatSpec(formatter, false);
                } catch (DateTimeParseException e) {
                    // fall through to date-only probe
                }
                try {
                    LocalDate.parse(dateString, formatter);
                    return new DateFormatSpec(formatter, true);
                } catch (DateTimeParseException e) {
                    // try next formatter
                }
            }
        }

        return null; // No format worked
    }

    /**
     * Parses a date/time string to epoch millis (UTC) using the detected format,
     * taking the date-only or date-time branch directly. The opposite branch is kept
     * as a rare fallback for mixed files; unparseable values yield {@link #INVALID_TS}.
     */
    private static long parseTimestampMillis(String dateString, DateFormatSpec spec) {
        if (dateString.isEmpty()) {
            return INVALID_TS;
        }
        if (spec.dateOnly()) {
            try {
                return LocalDate.parse(dateString, spec.formatter()).toEpochDay() * 86_400_000L;
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(dateString, spec.formatter())
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (DateTimeParseException e2) {
                    return INVALID_TS;
                }
            }
        } else {
            try {
                return LocalDateTime.parse(dateString, spec.formatter())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return LocalDate.parse(dateString, spec.formatter()).toEpochDay() * 86_400_000L;
                } catch (DateTimeParseException e2) {
                    return INVALID_TS;
                }
            }
        }
    }

    /**
     * Parses a numeric value string, handling missing values and common formats.
     * Kalix numbers are dot-decimal everywhere in the world; a comma is treated as a
     * thousands separator only when it forms complete groups ("1,234,567.89").
     * Anything else — notably a decimal comma like {@code 1,5} in a semicolon-delimited
     * European file — is rejected as NaN rather than silently misread ({@code 1,5} must
     * never become 15).
     *
     * <p>Package-private for testing. Returns a primitive: missing or unparseable
     * values are {@link Double#NaN}.</p>
     */
    static double parseNumericValue(String valueString) {
        if (valueString == null || isMissingValue(valueString)) {
            return Double.NaN;
        }

        String cleaned = valueString.replace(" ", "");
        if (cleaned.indexOf(',') >= 0) {
            if (!THOUSANDS_GROUPED_PATTERN.matcher(cleaned).matches()) {
                return Double.NaN;
            }
            cleaned = cleaned.replace(",", "");
        }

        if (!isPlainNumber(cleaned)) {
            return Double.NaN;
        }

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Hand-rolled equivalent of the numeric-format regex
     * {@code ^[+-]?([0-9]*[.])?[0-9]+([eE][+-]?[0-9]+)?$} — integers, decimals, and
     * scientific notation. Called once per data cell, so it avoids the per-call
     * {@code Matcher} machinery on the hot path. Notably (and deliberately, matching
     * the regex) it rejects {@code Double.parseDouble}-isms like "Infinity", "NaN",
     * hex floats, and trailing type suffixes ("1d").
     *
     * <p>Package-private for testing.</p>
     */
    static boolean isPlainNumber(String s) {
        int n = s.length();
        int i = 0;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        // Mantissa: optional digits, optional single dot, then required digits.
        int intDigits = 0;
        while (i < n && isAsciiDigit(s.charAt(i))) {
            i++;
            intDigits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            int fracDigits = 0;
            while (i < n && isAsciiDigit(s.charAt(i))) {
                i++;
                fracDigits++;
            }
            if (fracDigits == 0) {
                return false;   // "1." / "." — digits required after the dot
            }
        } else if (intDigits == 0) {
            return false;       // no digits at all (or bare sign)
        }
        // Optional exponent: e/E, optional sign, required digits.
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int expDigits = 0;
            while (i < n && isAsciiDigit(s.charAt(i))) {
                i++;
                expDigits++;
            }
            if (expDigits == 0) {
                return false;
            }
        }
        return i == n;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Checks if a value string represents a missing value.
     */
    private static boolean isMissingValue(String value) {
        return MISSING_VALUE_PATTERNS.contains(value.toLowerCase().trim());
    }

    /**
     * Configuration options for CSV import operations.
     *
     * <p>Provides customization options for import behavior, data validation,
     * and performance tuning.</p>
     */
    public static class CsvImportOptions {
        /** Skip rows where all data values are missing */
        public boolean skipRowsWithAllMissingValues = true;

        /**
         * Creates default import options.
         */
        public CsvImportOptions() {}
    }
}
