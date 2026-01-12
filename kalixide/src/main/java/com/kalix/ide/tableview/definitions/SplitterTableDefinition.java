package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TablePropertyDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Table property definition for Splitter node table parameter.
 * Table is a Flow-Effluent relationship with dynamic rows.
 */
public class SplitterTableDefinition implements TablePropertyDefinition {

    private static final String[] COLUMN_NAMES = {
        "Flow [ML]",
        "Effluent [ML]"
    };

    private static final int NUM_COLUMNS = COLUMN_NAMES.length;

    // Pattern for valid numbers including scientific notation (e.g., 1e8, -3.14, .5)
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
        "-?\\d*\\.?\\d+([eE][+-]?\\d+)?"
    );

    @Override
    public String getNodeType() {
        return "splitter";
    }

    @Override
    public String getPropertyName() {
        return "table";
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
        String[] allValues = parseNumericValues(iniValue);

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
        return "Flow [ML], Effluent [ML],";
    }

    @Override
    public String validateCell(int row, int col, String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Value cannot be empty";
        }
        if (!isValidNumber(value.trim())) {
            return "Value must be a number";
        }
        return null;
    }

    @Override
    public String getWindowTitle() {
        return "Splitter Table";
    }

    private String[] parseNumericValues(String iniValue) {
        if (iniValue == null || iniValue.trim().isEmpty()) {
            return new String[0];
        }

        // Split by commas, handling potential header line
        String[] lines = iniValue.split("\n");
        List<String> values = new ArrayList<>();

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }

            // Parse values from this line, skipping if any value is not a valid number
            String[] parts = trimmedLine.split(",");
            List<String> lineValues = new ArrayList<>();
            boolean isDataLine = true;

            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    if (isValidNumber(trimmed)) {
                        lineValues.add(trimmed);
                    } else {
                        // Not a number - this is a header line
                        isDataLine = false;
                        break;
                    }
                }
            }

            if (isDataLine) {
                values.addAll(lineValues);
            }
        }

        return values.toArray(new String[0]);
    }

    private boolean isValidNumber(String value) {
        return NUMBER_PATTERN.matcher(value).matches();
    }
}
