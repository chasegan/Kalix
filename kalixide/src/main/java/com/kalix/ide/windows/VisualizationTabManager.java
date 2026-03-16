package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages visualization tabs (right side of RunManager) - plots and statistics.
 *
 * <h2>Shared Data Model</h2>
 * All tabs share the same {@link DataSet} ({@code sharedDataSet}) and color map.
 * When data changes in the DataSet, all tabs see it immediately. However, each
 * {@link PlotPanel} has its own display cache that must be invalidated via
 * {@link #updateAllTabs} → {@link PlotPanel#refreshData}.
 *
 * <h2>Tab Types</h2>
 * <ul>
 *   <li><b>PLOT</b> - Time series chart with aggregation, transforms, and LOD rendering</li>
 *   <li><b>STATS</b> - Statistics table showing min/max/mean/etc. for each series</li>
 * </ul>
 *
 * <h2>Update Flow</h2>
 * When data is added/removed from the shared DataSet:
 * <ol>
 *   <li>RunManager calls {@link #updateAllTabs(boolean)}</li>
 *   <li>For each PLOT tab: calls {@link PlotPanel#refreshData} (invalidates caches, rebuilds)</li>
 *   <li>For STATS tabs: they update automatically via DataSet listeners</li>
 * </ol>
 *
 * <h2>New Tab Behavior</h2>
 * When adding a new tab ({@link #addPlotTab}), it's connected to {@code sharedDataSet}
 * and immediately sees all current data. The new tab's display cache is built fresh,
 * which is why new tabs show correct data even if existing tabs have stale caches.
 *
 * @see PlotPanel
 * @see com.kalix.ide.windows.RunManager#addSeriesToPool
 */
public class VisualizationTabManager {

    private final JTabbedPane tabbedPane;
    private final DataSet sharedDataSet;           // Single source of truth for all tabs
    private final Map<String, Color> sharedColorMap;  // Consistent colors across all tabs
    private final List<TabInfo> tabs;

    // Tab change tracking
    private int lastActivePlotTabIndex = 0;
    private Runnable onTabChangedCallback;

    // Drag and drop state
    private static final int DRAG_THRESHOLD = 5;
    private JPanel draggingTab = null;
    private int dragStartIndex = -1;
    private int pressX, pressY;

    /**
     * UI constants for consistent styling and sizing.
     */
    private static class UIConstants {
        static final int TAB_ICON_SIZE = 14;
        static final int BUTTON_ICON_SIZE = 14;
        static final Dimension WIDE_DROPDOWN_SIZE = new Dimension(150, 25);
        static final Dimension NARROW_DROPDOWN_SIZE = new Dimension(80, 25);
        static final Color DRAG_HIGHLIGHT_COLOR = new Color(200, 200, 255, 100);
        static final int HORIZONTAL_SPACING = 5;
        static final int TAB_PANEL_PADDING = 2;
    }

    /**
     * Aggregation period options for time series data.
     */
    private static final String[] AGGREGATION_OPTIONS = {
        "Original",
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

    /** Aggregation method options. */
    private static final String[] AGGREGATION_METHOD_OPTIONS = {"Sum", "Min", "Max", "Mean"};

    /** Plot type options. */
    private static final String[] PLOT_TYPE_OPTIONS = {"Values", "Cumulative Values", "Difference", "Cumulative Difference", "Exceedance", "Double Mass", "Residual Mass"};

    /** Y-axis scale options. */
    private static final String[] Y_SPACE_OPTIONS = {"Linear", "Log", "Sqrt"};

    /**
     * Settings that can be copied between tabs.
     * Makes it explicit what settings are shareable and provides a clean interface for copying.
     */
    public static class TabSettings {
        // Aggregation settings (common to both plot and stats tabs)
        public AggregationPeriod aggregationPeriod = AggregationPeriod.ORIGINAL;
        public AggregationMethod aggregationMethod = AggregationMethod.SUM;

        // Plot-specific settings (ignored when creating stats tabs)
        public com.kalix.ide.flowviz.transform.PlotType plotType = com.kalix.ide.flowviz.transform.PlotType.VALUES;
        public YAxisScale yAxisScale = YAxisScale.LINEAR;
        public boolean autoYMode = true;
        public boolean showCoordinates = false;
        public boolean legendCollapsed = false;

        // Series selection from source tab (null = inherit from active tab)
        public Set<String> selectedSeries = null;

        /**
         * Extract settings from a plot tab.
         */
        public static TabSettings fromPlotTab(PlotPanel plotPanel, TabInfo tabInfo) {
            TabSettings settings = new TabSettings();
            settings.aggregationPeriod = plotPanel.getAggregationPeriod();
            settings.aggregationMethod = plotPanel.getAggregationMethod();
            settings.plotType = plotPanel.getPlotType();
            settings.yAxisScale = plotPanel.getYAxisScale();
            settings.autoYMode = plotPanel.isAutoYMode();
            settings.showCoordinates = plotPanel.isShowCoordinates();
            settings.legendCollapsed = plotPanel.isLegendCollapsed();
            settings.selectedSeries = new LinkedHashSet<>(tabInfo.selectedSeries);
            return settings;
        }

        /**
         * Extract settings from a stats tab.
         */
        public static TabSettings fromStatsTab(TabInfo statsTabInfo) {
            TabSettings settings = new TabSettings();
            settings.aggregationPeriod = statsTabInfo.statsPeriod;
            settings.aggregationMethod = statsTabInfo.statsMethod;
            settings.selectedSeries = new LinkedHashSet<>(statsTabInfo.selectedSeries);
            return settings;
        }

        /**
         * Get default settings from preferences.
         */
        public static TabSettings getDefaults() {
            TabSettings settings = new TabSettings();
            settings.showCoordinates = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, false);
            settings.autoYMode = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true);
            return settings;
        }
    }

    /**
     * Represents a visualization tab with its type and components.
     */
    private static class TabInfo {
        enum TabType { PLOT, STATS }

        final TabType type;
        final String name;
        final JComponent component;
        final PlotPanel plotPanel; // null for stats tabs
        final StatsTableModel statsModel; // null for plot tabs

        // Per-tab selected series (plot tabs only). Preserves insertion order for legend consistency.
        final Set<String> selectedSeries = new LinkedHashSet<>();

        // Aggregation settings for stats tabs
        AggregationPeriod statsPeriod = AggregationPeriod.ORIGINAL;
        AggregationMethod statsMethod = AggregationMethod.SUM;

        TabInfo(TabType type, String name, JComponent component, PlotPanel plotPanel, StatsTableModel statsModel) {
            this.type = type;
            this.name = name;
            this.component = component;
            this.plotPanel = plotPanel;
            this.statsModel = statsModel;
        }
    }

    /**
     * Creates a new tab manager for visualization tabs.
     *
     * @param sharedDataSet The dataset shared across all tabs
     * @param sharedColorMap The color mapping shared across all tabs
     */
    public VisualizationTabManager(DataSet sharedDataSet, Map<String, Color> sharedColorMap) {
        this.sharedDataSet = sharedDataSet;
        this.sharedColorMap = sharedColorMap;
        this.tabs = new ArrayList<>();

        // Create tabbed pane with close buttons
        this.tabbedPane = new JTabbedPane();
        this.tabbedPane.setTabPlacement(JTabbedPane.TOP);
        this.tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        // Track tab changes for tree synchronization
        this.tabbedPane.addChangeListener(e -> {
            TabInfo active = getActiveTab();
            if (active != null && active.type == TabInfo.TabType.PLOT) {
                lastActivePlotTabIndex = tabbedPane.getSelectedIndex();
            }
            if (onTabChangedCallback != null) {
                onTabChangedCallback.run();
            }
        });
    }

    /**
     * Sets a callback to be invoked when the active tab changes.
     * Used by RunManager to synchronize tree selection with the active tab.
     */
    public void setOnTabChangedCallback(Runnable callback) {
        this.onTabChangedCallback = callback;
    }

    /**
     * Adds a new plot tab with default settings from preferences.
     *
     * @return The created PlotPanel
     */
    public PlotPanel addPlotTab() {
        // Create new plot panel with shared dataset
        PlotPanel plotPanel = new PlotPanel();
        plotPanel.setDataSet(sharedDataSet);
        plotPanel.setSeriesColors(sharedColorMap);

        // Inherit series from active plot tab, or use all series from pool for the first tab
        TabInfo activeTab = getActivePlotTab();
        Set<String> inheritedSeries;
        if (activeTab != null) {
            inheritedSeries = new LinkedHashSet<>(activeTab.selectedSeries);
        } else {
            inheritedSeries = new LinkedHashSet<>(sharedDataSet.getSeriesNames());
        }
        plotPanel.setVisibleSeries(new ArrayList<>(inheritedSeries));

        // Get default settings from preferences
        boolean defaultShowCoordinates = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, false);
        plotPanel.setShowCoordinates(defaultShowCoordinates);

        boolean defaultAutoY = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true);
        plotPanel.setAutoYMode(defaultAutoY);

        boolean defaultLegendCollapsed = PreferenceManager.getFileBoolean(PreferenceKeys.PLOT_LEGEND_COLLAPSED, false);
        plotPanel.setLegendCollapsed(defaultLegendCollapsed);

        // Populate legend with inherited series
        for (String seriesName : inheritedSeries) {
            Color color = sharedColorMap.get(seriesName);
            if (color != null) {
                plotPanel.addLegendSeries(seriesName, color);
            }
        }

        // Create container panel with toolbar
        JPanel containerPanel = new JPanel(new BorderLayout());
        JToolBar toolbar = createPlotToolbar(plotPanel, defaultAutoY, defaultShowCoordinates);
        containerPanel.add(toolbar, BorderLayout.NORTH);
        containerPanel.add(plotPanel, BorderLayout.CENTER);

        // Add tab with inherited series selection
        TabInfo tabInfo = new TabInfo(TabInfo.TabType.PLOT, "Plot", containerPanel, plotPanel, null);
        tabInfo.selectedSeries.addAll(inheritedSeries);
        tabs.add(tabInfo);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab("", containerPanel);
        setupTabIcon(index, TabInfo.TabType.PLOT);

        // Push initial state to history
        plotPanel.pushState();

        return plotPanel;
    }

    /**
     * Adds a new plot tab with settings copied from another tab.
     * The new plot tab will have the same settings and all series from the source tab.
     *
     * @param settings The settings to apply to the new plot tab
     * @return The created PlotPanel
     */
    public PlotPanel addPlotTabFromSettings(TabSettings settings) {
        // Create new plot panel with shared dataset
        PlotPanel plotPanel = new PlotPanel();
        plotPanel.setDataSet(sharedDataSet);
        plotPanel.setSeriesColors(sharedColorMap);

        // Use series from settings if provided, otherwise inherit from active tab
        Set<String> inheritedSeries;
        if (settings.selectedSeries != null) {
            inheritedSeries = new LinkedHashSet<>(settings.selectedSeries);
        } else {
            TabInfo activeTab = getActivePlotTab();
            inheritedSeries = activeTab != null
                ? new LinkedHashSet<>(activeTab.selectedSeries)
                : new LinkedHashSet<>(sharedDataSet.getSeriesNames());
        }
        plotPanel.setVisibleSeries(new ArrayList<>(inheritedSeries));

        // Apply all settings from TabSettings (must be done AFTER setVisibleSeries)
        plotPanel.setAggregation(settings.aggregationPeriod, settings.aggregationMethod);
        plotPanel.setPlotType(settings.plotType);
        plotPanel.setYAxisScale(settings.yAxisScale);
        plotPanel.setAutoYMode(settings.autoYMode);
        plotPanel.setShowCoordinates(settings.showCoordinates);
        if (settings.legendCollapsed) {
            plotPanel.setLegendCollapsed(true);
        }

        // Populate legend with inherited series
        for (String seriesName : inheritedSeries) {
            Color color = sharedColorMap.get(seriesName);
            if (color != null) {
                plotPanel.addLegendSeries(seriesName, color);
            }
        }

        // Create container panel with toolbar
        JPanel containerPanel = new JPanel(new BorderLayout());
        JToolBar toolbar = createPlotToolbar(plotPanel, settings.autoYMode, settings.showCoordinates);
        containerPanel.add(toolbar, BorderLayout.NORTH);
        containerPanel.add(plotPanel, BorderLayout.CENTER);

        // Add tab with inherited series selection
        TabInfo tabInfo = new TabInfo(TabInfo.TabType.PLOT, "Plot", containerPanel, plotPanel, null);
        tabInfo.selectedSeries.addAll(inheritedSeries);
        tabs.add(tabInfo);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab("", containerPanel);
        setupTabIcon(index, TabInfo.TabType.PLOT);

        // Select the new tab
        tabbedPane.setSelectedIndex(index);

        // Push initial state to history
        plotPanel.pushState();

        return plotPanel;
    }

    /**
     * Creates a toolbar for a plot tab.
     */
    private JToolBar createPlotToolbar(PlotPanel plotPanel, boolean initialAutoY, boolean initialShowCoordinates) {
        PlotToolbarBuilder builder = new PlotToolbarBuilder(plotPanel);
        builder
            .setOnUndoRedo(state -> {
                syncTabSelectionFromPlotState(plotPanel, state);
                builder.getController().updateFromState(state);
            })
            .addSaveButton()
            .addUndoRedoButtons()
            .addSeparator()
            .addAggregationControls()
            .addSeparator()
            .addMaskToggle()
            .addSeparator()
            .addPlotTypeDropdown()
            .addSeparator()
            .addYSpaceDropdown()
            .addSeparator()
            .addAutoYToggle(initialAutoY)
            .addCoordinatesToggle(initialShowCoordinates)
            .addLegendToggle(!plotPanel.isLegendCollapsed());
        return builder.build();
    }

    /**
     * Creates a toolbar for a stats tab.
     */
    private JToolBar createStatsToolbar(TabInfo tabInfo, JTable statsTable) {
        return new StatsToolbarBuilder(tabInfo, statsTable, sharedDataSet)
            .addSaveButton()
            .addSeparator()
            .addAggregationControls()
            .addSeparator()
            .addMaskControls()
            .build();
    }

    /**
     * Builder for creating plot toolbars with consistent styling.
     */
    /**
     * Controller for updating toolbar controls from a PlotState without triggering listeners.
     */
    static class PlotToolbarController {
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

    private static class PlotToolbarBuilder {
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
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Aggregation period dropdown
            aggregationPeriodCombo = createDropdown(AGGREGATION_OPTIONS,
                UIConstants.WIDE_DROPDOWN_SIZE, "Aggregation");
            // Set initial value from current PlotPanel state
            aggregationPeriodCombo.setSelectedItem(plotPanel.getAggregationPeriod().getDisplayName());
            aggregationPeriodCombo.addActionListener(e -> applyAggregation());
            toolbar.add(aggregationPeriodCombo);

            // "by" label
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));
            toolbar.add(new JLabel("by"));
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Aggregation method dropdown
            aggregationMethodCombo = createDropdown(AGGREGATION_METHOD_OPTIONS,
                UIConstants.NARROW_DROPDOWN_SIZE, "Aggregation method");
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
                "Mask: only include timestamps where all series have valid data", false);
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
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Plot type dropdown
            plotTypeCombo = createDropdown(PLOT_TYPE_OPTIONS,
                UIConstants.WIDE_DROPDOWN_SIZE, "Plot type");
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
                UIConstants.NARROW_DROPDOWN_SIZE, "Y-axis scale");
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
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, enabled);
                if (enabled) {
                    // Fit Y-axis to visible data in current X range (don't change X zoom)
                    plotPanel.fitYAxis();
                }
            });
            toolbar.add(autoYToggle);
            return this;
        }

        PlotToolbarBuilder addCoordinatesToggle(boolean initialState) {
            JToggleButton button = createToggleButton(FontAwesomeSolid.CROSSHAIRS,
                "Show Coordinates", initialState);
            button.addActionListener(e -> {
                plotPanel.setShowCoordinates(button.isSelected());
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, button.isSelected());
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
                PreferenceManager.setFileBoolean(PreferenceKeys.PLOT_LEGEND_COLLAPSED, !button.isSelected());
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
            JButton button = new JButton(FontIcon.of(icon, UIConstants.BUTTON_ICON_SIZE));
            button.setToolTipText(tooltip);
            button.setFocusable(false);
            button.addActionListener(e -> action.run());
            return button;
        }

        /** Creates a standard toggle button. */
        private JToggleButton createToggleButton(FontAwesomeSolid icon, String tooltip, boolean initialState) {
            JToggleButton button = new JToggleButton(FontIcon.of(icon, UIConstants.BUTTON_ICON_SIZE));
            button.setToolTipText(tooltip);
            button.setFocusable(false);
            button.setSelected(initialState);
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

    /**
     * Builder for creating stats toolbars with consistent styling.
     */
    private static class StatsToolbarBuilder {
        private final JToolBar toolbar;
        private final TabInfo tabInfo;
        private final JTable statsTable;
        private final DataSet dataSet;

        // Store dropdown references for coordinated updates
        private JComboBox<String> aggregationPeriodCombo;
        private JComboBox<String> aggregationMethodCombo;

        StatsToolbarBuilder(TabInfo tabInfo, JTable statsTable, DataSet dataSet) {
            this.tabInfo = tabInfo;
            this.statsTable = statsTable;
            this.dataSet = dataSet;
            this.toolbar = new JToolBar();
            this.toolbar.setFloatable(false);
            this.toolbar.setRollover(true);
        }

        StatsToolbarBuilder addSaveButton() {
            JButton button = createIconButton(FontAwesomeSolid.SAVE, "Save Data", this::saveStatsData);
            toolbar.add(button);
            return this;
        }

        StatsToolbarBuilder addAggregationControls() {
            // Resolution label
            toolbar.add(new JLabel("Resolution:"));
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Aggregation period dropdown
            aggregationPeriodCombo = createDropdown(AGGREGATION_OPTIONS,
                UIConstants.WIDE_DROPDOWN_SIZE, "Aggregation");
            // Set initial selection from tab info
            aggregationPeriodCombo.setSelectedItem(tabInfo.statsPeriod.getDisplayName());
            aggregationPeriodCombo.addActionListener(e -> applyAggregation());
            toolbar.add(aggregationPeriodCombo);

            // "by" label
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));
            toolbar.add(new JLabel("by"));
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Aggregation method dropdown
            aggregationMethodCombo = createDropdown(AGGREGATION_METHOD_OPTIONS,
                UIConstants.NARROW_DROPDOWN_SIZE, "Aggregation method");
            // Set initial selection from tab info
            aggregationMethodCombo.setSelectedItem(tabInfo.statsMethod.getDisplayName());
            aggregationMethodCombo.addActionListener(e -> applyAggregation());
            toolbar.add(aggregationMethodCombo);

            return this;
        }

        StatsToolbarBuilder addMaskControls() {
            // Mask label
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));
            toolbar.add(new JLabel("Mask:"));
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Mask mode dropdown
            String[] maskOptions = {"All", "Each", "None"};
            JComboBox<String> maskCombo = createDropdown(maskOptions,
                UIConstants.NARROW_DROPDOWN_SIZE, "Mask mode for bivariate statistics");

            // Set initial selection from stats model
            if (tabInfo.statsModel != null) {
                maskCombo.setSelectedItem(tabInfo.statsModel.getMaskMode().getDisplayName());
            }

            maskCombo.addActionListener(e -> {
                String selected = (String) maskCombo.getSelectedItem();
                if (selected != null && tabInfo.statsModel != null) {
                    com.kalix.ide.flowviz.stats.MaskMode mode =
                        com.kalix.ide.flowviz.stats.MaskMode.fromDisplayName(selected);
                    tabInfo.statsModel.setMaskMode(mode);
                }
            });
            toolbar.add(maskCombo);

            return this;
        }

        /** Applies current aggregation settings to stats. */
        private void applyAggregation() {
            if (aggregationPeriodCombo == null || aggregationMethodCombo == null) {
                return;
            }

            String periodStr = (String) aggregationPeriodCombo.getSelectedItem();
            String methodStr = (String) aggregationMethodCombo.getSelectedItem();

            if (periodStr != null && methodStr != null) {
                AggregationPeriod period = AggregationPeriod.fromDisplayName(periodStr);
                AggregationMethod method = AggregationMethod.fromDisplayName(methodStr);

                // Update tab info aggregation settings
                tabInfo.statsPeriod = period;
                tabInfo.statsMethod = method;

                // Recompute stats with aggregated data
                recomputeStats();
            }
        }

        /** Recomputes stats with current aggregation settings, filtered to per-tab series. */
        private void recomputeStats() {
            if (tabInfo.statsModel == null || dataSet == null) {
                return;
            }

            tabInfo.statsModel.clear();
            for (String seriesName : tabInfo.selectedSeries) {
                TimeSeriesData originalSeries = dataSet.getSeries(seriesName);
                if (originalSeries != null) {
                    TimeSeriesData aggregatedSeries = com.kalix.ide.flowviz.transform.TimeSeriesAggregator.aggregate(
                        originalSeries, tabInfo.statsPeriod, tabInfo.statsMethod);
                    if (aggregatedSeries != null) {
                        tabInfo.statsModel.addOrUpdateSeries(aggregatedSeries);
                    }
                }
            }
        }

        /** Saves stats data to CSV. */
        private void saveStatsData() {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Save Statistics");
            fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));

            int result = fileChooser.showSaveDialog(statsTable);
            if (result == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                if (!file.getName().toLowerCase().endsWith(".csv")) {
                    file = new File(file.getAbsolutePath() + ".csv");
                }

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

        StatsToolbarBuilder addSeparator() {
            toolbar.addSeparator();
            return this;
        }

        JToolBar build() {
            return toolbar;
        }

        /** Creates a standard icon button. */
        private JButton createIconButton(FontAwesomeSolid icon, String tooltip, Runnable action) {
            JButton button = new JButton(FontIcon.of(icon, UIConstants.BUTTON_ICON_SIZE));
            button.setToolTipText(tooltip);
            button.setFocusable(false);
            button.addActionListener(e -> action.run());
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

    /**
     * Applies a custom cell renderer to the stats table that colors statistic values.
     * The Series column (column 0) remains default, while statistic columns use a theme color.
     */
    private void applyStatsTableRenderer(JTable table) {
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

                java.awt.Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);

                if (c instanceof JLabel) {
                    JLabel label = (JLabel) c;

                    if (!isSelected) {
                        if (column == 0) {
                            // Series name column - use default foreground color
                            Color defaultColor = UIManager.getColor("Table.foreground");
                            if (defaultColor != null) {
                                label.setForeground(defaultColor);
                            }
                        } else {
                            // Statistic columns - use muted theme color
                            Color statsColor = UIManager.getColor("Label.disabledForeground");
                            if (statsColor == null) {
                                // Fallback to a semi-transparent default text color
                                Color defaultColor = UIManager.getColor("Table.foreground");
                                if (defaultColor != null) {
                                    statsColor = new Color(
                                        defaultColor.getRed(),
                                        defaultColor.getGreen(),
                                        defaultColor.getBlue(),
                                        160  // ~63% opacity
                                    );
                                }
                            }
                            if (statsColor != null) {
                                label.setForeground(statsColor);
                            }
                        }
                    }
                }

                return c;
            }
        });
    }

    /**
     * Adds a new statistics tab.
     *
     * @return The created StatsTableModel
     */
    public StatsTableModel addStatsTab() {
        // Create new stats table
        StatsTableModel model = new StatsTableModel();
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);

        // Apply custom renderer to color statistics columns
        applyStatsTableRenderer(table);

        // Make the Series column wider to accommodate longer series names
        if (table.getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setPreferredWidth(200);
        }

        JScrollPane scrollPane = new JScrollPane(table);

        // Create container panel with toolbar
        JPanel containerPanel = new JPanel(new BorderLayout());

        // Create tab info so we can reference it in toolbar builder
        TabInfo tabInfo = new TabInfo(TabInfo.TabType.STATS, "Statistics", containerPanel, null, model);

        // Inherit series from active tab
        TabInfo activeTab = getActiveTab();
        if (activeTab != null && !activeTab.selectedSeries.isEmpty()) {
            tabInfo.selectedSeries.addAll(activeTab.selectedSeries);
        } else {
            tabInfo.selectedSeries.addAll(sharedDataSet.getSeriesNames());
        }

        tabs.add(tabInfo);

        // Populate with inherited series data
        rebuildStatsTab(tabInfo);

        JToolBar toolbar = createStatsToolbar(tabInfo, table);
        containerPanel.add(toolbar, BorderLayout.NORTH);
        containerPanel.add(scrollPane, BorderLayout.CENTER);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab("", containerPanel);
        setupTabIcon(index, TabInfo.TabType.STATS);

        return model;
    }

    /**
     * Adds a new statistics tab with settings copied from another tab.
     * The new stats tab will have the same settings and all series from the source tab.
     *
     * @param settings The settings to apply to the new stats tab
     * @return The created StatsTableModel
     */
    public StatsTableModel addStatsTabFromSettings(TabSettings settings) {
        // Create new stats table
        StatsTableModel model = new StatsTableModel();
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);

        // Apply custom renderer to color statistics columns
        applyStatsTableRenderer(table);

        // Make the Series column wider to accommodate longer series names
        if (table.getColumnCount() > 0) {
            table.getColumnModel().getColumn(0).setPreferredWidth(200);
        }

        JScrollPane scrollPane = new JScrollPane(table);

        // Create container panel with toolbar
        JPanel containerPanel = new JPanel(new BorderLayout());

        // Create tab info so we can reference it in toolbar builder
        TabInfo tabInfo = new TabInfo(TabInfo.TabType.STATS, "Statistics", containerPanel, null, model);

        // Apply aggregation settings from TabSettings
        tabInfo.statsPeriod = settings.aggregationPeriod;
        tabInfo.statsMethod = settings.aggregationMethod;

        // Use series from settings if provided, otherwise inherit from active tab
        if (settings.selectedSeries != null) {
            tabInfo.selectedSeries.addAll(settings.selectedSeries);
        } else {
            TabInfo activeTab = getActiveTab();
            if (activeTab != null && !activeTab.selectedSeries.isEmpty()) {
                tabInfo.selectedSeries.addAll(activeTab.selectedSeries);
            } else {
                tabInfo.selectedSeries.addAll(sharedDataSet.getSeriesNames());
            }
        }

        tabs.add(tabInfo);

        // Populate with inherited series data
        rebuildStatsTab(tabInfo);

        JToolBar toolbar = createStatsToolbar(tabInfo, table);
        containerPanel.add(toolbar, BorderLayout.NORTH);
        containerPanel.add(scrollPane, BorderLayout.CENTER);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab("", containerPanel);
        setupTabIcon(index, TabInfo.TabType.STATS);

        // Select the new tab
        tabbedPane.setSelectedIndex(index);

        return model;
    }

    /**
     * Sets up a tab with an icon and interaction handlers.
     */
    private void setupTabIcon(int index, TabInfo.TabType tabType) {
        // Create tab panel with just the icon
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,
            UIConstants.TAB_PANEL_PADDING, UIConstants.TAB_PANEL_PADDING));
        tabPanel.setOpaque(false);

        // Create icon based on tab type
        FontIcon tabIcon;
        String tooltip;
        if (tabType == TabInfo.TabType.PLOT) {
            tabIcon = FontIcon.of(FontAwesomeSolid.CHART_LINE, UIConstants.TAB_ICON_SIZE);
            tooltip = "Plot";
        } else {
            tabIcon = FontIcon.of(FontAwesomeSolid.CALCULATOR, UIConstants.TAB_ICON_SIZE);
            tooltip = "Statistics";
        }

        JLabel label = new JLabel(tabIcon);
        label.setToolTipText(tooltip);

        tabPanel.add(label);

        tabbedPane.setTabComponentAt(index, tabPanel);

        // Add drag-and-drop support for tab reordering
        setupTabDragAndDrop(tabPanel, label);

        // Add context menu support
        setupTabContextMenu(tabPanel, label, tabType);
    }

    /**
     * Sets up drag-and-drop handlers for tab reordering.
     */
    private void setupTabDragAndDrop(JPanel tabPanel, JLabel label) {
        MouseAdapter dragHandler = new MouseAdapter() {
            private boolean hasDragged = false;

            @Override
            public void mousePressed(MouseEvent e) {
                pressX = e.getX();
                pressY = e.getY();
                dragStartIndex = tabbedPane.indexOfTabComponent(tabPanel);
                hasDragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStartIndex == -1) return;

                // Check if we've moved beyond the drag threshold
                int deltaX = Math.abs(e.getX() - pressX);
                int deltaY = Math.abs(e.getY() - pressY);

                if (deltaX > DRAG_THRESHOLD || deltaY > DRAG_THRESHOLD) {
                    hasDragged = true;
                    if (draggingTab == null) {
                        draggingTab = tabPanel;
                        tabPanel.setOpaque(true);
                        tabPanel.setBackground(UIConstants.DRAG_HIGHLIGHT_COLOR);
                    }

                    // Determine which tab we're hovering over
                    Point screenPoint = e.getLocationOnScreen();
                    SwingUtilities.convertPointFromScreen(screenPoint, tabbedPane);

                    for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                        Rectangle tabBounds = tabbedPane.getBoundsAt(i);
                        if (tabBounds != null && tabBounds.contains(screenPoint)) {
                            if (i != dragStartIndex) {
                                // Reorder the tab
                                reorderTab(dragStartIndex, i);
                                dragStartIndex = i;
                            }
                            break;
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggingTab != null) {
                    draggingTab.setOpaque(false);
                    draggingTab.setBackground(null);
                    draggingTab = null;
                } else if (!hasDragged) {
                    // This was a click, not a drag - select the tab
                    int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
                    if (tabIndex != -1) {
                        tabbedPane.setSelectedIndex(tabIndex);
                    }
                }
                dragStartIndex = -1;
                hasDragged = false;
            }
        };

        // Add listeners to the label (main drag area) but not the close button
        label.addMouseListener(dragHandler);
        label.addMouseMotionListener(dragHandler);
    }

    /**
     * Sets up context menu for tab right-click.
     */
    private void setupTabContextMenu(JPanel tabPanel, JLabel label, TabInfo.TabType tabType) {
        JPopupMenu contextMenu = new JPopupMenu();

        // "Add Plot" menu item - always shown
        JMenuItem addPlotItem = new JMenuItem("Add Plot");
        addPlotItem.addActionListener(e -> {
            // Find the TabInfo for this tab
            int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
            if (tabIndex != -1 && tabIndex < tabs.size()) {
                TabInfo sourceTab = tabs.get(tabIndex);

                // Extract settings from source tab
                TabSettings settings;
                if (sourceTab.type == TabInfo.TabType.PLOT && sourceTab.plotPanel != null) {
                    settings = TabSettings.fromPlotTab(sourceTab.plotPanel, sourceTab);
                } else if (sourceTab.type == TabInfo.TabType.STATS) {
                    settings = TabSettings.fromStatsTab(sourceTab);
                } else {
                    settings = TabSettings.getDefaults();
                }

                // Create new plot tab with copied settings
                addPlotTabFromSettings(settings);
            } else {
                // Fallback: create with default settings
                addPlotTab();
            }
        });
        contextMenu.add(addPlotItem);

        // "Add Stats" menu item - always shown
        JMenuItem addStatsItem = new JMenuItem("Add Stats");
        addStatsItem.addActionListener(e -> {
            // Find the TabInfo for this tab
            int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
            if (tabIndex != -1 && tabIndex < tabs.size()) {
                TabInfo sourceTab = tabs.get(tabIndex);

                // Extract settings from source tab
                TabSettings settings;
                if (sourceTab.type == TabInfo.TabType.PLOT && sourceTab.plotPanel != null) {
                    settings = TabSettings.fromPlotTab(sourceTab.plotPanel, sourceTab);
                } else if (sourceTab.type == TabInfo.TabType.STATS) {
                    settings = TabSettings.fromStatsTab(sourceTab);
                } else {
                    settings = TabSettings.getDefaults();
                }

                // Create new stats tab with copied settings
                addStatsTabFromSettings(settings);
            } else {
                // Fallback: create with default settings
                addStatsTab();
            }
        });
        contextMenu.add(addStatsItem);

        // "Remove" menu item - only shown if there is more than one tab
        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> {
            int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
            if (tabIndex != -1) {
                closeTab(tabIndex);
            }
        });
        contextMenu.add(removeItem);

        // Add popup listener to show menu and update "Remove" visibility
        MouseAdapter popupListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            private void showContextMenu(MouseEvent e) {
                // Count total tabs
                int totalCount = tabs.size();

                // Only show remove if there is more than one tab
                removeItem.setVisible(totalCount > 1);

                contextMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        };

        label.addMouseListener(popupListener);
    }

    /**
     * Reorders a tab from one index to another.
     */
    private void reorderTab(int fromIndex, int toIndex) {
        if (fromIndex == toIndex || fromIndex < 0 || toIndex < 0) {
            return;
        }

        // Save the tab info
        TabInfo movedTab = tabs.get(fromIndex);
        Component component = tabbedPane.getComponentAt(fromIndex);
        Component tabComponent = tabbedPane.getTabComponentAt(fromIndex);
        String title = tabbedPane.getTitleAt(fromIndex);
        Icon icon = tabbedPane.getIconAt(fromIndex);

        // Remove from old position
        tabbedPane.removeTabAt(fromIndex);
        tabs.remove(fromIndex);

        // Insert at new position
        tabbedPane.insertTab(title, icon, component, null, toIndex);
        tabbedPane.setTabComponentAt(toIndex, tabComponent);
        tabs.add(toIndex, movedTab);

        // Select the moved tab
        tabbedPane.setSelectedIndex(toIndex);
    }

    /**
     * Closes a tab at the given index.
     */
    private void closeTab(int index) {
        // Safety check: don't close if it's the last tab
        if (tabbedPane.getTabCount() <= 1) {
            return;
        }

        // Remove tab
        tabs.remove(index);
        tabbedPane.removeTabAt(index);
    }

    /**
     * Updates all plot tabs using each tab's own per-tab selected series.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    public void updateAllTabs(boolean resetZoom) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
                tab.plotPanel.setSeriesColors(sharedColorMap);
                tab.plotPanel.setVisibleSeries(new ArrayList<>(tab.selectedSeries));
                tab.plotPanel.refreshData(resetZoom);
            }
            // Stats tabs update automatically through their DataSet listeners
        }
    }

    /**
     * Updates only the target plot tab (identified by PlotPanel reference).
     */
    public void updateTab(PlotPanel targetPanel, boolean resetZoom) {
        for (TabInfo tab : tabs) {
            if (tab.plotPanel == targetPanel) {
                tab.plotPanel.setSeriesColors(sharedColorMap);
                tab.plotPanel.setVisibleSeries(new ArrayList<>(tab.selectedSeries));
                tab.plotPanel.refreshData(resetZoom);
                return;
            }
        }
    }

    // === Active tab query methods ===

    /**
     * Returns the TabInfo for the currently selected tab, or null.
     */
    private TabInfo getActiveTab() {
        int index = tabbedPane.getSelectedIndex();
        if (index >= 0 && index < tabs.size()) {
            return tabs.get(index);
        }
        return null;
    }

    /**
     * Returns the active PLOT tab's TabInfo, or null if a STATS tab is active.
     */
    private TabInfo getActivePlotTab() {
        TabInfo active = getActiveTab();
        if (active != null && active.type == TabInfo.TabType.PLOT) {
            return active;
        }
        return null;
    }

    /**
     * Returns the last-active plot tab (fallback when a stats tab is focused).
     */
    private TabInfo getLastActivePlotTab() {
        if (lastActivePlotTabIndex >= 0 && lastActivePlotTabIndex < tabs.size()) {
            TabInfo tab = tabs.get(lastActivePlotTabIndex);
            if (tab.type == TabInfo.TabType.PLOT) return tab;
        }
        // Fallback: first plot tab
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.PLOT) return tab;
        }
        return null;
    }

    /**
     * Returns the target tab for selection changes: active tab (any type), or last-active plot tab as fallback.
     */
    private TabInfo getTargetTab() {
        TabInfo active = getActiveTab();
        if (active != null) return active;
        return getLastActivePlotTab();
    }

    /**
     * Returns the selected series for the target tab, or empty set.
     */
    public Set<String> getTargetTabSelectedSeries() {
        TabInfo tab = getTargetTab();
        return tab != null ? Collections.unmodifiableSet(tab.selectedSeries) : Collections.emptySet();
    }

    /**
     * Returns the PlotPanel for the target tab (null if it's a stats tab).
     */
    public PlotPanel getTargetPlotPanel() {
        TabInfo tab = getTargetTab();
        return tab != null ? tab.plotPanel : null;
    }

    /**
     * Sets the selected series on the target tab. Updates visuals accordingly.
     */
    public void setTargetTabSelectedSeries(Set<String> series) {
        TabInfo tab = getTargetTab();
        if (tab == null) return;

        tab.selectedSeries.clear();
        tab.selectedSeries.addAll(series);

        if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
            // Rebuild legend
            tab.plotPanel.clearLegend();
            for (String seriesName : series) {
                Color color = sharedColorMap.get(seriesName);
                if (color != null) {
                    tab.plotPanel.addLegendSeries(seriesName, color);
                }
            }

            // Update visible series and refresh
            tab.plotPanel.setSeriesColors(sharedColorMap);
            tab.plotPanel.setVisibleSeries(new ArrayList<>(series));
            tab.plotPanel.refreshData(false);
        } else if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
            // Rebuild stats table to show only selected series
            rebuildStatsTab(tab);
        }
    }

    /**
     * Rebuilds a stats tab to show only its selected series.
     */
    private void rebuildStatsTab(TabInfo tab) {
        tab.statsModel.clear();
        for (String seriesName : tab.selectedSeries) {
            TimeSeriesData data = sharedDataSet.getSeries(seriesName);
            if (data != null) {
                TimeSeriesData aggregatedData = com.kalix.ide.flowviz.transform.TimeSeriesAggregator.aggregate(
                    data, tab.statsPeriod, tab.statsMethod);
                if (aggregatedData != null) {
                    tab.statsModel.addOrUpdateSeries(aggregatedData);
                }
            }
        }
    }

    /**
     * Returns the union of selected series across all tabs.
     */
    public Set<String> getAllSelectedSeriesAcrossTabs() {
        Set<String> all = new HashSet<>();
        for (TabInfo tab : tabs) {
            all.addAll(tab.selectedSeries);
        }
        return all;
    }

    /**
     * Clears selected series on all tabs.
     */
    public void clearAllTabSeries() {
        for (TabInfo tab : tabs) {
            tab.selectedSeries.clear();
            if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
                tab.plotPanel.clearLegend();
            } else if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
                tab.statsModel.clear();
            }
        }
    }

    /**
     * Syncs TabInfo.selectedSeries and tree after an undo/redo changes visible series.
     */
    private void syncTabSelectionFromPlotState(PlotPanel panel, com.kalix.ide.flowviz.PlotState state) {
        for (TabInfo tab : tabs) {
            if (tab.plotPanel == panel) {
                tab.selectedSeries.clear();
                tab.selectedSeries.addAll(state.getVisibleSeries());

                // Rebuild legend to match
                panel.clearLegend();
                for (String seriesName : state.getVisibleSeries()) {
                    Color color = sharedColorMap.get(seriesName);
                    if (color != null) {
                        panel.addLegendSeries(seriesName, color);
                    }
                }
                break;
            }
        }

        // Trigger tree sync
        if (onTabChangedCallback != null) {
            onTabChangedCallback.run();
        }
    }

    /**
     * Checks whether any tab has the given series selected.
     */
    public boolean isSeriesSelectedOnAnyTab(String seriesKey) {
        for (TabInfo tab : tabs) {
            if (tab.selectedSeries.contains(seriesKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the JTabbedPane component.
     */
    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    /**
     * Gets all plot panels from plot tabs.
     */
    public List<PlotPanel> getAllPlotPanels() {
        List<PlotPanel> plotPanels = new ArrayList<>();
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
                plotPanels.add(tab.plotPanel);
            }
        }
        return plotPanels;
    }

    /**
     * Gets all stats models from stats tabs.
     */
    public List<StatsTableModel> getAllStatsModels() {
        List<StatsTableModel> models = new ArrayList<>();
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
                models.add(tab.statsModel);
            }
        }
        return models;
    }

    /**
     * Updates a series in all stats tabs, applying aggregation settings.
     * This should be called instead of directly calling model.addOrUpdateSeries().
     *
     * @param seriesName The name of the series
     * @param data The original (unaggregated) time series data
     */
    public void updateSeriesInStatsTabsWithAggregation(String seriesName, TimeSeriesData data) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null
                    && tab.selectedSeries.contains(seriesName)) {
                TimeSeriesData aggregatedData = com.kalix.ide.flowviz.transform.TimeSeriesAggregator.aggregate(
                    data, tab.statsPeriod, tab.statsMethod);

                if (aggregatedData != null) {
                    tab.statsModel.addOrUpdateSeries(aggregatedData);
                }
            }
        }
    }

    /**
     * Adds a loading series entry to all stats tabs.
     *
     * @param seriesName The name of the series being loaded
     */
    public void addLoadingSeriesInStatsTabs(String seriesName) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null
                    && tab.selectedSeries.contains(seriesName)) {
                tab.statsModel.addLoadingSeries(seriesName);
            }
        }
    }

    /**
     * Adds an error series entry to all stats tabs.
     *
     * @param seriesName The name of the series
     * @param errorMessage The error message
     */
    public void addErrorSeriesInStatsTabs(String seriesName, String errorMessage) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null
                    && tab.selectedSeries.contains(seriesName)) {
                tab.statsModel.addErrorSeries(seriesName, errorMessage);
            }
        }
    }

    /**
     * Removes a series from all stats tabs.
     *
     * @param seriesName The name of the series to remove
     */
    public void removeSeriesFromStatsTabs(String seriesName) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
                tab.selectedSeries.remove(seriesName);
                tab.statsModel.removeSeries(seriesName);
            }
        }
    }
}
