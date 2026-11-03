package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.rendering.XAxisType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins each plot type's declared properties: the mask default issue #369 specifies
 * (ON for the cumulative/derived plot types, OFF for the direct-value types) and the
 * X-axis domain the transformer leaves in the timestamp slot.
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

    @Test
    void xAxisTypeSaysWhatTheTransformerStores() {
        assertEquals(XAxisType.PERCENTILE, PlotType.EXCEEDANCE.xAxisType());
        assertEquals(XAxisType.NUMERIC, PlotType.DOUBLE_MASS.xAxisType());
        for (PlotType type : PlotType.values()) {
            if (type != PlotType.EXCEEDANCE && type != PlotType.DOUBLE_MASS) {
                assertEquals(XAxisType.TIME, type.xAxisType(), type.name());
            }
        }
    }

    @Test
    void everyNonTimeAxisNamesItsXQuantity() {
        for (PlotType type : PlotType.values()) {
            boolean time = type.xAxisType() == XAxisType.TIME;
            assertEquals(time, type.xAxisLabel() == null,
                type.name() + ": a non-time axis heads the data table's first column");
        }
        assertEquals("percentile", PlotType.EXCEEDANCE.xAxisLabel());
        assertEquals("cumulative reference", PlotType.DOUBLE_MASS.xAxisLabel());
    }
}
