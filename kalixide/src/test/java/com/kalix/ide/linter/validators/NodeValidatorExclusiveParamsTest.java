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
 * A storage cannot have both target_level and target_volume, as the engine
 * refuses at configure. The schema's exclusive_params group says so.
 */
class NodeValidatorExclusiveParamsTest {

    private static String storage(String targets) {
        return """
                [node.weir]
                type = storage
                loc = 0, 0
                dimensions = 0, 0, 0, 0, 1, 1000, 1, 0
                %s
                """.formatted(targets);
    }

    private static List<ValidationIssue> issues(String model, LinterSchema schema) {
        ValidationResult result = new ValidationResult();
        new NodeValidator().validate(INIModelParser.parse(model), schema, result, null);
        return result.getIssues().stream()
                .filter(i -> "exclusive_params".equals(i.getRuleName()))
                .toList();
    }

    @Test
    void bothTargetsReportedOnTheLaterLine() {
        String model = storage("target_level = 1.5\ntarget_volume = 1500");
        List<ValidationIssue> found = issues(model, LinterSchema.loadDefault());

        assertEquals(1, found.size());
        assertEquals(ValidationRule.Severity.ERROR, found.get(0).getSeverity());
        assertEquals(6, found.get(0).getLineNumber(), "reported on target_volume, the second given");
        assertEquals("Node 'weir' cannot have both target_level and target_volume", found.get(0).getMessage());
    }

    @Test
    void threeOfAGroupAreNamedWithoutSayingBoth() {
        // No shipped group has three members, so the test adds one.
        LinterSchema schema = LinterSchema.loadDefault();
        schema.getNodeType("storage").exclusiveParams
                .add(List.of("target_level", "target_volume", "forced_level"));
        String model = storage("forced_level = 2\ntarget_level = 1.5\ntarget_volume = 1500");
        List<String> messages = issues(model, schema).stream().map(ValidationIssue::getMessage).toList();

        assertTrue(messages.contains(
                "Node 'weir' can have only one of forced_level, target_level and target_volume"), messages.toString());
    }

    @Test
    void parameterNamesMatchWhateverTheirCase() {
        String model = storage("Target_Level = 1.5\ntarget_volume = 1500");
        assertEquals(1, issues(model, LinterSchema.loadDefault()).size());
    }

    @Test
    void eitherTargetAloneIsFine() {
        LinterSchema schema = LinterSchema.loadDefault();
        assertTrue(issues(storage("target_level = 1.5"), schema).isEmpty());
        assertTrue(issues(storage("target_volume = 1500"), schema).isEmpty());
    }

    @Test
    void disablingTheRuleSuppressesTheIssue() {
        LinterSchema schema = LinterSchema.loadDefault();
        schema.getValidationRule("exclusive_params").setEnabled(false);
        assertTrue(issues(storage("target_level = 1.5\ntarget_volume = 1500"), schema).isEmpty());
    }
}
