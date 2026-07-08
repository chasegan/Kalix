package com.kalix.ide.editor.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the built-in node template definitions from {@code /node_templates.json}
 * and exposes them for menu-building and template insertion.
 */
public final class NodeTemplateCatalog {

    private static final Logger logger = LoggerFactory.getLogger(NodeTemplateCatalog.class);
    private static final String RESOURCE_PATH = "/node_templates.json";

    private static LinkedHashMap<String, List<String>> nodeTypes;

    private NodeTemplateCatalog() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Returns the node type templates, keyed by template id (e.g. "gr4j",
     * "storage") in the order they're declared in the JSON, each mapped to
     * its raw INI lines.
     */
    public static synchronized LinkedHashMap<String, List<String>> getNodeTypes() {
        if (nodeTypes == null) {
            nodeTypes = loadNodeTypes();
        }
        return nodeTypes;
    }

    /**
     * Turns a template id into a display label, e.g. {@code "routing_node"} ->
     * {@code "Routing Node"}. Special-cases {@code "gr4j"} -> {@code "GR4J"}.
     */
    public static String humanise(String nodeType) {
        if ("gr4j".equals(nodeType)) {
            return "GR4J";
        }
        String[] words = nodeType.split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private static LinkedHashMap<String, List<String>> loadNodeTypes() {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        try (InputStream stream = NodeTemplateCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new RuntimeException("Node template resource not found: " + RESOURCE_PATH);
            }
            String content = new String(stream.readAllBytes());
            JsonNode root = new ObjectMapper().readTree(content);
            JsonNode nodeTypesNode = root.path("node_types");

            Iterator<Map.Entry<String, JsonNode>> fields = nodeTypesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                List<String> lines = new java.util.ArrayList<>();
                for (JsonNode lineNode : entry.getValue()) {
                    lines.add(lineNode.asText());
                }
                result.put(entry.getKey(), lines);
            }
        } catch (Exception e) {
            logger.error("Failed to load node templates from {}", RESOURCE_PATH, e);
        }
        return result;
    }
}
