package com.kalix.ide.editor.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Loads the built-in node template definitions from {@code /node_templates.json}
 * and exposes them for menu-building and template insertion.
 */
public final class NodeTemplateCatalog {

    private static final Logger logger = LoggerFactory.getLogger(NodeTemplateCatalog.class);
    private static final String RESOURCE_PATH = "/node_templates.json";

    private static List<NodeTemplate> templates;

    /**
     * One template.
     *
     * <p>{@code id} names the node that gets inserted ({@code id_1}, {@code id_2}, …)
     * and must be a valid INI identifier. {@code label} is the menu text. They are
     * separate on purpose: retitling a menu entry must not rename anyone's nodes, and
     * renaming an id must not disturb the UI.
     *
     * <p>{@code lines} are the INI body only. The {@code [node.<name>]} header is
     * generated from the id at insertion time, so it is not stored — and cannot drift.
     */
    public record NodeTemplate(String id, String label, List<String> lines) {
    }

    private NodeTemplateCatalog() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Returns the node templates in the order they are declared in the JSON.
     *
     * <p>A load failure is never cached: it returns an empty list for that call (so
     * menus built from it simply have no template items) but retries the load on the
     * next call, rather than permanently disabling the feature after one transient
     * failure.</p>
     */
    public static synchronized List<NodeTemplate> templates() {
        if (templates == null) {
            List<NodeTemplate> loaded = loadTemplates();
            if (loaded == null) {
                return List.of();
            }
            templates = loaded;
        }
        return templates;
    }

    /**
     * @return the template with the given id, or {@code null} if there is none
     */
    public static NodeTemplate byId(String id) {
        for (NodeTemplate template : templates()) {
            if (template.id().equals(id)) {
                return template;
            }
        }
        return null;
    }

    /**
     * @return the parsed templates as an unmodifiable list, or {@code null} if loading
     *         failed (logged at error level — the caller decides how to degrade)
     */
    private static List<NodeTemplate> loadTemplates() {
        List<NodeTemplate> result = new ArrayList<>();
        try (InputStream stream = NodeTemplateCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Node template resource not found: " + RESOURCE_PATH);
            }
            // Explicit charset: the platform default is UTF-8 on modern JDKs, but a
            // label with an accent should not depend on that staying true.
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = new ObjectMapper().readTree(content);
            JsonNode nodeTypesNode = root.path("node_types");

            Iterator<Map.Entry<String, JsonNode>> fields = nodeTypesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String id = entry.getKey();
                JsonNode definition = entry.getValue();

                String label = definition.path("label").asText(id);
                List<String> lines = new ArrayList<>();
                for (JsonNode lineNode : definition.path("lines")) {
                    lines.add(lineNode.asText());
                }
                result.add(new NodeTemplate(id, label, Collections.unmodifiableList(lines)));
            }
        } catch (Exception e) {
            logger.error("Failed to load node templates from {} - " +
                    "\"Insert node template\" will be unavailable until this is fixed", RESOURCE_PATH, e);
            return null;
        }
        // Unmodifiable: this is a process-wide singleton, and callers were previously
        // handed the live collections.
        return Collections.unmodifiableList(result);
    }
}
