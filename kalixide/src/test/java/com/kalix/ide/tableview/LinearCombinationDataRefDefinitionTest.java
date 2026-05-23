package com.kalix.ide.tableview;

import com.kalix.ide.tableview.definitions.LinearCombinationDataRefDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinearCombinationDataRefDefinitionTest {

    private static final String EXAMPLE =
            "0.1371563839 * data.31032_csv.by_name.daily_rain"
            + " + 0.5095107995 * data.31075_csv.by_name.daily_rain"
            + " + 0.5975703828 * data.31125_csv.by_name.daily_rain";

    @Test
    void registry_findsHandlerForSacramentoRain() {
        TablePropertyDefinition def = TablePropertyRegistry.getInstance()
                .findHandler("sacramento", "rain", EXAMPLE);
        assertNotNull(def);
        assertEquals("sacramento", def.getNodeType());
        assertEquals("rain", def.getPropertyName());
    }

    @Test
    void registry_findsHandlerForGr4jRain() {
        TablePropertyDefinition def = TablePropertyRegistry.getInstance()
                .findHandler("gr4j", "rain", EXAMPLE);
        assertNotNull(def);
        assertEquals("gr4j", def.getNodeType());
    }

    @Test
    void registry_returnsNullForUnparseableRainValue() {
        // A pure constant or a constant-plus-ref isn't a linear combination;
        // the menu item should be hidden in those cases.
        assertNull(TablePropertyRegistry.getInstance().findHandler("sacramento", "rain", "4"));
        assertNull(TablePropertyRegistry.getInstance().findHandler("sacramento", "rain", "4 + data.foo"));
    }

    @Test
    void parseValues_producesCoefficientAndRefColumns() {
        LinearCombinationDataRefDefinition def =
                new LinearCombinationDataRefDefinition("sacramento", "rain");
        String[][] rows = def.parseValues(EXAMPLE);
        assertEquals(3, rows.length);
        assertArrayEquals(new String[]{"0.1371563839", "data.31032_csv.by_name.daily_rain"}, rows[0]);
        assertArrayEquals(new String[]{"0.5095107995", "data.31075_csv.by_name.daily_rain"}, rows[1]);
        assertArrayEquals(new String[]{"0.5975703828", "data.31125_csv.by_name.daily_rain"}, rows[2]);
    }

    @Test
    void formatValues_inlineRoundTrip() {
        LinearCombinationDataRefDefinition def =
                new LinearCombinationDataRefDefinition("sacramento", "rain");
        String[][] rows = def.parseValues(EXAMPLE);
        String formatted = def.formatValues(rows, false, new TableValueFormatter(), 0);
        // Parsing the formatted output must reproduce the same rows.
        assertArrayEquals(rows, def.parseValues(formatted));
    }

    @Test
    void formatValues_multiLineIndentMatchesRainEquals() {
        LinearCombinationDataRefDefinition def =
                new LinearCombinationDataRefDefinition("sacramento", "rain");
        String[][] rows = {
                {"0.5", "data.foo"},
                {"-0.3", "data.bar"},
        };
        // Indent of 7 spaces aligns continuation lines under "rain = ".
        String formatted = def.formatValues(rows, true, new TableValueFormatter(), 7);
        String expected =
                "0.5 * data.foo -\n"
              + "       0.3 * data.bar";
        assertEquals(expected, formatted);
    }

    @Test
    void formatValues_skipsBlankRowsDefensively() {
        LinearCombinationDataRefDefinition def =
                new LinearCombinationDataRefDefinition("sacramento", "rain");
        String[][] rows = {
                {"0.5", "data.foo"},
                {"", ""},
                {"0.3", "data.bar"},
        };
        String formatted = def.formatValues(rows, false, new TableValueFormatter(), 0);
        assertEquals("0.5 * data.foo + 0.3 * data.bar", formatted);
    }

    @Test
    void validateCell_rejectsNonNumericCoefficient() {
        LinearCombinationDataRefDefinition def =
                new LinearCombinationDataRefDefinition("sacramento", "rain");
        assertNull(def.validateCell(0, 0, "0.5"));
        assertNotNull(def.validateCell(0, 0, "abc"));
        assertNotNull(def.validateCell(0, 0, ""));
    }

    @Test
    void validateCell_rejectsRefWithoutDataPrefix() {
        LinearCombinationDataRefDefinition def =
                new LinearCombinationDataRefDefinition("sacramento", "rain");
        assertNull(def.validateCell(0, 1, "data.foo"));
        assertNull(def.validateCell(0, 1, "data.foo.bar[-1,0]"));
        assertNotNull(def.validateCell(0, 1, "foo.bar"));
        assertNotNull(def.validateCell(0, 1, ""));
    }
}
