package com.kalix.ide.tableview;

import com.kalix.ide.tableview.definitions.LookupTable1dDefinition;
import com.kalix.ide.tableview.definitions.LookupTable2dDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the lookup-table ([table.*] data) table view definitions.
 */
class LookupTableDefinitionsTest {

    // ==================== 1D ====================

    @Test
    @DisplayName("1D without header: x/y columns, rows parsed in pairs")
    void test1dNoHeader() {
        String value = "0, 0, 0.5, 120, 3, 2200";
        LookupTable1dDefinition def = new LookupTable1dDefinition("rating", value);

        assertArrayEquals(new String[]{"x", "y"}, def.getColumnNames());
        assertNull(def.getHeaderLine());

        String[][] grid = def.parseValues(value);
        assertEquals(3, grid.length);
        assertArrayEquals(new String[]{"0", "0"}, grid[0]);
        assertArrayEquals(new String[]{"0.5", "120"}, grid[1]);
        assertArrayEquals(new String[]{"3", "2200"}, grid[2]);
    }

    @Test
    @DisplayName("1D with header: labels become column names and re-emit on save")
    void test1dWithHeader() {
        String value = "stage, flow, 0, 0, 1, 250";
        LookupTable1dDefinition def = new LookupTable1dDefinition("rating", value);

        assertArrayEquals(new String[]{"stage", "flow"}, def.getColumnNames());
        assertEquals("stage, flow,", def.getHeaderLine());

        // Header cells are column names, not grid data
        String[][] grid = def.parseValues(value);
        assertEquals(2, grid.length);
        assertArrayEquals(new String[]{"0", "0"}, grid[0]);
        assertArrayEquals(new String[]{"1", "250"}, grid[1]);
    }

    @Test
    @DisplayName("1D empty value yields a single empty row template")
    void test1dEmptyValue() {
        LookupTable1dDefinition def = new LookupTable1dDefinition("rating", "");
        String[][] grid = def.parseValues("");
        assertEquals(1, grid.length);
        assertArrayEquals(new String[]{"", ""}, grid[0]);
    }

    @Test
    @DisplayName("1D cell validation requires numbers")
    void test1dValidation() {
        LookupTable1dDefinition def = new LookupTable1dDefinition("rating", "0, 0");
        assertNull(def.validateCell(0, 0, "1.5"));
        assertNull(def.validateCell(0, 1, "-2e3"));
        assertNotNull(def.validateCell(0, 0, ""));
        assertNotNull(def.validateCell(0, 1, "blah"));
    }

    @Test
    @DisplayName("1D format round-trip preserves values and header")
    void test1dFormatRoundTrip() {
        String value = "stage, flow, 0, 0, 1, 250";
        LookupTable1dDefinition def = new LookupTable1dDefinition("rating", value);
        String[][] grid = def.parseValues(value);

        TableValueFormatter formatter = new TableValueFormatter();
        String multiLine = def.formatValues(grid, true, formatter, 7);
        assertTrue(multiLine.startsWith("stage, flow,"), "header line re-emitted: " + multiLine);
        assertTrue(multiLine.contains("250"), "values retained: " + multiLine);

        // The re-emitted value parses back to the same grid
        LookupTable1dDefinition def2 = new LookupTable1dDefinition("rating", multiLine);
        assertArrayEquals(new String[]{"stage", "flow"}, def2.getColumnNames());
        assertArrayEquals(grid, def2.parseValues(multiLine));
    }

    // ==================== 2D ====================

    @Test
    @DisplayName("2D: key row is row 0 of the grid, fully editable")
    void test2dParse() {
        String value = "x, 1, 2,  0, 10, 1000,  5, 20, 2000";
        LookupTable2dDefinition def = new LookupTable2dDefinition("pump", 3);

        // Headers make the n_cols count visible: key column is col1
        assertArrayEquals(new String[]{"key", "col2", "col3"}, def.getColumnNames());
        String[][] grid = def.parseValues(value);
        assertEquals(3, grid.length);
        assertArrayEquals(new String[]{"x", "1", "2"}, grid[0]);
        assertArrayEquals(new String[]{"0", "10", "1000"}, grid[1]);
        assertArrayEquals(new String[]{"5", "20", "2000"}, grid[2]);
    }

    @Test
    @DisplayName("2D empty value yields key row + one data row template")
    void test2dEmptyValue() {
        LookupTable2dDefinition def = new LookupTable2dDefinition("pump", 4);
        String[][] grid = def.parseValues("");
        assertEquals(2, grid.length);
        assertEquals("x", grid[0][0]);
        assertEquals(4, grid[0].length);
    }

    @Test
    @DisplayName("2D cell validation: non-numeric corner, numeric everywhere else")
    void test2dValidation() {
        LookupTable2dDefinition def = new LookupTable2dDefinition("pump", 3);

        // Corner marker: text required
        assertNull(def.validateCell(0, 0, "x"));
        assertNotNull(def.validateCell(0, 0, "5"));
        assertNotNull(def.validateCell(0, 0, ""));

        // Column keys and values: numbers required
        assertNull(def.validateCell(0, 1, "1"));
        assertNotNull(def.validateCell(0, 1, "jan"));
        assertNull(def.validateCell(1, 0, "0"));
        assertNull(def.validateCell(2, 2, "1e9"));
        assertNotNull(def.validateCell(1, 1, "blah"));
    }

    @Test
    @DisplayName("2D format round-trip preserves the grid including the corner")
    void test2dFormatRoundTrip() {
        String value = "x, 1, 2,  0, 10, 1000,  5, 20, 2000";
        LookupTable2dDefinition def = new LookupTable2dDefinition("pump", 3);
        String[][] grid = def.parseValues(value);

        TableValueFormatter formatter = new TableValueFormatter();
        String inline = def.formatValues(grid, false, formatter, 7);
        assertArrayEquals(grid, def.parseValues(inline));

        String multiLine = def.formatValues(grid, true, formatter, 7);
        assertTrue(multiLine.startsWith("x"), "corner survives: " + multiLine);
        assertArrayEquals(grid, def.parseValues(multiLine));
    }

    // ==================== Raw cell splitting ====================

    @Test
    @DisplayName("splitRawCells preserves text cells and tolerates trailing commas")
    void testSplitRawCells() {
        assertArrayEquals(new String[]{"x", "1", "2"}, TableParsingUtils.splitRawCells("x, 1, 2,"));
        assertArrayEquals(new String[]{"stage", "flow", "0", "0"},
                TableParsingUtils.splitRawCells("stage, flow,\n       0, 0"));
        assertArrayEquals(new String[0], TableParsingUtils.splitRawCells("  "));
        assertArrayEquals(new String[0], TableParsingUtils.splitRawCells(null));
    }
}
