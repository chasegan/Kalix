package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TableSectionValidator ([table.*] lookup table sections).
 * The rules mirror the engine's load-time validation in
 * src/numerical/lookup_table.rs.
 */
class TableSectionValidatorTest {

    private TableSectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TableSectionValidator();
    }

    // ==================== Section-level validation ====================

    @Test
    @DisplayName("Well-formed 1D and 2D tables should produce no issues")
    void testValidTables() {
        assertNoIssues("""
            [table.rating]
            values = 0, 0,
                   0.5, 120,
                   3, 2200

            [table.monthly]
            n_cols = 4
            values = x, 1, 2, 3,
                   0, 10, 20, 30,
                   100, 11, 22, 33
            """);
    }

    @Test
    @DisplayName("1D table with a text header should be valid")
    void testValid1dWithHeader() {
        assertNoIssues("""
            [table.rating]
            values = stage, flow,
                   0, 0,
                   1, 250
            """);
    }

    @Test
    @DisplayName("Invalid table names should be flagged")
    void testInvalidTableName() {
        assertHasIssue("[table.Bad]\nvalues = 0, 0, 1, 1\n", "Invalid table name");
        assertHasIssue("[table.a.b]\nvalues = 0, 0, 1, 1\n", "Invalid table name");
    }

    @Test
    @DisplayName("Missing data property should be flagged")
    void testMissingData() {
        assertHasIssue("[table.t]\nn_cols = 2\n", "has no 'values' property");
    }

    @Test
    @DisplayName("Unexpected properties should be flagged")
    void testUnexpectedProperty() {
        assertHasIssue("[table.t]\nvalues = 0, 0, 1, 1\nfoo = bar\n", "Unexpected property 'foo'");
    }

    @Test
    @DisplayName("Bad n_cols values should be flagged")
    void testBadNCols() {
        assertHasIssue("[table.t]\nn_cols = two\nvalues = 0, 0, 1, 1\n", "n_cols must be an integer");
        assertHasIssue("[table.t]\nn_cols = 1\nvalues = 0, 0, 1, 1\n", "n_cols must be at least 2");
    }

    @Test
    @DisplayName("bilinear accepts true or false on a 2D table")
    void testValidBilinear() {
        assertNoIssues("[table.t]\nn_cols = 3\nbilinear = true\nvalues = x, 1, 2, 0, 5, 6\n");
        assertNoIssues("[table.t]\nbilinear = true\nn_cols = 3\nvalues = x, 1, 2, 0, 5, 6\n");
        assertNoIssues("[table.t]\nn_cols = 3\nbilinear = false\nvalues = x, 1, 2, 0, 5, 6\n");
        assertNoIssues("[table.t]\nbilinear = false\nvalues = 0, 0, 1, 1\n");
    }

    @Test
    @DisplayName("Bad bilinear values should be flagged")
    void testBadBilinear() {
        assertHasIssue("[table.t]\nn_cols = 3\nbilinear = yes\nvalues = x, 1, 2, 0, 5, 6\n", "bilinear must be 'true' or 'false'");
        assertHasIssue("[table.t]\nn_cols = 3\nbilinear = True\nvalues = x, 1, 2, 0, 5, 6\n", "bilinear must be 'true' or 'false'");
    }

    @Test
    @DisplayName("bilinear = true on a 1D table should be flagged, whatever the property order")
    void testBilinearOn1dTable() {
        assertHasIssue("[table.t]\nbilinear = true\nvalues = 0, 0, 1, 1\n", "bilinear applies only to 2D tables");
        assertHasIssue("[table.t]\nbilinear = true\nn_cols = 2\nvalues = 0, 0, 1, 1\n", "bilinear applies only to 2D tables");
    }

    // ==================== Data grid validation ====================

    @Test
    @DisplayName("1D data errors should be flagged")
    void test1dDataErrors() {
        assertDataError("0, 0, 1", 2, "even number of values");
        assertDataError("0, 0, 0, 1", 2, "strictly ascending");
        assertDataError("1, 0, 0, 1", 2, "strictly ascending");
        assertDataError("0, blah, 1, 1", 2, "finite number");
        assertDataError("0, nan, 1, 1", 2, "finite number");
        assertDataError("", 2, "values is empty");
        assertDataError("stage, 0, 1, 1", 2, "header must have exactly 2 non-numeric labels");
        assertDataError("stage, flow", 2, "no data rows");
    }

    @Test
    @DisplayName("2D data errors should be flagged")
    void test2dDataErrors() {
        assertDataError("0, 1, 2, 0, 0, 0", 3, "corner label");
        assertDataError("x, 1, 2, 0, 0", 3, "multiple of n_cols");
        assertDataError("x, 1, 2", 3, "no data rows");
        assertDataError("x, 2, 1, 0, 0, 0", 3, "column keys must be strictly ascending");
        assertDataError("x, 1, 1, 0, 0, 0", 3, "column keys must be strictly ascending");
        assertDataError("x, 1, 2, 5, 0, 0, 5, 1, 1", 3, "row keys must be strictly ascending");
    }

    @Test
    @DisplayName("Well-formed data should produce no errors")
    void testGoodData() {
        assertTrue(TableSectionValidator.checkTableValues("t", "0, 0, 1, 10", 2).isEmpty());
        assertTrue(TableSectionValidator.checkTableValues("t", "0, 0, 1, 10,", 2).isEmpty(), "trailing comma tolerated");
        assertTrue(TableSectionValidator.checkTableValues("t", "x, 1, 2, 0, 5, 6", 3).isEmpty());
        // Single-row tables are allowed (constant 1D; monthly-constants 2D)
        assertTrue(TableSectionValidator.checkTableValues("t", "5, 42", 2).isEmpty());
        assertTrue(TableSectionValidator.checkTableValues("t", "x, 1, 2, 3, 0, 10, 20, 30", 4).isEmpty());
    }

    // ==================== Helper methods ====================

    private ValidationResult run(String ini) {
        INIModelParser.ParsedModel model = INIModelParser.parse(ini);
        ValidationResult result = new ValidationResult();
        validator.validate(model, null, result, null);
        return result;
    }

    private void assertNoIssues(String ini) {
        ValidationResult result = run(ini);
        assertTrue(result.getIssues().isEmpty(),
                "Expected no issues, got: " + result.getIssues());
    }

    private void assertHasIssue(String ini, String expectedMessage) {
        ValidationResult result = run(ini);
        boolean found = result.getIssues().stream()
                .anyMatch(issue -> issue.getMessage().contains(expectedMessage));
        assertTrue(found, "Expected an issue containing '" + expectedMessage
                + "', got: " + result.getIssues());
    }

    private void assertDataError(String data, int nCols, String expectedMessage) {
        List<String> errors = TableSectionValidator.checkTableValues("t", data, nCols);
        boolean found = errors.stream().anyMatch(e -> e.contains(expectedMessage));
        assertTrue(found, "Expected error containing '" + expectedMessage
                + "' for data '" + data + "', got: " + errors);
    }
}
