package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies an output reference to a nonexistent node produces exactly ONE
 * diagnostic (review, linter section): ReferenceValidator used to run the
 * schema-driven check and then add an identical message from its own
 * (dsflow|usflow|storage) loop.
 */
class ReferenceValidatorOutputRefTest {

    private static final String MODEL = """
            [node.real]
            type = confluence
            loc = 1, 2

            [outputs]
            node.ghost.dsflow
            node.real.dsflow
            """;

    @Test
    void nonexistentOutputNodeReportedOnce() {
        LinterSchema schema = LinterSchema.loadDefault();
        INIModelParser.ParsedModel model = INIModelParser.parse(MODEL);

        ValidationResult result = new ValidationResult();
        new ReferenceValidator().validate(model, schema, result, null);

        List<ValidationIssue> ghostIssues = result.getIssues().stream()
                .filter(i -> i.getMessage().contains("ghost"))
                .toList();
        assertEquals(1, ghostIssues.size(),
                "nonexistent output node must produce exactly one diagnostic, got: " + result.getIssues());
        assertEquals(6, ghostIssues.get(0).getLineNumber());
    }

    @Test
    void validOutputReferenceIsClean() {
        LinterSchema schema = LinterSchema.loadDefault();
        INIModelParser.ParsedModel model = INIModelParser.parse("""
                [node.real]
                type = confluence
                loc = 1, 2

                [outputs]
                node.real.dsflow
                """);

        ValidationResult result = new ValidationResult();
        new ReferenceValidator().validate(model, schema, result, null);
        assertTrue(result.getIssues().isEmpty(), "unexpected: " + result.getIssues());
    }

    @Test
    void varOutputReferenceIsAccepted() {
        LinterSchema schema = LinterSchema.loadDefault();
        INIModelParser.ParsedModel model = INIModelParser.parse("""
                [node.real]
                type = confluence
                loc = 1, 2

                [var.accounting]
                headroom = 5.0

                [outputs]
                var.accounting.headroom
                """);

        ValidationResult result = new ValidationResult();
        new ReferenceValidator().validate(model, schema, result, null);
        assertTrue(result.getIssues().isEmpty(), "unexpected: " + result.getIssues());
    }

    @Test
    void unknownVarOutputReferenceFlagged() {
        LinterSchema schema = LinterSchema.loadDefault();
        INIModelParser.ParsedModel model = INIModelParser.parse("""
                [var.accounting]
                headroom = 5.0

                [outputs]
                var.accounting.missing
                """);

        ValidationResult result = new ValidationResult();
        new ReferenceValidator().validate(model, schema, result, null);
        List<ValidationIssue> issues = result.getIssues().stream()
                .filter(i -> i.getMessage().contains("unknown var"))
                .toList();
        assertEquals(1, issues.size(), "expected one unknown-var diagnostic, got: " + result.getIssues());
    }
}
