package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.ValidationResult;

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
     */
    void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result);

    /**
     * Get a description of what this validator checks.
     * Used for debugging and logging purposes.
     *
     * @return A brief description of the validation performed
     */
    String getDescription();
}