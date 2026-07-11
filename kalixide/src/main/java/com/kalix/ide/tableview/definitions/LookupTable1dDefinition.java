package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TableParsingUtils;
import com.kalix.ide.tableview.TablePropertyDefinition;

/**
 * Table view definition for the {@code values} property of a 1D
 * {@code [table.*]} lookup table: rows of (x, y) breakpoints, with an
 * optional two-label text header.
 *
 * <p>Unlike the node-table definitions this is not registered in the
 * {@link com.kalix.ide.tableview.TablePropertyRegistry}: a lookup table's
 * shape depends on its sibling {@code n_cols} property, which only the
 * command layer can see, so {@code OpenTableViewCommand} constructs the
 * right definition per invocation.</p>
 *
 * <p>Instances are per-invocation and value-aware: the constructor inspects
 * the current value so a text header ("stage, flow") becomes the column
 * names and is re-emitted on save via {@link #getHeaderLine()}.</p>
 */
public final class LookupTable1dDefinition implements TablePropertyDefinition {

    private static final int NUM_COLUMNS = 2;

    private final String tableName;
    /** Header labels from the value, or null when the table has no header. */
    private final String[] headerLabels;

    public LookupTable1dDefinition(String tableName, String currentValue) {
        this.tableName = tableName;
        String[] cells = TableParsingUtils.splitRawCells(currentValue);
        // The engine's header rule: a non-numeric first cell means the first
        // two cells are labels (LookupTable::parse_1d). Malformed headers are
        // shown as data so the modeller can see and fix them.
        if (cells.length >= 2
                && !TableParsingUtils.isValidNumber(cells[0])
                && !TableParsingUtils.isValidNumber(cells[1])) {
            this.headerLabels = new String[]{cells[0], cells[1]};
        } else {
            this.headerLabels = null;
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
        return headerLabels != null ? headerLabels.clone() : new String[]{"x", "y"};
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
        int start = headerLabels != null ? 2 : 0; // Header lives in the column names
        int numRows = (cells.length - start + NUM_COLUMNS - 1) / NUM_COLUMNS;
        if (numRows <= 0) {
            return new String[][]{{"", ""}};
        }
        String[][] result = new String[numRows][NUM_COLUMNS];
        for (int i = 0; i < numRows; i++) {
            for (int j = 0; j < NUM_COLUMNS; j++) {
                int index = start + i * NUM_COLUMNS + j;
                result[i][j] = index < cells.length ? cells[index] : "";
            }
        }
        return result;
    }

    @Override
    public int getValuesPerLine() {
        return NUM_COLUMNS; // One (x, y) row per line
    }

    @Override
    public String getHeaderLine() {
        return headerLabels != null ? headerLabels[0] + ", " + headerLabels[1] + "," : null;
    }

    @Override
    public String validateCell(int row, int col, String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Value cannot be empty";
        }
        if (!TableParsingUtils.isValidNumber(value.trim())) {
            return "Value must be a number";
        }
        return null;
    }

    @Override
    public String getWindowTitle() {
        return "Lookup table " + tableName;
    }
}
