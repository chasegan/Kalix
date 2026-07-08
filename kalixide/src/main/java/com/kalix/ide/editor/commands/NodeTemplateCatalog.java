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
     *
     * <p>A load failure is never cached: it returns an empty map for that
     * call (so menus built from it simply have no template items) but
     * retries the load on the next call, rather than permanently disabling
     * the feature after one transient failure.</p>
     */
    public static synchronized LinkedHashMap<String, List<String>> getNodeTypes() {
        if (nodeTypes == null) {
            LinkedHashMap<String, List<String>> loaded = loadNodeTypes();
            if (loaded == null) {
                return new LinkedHashMap<>();
            }
            nodeTypes = loaded;
        }
        return nodeTypes;
    }

   /**
     * @return the parsed templates, or {@code null} if loading failed (logged
     *         at error level - the caller decides how to degrade)
     */
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
            logger.error("Failed to load node templates from {} - " +
                    "\"Insert node template\" will be unavailable until this is fixed", RESOURCE_PATH, e);
            return null;
        }
        return result;
    }
}
