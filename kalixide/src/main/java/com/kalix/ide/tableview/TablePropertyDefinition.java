package com.kalix.ide.tableview;

/**
 * Defines metadata and parsing/formatting behavior for a table-editable property.
 * Each implementation handles a specific node type + property combination.
 */
public interface TablePropertyDefinition {

    /**
     * Gets the node type this definition applies to (e.g., "sacramento", "storage", "gr4j").
     */
    String getNodeType();

    /**
     * Gets the property name this definition applies to (e.g., "params", "dimensions").
     */
    String getPropertyName();

    /**
     * Gets the display orientation for this property.
     */
    DisplayOrientation getOrientation();

    /**
     * Gets the column names for the table header.
     * For VERTICAL orientation, typically ["Parameter", "Value"].
     * For HORIZONTAL orientation, the actual data column names.
     */
    String[] getColumnNames();

    /**
     * Gets the row names/labels.
     * For VERTICAL orientation, these are the parameter names displayed in the first column.
     * For HORIZONTAL orientation, returns null (rows are numbered or dynamic).
     */
    String[] getRowNames();

    /**
     * Returns true if the number of rows is fixed (e.g., Sacramento has exactly 17 parameters).
     * Returns false if rows can be added/removed (e.g., storage dimensions).
     */
    boolean isFixedRowCount();

    /**
     * Parses the INI property value into a 2D array of strings for table display.
     * The array dimensions depend on orientation:
     * - VERTICAL: [numParams][1] (single value column)
     * - HORIZONTAL: [numRows][numColumns]
     *
     * @param iniValue The raw property value from the INI file (may be multi-line, comments stripped)
     * @return 2D array of string values for the table
     */
    String[][] parseValues(String iniValue);

    /**
     * Gets the number of values per line for multi-line formatting.
     * For VERTICAL orientation, this is how many parameter values per line.
     * For HORIZONTAL orientation, this is typically the number of columns (one row per line).
     */
    int getValuesPerLine();

    /**
     * Gets the header line for multi-line formatting (e.g., column names with units).
     * Returns null if no header line should be included.
     * Primarily used for HORIZONTAL orientation tables like storage dimensions.
     */
    default String getHeaderLine() {
        return null;
    }

    /**
     * Validates a cell value.
     *
     * @param row   Row index
     * @param col   Column index (for VERTICAL, this is always the value column)
     * @param value The value to validate
     * @return Error message if invalid, null if valid
     */
    String validateCell(int row, int col, String value);

    /**
     * Gets a user-friendly title for the table view window.
     */
    default String getWindowTitle() {
        return getNodeType().substring(0, 1).toUpperCase() + getNodeType().substring(1)
            + " " + getPropertyName();
    }
}
