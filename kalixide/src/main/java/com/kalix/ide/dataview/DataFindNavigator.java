package com.kalix.ide.dataview;

import java.util.List;

/**
 * Pure navigation arithmetic for the unified Find: merges the session's data
 * matches with the header-name matches (the Columns scope) into one cycle —
 * header columns first (virtual row {@code -1}), then data cells in
 * (row, column) order — and picks the landing for a direction, honouring
 * wrap. Kept free of Swing so the ordering rules are directly testable.
 */
final class DataFindNavigator {

    /**
     * Where a find step lands. {@code row == -1} means a column header;
     * ordinals and the total span headers + cells. {@code nearestDate} marks
     * the no-exact-match fallback onto the first row at or after a date query.
     */
    record Landing(long row, int column, int ordinal, int total, boolean wrapped, boolean nearestDate) {
    }

    private DataFindNavigator() {
    }

    /**
     * @param headerCols matching header model columns, ascending
     * @param fromRow    virtual from-row: {@code -1} = a header landing,
     *                   {@code -2} = before everything, {@code Long.MAX_VALUE}
     *                   = after everything
     * @return the landing, or {@code null} for an honest miss
     */
    static Landing choose(DataViewSession.FindScan scan, List<Integer> headerCols,
                          long fromRow, int fromColumn, boolean forward, boolean wrap) {
        int total = scan.total() + headerCols.size();

        Integer headerNext = null;
        Integer headerPrev = null;
        for (int i = 0; i < headerCols.size(); i++) {
            int h = headerCols.get(i);
            boolean after = fromRow < -1 || (fromRow == -1 && h > fromColumn);
            boolean before = fromRow > -1 || (fromRow == -1 && h < fromColumn);
            if (after && headerNext == null) {
                headerNext = i;
            }
            if (before) {
                headerPrev = i;
            }
        }

        Landing landing;
        if (forward) {
            if (headerNext != null) {
                landing = header(headerCols, headerNext, total, false);
            } else if (scan.firstAfter() != null) {
                landing = cell(scan.firstAfter(), headerCols.size(), total, false);
            } else if (wrap && !headerCols.isEmpty()) {
                landing = header(headerCols, 0, total, true);
            } else if (wrap && scan.firstOverall() != null) {
                landing = cell(scan.firstOverall(), headerCols.size(), total, true);
            } else {
                landing = null;
            }
        } else {
            if (scan.lastBefore() != null) {
                landing = cell(scan.lastBefore(), headerCols.size(), total, false);
            } else if (headerPrev != null) {
                landing = header(headerCols, headerPrev, total, false);
            } else if (wrap && scan.lastOverall() != null) {
                landing = cell(scan.lastOverall(), headerCols.size(), total, true);
            } else if (wrap && !headerCols.isEmpty()) {
                landing = header(headerCols, headerCols.size() - 1, total, true);
            } else {
                landing = null;
            }
        }

        if (landing == null && scan.nearestDateRow() >= 0) {
            // A date query with no exact hit lands on the nearest later date
            // rather than a dead miss — the old "Find date" jump, preserved.
            landing = new Landing(scan.nearestDateRow(), 0, 0, total, false, true);
        }
        return landing;
    }

    private static Landing header(List<Integer> headerCols, int index, int total, boolean wrapped) {
        return new Landing(-1, headerCols.get(index), index + 1, total, wrapped, false);
    }

    private static Landing cell(DataViewSession.CellRef ref, int headerCount, int total, boolean wrapped) {
        return new Landing(ref.row(), ref.column(), headerCount + ref.ordinal(), total, wrapped, false);
    }
}
