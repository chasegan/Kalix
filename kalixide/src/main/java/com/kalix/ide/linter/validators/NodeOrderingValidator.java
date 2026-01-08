package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates that downstream node references (ds_1, ds_2, etc.) point to nodes
 * that appear below the current node in the model file.
 *
 * This enforces proper flow ordering in the model definition.
 */
public class NodeOrderingValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema,
                         ValidationResult result, File baseDirectory) {
        ValidationRule rule = schema.getValidationRule("node_ordering");
        ValidationRule.Severity severity = (rule != null) ? rule.getSeverity() : ValidationRule.Severity.ERROR;

        // Build lookup map: node name -> start line (O(n) setup, O(1) lookups)
        Map<String, Integer> nodeStartLines = new HashMap<>();
        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            nodeStartLines.put(node.getNodeName(), node.getStartLine());
        }

        // Check each node's downstream references
        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            for (INIModelParser.Property prop : node.getProperties().values()) {
                if (!prop.getKey().startsWith("ds_")) {
                    continue;
                }

                String referencedNodeName = prop.getValue();
                Integer referencedNodeLine = nodeStartLines.get(referencedNodeName);

                // Skip if referenced node doesn't exist (ReferenceValidator handles that)
                if (referencedNodeLine == null) {
                    continue;
                }

                // Check if referenced node appears BELOW current node
                if (referencedNodeLine <= node.getStartLine()) {
                    result.addIssue(prop.getLineNumber(),
                            "Can only link to nodes that appear below this node in the model file",
                            severity, "node_ordering");
                }
            }
        }
    }

    @Override
    public String getDescription() {
        return "Node ordering validation";
    }
}
