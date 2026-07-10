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

    /** Initialization-on-demand holder: loads exactly once, thread-safely, without locking. */
    private static final class Holder {
        private static final List<NodeTemplate> TEMPLATES = loadTemplates();
    }

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
     * Returns the node templates in the order they are declared in the JSON, or an
     * empty list if the resource could not be loaded.
     *
     * <p>The load happens once and its outcome is final — including failure. There is
     * nothing to retry: the resource is on the classpath, so the only ways to fail are
     * an absent resource (a packaging bug) or malformed JSON (a source bug), and
     * neither heals between calls. Retrying would re-log the same error on every
     * right-click and, worse, make the two menus disagree — the map rebuilds its
     * submenu per click and would recover, while the editor registers its commands once
     * at construction and never would. One outcome, both menus, no lifecycle to get
     * wrong. A broken resource is caught by NodeTemplateCatalogTest, not at runtime.</p>
     */
    public static List<NodeTemplate> templates() {
        return Holder.TEMPLATES;
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
     * @return the parsed templates as an unmodifiable list, or an empty list if loading
     *         failed (logged once, at error level)
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
                    "\"Insert node\" will have no items for this session", RESOURCE_PATH, e);
            return List.of();
        }
        // Unmodifiable: this is a process-wide singleton, and callers were previously
        // handed the live collections.
        return Collections.unmodifiableList(result);
    }
}
