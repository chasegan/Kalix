package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.validators.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main linter for validating Kalix model files against schema rules.
 */
public class ModelLinter {

    private static final Logger logger = LoggerFactory.getLogger(ModelLinter.class);

    private final SchemaManager schemaManager;
    private final List<ValidationStrategy> validators;

    public ModelLinter(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        this.validators = Arrays.asList(
            new SectionValidator(),
            new FileValidator(),
            new ReferenceValidator(),
            new NodeOrderingValidator(),
            new NodeValidator(),
            new UniqueNameValidator(),
            new DuplicatePropertyValidator()
        );
    }

    public SchemaManager getSchemaManager() {
        return schemaManager;
    }

    /**
     * Validate INI model content against the loaded schema with a base directory for resolving relative paths.
     *
     * @param content The model content to validate
     * @param baseDirectory The base directory for resolving relative file paths (null to use current directory)
     * @return ValidationResult containing any issues found
     */
    public ValidationResult validate(String content, java.io.File baseDirectory) {
        ValidationResult result = new ValidationResult();

        if (!schemaManager.isLintingEnabled()) {
            return result; // Linting disabled
        }

        LinterSchema schema = schemaManager.getCurrentSchema();
        if (schema == null) {
            result.addIssue(1, "Linter schema not loaded", ValidationRule.Severity.ERROR, "schema_error");
            return result;
        }

        try {
            // Pre-parsing check: detect standalone carriage return characters
            checkForStandaloneCarriageReturns(content, result);

            INIModelParser.ParsedModel model = INIModelParser.parse(content);

            // Skip validation for empty models (no sections or content)
            if (isModelEmpty(model)) {
                return result; // Return empty result - no errors for empty models
            }

            validateModel(model, schema, result, baseDirectory);
        } catch (Exception e) {
            logger.error("Error during validation", e);
            result.addIssue(1, "Validation failed: " + e.getMessage(), ValidationRule.Severity.ERROR, "validation_error");
        }

        return result;
    }

    /**
     * Validate pre-parsed model against the loaded schema.
     * Used by performance-optimized parsers.
     */
    public ValidationResult validateParsedModel(INIModelParser.ParsedModel model) {
        return validateParsedModel(model, null);
    }

    /**
     * Validate pre-parsed model against the loaded schema with a base directory.
     * Used by performance-optimized parsers.
     *
     * @param model The parsed model to validate
     * @param baseDirectory The base directory for resolving relative file paths (null to use current directory)
     * @return ValidationResult containing any issues found
     */
    public ValidationResult validateParsedModel(INIModelParser.ParsedModel model, java.io.File baseDirectory) {
        ValidationResult result = new ValidationResult();

        if (!schemaManager.isLintingEnabled()) {
            return result; // Linting disabled
        }

        LinterSchema schema = schemaManager.getCurrentSchema();
        if (schema == null) {
            result.addIssue(1, "Linter schema not loaded", ValidationRule.Severity.ERROR, "schema_error");
            return result;
        }

        try {
            // Skip validation for empty models (no sections or content)
            if (isModelEmpty(model)) {
                return result; // Return empty result - no errors for empty models
            }

            validateModel(model, schema, result, baseDirectory);
        } catch (Exception e) {
            logger.error("Error during validation", e);
            result.addIssue(1, "Validation failed: " + e.getMessage(), ValidationRule.Severity.ERROR, "validation_error");
        }

        return result;
    }

    /**
     * Check if the parsed model is effectively empty (no meaningful content).
     * This includes models that are completely empty or contain only comments.
     */
    private boolean isModelEmpty(INIModelParser.ParsedModel model) {
        // Check if model has any sections
        if (model.getSections().isEmpty()) {
            return true;
        }

        // Check if model has any meaningful content
        // (sections exist but they're all empty, or only contain empty sections)
        boolean hasContent = !model.getInputFiles().isEmpty();

        // Check for input files

        // Check for output references
        if (!model.getOutputReferences().isEmpty()) {
            hasContent = true;
        }

        // Check for node sections
        if (!model.getNodes().isEmpty()) {
            hasContent = true;
        }

        // Check for any sections with properties
        for (INIModelParser.Section section : model.getSections().values()) {
            if (!section.getProperties().isEmpty()) {
                hasContent = true;
                break;
            }
        }

        return !hasContent;
    }

    private void validateModel(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        for (ValidationStrategy validator : validators) {
            try {
                validator.validate(model, schema, result, baseDirectory);
            } catch (Exception e) {
                logger.error("Error in validator {}: {}", validator.getDescription(), e.getMessage());
            }
        }
    }

    /**
     * Pre-parsing check: detects standalone carriage return characters (\r not followed by \n).
     * These can cause parsing errors and appear as invisible characters in some editors.
     */
    private void checkForStandaloneCarriageReturns(String content, ValidationResult result) {
        // Pattern to match \r NOT followed by \n
        Pattern pattern = Pattern.compile("\\r(?!\\n)");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            int position = matcher.start();
            int lineNumber = getLineNumber(content, position);
            int columnNumber = getColumnNumber(content, position);

            // Extract context around the CR for the error message
            String context = getContextAroundPosition(content, position, 20);

            result.addIssue(
                lineNumber,
                String.format("Invalid carriage return character at column %d in: '%s'. Remove hidden control characters.",
                    columnNumber, context),
                ValidationRule.Severity.ERROR,
                "invalid_line_ending"
            );
        }
    }

    /**
     * Get the line number (1-based) for a given character position in the content.
     */
    private int getLineNumber(String content, int position) {
        int lineNumber = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    /**
     * Get the column number (1-based) for a given character position in the content.
     */
    private int getColumnNumber(String content, int position) {
        int columnNumber = 1;
        for (int i = position - 1; i >= 0; i--) {
            if (content.charAt(i) == '\n') {
                break;
            }
            columnNumber++;
        }
        return columnNumber;
    }

    /**
     * Extract context around a position for error messages.
     */
    private String getContextAroundPosition(String content, int position, int contextLength) {
        int start = Math.max(0, position - contextLength);
        int end = Math.min(content.length(), position + contextLength);

        String context = content.substring(start, end);

        // Replace the CR with a visible marker for the error message
        context = context.replace("\r", "<CR>");

        // Truncate long contexts
        if (context.length() > 40) {
            context = "..." + context.substring(Math.max(0, context.length() - 40));
        }

        return context;
    }
}