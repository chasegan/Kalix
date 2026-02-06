package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;

import java.util.List;
import java.util.Map;

/**
 * Validates that no property is defined more than once within a section.
 */
public class DuplicatePropertyValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        ValidationRule rule = schema.getValidationRule("unique_properties");
        if (rule == null || !rule.isEnabled()) return;

        for (INIModelParser.Section section : model.getSections().values()) {
            Map<String, List<Integer>> duplicates = INIModelParser.findDuplicateProperties(section);
            for (Map.Entry<String, List<Integer>> entry : duplicates.entrySet()) {
                String propertyKey = entry.getKey();
                List<Integer> lineNumbers = entry.getValue();

                for (Integer lineNumber : lineNumbers) {
                    result.addIssue(lineNumber,
                                  "Duplicate property '" + propertyKey + "' in [" + section.getName() + "]",
                                  rule.getSeverity(), "duplicate_property");
                }
            }
        }
    }

    @Override
    public String getDescription() {
        return "Duplicate property validation";
    }
}
