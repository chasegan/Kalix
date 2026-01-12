package com.kalix.ide.tableview.definitions;

import com.kalix.ide.tableview.DisplayOrientation;
import com.kalix.ide.tableview.TablePropertyDefinition;

import java.util.regex.Pattern;

/**
 * Abstract base class for vertical parameter table definitions.
 * Handles common functionality for model parameters like Sacramento and GR4J.
 */
public abstract class AbstractVerticalParamsDefinition implements TablePropertyDefinition {

    // Pattern for valid numbers including scientific notation (e.g., 1e8, -3.14, .5)
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
        "-?\\d*\\.?\\d+([eE][+-]?\\d+)?"
    );

    private static final String[] COLUMN_NAMES = {"Parameter", "Value"};

    /**
     * Gets the parameter names for this model type.
     * Subclasses must provide their specific parameter names.
     */
    protected abstract String[] getParameterNames();

    /**
     * Gets the number of values to display per line in multi-line format.
     */
    protected abstract int getMultiLineValuesPerLine();

    @Override
    public DisplayOrientation getOrientation() {
        return DisplayOrientation.VERTICAL;
    }

    @Override
    public String[] getColumnNames() {
        return COLUMN_NAMES;
    }

    @Override
    public String[] getRowNames() {
        return getParameterNames();
    }

    @Override
    public boolean isFixedRowCount() {
        return true;
    }

    @Override
    public String[][] parseValues(String iniValue) {
        String[] values = parseNumericValues(iniValue);
        String[] paramNames = getParameterNames();
        String[][] result = new String[paramNames.length][1];

        for (int i = 0; i < paramNames.length; i++) {
            result[i][0] = i < values.length ? values[i] : "";
        }

        return result;
    }

    @Override
    public int getValuesPerLine() {
        return getMultiLineValuesPerLine();
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

    /**
     * Parses comma-separated numeric values from an INI value string.
     */
    protected String[] parseNumericValues(String iniValue) {
        if (iniValue == null || iniValue.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = iniValue.split(",");
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i].trim();
        }
        return result;
    }

    /**
     * Validates that a string represents a valid number.
     */
    private boolean isValidNumber(String value) {
        return NUMBER_PATTERN.matcher(value).matches();
    }
}
