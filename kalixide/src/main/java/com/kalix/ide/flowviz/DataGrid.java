package com.kalix.ide.flowviz;

import com.kalix.ide.dataview.DataFind;
import com.kalix.ide.dataview.FindableData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.io.TimeSeriesCsvExporter;

import java.util.List;

/**
 * One immutable snapshot of a tab's display data: what the Data page shows, what
 * Find scans, and what the plot's Save Data writes. Every cell is spelled by the
 * CSV exporter's shared helpers, so the table and the file cannot disagree.
 *
 * <p>Replaced wholesale on every refresh, never mutated: the find controller's
 * staleness check is identity, so a scan started against an old grid is dropped.
 * Safe to read from any thread.
 *
 * <p>Rows are the export's own merged timestamps; cells are looked up by
 * binary search rather than held densely (millions of rows times a few series would
 * be hundreds of megabytes). A missing point and a stored NaN both render blank, as
 * the export writes them.
 */
final class DataGrid implements FindableData {

    static final DataGrid EMPTY = new DataGrid(PlotType.VALUES, 0, "date", List.of(), List.of(), new long[0]);

    private final PlotType plotType;
    private final long stepSeconds;
    private final String indexHeader;
    private final List<String> seriesHeaders;
    private final List<TimeSeriesData> series;
    private final long[] rowTimes;

    private DataGrid(PlotType plotType, long stepSeconds, String indexHeader,
                     List<String> seriesHeaders, List<TimeSeriesData> series, long[] rowTimes) {
        this.plotType = plotType;
        this.stepSeconds = stepSeconds;
        this.indexHeader = indexHeader;
        this.seriesHeaders = seriesHeaders;
        this.series = series;
        this.rowTimes = rowTimes;
    }

    /** Builds from the plot's display data — the same DataSet "Save Data" exports. */
    static DataGrid of(DataSet display, PlotType plotType, LabelResolver labels) {
        if (display == null || display.isEmpty()) {
            return EMPTY;
        }
        List<TimeSeriesData> series = List.copyOf(TimeSeriesCsvExporter.dataColumnSeries(display));
        List<String> headers = List.copyOf(TimeSeriesCsvExporter.dataColumnHeaders(display, labels));
        long step = TimeSeriesCsvExporter.dataStepSeconds(series);
        return new DataGrid(plotType, step, TimeSeriesCsvExporter.dataFirstHeader(plotType, series, step),
            headers, series, TimeSeriesCsvExporter.dataRowTimes(series));
    }

    int rowCount() {
        return rowTimes.length;
    }

    int seriesCount() {
        return series.size();
    }

    String indexHeader() {
        return indexHeader;
    }

    String seriesHeader(int s) {
        return seriesHeaders.get(s);
    }

    String indexText(int row) {
        return TimeSeriesCsvExporter.dataFirstCell(plotType, rowTimes[row], stepSeconds);
    }

    String valueText(int row, int s) {
        return TimeSeriesCsvExporter.dataValueCell(valueAt(rowTimes[row], series.get(s)));
    }

    /** The first valid value at the timestamp (the export's duplicate rule), else NaN. */
    private static double valueAt(long timestamp, TimeSeriesData data) {
        long[] times = data.getTimestamps();
        boolean[] valid = data.getValidPoints();
        int low = 0;
        int high = times.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (times[mid] < timestamp) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        for (int i = low; i < times.length && times[i] == timestamp; i++) {
            if (valid[i]) {
                return data.getValues()[i];
            }
        }
        return Double.NaN;
    }

    /** Scan rows are view rows: the header is the table's, not a data row. */
    @Override
    public long headerRowOffset() {
        return 0;
    }

    /**
     * One pass over the grid matching cell text exactly as the table shows it.
     * Parsed-date matching applies only when the index is a date: exceedance and
     * double-mass timestamps are scaled numbers, so they match as text only.
     */
    @Override
    public DataFind.Scan scanForMatches(DataFind.Spec spec, long fromRow, int fromColumn) {
        DataFind.Collector collector = new DataFind.Collector(fromRow, fromColumn);
        String needle = DataFind.foldNeedle(spec);
        boolean datedIndex = switch (plotType) {
            case EXCEEDANCE, DOUBLE_MASS -> false;
            case VALUES, CUMULATIVE, DIFFERENCE, CUMULATIVE_DIFFERENCE, RESIDUAL_MASS -> true;
        };
        for (int row = 0; row < rowTimes.length; row++) {
            long timestamp = rowTimes[row];
            if (datedIndex && spec.dateMillis() != null && timestamp >= spec.dateMillis()) {
                collector.offerNearestDate(row);
            }
            if (spec.inDates()
                    && (DataFind.textMatches(indexText(row), needle, spec)
                        || (datedIndex && DataFind.dateMatches(timestamp, spec)))) {
                collector.offer(row, 0);
            }
            if (!spec.inValues()) {
                continue;
            }
            for (int s = 0; s < series.size(); s++) {
                if (DataFind.textMatches(valueText(row, s), needle, spec)) {
                    collector.offer(row, s + 1);
                }
            }
        }
        return collector.finish();
    }
}
