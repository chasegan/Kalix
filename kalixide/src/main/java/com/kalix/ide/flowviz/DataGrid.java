package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.transform.XAxisType;
import com.kalix.ide.dataview.DataFind;
import com.kalix.ide.dataview.FindableData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.TimeSeriesTable;

/**
 * One immutable snapshot of a tab's display data: what the Data page shows, what
 * Find scans, and what the plot's Save Data writes. It is a {@link TimeSeriesTable}
 * with its rows laid out for a {@code JTable} and for Find, so the page and the file
 * cannot disagree.
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

    static final DataGrid EMPTY = new DataGrid(new TimeSeriesTable(new DataSet(), null, null));

    private final TimeSeriesTable table;
    private final long[] rowTimes;

    private DataGrid(TimeSeriesTable table) {
        this.table = table;
        this.rowTimes = table.rowTimes();
    }

    /** Builds from the table of the plot's display data — the same table "Save Data" writes. */
    static DataGrid of(TimeSeriesTable table) {
        return table.isEmpty() ? EMPTY : new DataGrid(table);
    }

    int rowCount() {
        return rowTimes.length;
    }

    int seriesCount() {
        return table.series().size();
    }

    String indexHeader() {
        return table.firstHeader();
    }

    String seriesHeader(int s) {
        return table.headers().get(s);
    }

    String indexText(int row) {
        return table.firstCell(rowTimes[row]);
    }

    String valueText(int row, int s) {
        return TimeSeriesTable.valueCell(valueAt(rowTimes[row], table.series().get(s)));
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
     * Parsed-date matching applies only when the index is a date: on a percentile or
     * numeric X axis the index is a number, so it matches as text only.
     */
    @Override
    public DataFind.Scan scanForMatches(DataFind.Spec spec, long fromRow, int fromColumn) {
        DataFind.Collector collector = new DataFind.Collector(fromRow, fromColumn);
        String needle = DataFind.foldNeedle(spec);
        boolean datedIndex = table.plotType().xAxisType() == XAxisType.TIME;
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
            for (int s = 0; s < table.series().size(); s++) {
                if (DataFind.textMatches(valueText(row, s), needle, spec)) {
                    collector.offer(row, s + 1);
                }
            }
        }
        return collector.finish();
    }
}
