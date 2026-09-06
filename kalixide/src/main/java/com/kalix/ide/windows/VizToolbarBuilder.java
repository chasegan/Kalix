package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.PlotState;
import com.kalix.ide.flowviz.rendering.PlotTypeListCellRenderer;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.preferences.PreferenceKeys;

import com.formdev.flatlaf.FlatClientProperties;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import java.awt.Dimension;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builder for a unified tab's single toolbar: the Plot ⁄ Stats view toggle
 * (leading), the always-visible shared controls (save, undo/redo, aggregation,
 * mask — one set of controls over the one shared state, so the two views can
 * never disagree), and the plot-only cluster (palette, plot type, y-scale,
 * auto-Y, coordinates, legend) shown only in the plot view.
 *
 * <p>All controls drive the tab's state-owning {@link PlotPanel}; the mask is
 * the ternary combo everywhere (the plot's old binary toggle is gone — EACH now
 * has a plot rendering too). Reflection back into the controls goes through
 * {@link VizToolbarController} with listeners silenced.
 */
class VizToolbarBuilder {

    /** Y-axis scale options. */
    private static final String[] Y_SPACE_OPTIONS = {"Linear", "Log", "Sqrt"};

    private final JToolBar toolbar;
    private final VisualizationTabManager.TabInfo tabInfo;
    private final JTable statsTable;

    private Consumer<PlotState> onUndoRedo;
    private Runnable onStateApplied;
    private Consumer<VisualizationTabManager.TabInfo.TabType> onViewToggle;

    private JToggleButton plotViewToggle;
    private JToggleButton statsViewToggle;
    private JComboBox<String> aggregationPeriodCombo;
    private JComboBox<String> aggregationMethodCombo;
    private JComboBox<String> maskCombo;
    private JComboBox<PlotType> plotTypeCombo;
    private JComboBox<String> ySpaceCombo;
    private JToggleButton autoYToggle;

    /** The plot-only cluster (incl. its separators/labels), hidden in the stats view. */
    private final List<JComponent> plotOnlyComponents = new ArrayList<>();
    private VizToolbarController controller;

    VizToolbarBuilder(VisualizationTabManager.TabInfo tabInfo, JTable statsTable) {
        this.tabInfo = tabInfo;
        this.statsTable = statsTable;
        this.toolbar = new JToolBar();
        this.toolbar.setFloatable(false);
        this.toolbar.setRollover(true);
    }

    VizToolbarBuilder setOnUndoRedo(Consumer<PlotState> callback) {
        this.onUndoRedo = callback;
        return this;
    }

    /** Invoked after a control applied a state change, so the owner reprojects the stats table. */
    VizToolbarBuilder setOnStateApplied(Runnable callback) {
        this.onStateApplied = callback;
        return this;
    }

    VizToolbarBuilder setOnViewToggle(Consumer<VisualizationTabManager.TabInfo.TabType> callback) {
        this.onViewToggle = callback;
        return this;
    }

    /** Builds the full toolbar and initialises every control from the tab's panel state. */
    JToolBar build(boolean initialAutoY, boolean initialShowCoordinates) {
        PlotPanel plotPanel = tabInfo.plotPanel;

        addViewToggle();
        toolbar.addSeparator();
        addSaveButton();
        addUndoRedoButtons();
        toolbar.addSeparator();
        addAggregationControls();
        addMaskControls();

        // --- Plot-only cluster (hidden in the stats view) ---
        plotOnly(new JToolBar.Separator());
        JButton palette = createIconButton(FontAwesomeSolid.PALETTE,
            "Plot Palettes…", PlotPaletteWindow::showWindow);
        plotOnly(palette);
        plotOnly(new JToolBar.Separator());
        addPlotTypeDropdown();
        plotOnly(new JToolBar.Separator());
        addYSpaceDropdown();
        plotOnly(new JToolBar.Separator());
        addAutoYToggle(initialAutoY);
        addCoordinatesToggle(initialShowCoordinates);
        addLegendToggle(plotPanel.isLegendEnabled());

        controller = new VizToolbarController(
            aggregationPeriodCombo, aggregationMethodCombo, maskCombo,
            plotTypeCombo, ySpaceCombo, autoYToggle);
        applyViewMode(tabInfo.viewMode);
        return toolbar;
    }

    VizToolbarController getController() {
        return controller;
    }

    /**
     * Reflects a view switch into the toolbar: the plot-only cluster hides in the
     * stats view and the toggle pair follows. Selection is set directly (no action
     * fires), so this is safe from the switch path itself.
     */
    void applyViewMode(VisualizationTabManager.TabInfo.TabType mode) {
        boolean plot = mode == VisualizationTabManager.TabInfo.TabType.PLOT;
        for (JComponent component : plotOnlyComponents) {
            component.setVisible(plot);
        }
        plotViewToggle.setSelected(plot);
        statsViewToggle.setSelected(!plot);
        toolbar.revalidate();
        toolbar.repaint();
    }

    private void addViewToggle() {
        plotViewToggle = viewToggleButton(FontAwesomeSolid.CHART_LINE, "Plot view");
        statsViewToggle = viewToggleButton(FontAwesomeSolid.CALCULATOR, "Stats view");
        ButtonGroup group = new ButtonGroup();
        group.add(plotViewToggle);
        group.add(statsViewToggle);
        plotViewToggle.addActionListener(e -> fireViewToggle(VisualizationTabManager.TabInfo.TabType.PLOT));
        statsViewToggle.addActionListener(e -> fireViewToggle(VisualizationTabManager.TabInfo.TabType.STATS));
        toolbar.add(plotViewToggle);
        toolbar.add(statsViewToggle);
    }

    private void fireViewToggle(VisualizationTabManager.TabInfo.TabType mode) {
        if (onViewToggle != null) {
            onViewToggle.accept(mode);
        }
    }

    private JToggleButton viewToggleButton(FontAwesomeSolid icon, String tooltip) {
        JToggleButton button = new JToggleButton(FontIcon.of(icon, ToolbarConstants.BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        // FlatLaf's segmented-tab look: an underlined pair rather than pressed buttons.
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TAB);
        button.setPreferredSize(ToolbarConstants.BUTTON_SIZE);
        button.setMinimumSize(ToolbarConstants.BUTTON_SIZE);
        button.setMaximumSize(ToolbarConstants.BUTTON_SIZE);
        return button;
    }

    private void addSaveButton() {
        // Shared position, view-dispatched action: the plot's timeseries save or
        // the stats table's CSV export, whichever page is showing.
        JButton button = createIconButton(FontAwesomeSolid.SAVE, "Save Data", () -> {
            if (tabInfo.viewMode == VisualizationTabManager.TabInfo.TabType.PLOT) {
                tabInfo.plotPanel.saveData();
            } else {
                saveStatsData();
            }
        });
        toolbar.add(button);
    }

    private void addUndoRedoButtons() {
        JButton undoButton = createIconButton(FontAwesomeSolid.UNDO, "Undo", () -> {
            PlotState state = tabInfo.plotPanel.undo();
            if (state != null && onUndoRedo != null) {
                onUndoRedo.accept(state);
            }
        });
        JButton redoButton = createIconButton(FontAwesomeSolid.REDO, "Redo", () -> {
            PlotState state = tabInfo.plotPanel.redo();
            if (state != null && onUndoRedo != null) {
                onUndoRedo.accept(state);
            }
        });

        // Initialise from the panel: a duplicated tab has history before its toolbar exists.
        undoButton.setEnabled(tabInfo.plotPanel.canUndo());
        redoButton.setEnabled(tabInfo.plotPanel.canRedo());
        tabInfo.plotPanel.setOnHistoryChanged(() -> {
            undoButton.setEnabled(tabInfo.plotPanel.canUndo());
            redoButton.setEnabled(tabInfo.plotPanel.canRedo());
        });

        toolbar.add(undoButton);
        toolbar.add(redoButton);
    }

    private void addAggregationControls() {
        toolbar.add(new JLabel("Resolution:"));
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));

        aggregationPeriodCombo = createDropdown(ToolbarConstants.AGGREGATION_OPTIONS,
            ToolbarConstants.WIDE_DROPDOWN_SIZE, "Aggregation");
        aggregationPeriodCombo.setSelectedItem(tabInfo.plotPanel.getAggregationPeriod().getDisplayName());
        aggregationPeriodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationPeriodCombo);

        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));
        toolbar.add(new JLabel("by"));
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));

        aggregationMethodCombo = createDropdown(ToolbarConstants.AGGREGATION_METHOD_OPTIONS,
            ToolbarConstants.NARROW_DROPDOWN_SIZE, "Aggregation method");
        aggregationMethodCombo.setSelectedItem(tabInfo.plotPanel.getAggregationMethod().getDisplayName());
        aggregationMethodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationMethodCombo);
    }

    private void addMaskControls() {
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));
        toolbar.add(new JLabel("Mask:"));
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));

        String[] maskOptions = {"All", "Each", "None"};
        maskCombo = createDropdown(maskOptions,
            ToolbarConstants.NARROW_DROPDOWN_SIZE, "Mask mode for bivariate statistics");
        maskCombo.setSelectedItem(tabInfo.plotPanel.getMaskMode().getDisplayName());
        maskCombo.addActionListener(e -> {
            String selected = (String) maskCombo.getSelectedItem();
            if (selected != null) {
                tabInfo.plotPanel.setMaskMode(MaskMode.fromDisplayName(selected));
                applied();
            }
        });
        toolbar.add(maskCombo);
    }

    private void applyAggregation() {
        if (aggregationPeriodCombo == null || aggregationMethodCombo == null) {
            return;
        }
        String periodStr = (String) aggregationPeriodCombo.getSelectedItem();
        String methodStr = (String) aggregationMethodCombo.getSelectedItem();
        if (periodStr != null && methodStr != null) {
            AggregationPeriod period = AggregationPeriod.fromDisplayName(periodStr);
            AggregationMethod method = AggregationMethod.fromDisplayName(methodStr);
            tabInfo.plotPanel.setAggregation(period, method);
            applied();
        }
    }

    private void addPlotTypeDropdown() {
        JLabel label = new JLabel("Plot Type:");
        plotOnly(label);
        JComponent strut = (JComponent) Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING);
        plotOnly(strut);

        this.plotTypeCombo = new JComboBox<>(PlotType.values());
        plotTypeCombo.setMaximumSize(ToolbarConstants.WIDE_DROPDOWN_SIZE);
        plotTypeCombo.setToolTipText("Plot type");
        plotTypeCombo.setRenderer(new PlotTypeListCellRenderer());
        plotTypeCombo.setSelectedItem(tabInfo.plotPanel.getPlotType());
        plotTypeCombo.addActionListener(e -> {
            PlotType selected = (PlotType) plotTypeCombo.getSelectedItem();
            // Re-picking the current type is a no-op: the combo fires even for a same-item
            // selection, and applying the default then would stomp a manual mask override,
            // reset the zoom, and push a spurious undo entry.
            if (selected == null || selected == tabInfo.plotPanel.getPlotType()) {
                return;
            }
            tabInfo.plotPanel.setPlotTypeAndMaskMode(selected,
                selected.isDataMaskDefault() ? MaskMode.ALL : MaskMode.NONE);
            // Reflect the panel's resulting mask silently (the mask is shared state now).
            VizToolbarController.setSilently(maskCombo, tabInfo.plotPanel.getMaskMode().getDisplayName());
            applied();
        });
        plotOnly(plotTypeCombo);
    }

    private void addYSpaceDropdown() {
        ySpaceCombo = createDropdown(Y_SPACE_OPTIONS,
            ToolbarConstants.NARROW_DROPDOWN_SIZE, "Y-axis scale");
        ySpaceCombo.setSelectedItem(tabInfo.plotPanel.getYAxisScale().getDisplayName());
        ySpaceCombo.addActionListener(e -> {
            String selected = (String) ySpaceCombo.getSelectedItem();
            if (selected != null) {
                tabInfo.plotPanel.setYAxisScale(YAxisScale.fromDisplayName(selected));
            }
        });
        plotOnly(ySpaceCombo);
    }

    private void addAutoYToggle(boolean initialState) {
        autoYToggle = createToggleButton(FontAwesomeSolid.ARROWS_ALT_V, "Auto-Y Mode", initialState);
        autoYToggle.addActionListener(e -> {
            boolean enabled = autoYToggle.isSelected();
            tabInfo.plotPanel.setAutoYMode(enabled);
            PreferenceKeys.FLOWVIZ_AUTO_Y_MODE.set(enabled);
        });
        // Follow changes made elsewhere (context menu, explicit axis limits). Only the
        // toolbar records the preference: those paths change this plot, not the default.
        tabInfo.plotPanel.setOnAutoYModeChanged(() -> autoYToggle.setSelected(tabInfo.plotPanel.isAutoYMode()));
        plotOnly(autoYToggle);
    }

    private void addCoordinatesToggle(boolean initialState) {
        JToggleButton button = createToggleButton(FontAwesomeSolid.CROSSHAIRS, "Show Coordinates", initialState);
        button.addActionListener(e -> {
            tabInfo.plotPanel.setShowCoordinates(button.isSelected());
            PreferenceKeys.FLOWVIZ_SHOW_COORDINATES.set(button.isSelected());
        });
        plotOnly(button);
    }

    private void addLegendToggle(boolean initialState) {
        JToggleButton button = createToggleButton(FontAwesomeSolid.KEY, "Show Key", initialState);
        // The legend manager persists its own state, so no preference write here.
        button.addActionListener(e -> tabInfo.plotPanel.setLegendEnabled(button.isSelected()));
        tabInfo.plotPanel.getLegendManager().setOnEnabledChanged(() ->
            button.setSelected(tabInfo.plotPanel.isLegendEnabled()));
        plotOnly(button);
    }

    /** Adds a component to the toolbar AND records it as plot-only (hidden in stats view). */
    private void plotOnly(JComponent component) {
        plotOnlyComponents.add(component);
        toolbar.add(component);
    }

    private void applied() {
        if (onStateApplied != null) {
            onStateApplied.run();
        }
    }

    /** Saves stats data to CSV. */
    private void saveStatsData() {
        // The suggested name carries the conventional extension; the dialog takes whatever
        // the user types verbatim and confirms any overwrite itself.
        java.util.Optional<File> chosen = KalixFileDialog.saveFile(statsTable)
            .title("Save Statistics")
            .suggestedName("statistics.csv")
            .filters(FileDialogFilter.of("CSV Files (*.csv)", "csv"))
            .show();
        if (chosen.isPresent()) {
            File file = chosen.get();

            try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                // Write header (dynamic columns from table)
                for (int col = 0; col < statsTable.getColumnCount(); col++) {
                    if (col > 0) writer.write(",");
                    writer.write(statsTable.getColumnName(col));
                }
                writer.write("\n");

                // Write data rows
                for (int row = 0; row < statsTable.getRowCount(); row++) {
                    for (int col = 0; col < statsTable.getColumnCount(); col++) {
                        if (col > 0) writer.write(",");
                        Object value = statsTable.getValueAt(row, col);
                        writer.write(value != null ? value.toString() : "");
                    }
                    writer.write("\n");
                }

                JOptionPane.showMessageDialog(statsTable,
                    "Statistics saved successfully to:\n" + file.getAbsolutePath(),
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);

            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(statsTable,
                    "Error saving statistics: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Creates a standard icon button. */
    private JButton createIconButton(FontAwesomeSolid icon, String tooltip, Runnable action) {
        JButton button = new JButton(FontIcon.of(icon, ToolbarConstants.BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setPreferredSize(ToolbarConstants.BUTTON_SIZE);
        button.setMinimumSize(ToolbarConstants.BUTTON_SIZE);
        button.setMaximumSize(ToolbarConstants.BUTTON_SIZE);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** Creates a standard toggle button. */
    private JToggleButton createToggleButton(FontAwesomeSolid icon, String tooltip, boolean initialState) {
        JToggleButton button = new JToggleButton(FontIcon.of(icon, ToolbarConstants.BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setSelected(initialState);
        button.setPreferredSize(ToolbarConstants.BUTTON_SIZE);
        button.setMinimumSize(ToolbarConstants.BUTTON_SIZE);
        button.setMaximumSize(ToolbarConstants.BUTTON_SIZE);
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
