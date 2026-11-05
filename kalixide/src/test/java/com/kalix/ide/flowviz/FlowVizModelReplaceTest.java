package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FlowVizModel#replace}: a whole new state (a new or reset tab) is shown in
 * full and recorded as one undo entry holding the fitted viewport.
 */
class FlowVizModelReplaceTest {

    private static final long DAY = 86_400_000L;
    private static final DatasetSeries A = new DatasetSeries("/tmp/x.csv", "a");

    @Test
    void replaceFitsTheNewStateAndRecordsOneEntry() {
        FlowVizPanel panel = new FlowVizPanel();
        panel.setSize(800, 600);
        DataSet pool = new DataSet();
        pool.addSeries(A, new TimeSeriesData(new long[] {0, DAY, 2 * DAY}, new double[] {10, 20, 30}));
        panel.setDataSet(pool);
        FlowVizModel model = panel.model();

        // Only the series differ from the start: a plain update would not fit, a replace must.
        model.replace(FlowVizState.INITIAL.withVisibleSeries(List.of(A)));
        ViewPort fitted = panel.viewportForTests();
        assertTrue(fitted.getMinValue() <= 10 && fitted.getMaxValue() >= 30, "the new state is shown in full");
        assertFalse(model.canUndo(), "one entry: the first");
        assertEquals(fitted.getMaxValue(), model.current().getMaxValue(), 1e-12,
            "the recorded entry holds the fitted viewport");

        model.replace(FlowVizState.INITIAL);
        assertTrue(model.canUndo(), "a reset is one undoable entry");
        model.undo();
        assertEquals(List.of(A), model.current().getVisibleSeries());
        assertEquals(fitted.getMaxValue(), panel.viewportForTests().getMaxValue(), 1e-12,
            "undo restores the fitted view");
    }
}
