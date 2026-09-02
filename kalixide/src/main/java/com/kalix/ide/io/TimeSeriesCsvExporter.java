package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.utils.TimeFormatUtil;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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
 * <p><strong>Performance:</strong> rows are produced by a k-way merge over the series'
 * sorted timestamp arrays ({@link CsvExportUtil#forEachMergedRow}) — O(total points)
 * overall, no per-row allocation, no per-cell search. Output is UTF-8.</p>
 *
 * <p><strong>Thread Safety:</strong> This class is stateless and thread-safe.</p>
 *
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
     * @see #exportDataToCsv(DataSet, File, PlotType, LabelResolver)
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
     * <p>Rows are visited in chronological order by merging the series' sorted
     * timestamp arrays; every data point is included even if series have different
     * timestamp sets (a series without a point at a row's timestamp gets an empty
     * cell).</p>
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

        boolean isExceedance = (plotType == PlotType.EXCEEDANCE);

        // Pick a single date format for the whole file based on the resolution of the data.
        // Sub-daily series get ISO datetime; daily-or-coarser get date-only. Inferred from the
        // first regular-interval series; falls back to date-only if none is regular.
        long stepSeconds = CsvExportUtil.inferStepSeconds(allSeries);

        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writeHeader(writer, headers, isExceedance);
            writeDataRows(writer, allSeries, isExceedance, stepSeconds);
        }
    }

    /**
     * Writes the CSV header row with column names.
     *
     * <p>The header includes "Datetime" (or "Percentile" for exceedance) as the first column,
     * followed by each time series name. Series names are properly escaped for CSV format.</p>
     */
    private static void writeHeader(Writer writer, List<String> headers, boolean isExceedance)
            throws IOException {
        writer.write(isExceedance ? "Percentile" : "Datetime");
        for (String header : headers) {
            writer.write(",");
            writer.write(CsvExportUtil.escapeCsvField(header));
        }
        writer.write("\n");
    }

    /**
     * Writes all data rows to the CSV file.
     *
     * <p>Each row corresponds to one unique timestamp, produced by the k-way merge in
     * {@link CsvExportUtil#forEachMergedRow}. A series without a valid point at a row's
     * timestamp gets an empty cell.</p>
     *
     * @param writer the file writer
     * @param allSeries list of all time series data
     * @param isExceedance true if this is exceedance data (format first column as percentile)
     * @param stepSeconds resolution driving the timestamp format (0 = date-only)
     * @throws IOException if writing fails
     */
    private static void writeDataRows(Writer writer, List<TimeSeriesData> allSeries,
                                      boolean isExceedance, long stepSeconds) throws IOException {
        StringBuilder row = new StringBuilder(64);
        CsvExportUtil.forEachMergedRow(allSeries, (timestamp, values) -> {
            row.setLength(0);
            // Format first column based on plot type
            if (isExceedance) {
                // Convert fake timestamp to percentile
                double percentile = (double) timestamp / com.kalix.ide.flowviz.transform.PlotTypeTransformer.PERCENTILE_SCALE;
                row.append(String.format(java.util.Locale.ROOT, "%.2f", percentile));
            } else {
                row.append(TimeFormatUtil.formatForStepSize(timestamp, stepSeconds));
            }

            // Write values for each series; NaN/missing → empty cell
            for (double value : values) {
                row.append(',');
                if (!Double.isNaN(value)) {
                    row.append(value);
                }
            }
            writer.write(row.append('\n').toString());
        });
    }
}
