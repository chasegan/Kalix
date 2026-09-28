package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for CropSectionValidator ([crop.*] sections). The rules mirror
 * the engine's load-time validation in src/hydrology/crop.rs.
 */
class CropSectionValidatorTest {

    private CropSectionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CropSectionValidator();
    }

    @Test
    @DisplayName("Well-formed crops should produce no issues")
    void testValidCrops() {
        assertNoIssues("""
            [crop.cotton]
            root_depth = 900
            p = 0.65
            kc = Day, Kc,
                 0,   0.35,
                 70,  1.2,
                 180, 0.6,
            season_len = 180

            [crop.fallow]
            root_depth = 600
            kc = 0.4
            """);
    }

    @Test
    @DisplayName("Invalid crop names should be flagged")
    void testInvalidCropName() {
        assertHasIssue("[crop.Cotton]\nroot_depth = 900\nkc = 1\n", "Invalid crop name");
        assertHasIssue("[crop.a.b]\nroot_depth = 900\nkc = 1\n", "Invalid crop name");
    }

    @Test
    @DisplayName("Missing required properties should be flagged")
    void testMissingProperties() {
        assertHasIssue("[crop.cotton]\nkc = 1\n", "has no 'root_depth'");
        assertHasIssue("[crop.cotton]\nroot_depth = 900\n", "has no 'kc'");
    }

    @Test
    @DisplayName("Bad values should be flagged")
    void testBadValues() {
        assertHasIssue("[crop.cotton]\nroot_depth = 0\nkc = 1\n", "root_depth must be a positive number");
        assertHasIssue("[crop.cotton]\nroot_depth = deep\nkc = 1\n", "root_depth must be a positive number");
        assertHasIssue("[crop.cotton]\nroot_depth = 900\np = 1\nkc = 1\n", "p must be at least 0 and less than 1");
        assertHasIssue("[crop.cotton]\nroot_depth = 900\nkc = -1\n", "kc must be a non-negative number");
        assertHasIssue("[crop.cotton]\nroot_depth = 900\nkc = 1\nseason_len = 0\n", "season_len must be a whole number of days");
        assertHasIssue("[crop.cotton]\nroot_depth = 900\nkc = 1\nseason_len = 90.5\n", "season_len must be a whole number of days");
        assertHasIssue("[crop.cotton]\nroot_depth = 900\nkc = 1\nyield = 2\n", "Unexpected property 'yield'");
    }

    @Test
    @DisplayName("kc table errors should be flagged")
    void testKcTableErrors() {
        assertTrue(CropSectionValidator.checkKc("c", "0, 0.3, 30").stream().anyMatch(e -> e.contains("even number")));
        assertTrue(CropSectionValidator.checkKc("c", "0, 0.3, 30, 0.5, 20, 1").stream().anyMatch(e -> e.contains("must increase")));
        assertTrue(CropSectionValidator.checkKc("c", "-1, 0.3, 30, 0.5").stream().anyMatch(e -> e.contains("non-negative")));
        assertTrue(CropSectionValidator.checkKc("c", "0, -0.3, 30, 0.5").stream().anyMatch(e -> e.contains("non-negative")));
        assertTrue(CropSectionValidator.checkKc("c", "Day, 0.3, 30, 0.5").stream().anyMatch(e -> e.contains("two-label header")));
        assertTrue(CropSectionValidator.checkKc("c", "Day, Kc").stream().anyMatch(e -> e.contains("no data rows")));
        assertTrue(CropSectionValidator.checkKc("c", "").stream().anyMatch(e -> e.contains("is empty")));
    }

    @Test
    @DisplayName("Well-formed kc should produce no errors")
    void testGoodKc() {
        assertTrue(CropSectionValidator.checkKc("c", "0.95").isEmpty());
        assertTrue(CropSectionValidator.checkKc("c", "0, 0.3, 30, 0.5, 90, 1.1,").isEmpty(), "trailing comma tolerated");
        assertTrue(CropSectionValidator.checkKc("c", "Day, Kc, 0, 0.3, 30, 0.5").isEmpty());
        assertTrue(CropSectionValidator.checkKc("c", "10, 0.5").isEmpty(), "one row is a constant");
    }

    private ValidationResult run(String ini) {
        INIModelParser.ParsedModel model = INIModelParser.parse(ini);
        ValidationResult result = new ValidationResult();
        validator.validate(model, null, result, null);
        return result;
    }

    private void assertNoIssues(String ini) {
        ValidationResult result = run(ini);
        assertTrue(result.getIssues().isEmpty(), "Expected no issues, got: " + result.getIssues());
    }

    private void assertHasIssue(String ini, String expectedMessage) {
        ValidationResult result = run(ini);
        boolean found = result.getIssues().stream().anyMatch(issue -> issue.getMessage().contains(expectedMessage));
        assertTrue(found, "Expected an issue containing '" + expectedMessage + "', got: " + result.getIssues());
    }
}
