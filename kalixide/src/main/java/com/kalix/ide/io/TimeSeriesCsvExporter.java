package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.utils.TimeFormatUtil;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for exporting time series data to CSV format.
 *
 * <p>This class provides functionality to export {@link DataSet} objects containing
 * multiple time series to CSV files. The resulting CSV format includes:</p>
 *
 * <ul>
 *   <li>First column: timestamps, headed "date" when every row is a bare date and
 *       "datetime" when any row carries a time of day, as the engine's output files are</li>
 *   <li>Subsequent columns: One column per time series with series names as headers</li>
 *   <li>Missing values (NaN) are represented as empty cells</li>
 *   <li>Datetime formatting adapts based on precision (date-only vs. date-time)</li>
 * </ul>
 *
 * <p><strong>CSV Format Example:</strong></p>
 * <pre>
 * datetime,Series1,Series2,Series3
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
     * <p>For EXCEEDANCE plot type, the first column is "percentile" with percentages.
     * For other plot types it is the timestamps, headed "date" or "datetime" (see the class
     * doc).</p>
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
        // One column per series, in pool insertion order, headed by the ref's projected
        // label. Two series can share a label (the same derived-series name from two
        // files of the same name, say); a second column with a header already used is
        // headed "label (2)", never dropped.
        List<String> headers = dataColumnHeaders(dataSet, labelResolver);
        List<TimeSeriesData> allSeries = dataColumnSeries(dataSet);
        if (headers.isEmpty()) {
            return;
        }

        long stepSeconds = dataStepSeconds(allSeries);
        String firstHeader = dataFirstHeader(plotType, allSeries, stepSeconds);

        try (Writer writer = openWriter(file)) {
            writeHeader(writer, firstHeader, headers);
            writeDataRows(writer, allSeries, plotType, stepSeconds);
        }
    }

    /**
     * One header per series column, in pool order, headed by the ref's projected label.
     * A label already used is headed "label (2)", never dropped. Shared so other
     * tabular views cannot drift.
     */
    public static List<String> dataColumnHeaders(DataSet dataSet, LabelResolver labelResolver) {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> usesOfLabel = new HashMap<>();
        for (SeriesRef ref : dataSet.getSeriesRefs()) {
            if (dataSet.getSeries(ref) == null) continue;
            String label = labelResolver != null ? labelResolver.labelFor(ref) : String.valueOf(ref);
            int uses = usesOfLabel.merge(label, 1, Integer::sum);
            headers.add(uses == 1 ? label : label + " (" + uses + ")");
        }
        return headers;
    }

    /** The series behind {@link #dataColumnHeaders}, in the same order. */
    public static List<TimeSeriesData> dataColumnSeries(DataSet dataSet) {
        List<TimeSeriesData> allSeries = new ArrayList<>();
        for (SeriesRef ref : dataSet.getSeriesRefs()) {
            TimeSeriesData data = dataSet.getSeries(ref);
            if (data != null) {
                allSeries.add(data);
            }
        }
        return allSeries;
    }

    /**
     * The step driving the first column's date format: the first regular-interval
     * series' step, else 0 (date-only). Shared so other tabular views cannot drift.
     */
    public static long dataStepSeconds(List<TimeSeriesData> allSeries) {
        return CsvExportUtil.inferStepSeconds(allSeries);
    }

    /**
     * Every row's timestamp, ascending, exactly as the export visits them (one per distinct
     * timestamp across the series). Shared so other tabular views cannot drift.
     */
    public static long[] dataRowTimes(List<TimeSeriesData> allSeries) {
        int total = 0;
        for (TimeSeriesData series : allSeries) {
            total += series.getPointCount();
        }
        long[] times = new long[total];
        int[] count = {0};
        try {
            CsvExportUtil.forEachMergedRow(allSeries, (timestamp, values) -> times[count[0]++] = timestamp);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // unreachable: the consumer does no I/O
        }
        return Arrays.copyOf(times, count[0]);
    }

    /** A value cell: empty for NaN (missing or stored), else Java's double spelling. */
    public static String dataValueCell(double value) {
        return Double.isNaN(value) ? "" : Double.toString(value);
    }

    /**
     * The first column's heading: "percentile" for exceedance, else "datetime" when any
     * row carries a time of day, else "date". Shared so other tabular views cannot drift.
     */
    public static String dataFirstHeader(PlotType plotType, List<TimeSeriesData> allSeries, long stepSeconds) {
        return plotType == PlotType.EXCEEDANCE ? "percentile"
            : anyRowNeedsTime(allSeries, stepSeconds) ? "datetime" : "date";
    }

    /**
     * The first column's cell for a row's timestamp: the percentile for exceedance, else the
     * date or datetime. Shared so other tabular views cannot drift.
     */
    public static String dataFirstCell(PlotType plotType, long timestamp, long stepSeconds) {
        return plotType == PlotType.EXCEEDANCE ? formatPercentile(timestamp)
            : TimeFormatUtil.formatForStepSize(timestamp, stepSeconds);
    }

    /** An exceedance series' fake timestamp as its percentile text ("12.34"). */
    private static String formatPercentile(long timestamp) {
        return String.format(Locale.ROOT, "%.2f", (double) timestamp / PlotTypeTransformer.PERCENTILE_SCALE);
    }

    /**
     * UTF-8 writer for the output file; a one-entry zip archive when the name
     * says {@code .csv.zip} — the extension is the single source of truth for
     * the format. Delegates to {@link CsvZipFormat#newUtf8Writer}, the one
     * holder of the entry-name and pinned-timestamp conventions.
     */
    private static Writer openWriter(File file) throws IOException {
        return CsvZipFormat.newUtf8Writer(file);
    }

    /**
     * Whether any row {@link #writeDataRows} will write carries a time of day. Rows are
     * formatted one by one, so series on different grids (midnight and 9am daily, say) mix
     * bare dates and datetimes in one column; the heading then says "datetime". A sub-daily
     * step answers on the first timestamp.
     */
    private static boolean anyRowNeedsTime(List<TimeSeriesData> allSeries, long stepSeconds) {
        for (TimeSeriesData series : allSeries) {
            for (long timestamp : series.getTimestamps()) {
                if (TimeFormatUtil.needsTimeOfDay(timestamp, stepSeconds)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the CSV header row: the first column's heading ("date", "datetime" or
     * "percentile"), then each series name, escaped for CSV.
     */
    private static void writeHeader(Writer writer, String firstHeader, List<String> headers)
            throws IOException {
        writer.write(firstHeader);
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
     * @param plotType the plot type (null for default behavior)
     * @param stepSeconds resolution driving the timestamp format (0 = date-only)
     * @throws IOException if writing fails
     */
    private static void writeDataRows(Writer writer, List<TimeSeriesData> allSeries,
                                      PlotType plotType, long stepSeconds) throws IOException {
        StringBuilder row = new StringBuilder(64);
        CsvExportUtil.forEachMergedRow(allSeries, (timestamp, values) -> {
            row.setLength(0);
            row.append(dataFirstCell(plotType, timestamp, stepSeconds));

            for (double value : values) {
                row.append(',').append(dataValueCell(value));
            }
            writer.write(row.append('\n').toString());
        });
    }
}
