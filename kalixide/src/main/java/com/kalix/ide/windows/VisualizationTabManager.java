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

    // Icon sizes
    private static final int TAB_ICON_SIZE = 14;

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
     * Adds a new plot tab.
     *
     * @param name Ignored - tabs now use icons instead of names
     * @return The created PlotPanel
     */
    public PlotPanel addPlotTab(String name) {
        // Create new plot panel with shared dataset
        PlotPanel plotPanel = new PlotPanel();
        plotPanel.setDataSet(sharedDataSet);
        plotPanel.setSeriesColors(sharedColorMap);

        // Get visible series from existing tabs (if any)
        List<String> visibleSeries = getVisibleSeriesFromDataSet();
        plotPanel.setVisibleSeries(visibleSeries);

        // Enable features
        plotPanel.setShowCoordinates(true);

        // Get default Auto-Y mode from preferences
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
        JToolBar toolbar = createPlotToolbar(plotPanel, defaultAutoY);
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
    private JToolBar createPlotToolbar(PlotPanel plotPanel, boolean initialAutoY) {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setRollover(true);

        // Save Data button
        JButton saveButton = new JButton(FontIcon.of(FontAwesomeSolid.SAVE, 14));
        saveButton.setToolTipText("Save Data");
        saveButton.setFocusable(false);
        saveButton.addActionListener(e -> plotPanel.saveData());
        toolbar.add(saveButton);

        toolbar.addSeparator();

        // Zoom to Fit button
        JButton zoomFitButton = new JButton(FontIcon.of(FontAwesomeSolid.EXPAND, 14));
        zoomFitButton.setToolTipText("Zoom to Fit");
        zoomFitButton.setFocusable(false);
        zoomFitButton.addActionListener(e -> plotPanel.zoomToFit());
        toolbar.add(zoomFitButton);

        // Auto-Y toggle button
        JToggleButton autoYButton = new JToggleButton(FontIcon.of(FontAwesomeSolid.ARROWS_ALT_V, 14));
        autoYButton.setToolTipText("Auto-Y Mode");
        autoYButton.setFocusable(false);
        autoYButton.setSelected(initialAutoY);
        autoYButton.addActionListener(e -> {
            boolean enabled = autoYButton.isSelected();
            plotPanel.setAutoYMode(enabled);
            // If enabling Auto-Y, immediately apply it by zooming to fit
            if (enabled) {
                plotPanel.zoomToFit();
            }
        });
        toolbar.add(autoYButton);

        return toolbar;
    }

    /**
     * Adds a new statistics tab.
     *
     * @param name Ignored - tabs now use icons instead of names
     * @return The created StatsTableModel
     */
    public RunManager.StatsTableModel addStatsTab(String name) {
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
     * Sets up a tab with an icon.
     */
    private void setupTabIcon(int index, TabInfo.TabType tabType) {
        // Create tab panel with just the icon
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        tabPanel.setOpaque(false);

        // Create icon based on tab type
        FontIcon tabIcon;
        String tooltip;
        if (tabType == TabInfo.TabType.PLOT) {
            tabIcon = FontIcon.of(FontAwesomeSolid.CHART_LINE, TAB_ICON_SIZE);
            tooltip = "Plot";
        } else {
            tabIcon = FontIcon.of(FontAwesomeSolid.CALCULATOR, TAB_ICON_SIZE);
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
                        tabPanel.setBackground(new Color(200, 200, 255, 100));
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
                addPlotTab(null);
            } else {
                addStatsTab(null);
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
