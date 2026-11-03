package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.utils.TimeFormatUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The table a plot's data makes: what Save Data writes as CSV and what the Data page
 * shows. Built once from a display set, its plot type and the labels, it holds the one
 * set of rules for the table's shape and spelling — which series are columns and in what
 * order, how their headers read, how the first column is headed and each of its cells
 * written, how a value cell is written, and which rows exist — so the file and the page
 * cannot disagree.
 *
 * <p>Columns are the set's series in insertion order, headed by the ref's projected label;
 * a second column with a header already used is headed "label (2)", never dropped. Rows
 * are the distinct timestamps across the series, ascending. The first column is the
 * percentile for an exceedance plot, else the date, headed "datetime" when any row carries
 * a time of day and "date" otherwise. A value is written in Java's double spelling, and a
 * missing point or a stored NaN as an empty cell.
 *
 * <p>Immutable, and safe to read from any thread.
 */
public final class TimeSeriesTable {

    private final PlotType plotType;
    private final List<TimeSeriesData> series;
    private final List<String> headers;
    private final long stepSeconds;
    // Worked out on first use: it scans every timestamp, and a caller comparing headers
    // need not pay for it. Benign if two threads race to it: the answer is one string.
    private String firstHeader;

    /**
     * @param dataSet the series to tabulate, in the order they were added
     * @param plotType what the series' timestamps mean; null means {@link PlotType#VALUES}
     * @param labels projects each series' ref to its column header; null uses the ref's text
     */
    public TimeSeriesTable(DataSet dataSet, PlotType plotType, LabelResolver labels) {
        this.plotType = plotType != null ? plotType : PlotType.VALUES;
        List<TimeSeriesData> columns = new ArrayList<>();
        List<String> columnHeaders = new ArrayList<>();
        Map<String, Integer> usesOfLabel = new HashMap<>();
        for (SeriesRef ref : dataSet.getSeriesRefs()) {
            TimeSeriesData data = dataSet.getSeries(ref);
            if (data == null) {
                continue;
            }
            columns.add(data);
            String label = labels != null ? labels.labelFor(ref) : String.valueOf(ref);
            int uses = usesOfLabel.merge(label, 1, Integer::sum);
            columnHeaders.add(uses == 1 ? label : label + " (" + uses + ")");
        }
        this.series = List.copyOf(columns);
        this.headers = List.copyOf(columnHeaders);
        this.stepSeconds = CsvExportUtil.inferStepSeconds(series);
    }

    /** Whether the table has no columns. */
    public boolean isEmpty() {
        return series.isEmpty();
    }

    public PlotType plotType() {
        return plotType;
    }

    /** The series behind each column, in column order. */
    public List<TimeSeriesData> series() {
        return series;
    }

    /** Each series column's header, in column order. */
    public List<String> headers() {
        return headers;
    }

    /** The step driving the first column's date format: the first regular series' step, else 0 (date-only). */
    public long stepSeconds() {
        return stepSeconds;
    }

    /** The first column's heading: "percentile", "datetime" or "date". */
    public String firstHeader() {
        String header = firstHeader;
        if (header == null) {
            header = plotType == PlotType.EXCEEDANCE ? "percentile"
                : anyRowNeedsTime() ? "datetime" : "date";
            firstHeader = header;
        }
        return header;
    }

    /** The first column's cell for a row's timestamp: the percentile, else the date or datetime. */
    public String firstCell(long timestamp) {
        return plotType == PlotType.EXCEEDANCE ? formatPercentile(timestamp)
            : TimeFormatUtil.formatForStepSize(timestamp, stepSeconds);
    }

    /** A value cell: empty for NaN (missing or stored), else Java's double spelling. */
    public static String valueCell(double value) {
        return Double.isNaN(value) ? "" : Double.toString(value);
    }

    /**
     * Every row's timestamp, ascending: one per distinct timestamp across the series.
     *
     * <p>Sized to the longest series, which is the fewest rows there can be, and grown
     * only when series on different grids add rows. Sizing to the sum of the series'
     * points would allocate the table's whole area for aligned series: ten series of
     * 3.65M points is 290 MB for 29 MB of rows.
     */
    public long[] rowTimes() {
        int longest = 0;
        for (TimeSeriesData data : series) {
            longest = Math.max(longest, data.getPointCount());
        }
        long[][] times = {new long[longest]};
        int[] count = {0};
        try {
            CsvExportUtil.forEachMergedRow(series, (timestamp, values) -> {
                if (count[0] == times[0].length) {
                    times[0] = Arrays.copyOf(times[0], times[0].length + (times[0].length >> 1) + 1);
                }
                times[0][count[0]++] = timestamp;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e); // unreachable: the consumer does no I/O
        }
        return count[0] == times[0].length ? times[0] : Arrays.copyOf(times[0], count[0]);
    }

    /** An exceedance series' fake timestamp as its percentile text ("12.34"). */
    private static String formatPercentile(long timestamp) {
        return String.format(Locale.ROOT, "%.2f", (double) timestamp / PlotTypeTransformer.PERCENTILE_SCALE);
    }

    /**
     * Whether any row carries a time of day. Rows are formatted one by one, so series on
     * different grids (midnight and 9am daily, say) mix bare dates and datetimes in one
     * column; the heading then says "datetime". A sub-daily step answers on the first
     * timestamp.
     */
    private boolean anyRowNeedsTime() {
        for (TimeSeriesData data : series) {
            for (long timestamp : data.getTimestamps()) {
                if (TimeFormatUtil.needsTimeOfDay(timestamp, stepSeconds)) {
                    return true;
                }
            }
        }
        return false;
    }
}
