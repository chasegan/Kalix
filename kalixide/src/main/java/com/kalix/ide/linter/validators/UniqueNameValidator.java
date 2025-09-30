package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;

import java.util.List;
import java.util.Map;

/**
 * Validates that node names are unique throughout the model.
 */
public class UniqueNameValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
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