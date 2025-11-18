package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.utils.ValidationUtils;

import java.util.Set;

/**
 * Validates required sections and basic section structure.
 */
public class SectionValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        validateRequiredSections(model, result);
        validateKalixSection(model, result);
    }

    @Override
    public String getDescription() {
        return "Required sections and kalix section validation";
    }

    private void validateRequiredSections(INIModelParser.ParsedModel model, ValidationResult result) {
        Set<String> requiredSections = Set.of("kalix", "inputs", "outputs");

        for (String sectionName : requiredSections) {
            if (!model.getSections().containsKey(sectionName)) {
                result.addIssue(1, "Missing required section: [" + sectionName + "]",
                              ValidationRule.Severity.ERROR, "missing_section");
            }
        }
    }

    private void validateKalixSection(INIModelParser.ParsedModel model, ValidationResult result) {
        INIModelParser.Section kalixSection = model.getSections().get("kalix");
        if (kalixSection != null) {
            validateIniVersion(kalixSection, result);
        }
    }

    private void validateIniVersion(INIModelParser.Section kalixSection, ValidationResult result) {
        INIModelParser.Property versionProp = kalixSection.getProperties().get("ini_version");
        if (versionProp == null) {
            result.addIssue(kalixSection.getStartLine() + 1,
                          "Missing required property: ini_version",
                          ValidationRule.Severity.ERROR, "missing_ini_version");
        } else {
            String version = versionProp.getValue();
            if (!ValidationUtils.isValidIniVersion(version)) {
                result.addIssue(versionProp.getLineNumber(),
                              "Invalid ini_version format. Expected: X.Y.Z",
                              ValidationRule.Severity.ERROR, "invalid_ini_version");
            }
        }
    }
}