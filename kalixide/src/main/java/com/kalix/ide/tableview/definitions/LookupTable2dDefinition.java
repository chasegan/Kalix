package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TableParsingUtils;
import com.kalix.ide.tableview.TablePropertyDefinition;

/**
 * Table view definition for the {@code values} property of a 2D
 * {@code [table.*]} lookup table ({@code n_cols > 2}).
 *
 * <p>The grid mirrors the text faithfully: row 0 is the key row (corner
 * marker + column keys) and remains fully editable, so a modeller can change
 * the column keys as easily as the values. Cell validation applies the
 * engine's grammar per cell: the corner must be non-numeric, everything else
 * numeric. Cross-cell rules (ascending keys, divisibility) stay with the
 * linter after accept.</p>
 *
 * <p>Constructed per invocation by {@code OpenTableViewCommand}, which reads
 * the sibling {@code n_cols} property — the one piece of shape information
 * the value string alone cannot supply. Not registered in the
 * {@link com.kalix.ide.tableview.TablePropertyRegistry}.</p>
 */
public final class LookupTable2dDefinition implements TablePropertyDefinition {

    private final String tableName;
    private final int nCols;
    private final String[] columnNames;

    public LookupTable2dDefinition(String tableName, int nCols) {
        this.tableName = tableName;
        this.nCols = Math.max(nCols, 3);
        this.columnNames = new String[this.nCols];
        // Positional headers: the real column keys live in row 0, so the
        // JTable headers just orient the reader. Numbering starts at col2
        // (the key column is column 1) so the last header equals n_cols,
        // making the count rule visible.
        columnNames[0] = "key";
        for (int i = 1; i < this.nCols; i++) {
            columnNames[i] = "col" + (i + 1);
        }
    }

    @Override
    public String getNodeType() {
        return "table";
    }

    @Override
    public String getPropertyName() {
        return "values";
    }

    @Override
    public DisplayOrientation getOrientation() {
        return DisplayOrientation.HORIZONTAL;
    }

    @Override
    public String[] getColumnNames() {
        return columnNames.clone();
    }

    @Override
    public String[] getRowNames() {
        return null; // Dynamic rows
    }

    @Override
    public boolean isFixedRowCount() {
        return false;
    }

    @Override
    public String[][] parseValues(String iniValue) {
        String[] cells = TableParsingUtils.splitRawCells(iniValue);
        int numRows = (cells.length + nCols - 1) / nCols;
        if (numRows <= 0) {
            // Empty template: a key row plus one data row
            String[][] template = new String[2][nCols];
            for (int j = 0; j < nCols; j++) {
                template[0][j] = j == 0 ? "x" : "";
                template[1][j] = "";
            }
            return template;
        }
        String[][] result = new String[numRows][nCols];
        for (int i = 0; i < numRows; i++) {
            for (int j = 0; j < nCols; j++) {
                int index = i * nCols + j;
                result[i][j] = index < cells.length ? cells[index] : "";
            }
        }
        return result;
    }

    @Override
    public int getValuesPerLine() {
        return nCols; // One grid row per line
    }

    @Override
    public String validateCell(int row, int col, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (row == 0 && col == 0) {
            // The corner marker must read as text, per the engine's grammar
            if (trimmed.isEmpty()) {
                return "Corner marker cannot be empty (use e.g. 'x')";
            }
            if (TableParsingUtils.isValidNumber(trimmed)) {
                return "Corner marker must not be a number (use e.g. 'x')";
            }
            return null;
        }
        if (trimmed.isEmpty()) {
            return "Value cannot be empty";
        }
        if (!TableParsingUtils.isValidNumber(trimmed)) {
            return row == 0 ? "Column keys must be numbers" : "Value must be a number";
        }
        return null;
    }

    @Override
    public String getWindowTitle() {
        return "Lookup table " + tableName;
    }
}
