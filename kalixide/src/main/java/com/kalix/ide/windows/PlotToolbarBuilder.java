package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.preferences.PreferenceKeys;

import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import java.awt.Dimension;

/**
 * Builder for a plot tab's toolbar (save, undo/redo, palette, aggregation, mask,
 * plot type, y-scale, and the auto-Y / coordinates / legend toggles), extracted
 * from {@link VisualizationTabManager}. {@link #build()} also assembles the
 * {@link PlotToolbarController} used to reflect undo/redo state back into the
 * controls.
 */
class PlotToolbarBuilder {

    // === Toolbar sizing (shared with StatsToolbarBuilder) ===
    static final int BUTTON_ICON_SIZE = 14;
    static final Dimension WIDE_DROPDOWN_SIZE = new Dimension(150, 25);
    static final Dimension NARROW_DROPDOWN_SIZE = new Dimension(80, 25);
    static final int HORIZONTAL_SPACING = 5;
    // Shared square footprint for every icon/toggle button on this toolbar, so save,
    // undo/redo, palette, mask, auto-Y, coordinates and legend all line up identically
    // rather than each sizing itself off its own icon/border combination.
    static final Dimension BUTTON_SIZE = new Dimension(28, 28);

    /**
     * Aggregation period options for time series data (shared with
     * {@link StatsToolbarBuilder}).
     */
    static final String[] AGGREGATION_OPTIONS = {
        "Original",
        "Daily",
        "Monthly",
        "Annual (Jan-Dec)",
        "Annual (Feb-Jan)",
        "Annual (Mar-Feb)",
        "Annual (Apr-Mar)",
        "Annual (May-Apr)",
        "Annual (Jun-May)",
        "Annual (Jul-Jun)",
        "Annual (Aug-Jul)",
        "Annual (Sep-Aug)",
        "Annual (Oct-Sep)",
        "Annual (Nov-Oct)",
        "Annual (Dec-Nov)"
    };

    /** Aggregation method options (shared with {@link StatsToolbarBuilder}). */
    static final String[] AGGREGATION_METHOD_OPTIONS = {"Sum", "Min", "Max", "Mean"};

    /** Plot type options. */
    private static final String[] PLOT_TYPE_OPTIONS = {"Values", "Cumulative Values", "Difference", "Cumulative Difference", "Exceedance", "Double Mass", "Residual Mass"};

    /** Y-axis scale options. */
    private static final String[] Y_SPACE_OPTIONS = {"Linear", "Log", "Sqrt"};

    private final JToolBar toolbar;
    private final PlotPanel plotPanel;
    private java.util.function.Consumer<com.kalix.ide.flowviz.PlotState> onUndoRedo;

    // Store references to all state-reflecting controls
    private JComboBox<String> aggregationPeriodCombo;
    private JComboBox<String> aggregationMethodCombo;
    private JComboBox<String> plotTypeCombo;
    private JComboBox<String> ySpaceCombo;
    private JToggleButton maskToggle;
    private JToggleButton autoYToggle;

    PlotToolbarBuilder(PlotPanel plotPanel) {
        this.plotPanel = plotPanel;
        this.toolbar = new JToolBar();
        this.toolbar.setFloatable(false);
        this.toolbar.setRollover(true);
    }

    PlotToolbarBuilder setOnUndoRedo(java.util.function.Consumer<com.kalix.ide.flowviz.PlotState> callback) {
        this.onUndoRedo = callback;
        return this;
    }

    PlotToolbarBuilder addSaveButton() {
        JButton button = createIconButton(FontAwesomeSolid.SAVE, "Save Data", plotPanel::saveData);
        toolbar.add(button);
        return this;
    }

    /** Adds the button that opens the global plot-palette editor window. */
    PlotToolbarBuilder addPaletteButton() {
        JButton button = createIconButton(FontAwesomeSolid.PALETTE,
            "Plot Palettes…", PlotPaletteWindow::showWindow);
        toolbar.add(button);
        return this;
    }

    PlotToolbarBuilder addUndoRedoButtons() {
        JButton undoButton = createIconButton(FontAwesomeSolid.UNDO, "Undo", () -> {
            com.kalix.ide.flowviz.PlotState state = plotPanel.undo();
            if (state != null && onUndoRedo != null) {
                onUndoRedo.accept(state);
            }
        });
        JButton redoButton = createIconButton(FontAwesomeSolid.REDO, "Redo", () -> {
            com.kalix.ide.flowviz.PlotState state = plotPanel.redo();
            if (state != null && onUndoRedo != null) {
                onUndoRedo.accept(state);
            }
        });

        undoButton.setEnabled(false);
        redoButton.setEnabled(false);

        // Update button state whenever history changes
        plotPanel.setOnHistoryChanged(() -> {
            undoButton.setEnabled(plotPanel.canUndo());
            redoButton.setEnabled(plotPanel.canRedo());
        });

        toolbar.add(undoButton);
        toolbar.add(redoButton);
        return this;
    }

    PlotToolbarBuilder addAggregationControls() {
        // Resolution label
        toolbar.add(new JLabel("Resolution:"));
        toolbar.add(Box.createHorizontalStrut(HORIZONTAL_SPACING));

        // Aggregation period dropdown
        aggregationPeriodCombo = createDropdown(AGGREGATION_OPTIONS,
            WIDE_DROPDOWN_SIZE, "Aggregation");
        // Set initial value from current PlotPanel state
        aggregationPeriodCombo.setSelectedItem(plotPanel.getAggregationPeriod().getDisplayName());
        aggregationPeriodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationPeriodCombo);

        // "by" label
        toolbar.add(Box.createHorizontalStrut(HORIZONTAL_SPACING));
        toolbar.add(new JLabel("by"));
        toolbar.add(Box.createHorizontalStrut(HORIZONTAL_SPACING));

        // Aggregation method dropdown
        aggregationMethodCombo = createDropdown(AGGREGATION_METHOD_OPTIONS,
            NARROW_DROPDOWN_SIZE, "Aggregation method");
        // Set initial value from current PlotPanel state
        aggregationMethodCombo.setSelectedItem(plotPanel.getAggregationMethod().getDisplayName());
        aggregationMethodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationMethodCombo);

        return this;
    }

    /** Applies current aggregation settings to the plot panel. */
    private void applyAggregation() {
        if (aggregationPeriodCombo == null || aggregationMethodCombo == null) {
            return;
        }

        String periodStr = (String) aggregationPeriodCombo.getSelectedItem();
        String methodStr = (String) aggregationMethodCombo.getSelectedItem();

        if (periodStr != null && methodStr != null) {
            AggregationPeriod period = AggregationPeriod.fromDisplayName(periodStr);
            AggregationMethod method = AggregationMethod.fromDisplayName(methodStr);
            plotPanel.setAggregation(period, method);
        }
    }

    PlotToolbarBuilder addMaskToggle() {
        maskToggle = createToggleButton(FontAwesomeSolid.MASK,
            "Overlapping Data Mask", false);
        maskToggle.addActionListener(e -> {
            com.kalix.ide.flowviz.stats.MaskMode mode = maskToggle.isSelected()
                ? com.kalix.ide.flowviz.stats.MaskMode.ALL
                : com.kalix.ide.flowviz.stats.MaskMode.NONE;
            plotPanel.setMaskMode(mode);
        });
        toolbar.add(maskToggle);
        return this;
    }

    PlotToolbarBuilder addPlotTypeDropdown() {
        // Plot Type label
        toolbar.add(new JLabel("Plot Type:"));
        toolbar.add(Box.createHorizontalStrut(HORIZONTAL_SPACING));

        // Plot type dropdown
        plotTypeCombo = createDropdown(PLOT_TYPE_OPTIONS,
            WIDE_DROPDOWN_SIZE, "Plot type");
        // Set initial value from current PlotPanel state
        plotTypeCombo.setSelectedItem(plotPanel.getPlotType().getDisplayName());
        plotTypeCombo.addActionListener(e -> {
            String selected = (String) plotTypeCombo.getSelectedItem();
            if (selected != null) {
                com.kalix.ide.flowviz.transform.PlotType type =
                    com.kalix.ide.flowviz.transform.PlotType.fromDisplayName(selected);
                plotPanel.setPlotType(type);
            }
        });
        toolbar.add(plotTypeCombo);
        return this;
    }

    PlotToolbarBuilder addYSpaceDropdown() {
        ySpaceCombo = createDropdown(Y_SPACE_OPTIONS,
            NARROW_DROPDOWN_SIZE, "Y-axis scale");
        // Set initial value from current PlotPanel state
        ySpaceCombo.setSelectedItem(plotPanel.getYAxisScale().getDisplayName());
        ySpaceCombo.addActionListener(e -> {
            String selected = (String) ySpaceCombo.getSelectedItem();
            if (selected != null) {
                YAxisScale scale = YAxisScale.fromDisplayName(selected);
                plotPanel.setYAxisScale(scale);
            }
        });
        toolbar.add(ySpaceCombo);
        return this;
    }


    PlotToolbarBuilder addAutoYToggle(boolean initialState) {
        autoYToggle = createToggleButton(FontAwesomeSolid.ARROWS_ALT_V,
            "Auto-Y Mode", initialState);
        autoYToggle.addActionListener(e -> {
            boolean enabled = autoYToggle.isSelected();
            plotPanel.setAutoYMode(enabled);
            PreferenceKeys.FLOWVIZ_AUTO_Y_MODE.set(enabled);
        });

        // Follow changes made elsewhere (context menu, explicit axis limits). Only the
        // toolbar records the preference: those paths change this plot, not the default.
        plotPanel.setOnAutoYModeChanged(() -> autoYToggle.setSelected(plotPanel.isAutoYMode()));

        toolbar.add(autoYToggle);
        return this;
    }

    PlotToolbarBuilder addCoordinatesToggle(boolean initialState) {
        JToggleButton button = createToggleButton(FontAwesomeSolid.CROSSHAIRS,
            "Show Coordinates", initialState);
        button.addActionListener(e -> {
            plotPanel.setShowCoordinates(button.isSelected());
            PreferenceKeys.FLOWVIZ_SHOW_COORDINATES.set(button.isSelected());
        });
        toolbar.add(button);
        return this;
    }

    PlotToolbarBuilder addLegendToggle(boolean initialState) {
        JToggleButton button = createToggleButton(FontAwesomeSolid.KEY,
            "Show Key", initialState);

        // Update collapsed state when button is clicked
        button.addActionListener(e -> {
            plotPanel.setLegendCollapsed(!button.isSelected());
            PreferenceKeys.PLOT_LEGEND_COLLAPSED.set(!button.isSelected());
        });

        // Set up callback to update button when collapsed state changes from other sources
        plotPanel.getLegendManager().setOnCollapsedChanged(() -> {
            boolean collapsed = plotPanel.isLegendCollapsed();
            button.setSelected(!collapsed);
        });

        toolbar.add(button);
        return this;
    }

    PlotToolbarBuilder addSeparator() {
        toolbar.addSeparator();
        return this;
    }

    private PlotToolbarController controller;

    JToolBar build() {
        controller = new PlotToolbarController(
            aggregationPeriodCombo, aggregationMethodCombo,
            plotTypeCombo, ySpaceCombo, maskToggle, autoYToggle);
        return toolbar;
    }

    PlotToolbarController getController() {
        return controller;
    }

    /** Creates a standard icon button. */
    private JButton createIconButton(FontAwesomeSolid icon, String tooltip, Runnable action) {
        JButton button = new JButton(FontIcon.of(icon, BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setPreferredSize(BUTTON_SIZE);
        button.setMinimumSize(BUTTON_SIZE);
        button.setMaximumSize(BUTTON_SIZE);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** Creates a standard toggle button. */
    private JToggleButton createToggleButton(FontAwesomeSolid icon, String tooltip, boolean initialState) {
        JToggleButton button = new JToggleButton(FontIcon.of(icon, BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setSelected(initialState);
        button.setPreferredSize(BUTTON_SIZE);
        button.setMinimumSize(BUTTON_SIZE);
        button.setMaximumSize(BUTTON_SIZE);
        return button;
    }

    /** Creates a standard dropdown. */
    private JComboBox<String> createDropdown(String[] options, Dimension size, String tooltip) {
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setMaximumSize(size);
        combo.setToolTipText(tooltip);
        return combo;
    }
}
