package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlowVizWindow extends JFrame {
    private static final List<FlowVizWindow> openWindows = new ArrayList<>();
    private static int windowCounter = 0;
    
    private PlotPanel plotPanel;
    private DataPanel dataPanel;
    private JSplitPane splitPane;
    private JToolBar toolBar;
    // State variables have been moved to FlowVizActionManager
    
    // Data management
    private DataSet dataSet;
    private File currentFile;

    // Managers
    private final FlowVizMenuManager menuManager;
    private FlowVizDataManager dataManager;
    private FlowVizActionManager actionManager;
    
    public FlowVizWindow() {
        windowCounter++;
        setTitle("Kalix - Untitled " + windowCounter);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(600, 400);
        setLocationRelativeTo(null);

        // Initialize menu manager
        menuManager = new FlowVizMenuManager(this);

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
        setupDataModel();
        setupDataManager();
        setupActionManager();
        setupMenuBar();
        setupToolBar();
        loadPreferences();

        setVisible(true);
    }

    /**
     * Creates a new FlowViz window and pre-loads data from the specified file.
     * Supports CSV and Kalix timeseries (.kai) files.
     *
     * @param dataFile The file to load (CSV or KAI format)
     */
    public FlowVizWindow(File dataFile) {
        windowCounter++;
        setTitle("Kalix - Untitled " + windowCounter);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(600, 400);
        setLocationRelativeTo(null);

        // Initialize menu manager
        menuManager = new FlowVizMenuManager(this);

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
        setupDataModel();
        setupDataManager();
        setupActionManager();
        setupMenuBar();
        setupToolBar();
        loadPreferences();

        // Load the specified file after initialization
        if (dataFile != null && dataFile.exists()) {
            SwingUtilities.invokeLater(() -> dataManager.loadFile(dataFile));
        }

        setVisible(true);
    }
    
    private void initializeComponents() {
        plotPanel = new PlotPanel();
        // Auto-Y mode and precision will be initialized by action manager
        plotPanel.setAutoYMode(true);  // Default value, will be updated by loadPreferences
        plotPanel.setPrecision64Supplier(() -> actionManager == null || actionManager.isPrecision64());
        dataPanel = new DataPanel();
        dataPanel.setPreferredSize(new Dimension(250, 0));
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

        // Set up visibility change listener for data panel
        dataPanel.setVisibilityChangeListener(this::updatePlotVisibility);

        // Data manager will handle drag-and-drop
    }
    
    private void setupMenuBar() {
        // Setup action callbacks (delegated to action manager)
        menuManager.setupActionCallbacks(
            actionManager::newSession,
            actionManager::openCsvFile,
            actionManager::exportPlot,
            actionManager::toggleData,
            actionManager::toggleCoordinateDisplay,
            actionManager::zoomIn,
            actionManager::zoomOut,
            actionManager::zoomToFit,
            actionManager::resetView,
            actionManager::toggleAutoYMode,
            actionManager::showStatistics,
            actionManager::showDataInfo,
            actionManager::showAbout,
            actionManager::showShortcuts,
            actionManager::togglePrecision64
        );

        // Setup state suppliers (delegated to action manager)
        menuManager.setupStateSuppliers(
            actionManager::isDataVisible,
            actionManager::isAutoYMode,
            () -> plotPanel.isShowCoordinates(),
            actionManager::isPrecision64
        );

        // Create and set the menu bar
        JMenuBar menuBar = menuManager.createMenuBar();
        setJMenuBar(menuBar);

        // Setup keyboard shortcuts
        menuManager.setupKeyboardShortcuts();
    }

    private void setupToolBar() {
        toolBar = new JToolBar("FlowViz Tools");
        toolBar.setFloatable(false);
        toolBar.setRollover(true);

        // Open file button
        JButton openButton = new JButton("Open");
        openButton.setToolTipText("Open CSV file (Ctrl+O)");
        openButton.addActionListener(e -> actionManager.openCsvFile());
        toolBar.add(openButton);

        toolBar.addSeparator();

        // Zoom controls
        JButton zoomInButton = new JButton("Zoom In");
        zoomInButton.setToolTipText("Zoom in (+)");
        zoomInButton.addActionListener(e -> actionManager.zoomIn());
        toolBar.add(zoomInButton);

        JButton zoomOutButton = new JButton("Zoom Out");
        zoomOutButton.setToolTipText("Zoom out (-)");
        zoomOutButton.addActionListener(e -> actionManager.zoomOut());
        toolBar.add(zoomOutButton);

        JButton zoomFitButton = new JButton("Fit");
        zoomFitButton.setToolTipText("Zoom to fit all data (F)");
        zoomFitButton.addActionListener(e -> actionManager.zoomToFit());
        toolBar.add(zoomFitButton);

        JButton resetButton = new JButton("Reset");
        resetButton.setToolTipText("Reset view (R)");
        resetButton.addActionListener(e -> actionManager.resetView());
        toolBar.add(resetButton);

        toolBar.addSeparator();

        // Toggle buttons
        JToggleButton autoYButton = new JToggleButton("Auto Y");
        autoYButton.setToolTipText("Auto-scale Y axis during zoom (Y)");
        autoYButton.addActionListener(e -> actionManager.toggleAutoYMode());
        toolBar.add(autoYButton);

        JToggleButton coordButton = new JToggleButton("Coordinates");
        coordButton.setToolTipText("Show coordinates (C)");
        coordButton.addActionListener(e -> actionManager.toggleCoordinateDisplay());
        toolBar.add(coordButton);

        JToggleButton dataButton = new JToggleButton("Data Panel");
        dataButton.setToolTipText("Toggle data panel visibility (D)");
        dataButton.addActionListener(e -> actionManager.toggleData());
        toolBar.add(dataButton);

        toolBar.addSeparator();

        // Export button
        JButton exportButton = new JButton("Export");
        exportButton.setToolTipText("Export plot data (Ctrl+E)");
        exportButton.addActionListener(e -> actionManager.exportPlot());
        toolBar.add(exportButton);

        // Update toggle button states based on current settings
        // This will be called after loadPreferences()
        SwingUtilities.invokeLater(() -> {
            if (actionManager != null) {
                autoYButton.setSelected(actionManager.isAutoYMode());
                coordButton.setSelected(plotPanel.isShowCoordinates());
                dataButton.setSelected(actionManager.isDataVisible());
            }
        });

        // Add toolbar to the top of the window
        add(toolBar, BorderLayout.NORTH);
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

                // Add to legend
                plotPanel.addLegendSeries(series.getName(), seriesColor);

                updateTitle();
            }

            @Override
            public void onSeriesRemoved(String seriesName) {
                dataPanel.removeSeries(seriesName);
                updatePlotPanel();

                // Remove from legend
                plotPanel.removeLegendSeries(seriesName);

                updateTitle();
            }

            @Override
            public void onDataChanged() {
                updatePlotPanel();
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
        String title;

        // Detect if we have data from a single file by checking series names
        boolean isSingleFile = false;
        if (currentFile != null && dataSet.getSeriesCount() > 0) {
            // Check if all series are from the same file
            java.util.Set<String> fileNames = new java.util.HashSet<>();
            for (String seriesName : dataSet.getSeriesNames()) {
                // Series names are formatted as "filename: SeriesName"
                if (seriesName.contains(": ")) {
                    String fileName = seriesName.substring(0, seriesName.indexOf(": "));
                    fileNames.add(fileName);
                }
            }
            isSingleFile = fileNames.size() == 1;
        }

        if (isSingleFile) {
            title = currentFile.getName();
        } else {
            title = "Various Timeseries";
        }

        if (dataSet.getSeriesCount() > 0) {
            title += String.format(" (%d series)", dataSet.getSeriesCount());
        }

        setTitle("Kalix - " + title);
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
            file -> currentFile = file,
            this::updateTitle,
            () -> actionManager.zoomToFit()
        );
        dataManager.setupDragAndDrop();
    }

    private void setupActionManager() {
        // Create action manager with callbacks for parent communication
        actionManager = new FlowVizActionManager(
            plotPanel,
            dataPanel,
            splitPane,
            menuManager,
            dataManager,
            this::updateTitle,
            file -> currentFile = file
        );
    }

    /**
     * Loads preferences and applies them to the interface.
     */
    private void loadPreferences() {
        // Load coordinate display preference (default: false)
        boolean showCoordinates = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, false);
        plotPanel.setShowCoordinates(showCoordinates);

        // Other preferences are loaded by the action manager

        // Load menu preferences
        menuManager.loadMenuPreferences();

        // Update all menu states to reflect loaded preferences
        menuManager.updateMenuStates();
    }

    /**
     * Reloads all preferences and updates the UI accordingly.
     * This method is called when preferences change externally.
     */
    public void reloadPreferences() {
        loadPreferences();

        // Also reload action manager preferences
        if (actionManager != null) {
            actionManager.reloadPreferences();
        }
    }

    public static void createNewWindow() {
        SwingUtilities.invokeLater(() -> new FlowVizWindow());
    }

    /**
     * Creates a new FlowViz window pre-loaded with data from the specified file.
     *
     * @param dataFile The file to load (CSV or KAI format)
     */
    public static void createWindowWithFile(File dataFile) {
        SwingUtilities.invokeLater(() -> new FlowVizWindow(dataFile));
    }

    public static List<FlowVizWindow> getOpenWindows() {
        return new ArrayList<>(openWindows);
    }
}