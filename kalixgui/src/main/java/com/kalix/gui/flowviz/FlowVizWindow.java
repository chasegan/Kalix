package com.kalix.gui.flowviz;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.io.TimeSeriesCsvImporter;
import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

public class FlowVizWindow extends JFrame {
    private static final List<FlowVizWindow> openWindows = new ArrayList<>();
    private static int windowCounter = 0;
    
    private PlotPanel plotPanel;
    private DataPanel dataPanel;
    private JLabel statusLabel;
    private JSplitPane splitPane;
    private boolean dataVisible = true;
    private boolean autoYMode = true;
    
    // Data management
    private DataSet dataSet;
    private File currentFile;

    // Preferences
    private final Preferences prefs;

    // Managers
    private FlowVizMenuManager menuManager;
    private FlowVizDataManager dataManager;
    
    public FlowVizWindow() {
        windowCounter++;
        setTitle("FlowViz - Untitled " + windowCounter);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);

        // Initialize preferences
        this.prefs = Preferences.userNodeForPackage(FlowVizWindow.class);

        // Initialize menu manager
        menuManager = new FlowVizMenuManager(this, prefs);

        // Track this window
        openWindows.add(this);

        // Handle window closing
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                openWindows.remove(FlowVizWindow.this);
            }
        });

        initializeComponents();
        setupLayout();
        setupMenuBar();
        setupDataModel();
        setupDataManager();
        loadPreferences();

        setVisible(true);
    }
    
    private void initializeComponents() {
        plotPanel = new PlotPanel();
        plotPanel.setAutoYMode(autoYMode);  // Initialize Auto-Y mode
        dataPanel = new DataPanel();
        dataPanel.setPreferredSize(new Dimension(250, 0));
        
        statusLabel = new JLabel("Ready - No data loaded");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Create split pane with data panel on left, plot on right
        splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            dataPanel,
            plotPanel
        );
        splitPane.setDividerLocation(250);
        splitPane.setResizeWeight(0.0); // Keep data panel fixed width
        
        add(splitPane, BorderLayout.CENTER);
        
        // Status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusPanel, BorderLayout.SOUTH);
        
        // Set up visibility change listener for data panel
        dataPanel.setVisibilityChangeListener(this::updatePlotVisibility);
        
        // Data manager will handle drag-and-drop
    }
    
    private void setupMenuBar() {
        // Setup action callbacks
        menuManager.setupActionCallbacks(
            this::newSession,
            this::openCsvFile,
            this::exportPlot,
            this::toggleData,
            this::toggleCoordinateDisplay,
            this::zoomIn,
            this::zoomOut,
            this::zoomToFit,
            this::resetView,
            this::toggleAutoYMode,
            this::showStatistics,
            this::showDataInfo,
            this::showAbout,
            this::showShortcuts
        );

        // Setup state suppliers
        menuManager.setupStateSuppliers(
            () -> dataVisible,
            () -> autoYMode,
            () -> plotPanel.isShowCoordinates()
        );

        // Create and set the menu bar
        JMenuBar menuBar = menuManager.createMenuBar();
        setJMenuBar(menuBar);

        // Setup keyboard shortcuts
        menuManager.setupKeyboardShortcuts();
    }
    
    
    
    private void setupDataModel() {
        dataSet = new DataSet();
        
        // Set up data change listeners
        dataSet.addListener(new DataSet.DataSetListener() {
            @Override
            public void onSeriesAdded(TimeSeriesData series) {
                // Generate color for the series (cycle through predefined colors)
                Color seriesColor = generateSeriesColor(dataSet.getSeriesCount() - 1);

                dataPanel.addSeries(series.getName(), seriesColor, series.getPointCount());
                
                // Update plot panel with new data
                updatePlotPanel();
                
                updateTitle();
                updateStatus(String.format("Added series '%s' with %,d points", 
                    series.getName(), series.getPointCount()));
            }
            
            @Override
            public void onSeriesRemoved(String seriesName) {
                dataPanel.removeSeries(seriesName);
                updatePlotPanel();
                updateTitle();
                updateStatus("Removed series: " + seriesName);
            }
            
            @Override
            public void onDataChanged() {
                updatePlotPanel();
                updateStatus("Data updated");
            }
        });
    }
    
    private static final Color[] SERIES_COLORS = {
        new Color(31, 119, 180),   // Blue
        new Color(255, 127, 14),   // Orange
        new Color(44, 160, 44),    // Green
        new Color(214, 39, 40),    // Red
        new Color(148, 103, 189),  // Purple
        new Color(140, 86, 75),    // Brown
        new Color(227, 119, 194),  // Pink
        new Color(127, 127, 127),  // Gray
        new Color(188, 189, 34),   // Olive
        new Color(23, 190, 207),   // Cyan
        new Color(174, 199, 232),  // Light Blue
        new Color(255, 187, 120)   // Light Orange
    };
    
    private Color generateSeriesColor(int seriesIndex) {
        return SERIES_COLORS[seriesIndex % SERIES_COLORS.length];
    }
    
    private void updateTitle() {
        String title = "FlowViz";
        if (currentFile != null) {
            title += " - " + currentFile.getName();
        } else {
            title += " - Untitled " + windowCounter;
        }
        
        if (dataSet.getSeriesCount() > 0) {
            title += String.format(" (%d series, %,d total points)", 
                dataSet.getSeriesCount(), dataSet.getTotalPointCount());
        }
        
        setTitle(title);
    }
    
    private void updatePlotPanel() {
        // Update plot panel with current data and colors
        plotPanel.setDataSet(dataSet);
        
        // Build color map for all series
        Map<String, Color> colorMap = new HashMap<>();
        List<String> visibleSeriesList = dataPanel.getVisibleSeries();

        // If no series are marked as visible in data panel (initial state), show all series
        if (visibleSeriesList.isEmpty() && !dataSet.isEmpty()) {
            visibleSeriesList = new ArrayList<>(dataSet.getSeriesNames());
        }
        
        for (String seriesName : dataSet.getSeriesNames()) {
            Color color = generateSeriesColor(dataSet.getSeriesNames().indexOf(seriesName));
            colorMap.put(seriesName, color);
        }
        
        plotPanel.setSeriesColors(colorMap);
        plotPanel.setVisibleSeries(visibleSeriesList);
    }
    
    private void updatePlotVisibility() {
        // Update only visibility without resetting viewport
        List<String> visibleSeriesList = dataPanel.getVisibleSeries();

        // If no series are marked as visible in data panel (initial state), show all series
        if (visibleSeriesList.isEmpty() && !dataSet.isEmpty()) {
            visibleSeriesList = new ArrayList<>(dataSet.getSeriesNames());
        }

        plotPanel.setVisibleSeries(visibleSeriesList);
    }

    private void setupDataManager() {
        // Create data manager after dataSet is initialized
        dataManager = new FlowVizDataManager(this, dataSet);

        dataManager.setupCallbacks(
            this::updateStatus,
            file -> currentFile = file,
            this::updateTitle,
            this::zoomToFit
        );
        dataManager.setupDragAndDrop();
    }

    // Menu action methods
    private void newSession() {
        // Clear all existing data
        dataManager.clearAllData();
        dataPanel.clearSeries();
        currentFile = null;
        updateTitle();
        updateStatus("Started new session - all data cleared");
    }
    
    private void openCsvFile() {
        dataManager.openCsvFiles();
    }
    
    
    
    
    
    
    private void exportPlot() {
        updateStatus("Export plot - Not yet implemented");
    }
    
    private void toggleData() {
        dataVisible = !dataVisible;
        if (dataVisible) {
            splitPane.setLeftComponent(dataPanel);
            splitPane.setDividerLocation(250);
        } else {
            splitPane.setLeftComponent(null);
            splitPane.setDividerLocation(0);
        }
        menuManager.updateMenuStates();
        updateStatus("Data panel " + (dataVisible ? "shown" : "hidden"));
    }
    
    private void zoomIn() {
        plotPanel.zoomIn();
        updateStatus("Zoomed in");
    }
    
    private void zoomOut() {
        plotPanel.zoomOut();
        updateStatus("Zoomed out");
    }
    
    private void zoomToFit() {
        plotPanel.zoomToFit();
        updateStatus("Zoomed to fit data");
    }
    
    private void toggleAutoYMode() {
        autoYMode = !autoYMode;
        plotPanel.setAutoYMode(autoYMode);
        menuManager.updateMenuStates();
        updateStatus(autoYMode ? "Auto-Y mode enabled" : "Auto-Y mode disabled");
    }

    private void toggleCoordinateDisplay() {
        boolean currentState = plotPanel.isShowCoordinates();
        boolean newState = !currentState;
        plotPanel.setShowCoordinates(newState);

        // Save preference
        prefs.putBoolean(AppConstants.PREF_FLOWVIZ_SHOW_COORDINATES, newState);

        updateStatus("Coordinate display " + (newState ? "enabled" : "disabled"));
    }
    
    private void resetView() {
        plotPanel.resetView();
        updateStatus("View reset");
    }
    
    private void showStatistics() {
        updateStatus("Statistics panel - Not yet implemented");
    }
    
    private void showDataInfo() {
        updateStatus("Data information - Not yet implemented");
    }
    
    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "FlowViz - Time Series Visualization Tool\n" +
            "Version 1.0\n\n" +
            "High-performance visualization for large datasets\n" +
            "Part of the Kalix Hydrologic Modeling Platform",
            "About FlowViz",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void showShortcuts() {
        JOptionPane.showMessageDialog(this,
            "FlowViz Keyboard & Mouse Shortcuts:\n\n" +
            "Ctrl+N - New session (clear all data)\n" +
            "Ctrl+O - Add CSV file to current session\n" +
            "Ctrl+W - Close window\n" +
            "L - Toggle data panel\n" +
            "+ - Zoom in\n" +
            "- - Zoom out\n" +
            "Mouse wheel - Zoom at cursor\n" +
            "Mouse drag - Pan view\n" +
            "Double-click plot - Reset zoom to fit data\n\n" +
            "Data Reordering:\n" +
            "Click data item - Select item\n" +
            "Cmd+↑/Ctrl+↑ - Move selected item up (toward background)\n" +
            "Cmd+↓/Ctrl+↓ - Move selected item down (toward foreground)\n\n" +
            "File Loading:\n" +
            "Drag & drop CSV files onto window - Load multiple files at once",
            "Keyboard & Mouse Shortcuts",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
    }

    /**
     * Loads preferences and applies them to the interface.
     */
    private void loadPreferences() {
        // Load coordinate display preference (default: false)
        boolean showCoordinates = prefs.getBoolean(AppConstants.PREF_FLOWVIZ_SHOW_COORDINATES, false);

        // Apply the setting to the plot panel
        plotPanel.setShowCoordinates(showCoordinates);

        // Load menu preferences
        menuManager.loadMenuPreferences();
    }

    public static void createNewWindow() {
        SwingUtilities.invokeLater(() -> new FlowVizWindow());
    }
    
    public static List<FlowVizWindow> getOpenWindows() {
        return new ArrayList<>(openWindows);
    }
    
    public static int getWindowCount() {
        return openWindows.size();
    }
}