package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the merged toolbar's reflection: {@code updateFromState} must move every
 * control to the given state WITHOUT firing listeners — a fired listener would
 * drive the panel (mutating state and pushing history), turning undo into a loop.
 */
class VizToolbarControllerTest {

    private static VisualizationTabManager manager() {
        return new VisualizationTabManager(new DataSet(),
            ref -> new LineStyle(Color.BLACK, StrokeStyle.values()[0]));
    }

    private static JComboBox<?> combo(Container root, String tooltip) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> box && tooltip.equals(box.getToolTipText())) {
                return box;
            }
            if (component instanceof Container container) {
                JComboBox<?> found = combo(container, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void updateFromStateReflectsSilently() {
        VisualizationTabManager mgr = manager();
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        mgr.addPlotTabFromSettings(settings);

        PlotPanel panel = mgr.getTargetPlotPanel();
        panel.setAggregation(AggregationPeriod.DAILY, AggregationMethod.MEAN); // entry 2

        VizToolbarController controller = mgr.tabAt(0).vizToolbar.getController();
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        JComboBox<?> periodCombo = combo(tabRoot, "Aggregation");
        JComboBox<?> maskCombo = combo(tabRoot, "Mask mode for bivariate statistics");
        assertNotNull(periodCombo);
        assertNotNull(maskCombo);

        PlotState previous = panel.undo(); // the construction-entry state (defaults)
        assertNotNull(previous);
        panel.redo();                      // panel back at DAILY/MEAN

        controller.updateFromState(previous);

        // The controls reflect the given state...
        assertEquals(previous.getAggregationPeriod().getDisplayName(), periodCombo.getSelectedItem());
        assertEquals(VizToolbarBuilder.maskItem(previous.getMaskMode()), maskCombo.getSelectedItem());
        // ...but the panel was NOT driven: its state and history are untouched.
        assertEquals(AggregationPeriod.DAILY, panel.getAggregationPeriod(),
            "a fired combo listener would have re-applied the old aggregation");
        assertEquals(AggregationMethod.MEAN, panel.getAggregationMethod());
        assertFalse(panel.canRedo(), "no history entry was pushed or truncated");
    }
}
