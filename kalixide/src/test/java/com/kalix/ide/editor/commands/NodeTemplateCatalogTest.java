package com.kalix.ide.editor.commands;

import com.kalix.ide.editor.commands.NodeTemplateCatalog.NodeTemplate;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shipped node_templates.json. Every assertion here is about a property a
 * template must have for the node it inserts to be a legal, runnable Kalix model.
 */
class NodeTemplateCatalogTest {

    @Test
    void catalogLoads() {
        assertFalse(NodeTemplateCatalog.templates().isEmpty(), "the shipped catalog must parse");
    }

    /**
     * The id becomes the node name ({@code id_1}), so it must be a bare INI identifier.
     * Before the id/label split the menu label was used, yielding [node.Unregulated user].
     */
    @Test
    void everyIdIsAValidNodeName() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            assertTrue(template.id().matches("[a-z][a-z0-9_]*"),
                "template id '" + template.id() + "' is not a lowercase identifier");
        }
    }

    @Test
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            assertTrue(seen.add(template.id()), "duplicate template id: " + template.id());
        }
    }

    @Test
    void everyTemplateHasALabel() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            assertFalse(template.label().isBlank(), "template '" + template.id() + "' has no label");
        }
    }

    /** Labels are free text; ids are not. They must be allowed to differ. */
    @Test
    void labelsAreIndependentOfIds() {
        NodeTemplate unregulated = NodeTemplateCatalog.byId("unregulated_user");
        assertNotNull(unregulated);
        assertEquals("Unregulated user", unregulated.label());
    }

    /** The header is generated from the id, so no template may carry one. */
    @Test
    void noTemplateCarriesItsOwnSectionHeader() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            for (String line : template.lines()) {
                assertFalse(line.trim().startsWith("["),
                    "template '" + template.id() + "' still carries a header line: " + line);
            }
        }
    }

    @Test
    void everyTemplateDeclaresATypeAndCoordinatePlaceholders() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            String body = String.join("\n", template.lines());
            assertTrue(body.matches("(?s).*\\btype\\s*=.*"), "template '" + template.id() + "' has no type");
            assertTrue(body.matches("(?s).*\\bloc\\s*=.*"), "template '" + template.id() + "' has no loc");
            assertTrue(body.contains("%%X%%") && body.contains("%%Y%%"),
                "template '" + template.id() + "' has no coordinate placeholders");
        }
    }

    /** The declared type must match the id — an easy copy-paste slip in the JSON. */
    @Test
    void declaredTypeMatchesTheTemplateId() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            assertEquals(template.id(), valueOf(template.id(), "type"),
                "template '" + template.id() + "' declares a different node type");
        }
    }

    /**
     * Kalix INI comments are '#' and ';'. A '//' is not a comment — it is swallowed
     * into the value, and the engine panics parsing it as a number.
     */
    @Test
    void noTemplateUsesSlashSlashAsAComment() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            for (String line : template.lines()) {
                assertFalse(line.contains("//"),
                    "template '" + template.id() + "' uses // as a comment: " + line);
            }
        }
    }

    /**
     * A template exists so a modeller can check a model steps through before
     * calibrating. GR4J with params=0,0,0,0 does not: x4=0 gives a zero-length unit
     * hydrograph and the engine indexes out of bounds.
     */
    @Test
    void gr4jParametersAreRunnable() {
        String params = valueOf("gr4j", "params");
        String[] xs = params.split(",");
        assertEquals(4, xs.length, "GR4J takes four parameters");
        assertTrue(Double.parseDouble(xs[0].trim()) > 0, "x1 (production store) must be positive");
        assertTrue(Double.parseDouble(xs[3].trim()) > 0, "x4 (unit hydrograph base) must be positive");
    }

    @Test
    void sacramentoTakesSeventeenParameters() {
        assertEquals(17, valueOf("sacramento", "params").split(",").length);
    }

    /**
     * Interpolation tables need at least two rows. One row crashed the storage node
     * ("must have at least 2 rows") and the splitter (index out of bounds).
     */
    @Test
    void tableValuedTemplatesCarryAtLeastTwoRows() {
        assertTrue(rowCount("storage", "dimensions") >= 2, "storage dimensions needs >= 2 rows");
        assertTrue(rowCount("loss", "table") >= 2, "loss table needs >= 2 rows");
        assertTrue(rowCount("splitter", "table") >= 2, "splitter table needs >= 2 rows");
    }

    /** Continuation lines must start with whitespace, or the parser ends the value. */
    @Test
    void continuationLinesAreIndented() {
        for (NodeTemplate template : NodeTemplateCatalog.templates()) {
            for (String line : template.lines()) {
                if (!line.contains("=")) {
                    assertTrue(Character.isWhitespace(line.charAt(0)),
                        "template '" + template.id() + "' has an unindented continuation line: " + line);
                }
            }
        }
    }

    /** The first line of a key's value, with any inline comment stripped. */
    private static String valueOf(String templateId, String key) {
        NodeTemplate template = NodeTemplateCatalog.byId(templateId);
        assertNotNull(template, "no template: " + templateId);
        for (String line : template.lines()) {
            if (line.trim().startsWith(key + " ") || line.trim().startsWith(key + "=")) {
                String value = line.substring(line.indexOf('=') + 1);
                int comment = value.indexOf('#');
                return (comment == -1 ? value : value.substring(0, comment)).trim();
            }
        }
        throw new AssertionError("template '" + templateId + "' has no '" + key + "'");
    }

    /** A table row per line: the key's own line, plus its indented continuation lines. */
    private static int rowCount(String templateId, String key) {
        NodeTemplate template = NodeTemplateCatalog.byId(templateId);
        assertNotNull(template, "no template: " + templateId);
        int rows = 0;
        boolean inValue = false;
        for (String line : template.lines()) {
            boolean isContinuation = !line.contains("=");
            if (line.trim().startsWith(key)) {
                inValue = true;
                rows = 1;
            } else if (inValue && isContinuation) {
                rows++;
            } else if (inValue) {
                break;
            }
        }
        return rows;
    }

    @Test
    void byIdFindsTemplatesAndRejectsUnknownOnes() {
        assertNotNull(NodeTemplateCatalog.byId("gr4j"));
        assertNull(NodeTemplateCatalog.byId("GR4J"), "ids are case-sensitive");
        assertNull(NodeTemplateCatalog.byId("nonexistent"));
    }

    /** The catalog is a process-wide singleton; callers must not be able to corrupt it. */
    @Test
    void templatesAreUnmodifiable() {
        List<NodeTemplate> templates = NodeTemplateCatalog.templates();
        assertThrows(UnsupportedOperationException.class, templates::clear);
        assertThrows(UnsupportedOperationException.class, () -> templates.get(0).lines().clear());
    }
}
