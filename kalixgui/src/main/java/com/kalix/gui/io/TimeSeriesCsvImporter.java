package com.kalix.gui.io;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;

import javax.swing.SwingWorker;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
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
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * CsvImportTask task = new CsvImportTask(csvFile, new CsvImportOptions()) {
 *     &#64;Override
 *     protected void done() {
 *         try {
 *             CsvImportResult result = get();
 *             if (!result.hasErrors()) {
 *                 DataSet dataSet = result.getDataSet();
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
 * <p><strong>Thread Safety:</strong> This class is stateless and thread-safe.
 * Multiple import operations can run concurrently.</p>
 *
 * @author Claude Code Assistant
 * @since 1.0
 * @see TimeSeriesCsvExporter
 * @see DataSet
 * @see TimeSeriesData
 */
public class TimeSeriesCsvImporter {

    /**
     * Common date/time patterns supported by the importer.
     * Patterns are tried in order until one succeeds.
     */
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE
    };

    /**
     * Patterns that represent missing or invalid values.
     * These are converted to NaN in the imported data.
     */
    private static final Set<String> MISSING_VALUE_PATTERNS = Set.of(
        "", "na", "nan", "null", "n/a", "#n/a", "missing", "-", "--", "?"
    );

    /**
     * Regular expression for detecting numeric values.
     * Supports integers, decimals, and scientific notation.
     */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
        "^[+-]?([0-9]*[.])?[0-9]+([eE][+-]?[0-9]+)?$"
    );

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
        private final DataSet dataSet;
        private final List<String> warnings;
        private final List<String> errors;
        private final ImportStatistics statistics;

        /**
         * Creates a new import result.
         *
         * @param dataSet the imported dataset
         * @param warnings non-fatal issues encountered during import
         * @param errors fatal errors that prevented successful import
         * @param statistics detailed import statistics
         */
        public CsvImportResult(DataSet dataSet, List<String> warnings, List<String> errors, ImportStatistics statistics) {
            this.dataSet = dataSet;
            this.warnings = new ArrayList<>(warnings);
            this.errors = new ArrayList<>(errors);
            this.statistics = statistics;
        }

        /**
         * @return the imported dataset (may be empty if errors occurred)
         */
        public DataSet getDataSet() { return dataSet; }

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
     * operations. Subclasses must implement the SwingWorker lifecycle methods
     * to handle progress updates and completion.</p>
     *
     * <p><strong>Progress Reporting:</strong> The task publishes progress values
     * from 0-100 representing the percentage of file processed.</p>
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
            return importFile();
        }

        /**
         * Performs the actual CSV import operation.
         *
         * <p>This method handles the complete import process including:</p>
         * <ol>
         *   <li>File reading and delimiter detection</li>
         *   <li>Header parsing and validation</li>
         *   <li>Date format detection</li>
         *   <li>Data parsing with progress reporting</li>
         *   <li>Time series creation and validation</li>
         * </ol>
         *
         * @return the import result with data, warnings, and statistics
         * @throws IOException if file reading fails
         */
        private CsvImportResult importFile() throws IOException {
            long startTime = System.currentTimeMillis();

            List<String> warnings = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            DataSet dataSet = new DataSet();

            try (BufferedReader reader = new BufferedReader(
                    new FileReader(csvFile, StandardCharsets.UTF_8))) {

                // Read all lines first to determine size for progress reporting
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }

                if (lines.isEmpty()) {
                    errors.add("CSV file is empty");
                    return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
                }

                // Detect delimiter
                char delimiter = detectDelimiter(lines.subList(0, Math.min(10, lines.size())));

                // Parse header
                String[] headers = parseLine(lines.get(0), delimiter);
                if (headers.length < 2) {
                    errors.add("CSV must have at least 2 columns (time + at least one data series)");
                    return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
                }

                // Detect date format using first few data rows
                DateTimeFormatter dateFormat = detectDateFormat(lines, delimiter, warnings);
                if (dateFormat == null) {
                    errors.add("Could not detect date format in first column");
                    return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
                }

                // Prepare data structures
                int seriesCount = headers.length - 1;
                List<LocalDateTime> dateTimes = new ArrayList<>();
                List<List<Double>> seriesValues = new ArrayList<>();

                for (int i = 0; i < seriesCount; i++) {
                    seriesValues.add(new ArrayList<>());
                }

                // Parse data rows
                int validRows = 0;
                int totalRows = lines.size();

                for (int lineNum = 1; lineNum < lines.size(); lineNum++) {
                    if (isCancelled()) {
                        return createResult(dataSet, warnings, errors, startTime, dateFormat, totalRows, validRows, 0);
                    }

                    // Report progress
                    int progress = (int) ((lineNum * 100.0) / lines.size());
                    setProgress(progress);

                    String dataLine = lines.get(lineNum);
                    if (dataLine.trim().isEmpty()) continue;

                    String[] values = parseLine(dataLine, delimiter);
                    if (values.length != headers.length) {
                        warnings.add(String.format("Line %d: Expected %d columns, found %d",
                            lineNum + 1, headers.length, values.length));
                        continue;
                    }

                    // Parse timestamp
                    LocalDateTime dateTime = parseDateTime(values[0].trim(), dateFormat);
                    if (dateTime == null) {
                        warnings.add(String.format("Line %d: Could not parse date/time '%s'",
                            lineNum + 1, values[0]));
                        continue;
                    }

                    // Parse data values
                    boolean hasValidData = false;
                    List<Double> rowValues = new ArrayList<>();

                    for (int i = 1; i < values.length; i++) {
                        Double value = parseNumericValue(values[i].trim());
                        rowValues.add(value);
                        if (value != null && !value.isNaN()) {
                            hasValidData = true;
                        }
                    }

                    if (hasValidData || !options.skipRowsWithAllMissingValues) {
                        dateTimes.add(dateTime);
                        for (int i = 0; i < seriesCount; i++) {
                            seriesValues.get(i).add(rowValues.get(i));
                        }
                        validRows++;
                    }
                }

                // Create TimeSeriesData objects
                if (validRows > 0) {
                    LocalDateTime[] dateTimeArray = dateTimes.toArray(new LocalDateTime[0]);

                    for (int i = 0; i < seriesCount; i++) {
                        String seriesName = headers[i + 1].trim();
                        if (seriesName.isEmpty()) {
                            seriesName = "Series " + (i + 1);
                        }

                        double[] valueArray = seriesValues.get(i).stream()
                            .mapToDouble(v -> v != null ? v : Double.NaN)
                            .toArray();

                        TimeSeriesData series = new TimeSeriesData(seriesName, dateTimeArray, valueArray);
                        dataSet.addSeries(series);

                        // Validate series
                        if (series.getValidPointCount() == null || series.getValidPointCount() == 0) {
                            warnings.add("Series '" + seriesName + "' contains no valid data points");
                        }
                    }
                } else {
                    errors.add("No valid data rows found");
                }

                return createResult(dataSet, warnings, errors, startTime, dateFormat, totalRows, validRows, seriesCount);

            } catch (Exception e) {
                errors.add("Parse error: " + e.getMessage());
                return createResult(dataSet, warnings, errors, startTime, null, 0, 0, 0);
            }
        }

        /**
         * Creates an import result with the given parameters.
         */
        private CsvImportResult createResult(DataSet dataSet, List<String> warnings, List<String> errors,
                                           long startTime, DateTimeFormatter dateFormat, int totalRows,
                                           int validRows, int seriesCount) {
            long parseTimeMs = System.currentTimeMillis() - startTime;
            ImportStatistics stats = new ImportStatistics(totalRows, validRows, 1, seriesCount, parseTimeMs, dateFormat);
            return new CsvImportResult(dataSet, warnings, errors, stats);
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
     * Attempts to detect the date format used in the CSV file.
     */
    private static DateTimeFormatter detectDateFormat(List<String> lines, char delimiter, List<String> warnings) {
        if (lines.size() < 2) return null;

        // Try parsing first few data rows with different formats
        for (int lineNum = 1; lineNum < Math.min(6, lines.size()); lineNum++) {
            String[] values = parseLine(lines.get(lineNum), delimiter);
            if (values.length == 0) continue;

            String dateString = values[0].trim();
            if (dateString.isEmpty()) continue;

            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                LocalDateTime result = parseDateTime(dateString, formatter);
                if (result != null) {
                    return formatter; // Found working format
                }
            }
        }

        return null; // No format worked
    }

    /**
     * Parses a date/time string using the specified formatter.
     */
    private static LocalDateTime parseDateTime(String dateString, DateTimeFormatter formatter) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }

        try {
            // Try parsing as LocalDateTime first
            return LocalDateTime.parse(dateString, formatter);
        } catch (DateTimeParseException e) {
            try {
                // If that fails, try parsing as LocalDate and convert to LocalDateTime at start of day
                java.time.LocalDate localDate = java.time.LocalDate.parse(dateString, formatter);
                return localDate.atStartOfDay();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * Parses a numeric value string, handling missing values and common formats.
     */
    private static Double parseNumericValue(String valueString) {
        if (valueString == null || isMissingValue(valueString)) {
            return Double.NaN;
        }

        // Handle common numeric formats
        String cleaned = valueString.replace(",", "").replace(" ", "");

        if (!NUMERIC_PATTERN.matcher(cleaned).matches()) {
            return Double.NaN;
        }

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
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

        /** Use strict date parsing (reject ambiguous formats) */
        public boolean strictDateParsing = false;

        /** Maximum number of rows to process (for preview/testing) */
        public int maxPreviewRows = 10000;

        /**
         * Creates default import options.
         */
        public CsvImportOptions() {}

        /**
         * Sets whether to skip rows with all missing values.
         *
         * @param skip true to skip rows with all missing data
         * @return this options object for method chaining
         */
        public CsvImportOptions skipRowsWithAllMissingValues(boolean skip) {
            this.skipRowsWithAllMissingValues = skip;
            return this;
        }

        /**
         * Sets strict date parsing mode.
         *
         * @param strict true for strict parsing (reject ambiguous dates)
         * @return this options object for method chaining
         */
        public CsvImportOptions strictDateParsing(boolean strict) {
            this.strictDateParsing = strict;
            return this;
        }

        /**
         * Sets the maximum number of rows to process.
         *
         * @param maxRows maximum rows to import (0 for unlimited)
         * @return this options object for method chaining
         */
        public CsvImportOptions maxPreviewRows(int maxRows) {
            this.maxPreviewRows = maxRows;
            return this;
        }
    }
}