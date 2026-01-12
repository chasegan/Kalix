package com.kalix.ide.tableview;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model that handles both VERTICAL and HORIZONTAL orientations
 * for table-based property editing.
 */
public class TableDataModel extends AbstractTableModel {

    private final TablePropertyDefinition definition;
    private final List<String[]> data;
    private final String[] columnNames;

    public TableDataModel(TablePropertyDefinition definition, String[][] initialValues) {
        this.definition = definition;
        this.columnNames = definition.getColumnNames();

        // Convert initial values to mutable list
        this.data = new ArrayList<>();
        for (String[] row : initialValues) {
            data.add(row.clone());
        }
    }

    @Override
    public int getRowCount() {
        return data.size();
    }

    @Override
    public int getColumnCount() {
        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            return 2; // Parameter name + value
        } else {
            return columnNames.length;
        }
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            if (columnIndex == 0) {
                // Parameter name
                String[] rowNames = definition.getRowNames();
                return rowIndex < rowNames.length ? rowNames[rowIndex] : "";
            } else {
                // Value
                return data.get(rowIndex)[0];
            }
        } else {
            return data.get(rowIndex)[columnIndex];
        }
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            if (columnIndex == 1) {
                data.get(rowIndex)[0] = (String) aValue;
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        } else {
            data.get(rowIndex)[columnIndex] = (String) aValue;
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        if (definition.getOrientation() == DisplayOrientation.VERTICAL) {
            return columnIndex == 1; // Only value column is editable
        }
        return true; // All cells editable in HORIZONTAL mode
    }

    public void addRow() {
        int numCols = definition.getOrientation() == DisplayOrientation.VERTICAL
            ? 1
            : definition.getColumnNames().length;
        String[] newRow = new String[numCols];
        for (int i = 0; i < numCols; i++) {
            newRow[i] = "";
        }
        data.add(newRow);
        fireTableRowsInserted(data.size() - 1, data.size() - 1);
    }

    public void insertRow(int rowIndex) {
        int numCols = definition.getOrientation() == DisplayOrientation.VERTICAL
            ? 1
            : definition.getColumnNames().length;
        String[] newRow = new String[numCols];
        for (int i = 0; i < numCols; i++) {
            newRow[i] = "";
        }
        if (rowIndex < 0) {
            rowIndex = 0;
        } else if (rowIndex > data.size()) {
            rowIndex = data.size();
        }
        data.add(rowIndex, newRow);
        fireTableRowsInserted(rowIndex, rowIndex);
    }

    public void removeRow(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < data.size()) {
            data.remove(rowIndex);
            fireTableRowsDeleted(rowIndex, rowIndex);
        }
    }

    /**
     * Gets the data values in the format expected by the definition's formatValues method.
     */
    public String[][] getDataValues() {
        String[][] result = new String[data.size()][];
        for (int i = 0; i < data.size(); i++) {
            result[i] = data.get(i).clone();
        }
        return result;
    }

    /**
     * Gets non-empty data values, filtering out rows where all cells are empty or whitespace.
     */
    public String[][] getNonEmptyDataValues() {
        List<String[]> nonEmptyRows = new ArrayList<>();
        for (String[] row : data) {
            boolean hasValue = false;
            for (String cell : row) {
                if (cell != null && !cell.trim().isEmpty()) {
                    hasValue = true;
                    break;
                }
            }
            if (hasValue) {
                nonEmptyRows.add(row.clone());
            }
        }
        return nonEmptyRows.toArray(new String[0][]);
    }
}
