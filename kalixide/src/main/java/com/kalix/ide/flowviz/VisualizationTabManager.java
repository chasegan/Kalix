package com.kalix.ide.flowviz;

import com.kalix.ide.components.TabDragReorderer;
import com.kalix.ide.dataview.DataViewPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.SeriesStyleResolver;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.transform.AggregationPipeline;
import com.kalix.ide.preferences.PreferenceKeys;

import com.formdev.flatlaf.FlatClientProperties;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.Icon;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages visualization tabs (right side of RunManager) - plots and statistics.
 *
 * <h2>Data Architecture</h2>
 * All tabs share a {@link DataSet} ({@code sharedDataSet}) as a data pool and a color map.
 * The pool accumulates all fetched time series and does not shrink on deselect.
 * Each tab's {@link FlowVizModel} holds its selection, which decides the series from the
 * pool its pages show.
 *
 * <h2>Unified Tabs</h2>
 * Every tab owns the full bundle: a {@link FlowVizModel} — the one state owner (selection,
 * checked sources, aggregation, mask, undo history) — and three pages projecting it:
 * a {@link FlowVizPanel}, a {@link StatsVizPanel} and a {@link DataVizPanel}. The tab's
 * view mode decides which page is shown:
 * <ul>
 *   <li><b>PLOT</b> - the time-series chart (transforms, LOD rendering)</li>
 *   <li><b>STATS</b> - the statistics table (min/max/mean/etc.), fed from the
 *       same aggregation settings through the shared {@link AggregationPipeline},
 *       so the two projections can never disagree</li>
 *   <li><b>DATA</b> - the plot's display data as a table, across the whole
 *       period, spelled exactly as the plot's Save Data writes it</li>
 * </ul>
 * Hidden pages are lazy: what they miss marks them dirty, and they catch up when shown.
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
 * @see FlowVizPanel
 * @see com.kalix.ide.windows.RunManager#publishSeries
 */
public class VisualizationTabManager {

    private final JTabbedPane tabbedPane;
    private final DataSet sharedDataSet;           // Single source of truth for all tabs
    private final SeriesStyleResolver styleResolver;  // Shared resolver — consistent styling across all tabs
    private VizHost host = new VizHost() { };  // all-defaults host until the owner installs one
    private final List<TabInfo> tabs;
    /** Fetch states of series not (yet) in the pool, shared by every tab: fetching is per series. */
    private final Map<SeriesRef, SeriesLoadState> loadStates = new HashMap<>();

    // Tab change tracking
    private int lastActivePlotTabIndex = 0;

    // Default tab names ("View 1", "View 2", ...). Session-monotonic: numbers are
    // never reused after a close, so no two tabs can share a default name and
    // existing tabs never renumber under the user.
    private int nextViewNumber = 1;

    // Ghost-style drag-to-reorder, shared across all tabs (custom tab components, so it attaches
    // to each tab's handle rather than the strip). Initialized once tabbedPane exists.
    private final TabDragReorderer tabReorderer;

    /**
     * UI constants for consistent styling and sizing. Toolbar sizing lives with
     * {@link FlowVizToolbarBuilder}; these cover the tab strip itself.
     */
    private static class UIConstants {
        static final int TAB_PANEL_PADDING = 2;
        /** Glyph size for the trailing "+" (new tab) button in the tab strip. */
        static final int NEW_TAB_ICON_SIZE = 14;
    }

    /**
     * A tab's display preferences: how its plot is drawn, outside the undoable state.
     * New tabs take them from preferences; a duplicate copies its source's.
     */
    private record DisplayPrefs(boolean showCoordinates, boolean legendCollapsed, boolean legendEnabled,
                                boolean connectAcrossGaps, boolean showOrphanMarkers) {

        static DisplayPrefs fromPreferences() {
            return new DisplayPrefs(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES.get(),
                PreferenceKeys.PLOT_LEGEND_COLLAPSED.get(), PreferenceKeys.PLOT_LEGEND_ENABLED.get(),
                false, false);
        }

        static DisplayPrefs of(FlowVizPanel panel) {
            return new DisplayPrefs(panel.isShowCoordinates(), panel.isLegendCollapsed(),
                panel.isLegendEnabled(), panel.isConnectAcrossGaps(), panel.isShowOrphanMarkers());
        }

        void applyTo(FlowVizPanel panel) {
            panel.setShowCoordinates(showCoordinates);
            // Order matters: setConnectAcrossGaps(true) clears orphan markers and vice versa,
            // so apply connect first — for every valid (mutually exclusive) combination the
            // net result matches the source tab.
            panel.setConnectAcrossGaps(connectAcrossGaps);
            panel.setShowOrphanMarkers(showOrphanMarkers);
            // Unconditional: the new legend manager starts from the global preference, which
            // may disagree with this tab's settings in either direction.
            panel.setLegendCollapsed(legendCollapsed);
            panel.setLegendEnabled(legendEnabled);
        }
    }

    /**
     * The state a fresh tab of the given view starts in: the defaults, with the view's
     * overlap mask — none on a plot, ALL on the stats (their historical default).
     */
    static FlowVizState defaultState(FlowVizView view) {
        MaskMode mask = switch (view) {
            case PLOT, DATA -> MaskMode.NONE;
            case STATS -> MaskMode.ALL;
        };
        return FlowVizState.defaults().withMaskMode(mask);
    }

    /**
     * Represents a visualization tab: its pages over one {@link FlowVizModel}, reached
     * through the plot page's {@link FlowVizPanel#model()}, and the tab's UI handles.
     * Package-private: {@link FlowVizToolbarBuilder} drives the panel through it.
     */
    static class TabInfo {
        /** Which projection this tab currently shows. Mutable by design (the view toggle). */
        FlowVizView viewMode;
        final JComponent component;
        final FlowVizPanel vizPanel;
        final StatsVizPanel statsVizPanel;
        final DataVizPanel dataVizPanel;

        // Tab identifier: starts as a machine default ("View n"), user-renamable
        String name;
        /** The machine-assigned default name; rename-to-empty reverts to it. */
        String defaultName;
        // remember the label for renaming
        JLabel nameLabel;

        // Toolbar/view handles, set once built: let in-place operations (Reset,
        // the view toggle) drive the tab's UI from its actual state.
        FlowVizToolbarBuilder vizToolbar;
        JPanel viewCards; // CardLayout holding the plot, stats and data pages

        TabInfo(FlowVizView viewMode, String name, JComponent component, 
                FlowVizPanel vizPanel, StatsVizPanel statsVizPanel, DataVizPanel dataVizPanel
            ) {
            this.viewMode = viewMode;
            this.name = name;
            this.component = component;
            this.vizPanel = vizPanel;
            this.statsVizPanel = statsVizPanel;
            this.dataVizPanel = dataVizPanel;
        }

        FlowVizModel model() {
            return vizPanel.model();
        }

        /** The selected series, in order: the first is the bivariate reference. */
        List<SeriesRef> selection() {
            return model().current().getVisibleSeries();
        }

        void rename(String name) {
            this.name = name;
            this.nameLabel.setText(name);
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
            if (active != null && active.viewMode == FlowVizView.PLOT) {
                lastActivePlotTabIndex = tabbedPane.getSelectedIndex();
            }
            host.onActiveTabChanged();
        });
    }

    /**
     * Installs the owning window's {@link VizHost} — label projection, save-dialog
     * seeding, and active-tab-change notification, in one seam. Call before the
     * first tab is added so new panels pick the context up; installing later
     * retrofits the label resolver onto existing tabs. Never null: the manager
     * starts with an all-defaults host, which is the complete "no sources,
     * default labels" configuration rather than a degraded one.
     */
    public void setHost(VizHost host) {
        this.host = host;
        LabelResolver labels = host.labelResolver();
        for (TabInfo tab : tabs) {
            if (labels != null) {
                tab.vizPanel.setLabelResolver(labels);
                tab.statsVizPanel.setLabelResolver(labels);
                tab.dataVizPanel.setLabelResolver(labels);
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
        // No explicit icon colour: an uncoloured FontIcon leaves the Graphics colour
        // alone and so paints in the component's foreground, which follows the theme
        // and survives updateComponentTreeUI. Setting one here would bake a colour
        // that goes stale on the next theme switch.
        FontIcon icon = FontIcon.of(FontAwesomeSolid.PLUS, UIConstants.NEW_TAB_ICON_SIZE);

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
     * Adds a plot tab that inherits the active tab's series and sources (or, with no tab
     * yet, every series in the pool), with default settings. It is not selected.
     *
     * @return The created FlowVizPanel
     */
    public FlowVizPanel addPlotTab() {
        TabInfo active = getActiveTab();
        FlowVizState initial = defaultState(FlowVizView.PLOT)
            .withVisibleSeries(active != null ? active.selection() : new ArrayList<>(sharedDataSet.getSeriesRefs()))
            .withCheckedSources(active != null ? active.model().current().getCheckedSources() : Set.of());
        return addTab(initial, FlowVizView.PLOT, DisplayPrefs.fromPreferences(), null, null, false).vizPanel;
    }

    /**
     * Adds a plot tab in {@code initial}'s state, and selects it.
     *
     * @return The created FlowVizPanel
     */
    public FlowVizPanel addPlotTab(FlowVizState initial) {
        return addTab(initial, FlowVizView.PLOT, DisplayPrefs.fromPreferences(), null, null, true).vizPanel;
    }

    /**
     * Adds a plot tab with <em>no series selected</em> — the blank-slate counterpart to
     * duplicating a tab and clearing its selection by hand.
     *
     * <p>The checked <em>sources</em> are deliberately inherited from the active tab.
     * Starting with no sources would empty the outputs tree too, forcing the source to be
     * re-checked before any series could be picked — more work than the gesture this
     * replaces, not less.</p>
     *
     * @return The created FlowVizPanel
     */
    public FlowVizPanel addEmptyPlotTab() {
        TabInfo active = getActiveTab();
        return addPlotTab(defaultState(FlowVizView.PLOT)
            .withCheckedSources(active != null ? active.model().current().getCheckedSources() : Set.of()));
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
     * @return The created FlowVizPanel
     */
    public FlowVizPanel addDefaultPlotTab() {
        return addPlotTab(defaultState(FlowVizView.PLOT).withCheckedSources(Set.of(new LastSource())));
    }

    /**
     * Adds a stats-view tab in {@code initial}'s state, and selects it.
     *
     * @return The created StatsTableModel (the tab's stats projection)
     */
    public StatsTableModel addStatsTab(FlowVizState initial) {
        return addTab(initial, FlowVizView.STATS, DisplayPrefs.fromPreferences(), null, null, true)
            .statsVizPanel.model();
    }

    /**
     * Duplicates a tab, Chrome-style: the copy has the source's whole undo history, its
     * current state, display preferences and page. A default-named source gives the copy
     * a fresh number (two "View 1"s help nobody); a user-chosen name is kept.
     */
    TabInfo duplicateTab(TabInfo source) {
        String name = source.name != null && source.name.equals(source.defaultName) ? null : source.name;
        return addTab(source.model().current(), source.viewMode, DisplayPrefs.of(source.vizPanel),
            name, source.vizPanel, true);
    }

    /**
     * Creates a tab: one {@link FlowVizModel} and the three pages over it, in
     * {@code initial}'s state; {@code viewMode} decides which page shows.
     *
     * @param name a user-chosen name, or null for a fresh "View n"
     * @param historySource a panel whose history the tab copies (duplication), or null
     */
    private TabInfo addTab(FlowVizState initial, FlowVizView viewMode, DisplayPrefs prefs,
                           String name, FlowVizPanel historySource, boolean select) {
        // One model per tab owns its state; the pages project it.
        FlowVizModel model = new FlowVizModel();
        FlowVizPanel vizPanel = new FlowVizPanel(model);
        vizPanel.setDataSet(sharedDataSet);
        vizPanel.setStyleResolver(styleResolver);
        // Read through the current host at each dialog open (the host can be
        // installed after early tabs exist).
        vizPanel.setBaseDirectorySupplier(() -> host.baseDirectory());

        StatsVizPanel statsVizPanel = new StatsVizPanel(model, () -> loadStates);
        DataVizPanel dataVizPanel = new DataVizPanel(model);

        LabelResolver labels = host.labelResolver();
        if (labels != null) {
            vizPanel.setLabelResolver(labels);
            statsVizPanel.setLabelResolver(labels);
            dataVizPanel.setLabelResolver(labels);
        }

        JPanel containerPanel = new JPanel(new BorderLayout());
        String tabName = name != null && !name.isEmpty() ? name : "View " + nextViewNumber++;
        TabInfo tabInfo = new TabInfo(viewMode, tabName, containerPanel, vizPanel, statsVizPanel, dataVizPanel);
        if (!tabName.equals(name)) {
            tabInfo.defaultName = tabName;
        }

        // One change: the initial state lands as a single rebuild and a single history
        // entry, so a fresh tab starts with a one-entry history (no construction
        // intermediates for undo to walk into).
        model.replace(initial);
        prefs.applyTo(vizPanel);

        // Duplicating copies the source's full history (the batch above already pushed
        // this tab's own initial entry, which the copy replaces). Done BEFORE the toolbar
        // is built, which initialises its controls from the state.
        if (historySource != null) {
            vizPanel.copyHistoryFrom(historySource);
        }

        // Undo and redo move the whole tab: the pages follow the model themselves, and
        // the window trees reproject when it is the active tab.
        model.addListener((old, now, cause) -> {
            if (cause == FlowVizModel.Cause.RESTORE) {
                notifyTabMutated(tabInfo);
            }
            if (!now.getVisibleSeries().equals(old.getVisibleSeries())) {
                pruneLoadStates();
                host.onSelectionsChanged();
            }
        });

        tabs.add(tabInfo);

        // The shown page draws now; hidden ones start dirty and catch up on toggle.
        showPages(tabInfo, viewMode);

        FlowVizToolbarBuilder toolbarBuilder = new FlowVizToolbarBuilder(tabInfo);
        toolbarBuilder.setOnViewToggle(mode -> switchView(tabInfo, mode));
        containerPanel.add(toolbarBuilder.build(model.current().isAutoYMode(), prefs.showCoordinates()),
            BorderLayout.NORTH);
        tabInfo.vizToolbar = toolbarBuilder;

        // Every tab mounts every page in a CardLayout — the view toggle switches
        // them live with no re-parenting (re-parenting would trip FlowVizPanel's
        // removeNotify teardown and flicker).
        JPanel viewCards = new JPanel(new CardLayout());
        viewCards.add(vizPanel, FlowVizView.PLOT.name());
        viewCards.add(statsVizPanel, FlowVizView.STATS.name());
        viewCards.add(dataVizPanel, FlowVizView.DATA.name());
        ((CardLayout) viewCards.getLayout()).show(viewCards, viewMode.name());
        tabInfo.viewCards = viewCards;
        containerPanel.add(viewCards, BorderLayout.CENTER);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab(tabInfo.name, containerPanel);
        setupTabHandle(index, tabInfo);

        if (select) {
            tabbedPane.setSelectedIndex(index);
        }

        return tabInfo;
    }

    /**
     * Switches a tab between its pages. Pure presentation: no history entry
     * (Ctrl+Z must never flip the page), no tree reprojection (the selection is
     * unchanged). The newly shown page catches up on whatever it missed.
     */
    void switchView(TabInfo tab, FlowVizView mode) {
        if (tab.viewMode == mode) {
            return;
        }
        tab.viewMode = mode;
        ((CardLayout) tab.viewCards.getLayout()).show(tab.viewCards, mode.name());
        if (tab.vizToolbar != null) {
            tab.vizToolbar.applyViewMode(mode);
        }
        showPages(tab, mode);
        if (mode == FlowVizView.PLOT && tab == getActiveTab()) {
            lastActivePlotTabIndex = tabbedPane.getSelectedIndex();
        }
    }

    /** Tells each page whether it is the one showing. */
    private static void showPages(TabInfo tab, FlowVizView mode) {
        tab.vizPanel.setShown(mode == FlowVizView.PLOT);
        tab.statsVizPanel.setShown(mode == FlowVizView.STATS);
        tab.dataVizPanel.setShown(mode == FlowVizView.DATA);
    }

    /**
     * Sets up a tab with an icon and interaction handlers.
     */
    private void setupTabHandle(int index, TabInfo tabInfo) {
        // The handle is the name alone. Tabs carry no icon: every tab is the same
        // unified kind now (the toolbar's view toggle announces the page), so an
        // icon would convey nothing — the default "View n" name is the handle.
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,
            UIConstants.TAB_PANEL_PADDING, UIConstants.TAB_PANEL_PADDING));
        tabPanel.setOpaque(false);
        JPanel labelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, UIConstants.TAB_PANEL_PADDING, 0));
        labelPanel.setOpaque(false);

        JLabel nameLabel = new JLabel(tabInfo.name);
        labelPanel.add(nameLabel);
        tabPanel.add(labelPanel);

        tabbedPane.setTabComponentAt(index, tabPanel);

        // Add drag-and-drop support for tab reordering
        setupTabDragAndDrop(nameLabel, labelPanel);

        // Add context menu support
        setupTabContextMenu(tabPanel, nameLabel, labelPanel);
        setupTabDoubleClickRename(tabPanel, nameLabel, labelPanel);
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

    /** Duplicates the tab behind {@code tabPanel}; a tab that can no longer be found gets a default tab instead. */
    private void duplicateTabOf(JPanel tabPanel) {
        int tabIndex = tabbedPane.indexOfTabComponent(tabPanel);
        if (tabIndex == -1 || tabIndex >= tabs.size()) {
            addPlotTab(defaultState(FlowVizView.PLOT));
            return;
        }
        duplicateTab(tabs.get(tabIndex));
    }

    /**
     * Sets up context menu for tab right-click. Built once and shared across every
     * {@code labelComponents} entry (icon, name, wrapper panel) since mouse events dispatch to
     * whichever of them is deepest under the cursor.
     */
    private void setupTabContextMenu(JPanel tabPanel, Component... labelComponents) {
        JPopupMenu contextMenu = new JPopupMenu();

        // Primary block (ADR-0002 §1 ①): the default action, which on a tab is
        // what double-click does (setupTabDoubleClickRename). Ellipsis: opens a dialog (§2.4).
        JMenuItem renameItem = new JMenuItem("Rename…");
        renameItem.addActionListener(e -> triggerTabRename(tabPanel));
        contextMenu.add(renameItem);

        contextMenu.addSeparator();

        // Modify block (§1 ⑤). "Duplicate" names no type: the tab is the context (§2.3),
        // and it copies the active view — the old "new tab of the other kind" gesture is
        // now Duplicate + the view toggle. Reset changes the tab's state rather than its
        // existence — in place, keeping the tab's name and history, undoable — so it is
        // a modify action; the destructive block that §1 ⑥ and §8 isolate is Remove alone.
        JMenuItem duplicateItem = new JMenuItem("Duplicate");
        duplicateItem.addActionListener(e -> duplicateTabOf(tabPanel));
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
     * <p>Cancelling leaves the name alone; submitting an empty box reverts to the tab's
     * default name ("View n"). Without tab icons an empty handle would be ungrabbable,
     * so unnamed is no longer a reachable state — the default is the floor.</p>
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
        if (trimmed.isEmpty()) {
            if (sourceTab.defaultName == null) {
                sourceTab.defaultName = "View " + nextViewNumber++;
            }
            trimmed = sourceTab.defaultName;
        }
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
        pruneLoadStates();
        host.onSelectionsChanged();
    }

    /**
     * Detaches the toolbar callbacks a plot tab's toolbar installed on its panel. This
     * is the owner's side of the contract with {@code FlowVizPanel.removeNotify()}, which
     * leaves those callbacks alone so a re-parented tab keeps working; they are released
     * here, when the tab is genuinely discarded (close).
     */
    private void detachTabCallbacks(TabInfo tab) {
        tab.vizPanel.setOnHistoryChanged(null);
        tab.vizPanel.getLegendManager().setOnEnabledChanged(null);
    }

    /**
     * Resets the tab at {@code index} in place: default settings from preferences, no
     * series selected, no checked sources — while preserving the tab's identity (name,
     * position, toolbar) and its undo history, onto which the reset lands as one
     * undoable entry. It starts from the same {@link #defaultState} as a new tab, so a
     * reset tab cannot drift from what a genuinely new tab looks like.
     */
    void resetTabAt(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        TabInfo tab = tabs.get(index);

        // One reset path for every view. Mask semantics per view are preserved: a
        // plot-view reset leaves the mask alone (as it always has), a stats-view reset
        // restores the stats default (ALL). The seasonal mask has no per-view default.
        FlowVizState reset = switch (tab.viewMode) {
            case PLOT, DATA -> defaultState(tab.viewMode).withMaskMode(tab.model().current().getMaskMode());
            case STATS -> defaultState(FlowVizView.STATS);
        };
        tab.model().replace(reset);
        DisplayPrefs.fromPreferences().applyTo(tab.vizPanel);

        // The canonical record changed in place: reproject the trees (active tab only).
        notifyTabMutated(tab);
    }

    /**
     * Tells every tab that the pool changed; each page refreshes, or catches up when
     * shown.
     *
     * @param resetZoom If true, plot-view tabs zoom to fit all data. If false, zoom is kept.
     */
    public void updateAllTabs(boolean resetZoom) {
        for (TabInfo tab : tabs) {
            tab.vizPanel.setStyleResolver(styleResolver);
            tab.model().dataChanged(); // a shown plot rebuilds here
            if (resetZoom && tab.viewMode == FlowVizView.PLOT) {
                tab.vizPanel.fitToData();
            }
        }
    }

    /**
     * Tells the tab of {@code targetPanel} that the pool changed.
     *
     * @param resetZoom If true, the plot zooms to fit all data. If false, zoom is kept.
     */
    public void updateTab(FlowVizPanel targetPanel, boolean resetZoom) {
        for (TabInfo tab : tabs) {
            if (tab.vizPanel == targetPanel) {
                tab.vizPanel.setStyleResolver(styleResolver);
                tab.model().dataChanged(); // a shown plot rebuilds here
                if (resetZoom && tab.viewMode == FlowVizView.PLOT) {
                    tab.vizPanel.fitToData();
                } else if (resetZoom) {
                    tab.vizPanel.refreshData(true);
                }
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
     * Reprojects the window-level views (the source and outputs trees, via
     * {@link VizHost#onActiveTabChanged}) after an in-place
     * mutation of a tab's state (its selection or checked sources).
     * The trees mirror the ACTIVE tab only, so the active-tab guard lives here
     * and nowhere else: mutating a background tab stays silent, and its state
     * is projected later by activation ("Empty restores empty").
     *
     * <p>Every in-place mutation of a tab's record must end here — Reset and
     * undo/redo (through the tab's model listener) do. Mutations that happen while the corresponding
     * tree nodes are being removed in the same operation (removeRunData,
     * removeLoadedDataset) deliberately do not: their projection is corrected
     * at the source, and firing a restore mid-removal would be wrong. For
     * BACKGROUND tabs those silent scrubs can leave the record ahead of the
     * history top, so the scrub rides into that tab's next unrelated push —
     * accepted, per the silent-failure rule documented on FlowVizState.</p>
     */
    private void notifyTabMutated(TabInfo tab) {
        if (tab == getActiveTab()) {
            host.onActiveTabChanged();
        }
    }

    /**
     * Returns the last-active plot-view tab (fallback when no tab is selected).
     */
    private TabInfo getLastActivePlotTab() {
        if (lastActivePlotTabIndex >= 0 && lastActivePlotTabIndex < tabs.size()) {
            TabInfo tab = tabs.get(lastActivePlotTabIndex);
            if (tab.viewMode == FlowVizView.PLOT) return tab;
        }
        // Fallback: first plot-view tab
        for (TabInfo tab : tabs) {
            if (tab.viewMode == FlowVizView.PLOT) return tab;
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
        return tab != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(tab.selection()))
            : Collections.emptySet();
    }

    /**
     * Returns the checked data sources recorded for the target tab, or empty set.
     */
    public Set<SourceRef> getTargetTabCheckedSources() {
        TabInfo tab = getTargetTab();
        return tab != null ? tab.model().current().getCheckedSources() : Collections.emptySet();
    }

    /**
     * Pushes the target tab's current state to its undo history, if it changed.
     * Source tick/untick is an undoable action in its own right: the window layer
     * calls this after recording a source change, and when the change also pruned
     * series (which pushes on its own) the second push dedupes via FlowVizState.equals.
     * Every tab has a history now — a stats-view tab's source ticks are just as
     * undoable as a plot-view tab's.
     */
    public void pushTargetTabHistory() {
        FlowVizPanel panel = getTargetVizPanel();
        if (panel != null) {
            panel.pushState();
        }
    }

    /**
     * Records the checked data sources on the target tab (in the given order).
     * Pure bookkeeping — no visual side effects and no history entry of its own
     * ({@link #pushTargetTabHistory} records it); the source tree itself is the
     * caller's responsibility.
     */
    public void setTargetTabCheckedSources(Set<SourceRef> sources) {
        TabInfo tab = getTargetTab();
        if (tab == null) return;
        Set<SourceRef> ordered = new LinkedHashSet<>(sources);
        tab.model().amend(s -> s.withCheckedSources(ordered));
    }

    /**
     * Removes a source from every tab's recorded context. Called when the source is
     * removed for good (a run removed, a dataset unloaded) so no tab tries to restore
     * it later. The "Last" alias is deliberately never scrubbed — see
     * {@link com.kalix.ide.flowviz.data.LastSource}.
     */
    public void removeSourceFromAllTabs(SourceRef ref) {
        for (TabInfo tab : tabs) {
            Set<SourceRef> sources = tab.model().current().getCheckedSources();
            if (sources.contains(ref)) {
                Set<SourceRef> kept = new LinkedHashSet<>(sources);
                kept.remove(ref);
                tab.model().amend(s -> s.withCheckedSources(kept));
            }
        }
    }

    /**
     * Returns the state-owning panel for the target tab (every tab has one),
     * or null when there is no tab at all.
     */
    public FlowVizPanel getTargetVizPanel() {
        TabInfo tab = getTargetTab();
        return tab != null ? tab.vizPanel : null;
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
        for (SeriesRef ref : tab.selection()) {
            if (series.contains(ref)) {
                ordered.add(ref);
            }
        }
        ordered.addAll(series);

        // The selection lands in the tab's state (undoably) whatever view is showing;
        // every page follows the model (or dirties).
        tab.vizPanel.setStyleResolver(styleResolver);
        tab.model().update(s -> s.withVisibleSeries(new ArrayList<>(ordered)));
    }

    /**
     * Forgets the fetch state of series no tab selects any more, on every selection change
     * (tick, undo, redo, close), so a stale failure never comes back with a restored selection.
     */
    private void pruneLoadStates() {
        loadStates.keySet().retainAll(getAllSelectedSeriesAcrossTabs());
    }

    /**
     * Returns the union of selected series across all tabs.
     */
    public Set<SeriesRef> getAllSelectedSeriesAcrossTabs() {
        Set<SeriesRef> all = new HashSet<>();
        for (TabInfo tab : tabs) {
            all.addAll(tab.selection());
        }
        return all;
    }

    // (renameSeriesAcrossTabs removed — identity is now SeriesRef-typed, which is stable
    // across renames. The label projected by LabelResolver picks up the new name on the
    // next render; no per-collection rewriting required.)

    /**
     * Checks whether any tab has the given series selected.
     */
    public boolean isSeriesSelectedOnAnyTab(SeriesRef seriesRef) {
        for (TabInfo tab : tabs) {
            if (tab.selection().contains(seriesRef)) {
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
     * Gets every tab's state-owning panel (all tabs have one).
     */
    public List<FlowVizPanel> getAllVizPanels() {
        List<FlowVizPanel> vizPanels = new ArrayList<>();
        for (TabInfo tab : tabs) {
            vizPanels.add(tab.vizPanel);
        }
        return vizPanels;
    }

    /** The tab at the given strip index — package-private, for tests. */
    TabInfo tabAt(int index) {
        return tabs.get(index);
    }

    /**
     * A series' data has arrived: its load state clears, and every tab that shows it
     * reprojects (hidden pages catch up when shown). Each page reads the aggregate from
     * its tab's cache, which resolves the ref against the pool.
     *
     * <p>The caller publishes {@code data} to the pool first.
     *
     * @param ref The identity of the series
     * @param data The original (unaggregated) time series data, already in the pool
     */
    public void updateSeriesInStatsTabsWithAggregation(SeriesRef ref, TimeSeriesData data) {
        loadStates.remove(ref);
        for (TabInfo tab : tabs) {
            if (tab.selection().contains(ref)) {
                tab.model().dataChanged();
            }
        }
    }

    /**
     * Marks a series as loading: every tab that shows it gets a blank placeholder row
     * in its stats page until the data arrives.
     */
    public void addLoadingSeriesInStatsTabs(SeriesRef ref) {
        setLoadState(ref, SeriesLoadState.LOADING);
    }

    /**
     * Marks a series' fetch as failed: every tab that shows it gets an "Error" row in
     * its stats page.
     */
    public void addErrorSeriesInStatsTabs(SeriesRef ref, String errorMessage) {
        setLoadState(ref, new SeriesLoadState.Failed(errorMessage));
    }

    private void setLoadState(SeriesRef ref, SeriesLoadState state) {
        loadStates.put(ref, state);
        for (TabInfo tab : tabs) {
            if (tab.selection().contains(ref)) {
                tab.statsVizPanel.refresh();
            }
        }
    }

    /**
     * Removes the given series from <em>every</em> tab's selection; every page follows.
     */
    public void removeSeriesFromAllTabs(Collection<SeriesRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        for (TabInfo tab : tabs) {
            removeSeriesFromTab(tab, refs);
        }
    }

    /** Drops refs from one tab's selection; every page follows the model. */
    private void removeSeriesFromTab(TabInfo tab, Collection<SeriesRef> refs) {
        List<SeriesRef> kept = new ArrayList<>(tab.selection());
        if (!kept.removeAll(refs)) {
            return;
        }
        tab.model().update(s -> s.withVisibleSeries(kept));
    }
}
