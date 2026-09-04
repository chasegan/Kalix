package com.kalix.ide.windows;

import com.kalix.ide.components.TabDragReorderer;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.SeriesStyleResolver;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.preferences.PreferenceKeys;

import com.formdev.flatlaf.FlatClientProperties;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Manages visualization tabs (right side of RunManager) - plots and statistics.
 *
 * <h2>Data Architecture</h2>
 * All tabs share a {@link DataSet} ({@code sharedDataSet}) as a data pool and a color map.
 * The pool accumulates all fetched time series and does not shrink on deselect.
 * Each tab maintains its own {@code selectedSeries} set (in {@code TabInfo}) controlling
 * which series from the pool it displays. Plot tabs render only their selected series;
 * stats tabs compute statistics only for theirs.
 *
 * <h2>Tab Types</h2>
 * <ul>
 *   <li><b>PLOT</b> - Time series chart with per-tab aggregation, transforms, masking,
 *       undo/redo history, and LOD rendering</li>
 *   <li><b>STATS</b> - Statistics table showing min/max/mean/etc. for the tab's selected series</li>
 * </ul>
 *
 * <h2>New Tab Behavior</h2>
 * New tabs inherit the active tab's series selection and settings. Plot tabs created
 * from another plot tab also inherit the full undo/redo state history.
 *
 * <h2>Tab Change Sync</h2>
 * When the user switches tabs, a callback notifies RunManager to restore the new tab's
 * full context: its checked data sources (each tab remembers the source-tree context it
 * was built in, as stable {@link SourceRef}s) and then its series checks.
 *
 * @see PlotPanel
 * @see com.kalix.ide.windows.RunManager#addSeriesToPool
 */
public class VisualizationTabManager {

    private final JTabbedPane tabbedPane;
    private final DataSet sharedDataSet;           // Single source of truth for all tabs
    private final SeriesStyleResolver styleResolver;  // Shared resolver — consistent styling across all tabs
    private LabelResolver labelResolver;  // injected by owner; passed down to PlotPanels
    private java.util.function.Supplier<File> baseDirectorySupplier;  // seeds plot Save dialog's start folder
    private final List<TabInfo> tabs;

    // Tab change tracking
    private int lastActivePlotTabIndex = 0;
    private Runnable onTabChangedCallback;

    // Ghost-style drag-to-reorder, shared across all tabs (custom tab components, so it attaches
    // to each tab's handle rather than the strip). Initialized once tabbedPane exists.
    private final TabDragReorderer tabReorderer;

    /**
     * UI constants for consistent styling and sizing. Toolbar sizing lives with
     * {@link PlotToolbarBuilder}; these cover the tab strip itself.
     */
    private static class UIConstants {
        static final int TAB_ICON_SIZE = 14;
        static final int TAB_PANEL_PADDING = 2;
        // Gap between the tab icon and its name label — collapsed to 0 when the name is
        // empty so an unnamed tab doesn't show a dangling space after the icon.
        static final int TAB_NAME_LABEL_GAP = 4;
    }

    /**
     * Pads {@code nameLabel} on its leading edge so it sits clear of the tab icon, unless
     * the name is empty — an unnamed tab then shows just the icon, with no trailing gap.
     */
    private static void updateNameLabelPadding(JLabel nameLabel) {
        boolean hasName = nameLabel.getText() != null && !nameLabel.getText().isEmpty();
        nameLabel.setBorder(BorderFactory.createEmptyBorder(0, hasName ? UIConstants.TAB_NAME_LABEL_GAP : 0, 0, 0));
    }

    /**
     * Settings that can be copied between tabs.
     * Makes it explicit what settings are shareable and provides a clean interface for copying.
     */
    public static class TabSettings {
        // Tab name copied from the source tab when duplicating (null = leave the new tab unnamed)
        public String name = null;

        // Aggregation settings (common to both plot and stats tabs)
        public AggregationPeriod aggregationPeriod = AggregationPeriod.ORIGINAL;
        public AggregationMethod aggregationMethod = AggregationMethod.SUM;

        // Plot-specific settings (ignored when creating stats tabs)
        public com.kalix.ide.flowviz.transform.PlotType plotType = com.kalix.ide.flowviz.transform.PlotType.VALUES;
        public YAxisScale yAxisScale = YAxisScale.LINEAR;
        public boolean autoYMode = true;
        public boolean showCoordinates = false;
        public boolean legendCollapsed = false;
        public boolean legendEnabled = true;
        // Missing-data handling (context menu > Missing data). Mutually exclusive; both
        // false = default "break at gaps".
        public boolean connectAcrossGaps = false;
        public boolean showOrphanMarkers = false;

        // Series selection from source tab (null = inherit from active tab)
        public Set<SeriesRef> selectedSeries = null;

        // Checked data sources from source tab (null = inherit from active tab)
        public Set<SourceRef> checkedSources = null;

        // Source plot panel for history duplication (null = no history to copy)
        public PlotPanel sourcePlotPanel = null;

        /**
         * Extract settings from a plot tab.
         */
        public static TabSettings fromPlotTab(TabInfo tabInfo) {
            PlotPanel plotPanel = tabInfo.plotPanel;
            TabSettings settings = new TabSettings();
            settings.name = tabInfo.name;
            settings.aggregationPeriod = plotPanel.getAggregationPeriod();
            settings.aggregationMethod = plotPanel.getAggregationMethod();
            settings.plotType = plotPanel.getPlotType();
            settings.yAxisScale = plotPanel.getYAxisScale();
            settings.autoYMode = plotPanel.isAutoYMode();
            settings.showCoordinates = plotPanel.isShowCoordinates();
            settings.legendCollapsed = plotPanel.isLegendCollapsed();
            settings.legendEnabled = plotPanel.isLegendEnabled();
            settings.connectAcrossGaps = plotPanel.isConnectAcrossGaps();
            settings.showOrphanMarkers = plotPanel.isShowOrphanMarkers();
            settings.selectedSeries = new LinkedHashSet<>(tabInfo.selectedSeries);
            settings.checkedSources = new LinkedHashSet<>(tabInfo.checkedSources);
            settings.sourcePlotPanel = plotPanel;
            return settings;
        }

        /**
         * Extract settings from a stats tab.
         */
        public static TabSettings fromStatsTab(TabInfo statsTabInfo) {
            TabSettings settings = new TabSettings();
            settings.name = statsTabInfo.name;
            settings.aggregationPeriod = statsTabInfo.statsPeriod;
            settings.aggregationMethod = statsTabInfo.statsMethod;
            settings.selectedSeries = new LinkedHashSet<>(statsTabInfo.selectedSeries);
            settings.checkedSources = new LinkedHashSet<>(statsTabInfo.checkedSources);
            return settings;
        }

        /**
         * Get default settings from preferences.
         */
        public static TabSettings getDefaults() {
            TabSettings settings = new TabSettings();
            settings.showCoordinates = PreferenceKeys.FLOWVIZ_SHOW_COORDINATES.get();
            settings.autoYMode = PreferenceKeys.FLOWVIZ_AUTO_Y_MODE.get();
            settings.legendCollapsed = PreferenceKeys.PLOT_LEGEND_COLLAPSED.get();
            settings.legendEnabled = PreferenceKeys.PLOT_LEGEND_ENABLED.get();
            return settings;
        }
    }

    /**
     * Represents a visualization tab with its type and components. Package-private:
     * {@link StatsToolbarBuilder} reads/writes the stats aggregation fields.
     */
    static class TabInfo {
        enum TabType { PLOT, STATS }

        final TabType type;
        final JComponent component;
        final PlotPanel plotPanel; // null for stats tabs
        final StatsTableModel statsModel; // null for plot tabs

        // Per-tab selected series (plot tabs only). Preserves insertion order for legend consistency.
        final Set<SeriesRef> selectedSeries = new LinkedHashSet<>();

        // Per-tab checked data sources — the source-tree context this tab was built in.
        // Restored verbatim (with the series checks) when the tab becomes active, so a
        // tab is a complete view: sources + series + plot settings. Empty restores empty.
        final Set<SourceRef> checkedSources = new LinkedHashSet<>();

        // Aggregation settings for stats tabs
        AggregationPeriod statsPeriod = AggregationPeriod.ORIGINAL;
        AggregationMethod statsMethod = AggregationMethod.SUM;

        // Tab user-supplied identifier
        String name;
        // remember the label for renaming
        JLabel nameLabel;

        TabInfo(TabType type, String name, JComponent component, PlotPanel plotPanel, StatsTableModel statsModel) {
            this.type = type;
            this.name = name;
            this.component = component;
            this.plotPanel = plotPanel;
            this.statsModel = statsModel;
        }

        void rename(String name) {
            this.name = name;
            this.nameLabel.setText(name);
            updateNameLabelPadding(this.nameLabel);
        }

        void registerNameLabel(JLabel nameLabel) {
            this.nameLabel = nameLabel;
        }
    }

    /**
     * Creates a new tab manager for visualization tabs.
     *
     * @param sharedDataSet The dataset shared across all tabs
     * @param styleResolver The series-style resolver shared across all tabs
     */
    public VisualizationTabManager(DataSet sharedDataSet, SeriesStyleResolver styleResolver) {
        this.sharedDataSet = sharedDataSet;
        this.styleResolver = styleResolver;
        this.tabs = new ArrayList<>();

        // Create tabbed pane with close buttons
        this.tabbedPane = new JTabbedPane();
        this.tabbedPane.setTabPlacement(JTabbedPane.TOP);
        this.tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        this.tabReorderer = new TabDragReorderer(tabbedPane, this::reorderTab);
        tabbedPane.putClientProperty("JTabbedPane.tabClosable", Boolean.TRUE);
        tabbedPane.putClientProperty(
            "JTabbedPane.tabCloseCallback",
            (java.util.function.IntConsumer) this::closeTab
        );

        // "New plot" affordance, pinned to the trailing edge of the tab strip rather
        // than added as a real tab: `tabs` is index-aligned with the tabbedPane, and a
        // placeholder tab would have to be excluded from insertion, reordering, closing
        // and selection. A trailing component sits outside that arithmetic entirely.
        this.tabbedPane.putClientProperty(
            FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, createNewPlotTrailing());

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
     * Sets the {@link LabelResolver} used by child PlotPanels (and indirectly by their
     * legend / hover overlays) to project {@link SeriesRef}s to display strings. Called
     * by the owning RunManager during initialization.
     */
    public void setLabelResolver(LabelResolver resolver) {
        this.labelResolver = resolver;
        for (TabInfo tab : tabs) {
            if (tab.plotPanel != null) {
                tab.plotPanel.setLabelResolver(resolver);
            }
            if (tab.statsModel != null) {
                tab.statsModel.setLabelResolver(resolver);
            }
        }
    }

    /**
     * Sets the base directory supplier used to seed each plot tab's "Save Data" file
     * dialog start folder (the model's directory, or {@code null} if no file is loaded).
     * Applied to existing plot tabs and to any created afterwards. Call before the first
     * tab is added so the default plot tab picks it up.
     */
    public void setBaseDirectorySupplier(java.util.function.Supplier<File> supplier) {
        this.baseDirectorySupplier = supplier;
        for (TabInfo tab : tabs) {
            if (tab.plotPanel != null) {
                tab.plotPanel.setBaseDirectorySupplier(supplier);
            }
        }
    }

    /**
     * Builds the "+" affordance pinned to the trailing edge of the tab strip.
     *
     * <p>FlatLaf stretches the trailing component across the whole leftover strip, so the
     * button cannot be handed over bare: it would be sized to that entire width, painting
     * its glyph in the middle of empty space and — worse — making every blank pixel of the
     * strip a live "new plot tab" click target. The holder panel absorbs the stretch and
     * lets the button keep its preferred size at the trailing edge.</p>
     */
    private JComponent createNewPlotTrailing() {
        // No explicit icon colour, matching the PLOT/STATS tab icons: an uncoloured
        // FontIcon leaves the Graphics colour alone and so paints in the component's
        // foreground, which follows the theme and survives updateComponentTreeUI.
        // Setting one here would bake a colour that goes stale on the next theme switch.
        FontIcon icon = FontIcon.of(FontAwesomeSolid.PLUS, UIConstants.TAB_ICON_SIZE);

        JButton button = new JButton(icon);
        button.setToolTipText("New plot tab");
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE,
            FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        // Not a focus stop: the tab strip is navigated with the tabs themselves.
        button.setFocusable(false);
        button.getAccessibleContext().setAccessibleName("New plot tab");
        button.addActionListener(e -> addEmptyPlotTab());

        // GridBagLayout with an EAST anchor: trailing edge horizontally, centred
        // vertically, button left at its preferred size. FlowLayout is wrong here —
        // it stacks its row from the top of the container rather than centring it,
        // which pinned the glyph high against the top of the strip.
        JPanel holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE;
        holder.add(button, gbc);
        return holder;
    }

    /**
     * Adds a new plot tab with default settings from preferences.
     *
     * @return The created PlotPanel
     */
    public PlotPanel addPlotTab() {
        return addPlotTabFromSettings(TabSettings.getDefaults());
    }

    /**
     * Adds a plot tab with <em>no series selected</em> — the blank-slate counterpart to
     * duplicating a tab and clearing its selection by hand.
     *
     * <p>The checked <em>sources</em> are deliberately inherited from the active tab
     * (left null, so {@link #inheritedSources} resolves them) while the series selection
     * is set to an explicit empty set. Starting with no sources would empty the outputs
     * tree too, forcing the source to be re-checked before any series could be picked —
     * more work than the gesture this replaces, not less.</p>
     *
     * <p>The empty-but-non-null selection also marks this as a deliberate choice rather
     * than "inherit", which is what makes {@link #addPlotTabFromSettings} focus the new
     * tab on creation.</p>
     *
     * @return The created PlotPanel
     */
    public PlotPanel addEmptyPlotTab() {
        TabSettings settings = TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        return addPlotTabFromSettings(settings);
    }

    /**
     * Adds the <em>default</em> tab: a plot tab with the "Last run" source checked and
     * nothing else — no other source, no series selected, settings straight from
     * preferences.
     *
     * <p>This is what the visualization strip starts life as, and what it falls back to
     * when the final tab is closed (see {@link #closeTab}), so the two paths can never
     * drift apart. "Last run" is checked because it is the source a modeller almost
     * always wants next; it is recorded as a {@link LastSource} ref rather than a tree
     * path, so the tab picks the node up whenever it appears — including the first run
     * of the session, which happens long after this tab is built.</p>
     *
     * @return The created PlotPanel
     */
    public PlotPanel addDefaultPlotTab() {
        TabSettings settings = TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();
        settings.checkedSources.add(new LastSource());
        return addPlotTabFromSettings(settings);
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
        plotPanel.setStyleResolver(styleResolver);
        if (labelResolver != null) {
            plotPanel.setLabelResolver(labelResolver);
        }
        if (baseDirectorySupplier != null) {
            plotPanel.setBaseDirectorySupplier(baseDirectorySupplier);
        }

        // Use series from settings if provided, otherwise inherit from active tab
        Set<SeriesRef> inheritedSeries;
        if (settings.selectedSeries != null) {
            inheritedSeries = new LinkedHashSet<>(settings.selectedSeries);
        } else {
            TabInfo activeTab = getActivePlotTab();
            inheritedSeries = activeTab != null
                ? new LinkedHashSet<>(activeTab.selectedSeries)
                : new LinkedHashSet<>(sharedDataSet.getSeriesRefs());
        }
        plotPanel.setVisibleSeries(new ArrayList<>(inheritedSeries));

        // Apply all settings from TabSettings (must be done AFTER setVisibleSeries)
        plotPanel.setAggregation(settings.aggregationPeriod, settings.aggregationMethod);
        plotPanel.setPlotType(settings.plotType);
        plotPanel.setYAxisScale(settings.yAxisScale);
        plotPanel.setAutoYMode(settings.autoYMode);
        plotPanel.setShowCoordinates(settings.showCoordinates);
        // Order matters: setConnectAcrossGaps(true) clears orphan markers and vice versa,
        // so apply connect first — for every valid (mutually exclusive) combination the
        // net result matches the source tab.
        plotPanel.setConnectAcrossGaps(settings.connectAcrossGaps);
        plotPanel.setShowOrphanMarkers(settings.showOrphanMarkers);
        // Unconditional: the new legend manager starts from the global preference, which
        // may disagree with this tab's settings in either direction.
        plotPanel.setLegendCollapsed(settings.legendCollapsed);
        plotPanel.setLegendEnabled(settings.legendEnabled);

        // Populate legend with inherited series (colour resolved at render time)
        for (SeriesRef ref : inheritedSeries) {
            plotPanel.addLegendSeries(ref);
        }

        // Copy history from source plot tab (Chrome-style duplicate), or push initial state.
        // Done BEFORE the toolbar is built: copyHistoryFrom restores the source's current
        // state (mask mode included, which TabSettings doesn't carry), and the toolbar
        // controls initialise by reading the panel — building first left them showing
        // defaults that disagreed with the restored plot.
        if (settings.sourcePlotPanel != null) {
            plotPanel.copyHistoryFrom(settings.sourcePlotPanel);
        } else {
            plotPanel.pushState();
        }

        // Create container panel with toolbar
        JPanel containerPanel = new JPanel(new BorderLayout());
        JToolBar toolbar = createPlotToolbar(plotPanel, settings.autoYMode, settings.showCoordinates);
        containerPanel.add(toolbar, BorderLayout.NORTH);
        containerPanel.add(plotPanel, BorderLayout.CENTER);

        // Add tab with inherited series selection and source context
        TabInfo tabInfo = new TabInfo(
            TabInfo.TabType.PLOT, settings.name != null ? settings.name : "", containerPanel, plotPanel, null);
        tabInfo.selectedSeries.addAll(inheritedSeries);
        tabInfo.checkedSources.addAll(inheritedSources(settings));
        tabs.add(tabInfo);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab(tabInfo.name, containerPanel);
        setupTabIcon(index, tabInfo);

        // Select the new tab (only when duplicating, not for initial default tabs)
        if (settings.selectedSeries != null) {
            tabbedPane.setSelectedIndex(index);
        }

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
            .addPaletteButton()
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
            .addLegendToggle(plotPanel.isLegendEnabled());
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
     * Applies a custom cell renderer to the stats table that colors statistic values.
     * The index (column 0) and Series (column 1) columns remain default, while statistic
     * columns use a muted theme color.
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
                        if (column <= 1) {
                            // Index and Series columns - use default foreground color
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
        return addStatsTabFromSettings(TabSettings.getDefaults());
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
        if (labelResolver != null) {
            model.setLabelResolver(labelResolver);
        }
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);

        // Apply custom renderer to color statistics columns
        applyStatsTableRenderer(table);

        // Narrow index column (0); wider Series column (1) for longer series names.
        if (table.getColumnCount() > 1) {
            table.getColumnModel().getColumn(0).setMaxWidth(48);
            table.getColumnModel().getColumn(0).setPreferredWidth(40);
            table.getColumnModel().getColumn(1).setPreferredWidth(200);
        }

        JScrollPane scrollPane = new JScrollPane(table);

        // Create container panel with toolbar
        JPanel containerPanel = new JPanel(new BorderLayout());

        // Create tab info so we can reference it in toolbar builder
        TabInfo tabInfo = new TabInfo(
            TabInfo.TabType.STATS, settings.name != null ? settings.name : "", containerPanel, null, model);

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
                tabInfo.selectedSeries.addAll(sharedDataSet.getSeriesRefs());
            }
        }
        tabInfo.checkedSources.addAll(inheritedSources(settings));

        tabs.add(tabInfo);

        // Populate with inherited series data
        rebuildStatsTab(tabInfo);

        JToolBar toolbar = createStatsToolbar(tabInfo, table);
        containerPanel.add(toolbar, BorderLayout.NORTH);
        containerPanel.add(scrollPane, BorderLayout.CENTER);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab(tabInfo.name, containerPanel);
        setupTabIcon(index, tabInfo);

        // Select the new tab (only when duplicating, not for initial default tabs)
        if (settings.selectedSeries != null) {
            tabbedPane.setSelectedIndex(index);
        }

        return model;
    }

    /**
     * Sets up a tab with an icon and interaction handlers.
     */
    private void setupTabIcon(int index, TabInfo tabInfo) {
        TabInfo.TabType tabType = tabInfo.type;

        // Create tab panel with the icon and name label
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,
            UIConstants.TAB_PANEL_PADDING, UIConstants.TAB_PANEL_PADDING));
        tabPanel.setOpaque(false);
        JPanel labelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, UIConstants.TAB_PANEL_PADDING, 0));
        labelPanel.setOpaque(false);

        // Create icon based on tab type
        FontIcon tabIcon;
        if (tabType == TabInfo.TabType.PLOT) {
            tabIcon = FontIcon.of(FontAwesomeSolid.CHART_LINE, UIConstants.TAB_ICON_SIZE);
        } else {
            tabIcon = FontIcon.of(FontAwesomeSolid.CALCULATOR, UIConstants.TAB_ICON_SIZE);
        }

        JLabel nameLabel = new JLabel(tabInfo.name);
        updateNameLabelPadding(nameLabel);
        JLabel iconLabel = new JLabel(tabIcon);

        labelPanel.add(iconLabel);
        labelPanel.add(nameLabel);

        tabPanel.add(labelPanel);

        tabbedPane.setTabComponentAt(index, tabPanel);

        // Add drag-and-drop support for tab reordering
        setupTabDragAndDrop(iconLabel, nameLabel, labelPanel);

        // Add context menu support
        setupTabContextMenu(tabPanel, tabType, iconLabel, nameLabel, labelPanel);
        setupTabDoubleClickRename(tabPanel, iconLabel, nameLabel, labelPanel);
        tabInfo.registerNameLabel(nameLabel);
    }

    /**
     * Enables ghost-style drag-to-reorder for this tab. The handles are the drag targets (custom
     * tab components swallow the strip's mouse events); the shared reorderer paints the
     * dragged-tab ghost and the theme-coloured insertion line, commits the move on drop via
     * {@link #reorderTab}, and selects the tab on a plain click.
     */
    private void setupTabDragAndDrop(Component... handles) {
        for (Component handle : handles) {
            tabReorderer.attachToHandle(handle);
        }
    }

    /**
     * The settings of the tab behind {@code tabPanel}, for seeding a new tab from it.
     * Falls back to the defaults if the tab can no longer be found, which is what a
     * plain "add tab" starts from too.
     */
    private TabSettings settingsOfTab(JPanel tabPanel) {
        int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
        if (tabIndex == -1 || tabIndex >= tabs.size()) {
            return TabSettings.getDefaults();
        }
        TabInfo sourceTab = tabs.get(tabIndex);
        if (sourceTab.type == TabInfo.TabType.PLOT && sourceTab.plotPanel != null) {
            return TabSettings.fromPlotTab(sourceTab);
        }
        if (sourceTab.type == TabInfo.TabType.STATS) {
            return TabSettings.fromStatsTab(sourceTab);
        }
        return TabSettings.getDefaults();
    }

    /**
     * Sets up context menu for tab right-click. Built once and shared across every
     * {@code labelComponents} entry (icon, name, wrapper panel) since mouse events dispatch to
     * whichever of them is deepest under the cursor.
     */
    private void setupTabContextMenu(JPanel tabPanel, TabInfo.TabType tabType, Component... labelComponents) {
        JPopupMenu contextMenu = new JPopupMenu();
        boolean isPlot = tabType == TabInfo.TabType.PLOT;

        // Primary block (context-menu-style §1 ①): the default action, which on a tab is
        // what double-click does (setupTabDoubleClickRename). Ellipsis: opens a dialog (§2.4).
        JMenuItem renameItem = new JMenuItem("Rename…");
        renameItem.addActionListener(e -> triggerTabRename(tabPanel));
        contextMenu.add(renameItem);

        contextMenu.addSeparator();

        // Create block (§1 ④), in the sanctioned verbless "New …" form (§2.2). The new tab
        // is of the other type and starts from this tab's settings, as Duplicate does.
        JMenuItem newOtherTypeItem = new JMenuItem(isPlot ? "New stats tab" : "New plot tab");
        newOtherTypeItem.addActionListener(e -> {
            TabSettings settings = settingsOfTab(tabPanel);
            if (isPlot) {
                addStatsTabFromSettings(settings);
            } else {
                addPlotTabFromSettings(settings);
            }
        });
        contextMenu.add(newOtherTypeItem);

        contextMenu.addSeparator();

        // Modify block (§1 ⑤). "Duplicate" names no type: the tab is the context (§2.3).
        // Reset changes the tab's state rather than its existence, so it is a modify
        // action, not a destructive one, even though it is not undoable; the destructive
        // block that §1 ⑥ and §8 isolate is Remove alone.
        JMenuItem duplicateItem = new JMenuItem("Duplicate");
        duplicateItem.addActionListener(e -> {
            TabSettings settings = settingsOfTab(tabPanel);
            if (isPlot) {
                addPlotTabFromSettings(settings);
            } else {
                addStatsTabFromSettings(settings);
            }
        });
        contextMenu.add(duplicateItem);

        JMenuItem resetItem = new JMenuItem("Reset");
        resetItem.addActionListener(e -> {
            int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
            if (tabIndex != -1) {
                resetTabAt(tabIndex);
            }
        });
        contextMenu.add(resetItem);

        contextMenu.addSeparator();

        // Destructive block (§1 ⑥), alone. "Remove", not "Delete": a tab is a view onto
        // data that stays put (§2.5).
        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> {
            int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
            if (tabIndex != -1) {
                closeTab(tabIndex);
            }
        });
        contextMenu.add(removeItem);

        // Add popup listener to show the menu. "Remove" needs no guard: closing the
        // final tab repopulates the strip with a default tab rather than emptying it.
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
                contextMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        };

        for (Component labelComponent : labelComponents) {
            labelComponent.addMouseListener(popupListener);
        }
    }

    /**
     * Sets up double-click-to-rename on a tab. Mirrors {@link #setupTabContextMenu}: the
     * listener is attached to every {@code labelComponents} entry (icon, name, wrapper
     * panel) since mouse events dispatch to whichever of them is deepest under the cursor.
     */
    private void setupTabDoubleClickRename(JPanel tabPanel, Component... labelComponents) {
        MouseAdapter doubleClickListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    triggerTabRename(tabPanel);
                }
            }
        };
        for (Component labelComponent : labelComponents) {
            labelComponent.addMouseListener(doubleClickListener);
        }
    }

    /**
     * Prompts for a new name for the tab whose handle is {@code tabPanel}, from the context
     * menu or a double-click on the handle.
     *
     * <p>Cancelling leaves the name alone; submitting an empty box <em>clears</em> it. Unnamed
     * is a legitimate state — it is what every new tab starts as, and {@link #setupTabIcon}
     * and {@link #updateNameLabelPadding} render it as an icon-only handle — so clearing has
     * to be reachable, or a tab named once could never be un-named.</p>
     */
    private void triggerTabRename(JPanel tabPanel) {
        int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
        if (tabIndex == -1 || tabIndex >= tabs.size()) {
            return;
        }
        TabInfo sourceTab = tabs.get(tabIndex);
        String currentName = sourceTab.name;
        String prompt = currentName == null || currentName.isEmpty()
            ? "Enter a name for this tab:"
            : "Enter new name for tab '" + currentName + "':";

        String newName = (String) JOptionPane.showInputDialog(
            tabbedPane,
            prompt,
            "Rename tab",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );
        if (newName == null) {
            return;
        }

        String trimmed = newName.trim();
        sourceTab.rename(trimmed);
        tabbedPane.setTitleAt(tabIndex, trimmed);
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
        detachTabCallbacks(tabs.get(index));
        tabs.remove(index);
        tabbedPane.removeTabAt(index);

        // The strip is never left empty: closing the final tab lands the user back on a
        // brand-new default tab rather than on a blank panel with no way forward.
        if (tabs.isEmpty()) {
            addDefaultPlotTab();
        }
    }

    /**
     * Detaches the toolbar callbacks a plot tab's toolbar installed on its panel. This
     * is the owner's side of the contract with {@code PlotPanel.removeNotify()}, which
     * leaves those callbacks alone so a re-parented tab keeps working; they are released
     * here, when the tab is genuinely discarded (close, reset).
     */
    private void detachTabCallbacks(TabInfo tab) {
        if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
            tab.plotPanel.setOnHistoryChanged(null);
            tab.plotPanel.setOnAutoYModeChanged(null);
            tab.plotPanel.getLegendManager().setOnEnabledChanged(null);
        }
    }

    /**
     * Resets the tab at {@code index} to a brand-new tab of the same type: default
     * settings from preferences, no series selected, no checked sources — dropping all
     * data selection and plot/stats configuration. Implemented as remove-then-recreate
     * rather than clearing the existing tab's fields in place, so "reset" can never drift
     * from what a genuinely new tab looks like.
     */
    private void resetTabAt(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        TabInfo oldTab = tabs.get(index);
        TabInfo.TabType type = oldTab.type;

        detachTabCallbacks(oldTab);
        tabs.remove(index);
        tabbedPane.removeTabAt(index);

        // Brand-new, unnamed tab with nothing selected and nothing inherited — always
        // appended at the end by addPlotTabFromSettings/addStatsTabFromSettings, then
        // moved back into the slot the old tab occupied so tab order is undisturbed.
        TabSettings settings = TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>();

        if (type == TabInfo.TabType.PLOT) {
            addPlotTabFromSettings(settings);
        } else {
            addStatsTabFromSettings(settings);
        }

        int newIndex = tabbedPane.getTabCount() - 1;
        if (newIndex != index) {
            reorderTab(newIndex, index);
        }
    }

    /**
     * Updates all plot tabs using each tab's own per-tab selected series.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    public void updateAllTabs(boolean resetZoom) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
                tab.plotPanel.setStyleResolver(styleResolver);
                tab.plotPanel.setVisibleSeries(new ArrayList<>(tab.selectedSeries));
                tab.plotPanel.refreshData(resetZoom);
            }
            // Stats tabs are intentionally not touched here — they are refreshed via
            // explicit calls (updateSeriesInStatsTabsWithAggregation, rebuildStatsTab),
            // not through DataSet listeners.
        }
    }

    /**
     * Updates only the target plot tab (identified by PlotPanel reference).
     */
    public void updateTab(PlotPanel targetPanel, boolean resetZoom) {
        for (TabInfo tab : tabs) {
            if (tab.plotPanel == targetPanel) {
                tab.plotPanel.setStyleResolver(styleResolver);
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
    public Set<SeriesRef> getTargetTabSelectedSeries() {
        TabInfo tab = getTargetTab();
        return tab != null ? Collections.unmodifiableSet(tab.selectedSeries) : Collections.emptySet();
    }

    /**
     * Resolves the source context a new tab should start with: from the settings if
     * recorded there (duplication), otherwise the active tab's (fresh tab created while
     * another is showing), otherwise empty (startup default tabs).
     */
    private Set<SourceRef> inheritedSources(TabSettings settings) {
        if (settings.checkedSources != null) {
            return settings.checkedSources;
        }
        TabInfo activeTab = getActiveTab();
        return activeTab != null ? activeTab.checkedSources : Collections.emptySet();
    }

    /**
     * Returns the checked data sources recorded for the target tab, or empty set.
     */
    public Set<SourceRef> getTargetTabCheckedSources() {
        TabInfo tab = getTargetTab();
        return tab != null ? Collections.unmodifiableSet(tab.checkedSources) : Collections.emptySet();
    }

    /**
     * Records the checked data sources on the target tab (in the given order).
     * Pure bookkeeping — no visual side effects; the source tree itself is the
     * caller's responsibility.
     */
    public void setTargetTabCheckedSources(Set<SourceRef> sources) {
        TabInfo tab = getTargetTab();
        if (tab == null) return;
        tab.checkedSources.clear();
        tab.checkedSources.addAll(sources);
    }

    /**
     * Removes a source from every tab's recorded context. Called when the source is
     * removed for good (a run removed, a dataset unloaded) so no tab tries to restore
     * it later. The "Last" alias is deliberately never scrubbed — see
     * {@link com.kalix.ide.flowviz.data.LastSource}.
     */
    public void removeSourceFromAllTabs(SourceRef ref) {
        for (TabInfo tab : tabs) {
            tab.checkedSources.remove(ref);
        }
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
     *
     * <p>Order is preserved: series already shown keep their existing position, series
     * no longer selected are dropped, and newly-selected series are appended at the end.
     * This keeps the stats-table row order (and plot series order) stable as the user
     * adds series — in particular the first row stays the first-added series, which is
     * the bivariate reference.</p>
     */
    public void setTargetTabSelectedSeries(Set<SeriesRef> series) {
        TabInfo tab = getTargetTab();
        if (tab == null) return;

        // Merge: retain still-selected series in their current order, then append the
        // genuinely-new ones (LinkedHashSet.addAll skips refs already present).
        Set<SeriesRef> ordered = new LinkedHashSet<>();
        for (SeriesRef ref : tab.selectedSeries) {
            if (series.contains(ref)) {
                ordered.add(ref);
            }
        }
        ordered.addAll(series);

        tab.selectedSeries.clear();
        tab.selectedSeries.addAll(ordered);

        if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
            // Rebuild legend (colour resolved at render time)
            tab.plotPanel.clearLegend();
            for (SeriesRef ref : ordered) {
                tab.plotPanel.addLegendSeries(ref);
            }

            // Update visible series and refresh
            tab.plotPanel.setStyleResolver(styleResolver);
            tab.plotPanel.setVisibleSeries(new ArrayList<>(ordered));
            tab.plotPanel.refreshData(false);
        } else if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
            // Rebuild stats table to show only selected series
            rebuildStatsTab(tab);
        }
    }

    /**
     * Rebuilds a stats tab to show only its selected series. Collects all series first and
     * hands them to {@link StatsTableModel#setSeries} so statistics are recomputed once,
     * not once per series.
     */
    private void rebuildStatsTab(TabInfo tab) {
        java.util.LinkedHashMap<SeriesRef, TimeSeriesData> series = new java.util.LinkedHashMap<>();
        for (SeriesRef ref : tab.selectedSeries) {
            TimeSeriesData data = sharedDataSet.getSeries(ref);
            if (data != null) {
                TimeSeriesData aggregatedData = com.kalix.ide.flowviz.transform.TimeSeriesAggregator.aggregate(
                    data, tab.statsPeriod, tab.statsMethod);
                if (aggregatedData != null) {
                    series.put(ref, aggregatedData);
                }
            }
        }
        tab.statsModel.setSeries(series);
    }

    /**
     * Returns the union of selected series across all tabs.
     */
    public Set<SeriesRef> getAllSelectedSeriesAcrossTabs() {
        Set<SeriesRef> all = new HashSet<>();
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

                // Rebuild legend to match (colour resolved at render time)
                panel.clearLegend();
                for (SeriesRef ref : state.getVisibleSeries()) {
                    panel.addLegendSeries(ref);
                }
                break;
            }
        }

        // Trigger tree sync
        if (onTabChangedCallback != null) {
            onTabChangedCallback.run();
        }
    }

    // (renameSeriesAcrossTabs removed — identity is now SeriesRef-typed, which is stable
    // across renames. The label projected by LabelResolver picks up the new name on the
    // next render; no per-collection rewriting required.)

    /**
     * Checks whether any tab has the given series selected.
     */
    public boolean isSeriesSelectedOnAnyTab(SeriesRef seriesRef) {
        for (TabInfo tab : tabs) {
            if (tab.selectedSeries.contains(seriesRef)) {
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
     * @param ref The identity of the series
     * @param data The original (unaggregated) time series data
     */
    public void updateSeriesInStatsTabsWithAggregation(SeriesRef ref, TimeSeriesData data) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null
                    && tab.selectedSeries.contains(ref)) {
                TimeSeriesData aggregatedData = com.kalix.ide.flowviz.transform.TimeSeriesAggregator.aggregate(
                    data, tab.statsPeriod, tab.statsMethod);

                if (aggregatedData != null) {
                    tab.statsModel.addOrUpdateSeries(ref, aggregatedData);
                }
            }
        }
    }

    /**
     * Adds a loading series entry to all stats tabs that include the given ref.
     */
    public void addLoadingSeriesInStatsTabs(SeriesRef ref) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null
                    && tab.selectedSeries.contains(ref)) {
                tab.statsModel.addLoadingSeries(ref);
            }
        }
    }

    /**
     * Adds an error series entry to all stats tabs that include the given ref.
     */
    public void addErrorSeriesInStatsTabs(SeriesRef ref, String errorMessage) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null
                    && tab.selectedSeries.contains(ref)) {
                tab.statsModel.addErrorSeries(ref, errorMessage);
            }
        }
    }

    /**
     * Removes the given series from <em>every</em> tab — clearing each tab's
     * selectedSeries set, the legend and visible-series list on plot tabs, and the
     * stats model on stats tabs. Plot data is refreshed once per affected plot tab.
     */
    public void removeSeriesFromAllTabs(Collection<SeriesRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        for (TabInfo tab : tabs) {
            boolean changed = tab.selectedSeries.removeAll(refs);
            if (!changed) {
                continue;
            }
            if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
                for (SeriesRef ref : refs) {
                    tab.plotPanel.removeLegendSeries(ref);
                }
                tab.plotPanel.setVisibleSeries(new ArrayList<>(tab.selectedSeries));
                tab.plotPanel.refreshData(false);
            } else if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
                for (SeriesRef ref : refs) {
                    tab.statsModel.removeSeries(ref);
                }
            }
        }
    }

    /**
     * Removes a series from all stats tabs.
     */
    public void removeSeriesFromStatsTabs(SeriesRef ref) {
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
                tab.selectedSeries.remove(ref);
                tab.statsModel.removeSeries(ref);
            }
        }
    }
}
