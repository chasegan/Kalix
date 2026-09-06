package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.PlotState;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JToolBar;
import java.awt.Component;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins toolbar reflection: {@code updateFromState} must move every control to the
 * given state WITHOUT firing listeners — a fired listener would drive the panel
 * (mutating state and pushing history), turning every undo into a loop.
 */
class PlotToolbarControllerTest {

    @Test
    void updateFromStateReflectsSilently() {
        PlotPanel panel = new PlotPanel();
        panel.setDataSet(new DataSet());
        panel.setVisibleSeries(List.of());                                     // entry 1
        panel.setAggregation(AggregationPeriod.DAILY, AggregationMethod.MEAN); // entry 2

        PlotToolbarBuilder builder = new PlotToolbarBuilder(panel)
            .addAggregationControls()
            .addMaskToggle()
            .addPlotTypeDropdown()
            .addYSpaceDropdown()
            .addAutoYToggle(true);
        JToolBar toolbar = builder.build();
        PlotToolbarController controller = builder.getController();

        PlotState previous = panel.undo(); // the entry-1 state (default aggregation)
        assertNotNull(previous);
        panel.redo();                      // panel back at DAILY/MEAN

        controller.updateFromState(previous);

        // The controls reflect the given state...
        assertEquals(previous.getAggregationPeriod().getDisplayName(),
            combo(toolbar, "Aggregation").getSelectedItem());
        assertEquals(previous.getAggregationMethod().getDisplayName(),
            combo(toolbar, "Aggregation method").getSelectedItem());
        // ...but the panel was NOT driven: its state and history are untouched.
        assertEquals(AggregationPeriod.DAILY, panel.getAggregationPeriod(),
            "a fired combo listener would have re-applied the old aggregation");
        assertEquals(AggregationMethod.MEAN, panel.getAggregationMethod());
        assertFalse(panel.canRedo(), "no history entry was pushed or truncated");
    }

    private static JComboBox<?> combo(JToolBar toolbar, String tooltip) {
        for (Component component : toolbar.getComponents()) {
            if (component instanceof JComboBox<?> box && tooltip.equals(box.getToolTipText())) {
                return box;
            }
        }
        throw new AssertionError("no combo with tooltip: " + tooltip);
    }
}
