package com.kalix.gui.flowviz;

import com.kalix.gui.flowviz.data.CsvParser;
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

public class FlowVizWindow extends JFrame {
    private static final List<FlowVizWindow> openWindows = new ArrayList<>();
    private static int windowCounter = 0;
    
    private PlotPanel plotPanel;
    private LegendPanel legendPanel;
    private JLabel statusLabel;
    private JSplitPane splitPane;
    private boolean legendVisible = true;
    private boolean autoYMode = false;
    
    // Data management
    private DataSet dataSet;
    private File currentFile;
    
    public FlowVizWindow() {
        windowCounter++;
        setTitle("FlowViz - Untitled " + windowCounter);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
        
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
        
        setVisible(true);
    }
    
    private void initializeComponents() {
        plotPanel = new PlotPanel();
        legendPanel = new LegendPanel();
        legendPanel.setPreferredSize(new Dimension(250, 0));
        
        statusLabel = new JLabel("Ready - No data loaded");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Create split pane with legend on left, plot on right
        splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            legendPanel,
            plotPanel
        );
        splitPane.setDividerLocation(250);
        splitPane.setResizeWeight(0.0); // Keep legend fixed width
        
        add(splitPane, BorderLayout.CENTER);
        
        // Status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusPanel, BorderLayout.SOUTH);
        
        // Set up visibility change listener for legend
        legendPanel.setVisibilityChangeListener(this::updatePlotVisibility);
        
        // Set up drag-and-drop file loading
        setupDragAndDrop();
    }
    
    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(createMenuItem("New", e -> newSession()));
        fileMenu.add(createMenuItem("Add CSV...", e -> openCsvFile()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Export Plot...", e -> exportPlot()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Close", e -> dispose()));
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem legendToggle = new JCheckBoxMenuItem("Show Legend", legendVisible);
        legendToggle.addActionListener(e -> toggleLegend());
        viewMenu.add(legendToggle);
        
        // Zoom menu
        JMenu zoomMenu = new JMenu("Zoom");
        zoomMenu.add(createMenuItem("Zoom In", e -> zoomIn()));
        zoomMenu.add(createMenuItem("Zoom Out", e -> zoomOut()));
        zoomMenu.addSeparator();
        zoomMenu.add(createMenuItem("Zoom to Fit", e -> zoomToFit()));
        zoomMenu.add(createMenuItem("Reset View", e -> resetView()));
        zoomMenu.addSeparator();
        
        JCheckBoxMenuItem autoYToggle = new JCheckBoxMenuItem("Auto-Y", autoYMode);
        autoYToggle.addActionListener(e -> toggleAutoYMode());
        zoomMenu.add(autoYToggle);
        
        // Tools menu
        JMenu toolsMenu = new JMenu("Tools");
        toolsMenu.add(createMenuItem("Statistics", e -> showStatistics()));
        toolsMenu.add(createMenuItem("Data Info", e -> showDataInfo()));
        
        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem("About FlowViz", e -> showAbout()));
        helpMenu.add(createMenuItem("Keyboard Shortcuts", e -> showShortcuts()));
        
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(zoomMenu);
        menuBar.add(toolsMenu);
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
        
        // Setup keyboard shortcuts
        setupKeyboardShortcuts();
    }
    
    private void setupKeyboardShortcuts() {
        // Ctrl+N for New
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("ctrl N"), "newSession");
        getRootPane().getActionMap().put("newSession", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newSession();
            }
        });
        
        // Ctrl+O for Add CSV
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("ctrl O"), "openFile");
        getRootPane().getActionMap().put("openFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openCsvFile();
            }
        });
        
        // Ctrl+W for Close
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("ctrl W"), "closeWindow");
        getRootPane().getActionMap().put("closeWindow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        
        // L for toggle legend
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("L"), "toggleLegend");
        getRootPane().getActionMap().put("toggleLegend", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleLegend();
            }
        });
        
        // Plus/Minus for zoom
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("PLUS"), "zoomIn");
        getRootPane().getActionMap().put("zoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomIn();
            }
        });
        
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("MINUS"), "zoomOut");
        getRootPane().getActionMap().put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomOut();
            }
        });
    }
    
    private JMenuItem createMenuItem(String text, ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        return item;
    }
    
    private void setupDataModel() {
        dataSet = new DataSet();
        
        // Set up data change listeners
        dataSet.addListener(new DataSet.DataSetListener() {
            @Override
            public void onSeriesAdded(TimeSeriesData series) {
                // Generate color for the series (cycle through predefined colors)
                Color seriesColor = generateSeriesColor(dataSet.getSeriesCount() - 1);
                
                System.out.println("DEBUG: Adding series to legend: '" + series.getName() + "' with " + series.getPointCount() + " points");
                legendPanel.addSeries(series.getName(), seriesColor, series.getPointCount());
                
                // Update plot panel with new data
                updatePlotPanel();
                
                updateTitle();
                updateStatus(String.format("Added series '%s' with %,d points", 
                    series.getName(), series.getPointCount()));
            }
            
            @Override
            public void onSeriesRemoved(String seriesName) {
                legendPanel.removeSeries(seriesName);
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
        List<String> visibleSeriesList = legendPanel.getVisibleSeries();
        
        // If no series are marked as visible in legend (initial state), show all series
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
        List<String> visibleSeriesList = legendPanel.getVisibleSeries();
        
        // If no series are marked as visible in legend (initial state), show all series
        if (visibleSeriesList.isEmpty() && !dataSet.isEmpty()) {
            visibleSeriesList = new ArrayList<>(dataSet.getSeriesNames());
        }
        
        plotPanel.setVisibleSeries(visibleSeriesList);
    }

    // Menu action methods
    private void newSession() {
        // Clear all existing data
        dataSet.removeAllSeries();
        legendPanel.clearSeries();
        currentFile = null;
        updateTitle();
        updateStatus("Started new session - all data cleared");
    }
    
    private void openCsvFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        fileChooser.setMultiSelectionEnabled(true);  // Enable multi-select
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();
            
            if (selectedFiles.length == 1) {
                // Single file - use existing method
                loadCsvFile(selectedFiles[0]);
            } else if (selectedFiles.length > 1) {
                // Multiple files - load them sequentially
                loadMultipleCsvFiles(selectedFiles);
            }
        }
    }
    
    private void loadCsvFile(File csvFile) {
        updateStatus("Loading CSV file...");
        
        // Create progress dialog
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Parsing CSV...");
        
        JDialog progressDialog = new JDialog(this, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + csvFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);
        
        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);
        
        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(this);
        
        // Create CSV parse task
        CsvParser.CsvParseTask parseTask = new CsvParser.CsvParseTask(csvFile, new CsvParser.CsvParseOptions()) {
            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int progress = chunks.get(chunks.size() - 1);
                    progressBar.setValue(progress);
                    progressBar.setString(String.format("Parsing CSV... %d%%", progress));
                }
            }
            
            @Override
            protected void done() {
                progressDialog.dispose();
                
                try {
                    CsvParser.CsvParseResult parseResult = get();
                    handleParseResult(csvFile, parseResult);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(FlowVizWindow.this,
                        "Error loading CSV file:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    updateStatus("Error loading CSV file");
                }
            }
        };
        
        cancelButton.addActionListener(e -> {
            parseTask.cancel(true);
            progressDialog.dispose();
            updateStatus("CSV loading cancelled");
        });
        
        parseTask.execute();
        progressDialog.setVisible(true);
    }
    
    private void loadMultipleCsvFiles(File[] csvFiles) {
        updateStatus("Loading " + csvFiles.length + " CSV files...");
        
        // Create progress dialog for multiple files
        JProgressBar progressBar = new JProgressBar(0, csvFiles.length);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading files: 0 of " + csvFiles.length);
        
        JDialog progressDialog = new JDialog(this, "Loading Multiple Files", true);
        progressDialog.setLayout(new BorderLayout());
        progressDialog.add(new JLabel("Loading CSV files...", JLabel.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);
        
        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);
        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(this);
        
        // Load files sequentially in background thread
        SwingWorker<Void, Integer> multiLoadTask = new SwingWorker<Void, Integer>() {
            private volatile boolean cancelled = false;
            
            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i < csvFiles.length && !cancelled && !isCancelled(); i++) {
                    final int fileIndex = i;
                    final File csvFile = csvFiles[i];
                    
                    publish(fileIndex);
                    
                    // Parse file synchronously
                    CsvParser.CsvParseTask parseTask = new CsvParser.CsvParseTask(csvFile, new CsvParser.CsvParseOptions()) {
                        @Override
                        protected void done() {
                            try {
                                CsvParser.CsvParseResult result = get();
                                SwingUtilities.invokeLater(() -> {
                                    handleParseResult(csvFile, result);
                                });
                            } catch (Exception e) {
                                SwingUtilities.invokeLater(() -> {
                                    JOptionPane.showMessageDialog(FlowVizWindow.this,
                                        "Failed to load " + csvFile.getName() + ": " + e.getMessage(),
                                        "Load Error", JOptionPane.ERROR_MESSAGE);
                                });
                            }
                        }
                    };
                    
                    try {
                        // Execute and wait for completion
                        parseTask.execute();
                        parseTask.get(); // Wait for completion
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(FlowVizWindow.this,
                                "Failed to load " + csvFile.getName() + ": " + e.getMessage(),
                                "Load Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }
                return null;
            }
            
            @Override
            protected void process(java.util.List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int fileIndex = chunks.get(chunks.size() - 1);
                    progressBar.setValue(fileIndex);
                    progressBar.setString("Loading files: " + (fileIndex + 1) + " of " + csvFiles.length);
                }
            }
            
            @Override
            protected void done() {
                progressDialog.dispose();
                updateStatus("Loaded " + csvFiles.length + " CSV files");
            }
            
            public void cancel() {
                cancelled = true;
                cancel(true);
            }
        };
        
        cancelButton.addActionListener(e -> {
            multiLoadTask.cancel(true);
            progressDialog.dispose();
            updateStatus("File loading cancelled");
        });
        
        multiLoadTask.execute();
        progressDialog.setVisible(true);
    }
    
    private void handleParseResult(File csvFile, CsvParser.CsvParseResult parseResult) {
        if (parseResult.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder("Failed to load CSV file:\n\n");
            for (String error : parseResult.getErrors()) {
                errorMessage.append("• ").append(error).append("\n");
            }
            
            JOptionPane.showMessageDialog(this, errorMessage.toString(), 
                "CSV Load Error", JOptionPane.ERROR_MESSAGE);
            updateStatus("Failed to load CSV file");
            return;
        }
        
        // Add new data (don't clear existing data)
        String fileName = csvFile.getName();
        
        for (TimeSeriesData series : parseResult.getDataSet().getAllSeries()) {
            // Create display name: "filename.csv: ColumnName"
            String columnName = series.getName();
            String displayName = fileName + ": " + columnName;
            String uniqueName = getUniqueSeriesName(displayName);
            
            // Create new series with the display name
            TimeSeriesData namedSeries = new TimeSeriesData(
                uniqueName, 
                convertTimestampsToLocalDateTime(series.getTimestamps()),
                series.getValues()
            );
            dataSet.addSeries(namedSeries);
        }
        
        currentFile = csvFile;
        updateTitle();
        
        // Show warnings if any
        if (parseResult.hasWarnings()) {
            StringBuilder warningMessage = new StringBuilder("CSV loaded successfully with warnings:\n\n");
            for (String warning : parseResult.getWarnings()) {
                warningMessage.append("• ").append(warning).append("\n");
            }
            
            JOptionPane.showMessageDialog(this, warningMessage.toString(),
                "Load Warnings", JOptionPane.WARNING_MESSAGE);
        }
        
        // Update status with statistics
        CsvParser.ParseStatistics stats = parseResult.getStatistics();
        int newSeriesCount = parseResult.getDataSet().getSeriesCount();
        updateStatus(String.format("Added %d new series (%,d total series, %,d total points) in %d ms", 
            newSeriesCount, dataSet.getSeriesCount(), dataSet.getTotalPointCount(), stats.getParseTimeMs()));
        
        // Zoom to fit all data (including existing + new)
        zoomToFit();
    }
    
    private String getUniqueSeriesName(String baseName) {
        if (!dataSet.hasSeries(baseName)) {
            return baseName;
        }
        
        // Find unique name by appending number
        int counter = 2;
        String candidateName;
        do {
            candidateName = baseName + " (" + counter + ")";
            counter++;
        } while (dataSet.hasSeries(candidateName));
        
        return candidateName;
    }
    
    private java.time.LocalDateTime[] convertTimestampsToLocalDateTime(long[] timestamps) {
        java.time.LocalDateTime[] result = new java.time.LocalDateTime[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            result[i] = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]), 
                java.time.ZoneOffset.UTC);
        }
        return result;
    }
    
    private void exportPlot() {
        updateStatus("Export plot - Not yet implemented");
    }
    
    private void toggleLegend() {
        legendVisible = !legendVisible;
        if (legendVisible) {
            splitPane.setLeftComponent(legendPanel);
            splitPane.setDividerLocation(250);
        } else {
            splitPane.setLeftComponent(null);
            splitPane.setDividerLocation(0);
        }
        updateStatus("Legend " + (legendVisible ? "shown" : "hidden"));
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
        updateStatus(autoYMode ? "Auto-Y mode enabled" : "Auto-Y mode disabled");
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
            "L - Toggle legend\n" +
            "+ - Zoom in\n" +
            "- - Zoom out\n" +
            "Mouse wheel - Zoom at cursor\n" +
            "Mouse drag - Pan view\n" +
            "Double-click plot - Reset zoom to fit data\n\n" +
            "Legend Reordering:\n" +
            "Click legend item - Select item\n" +
            "Cmd+↑/Ctrl+↑ - Move selected item up (toward background)\n" +
            "Cmd+↓/Ctrl+↓ - Move selected item down (toward foreground)\n\n" +
            "File Loading:\n" +
            "Drag & drop CSV files onto window - Load multiple files at once",
            "Keyboard & Mouse Shortcuts",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void setupDragAndDrop() {
        // Enable drag-and-drop for CSV files on the entire window
        new DropTarget(this, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    updateStatus("Drop CSV files to load them...");
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragExit(DropTargetEvent dte) {
                updateStatus("Ready");
            }
            
            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void drop(DropTargetDropEvent dtde) {
                if (isDropAcceptable(dtde)) {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    
                    try {
                        Transferable transferable = dtde.getTransferable();
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        
                        // Filter for CSV files
                        List<File> csvFiles = files.stream()
                            .filter(file -> file.getName().toLowerCase().endsWith(".csv"))
                            .toList();
                        
                        if (csvFiles.isEmpty()) {
                            updateStatus("No CSV files found in drop");
                            JOptionPane.showMessageDialog(FlowVizWindow.this,
                                "Please drop CSV files only.", 
                                "Invalid File Type", 
                                JOptionPane.WARNING_MESSAGE);
                        } else if (csvFiles.size() == 1) {
                            // Single file - use existing method
                            loadCsvFile(csvFiles.get(0));
                        } else {
                            // Multiple files - use batch loading method
                            loadMultipleCsvFiles(csvFiles.toArray(new File[0]));
                        }
                        
                        dtde.dropComplete(true);
                    } catch (Exception e) {
                        dtde.dropComplete(false);
                        updateStatus("Failed to load dropped files");
                        JOptionPane.showMessageDialog(FlowVizWindow.this,
                            "Failed to load dropped files: " + e.getMessage(),
                            "Drop Error",
                            JOptionPane.ERROR_MESSAGE);
                    }
                } else {
                    dtde.rejectDrop();
                }
            }
            
            private boolean isDragAcceptable(DropTargetDragEvent dtde) {
                return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            
            private boolean isDropAcceptable(DropTargetDropEvent dtde) {
                return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
        });
    }
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
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