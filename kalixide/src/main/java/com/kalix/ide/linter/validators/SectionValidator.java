package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.schema.SectionDefinition;
import com.kalix.ide.linter.utils.ValidationUtils;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates required sections and section properties based on schema definitions.
 */
public class SectionValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        validateSections(model, schema, result);
    }

    @Override
    public String getDescription() {
        return "Section and property validation based on schema";
    }

    private void validateSections(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        Map<String, SectionDefinition> sectionDefs = schema.getSections();

        for (SectionDefinition sectionDef : sectionDefs.values()) {
            INIModelParser.Section modelSection = model.getSections().get(sectionDef.name);

            // Check if required section is missing
            if (sectionDef.required && modelSection == null) {
                result.addIssue(1, "Missing required section: [" + sectionDef.name + "]",
                              ValidationRule.Severity.ERROR, "missing_section");
                continue;
            }

            // If section exists, validate its properties
            if (modelSection != null) {
                validateSectionProperties(modelSection, sectionDef, result);
            }
        }
    }

    private void validateSectionProperties(INIModelParser.Section modelSection,
                                           SectionDefinition sectionDef,
                                           ValidationResult result) {
        for (SectionDefinition.PropertyDefinition propDef : sectionDef.properties.values()) {
            INIModelParser.Property modelProp = modelSection.getProperties().get(propDef.name);

            // Check if required property is missing
            if (propDef.required && modelProp == null) {
                result.addIssue(modelSection.getStartLine() + 1,
                              "Missing required property: " + propDef.name,
                              ValidationRule.Severity.ERROR, "missing_property_" + propDef.name);
                continue;
            }

            // If property exists, validate its format
            if (modelProp != null && propDef.pattern != null) {
                String value = modelProp.getValue();
                if (!Pattern.matches(propDef.pattern, value)) {
                    result.addIssue(modelProp.getLineNumber(),
                                  "Invalid " + propDef.name + " format",
                                  ValidationRule.Severity.ERROR, "invalid_" + propDef.name);
                }
            }

            // Special validation for version type
            if (modelProp != null && "version".equals(propDef.type)) {
                String value = modelProp.getValue();
                if (!ValidationUtils.isValidIniVersion(value)) {
                    result.addIssue(modelProp.getLineNumber(),
                                  "Invalid version format. Expected: X.Y.Z",
                                  ValidationRule.Severity.ERROR, "invalid_version");
                }
            }
        }
    }
}