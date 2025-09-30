package com.kalix.ide.linter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Main linter for validating Kalix model files against schema rules.
 */
public class ModelLinter {

    private static final Logger logger = LoggerFactory.getLogger(ModelLinter.class);

    private final SchemaManager schemaManager;
    private final Map<String, Long> fileExistenceCache = new HashMap<>();
    private long cacheTimeout = 5000; // 5 seconds

    public ModelLinter(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
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
        // Validate required sections
        validateRequiredSections(model, schema, result);

        // Validate file paths in inputs section
        validateInputFiles(model, schema, result);

        // Validate output references
        validateOutputReferences(model, schema, result);

        // Validate nodes
        validateNodes(model, schema, result);

        // Validate downstream references
        validateDownstreamReferences(model, schema, result);

        // Check for duplicate node names
        validateUniqueNodeNames(model, schema, result);
    }

    private void validateRequiredSections(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        // Check for required sections based on schema
        Set<String> requiredSections = Set.of("attributes", "inputs", "outputs");

        for (String sectionName : requiredSections) {
            if (!model.getSections().containsKey(sectionName)) {
                result.addIssue(1, "Missing required section: [" + sectionName + "]",
                              ValidationRule.Severity.ERROR, "missing_section");
            }
        }

        // Validate attributes section
        INIModelParser.Section attributesSection = model.getSections().get("attributes");
        if (attributesSection != null) {
            validateIniVersion(attributesSection, result);
        }
    }

    private void validateIniVersion(INIModelParser.Section attributesSection, ValidationResult result) {
        INIModelParser.Property versionProp = attributesSection.getProperties().get("ini_version");
        if (versionProp == null) {
            result.addIssue(attributesSection.getStartLine() + 1,
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

    private void validateInputFiles(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("file_paths");
        if (rule == null || !rule.isEnabled()) return;

        List<String> inputFiles = model.getInputFiles();
        for (String filePath : inputFiles) {
            if (!fileExists(filePath)) {
                // Find the line number for this file path
                int lineNumber = findFilePathLineNumber(model, filePath);
                result.addIssue(lineNumber,
                              "Input file does not exist: " + filePath,
                              rule.getSeverity(), "file_not_found");
            }
        }
    }

    private void validateOutputReferences(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        // Use shared validation logic
        ValidationUtils.validateOutputReferencesWithSchema(model.getOutputReferences(), model, schema, result);

        // Additional validation for node existence (beyond pattern matching)
        ValidationRule rule = schema.getValidationRule("output_references");
        if (rule == null || !rule.isEnabled()) return;

        Pattern outputPattern = ValidationUtils.getOutputReferencePattern();
        for (String outputRef : model.getOutputReferences()) {
            if (outputPattern.matcher(outputRef).matches()) {
                // Extract node name and check if it exists
                String[] parts = outputRef.split("\\.");
                if (parts.length >= 2) {
                    String nodeName = parts[1];
                    if (!model.getNodes().containsKey(nodeName)) {
                        int lineNumber = findOutputRefLineNumber(model, outputRef);
                        result.addIssue(lineNumber,
                                      "Output reference points to non-existent node: " + nodeName,
                                      rule.getSeverity(), "invalid_node_reference");
                    }
                }
            }
        }
    }

    private void validateNodes(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            validateNode(node, schema, result);
        }
    }

    private void validateNode(INIModelParser.NodeSection node, LinterSchema schema, ValidationResult result) {
        String nodeType = node.getNodeType();
        if (nodeType == null) {
            result.addIssue(node.getStartLine(),
                          "Node missing required 'type' parameter: " + node.getNodeName(),
                          ValidationRule.Severity.ERROR, "missing_node_type");
            return;
        }

        LinterSchema.NodeTypeDefinition typeDef = schema.getNodeType(nodeType);
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
                                    LinterSchema.NodeTypeDefinition typeDef, LinterSchema schema,
                                    ValidationResult result) {

        String paramName = prop.getKey();
        String paramValue = prop.getValue();

        // Check if parameter is allowed for this node type
        if (!typeDef.getAllowedParams().contains(paramName)) {
            result.addIssue(prop.getLineNumber(),
                          "Unknown parameter '" + paramName + "' for node type '" + typeDef.name + "'",
                          ValidationRule.Severity.WARNING, "unknown_parameter");
            return;
        }

        // Validate based on parameter type - simplified for common cases
        switch (paramName) {
            case "loc":
                validateCoordinates(prop, schema, result);
                break;
            case "area":
                validateNumber(prop, schema, result, 0.0, null); // Area must be positive
                break;
            case "params":
                // For params, we need to get the expected count from the node type definition
                validateNumberSequenceWithCount(node, prop, typeDef, schema, result);
                break;
            case "lag":
                validateInteger(prop, schema, result, 0, null); // Lag must be >= 0
                break;
            default:
                // Handle downstream parameters
                if (paramName.startsWith("ds_")) {
                    // Downstream validation will be handled separately
                }
                break;
        }
    }

    private void validateCoordinates(INIModelParser.Property prop, LinterSchema schema, ValidationResult result) {
        LinterSchema.DataType coordType = schema.getDataType("coordinates");
        if (coordType != null && !coordType.matches(prop.getValue())) {
            result.addIssue(prop.getLineNumber(),
                          "Invalid coordinate format. Expected: 'X, Y' (two comma-separated numbers)",
                          ValidationRule.Severity.ERROR, "invalid_coordinates");
        }
    }

    private void validateNumber(INIModelParser.Property prop, LinterSchema schema, ValidationResult result,
                              Double min, Double max) {
        LinterSchema.DataType numberType = schema.getDataType("number");
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

    private void validateNumberSequence(INIModelParser.Property prop, LinterSchema schema, ValidationResult result) {
        LinterSchema.DataType seqType = schema.getDataType("number_sequence");
        if (seqType != null && !seqType.matches(prop.getValue())) {
            result.addIssue(prop.getLineNumber(),
                          "Invalid number sequence format. Expected comma-separated numbers",
                          ValidationRule.Severity.ERROR, "invalid_number_sequence");
        }
    }

    private void validateNumberSequenceWithCount(INIModelParser.NodeSection node, INIModelParser.Property prop,
                                               LinterSchema.NodeTypeDefinition typeDef, LinterSchema schema,
                                               ValidationResult result) {
        // First validate the format
        validateNumberSequence(prop, schema, result);

        // Get parameter definition for count validation
        LinterSchema.ParameterDefinition paramDef = typeDef.getParameterDefinition(prop.getKey());
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
        LinterSchema.DataType integerType = schema.getDataType("integer");
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

    private void validateDownstreamReferences(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("dsnode_references");
        if (rule == null || !rule.isEnabled()) return;

        Set<String> nodeNames = model.getNodes().keySet();

        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            for (INIModelParser.Property prop : node.getProperties().values()) {
                if (prop.getKey().startsWith("ds_")) {
                    String referencedNode = prop.getValue();
                    if (!nodeNames.contains(referencedNode)) {
                        result.addIssue(prop.getLineNumber(),
                                      "Link points to non-existent node: " + referencedNode,
                                      rule.getSeverity(), "invalid_node_reference");
                    }
                }
            }
        }
    }

    private void validateUniqueNodeNames(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("unique_node_names");
        if (rule == null || !rule.isEnabled()) return;

        Map<String, List<Integer>> duplicates = INIModelParser.findDuplicateNodes(model);
        for (Map.Entry<String, List<Integer>> entry : duplicates.entrySet()) {
            String nodeName = entry.getKey();
            List<Integer> lineNumbers = entry.getValue();

            for (Integer lineNumber : lineNumbers) {
                result.addIssue(lineNumber,
                              "Duplicate node name: " + nodeName,
                              rule.getSeverity(), "duplicate_node_name");
            }
        }
    }

    // Helper methods
    private boolean fileExists(String filePath) {
        // Use cache to avoid repeated file system calls
        long now = System.currentTimeMillis();
        Long lastCheck = fileExistenceCache.get(filePath);

        if (lastCheck != null && (now - lastCheck) < cacheTimeout) {
            return true; // Assume it still exists within cache timeout
        }

        boolean exists = Files.exists(Paths.get(filePath));
        if (exists) {
            fileExistenceCache.put(filePath, now);
        } else {
            fileExistenceCache.remove(filePath);
        }

        return exists;
    }

    private int findFilePathLineNumber(INIModelParser.ParsedModel model, String filePath) {
        // This is a simplified implementation - in practice you'd track line numbers during parsing
        INIModelParser.Section inputsSection = model.getSections().get("inputs");
        return inputsSection != null ? inputsSection.getStartLine() + 1 : 1;
    }

    private int findOutputRefLineNumber(INIModelParser.ParsedModel model, String outputRef) {
        // Get the actual line number where this output reference appears
        Integer lineNumber = model.getOutputReferenceLineNumbers().get(outputRef);
        if (lineNumber != null) {
            return lineNumber;
        }

        // If lookup failed, try to find a reasonable line number in the outputs section
        INIModelParser.Section outputsSection = model.getSections().get("outputs");
        if (outputsSection != null) {
            // If we have any output references with line numbers, use the first one as a reasonable approximation
            if (!model.getOutputReferenceLineNumbers().isEmpty()) {
                int firstOutputLine = model.getOutputReferenceLineNumbers().values().iterator().next();
                return firstOutputLine;
            }
            // Otherwise use the section start line + 2
            return Math.max(outputsSection.getStartLine() + 2, 1);
        }

        // Last resort fallback
        return 1;
    }
}