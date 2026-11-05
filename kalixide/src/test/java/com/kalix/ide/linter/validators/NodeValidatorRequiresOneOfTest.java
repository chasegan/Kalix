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
 * A storage's forecast_adjustment needs a target, as the engine refuses at
 * configure. The schema's requires_one_of entry says so.
 */
class NodeValidatorRequiresOneOfTest {

    private static String storage(String extra) {
        return """
                [node.weir]
                type = storage
                loc = 0, 0
                dimensions = 0, 0, 0, 0, 1, 1000, 1, 0
                %s
                """.formatted(extra);
    }

    private static List<ValidationIssue> issues(String model, LinterSchema schema) {
        ValidationResult result = new ValidationResult();
        new NodeValidator().validate(INIModelParser.parse(model), schema, result, null);
        return result.getIssues().stream()
                .filter(i -> "requires_one_of".equals(i.getRuleName()))
                .toList();
    }

    @Test
    void adjustmentWithoutATargetIsReportedOnItsLine() {
        List<ValidationIssue> found = issues(storage("forecast_adjustment = 200"), LinterSchema.loadDefault());

        assertEquals(1, found.size());
        assertEquals(ValidationRule.Severity.ERROR, found.get(0).getSeverity());
        assertEquals(5, found.get(0).getLineNumber());
        assertEquals("Node 'weir': forecast_adjustment needs target_level or target_volume",
                found.get(0).getMessage());
    }

    @Test
    void adjustmentWithEitherTargetIsFine() {
        LinterSchema schema = LinterSchema.loadDefault();
        assertTrue(issues(storage("target_level = 1.5\nforecast_adjustment = 200"), schema).isEmpty());
        assertTrue(issues(storage("target_volume = 1500\nforecast_adjustment = 200"), schema).isEmpty());
        assertTrue(issues(storage("target_volume = 1500"), schema).isEmpty());
    }

    @Test
    void disablingTheRuleSuppressesTheIssue() {
        LinterSchema schema = LinterSchema.loadDefault();
        schema.getValidationRule("requires_one_of").setEnabled(false);
        assertTrue(issues(storage("forecast_adjustment = 200"), schema).isEmpty());
    }
}
