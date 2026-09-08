package com.kalix.ide.linter.utils;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.parsing.INIModelParser;

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
    private static final String DSNODE_PARAM_PREFIX = "ds_";

    /**
     * True if the property key is a downstream link parameter (ds_1, ds_2, ...)
     * whose value names a downstream node: {@code ds_} followed by one or more
     * digits and nothing else. Deliberately NOT ds_1_outlet, ds_1_order etc.,
     * whose values are not node names. Shared so every consumer (reference
     * validation, ordering validation, parsing) agrees on the rule. Runs on every
     * property key of every node per lint pass, hence a character scan rather
     * than a regex.
     */
    public static boolean isDsNodeParam(String propertyKey) {
        int length = propertyKey.length();
        if (length <= DSNODE_PARAM_PREFIX.length() || !propertyKey.startsWith(DSNODE_PARAM_PREFIX)) {
            return false;
        }
        for (int i = DSNODE_PARAM_PREFIX.length(); i < length; i++) {
            char c = propertyKey.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
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
     * Validate the model's {@code [outputs]} references against the schema rule.
     * @param model Parsed model (references and their lines)
     * @param schema Schema containing validation rules
     * @param result ValidationResult to add issues to
     */
    public static void validateOutputReferencesWithSchema(INIModelParser.ParsedModel model,
                                                        LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("output_references");
        if (rule == null || !rule.isEnabled()) return;

        // Check if this rule uses node-specific output validation
        if ("node_output_validation".equals(rule.getCheck())) {
            validateNodeSpecificOutputs(model, schema, result, rule);
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

        for (INIModelParser.ListEntry entry : model.getOutputReferenceEntries()) {
            if (!outputPattern.matcher(entry.text()).matches()) {
                result.addIssue(entry.lineNumber(),
                              "Invalid output reference format: " + entry.text(),
                              rule.getSeverity(), "invalid_output_reference");
            }
        }
    }

    /**
     * Validate output references using node-specific allowed outputs.
     * @param model Parsed model (references and their lines)
     * @param schema Schema containing node type definitions
     * @param result ValidationResult to add issues to
     * @param rule The validation rule for error reporting
     */
    private static void validateNodeSpecificOutputs(INIModelParser.ParsedModel model,
                                                   LinterSchema schema, ValidationResult result, ValidationRule rule) {
        // Iterate the entries, not the distinct references: a reference written
        // twice is wrong twice, on two lines.
        for (INIModelParser.ListEntry entry : model.getOutputReferenceEntries()) {
            String outputRef = entry.text();
            int line = entry.lineNumber();

            // A var series (var.<block>.<key>, a series written by a [var.*]
            // block) is recordable in [outputs] just like a node output.
            if (outputRef.startsWith("var.")) {
                validateVarOutputReference(outputRef, line, model, result, rule);
                continue;
            }

            // Account and RAS series (acc.<account or group>.<field>,
            // ras.<name>.fired) are recordable in [outputs] like node outputs.
            if (outputRef.startsWith("acc.") || outputRef.startsWith("ras.")) {
                validateAccountingOutputReference(outputRef, line, model, result, rule);
                continue;
            }

            java.util.regex.Matcher matcher = NODE_PROPERTY_REFERENCE_PATTERN.matcher(outputRef);
            if (!matcher.matches()) {
                result.addIssue(line,
                              "Invalid output reference format: " + outputRef + " (should be node.nodename.property)",
                              rule.getSeverity(), "invalid_output_reference");
                continue;
            }

            String nodeName = matcher.group(1);
            String outputProperty = matcher.group(2);

            // Check if the node exists
            INIModelParser.NodeSection node = model.getNodes().get(nodeName);
            if (node == null) {
                result.addIssue(line,
                              "Output reference points to non-existent node: " + nodeName,
                              rule.getSeverity(), "invalid_node_reference");
                continue;
            }

            String nodeType = node.getNodeType();
            if (nodeType == null) {
                result.addIssue(line,
                              "Node " + nodeName + " has no type defined",
                              rule.getSeverity(), "missing_node_type");
                continue;
            }

            // Get the node type definition and check allowed outputs
            com.kalix.ide.linter.schema.NodeTypeDefinition nodeTypeDef = schema.getNodeType(nodeType);
            if (nodeTypeDef == null) {
                result.addIssue(line,
                              "Unknown node type: " + nodeType,
                              rule.getSeverity(), "unknown_node_type");
                continue;
            }

            // Check if the output property is allowed for this node type
            // If no allowed outputs are defined, allow everything (fallback to no error/warning)
            if (!nodeTypeDef.allowedOutputs.isEmpty() && !nodeTypeDef.allowedOutputs.contains(outputProperty)) {
                result.addIssue(line,
                              "Output property '" + outputProperty + "' is not allowed for node type '" + nodeType + "'. Allowed outputs: " + nodeTypeDef.allowedOutputs,
                              rule.getSeverity(), "invalid_output_property");
            }
        }
    }

    /**
     * Validate a {@code var.<block>.<key>} output reference: the format, and
     * (when the block is present in the model) that the block and key exist.
     */
    private static void validateVarOutputReference(String outputRef, int line, INIModelParser.ParsedModel model,
                                                   ValidationResult result, ValidationRule rule) {
        java.util.regex.Matcher matcher = VAR_OUTPUT_REFERENCE_PATTERN.matcher(outputRef);
        if (!matcher.matches()) {
            result.addIssue(line,
                          "Invalid output reference format: " + outputRef + " (should be var.block.name)",
                          rule.getSeverity(), "invalid_output_reference");
            return;
        }

        String blockName = matcher.group(1);
        String varName = matcher.group(2);
        switch (checkVarReference(blockName, varName, model)) {
            case UNKNOWN_BLOCK -> result.addIssue(line,
                          "Output reference points to unknown var block: " + blockName
                                  + " (no [var." + blockName + "] section is defined)",
                          rule.getSeverity(), "invalid_var_reference");
            case UNKNOWN_KEY -> result.addIssue(line,
                          "Output reference points to unknown var: " + varName
                                  + " (no '" + varName + "' in [var." + blockName + "])",
                          rule.getSeverity(), "invalid_var_reference");
            case OK -> { /* resolved */ }
        }
    }

    /** Fields published per account, per account group, and per RAS. Mirror
     *  the engine's ACCOUNT_SERIES_FIELDS / GROUP_SERIES_FIELDS
     *  (src/hydrology/accounts/account_manager.rs) — groups aggregate every
     *  account field, `size` and `use` included. `size` and `initial` are
     *  static (fixed at load, not per-step), but are still valid field names
     *  here. */
    private static final java.util.Set<String> ACCOUNT_OUTPUT_FIELDS =
        java.util.Set.of("opening_balance", "closing_balance", "debits", "allocation", "use", "size", "initial");
    private static final java.util.Set<String> ACCOUNT_GROUP_OUTPUT_FIELDS = ACCOUNT_OUTPUT_FIELDS;
    private static final java.util.Set<String> RAS_OUTPUT_FIELDS = java.util.Set.of("fired", "pct");

    /**
     * Validate an accounting series reference in {@code [outputs]}:
     * {@code acc.<account or group>.<field>} or {@code ras.<name>.fired}.
     * Account names are not resolvable here (they live in the [acc.*] tables,
     * which the linter does not parse), so this checks shape and field name;
     * the engine catches unknown names at load.
     */
    private static void validateAccountingOutputReference(String outputRef, int line, INIModelParser.ParsedModel model,
                                                          ValidationResult result, ValidationRule rule) {
        String[] segments = outputRef.split("\\.");
        boolean isRas = outputRef.startsWith("ras.");
        if (segments.length != 3) {
            result.addIssue(line,
                          "Invalid output reference format: " + outputRef + " (should be "
                                  + (isRas ? "ras.name.fired" : "acc.name.field") + ")",
                          rule.getSeverity(), "invalid_output_reference");
            return;
        }

        java.util.Set<String> allowed;
        String what;
        if (isRas) {
            allowed = RAS_OUTPUT_FIELDS;
            what = "RAS";
        } else if (model.getSections().containsKey("acc." + segments[1].toLowerCase())) {
            allowed = ACCOUNT_GROUP_OUTPUT_FIELDS;
            what = "account group";
        } else {
            allowed = ACCOUNT_OUTPUT_FIELDS;
            what = "account";
        }

        if (!allowed.contains(segments[2].toLowerCase())) {
            java.util.List<String> known = new java.util.ArrayList<>(allowed);
            java.util.Collections.sort(known);
            result.addIssue(line,
                          "Unknown field for " + what + ": " + outputRef
                                  + " (expected one of: " + String.join(", ", known) + ")",
                          rule.getSeverity(), "invalid_output_reference");
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
        INIModelParser.NodeSection node = model.getNodes().get(nodeName);
        if (node == null) {
            return "Node reference points to non-existent node: " + nodeName;
        }

        String nodeType = node.getNodeType();
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