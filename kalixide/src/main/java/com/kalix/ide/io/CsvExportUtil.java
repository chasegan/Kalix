package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.io.IOException;
import java.util.List;

/**
 * Shared helpers for the CSV exporters ({@link TimeSeriesCsvExporter},
 * {@link SourceResCsvExporter}).
 *
 * <p>The centrepiece is {@link #forEachMergedRow}: a k-way merge over the (already
 * sorted) timestamp arrays of every series, visiting each distinct timestamp exactly
 * once in chronological order with that row's values. It runs in O(total points ×
 * series count) with no per-row allocation — replacing the previous
 * TreeSet-of-boxed-Longs union plus per-cell lookup (per manifestos/performance.md §1,
 * fast by default).</p>
 */
final class CsvExportUtil {

    private CsvExportUtil() {
        // Utility class — no instantiation
    }

    /** Receives one merged output row per distinct timestamp. */
    @FunctionalInterface
    interface RowConsumer {
        /**
         * @param timestamp the row's timestamp (epoch millis)
         * @param values one value per series, in series order; {@link Double#NaN} where a
         *               series has no valid point at this timestamp. The array is reused
         *               between rows — copy it if it must outlive the call.
         */
        void accept(long timestamp, double[] values) throws IOException;
    }

    /**
     * Merges all series into chronological rows and feeds them to {@code consumer}.
     *
     * <p>Each series contributes its value at a timestamp only where it has a valid
     * (non-NaN, finite) point there; otherwise the slot is NaN. If a series carries
     * duplicate timestamps, the first valid value among the duplicates wins and the
     * timestamp still yields a single row — matching the historical
     * collect-unique-timestamps-then-look-up behaviour.</p>
     */
    static void forEachMergedRow(List<TimeSeriesData> allSeries, RowConsumer consumer)
            throws IOException {
        int n = allSeries.size();
        long[][] timestamps = new long[n][];
        double[][] values = new double[n][];
        boolean[][] valid = new boolean[n][];
        int[] cursor = new int[n];
        for (int i = 0; i < n; i++) {
            TimeSeriesData series = allSeries.get(i);
            timestamps[i] = series.getTimestamps();
            values[i] = series.getValues();
            valid[i] = series.getValidPoints();
        }

        double[] row = new double[n];
        while (true) {
            // Next row's timestamp = minimum over the series cursors (k is small; a
            // linear scan beats a heap here).
            long min = Long.MAX_VALUE;
            boolean any = false;
            for (int i = 0; i < n; i++) {
                if (cursor[i] < timestamps[i].length && timestamps[i][cursor[i]] < min) {
                    min = timestamps[i][cursor[i]];
                    any = true;
                }
            }
            if (!any) {
                return;
            }

            for (int i = 0; i < n; i++) {
                row[i] = Double.NaN;
                long[] ts = timestamps[i];
                int c = cursor[i];
                while (c < ts.length && ts[c] == min) {
                    if (valid[i][c] && Double.isNaN(row[i])) {
                        row[i] = values[i][c];
                    }
                    c++;
                }
                cursor[i] = c;
            }
            consumer.accept(min, row);
        }
    }

    /**
     * The step (seconds) of the first regular-interval series, or 0 if none — drives
     * date-only vs. ISO-datetime formatting of the timestamp column.
     */
    static long inferStepSeconds(List<TimeSeriesData> allSeries) {
        for (TimeSeriesData series : allSeries) {
            if (series.hasRegularInterval()) {
                return series.getIntervalMillis() / 1000;
            }
        }
        return 0;
    }

    /**
     * Escapes a field for safe CSV output: fields containing a comma, quote, or newline
     * are wrapped in double quotes, with internal quotes doubled.
     */
    static String escapeCsvField(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
