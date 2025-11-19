package com.kalix.ide.flowviz;

import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.*;
import java.io.File;
import java.util.function.Consumer;

/**
 * Manages all user actions for the FlowViz window.
 *
 * This class handles all action methods that were previously part of FlowVizWindow,
 * providing a centralized location for action logic while maintaining clean
 * separation of concerns. Actions include view manipulation, data operations,
 * and UI state changes.
 *
 * The manager uses callback patterns to communicate with the parent window
 * and other components without creating tight coupling.
 *
 * @author Claude Code Assistant
 * @version 1.0
 */
public class FlowVizActionManager {

    private final PlotPanel plotPanel;
    private final DataPanel dataPanel;
    private final JSplitPane splitPane;
    private final FlowVizMenuManager menuManager;
    private final FlowVizDataManager dataManager;

    // State management
    private boolean dataVisible = false;  // Hidden by default
    private boolean autoYMode = true;
    private boolean precision64 = true;

    // Callbacks for parent communication
    private final Runnable titleUpdater;
    private final Consumer<File> currentFileUpdater;

    /**
     * Creates a new FlowVizActionManager.
     *
     * @param plotPanel The plot panel for zoom and display operations
     * @param dataPanel The data panel for series management
     * @param splitPane The split pane for layout management
     * @param menuManager The menu manager for state synchronization
     * @param dataManager The data manager for file operations
     * @param titleUpdater Callback to update window title
     * @param currentFileUpdater Callback to update current file reference
     */
    public FlowVizActionManager(PlotPanel plotPanel, DataPanel dataPanel, JSplitPane splitPane,
                               FlowVizMenuManager menuManager, FlowVizDataManager dataManager,
                               Runnable titleUpdater, Consumer<File> currentFileUpdater) {
        this.plotPanel = plotPanel;
        this.dataPanel = dataPanel;
        this.splitPane = splitPane;
        this.menuManager = menuManager;
        this.dataManager = dataManager;
        this.titleUpdater = titleUpdater;
        this.currentFileUpdater = currentFileUpdater;

        // Load initial state from preferences
        loadPreferences();

        // Set initial data panel visibility (hidden by default)
        if (!dataVisible) {
            splitPane.setLeftComponent(null);
            splitPane.setDividerLocation(0);
        }
    }

    /**
     * Starts a new session by clearing all data and resetting UI state.
     */
    public void newSession() {
        dataManager.clearAllData();
        dataPanel.clearSeries();
        currentFileUpdater.accept(null);
        titleUpdater.run();
    }

    /**
     * Opens CSV file dialog to load data.
     */
    public void openCsvFile() {
        dataManager.openCsvFiles();
    }

    /**
     * Exports the current plot (placeholder implementation).
     */
    public void exportPlot() {
        // Export plot - Not yet implemented
    }

    /**
     * Toggles the visibility of the data panel.
     */
    public void toggleData() {
        dataVisible = !dataVisible;
        if (dataVisible) {
            splitPane.setLeftComponent(dataPanel);
            splitPane.setDividerLocation(250);
        } else {
            splitPane.setLeftComponent(null);
            splitPane.setDividerLocation(0);
        }
        menuManager.updateMenuStates();
    }

    /**
     * Zooms into the plot.
     */
    public void zoomIn() {
        plotPanel.zoomIn();
    }

    /**
     * Zooms out from the plot.
     */
    public void zoomOut() {
        plotPanel.zoomOut();
    }

    /**
     * Zooms to fit all data in the view.
     */
    public void zoomToFit() {
        plotPanel.zoomToFit();
    }

    /**
     * Toggles the Auto-Y mode for automatic Y-axis scaling.
     */
    public void toggleAutoYMode() {
        autoYMode = !autoYMode;
        plotPanel.setAutoYMode(autoYMode);

        // Save preference
        PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, autoYMode);

        menuManager.updateMenuStates();
    }

    /**
     * Toggles coordinate display on the plot.
     */
    public void toggleCoordinateDisplay() {
        boolean currentState = plotPanel.isShowCoordinates();
        boolean newState = !currentState;
        plotPanel.setShowCoordinates(newState);

        // Save preference
        PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, newState);

    }

    /**
     * Toggles 64-bit precision mode for data export.
     */
    public void togglePrecision64() {
        precision64 = !precision64;

        // Save preference
        PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, precision64);

        menuManager.updateMenuStates();
    }

    /**
     * Resets the view to default zoom and pan settings.
     */
    public void resetView() {
        plotPanel.resetView();
    }

    /**
     * Shows statistics panel (placeholder implementation).
     */
    public void showStatistics() {
        // Statistics panel - Not yet implemented
    }

    /**
     * Shows data information dialog (placeholder implementation).
     */
    public void showDataInfo() {
        // Data information - Not yet implemented
    }

    /**
     * Shows the About dialog with application information.
     */
    public void showAbout() {
        JOptionPane.showMessageDialog(plotPanel.getParent(),
            "FlowViz - Time Series Visualization Tool\\n" +
            "Version 1.0\\n\\n" +
            "High-performance visualization for large datasets\\n" +
            "Part of the Kalix Hydrologic Modeling Platform",
            "About FlowViz",
            JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Shows keyboard shortcuts help dialog.
     */
    public void showShortcuts() {
        JOptionPane.showMessageDialog(plotPanel.getParent(),
            "FlowViz Keyboard & Mouse Shortcuts:\\n\\n" +
            "Ctrl+N - New session (clear all data)\\n" +
            "Ctrl+O - Add CSV file to current session\\n" +
            "Ctrl+W - Close window\\n" +
            "L - Toggle data panel\\n" +
            "+ - Zoom in\\n" +
            "- - Zoom out\\n" +
            "Mouse wheel - Zoom at cursor\\n" +
            "Mouse drag - Pan view\\n" +
            "Double-click plot - Reset zoom to fit data\\n\\n" +
            "Data Reordering:\\n" +
            "Click data item - Select item\\n" +
            "Cmd+↑/Ctrl+↑ - Move selected item up (toward background)\\n" +
            "Cmd+↓/Ctrl+↓ - Move selected item down (toward foreground)\\n\\n" +
            "File Loading:\\n" +
            "Drag & drop CSV files onto window - Load multiple files at once",
            "Keyboard & Mouse Shortcuts",
            JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Loads preferences and initializes action manager state.
     */
    private void loadPreferences() {
        // Load precision preference (default: true for 64-bit)
        precision64 = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, true);

        // Load auto-Y mode preference (default: true)
        autoYMode = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true);
        plotPanel.setAutoYMode(autoYMode);
    }

    /**
     * Reloads preferences from the preference store and updates the UI.
     * Called when preferences change externally.
     */
    public void reloadPreferences() {
        loadPreferences();
        menuManager.updateMenuStates();
    }

    // State getters for menu manager

    /**
     * Gets the current data panel visibility state.
     * @return true if data panel is visible
     */
    public boolean isDataVisible() {
        return dataVisible;
    }

    /**
     * Gets the current Auto-Y mode state.
     * @return true if Auto-Y mode is enabled
     */
    public boolean isAutoYMode() {
        return autoYMode;
    }

    /**
     * Gets the current 64-bit precision state.
     * @return true if 64-bit precision is enabled
     */
    public boolean isPrecision64() {
        return precision64;
    }
}