package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.utils.ValidationUtils;

import java.util.Set;

/**
 * Validates output references and downstream node references.
 */
public class ReferenceValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        validateOutputReferences(model, schema, result);
        validateDownstreamReferences(model, schema, result);
    }

    @Override
    public String getDescription() {
        return "Output and downstream reference validation";
    }

    private void validateOutputReferences(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        // Shared validation logic covers format checks and, in the schema's
        // node_output_validation mode, node existence and allowed outputs.
        // (A second node-existence loop here used to duplicate that diagnostic.)
        ValidationUtils.validateOutputReferencesWithSchema(model.getOutputReferences(), model, schema, result);
    }

    private void validateDownstreamReferences(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("dsnode_references");
        if (rule == null || !rule.isEnabled()) return;

        Set<String> nodeNames = model.getNodes().keySet();

        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            for (INIModelParser.Property prop : node.getProperties().values()) {
                // Only match ds_1, ds_2, etc. - not ds_1_outlet, ds_1_order, etc.
                if (ValidationUtils.isDsNodeParam(prop.getKey())) {
                    String referencedNode = prop.getValue();
                    if (!nodeNames.contains(referencedNode)) {
                        result.addIssue(prop.getLineNumber(),
                                      "Link points to non-existent node: " + referencedNode,
                                      rule.getSeverity(), "invalid_node_reference");
                    }
                }
            }
        }
    }

}