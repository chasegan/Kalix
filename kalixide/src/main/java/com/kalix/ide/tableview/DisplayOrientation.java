package com.kalix.ide.tableview;

/**
 * Defines how table data should be displayed in the TableViewWindow.
 */
public enum DisplayOrientation {
    /**
     * Traditional table layout with column names as headers and data in rows.
     * Used for storage dimensions where each row represents a data point.
     * Example:
     * | Level | Volume | Area | Spill |
     * |-------|--------|------|-------|
     * | 0.0   | 0.0    | 0.0  | 0.0   |
     * | 10.0  | 500    | 100  | 0.0   |
     */
    HORIZONTAL,

    /**
     * Transposed layout with parameter names as row labels and values in a single column.
     * Used for model parameters like Sacramento where there are many named values.
     * Example:
     * | Parameter | Value |
     * |-----------|-------|
     * | UZTWM     | 50.0  |
     * | UZFWM     | 40.0  |
     */
    VERTICAL
}
