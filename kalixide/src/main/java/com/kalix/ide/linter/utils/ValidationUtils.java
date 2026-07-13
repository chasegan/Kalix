package com.kalix.ide.linter.utils;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared validation utilities used by the ModelLinter validation system.
 * All validation logic should be centralized here to avoid duplication and ensure synchronization.
 */
public class ValidationUtils {

    // Patterns - single source of truth
    private static final Pattern OUTPUT_REFERENCE_PATTERN = Pattern.compile("^node\\.[\\w_]+\\.(dsflow|usflow|storage)$");
    private static final Pattern INI_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    // node.<name>.<property> - used to pick apart output/node references
    private static final Pattern NODE_PROPERTY_REFERENCE_PATTERN = Pattern.compile("^node\\.([\\w_]+)\\.([\\w_]+)$");
    // var.<block>.<key> - a series written by a [var.*] block, recordable in [outputs]
    private static final Pattern VAR_OUTPUT_REFERENCE_PATTERN = Pattern.compile("^var\\.([\\w_]+)\\.([\\w_]+)$");
    // Downstream link parameters: ds_1, ds_2, ... - deliberately NOT ds_1_outlet,
    // ds_1_order etc., whose values are not node names. Shared so every consumer
    // (reference validation, ordering validation, parsing) agrees on the rule.
    public static final Pattern DSNODE_PARAM_PATTERN = Pattern.compile("^ds_\\d+$");

    /**
     * True if the property key is a downstream link parameter (ds_1, ds_2, ...)
     * whose value names a downstream node.
     */
    public static boolean isDsNodeParam(String propertyKey) {
        return DSNODE_PARAM_PATTERN.matcher(propertyKey).matches();
    }

    /** Outcome of a {@code var.<block>.<key>} existence check. */
    public enum VarRefStatus { OK, UNKNOWN_BLOCK, UNKNOWN_KEY }

    /**
     * The ONE existence check for a {@code var.<block>.<key>} reference — a
     * series written by a {@code [var.*]} block — shared by the expression path
     * ({@code FunctionExpressionValidator}) and the {@code [outputs]} path.
     *
     * <p>Case-insensitive on both block and key, and the {@code phase} key is
     * excluded (the engine skips it when registering series, so
     * {@code var.acct.phase} is not a valid reference). This mirrors the engine,
     * which registers var series lowercased and resolves references via a
     * lowercased lookup ({@code ini_doc_model_io_0_0_1.rs}, {@code data_cache.rs}).</p>
     *
     * @param blockName the block name (the part after {@code var.})
     * @param key       the series key (the part after {@code var.<block>.})
     * @param model     the parsed model
     * @return {@link VarRefStatus#OK} when both resolve, otherwise which half failed
     */
    public static VarRefStatus checkVarReference(String blockName, String key, INIModelParser.ParsedModel model) {
        INIModelParser.Section varSection = model.getSections().get("var." + blockName.toLowerCase());
        if (varSection == null) {
            return VarRefStatus.UNKNOWN_BLOCK;
        }
        for (String k : varSection.getProperties().keySet()) {
            if (!k.equalsIgnoreCase("phase") && k.equalsIgnoreCase(key)) {
                return VarRefStatus.OK;
            }
        }
        return VarRefStatus.UNKNOWN_KEY;
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

        // Check if this rule uses node-specific output validation
        if ("node_output_validation".equals(rule.getCheck())) {
            validateNodeSpecificOutputs(outputRefs, model, schema, result, rule);
            return;
        }

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
                result.addIssue(outputRefReportLine(model, outputRef),
                              "Invalid output reference format: " + outputRef,
                              rule.getSeverity(), "invalid_output_reference");
            }
        }
    }

    /**
     * Validate output references using node-specific allowed outputs.
     * @param outputRefs List of output references to validate
     * @param model Parsed model for line number lookup
     * @param schema Schema containing node type definitions
     * @param result ValidationResult to add issues to
     * @param rule The validation rule for error reporting
     */
    private static void validateNodeSpecificOutputs(List<String> outputRefs, INIModelParser.ParsedModel model,
                                                   LinterSchema schema, ValidationResult result, ValidationRule rule) {
        for (String outputRef : outputRefs) {
            // A var series (var.<block>.<key>, a series written by a [var.*]
            // block) is recordable in [outputs] just like a node output.
            if (outputRef.startsWith("var.")) {
                validateVarOutputReference(outputRef, model, result, rule);
                continue;
            }

            java.util.regex.Matcher matcher = NODE_PROPERTY_REFERENCE_PATTERN.matcher(outputRef);
            if (!matcher.matches()) {
                result.addIssue(outputRefReportLine(model, outputRef),
                              "Invalid output reference format: " + outputRef + " (should be node.nodename.property)",
                              rule.getSeverity(), "invalid_output_reference");
                continue;
            }

            String nodeName = matcher.group(1);
            String outputProperty = matcher.group(2);

            // Check if the node exists
            if (!model.getNodes().containsKey(nodeName)) {
                result.addIssue(outputRefReportLine(model, outputRef),
                              "Output reference points to non-existent node: " + nodeName,
                              rule.getSeverity(), "invalid_node_reference");
                continue;
            }

            // Get the node's type
            INIModelParser.NodeSection node = model.getNodes().get(nodeName);
            String nodeType = null;
            for (INIModelParser.Property prop : node.getProperties().values()) {
                if ("type".equals(prop.getKey())) {
                    nodeType = prop.getValue();
                    break;
                }
            }

            if (nodeType == null) {
                result.addIssue(outputRefReportLine(model, outputRef),
                              "Node " + nodeName + " has no type defined",
                              rule.getSeverity(), "missing_node_type");
                continue;
            }

            // Get the node type definition and check allowed outputs
            com.kalix.ide.linter.schema.NodeTypeDefinition nodeTypeDef = schema.getNodeType(nodeType);
            if (nodeTypeDef == null) {
                result.addIssue(outputRefReportLine(model, outputRef),
                              "Unknown node type: " + nodeType,
                              rule.getSeverity(), "unknown_node_type");
                continue;
            }

            // Check if the output property is allowed for this node type
            // If no allowed outputs are defined, allow everything (fallback to no error/warning)
            if (!nodeTypeDef.allowedOutputs.isEmpty() && !nodeTypeDef.allowedOutputs.contains(outputProperty)) {
                result.addIssue(outputRefReportLine(model, outputRef),
                              "Output property '" + outputProperty + "' is not allowed for node type '" + nodeType + "'. Allowed outputs: " + nodeTypeDef.allowedOutputs,
                              rule.getSeverity(), "invalid_output_property");
            }
        }
    }

    /**
     * Validate a {@code var.<block>.<key>} output reference: the format, and
     * (when the block is present in the model) that the block and key exist.
     */
    private static void validateVarOutputReference(String outputRef, INIModelParser.ParsedModel model,
                                                   ValidationResult result, ValidationRule rule) {
        java.util.regex.Matcher matcher = VAR_OUTPUT_REFERENCE_PATTERN.matcher(outputRef);
        if (!matcher.matches()) {
            result.addIssue(outputRefReportLine(model, outputRef),
                          "Invalid output reference format: " + outputRef + " (should be var.block.name)",
                          rule.getSeverity(), "invalid_output_reference");
            return;
        }

        String blockName = matcher.group(1);
        String varName = matcher.group(2);
        switch (checkVarReference(blockName, varName, model)) {
            case UNKNOWN_BLOCK -> result.addIssue(outputRefReportLine(model, outputRef),
                          "Output reference points to unknown var block: " + blockName
                                  + " (no [var." + blockName + "] section is defined)",
                          rule.getSeverity(), "invalid_var_reference");
            case UNKNOWN_KEY -> result.addIssue(outputRefReportLine(model, outputRef),
                          "Output reference points to unknown var: " + varName
                                  + " (no '" + varName + "' in [var." + blockName + "])",
                          rule.getSeverity(), "invalid_var_reference");
            case OK -> { /* resolved */ }
        }
    }

    /**
     * Report line for an {@code [outputs]} reference: the line the reference was
     * written on, or a section-level fallback. Hoisted out of the many
     * output-validation sites that repeated this lookup verbatim.
     */
    private static int outputRefReportLine(INIModelParser.ParsedModel model, String outputRef) {
        Integer lineNumber = model.getOutputReferenceLineNumbers().get(outputRef);
        return lineNumber != null ? lineNumber : getOutputsSectionFallbackLine(model);
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
     * Validates a single node reference (e.g., "node.node13_inflow.ds_1").
     * Returns error message if invalid, null if valid.
     *
     * @param nodeRef The node reference to validate
     * @param model The parsed model
     * @param schema The linter schema
     * @return Error message if invalid, null if valid
     */
    public static String validateNodeReference(String nodeRef, INIModelParser.ParsedModel model, LinterSchema schema) {
        java.util.regex.Matcher matcher = NODE_PROPERTY_REFERENCE_PATTERN.matcher(nodeRef);

        if (!matcher.matches()) {
            return "Invalid node reference format: " + nodeRef + " (should be node.nodename.property)";
        }

        String nodeName = matcher.group(1);
        String outputProperty = matcher.group(2);

        // Check if the node exists
        if (!model.getNodes().containsKey(nodeName)) {
            return "Node reference points to non-existent node: " + nodeName;
        }

        // Get the node's type
        INIModelParser.NodeSection node = model.getNodes().get(nodeName);
        String nodeType = null;
        for (INIModelParser.Property prop : node.getProperties().values()) {
            if ("type".equals(prop.getKey())) {
                nodeType = prop.getValue();
                break;
            }
        }

        if (nodeType == null) {
            return "Node " + nodeName + " has no type defined";
        }

        // Get the node type definition and check allowed outputs
        com.kalix.ide.linter.schema.NodeTypeDefinition nodeTypeDef = schema.getNodeType(nodeType);
        if (nodeTypeDef == null) {
            return "Unknown node type: " + nodeType;
        }

        // Check if the output property is allowed for this node type
        // If no allowed outputs are defined, allow everything
        if (!nodeTypeDef.allowedOutputs.isEmpty() && !nodeTypeDef.allowedOutputs.contains(outputProperty)) {
            return "Output property '" + outputProperty + "' is not allowed for node type '" + nodeType + "'. Allowed outputs: " + nodeTypeDef.allowedOutputs;
        }

        return null; // Valid
    }
}