package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages visualization tabs in the RunManager.
 * Handles creation, deletion, and coordination of plot and statistics tabs.
 * All tabs share the same underlying dataset and color mappings.
 */
public class VisualizationTabManager {

    private final JTabbedPane tabbedPane;
    private final DataSet sharedDataSet;
    private final Map<String, Color> sharedColorMap;
    private final List<TabInfo> tabs;

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
        "Original resolution",
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

    /** Y-axis scale options. */
    private static final String[] Y_SPACE_OPTIONS = {"Linear", "Log", "Sqrt"};

    /**
     * Represents a visualization tab with its type and components.
     */
    private static class TabInfo {
        enum TabType { PLOT, STATS }

        final TabType type;
        final String name;
        final JComponent component;
        final PlotPanel plotPanel; // null for stats tabs
        final RunManager.StatsTableModel statsModel; // null for plot tabs

        TabInfo(TabType type, String name, JComponent component, PlotPanel plotPanel, RunManager.StatsTableModel statsModel) {
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

        // Get visible series from existing tabs (if any)
        List<String> visibleSeries = getVisibleSeriesFromDataSet();
        plotPanel.setVisibleSeries(visibleSeries);

        // Get default settings from preferences
        boolean defaultShowCoordinates = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, false);
        plotPanel.setShowCoordinates(defaultShowCoordinates);

        boolean defaultAutoY = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true);
        plotPanel.setAutoYMode(defaultAutoY);

        // Populate legend with existing series
        for (String seriesName : sharedDataSet.getSeriesNames()) {
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

        // Add tab
        TabInfo tabInfo = new TabInfo(TabInfo.TabType.PLOT, "Plot", containerPanel, plotPanel, null);
        tabs.add(tabInfo);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab("", containerPanel);
        setupTabIcon(index, TabInfo.TabType.PLOT);

        return plotPanel;
    }

    /**
     * Creates a toolbar for a plot tab.
     */
    private JToolBar createPlotToolbar(PlotPanel plotPanel, boolean initialAutoY, boolean initialShowCoordinates) {
        return new PlotToolbarBuilder(plotPanel)
            .addSaveButton()
            .addSeparator()
            .addAggregationControls()
            .addSeparator()
            .addYSpaceDropdown()
            .addSeparator()
            .addZoomButton()
            .addAutoYToggle(initialAutoY)
            .addCoordinatesToggle(initialShowCoordinates)
            .build();
    }

    /**
     * Builder for creating plot toolbars with consistent styling.
     */
    private static class PlotToolbarBuilder {
        private final JToolBar toolbar;
        private final PlotPanel plotPanel;

        PlotToolbarBuilder(PlotPanel plotPanel) {
            this.plotPanel = plotPanel;
            this.toolbar = new JToolBar();
            this.toolbar.setFloatable(false);
            this.toolbar.setRollover(true);
        }

        PlotToolbarBuilder addSaveButton() {
            JButton button = createIconButton(FontAwesomeSolid.SAVE, "Save Data", plotPanel::saveData);
            toolbar.add(button);
            return this;
        }

        PlotToolbarBuilder addAggregationControls() {
            // Aggregation period dropdown
            JComboBox<String> aggregationCombo = createDropdown(AGGREGATION_OPTIONS,
                UIConstants.WIDE_DROPDOWN_SIZE, "Aggregation");
            aggregationCombo.addActionListener(e -> {
                String selected = (String) aggregationCombo.getSelectedItem();
                // Functionality to be implemented
            });
            toolbar.add(aggregationCombo);

            // "by" label
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));
            toolbar.add(new JLabel("by"));
            toolbar.add(Box.createHorizontalStrut(UIConstants.HORIZONTAL_SPACING));

            // Aggregation method dropdown
            JComboBox<String> methodCombo = createDropdown(AGGREGATION_METHOD_OPTIONS,
                UIConstants.NARROW_DROPDOWN_SIZE, "Aggregation method");
            methodCombo.addActionListener(e -> {
                String selected = (String) methodCombo.getSelectedItem();
                // Functionality to be implemented
            });
            toolbar.add(methodCombo);

            return this;
        }

        PlotToolbarBuilder addYSpaceDropdown() {
            JComboBox<String> ySpaceCombo = createDropdown(Y_SPACE_OPTIONS,
                UIConstants.NARROW_DROPDOWN_SIZE, "Y-axis scale");
            ySpaceCombo.addActionListener(e -> {
                String selected = (String) ySpaceCombo.getSelectedItem();
                // Functionality to be implemented
            });
            toolbar.add(ySpaceCombo);
            return this;
        }

        PlotToolbarBuilder addZoomButton() {
            JButton button = createIconButton(FontAwesomeSolid.EXPAND, "Zoom to Fit", plotPanel::zoomToFit);
            toolbar.add(button);
            return this;
        }

        PlotToolbarBuilder addAutoYToggle(boolean initialState) {
            JToggleButton button = createToggleButton(FontAwesomeSolid.ARROWS_ALT_V,
                "Auto-Y Mode", initialState);
            button.addActionListener(e -> {
                boolean enabled = button.isSelected();
                plotPanel.setAutoYMode(enabled);
                if (enabled) {
                    plotPanel.zoomToFit();
                }
            });
            toolbar.add(button);
            return this;
        }

        PlotToolbarBuilder addCoordinatesToggle(boolean initialState) {
            JToggleButton button = createToggleButton(FontAwesomeSolid.CROSSHAIRS,
                "Show Coordinates", initialState);
            button.addActionListener(e -> plotPanel.setShowCoordinates(button.isSelected()));
            toolbar.add(button);
            return this;
        }

        PlotToolbarBuilder addSeparator() {
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
     * Adds a new statistics tab.
     *
     * @return The created StatsTableModel
     */
    public RunManager.StatsTableModel addStatsTab() {
        // Create new stats table
        RunManager.StatsTableModel model = new RunManager.StatsTableModel();
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);

        JScrollPane scrollPane = new JScrollPane(table);

        // Add tab
        TabInfo tabInfo = new TabInfo(TabInfo.TabType.STATS, "Statistics", scrollPane, null, model);
        tabs.add(tabInfo);

        int index = tabbedPane.getTabCount();
        tabbedPane.addTab("", scrollPane);
        setupTabIcon(index, TabInfo.TabType.STATS);

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

        // "Duplicate" menu item - always shown
        JMenuItem duplicateItem = new JMenuItem("Duplicate");
        duplicateItem.addActionListener(e -> {
            if (tabType == TabInfo.TabType.PLOT) {
                addPlotTab();
            } else {
                addStatsTab();
            }
        });
        contextMenu.add(duplicateItem);

        // "Remove" menu item - only shown if there are 2+ tabs of this type
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
                // Count tabs of this type
                int count = 0;
                for (TabInfo tab : tabs) {
                    if (tab.type == tabType) {
                        count++;
                    }
                }

                // Only show remove if there are 2+ tabs of this type
                removeItem.setVisible(count >= 2);

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
     * Updates all tabs with new data.
     * Called when timeseries selection changes.
     */
    public void updateAllTabs() {
        List<String> visibleSeries = getVisibleSeriesFromDataSet();

        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.PLOT && tab.plotPanel != null) {
                // Update plot tabs
                tab.plotPanel.setSeriesColors(sharedColorMap);
                tab.plotPanel.setVisibleSeries(visibleSeries);
                tab.plotPanel.repaint();
            }
            // Stats tabs update automatically through their DataSet listeners
        }
    }

    /**
     * Gets the list of visible series from the shared dataset.
     */
    private List<String> getVisibleSeriesFromDataSet() {
        if (sharedDataSet == null || sharedDataSet.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sharedDataSet.getSeriesNames());
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
    public List<RunManager.StatsTableModel> getAllStatsModels() {
        List<RunManager.StatsTableModel> models = new ArrayList<>();
        for (TabInfo tab : tabs) {
            if (tab.type == TabInfo.TabType.STATS && tab.statsModel != null) {
                models.add(tab.statsModel);
            }
        }
        return models;
    }

    /**
     * Gets the currently active tab info.
     */
    public TabInfo getActiveTab() {
        int index = tabbedPane.getSelectedIndex();
        if (index >= 0 && index < tabs.size()) {
            return tabs.get(index);
        }
        return null;
    }
}
