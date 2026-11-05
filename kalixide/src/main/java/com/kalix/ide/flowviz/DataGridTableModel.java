package com.kalix.ide.flowviz;

import javax.swing.table.AbstractTableModel;

/**
 * The table projection of the current {@link DataGrid}: the index column first,
 * then one column per series. Read-only; EDT only. A refresh swaps in a new grid
 * rather than editing this one, so Find's identity check sees every change.
 */
final class DataGridTableModel extends AbstractTableModel {

    private DataGrid grid = DataGrid.EMPTY;

    DataGrid grid() {
        return grid;
    }

    /** Swaps in {@code grid}; the columns are rebuilt only when the headers change, so their widths survive new data. */
    void setGrid(DataGrid grid) {
        DataGrid old = this.grid;
        this.grid = grid != null ? grid : DataGrid.EMPTY;
        if (sameColumns(old, this.grid)) {
            fireTableDataChanged();
        } else {
            fireTableStructureChanged();
        }
    }

    private static boolean sameColumns(DataGrid a, DataGrid b) {
        if (a.seriesCount() != b.seriesCount() || !a.indexHeader().equals(b.indexHeader())) {
            return false;
        }
        for (int s = 0; s < a.seriesCount(); s++) {
            if (!a.seriesHeader(s).equals(b.seriesHeader(s))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getRowCount() {
        return grid.rowCount();
    }

    @Override
    public int getColumnCount() {
        return grid.seriesCount() == 0 ? 0 : grid.seriesCount() + 1;
    }

    @Override
    public String getColumnName(int column) {
        return column == 0 ? grid.indexHeader() : grid.seriesHeader(column - 1);
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return columnIndex == 0 ? grid.indexText(rowIndex) : grid.valueText(rowIndex, columnIndex - 1);
    }
}
