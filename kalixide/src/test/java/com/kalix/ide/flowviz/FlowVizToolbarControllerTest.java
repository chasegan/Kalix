package com.kalix.ide.flowviz;

import com.kalix.ide.components.SeasonalMaskButton;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;

import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.time.Month;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        FlowVizPanel panel = mgr.getTargetVizPanel();
        panel.setAggregation(AggregationPeriod.DAILY, AggregationMethod.MEAN); // entry 2

        FlowVizToolbarController controller = mgr.tabAt(0).vizToolbar.getController();
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        JComboBox<?> periodCombo = combo(tabRoot, "Aggregation");
        JComboBox<?> maskCombo = combo(tabRoot, "Mask mode for bivariate statistics");
        assertNotNull(periodCombo);
        assertNotNull(maskCombo);

        FlowVizState previous = panel.undo(); // the construction-entry state (defaults)
        assertNotNull(previous);
        panel.redo();                      // panel back at DAILY/MEAN

        controller.updateFromState(previous);

        // The controls reflect the given state...
        assertEquals(previous.getAggregationPeriod().getDisplayName(), periodCombo.getSelectedItem());
        assertEquals(FlowVizToolbarBuilder.maskItem(previous.getMaskMode()), maskCombo.getSelectedItem());
        // ...but the panel was NOT driven: its state and history are untouched.
        assertEquals(AggregationPeriod.DAILY, panel.getAggregationPeriod(),
            "a fired combo listener would have re-applied the old aggregation");
        assertEquals(AggregationMethod.MEAN, panel.getAggregationMethod());
        assertFalse(panel.canRedo(), "no history entry was pushed or truncated");
    }
    private static SeasonalMaskButton seasonalButton(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof SeasonalMaskButton button) {
                return button;
            }
            if (component instanceof Container container) {
                SeasonalMaskButton found = seasonalButton(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static VisualizationTabManager.TabSettings emptySettings() {
        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        return settings;
    }

    /**
     * The seasonal button lives with the always-visible controls, not the plot-only
     * cluster: the panel owns one selection and both views project from it.
     */
    @Test
    void theSeasonalButtonIsSharedByBothViews() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings());
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        SeasonalMaskButton button = seasonalButton(tabRoot);
        assertNotNull(button, "the merged toolbar carries the seasonal mask button");

        mgr.tabAt(0).vizToolbar.applyViewMode(FlowVizView.STATS);
        assertTrue(button.isVisible(), "it stays visible in the stats view - the mask is shared");
    }

    @Test
    void updateFromStateReflectsTheSeasonalMaskSilently() {
        VisualizationTabManager mgr = manager();
        mgr.addPlotTabFromSettings(emptySettings());
        FlowVizPanel panel = mgr.getTargetVizPanel();
        Container tabRoot = (Container) mgr.getTabbedPane().getSelectedComponent();
        SeasonalMaskButton button = seasonalButton(tabRoot);
        assertNotNull(button);

        SeasonalMaskMode winter = SeasonalMaskMode.of(Set.of(Month.JUNE, Month.JULY));
        panel.setSeasonalMaskMode(winter);
        // The button is not an observer of the panel: like the combos, it is reconciled
        // only through updateFromState (undo/redo and reset), and otherwise drives it.
        mgr.tabAt(0).vizToolbar.getController().updateFromState(panel.currentState());
        assertEquals(winter, button.getMode(), "reflection moves the button to the state");

        FlowVizState previous = panel.undo();   // back to DISABLED
        panel.redo();                           // panel is masked again
        mgr.tabAt(0).vizToolbar.getController().updateFromState(previous);

        assertSame(SeasonalMaskMode.DISABLED, button.getMode(),
            "the control reflects the given state");
        assertEquals(winter, panel.getSeasonalMaskMode(),
            "...but reflecting must not drive the panel back - that would loop the undo");
        assertFalse(panel.canRedo(), "no history entry was pushed or truncated");
    }
}
