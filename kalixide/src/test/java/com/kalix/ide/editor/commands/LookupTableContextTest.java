package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that [table.*] sections produce PROPERTY contexts and that the
 * Table View command applies to their data property.
 */
class LookupTableContextTest {

    private static final String MODEL = """
            [kalix]

            [node.in1]
            loc = 0, 0
            type = inflow
            inflow = 30 * table.firstweek(sim.day)

            [table.firstweek]
            values = 1, 1,
                   7, 7,
                   31, 0

            [table.pump_rule]
            n_cols = 13
            values = x, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                   0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            """;

    private EditorContext contextAt(String marker) {
        int caret = MODEL.indexOf(marker);
        assertTrue(caret >= 0, "marker not found: " + marker);
        INIModelParser.ParsedModel model = INIModelParser.parse(MODEL);
        return new ContextDetector().detectContext(caret, MODEL, "", model);
    }

    @Test
    @DisplayName("Caret in a table's values property yields PROPERTY context")
    void testTableDataProperty() {
        EditorContext ctx = contextAt("values = 1, 1,");

        assertEquals(EditorContext.ContextType.PROPERTY, ctx.getType());
        assertEquals("table.firstweek", ctx.getSectionName().orElse(null));
        assertEquals("values", ctx.getPropertyKey().orElse(null));
        assertTrue(ctx.getPropertyValue().orElse("").contains("31"),
                "joined multi-line value expected");
        assertTrue(ctx.getNodeType().isEmpty(), "table sections have no node type");
    }

    @Test
    @DisplayName("Table View applies to table values, not to n_cols")
    void testTableViewApplicability() {
        OpenTableViewCommand command = new OpenTableViewCommand(
                null, () -> INIModelParser.parse(MODEL));

        assertTrue(command.isApplicable(contextAt("values = 1, 1,")),
                "1D table values should be table-editable");
        assertTrue(command.isApplicable(contextAt("values = x, 1, 2,")),
                "2D table values should be table-editable");
        assertFalse(command.isApplicable(contextAt("n_cols = 13")),
                "n_cols is a scalar, not a table");
    }

    @Test
    @DisplayName("Node table properties keep their existing behaviour")
    void testNodePropertyUnchanged() {
        EditorContext ctx = contextAt("inflow = 30");
        assertEquals(EditorContext.ContextType.PROPERTY, ctx.getType());
        assertEquals("inflow", ctx.getNodeType().orElse(null));

        // inflow is not a registered table property, so Table View stays hidden
        OpenTableViewCommand command = new OpenTableViewCommand(
                null, () -> INIModelParser.parse(MODEL));
        assertFalse(command.isApplicable(ctx));
    }
}
