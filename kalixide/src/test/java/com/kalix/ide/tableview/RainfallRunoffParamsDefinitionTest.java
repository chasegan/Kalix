package com.kalix.ide.tableview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The params table views for the rainfall-runoff nodes: each is registered,
 * names its parameters in the engine's order, and lays the values out the way
 * the engine's canonical writer does.
 */
class RainfallRunoffParamsDefinitionTest {

    private static final String[] AWBM_NAMES = {
            "a1", "a2", "c1", "c2", "c3", "bfi", "k_base", "k_surf"};
    private static final String[] SURM_NAMES = {
            "imp_fraction", "impsc", "smsc", "coeff", "sq", "fc", "rfac", "bfac", "sfac"};

    @Test
    void awbmParamsAreRegisteredInEngineOrder() {
        String value = "0.134, 0.433, 7, 70, 150, 0.35, 0.95, 0.35";
        TablePropertyDefinition def = TablePropertyRegistry.getInstance()
                .findHandler("awbm", "params", value);
        assertNotNull(def, "no params table view registered for awbm");
        assertArrayEquals(AWBM_NAMES, def.getRowNames());
        assertEquals(8, def.getValuesPerLine());

        String[][] cells = def.parseValues(value);
        assertEquals(8, cells.length);
        assertEquals("7", cells[2][0]);
        assertEquals("0.35", cells[7][0]);
    }

    @Test
    void awbmTwoTapParamsAreChosenByValueCount() {
        String value = "0.134, 0.433, 0.075, 0.762, 1.524, 0.76, 90, 100, 0.98, 0.80, 22";
        TablePropertyDefinition def = TablePropertyRegistry.getInstance()
                .findHandler("awbm", "params", value);
        assertNotNull(def, "no params table view registered for awbm two_tap");
        assertArrayEquals(new String[] {
                "a1", "a2", "c1", "c2", "c3", "inf_base", "gw_sat", "gw_max", "k_base", "k2", "h_gw"},
                def.getRowNames());
        assertEquals(11, def.getValuesPerLine());
        assertEquals("22", def.parseValues(value)[10][0]);
    }

    @Test
    void surmParamsAreRegisteredInEngineOrder() {
        String value = "0, 1, 97, 360, 0.5, 79, 1, 0.5, 0";
        TablePropertyDefinition def = TablePropertyRegistry.getInstance()
                .findHandler("surm", "params", value);
        assertNotNull(def, "no params table view registered for surm");
        assertArrayEquals(SURM_NAMES, def.getRowNames());
        assertEquals(9, def.getValuesPerLine());

        String[][] cells = def.parseValues(value);
        assertEquals(9, cells.length);
        assertEquals("360", cells[3][0]);
        assertEquals("0", cells[8][0]);
    }

    @Test
    void rainLinearCombinationIsRegisteredForBothNodes() {
        String rain = "0.4 * data.a.by_name.rain + 0.6 * data.b.by_name.rain";
        assertNotNull(TablePropertyRegistry.getInstance().findHandler("awbm", "rain", rain));
        assertNotNull(TablePropertyRegistry.getInstance().findHandler("surm", "rain", rain));
    }
}
