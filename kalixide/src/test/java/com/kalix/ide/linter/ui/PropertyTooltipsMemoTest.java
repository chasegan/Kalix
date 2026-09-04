package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property help is memoised per line only while the parsed model and the schema
 * are the same objects. A document edit hands over a new parsed model and a
 * schema reload a new schema, and either must invalidate the memo: the reviewed
 * first cut keyed the memo on the hovered line's own text, so changing the
 * node's {@code type} line left the tip describing the old type.
 */
class PropertyTooltipsMemoTest {

    /** A schema manager whose current schema is whatever the test says it is. */
    private static final class SwappableSchemaManager extends SchemaManager {
        private LinterSchema schema = LinterSchema.loadDefault();

        @Override
        public LinterSchema getCurrentSchema() {
            return schema;
        }
    }

    private static final String GR4J = "[node.a]\ntype = gr4j\narea = 10\n";
    private static final String INFLOW = "[node.a]\ntype = inflow\narea = 10\n";

    @Test
    void editingTheTypeLineInvalidatesTheMemo() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        textArea.setText(GR4J);
        AtomicReference<INIModelParser.ParsedModel> model = new AtomicReference<>(INIModelParser.parse(GR4J));
        PropertyTooltips tips = new PropertyTooltips(textArea, new SwappableSchemaManager(), model::get);
        int areaOffset = GR4J.indexOf("area");

        String tip = tips.tipAt(3, areaOffset);
        assertNotNull(tip);
        assertTrue(tip.contains("(required)"), tip);

        // Edit the type line (not the hovered line): the document hands over a new parsed model.
        textArea.setText(INFLOW);
        model.set(INIModelParser.parse(INFLOW));

        assertNull(tips.tipAt(3, areaOffset), "area is not a parameter of inflow; the gr4j tip must not survive");
    }

    @Test
    void reloadingTheSchemaInvalidatesTheMemo() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        textArea.setText(GR4J);
        INIModelParser.ParsedModel model = INIModelParser.parse(GR4J);
        SwappableSchemaManager schemaManager = new SwappableSchemaManager();
        PropertyTooltips tips = new PropertyTooltips(textArea, schemaManager, () -> model);
        int areaOffset = GR4J.indexOf("area");

        assertNotNull(tips.tipAt(3, areaOffset));

        schemaManager.schema = null; // schema unloaded
        assertNull(tips.tipAt(3, areaOffset));

        schemaManager.schema = LinterSchema.loadDefault(); // reloaded: a new instance
        assertNotNull(tips.tipAt(3, areaOffset));
    }

    @Test
    void unchangedModelAndSchemaReuseTheAnalysis() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        textArea.setText(GR4J);
        INIModelParser.ParsedModel model = INIModelParser.parse(GR4J);
        int[] supplierCalls = {0};
        PropertyTooltips tips = new PropertyTooltips(textArea, new SwappableSchemaManager(), () -> {
            supplierCalls[0]++;
            return model;
        });
        int areaOffset = GR4J.indexOf("area");

        String first = tips.tipAt(3, areaOffset);
        String again = tips.tipAt(3, areaOffset + 2); // still on the key
        assertSame(first, again, "a memo hit returns the very same tip; a fresh analysis would build a new string");
        assertEquals(2, supplierCalls[0], "the (cheap) supplier is asked each time");

        // Off the key (in the value): the cheap gate answers before any lookup.
        assertNull(tips.tipAt(3, GR4J.indexOf("10")));
        assertEquals(2, supplierCalls[0]);
    }
}
