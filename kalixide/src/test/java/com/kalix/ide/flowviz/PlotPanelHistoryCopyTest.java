package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins Chrome-style tab duplication: {@code copyHistoryFrom} must carry the full
 * history — including the mask mode, which TabSettings historically did not carry
 * (the construction-order comment in VisualizationTabManager depends on this) —
 * restore the source's CURRENT state, and preserve any redo tail.
 */
class PlotPanelHistoryCopyTest {

    private static PlotPanel panelWithHistory() {
        PlotPanel panel = new PlotPanel();
        panel.setDataSet(new DataSet());
        panel.setVisibleSeries(List.of());                                   // entry 1 (defaults)
        panel.setAggregation(AggregationPeriod.DAILY, AggregationMethod.MEAN); // entry 2
        panel.setMaskMode(MaskMode.EACH);                                    // entry 3
        return panel;
    }

    @Test
    void duplicateCarriesCurrentStateIncludingMask() {
        PlotPanel source = panelWithHistory();

        PlotPanel copy = new PlotPanel();
        copy.setDataSet(new DataSet());
        copy.copyHistoryFrom(source);

        assertEquals(MaskMode.EACH, copy.getMaskMode(), "mask rides the history, not TabSettings");
        assertEquals(AggregationPeriod.DAILY, copy.getAggregationPeriod());
        assertEquals(AggregationMethod.MEAN, copy.getAggregationMethod());
        assertTrue(copy.canUndo(), "the copied past is walkable");
        assertFalse(copy.canRedo(), "source was at its newest state");
    }

    @Test
    void undoOnTheCopyWalksTheSourcesPast() {
        PlotPanel copy = new PlotPanel();
        copy.setDataSet(new DataSet());
        copy.copyHistoryFrom(panelWithHistory());

        copy.undo();
        assertEquals(MaskMode.NONE, copy.getMaskMode(), "entry 2 predates the mask change");
        assertEquals(AggregationPeriod.DAILY, copy.getAggregationPeriod());
        assertTrue(copy.canRedo());
    }

    @Test
    void redoTailIsPreserved() {
        PlotPanel source = panelWithHistory();
        source.undo(); // source now mid-history with a redo tail

        PlotPanel copy = new PlotPanel();
        copy.setDataSet(new DataSet());
        copy.copyHistoryFrom(source);

        assertEquals(MaskMode.NONE, copy.getMaskMode(), "copy lands at the source's current position");
        assertTrue(copy.canRedo(), "the source's redo tail is copied too");
        copy.redo();
        assertEquals(MaskMode.EACH, copy.getMaskMode());
    }
}
