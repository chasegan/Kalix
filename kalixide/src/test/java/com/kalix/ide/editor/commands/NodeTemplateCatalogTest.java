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
            assertTrue(body.contains("type="), "template '" + template.id() + "' has no type");
            assertTrue(body.contains("%%X%%") && body.contains("%%Y%%"),
                "template '" + template.id() + "' has no coordinate placeholders");
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
