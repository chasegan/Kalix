package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.validators.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

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
            new NodeValidator(),
            new UniqueNameValidator()
        );
    }

    public SchemaManager getSchemaManager() {
        return schemaManager;
    }

    /**
     * Validate INI model content against the loaded schema.
     */
    public ValidationResult validate(String content) {
        return validate(content, null);
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
        boolean hasContent = false;

        // Check for input files
        if (!model.getInputFiles().isEmpty()) {
            hasContent = true;
        }

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
}