package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotState;
import com.kalix.ide.flowviz.transform.PlotType;

import javax.swing.JComboBox;
import javax.swing.JToggleButton;

/**
 * Controller for updating the unified viz toolbar's controls from a PlotState
 * without triggering listeners. Built by {@link VizToolbarBuilder#build}; used
 * by the undo/redo callback and in-place Reset to reflect a restored state back
 * into the dropdowns and toggles — a fired listener would drive the panel and
 * loop the very undo being reflected.
 */
class VizToolbarController {
    private final JComboBox<String> aggregationPeriodCombo;
    private final JComboBox<String> aggregationMethodCombo;
    private final JComboBox<String> maskCombo;
    private final JComboBox<PlotType> plotTypeCombo;
    private final JComboBox<String> ySpaceCombo;
    private final JToggleButton autoYToggle;

    VizToolbarController(JComboBox<String> aggregationPeriodCombo,
                         JComboBox<String> aggregationMethodCombo,
                         JComboBox<String> maskCombo,
                         JComboBox<PlotType> plotTypeCombo,
                         JComboBox<String> ySpaceCombo,
                         JToggleButton autoYToggle) {
        this.aggregationPeriodCombo = aggregationPeriodCombo;
        this.aggregationMethodCombo = aggregationMethodCombo;
        this.maskCombo = maskCombo;
        this.plotTypeCombo = plotTypeCombo;
        this.ySpaceCombo = ySpaceCombo;
        this.autoYToggle = autoYToggle;
    }

    /**
     * Updates all toolbar controls to reflect the given state.
     * Temporarily removes listeners to avoid triggering state pushes.
     */
    void updateFromState(PlotState state) {
        setSilently(aggregationPeriodCombo, state.getAggregationPeriod().getDisplayName());
        setSilently(aggregationMethodCombo, state.getAggregationMethod().getDisplayName());
        setSilently(maskCombo, state.getMaskMode().getDisplayName());
        setSilently(plotTypeCombo, state.getPlotType());
        setSilently(ySpaceCombo, state.getYAxisScale().getDisplayName());
        setSilently(autoYToggle, state.isAutoYMode());
    }

    static void setSilently(JComboBox<String> combo, String value) {
        java.awt.event.ActionListener[] listeners = combo.getActionListeners();
        for (var l : listeners) combo.removeActionListener(l);
        try {
            combo.setSelectedItem(value);
        } finally {
            for (var l : listeners) combo.addActionListener(l);
        }
    }

    private static void setSilently(JComboBox<PlotType> combo, PlotType value) {
        java.awt.event.ActionListener[] listeners = combo.getActionListeners();
        for (var l : listeners) combo.removeActionListener(l);
        try {
            combo.setSelectedItem(value);
        } finally {
            for (var l : listeners) combo.addActionListener(l);
        }
    }

    private static void setSilently(JToggleButton toggle, boolean selected) {
        java.awt.event.ActionListener[] listeners = toggle.getActionListeners();
        for (var l : listeners) toggle.removeActionListener(l);
        try {
            toggle.setSelected(selected);
        } finally {
            for (var l : listeners) toggle.addActionListener(l);
        }
    }
}
