package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;

import java.io.File;

/**
 * Strategy interface for different types of validation.
 * Each validation strategy is responsible for validating a specific aspect of the model.
 */
public interface ValidationStrategy {

    /**
     * Validate the model against the schema and add any issues to the result.
     *
     * @param model The parsed INI model to validate
     * @param schema The linter schema containing validation rules
     * @param result The validation result to add issues to
     * @param baseDirectory The base directory for resolving relative paths (null to use current directory)
     */
    void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, File baseDirectory);

    /**
     * Get a description of what this validator checks.
     * Used for debugging and logging purposes.
     *
     * @return A brief description of the validation performed
     */
    String getDescription();
}