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
    void gr4jsgParamListsAreRegisteredInEngineOrder() {
        TablePropertyRegistry registry = TablePropertyRegistry.getInstance();

        TablePropertyDefinition params = registry.findHandler("gr4jsg", "params", "1500, 4, 65, 0.38");
        assertNotNull(params, "no params table view registered for gr4jsg");
        assertArrayEquals(new String[] {"x1", "x2", "x3", "x4"}, params.getRowNames());

        String snowValue = "0.5, 0, 3.38, 1.3, 3";
        TablePropertyDefinition snow = registry.findHandler("gr4jsg", "snow_params", snowValue);
        assertNotNull(snow, "no snow_params table view registered for gr4jsg");
        assertArrayEquals(new String[] {"tfrac", "taccum", "m_rainfall", "base_rainfall", "m_nonrainfall"},
                snow.getRowNames());
        assertEquals(5, snow.getValuesPerLine());
        assertEquals("3.38", snow.parseValues(snowValue)[2][0]);

        String iceValue = "100000, 6, 0, 0.5, 0";
        TablePropertyDefinition ice = registry.findHandler("gr4jsg", "ice_params", iceValue);
        assertNotNull(ice, "no ice_params table view registered for gr4jsg");
        assertArrayEquals(new String[] {"initial_ice", "ddfi", "tmelt", "return_flow", "accumulation"},
                ice.getRowNames());
        assertEquals(5, ice.getValuesPerLine());
        assertEquals("100000", ice.parseValues(iceValue)[0][0]);
    }

    @Test
    void rainLinearCombinationIsRegisteredForBothNodes() {
        String rain = "0.4 * data.a.by_name.rain + 0.6 * data.b.by_name.rain";
        assertNotNull(TablePropertyRegistry.getInstance().findHandler("awbm", "rain", rain));
        assertNotNull(TablePropertyRegistry.getInstance().findHandler("surm", "rain", rain));
    }
}
