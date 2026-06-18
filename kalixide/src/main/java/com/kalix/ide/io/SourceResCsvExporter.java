package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.utils.TimeFormatUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exports a {@link DataSet} to a Source result CSV ({@code .res.csv}) file.
 *
 * <p>Kalix's data model carries no Source provenance (units, run/scenario names, site
 * identifiers), so the extended header is written generically: structural fields take a
 * {@code None} placeholder and only the series {@code Name} — the resolved series label — is
 * meaningful. The output is a valid {@code .res.csv} that this importer round-trips, not a
 * faithful reproduction of a Source export.</p>
 *
 * <p>Missing values (NaN) are always written as empty cells; the declared
 * {@code Missing data value} sentinel is present for format conformance only.</p>
 *
 * @see SourceResCsvImporter
 */
public final class SourceResCsvExporter {

    private static final String FILE_VERSION = "3";
    private static final String PLACEHOLDER = "None";
    private static final String ATTR_SCHEMA =
        "Field,Units,RunName,ScenarioName,ScenarioInputSetName,Name,Site,ElementName,"
            + "WaterFeatureType,ElementType,Structure,Custom";
    private static final int ATTR_TRAILING_PLACEHOLDERS = 6; // Site … Custom

    private SourceResCsvExporter() {
        // Utility class — no instantiation
    }

    /**
     * Exports a dataset to a {@code .res.csv} file.
     *
     * @param dataSet the dataset to export; must not be null or empty
     * @param outputFile the target file; created or overwritten
     * @param labelResolver projects {@link SeriesRef} → series name; if null, {@code ref.toString()}
     * @throws IOException if writing fails
     * @throws IllegalArgumentException if the dataset is null/empty or the file is null
     */
    public static void export(DataSet dataSet, File outputFile, LabelResolver labelResolver)
            throws IOException {
        if (dataSet == null) {
            throw new IllegalArgumentException("DataSet cannot be null");
        }
        if (dataSet.isEmpty()) {
            throw new IllegalArgumentException("DataSet cannot be empty");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("Output file cannot be null");
        }

        // (name → data) in pool insertion order.
        LinkedHashMap<String, TimeSeriesData> labeled = new LinkedHashMap<>();
        for (SeriesRef ref : dataSet.getSeriesRefs()) {
            TimeSeriesData data = dataSet.getSeries(ref);
            if (data == null) {
                continue;
            }
            String label = labelResolver != null ? labelResolver.labelFor(ref) : String.valueOf(ref);
            labeled.put(label, data);
        }
        if (labeled.isEmpty()) {
            return;
        }

        List<String> names = new ArrayList<>(labeled.keySet());
        List<TimeSeriesData> allSeries = new ArrayList<>(labeled.values());
        Set<Long> allTimestamps = collectAllTimestamps(allSeries);
        long stepSeconds = inferStepSeconds(allSeries);

        try (FileWriter writer = new FileWriter(outputFile)) {
            writeHeader(writer, names, allTimestamps, stepSeconds);
            writeDataRows(writer, names, allSeries, allTimestamps, stepSeconds);
        }
    }

    private static void writeHeader(FileWriter writer, List<String> names,
                                    Set<Long> allTimestamps, long stepSeconds) throws IOException {
        int seriesCount = names.size();

        // File-metadata block.
        writer.write(SourceResCsvFormat.KEY_FILE_VERSION + "," + FILE_VERSION + "\n");
        writer.write(SourceResCsvFormat.KEY_MISSING_VALUE + ","
            + (long) SourceResCsvFormat.DEFAULT_MISSING_VALUE + "\n");
        writer.write(SourceResCsvFormat.MARKER_EOM + "\n");

        // Provenance block (generic) + the series-attribute schema row.
        writer.write("Project name," + PLACEHOLDER + "\n");
        writer.write("Source version,Kalix\n");
        writer.write("Latest result run time," + nowStamp() + "\n");
        writer.write("Simulation time," + simulationRange(allTimestamps, stepSeconds) + "\n");
        writer.write(ATTR_SCHEMA + "\n");
        writer.write(SourceResCsvFormat.MARKER_EOC + "\n");

        // Series count + one attribute row per series.
        writer.write(seriesCount + "\n");
        for (int i = 0; i < seriesCount; i++) {
            StringBuilder row = new StringBuilder();
            row.append(i + 1).append(',')                  // Field
               .append(PLACEHOLDER).append(',')            // Units
               .append(PLACEHOLDER).append(',')            // RunName
               .append(PLACEHOLDER).append(',')            // ScenarioName
               .append(PLACEHOLDER).append(',')            // ScenarioInputSetName
               .append(escapeCsvField(names.get(i)));      // Name
            for (int p = 0; p < ATTR_TRAILING_PLACEHOLDERS; p++) {
                row.append(',').append(PLACEHOLDER);       // Site … Custom
            }
            writer.write(row.append('\n').toString());
        }

        // Ordinary CSV column header: Date + one "<index>><Name>" token per series.
        StringBuilder header = new StringBuilder("Date");
        for (int i = 0; i < seriesCount; i++) {
            header.append(',').append(escapeCsvField((i + 1) + ">" + names.get(i)));
        }
        writer.write(header.append('\n').toString());
        writer.write(SourceResCsvFormat.MARKER_EOH + "\n");
    }

    private static void writeDataRows(FileWriter writer, List<String> names,
                                      List<TimeSeriesData> allSeries, Set<Long> allTimestamps,
                                      long stepSeconds) throws IOException {
        for (Long timestamp : allTimestamps) {
            StringBuilder row = new StringBuilder();
            row.append(TimeFormatUtil.formatForStepSize(timestamp, stepSeconds));
            for (TimeSeriesData series : allSeries) {
                row.append(',');
                Double value = valueAt(series, timestamp);
                if (value != null && !Double.isNaN(value)) {
                    row.append(value);
                }
                // NaN / missing → empty cell
            }
            writer.write(row.append('\n').toString());
        }
    }

    /** Sorted union of every series' timestamps. */
    private static Set<Long> collectAllTimestamps(List<TimeSeriesData> allSeries) {
        Set<Long> all = new TreeSet<>();
        for (TimeSeriesData series : allSeries) {
            for (long ts : series.getTimestamps()) {
                all.add(ts);
            }
        }
        return all;
    }

    /** The step (seconds) of the first regular series, or 0 if none — drives date formatting. */
    private static long inferStepSeconds(List<TimeSeriesData> allSeries) {
        for (TimeSeriesData series : allSeries) {
            if (series.hasRegularInterval()) {
                return series.getIntervalMillis() / 1000;
            }
        }
        return 0;
    }

    /** The value of {@code series} at {@code timestamp} via binary search, or null if absent/invalid. */
    private static Double valueAt(TimeSeriesData series, long timestamp) {
        long[] timestamps = series.getTimestamps();
        int idx = Arrays.binarySearch(timestamps, timestamp);
        if (idx >= 0 && series.getValidPoints()[idx]) {
            return series.getValues()[idx];
        }
        return null;
    }

    private static String simulationRange(Set<Long> allTimestamps, long stepSeconds) {
        if (allTimestamps.isEmpty()) {
            return PLACEHOLDER;
        }
        TreeSet<Long> sorted = (TreeSet<Long>) allTimestamps;
        return TimeFormatUtil.formatForStepSize(sorted.first(), stepSeconds)
            + " - " + TimeFormatUtil.formatForStepSize(sorted.last(), stepSeconds);
    }

    private static String nowStamp() {
        return java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** Quotes a field containing a comma, quote, or newline (Source's CSV dialect). */
    private static String escapeCsvField(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
