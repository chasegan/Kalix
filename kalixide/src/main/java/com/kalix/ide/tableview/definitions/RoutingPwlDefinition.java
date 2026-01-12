package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TableParsingUtils;
import com.kalix.ide.tableview.TablePropertyDefinition;

/**
 * Table property definition for Routing node piecewise linear (pwl) parameter.
 * PWL is a Flow-Time table with dynamic rows.
 */
public class RoutingPwlDefinition implements TablePropertyDefinition {

    private static final String[] COLUMN_NAMES = {
        "Flow [ML]",
        "Travel Time [steps]"
    };

    private static final int NUM_COLUMNS = COLUMN_NAMES.length;

    @Override
    public String getNodeType() {
        return "routing";
    }

    @Override
    public String getPropertyName() {
        return "pwl";
    }

    @Override
    public DisplayOrientation getOrientation() {
        return DisplayOrientation.HORIZONTAL;
    }

    @Override
    public String[] getColumnNames() {
        return COLUMN_NAMES;
    }

    @Override
    public String[] getRowNames() {
        return null; // Dynamic rows, no fixed names
    }

    @Override
    public boolean isFixedRowCount() {
        return false;
    }

    @Override
    public String[][] parseValues(String iniValue) {
        if (iniValue == null || iniValue.trim().isEmpty()) {
            // Return empty table with one row
            return new String[][]{{"", ""}};
        }

        // Parse all numeric values
        String[] allValues = TableParsingUtils.parseNumericValues(iniValue);

        // Group into rows of 2 columns
        int numRows = (allValues.length + NUM_COLUMNS - 1) / NUM_COLUMNS;
        if (numRows == 0) {
            numRows = 1;
        }

        String[][] result = new String[numRows][NUM_COLUMNS];

        for (int i = 0; i < numRows; i++) {
            for (int j = 0; j < NUM_COLUMNS; j++) {
                int index = i * NUM_COLUMNS + j;
                result[i][j] = index < allValues.length ? allValues[index] : "";
            }
        }

        return result;
    }

    @Override
    public int getValuesPerLine() {
        return NUM_COLUMNS; // One row per line
    }

    @Override
    public String getHeaderLine() {
        return "Flow [ML], Travel Time [steps],";
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
        return "Routing PWL";
    }
}
