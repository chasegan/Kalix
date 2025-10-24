package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.schema.DataType;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.schema.ParameterDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Validates individual nodes including their type, required parameters, and parameter formats.
 */
public class NodeValidator implements ValidationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(NodeValidator.class);
    private final FunctionExpressionValidator functionValidator = new FunctionExpressionValidator();

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            validateNode(node, schema, result);
        }
    }

    @Override
    public String getDescription() {
        return "Node type and parameter validation";
    }

    private void validateNode(INIModelParser.NodeSection node, LinterSchema schema, ValidationResult result) {
        String nodeType = node.getNodeType();
        if (nodeType == null) {
            result.addIssue(node.getStartLine(),
                          "Node missing required 'type' parameter: " + node.getNodeName(),
                          ValidationRule.Severity.ERROR, "missing_node_type");
            return;
        }

        NodeTypeDefinition typeDef = schema.getNodeType(nodeType);
        if (typeDef == null) {
            INIModelParser.Property typeProp = node.getProperties().get("type");
            int lineNumber = typeProp != null ? typeProp.getLineNumber() : node.getStartLine();
            result.addIssue(lineNumber,
                          "Unknown node type: " + nodeType,
                          ValidationRule.Severity.ERROR, "unknown_node_type");
            return;
        }

        // Check required parameters
        for (String requiredParam : typeDef.requiredParams) {
            if (!node.getProperties().containsKey(requiredParam)) {
                result.addIssue(node.getStartLine(),
                              "Node '" + node.getNodeName() + "' missing required parameter: " + requiredParam,
                              ValidationRule.Severity.ERROR, "missing_required_param");
            }
        }

        // Validate parameter types and formats
        for (INIModelParser.Property prop : node.getProperties().values()) {
            validateNodeProperty(node, prop, typeDef, schema, result);
        }
    }

    private void validateNodeProperty(INIModelParser.NodeSection node, INIModelParser.Property prop,
                                    NodeTypeDefinition typeDef, LinterSchema schema,
                                    ValidationResult result) {

        String paramName = prop.getKey();

        // Check if parameter is allowed for this node type
        if (!typeDef.getAllowedParams().contains(paramName)) {
            result.addIssue(prop.getLineNumber(),
                          "Unknown parameter '" + paramName + "' for node type '" + typeDef.name + "'",
                          ValidationRule.Severity.WARNING, "unknown_parameter");
            return;
        }

        // Get parameter definition to check type
        ParameterDefinition paramDef = typeDef.getParameterDefinition(paramName);
        if (paramDef == null) {
            // Parameter not defined in schema - downstream params are validated by ReferenceValidator
            return;
        }

        // Validate based on parameter type from schema
        switch (paramDef.type) {
            case "function_expression":
                validateFunctionExpression(prop, result);
                break;
            case "number":
                validateNumber(prop, schema, result, paramDef.min, paramDef.max);
                break;
            case "integer":
                Integer min = paramDef.min != null ? paramDef.min.intValue() : null;
                Integer max = paramDef.max != null ? paramDef.max.intValue() : null;
                validateInteger(prop, schema, result, min, max);
                break;
            case "coordinates":
                validateCoordinates(prop, schema, result);
                break;
            case "number_sequence":
                validateNumberSequenceWithCount(node, prop, typeDef, schema, result);
                break;
            case "string":
                if (paramDef.pattern != null) {
                    validateStringPattern(prop, paramDef.pattern, result);
                }
                break;
            case "literal":
                // Literal values are validated during parsing
                break;
            case "downstream_reference":
                // Downstream references are validated by ReferenceValidator
                break;
            default:
                // Unknown parameter type - skip validation
                break;
        }
    }

    private void validateFunctionExpression(INIModelParser.Property prop, ValidationResult result) {
        List<String> errors = functionValidator.validate(prop.getValue());

        for (String error : errors) {
            // Determine severity - warnings for division by zero, errors for everything else
            ValidationRule.Severity severity = error.toLowerCase().startsWith("warning")
                ? ValidationRule.Severity.WARNING
                : ValidationRule.Severity.ERROR;

            result.addIssue(prop.getLineNumber(), error, severity, "function_expression_error");
        }
    }

    // Helper validation methods extracted from ModelLinter
    private void validateCoordinates(INIModelParser.Property prop, LinterSchema schema, ValidationResult result) {
        DataType coordType = schema.getDataType("coordinates");
        if (coordType != null && !coordType.matches(prop.getValue())) {
            result.addIssue(prop.getLineNumber(),
                          "Invalid coordinate format. Expected: 'X, Y' (two comma-separated numbers)",
                          ValidationRule.Severity.ERROR, "invalid_coordinates");
        }
    }

    private void validateNumber(INIModelParser.Property prop, LinterSchema schema, ValidationResult result,
                              Double min, Double max) {
        DataType numberType = schema.getDataType("number");
        if (numberType != null && !numberType.matches(prop.getValue())) {
            result.addIssue(prop.getLineNumber(),
                          "Invalid number format: " + prop.getValue(),
                          ValidationRule.Severity.ERROR, "invalid_number");
            return;
        }

        // Check bounds if specified
        try {
            double value = Double.parseDouble(prop.getValue());
            if (min != null && value < min) {
                result.addIssue(prop.getLineNumber(),
                              "Value must be >= " + min + ": " + prop.getValue(),
                              ValidationRule.Severity.ERROR, "value_out_of_range");
            }
            if (max != null && value > max) {
                result.addIssue(prop.getLineNumber(),
                              "Value must be <= " + max + ": " + prop.getValue(),
                              ValidationRule.Severity.ERROR, "value_out_of_range");
            }
        } catch (NumberFormatException e) {
            // Already handled by pattern validation above
        }
    }

    private void validateNumberSequenceWithCount(INIModelParser.NodeSection node, INIModelParser.Property prop,
                                               NodeTypeDefinition typeDef, LinterSchema schema,
                                               ValidationResult result) {
        // First validate the format
        DataType seqType = schema.getDataType("number_sequence");
        if (seqType != null && !seqType.matches(prop.getValue())) {
            result.addIssue(prop.getLineNumber(),
                          "Invalid number sequence format. Expected comma-separated numbers",
                          ValidationRule.Severity.ERROR, "invalid_number_sequence");
        }

        // Get parameter definition for count validation
        ParameterDefinition paramDef = typeDef.getParameterDefinition(prop.getKey());
        if (paramDef == null || paramDef.count == null) {
            return; // No count constraint specified
        }

        // Count the actual number of values
        String[] values = prop.getValue().split("\\s*,\\s*");
        int actualCount = values.length;
        int expectedCount = paramDef.count;

        if (actualCount != expectedCount) {
            result.addIssue(prop.getLineNumber(),
                          String.format("Parameter '%s' expects %d values but got %d",
                                      prop.getKey(), expectedCount, actualCount),
                          ValidationRule.Severity.ERROR, "incorrect_parameter_count");
        }
    }

    private void validateInteger(INIModelParser.Property prop, LinterSchema schema, ValidationResult result,
                               Integer min, Integer max) {
        DataType integerType = schema.getDataType("integer");
        if (integerType != null && !integerType.matches(prop.getValue())) {
            result.addIssue(prop.getLineNumber(),
                          "Invalid integer format: " + prop.getValue(),
                          ValidationRule.Severity.ERROR, "invalid_integer");
            return;
        }

        // Check bounds if specified
        try {
            int value = Integer.parseInt(prop.getValue());
            if (min != null && value < min) {
                result.addIssue(prop.getLineNumber(),
                              "Value must be >= " + min + ": " + prop.getValue(),
                              ValidationRule.Severity.ERROR, "value_out_of_range");
            }
            if (max != null && value > max) {
                result.addIssue(prop.getLineNumber(),
                              "Value must be <= " + max + ": " + prop.getValue(),
                              ValidationRule.Severity.ERROR, "value_out_of_range");
            }
        } catch (NumberFormatException e) {
            // Already handled by pattern validation above
        }
    }

    private void validateStringPattern(INIModelParser.Property prop, String pattern, ValidationResult result) {
        try {
            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            if (!regex.matcher(prop.getValue()).matches()) {
                result.addIssue(prop.getLineNumber(),
                              "Invalid format for parameter '" + prop.getKey() + "': " + prop.getValue(),
                              ValidationRule.Severity.ERROR, "invalid_string_format");
            }
        } catch (java.util.regex.PatternSyntaxException e) {
            // Invalid regex in schema - log but don't fail validation
            logger.warn("Invalid regex pattern in schema for parameter {}: {}", prop.getKey(), pattern);
        }
    }
}