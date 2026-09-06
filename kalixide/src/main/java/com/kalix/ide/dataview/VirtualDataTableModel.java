package com.kalix.ide.dataview;

import javax.swing.table.AbstractTableModel;

/**
 * The table projection of a {@link DataViewSession}: a read-only virtual
 * {@code TableModel} whose cells are fetched on demand. {@code getValueAt} for
 * an unloaded block returns {@code null} (rendered as an empty placeholder
 * cell) and schedules the background fetch; the block's arrival fires a row
 * update and the cells fill in. The EDT never waits on I/O.
 *
 * <p>Grows live while indexing runs: throttled progress events append rows, so
 * the scrollbar extends as the file streams in. When the dialect has a header
 * row it is presented as column names, not as row 0.
 *
 * <p>Construct on the EDT (it registers a session listener and snapshots the
 * current counts).
 */
public final class VirtualDataTableModel extends AbstractTableModel {

    private final DataViewSession session;
    private final int headerOffset;

    private int knownRowCount;
    private int knownColumnCount;

    public VirtualDataTableModel(DataViewSession session) {
        this.session = session;
        this.headerOffset = session.dialect().hasHeaderRow() ? 1 : 0;
        this.knownRowCount = viewRowCount();
        this.knownColumnCount = session.columnCount();
        session.addListener(new DataViewSession.Listener() {
            @Override
            public void onProgress(long rows, long lines, long indexedBytes, long totalBytes, boolean complete) {
                growTo(viewRowCount());
            }

            @Override
            public void onStructureKnown() {
                knownColumnCount = session.columnCount();
                growTo(viewRowCount());
                fireTableStructureChanged();
            }

            @Override
            public void onRowBlockLoaded(long firstRow, int count) {
                int first = (int) Math.max(0, Math.min(firstRow - headerOffset, Integer.MAX_VALUE));
                int last = (int) Math.min((long) knownRowCount - 1, firstRow - headerOffset + count - 1);
                if (last >= first && knownRowCount > 0) {
                    fireTableRowsUpdated(first, last);
                }
            }
        });
    }

    private int viewRowCount() {
        return (int) Math.max(0, Math.min(session.rowCount() - headerOffset, Integer.MAX_VALUE));
    }

    private void growTo(int newCount) {
        if (newCount > knownRowCount) {
            int firstNew = knownRowCount;
            knownRowCount = newCount;
            fireTableRowsInserted(firstNew, newCount - 1);
        }
    }

    @Override
    public int getRowCount() {
        return knownRowCount;
    }

    @Override
    public int getColumnCount() {
        return knownColumnCount;
    }

    @Override
    public String getColumnName(int column) {
        String[] header = session.headerRow();
        if (header != null && column < header.length && !header[column].isBlank()) {
            return header[column];
        }
        return "C" + (column + 1);
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false; // V1 is a viewer; editing arrives as an overlay later
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        long fileRow = (long) rowIndex + headerOffset;
        String[] row = session.rowIfLoaded(fileRow);
        if (row == null) {
            session.requestRow(fileRow); // placeholder now, repaint on arrival
            return null;
        }
        return columnIndex < row.length ? row[columnIndex] : null;
    }
}
