package com.kalix.gui.io;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Utility class for exporting time series data to CSV format.
 *
 * <p>This class provides functionality to export {@link DataSet} objects containing
 * multiple time series to CSV files. The resulting CSV format includes:</p>
 *
 * <ul>
 *   <li>First column: "Datetime" with timestamps in ISO format</li>
 *   <li>Subsequent columns: One column per time series with series names as headers</li>
 *   <li>Missing values (NaN) are represented as empty cells</li>
 *   <li>Datetime formatting adapts based on precision (date-only vs. date-time)</li>
 * </ul>
 *
 * <p><strong>CSV Format Example:</strong></p>
 * <pre>
 * Datetime,Series1,Series2,Series3
 * 2023-01-01,10.5,20.1,
 * 2023-01-01T12:30:00,11.2,21.3,15.8
 * 2023-01-02,12.0,,16.2
 * </pre>
 *
 * <p><strong>Thread Safety:</strong> This class is stateless and thread-safe.</p>
 *
 * @author Claude Code Assistant
 * @since 1.0
 * @see DataSet
 * @see TimeSeriesData
 */
public class TimeSeriesCsvExporter {

    /**
     * Date formatter for dates without time components (whole days at midnight).
     * Format: "yyyy-MM-dd"
     */
    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * DateTime formatter for dates with time components.
     * Format: "yyyy-MM-dd'T'HH:mm:ss"
     */
    private static final DateTimeFormatter DATETIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private TimeSeriesCsvExporter() {
        // Utility class - no instantiation
    }

    /**
     * Exports a dataset to a CSV file.
     *
     * <p>This method creates a CSV file with all unique timestamps from all series
     * in the dataset. Each row represents a unique timestamp, and each column
     * represents a time series. Missing values are represented as empty cells.</p>
     *
     * <p>The datetime column uses adaptive formatting:</p>
     * <ul>
     *   <li>Whole days (midnight, 00:00:00): "yyyy-MM-dd"</li>
     *   <li>Times with hours/minutes/seconds: "yyyy-MM-dd'T'HH:mm:ss"</li>
     * </ul>
     *
     * @param dataSet the dataset to export; must not be null
     * @param outputFile the target CSV file; will be created or overwritten
     * @throws IOException if an I/O error occurs while writing the file
     * @throws IllegalArgumentException if dataSet is null or empty
     *
     * @see #exportDataToCsv(DataSet, File)
     */
    public static void export(DataSet dataSet, File outputFile) throws IOException {
        if (dataSet == null) {
            throw new IllegalArgumentException("DataSet cannot be null");
        }
        if (dataSet.isEmpty()) {
            throw new IllegalArgumentException("DataSet cannot be empty");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("Output file cannot be null");
        }

        exportDataToCsv(dataSet, outputFile);
    }

    /**
     * Performs the actual CSV export operation.
     *
     * <p>This method collects all unique timestamps across all series, then
     * writes them in chronological order with corresponding values for each series.
     * The algorithm ensures that all data points are included even if series
     * have different timestamp sets.</p>
     *
     * @param dataSet the dataset to export
     * @param file the output file
     * @throws IOException if writing fails
     */
    private static void exportDataToCsv(DataSet dataSet, File file) throws IOException {
        List<TimeSeriesData> allSeries = dataSet.getAllSeries();
        if (allSeries.isEmpty()) {
            return;
        }

        // Collect all unique timestamps across all series
        Set<Long> allTimestamps = collectAllTimestamps(allSeries);

        try (FileWriter writer = new FileWriter(file)) {
            writeHeader(writer, allSeries);
            writeDataRows(writer, allSeries, allTimestamps);
        }
    }

    /**
     * Collects all unique timestamps from all time series in the dataset.
     *
     * <p>Uses a {@link TreeSet} to automatically sort timestamps in chronological
     * order while eliminating duplicates.</p>
     *
     * @param allSeries list of all time series data
     * @return sorted set of unique timestamps
     */
    private static Set<Long> collectAllTimestamps(List<TimeSeriesData> allSeries) {
        Set<Long> allTimestamps = new TreeSet<>();
        for (TimeSeriesData series : allSeries) {
            for (long timestamp : series.getTimestamps()) {
                allTimestamps.add(timestamp);
            }
        }
        return allTimestamps;
    }

    /**
     * Writes the CSV header row with column names.
     *
     * <p>The header includes "Datetime" as the first column, followed by
     * each time series name. Series names are properly escaped for CSV format.</p>
     *
     * @param writer the file writer
     * @param allSeries list of all time series data
     * @throws IOException if writing fails
     */
    private static void writeHeader(FileWriter writer, List<TimeSeriesData> allSeries)
            throws IOException {
        writer.write("Datetime");
        for (TimeSeriesData series : allSeries) {
            writer.write(",");
            writer.write(escapeCsvField(series.getName()));
        }
        writer.write("\n");
    }

    /**
     * Writes all data rows to the CSV file.
     *
     * <p>Each row corresponds to one unique timestamp. For each timestamp,
     * the method looks up the corresponding value in each time series.
     * If a series doesn't have data for that timestamp, an empty cell is written.</p>
     *
     * @param writer the file writer
     * @param allSeries list of all time series data
     * @param allTimestamps sorted set of all unique timestamps
     * @throws IOException if writing fails
     */
    private static void writeDataRows(FileWriter writer, List<TimeSeriesData> allSeries,
                                    Set<Long> allTimestamps) throws IOException {
        for (Long timestamp : allTimestamps) {
            // Format datetime
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
            String dateTimeStr = formatDateTime(dateTime);
            writer.write(dateTimeStr);

            // Write values for each series
            for (TimeSeriesData series : allSeries) {
                writer.write(",");
                Double value = getValueAtTimestamp(series, timestamp);
                if (value != null && !Double.isNaN(value)) {
                    writer.write(String.valueOf(value));
                }
                // For NaN/missing values, write empty cell (nothing)
            }
            writer.write("\n");
        }
    }

    /**
     * Formats a LocalDateTime for CSV output using adaptive precision.
     *
     * <p>The formatting logic:</p>
     * <ul>
     *   <li>If the time is exactly midnight (00:00:00.000): returns date only</li>
     *   <li>Otherwise: returns full date and time</li>
     * </ul>
     *
     * @param dateTime the datetime to format
     * @return formatted datetime string suitable for CSV
     */
    private static String formatDateTime(LocalDateTime dateTime) {
        // Check if it's a whole day (midnight with no time component)
        if (dateTime.getHour() == 0 && dateTime.getMinute() == 0 &&
            dateTime.getSecond() == 0 && dateTime.getNano() == 0) {
            return dateTime.format(DATE_FORMATTER);
        } else {
            return dateTime.format(DATETIME_FORMATTER);
        }
    }

    /**
     * Escapes a field for safe CSV output.
     *
     * <p>Fields containing commas, quotes, or newlines are wrapped in double quotes.
     * Internal quotes are escaped by doubling them ("" becomes """").</p>
     *
     * @param field the field to escape
     * @return escaped field safe for CSV output
     */
    private static String escapeCsvField(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /**
     * Retrieves the value of a time series at a specific timestamp.
     *
     * <p>This method searches through the time series data to find a value
     * that matches the given timestamp exactly. Only valid (non-NaN) data
     * points are returned.</p>
     *
     * @param series the time series to search
     * @param timestamp the target timestamp
     * @return the value at the timestamp, or null if not found or invalid
     */
    private static Double getValueAtTimestamp(TimeSeriesData series, long timestamp) {
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();

        for (int i = 0; i < timestamps.length; i++) {
            if (timestamps[i] == timestamp && validPoints[i]) {
                return values[i];
            }
        }
        return null; // Not found or invalid
    }

    /**
     * Configuration class for CSV export options.
     *
     * <p>This class can be extended in the future to provide customization
     * options such as custom date formats, delimiter characters, or
     * missing value representations.</p>
     *
     * @since 1.0
     */
    public static class ExportOptions {
        // Future expansion for export customization
        // e.g., custom date formats, delimiters, missing value representations

        /**
         * Creates default export options.
         */
        public ExportOptions() {
            // Default configuration
        }
    }
}