package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.LineShape;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line shape is part of the tab's undoable state: it rides in {@link FlowVizState}, so
 * Ctrl+Z switches a stepped plot back to straight, and re-picking the shape already in force
 * must not manufacture a history entry.
 */
class LineShapeStateTest {

    private static final long DAY_MS = 86_400_000L;
    private static final SeriesRef REF = new DatasetSeries("/t.csv", "flow");

    private static FlowVizPanel panelWithData() {
        long[] timestamps = new long[100];
        double[] values = new double[100];
        for (int i = 0; i < timestamps.length; i++) {
            timestamps[i] = 1_577_836_800_000L + i * DAY_MS;
            values[i] = i;
        }
        DataSet pool = new DataSet();
        pool.addSeries(REF, new TimeSeriesData(timestamps, values));

        FlowVizPanel panel = new FlowVizPanel();
        panel.setDataSet(pool);
        panel.setVisibleSeries(List.of(REF));
        return panel;
    }

    @Test
    void changingTheShapeIsUndoableAndRedoable() {
        FlowVizPanel panel = panelWithData();
        panel.setLineShape(LineShape.STEPPED);
        assertTrue(panel.canUndo(), "a line shape change is a state change, so it is undoable");

        panel.undo();
        assertEquals(LineShape.STRAIGHT, panel.getLineShape(), "undo takes the plot back to straight");

        panel.redo();
        assertEquals(LineShape.STEPPED, panel.getLineShape(), "redo makes it stepped again");
    }

    @Test
    void reselectingTheSameShapePushesNoHistory() {
        FlowVizPanel panel = panelWithData();
        panel.setLineShape(LineShape.STEPPED);
        panel.setLineShape(LineShape.STEPPED);

        panel.undo();
        assertEquals(LineShape.STRAIGHT, panel.getLineShape(),
            "one undo reaches straight - no duplicate entry in between");
    }

    @Test
    void theShapeIsCarriedInTheCapturedState() {
        FlowVizPanel panel = panelWithData();
        panel.setLineShape(LineShape.STEPPED);
        assertEquals(LineShape.STEPPED, panel.currentState().getLineShape());
    }

    @Test
    void undoOfResetBringsTheShapeBack() {
        VisualizationTabManager mgr = new VisualizationTabManager(new DataSet(),
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        mgr.addPlotTabFromSettings(settings);
        FlowVizPanel panel = mgr.getTargetVizPanel();
        panel.setLineShape(LineShape.STEPPED);

        mgr.resetTabAt(mgr.getTabbedPane().getSelectedIndex());
        assertEquals(LineShape.STRAIGHT, panel.getLineShape(), "reset returns the shape to straight");

        FlowVizState undone = panel.undo();
        mgr.syncTabSelectionFromState(panel, undone);
        assertEquals(LineShape.STEPPED, panel.getLineShape(),
            "a reset is one undoable step, and the shape comes back with the rest of the view");
    }
}
