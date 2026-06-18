package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Imports time series from a Source result CSV ({@code .res.csv}) file.
 *
 * <p>The extended header is parsed by {@link SourceResCsvHeader}; the (potentially very
 * large) data region is then streamed line-by-line so the raw text is never held in memory
 * all at once — only the parsed numeric arrays are retained.</p>
 *
 * <p>Missing-value handling on read: an empty/whitespace cell, an unparseable cell, or a
 * cell equal to the file's declared missing-data sentinel all become {@link Double#NaN}.</p>
 *
 * @see SourceResCsvExporter
 * @see TimeSeriesCsvImporter
 */
public final class SourceResCsvImporter {

    /**
     * Date/time patterns tried (in order) against the first data row to pick one formatter
     * for the whole file. ISO date-only is the common case for Source daily results.
     */
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE
    };

    private SourceResCsvImporter() {
        // Utility class — no instantiation
    }

    /**
     * Result of a {@code .res.csv} import: the parsed series plus any non-fatal warnings and
     * fatal errors. On a fatal error {@link #getSeries()} is empty and {@link #hasErrors()}
     * is true.
     */
    public static final class ResCsvImportResult {
        private final List<NamedSeries> series;
        private final List<String> warnings;
        private final List<String> errors;

        ResCsvImportResult(List<NamedSeries> series, List<String> warnings, List<String> errors) {
            this.series = series;
            this.warnings = warnings;
            this.errors = errors;
        }

        public List<NamedSeries> getSeries() { return series; }
        public List<String> getWarnings() { return warnings; }
        public List<String> getErrors() { return errors; }
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }

        /** @return total data points across all imported series. */
        public int getTotalPointCount() {
            return series.stream().mapToInt(s -> s.data().getPointCount()).sum();
        }
    }

    /**
     * Parses a {@code .res.csv} file with no progress reporting or cancellation.
     */
    public static ResCsvImportResult parse(File file) throws IOException {
        return parse(file, null, null);
    }

    /**
     * Parses a {@code .res.csv} file.
     *
     * @param file the file to read
     * @param progress optional sink for 0–100 byte-based progress; may be null
     * @param cancelled optional cancellation check polled per data row; may be null
     * @return the import result (never null)
     * @throws IOException if the file cannot be read
     */
    public static ResCsvImportResult parse(File file, IntConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        long fileLength = Math.max(1, file.length());
        CountingInputStream counter = new CountingInputStream(new FileInputStream(file));

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(counter, StandardCharsets.UTF_8))) {

            SourceResCsvHeader header;
            try {
                header = SourceResCsvHeader.parse(reader);
            } catch (ResCsvFormatException e) {
                errors.add(e.getMessage());
                return new ResCsvImportResult(new ArrayList<>(), warnings, errors);
            }

            int seriesCount = header.getSeriesCount();
            if (seriesCount == 0) {
                errors.add("No data columns found in header");
                return new ResCsvImportResult(new ArrayList<>(), warnings, errors);
            }
            double missingValue = header.getMissingValue();

            List<Long> timestamps = new ArrayList<>();
            List<double[]> rows = new ArrayList<>();
            DateTimeFormatter dateFormat = null;

            int lineNum = 0;          // counts data lines for warning messages
            int lastProgress = -1;
            String dataLine;
            while ((dataLine = reader.readLine()) != null) {
                lineNum++;
                if (cancelled != null && cancelled.getAsBoolean()) {
                    break;
                }
                if (dataLine.trim().isEmpty()) {
                    continue;
                }

                if (progress != null) {
                    int pct = (int) (counter.getCount() * 100 / fileLength);
                    if (pct != lastProgress) {
                        lastProgress = pct;
                        progress.accept(Math.min(100, pct));
                    }
                }

                List<String> cells = SourceResCsvFormat.splitCsvLine(dataLine);

                if (dateFormat == null) {
                    dateFormat = detectDateFormat(cells.get(0).trim());
                    if (dateFormat == null) {
                        errors.add("Could not parse date in first data column: '" + cells.get(0) + "'");
                        return new ResCsvImportResult(new ArrayList<>(), warnings, errors);
                    }
                }

                Long ts = parseTimestamp(cells.get(0).trim(), dateFormat);
                if (ts == null) {
                    warnings.add("Data line " + lineNum + ": could not parse date '" + cells.get(0) + "'");
                    continue;
                }

                double[] values = new double[seriesCount];
                for (int c = 0; c < seriesCount; c++) {
                    int cellIndex = c + 1;
                    String cell = cellIndex < cells.size() ? cells.get(cellIndex) : "";
                    values[c] = parseValue(cell, missingValue);
                }
                timestamps.add(ts);
                rows.add(values);
            }

            if (rows.isEmpty()) {
                errors.add("No data rows found after header");
                return new ResCsvImportResult(new ArrayList<>(), warnings, errors);
            }

            List<NamedSeries> series = buildSeries(header.getSeriesNames(), timestamps, rows, warnings);
            if (progress != null) {
                progress.accept(100);
            }
            return new ResCsvImportResult(series, warnings, errors);
        }
    }

    /**
     * Transposes the accumulated row-major data into one {@link TimeSeriesData} per series.
     */
    private static List<NamedSeries> buildSeries(List<String> names, List<Long> timestamps,
                                                 List<double[]> rows, List<String> warnings) {
        int rowCount = rows.size();
        int seriesCount = names.size();

        long[] ts = new long[rowCount];
        for (int r = 0; r < rowCount; r++) {
            ts[r] = timestamps.get(r);
        }

        List<NamedSeries> series = new ArrayList<>(seriesCount);
        for (int c = 0; c < seriesCount; c++) {
            double[] column = new double[rowCount];
            for (int r = 0; r < rowCount; r++) {
                column[r] = rows.get(r)[c];
            }
            String name = names.get(c);
            if (name == null || name.isEmpty()) {
                name = "Series " + (c + 1);
            }
            TimeSeriesData data = new TimeSeriesData(ts, column);
            series.add(new NamedSeries(name, data));

            if (data.getValidPointCount() == null || data.getValidPointCount() == 0) {
                warnings.add("Series '" + name + "' contains no valid data points");
            }
        }
        return series;
    }

    /** Reads a cell as a double; empty, unparseable, or sentinel-valued cells become NaN. */
    private static double parseValue(String cell, double missingValue) {
        if (cell == null) {
            return Double.NaN;
        }
        String s = cell.trim();
        if (s.isEmpty()) {
            return Double.NaN;
        }
        double value;
        try {
            value = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (!Double.isNaN(missingValue) && value == missingValue) {
            return Double.NaN;
        }
        return value;
    }

    /** Picks the first formatter that parses {@code dateString}, or null if none do. */
    private static DateTimeFormatter detectDateFormat(String dateString) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            if (parseTimestamp(dateString, formatter) != null) {
                return formatter;
            }
        }
        return null;
    }

    /** Parses {@code dateString} to epoch millis (UTC), trying date-time then date-only. */
    private static Long parseTimestamp(String dateString, DateTimeFormatter formatter) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateString, formatter).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDate.parse(dateString, formatter)
                    .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** Tracks how many bytes have been consumed, for byte-based progress reporting. */
    private static final class CountingInputStream extends FilterInputStream {
        private volatile long count = 0;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long getCount() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    /**
     * SwingWorker task wrapping {@link #parse} for background import with progress reporting,
     * mirroring {@link TimeSeriesCsvImporter.CsvImportTask}. Subclasses override the
     * SwingWorker lifecycle methods ({@code process}, {@code done}).
     */
    public abstract static class ResCsvImportTask extends SwingWorker<ResCsvImportResult, Integer> {
        protected final File file;

        public ResCsvImportTask(File file) {
            this.file = file;
        }

        @Override
        protected ResCsvImportResult doInBackground() throws Exception {
            return parse(file, this::setProgress, this::isCancelled);
        }
    }
}
