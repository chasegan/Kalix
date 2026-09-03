package com.kalix.ide.flowviz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the rule that keeps the plot key inside the plot area (the prevention half of
 * issue #383): the key never leaves the area, and an oversized key pins its top-left
 * corner so its title bar and collapse button stay reachable.
 */
class PlotLegendClampTest {

    // A plot area running from 100 to 500 on this axis, and a key 120 long.
    private static final int AREA_START = 100;
    private static final int AREA_LENGTH = 400;
    private static final int KEY_SIZE = 120;

    private static int clamp(int position) {
        return PlotLegendManager.clampAxis(position, AREA_START, AREA_LENGTH, KEY_SIZE);
    }

    @Test
    void positionInsideTheAreaIsUnchanged() {
        assertEquals(250, clamp(250));
        assertEquals(AREA_START, clamp(AREA_START));
        assertEquals(380, clamp(380), "flush with the far edge is still inside");
    }

    @Test
    void positionPastTheFarEdgeStopsFlushWithIt() {
        assertEquals(380, clamp(381));
        assertEquals(380, clamp(10_000));
    }

    @Test
    void positionBeforeTheNearEdgeStopsAtIt() {
        assertEquals(AREA_START, clamp(99));
        assertEquals(AREA_START, clamp(-1), "a negative coordinate is just off-area, not a sentinel");
    }

    @Test
    void oversizedKeyPinsItsStartToTheAreaStart() {
        int oversized = AREA_LENGTH + 50;
        assertEquals(AREA_START, PlotLegendManager.clampAxis(300, AREA_START, AREA_LENGTH, oversized));
        assertEquals(AREA_START, PlotLegendManager.clampAxis(-50, AREA_START, AREA_LENGTH, oversized));
    }

    @Test
    void keyExactlyFillingTheAreaSitsAtItsStart() {
        assertEquals(AREA_START, PlotLegendManager.clampAxis(300, AREA_START, AREA_LENGTH, AREA_LENGTH));
    }
}
