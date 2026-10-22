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

    void setGrid(DataGrid grid) {
        this.grid = grid != null ? grid : DataGrid.EMPTY;
        fireTableStructureChanged();
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
