package com.kalix.ide.editor.commands;

import com.kalix.ide.editor.commands.NodeTemplateCatalog.CarriedSection;
import com.kalix.ide.editor.commands.NodeTemplateCatalog.NodeTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sections a template carries are added only when the document lacks them. */
class CarriedSectionsTest {

    private static final NodeTemplate FIELD = new NodeTemplate("field", "Field", List.of("type = field"),
        List.of(new CarriedSection("crop.bare_soil", List.of("root_depth = 600", "kc = 0.4")),
                new CarriedSection("crop.cotton", List.of("root_depth = 900", "kc = 1"))));

    @Test
    void addsEverySectionToAnEmptyDocument() {
        String added = CommandExecutor.carriedSections(FIELD, "[kalix]\nstart = 2020-01-01\n");
        assertEquals("\n\n[crop.bare_soil]\nroot_depth = 600\nkc = 0.4\n\n[crop.cotton]\nroot_depth = 900\nkc = 1", added);
    }

    @Test
    void skipsASectionTheDocumentAlreadyDeclares() {
        String added = CommandExecutor.carriedSections(FIELD, "[crop.cotton]\nroot_depth = 1200\nkc = 0.9\n");
        assertTrue(added.contains("[crop.bare_soil]"));
        assertFalse(added.contains("[crop.cotton]"), "the document's own cotton is kept");
    }

    @Test
    void aHeaderCountsInAnyCaseAndIndentation() {
        assertEquals("", CommandExecutor.carriedSections(FIELD, "  [Crop.Bare_Soil]\nkc = 0\n\n[ crop.cotton ]\nkc = 1\n"));
    }

    @Test
    void nothingCarriedNothingAdded() {
        NodeTemplate gauge = new NodeTemplate("gauge", "Gauge", List.of("type = gauge"), List.of());
        assertEquals("", CommandExecutor.carriedSections(gauge, null));
    }
}
