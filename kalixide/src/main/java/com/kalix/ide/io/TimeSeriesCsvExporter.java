package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.utils.TimeFormatUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * @see #exportDataToCsv(DataSet, File, PlotType)
     */
    public static void export(DataSet dataSet, File outputFile) throws IOException {
        export(dataSet, outputFile, null, null);
    }

    /**
     * Exports a dataset to a CSV file with plot type awareness.
     *
     * <p>For EXCEEDANCE plot type, the first column will be "Percentile" with percentages.
     * For other plot types, the first column will be "Datetime" with timestamps.</p>
     *
     * @param dataSet the dataset to export; must not be null
     * @param outputFile the target CSV file; will be created or overwritten
     * @param plotType the plot type (null or VALUES for standard temporal export)
     * @throws IOException if an I/O error occurs while writing the file
     * @throws IllegalArgumentException if dataSet is null or empty
     */
    public static void export(DataSet dataSet, File outputFile, PlotType plotType) throws IOException {
        export(dataSet, outputFile, plotType, null);
    }

    /**
     * Exports a dataset to a CSV file, with plot-type awareness and an optional
     * {@link LabelResolver} for projecting ref-keyed series identity to column headers.
     *
     * <p>The dataset may carry data under either the legacy named-series API or the
     * ref-keyed API (or both during the migration). Ref-keyed series get their column
     * header via {@code labelResolver.labelFor(ref)} when a resolver is supplied,
     * otherwise from {@code ref.toString()}. Legacy named series use their {@code name}
     * field.</p>
     *
     * @param dataSet the dataset to export; must not be null
     * @param outputFile the target CSV file; will be created or overwritten
     * @param plotType the plot type (null or VALUES for standard temporal export)
     * @param labelResolver projects {@link SeriesRef} → column header; may be null
     * @throws IOException if an I/O error occurs while writing the file
     * @throws IllegalArgumentException if dataSet is null or empty
     */
    public static void export(DataSet dataSet, File outputFile, PlotType plotType,
                              LabelResolver labelResolver) throws IOException {
        if (dataSet == null) {
            throw new IllegalArgumentException("DataSet cannot be null");
        }
        if (dataSet.isEmpty()) {
            throw new IllegalArgumentException("DataSet cannot be empty");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("Output file cannot be null");
        }

        exportDataToCsv(dataSet, outputFile, plotType, labelResolver);
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
     * @param plotType the plot type (null for default behavior)
     * @throws IOException if writing fails
     */
    private static void exportDataToCsv(DataSet dataSet, File file, PlotType plotType,
                                        LabelResolver labelResolver) throws IOException {
        // Build (header → data) pairs in pool insertion order. The header is the ref's
        // projected label.
        LinkedHashMap<String, TimeSeriesData> labeled = new LinkedHashMap<>();
        for (SeriesRef ref : dataSet.getSeriesRefs()) {
            TimeSeriesData data = dataSet.getSeries(ref);
            if (data == null) continue;
            String label = labelResolver != null ? labelResolver.labelFor(ref) : String.valueOf(ref);
            labeled.put(label, data);
        }
        if (labeled.isEmpty()) {
            return;
        }

        List<String> headers = new ArrayList<>(labeled.keySet());
        List<TimeSeriesData> allSeries = new ArrayList<>(labeled.values());

        // Collect all unique timestamps across all series
        Set<Long> allTimestamps = collectAllTimestamps(allSeries);

        boolean isExceedance = (plotType == PlotType.EXCEEDANCE);

        // Pick a single date format for the whole file based on the resolution of the data.
        // Sub-daily series get ISO datetime; daily-or-coarser get date-only. Inferred from the
        // first regular-interval series; falls back to date-only if none is regular.
        long stepSeconds = inferStepSeconds(allSeries);

        try (FileWriter writer = new FileWriter(file)) {
            writeHeader(writer, headers, isExceedance);
            writeDataRows(writer, allSeries, allTimestamps, isExceedance, stepSeconds);
        }
    }

    private static long inferStepSeconds(List<TimeSeriesData> allSeries) {
        for (TimeSeriesData series : allSeries) {
            if (series.hasRegularInterval()) {
                return series.getIntervalMillis() / 1000;
            }
        }
        return 0;
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
     * <p>The header includes "Datetime" (or "Percentile" for exceedance) as the first column,
     * followed by each time series name. Series names are properly escaped for CSV format.</p>
     *
     * @param writer the file writer
     * @param allSeries list of all time series data
     * @param isExceedance true if this is exceedance data
     * @throws IOException if writing fails
     */
    private static void writeHeader(FileWriter writer, List<String> headers, boolean isExceedance)
            throws IOException {
        writer.write(isExceedance ? "Percentile" : "Datetime");
        for (String header : headers) {
            writer.write(",");
            writer.write(escapeCsvField(header));
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
     * @param isExceedance true if this is exceedance data (format first column as percentile)
     * @throws IOException if writing fails
     */
    private static void writeDataRows(FileWriter writer, List<TimeSeriesData> allSeries,
                                    Set<Long> allTimestamps, boolean isExceedance,
                                    long stepSeconds) throws IOException {
        for (Long timestamp : allTimestamps) {
            // Format first column based on plot type
            if (isExceedance) {
                // Convert fake timestamp to percentile
                double percentile = timestamp / 1_000_000.0;
                writer.write(String.format("%.2f", percentile));
            } else {
                writer.write(TimeFormatUtil.formatForStepSize(timestamp, stepSeconds));
            }

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
}