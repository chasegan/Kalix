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

    /**
     * Returns true if this definition can produce a meaningful table for the
     * given raw property value.
     *
     * <p>Implementations should be defensive and inexpensive: this is called
     * during right-click context-menu construction to decide whether the
     * "Table View" item appears. The default accepts any value, preserving
     * the historical behaviour. Definitions that handle a specific value shape
     * (e.g. a linear combination of data references) should override this so
     * the menu item is hidden when the value does not fit the shape.</p>
     *
     * <p>When several definitions are registered for the same
     * {@code (nodeType, propertyName)} pair, the registry walks them in
     * registration order and selects the first that returns true here, so
     * register more specific definitions first.</p>
     */
    default boolean canHandleValue(String value) {
        return true;
    }

    /**
     * Formats edited table values back to the INI value string.
     *
     * <p>The default implementation delegates to {@link TableValueFormatter},
     * producing the comma-separated layout used by all of the existing
     * built-in definitions. Definitions whose value shape is not comma-
     * separated (linear combinations, function calls, etc.) override this to
     * emit their own format.</p>
     *
     * @param values             the non-empty rows from the table
     * @param multiLine          true if the user chose the multi-line accept option
     * @param formatter          the standard formatter, available for delegation
     * @param continuationIndent number of spaces to indent continuation lines
     * @return the formatted property value
     */
    default String formatValues(String[][] values, boolean multiLine,
                                TableValueFormatter formatter, int continuationIndent) {
        if (multiLine) {
            return formatter.formatMultiLine(
                    values, getOrientation(), getValuesPerLine(), getHeaderLine(), continuationIndent);
        }
        return formatter.formatInline(values, getOrientation());
    }
}
