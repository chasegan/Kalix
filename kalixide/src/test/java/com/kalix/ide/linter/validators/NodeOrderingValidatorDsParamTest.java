package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies node ordering uses the shared ds_N parameter rule (review, linter
 * section): NodeOrderingValidator matched startsWith("ds_") where
 * ReferenceValidator deliberately used ^ds_\\d+$, so ds_1_outlet / ds_1_order
 * values (which are not node names) were spuriously ordering-checked.
 */
class NodeOrderingValidatorDsParamTest {

    // 'downstream' is defined ABOVE 'storage1', so ds_1 = downstream violates
    // ordering. ds_1_order's value ("downstream" here to make the trap real)
    // is NOT a node reference and must not be ordering-checked.
    private static final String MODEL = """
            [node.downstream]
            type = confluence
            loc = 1, 2

            [node.storage1]
            type = storage
            loc = 3, 4
            ds_1 = downstream
            ds_1_order = downstream
            """;

    @Test
    void onlyRealDsParamsAreOrderingChecked() {
        LinterSchema schema = LinterSchema.loadDefault();
        INIModelParser.ParsedModel model = INIModelParser.parse(MODEL);

        ValidationResult result = new ValidationResult();
        new NodeOrderingValidator().validate(model, schema, result, null);

        List<ValidationIssue> issues = result.getIssues();
        assertEquals(1, issues.size(),
                "only ds_1 must be ordering-checked, got: " + issues);
        assertEquals(8, issues.get(0).getLineNumber(), "issue must sit on the ds_1 line");
    }

    @Test
    void downstreamReferencesUseTheSharedRule() {
        INIModelParser.ParsedModel model = INIModelParser.parse("""
                [node.a]
                type = confluence
                loc = 1, 2
                ds_1 = b
                ds_1_outlet = main
                ds_1_order = 3

                [node.b]
                type = confluence
                loc = 3, 4
                """);

        Set<String> refs = INIModelParser.getDownstreamReferences(model);
        assertEquals(Set.of("b"), refs,
                "ds_1_outlet / ds_1_order values are not node references");
        assertTrue(refs.contains("b"));
    }
}
