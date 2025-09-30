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
            validateModel(model, schema, result);
        } catch (Exception e) {
            logger.error("Error during validation", e);
            result.addIssue(1, "Validation failed: " + e.getMessage(), ValidationRule.Severity.ERROR, "validation_error");
        }

        return result;
    }

    private void validateModel(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        for (ValidationStrategy validator : validators) {
            try {
                validator.validate(model, schema, result);
            } catch (Exception e) {
                logger.error("Error in validator {}: {}", validator.getDescription(), e.getMessage());
            }
        }
    }
}