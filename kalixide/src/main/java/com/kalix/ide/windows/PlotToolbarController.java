package com.kalix.ide.windows;

import javax.swing.JComboBox;
import javax.swing.JToggleButton;

/**
 * Controller for updating plot-toolbar controls from a PlotState without triggering
 * listeners. Built by {@link PlotToolbarBuilder#build()}; used by the undo/redo
 * callback to reflect a restored state back into the toolbar's dropdowns and toggles.
 */
class PlotToolbarController {
    private final JComboBox<String> aggregationPeriodCombo;
    private final JComboBox<String> aggregationMethodCombo;
    private final JComboBox<String> plotTypeCombo;
    private final JComboBox<String> ySpaceCombo;
    private final JToggleButton maskToggle;
    private final JToggleButton autoYToggle;

    PlotToolbarController(JComboBox<String> aggregationPeriodCombo,
                          JComboBox<String> aggregationMethodCombo,
                          JComboBox<String> plotTypeCombo,
                          JComboBox<String> ySpaceCombo,
                          JToggleButton maskToggle,
                          JToggleButton autoYToggle) {
        this.aggregationPeriodCombo = aggregationPeriodCombo;
        this.aggregationMethodCombo = aggregationMethodCombo;
        this.plotTypeCombo = plotTypeCombo;
        this.ySpaceCombo = ySpaceCombo;
        this.maskToggle = maskToggle;
        this.autoYToggle = autoYToggle;
    }

    /**
     * Updates all toolbar controls to reflect the given state.
     * Temporarily removes listeners to avoid triggering state pushes.
     */
    void updateFromState(com.kalix.ide.flowviz.PlotState state) {
        setSilently(aggregationPeriodCombo, state.getAggregationPeriod().getDisplayName());
        setSilently(aggregationMethodCombo, state.getAggregationMethod().getDisplayName());
        setSilently(plotTypeCombo, state.getPlotType().getDisplayName());
        setSilently(ySpaceCombo, state.getYAxisScale().getDisplayName());
        setSilently(maskToggle, state.getMaskMode() == com.kalix.ide.flowviz.stats.MaskMode.ALL);
        setSilently(autoYToggle, state.isAutoYMode());
    }

    private static void setSilently(JComboBox<String> combo, String value) {
        java.awt.event.ActionListener[] listeners = combo.getActionListeners();
        for (var l : listeners) combo.removeActionListener(l);
        combo.setSelectedItem(value);
        for (var l : listeners) combo.addActionListener(l);
    }

    private static void setSilently(JToggleButton toggle, boolean selected) {
        java.awt.event.ActionListener[] listeners = toggle.getActionListeners();
        for (var l : listeners) toggle.removeActionListener(l);
        toggle.setSelected(selected);
        for (var l : listeners) toggle.addActionListener(l);
    }
}
