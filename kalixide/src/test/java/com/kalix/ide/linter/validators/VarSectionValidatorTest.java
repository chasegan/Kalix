package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for VarSectionValidator ([var.*] model-variable blocks).
 * The rules mirror the engine's load-time validation in the var. arm of
 * src/io/ini_model_io_versions/ini_doc_model_io_0_0_1.rs.
 */
class VarSectionValidatorTest {

    private VarSectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new VarSectionValidator();
    }

    // ==================== Happy paths ====================

    @Test
    @DisplayName("A var block with phase = flow and multiple keys should produce no issues")
    void testValidVarBlock() {
        assertNoIssues("""
            [var.accounting]
            phase = flow
            cap = 100.0
            headroom = var.accounting.cap - 5.0
            """);
    }

    @Test
    @DisplayName("A var block without an explicit phase should produce no issues")
    void testValidVarBlockNoPhase() {
        assertNoIssues("""
            [var.accounting]
            used = 42.0
            """);
    }

    // ==================== Block-name errors ====================

    @Test
    @DisplayName("A dotted var block name should be flagged")
    void testDottedBlockName() {
        assertHasIssue("[var.a.b]\nx = 1\n", "Invalid var block name");
    }

    // ==================== Phase errors ====================

    @Test
    @DisplayName("phase = order should be flagged as not yet implemented")
    void testPhaseOrder() {
        assertHasIssue("[var.a]\nphase = order\nx = 1\n", "not yet implemented");
    }

    @Test
    @DisplayName("An unrecognised phase should be flagged")
    void testInvalidPhase() {
        assertHasIssue("[var.a]\nphase = bogus\nx = 1\n", "invalid phase");
    }

    // ==================== Key and expression errors ====================

    @Test
    @DisplayName("A dotted var key should be flagged")
    void testDottedKey() {
        assertHasIssue("[var.a]\nbad.key = 1\n", "Invalid var name");
    }

    @Test
    @DisplayName("A bad expression in a var value should be flagged")
    void testBadExpression() {
        assertHasIssue("[var.a]\nx = notafunc(1)\n", "Unknown function");
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
}
