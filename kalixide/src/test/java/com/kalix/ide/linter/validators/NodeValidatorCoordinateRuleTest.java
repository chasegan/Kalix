package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies coordinate validation is routed through the schema's
 * coordinate_format rule (review #45): the rule's enabled flag and severity
 * must be honoured instead of a hardcoded, always-on ERROR.
 */
class NodeValidatorCoordinateRuleTest {

    private static final String MODEL = """
            [node.a]
            type = confluence
            loc = not-a-coordinate
            """;

    private static List<ValidationIssue> validate(LinterSchema schema) {
        INIModelParser.ParsedModel model = INIModelParser.parse(MODEL);
        ValidationResult result = new ValidationResult();
        new NodeValidator().validate(model, schema, result, null);
        return result.getIssues().stream()
                .filter(i -> "coordinate_format".equals(coordinateIssueKey(i)))
                .toList();
    }

    private static String coordinateIssueKey(ValidationIssue issue) {
        return issue.getMessage().startsWith("Invalid coordinate format") ? "coordinate_format" : "";
    }

    @Test
    void invalidCoordinatesReportedWithRuleSeverity() {
        LinterSchema schema = LinterSchema.loadDefault();
        List<ValidationIssue> issues = validate(schema);

        assertEquals(1, issues.size(), "invalid coordinates must be reported when the rule is enabled");
        assertEquals(ValidationRule.Severity.ERROR, issues.get(0).getSeverity(),
                "default schema declares coordinate_format as error");
    }

    @Test
    void disablingTheRuleSuppressesTheIssue() {
        LinterSchema schema = LinterSchema.loadDefault();
        schema.getValidationRule("coordinate_format").setEnabled(false);

        assertTrue(validate(schema).isEmpty(),
                "coordinate_format toggle must actually suppress coordinate diagnostics");
    }
}
