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
 * order_through takes priority over a storage target, which the engine then
 * ignores. The schema's overridden_params entry warns that the target does nothing.
 */
class NodeValidatorOverriddenParamsTest {

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
                .filter(i -> "overridden_params".equals(i.getRuleName()))
                .toList();
    }

    @Test
    void targetUnderOrderThroughWarnsOnTheTargetLine() {
        String model = storage("order_through = TRUE\ntarget_level = 1.5");
        List<ValidationIssue> found = issues(model, LinterSchema.loadDefault());

        assertEquals(1, found.size());
        assertEquals(ValidationRule.Severity.WARNING, found.get(0).getSeverity());
        assertEquals(6, found.get(0).getLineNumber());
        assertEquals("Node 'weir': target_level has no effect because order_through = true",
                found.get(0).getMessage());
    }

    @Test
    void flagIsReadAsTheEngineReadsIt() {
        LinterSchema schema = LinterSchema.loadDefault();
        assertEquals(1, issues(storage("order_through = true, 2\ntarget_level = 1.5"), schema).size());
        assertEquals(1, issues(storage("Order_Through = true\nTarget_Volume = 1500"), schema).size());
        assertTrue(issues(storage("order_through = false, 2\ntarget_level = 1.5"), schema).isEmpty());
    }

    @Test
    void orderThroughFalseOrAbsentIsFine() {
        LinterSchema schema = LinterSchema.loadDefault();
        assertTrue(issues(storage("order_through = false\ntarget_volume = 1500"), schema).isEmpty());
        assertTrue(issues(storage("target_volume = 1500"), schema).isEmpty());
        assertTrue(issues(storage("order_through = true"), schema).isEmpty());
    }

    @Test
    void disablingTheRuleSuppressesTheIssue() {
        LinterSchema schema = LinterSchema.loadDefault();
        schema.getValidationRule("overridden_params").setEnabled(false);
        assertTrue(issues(storage("order_through = true\ntarget_volume = 1500"), schema).isEmpty());
    }
}
