package com.kalix.ide.flowviz.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PlotType#isDataMaskDefault()} to the mapping issue #369 specifies:
 * overlapping-data masking defaults ON for the cumulative/derived plot types and
 * OFF for the direct-value types.
 */
class PlotTypeTest {

    @Test
    void dataMaskDefaultMatchesIssue369() {
        assertFalse(PlotType.VALUES.isDataMaskDefault());
        assertTrue(PlotType.CUMULATIVE.isDataMaskDefault());
        assertFalse(PlotType.DIFFERENCE.isDataMaskDefault());
        assertTrue(PlotType.CUMULATIVE_DIFFERENCE.isDataMaskDefault());
        assertTrue(PlotType.EXCEEDANCE.isDataMaskDefault());
        assertTrue(PlotType.DOUBLE_MASS.isDataMaskDefault());
        assertTrue(PlotType.RESIDUAL_MASS.isDataMaskDefault());

        assertEquals(7, PlotType.values().length,
            "a new plot type must decide its mask default here too");
    }
}
