package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.utils.EngineNames;

import java.util.List;
import java.util.Map;

/**
 * Validates node names: each follows the engine's name rule, and none is repeated
 * (ignoring case).
 */
public class UniqueNameValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        ValidationRule formatRule = schema.getValidationRule("node_name_format");
        if (formatRule != null && formatRule.isEnabled()) {
            for (INIModelParser.NodeSection node : model.getAllNodeSections()) {
                if (!EngineNames.isValidNodeName(node.getNodeName())) {
                    result.addIssue(node.getStartLine(),
                                  EngineNames.NODE_NAME_RULE + ": " + node.getNodeName(),
                                  formatRule.getSeverity(), "node_name_format");
                }
            }
        }

        ValidationRule rule = schema.getValidationRule("unique_node_names");
        if (rule == null || !rule.isEnabled()) return;

        Map<String, List<Integer>> duplicates = INIModelParser.findDuplicateNodes(model);
        for (Map.Entry<String, List<Integer>> entry : duplicates.entrySet()) {
            String nodeName = entry.getKey();
            List<Integer> lineNumbers = entry.getValue();

            for (Integer lineNumber : lineNumbers) {
                result.addIssue(lineNumber,
                              "Duplicate node name: " + nodeName,
                              rule.getSeverity(), "duplicate_node_name");
            }
        }
    }

    @Override
    public String getDescription() {
        return "Unique node name validation";
    }
}