package com.kalix.ide.linter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.schema.DataType;
import com.kalix.ide.linter.schema.NodeTypeDefinition;
import com.kalix.ide.linter.schema.ParameterDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Loads and manages the linter schema for validating Kalix model files.
 * Supports loading from embedded resources or external files.
 */
public class LinterSchema {

    private static final Logger logger = LoggerFactory.getLogger(LinterSchema.class);
    private static final String DEFAULT_SCHEMA_RESOURCE = "/linter/kalix-model-schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode schemaRoot;
    private Map<String, ValidationRule> validationRules = new HashMap<>();
    private Map<String, NodeTypeDefinition> nodeTypes = new HashMap<>();
    private Map<String, DataType> dataTypes = new HashMap<>();
    private String version;

    /**
     * Load schema from embedded resource.
     */
    public static LinterSchema loadDefault() {
        LinterSchema schema = new LinterSchema();
        try {
            schema.loadFromResource(DEFAULT_SCHEMA_RESOURCE);
            return schema;
        } catch (Exception e) {
            logger.error("Failed to load default schema", e);
            throw new RuntimeException("Failed to load default linter schema", e);
        }
    }

    /**
     * Load schema from external file path.
     */
    public static LinterSchema loadFromFile(Path schemaPath) {
        LinterSchema schema = new LinterSchema();
        try {
            String content = Files.readString(schemaPath);
            schema.loadFromJson(content);
            return schema;
        } catch (Exception e) {
            logger.error("Failed to load schema from file: " + schemaPath, e);
            throw new RuntimeException("Failed to load schema from: " + schemaPath, e);
        }
    }

    private void loadFromResource(String resourcePath) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new RuntimeException("Schema resource not found: " + resourcePath);
            }
            String content = new String(stream.readAllBytes());
            loadFromJson(content);
        }
    }

    private void loadFromJson(String jsonContent) throws Exception {
        schemaRoot = objectMapper.readTree(jsonContent);

        // Parse version
        version = schemaRoot.path("version").asText("unknown");

        // Parse validation rules
        parseValidationRules();

        // Parse node types
        parseNodeTypes();

        // Parse data types
        parseDataTypes();    }

    private void parseValidationRules() {
        JsonNode rulesNode = schemaRoot.path("validation_rules");
        if (rulesNode.isMissingNode()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = rulesNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String ruleName = entry.getKey();
            JsonNode ruleNode = entry.getValue();

            String description = ruleNode.path("description").asText("");
            String severityStr = ruleNode.path("severity").asText("error");
            ValidationRule.Severity severity = "warning".equalsIgnoreCase(severityStr) ?
                    ValidationRule.Severity.WARNING : ValidationRule.Severity.ERROR;
            String pattern = ruleNode.path("pattern").asText(null);
            String check = ruleNode.path("check").asText(null);

            ValidationRule rule = new ValidationRule(ruleName, description, severity, pattern, check);
            validationRules.put(ruleName, rule);
        }
    }

    private void parseNodeTypes() {
        JsonNode typesNode = schemaRoot.path("node_types");
        if (typesNode.isMissingNode()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = typesNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String typeName = entry.getKey();
            JsonNode typeNode = entry.getValue();

            NodeTypeDefinition nodeType = new NodeTypeDefinition();
            nodeType.name = typeName;
            nodeType.description = typeNode.path("description").asText("");

            // Parse required parameters
            JsonNode requiredNode = typeNode.path("required_params");
            if (requiredNode.isArray()) {
                for (JsonNode param : requiredNode) {
                    nodeType.requiredParams.add(param.asText());
                }
            }

            // Parse optional parameters
            JsonNode optionalNode = typeNode.path("optional_params");
            if (optionalNode.isArray()) {
                for (JsonNode param : optionalNode) {
                    nodeType.optionalParams.add(param.asText());
                }
            }

            // Parse downstream node parameters
            JsonNode dsnodeNode = typeNode.path("dsnode_params");
            if (dsnodeNode.isArray()) {
                for (JsonNode param : dsnodeNode) {
                    nodeType.dsnodeParams.add(param.asText());
                }
            }

            // Parse allowed outputs
            JsonNode allowedOutputsNode = typeNode.path("allowed_outputs");
            if (allowedOutputsNode.isArray()) {
                for (JsonNode output : allowedOutputsNode) {
                    nodeType.allowedOutputs.add(output.asText());
                }
            }

            // Parse parameter definitions
            JsonNode parametersNode = typeNode.path("parameters");
            if (parametersNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> paramFields = parametersNode.fields();
                while (paramFields.hasNext()) {
                    Map.Entry<String, JsonNode> paramEntry = paramFields.next();
                    String paramName = paramEntry.getKey();
                    JsonNode paramNode = paramEntry.getValue();

                    ParameterDefinition paramDef = new ParameterDefinition();
                    paramDef.name = paramName;
                    paramDef.type = paramNode.path("type").asText("");
                    paramDef.description = paramNode.path("description").asText("");

                    // Parse count for number_sequence type
                    if (paramNode.has("count")) {
                        paramDef.count = paramNode.path("count").asInt();
                    }

                    // Parse min/max for numeric types
                    if (paramNode.has("min")) {
                        paramDef.min = paramNode.path("min").asDouble();
                    }
                    if (paramNode.has("max")) {
                        paramDef.max = paramNode.path("max").asDouble();
                    }

                    // Parse pattern for custom validation
                    if (paramNode.has("pattern")) {
                        paramDef.pattern = paramNode.path("pattern").asText();
                    }

                    nodeType.parameterDefinitions.put(paramName, paramDef);
                }
            }

            nodeTypes.put(typeName, nodeType);
        }
    }

    private void parseDataTypes() {
        JsonNode typesNode = schemaRoot.path("data_types");
        if (typesNode.isMissingNode()) return;

        Iterator<Map.Entry<String, JsonNode>> fields = typesNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String typeName = entry.getKey();
            JsonNode typeNode = entry.getValue();

            DataType dataType = new DataType();
            dataType.name = typeName;
            dataType.pattern = typeNode.path("pattern").asText(null);
            dataType.parse = typeNode.path("parse").asText(null);

            if (dataType.pattern != null) {
                try {
                    dataType.compiledPattern = Pattern.compile(dataType.pattern);
                } catch (Exception e) {
                    logger.warn("Invalid regex pattern for data type {}: {}", typeName, dataType.pattern);
                }
            }

            dataTypes.put(typeName, dataType);
        }
    }

    // Getters
    public String getVersion() { return version; }
    public Map<String, ValidationRule> getValidationRules() { return new HashMap<>(validationRules); }
    public Map<String, NodeTypeDefinition> getNodeTypes() { return new HashMap<>(nodeTypes); }
    public Map<String, DataType> getDataTypes() { return new HashMap<>(dataTypes); }

    public ValidationRule getValidationRule(String name) { return validationRules.get(name); }
    public NodeTypeDefinition getNodeType(String name) { return nodeTypes.get(name); }
    public DataType getDataType(String name) { return dataTypes.get(name); }

    /**
     * Get the default schema JSON content as a string.
     * This is useful for exporting the default schema to a file.
     */
    public static String getDefaultSchemaContent() throws Exception {
        try (InputStream stream = LinterSchema.class.getResourceAsStream(DEFAULT_SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new RuntimeException("Schema resource not found: " + DEFAULT_SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes());
        }
    }

}