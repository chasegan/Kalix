package com.kalix.ide.linter.model;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.schema.NodeTypeDefinition;

import java.io.File;
import java.util.Set;

/**
 * Immutable context for expression validation.
 * Encapsulates all information needed to validate function expressions,
 * including the current node context for 'this' reference resolution.
 *
 * <p>Use the {@link Builder} to create instances:</p>
 * <pre>{@code
 * ValidationContext context = ValidationContext.builder()
 *     .model(model)
 *     .schema(schema)
 *     .currentNode(node)
 *     .build();
 * }</pre>
 */
public final class ValidationContext {

    private final INIModelParser.ParsedModel model;
    private final LinterSchema schema;
    private final INIModelParser.NodeSection currentNode;
    private final File baseDirectory;

    private ValidationContext(Builder builder) {
        this.model = builder.model;
        this.schema = builder.schema;
        this.currentNode = builder.currentNode;
        this.baseDirectory = builder.baseDirectory;
    }

    /**
     * Get the parsed model, or null if not available.
     */
    public INIModelParser.ParsedModel getModel() {
        return model;
    }

    /**
     * Get the linter schema, or null if not available.
     */
    public LinterSchema getSchema() {
        return schema;
    }

    /**
     * Get the current node being validated, or null if not in a node context.
     */
    public INIModelParser.NodeSection getCurrentNode() {
        return currentNode;
    }

    /**
     * Get the base directory for file resolution, or null if not available.
     */
    public File getBaseDirectory() {
        return baseDirectory;
    }

    /**
     * Check if this context has a current node (for 'this' reference validation).
     */
    public boolean hasCurrentNode() {
        return currentNode != null;
    }

    /**
     * Check if this context has model and schema (for full validation).
     */
    public boolean hasModelAndSchema() {
        return model != null && schema != null;
    }

    /**
     * Get the current node's type, or null if not available.
     */
    public String getCurrentNodeType() {
        if (currentNode == null) {
            return null;
        }
        return currentNode.getNodeType();
    }

    /**
     * Get the current node's name, or null if not available.
     */
    public String getCurrentNodeName() {
        if (currentNode == null) {
            return null;
        }
        return currentNode.getNodeName();
    }

    /**
     * Get the node type definition for the current node, or null if not available.
     */
    public NodeTypeDefinition getCurrentNodeTypeDefinition() {
        if (schema == null || currentNode == null) {
            return null;
        }
        String nodeType = currentNode.getNodeType();
        if (nodeType == null) {
            return null;
        }
        return schema.getNodeType(nodeType);
    }

    /**
     * Get allowed outputs for the current node type, or empty set if not available.
     */
    public Set<String> getCurrentNodeAllowedOutputs() {
        NodeTypeDefinition typeDef = getCurrentNodeTypeDefinition();
        if (typeDef == null) {
            return Set.of();
        }
        return typeDef.allowedOutputs;
    }

    /**
     * Create a new builder for ValidationContext.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create an empty context (for simple expression validation without model context).
     */
    public static ValidationContext empty() {
        return new Builder().build();
    }

    @Override
    public String toString() {
        return String.format("ValidationContext{hasModel=%b, hasSchema=%b, currentNode=%s}",
                model != null, schema != null,
                currentNode != null ? currentNode.getNodeName() : "null");
    }

    /**
     * Builder for creating ValidationContext instances.
     */
    public static final class Builder {
        private INIModelParser.ParsedModel model;
        private LinterSchema schema;
        private INIModelParser.NodeSection currentNode;
        private File baseDirectory;

        private Builder() {}

        /**
         * Set the parsed model.
         */
        public Builder model(INIModelParser.ParsedModel model) {
            this.model = model;
            return this;
        }

        /**
         * Set the linter schema.
         */
        public Builder schema(LinterSchema schema) {
            this.schema = schema;
            return this;
        }

        /**
         * Set the current node being validated (enables 'this' reference support).
         */
        public Builder currentNode(INIModelParser.NodeSection currentNode) {
            this.currentNode = currentNode;
            return this;
        }

        /**
         * Set the base directory for file resolution.
         */
        public Builder baseDirectory(File baseDirectory) {
            this.baseDirectory = baseDirectory;
            return this;
        }

        /**
         * Build the ValidationContext.
         */
        public ValidationContext build() {
            return new ValidationContext(this);
        }
    }
}
