package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PlotPanel#batchStateChange}: several setters inside one batch produce
 * exactly one undo entry, and undo restores the pre-batch state — the contract tab
 * construction and in-place Reset rely on.
 */
class PlotPanelBatchStateTest {

    @Test
    void batchedSettersPushExactlyOneUndoEntry() {
        PlotPanel panel = new PlotPanel();
        panel.setDataSet(new DataSet());
        panel.pushState(); // baseline entry
        assertFalse(panel.canUndo(), "baseline alone: nothing to undo");

        panel.batchStateChange(() -> {
            panel.setAggregation(AggregationPeriod.MONTHLY, AggregationMethod.MEAN);
            panel.setPlotType(PlotType.EXCEEDANCE);
            panel.setYAxisScale(YAxisScale.LOG);
        });

        assertTrue(panel.canUndo(), "the batch pushed an entry");
        assertEquals(PlotType.EXCEEDANCE, panel.getPlotType());

        panel.undo();
        assertFalse(panel.canUndo(), "exactly ONE entry was pushed for three setters");
        assertEquals(PlotType.VALUES, panel.getPlotType(), "undo restored the pre-batch state");
    }
}
