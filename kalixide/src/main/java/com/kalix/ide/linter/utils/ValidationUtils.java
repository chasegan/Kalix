package com.kalix.ide.linter.utils;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared validation utilities to ensure consistency between ModelLinter and IncrementalValidator.
 * All validation logic should be centralized here to avoid duplication and ensure synchronization.
 */
public class ValidationUtils {

    // Patterns - single source of truth
    private static final Pattern OUTPUT_REFERENCE_PATTERN = Pattern.compile("^node\\.[\\w_]+\\.(dsflow|usflow|storage)$");
    private static final Pattern INI_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    /**
     * Validate output references using the standard pattern.
     * @param outputRefs List of output references to validate
     * @param model Parsed model for line number lookup
     * @param result ValidationResult to add issues to
     */
    public static void validateOutputReferences(List<String> outputRefs, INIModelParser.ParsedModel model, ValidationResult result) {
        for (String outputRef : outputRefs) {
            if (!OUTPUT_REFERENCE_PATTERN.matcher(outputRef).matches()) {
                // Get the actual line number from the model
                Integer lineNumber = model.getOutputReferenceLineNumbers().get(outputRef);
                int reportLine = lineNumber != null ? lineNumber : getOutputsSectionFallbackLine(model);
                result.addIssue(reportLine,
                              "Invalid output reference format: " + outputRef,
                              ValidationRule.Severity.ERROR, "invalid_output_reference");
            }
        }
    }

    /**
     * Validate output references against schema rule (for full validation).
     * @param outputRefs List of output references to validate
     * @param model Parsed model for line number lookup
     * @param schema Schema containing validation rules
     * @param result ValidationResult to add issues to
     */
    public static void validateOutputReferencesWithSchema(List<String> outputRefs, INIModelParser.ParsedModel model,
                                                        LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("output_references");
        if (rule == null || !rule.isEnabled()) return;

        // Use pattern from schema rule, fallback to our standard pattern
        String patternStr = rule.getPattern();
        Pattern outputPattern;
        if (patternStr != null && !patternStr.trim().isEmpty()) {
            outputPattern = Pattern.compile(patternStr);
        } else {
            outputPattern = OUTPUT_REFERENCE_PATTERN;
        }

        for (String outputRef : outputRefs) {
            if (!outputPattern.matcher(outputRef).matches()) {
                Integer lineNumber = model.getOutputReferenceLineNumbers().get(outputRef);
                int reportLine = lineNumber != null ? lineNumber : getOutputsSectionFallbackLine(model);
                result.addIssue(reportLine,
                              "Invalid output reference format: " + outputRef,
                              rule.getSeverity(), "invalid_output_reference");
            }
        }
    }

    /**
     * Validate INI version format.
     * @param versionValue The version string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidIniVersion(String versionValue) {
        return versionValue != null && INI_VERSION_PATTERN.matcher(versionValue).matches();
    }

    /**
     * Get fallback line number for outputs section errors.
     */
    private static int getOutputsSectionFallbackLine(INIModelParser.ParsedModel model) {
        INIModelParser.Section outputsSection = model.getSections().get("outputs");
        if (outputsSection != null) {
            // If we have any output references with line numbers, use the first one as approximation
            if (!model.getOutputReferenceLineNumbers().isEmpty()) {
                return model.getOutputReferenceLineNumbers().values().iterator().next();
            }
            // Otherwise use the section start line + 2
            return Math.max(outputsSection.getStartLine() + 2, 1);
        }
        return 1; // Last resort
    }

    /**
     * Get the standard output reference pattern.
     */
    public static Pattern getOutputReferencePattern() {
        return OUTPUT_REFERENCE_PATTERN;
    }

    /**
     * Get the standard INI version pattern.
     */
    public static Pattern getIniVersionPattern() {
        return INI_VERSION_PATTERN;
    }
}