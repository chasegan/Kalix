package com.kalix.ide.dataview;

import javax.swing.table.AbstractTableModel;

/**
 * The table projection of a {@link PixieDataSession}: date column first, then
 * one column per decoded series, rows over the union time index. Read-only —
 * the editable artifact is the {@code .pxt} manifest in the text editor; this
 * is the honest rendering of the binary half.
 *
 * <p>Construct on the EDT (it registers a session listener).
 */
public final class PixieTableModel extends AbstractTableModel {

    private final PixieDataSession session;

    public PixieTableModel(PixieDataSession session) {
        this.session = session;
        session.addListener(this::fireTableStructureChanged);
    }

    @Override
    public int getRowCount() {
        return session.rowCount();
    }

    @Override
    public int getColumnCount() {
        int series = session.seriesCount();
        return series == 0 ? 0 : series + 1;
    }

    @Override
    public String getColumnName(int column) {
        return column == 0 ? "Date" : session.seriesName(column - 1);
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return columnIndex == 0 ? session.dateText(rowIndex) : session.cellText(rowIndex, columnIndex - 1);
    }
}
