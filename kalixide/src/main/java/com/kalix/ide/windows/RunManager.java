package com.kalix.ide.windows;

import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.diff.DiffWindow;
import com.kalix.ide.io.TimeSeriesCsvImporter;
import com.kalix.ide.io.KalixTimeSeriesReader;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * User-friendly Run Manager window for managing model runs.
 * Replaces the debugging-focused CLI Sessions window with a more intuitive interface.
 */
public class RunManager extends JFrame {

    private static final Logger logger = LoggerFactory.getLogger(RunManager.class);

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private static RunManager instance;
    private static java.util.function.Supplier<java.io.File> baseDirectorySupplier;
    private static java.util.function.Supplier<String> editorTextSupplier;

    // Tree components
    private JTree timeseriesSourceTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode lastRunNode;
    private DefaultMutableTreeNode currentRunsNode;
    private DefaultMutableTreeNode libraryNode;
    private DefaultMutableTreeNode loadedDatasetsNode;

    // Timeseries tree components
    private JTree timeseriesTree;
    private DefaultTreeModel timeseriesTreeModel;
    private JScrollPane timeseriesScrollPane;

    // Visualization tab manager
    private VisualizationTabManager tabManager;
    private DataSet plotDataSet;

    // Cache for loaded dataset series (base names without display suffix)
    // Key: base series name (e.g., "file.mydata_csv.column1")
    // Value: TimeSeriesData
    // This mirrors how runs store data in TimeSeriesRequestManager cache
    private final Map<String, TimeSeriesData> datasetSeriesCache = new HashMap<>();

    // Series color management
    // Categorical 10 color palette - optimized for visibility and distinction
    private static final Color[] SERIES_COLORS = {
        new Color(0x1f77b4),  // Blue
        new Color(0xff7f0e),  // Orange
        new Color(0x2ca02c),  // Green
        new Color(0xd62728),  // Red
        new Color(0x9467bd),  // Purple
        new Color(0x8c564b),  // Brown
        new Color(0xe377c2),  // Pink
        new Color(0x7f7f7f),  // Gray
        new Color(0xbcbd22),  // Yellow-green
        new Color(0x17becf)   // Cyan
    };
    private Map<String, Color> seriesColorMap = new HashMap<>();
    private Set<String> selectedSeries = new HashSet<>();

    // Run tracking
    private Map<String, String> sessionToRunName = new HashMap<>();
    private Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private Map<String, RunStatus> lastKnownStatus = new HashMap<>();
    private int runCounter = 1;

    // Last run tracking
    private RunInfo lastRunInfo = null;
    private DefaultMutableTreeNode lastRunChildNode = null;
    private Map<String, Long> completionTimestamps = new HashMap<>();
    private long lastRunCompletionTime = 0L;

    // Flag to prevent selection listener from firing during programmatic updates
    private boolean isUpdatingSelection = false;

    /**
     * Natural sorting comparator that is case-insensitive and number-aware.
     * Compares strings by splitting them into text and numeric parts.
     * Text parts are compared case-insensitively, numeric parts as integers.
     *
     * Examples:
     * - "node1" < "node2" < "node10" (numeric comparison)
     * - "Node1" == "node1" (case-insensitive)
     * - "node1_inflow" < "node10_gauge" (numeric-aware)
     */
    private static int naturalCompare(String s1, String s2) {
        int i1 = 0, i2 = 0;
        int len1 = s1.length(), len2 = s2.length();

        while (i1 < len1 && i2 < len2) {
            // Determine if current characters are digits
            boolean isDigit1 = Character.isDigit(s1.charAt(i1));
            boolean isDigit2 = Character.isDigit(s2.charAt(i2));

            if (isDigit1 && isDigit2) {
                // Both are digits - extract and compare as numbers
                int numStart1 = i1, numStart2 = i2;
                while (i1 < len1 && Character.isDigit(s1.charAt(i1))) i1++;
                while (i2 < len2 && Character.isDigit(s2.charAt(i2))) i2++;

                String numStr1 = s1.substring(numStart1, i1);
                String numStr2 = s2.substring(numStart2, i2);

                // Compare as integers (handle large numbers gracefully)
                int cmp;
                try {
                    long num1 = Long.parseLong(numStr1);
                    long num2 = Long.parseLong(numStr2);
                    cmp = Long.compare(num1, num2);
                } catch (NumberFormatException e) {
                    // Fall back to string comparison if numbers too large
                    cmp = numStr1.compareTo(numStr2);
                }

                if (cmp != 0) return cmp;
            } else if (isDigit1) {
                // Digits come before non-digits
                return -1;
            } else if (isDigit2) {
                // Non-digits come after digits
                return 1;
            } else {
                // Both are non-digits - compare case-insensitively
                char c1 = Character.toLowerCase(s1.charAt(i1));
                char c2 = Character.toLowerCase(s2.charAt(i2));
                if (c1 != c2) {
                    return Character.compare(c1, c2);
                }
                i1++;
                i2++;
            }
        }

        // One string is a prefix of the other
        return Integer.compare(len1, len2);
    }

    /**
     * Private constructor for singleton pattern.
     */
    private RunManager(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.timeSeriesRequestManager = new TimeSeriesRequestManager(stdioTaskManager.getSessionManager());

        // Connect TimeSeriesRequestManager to SessionManager for response handling
        stdioTaskManager.getSessionManager().setTimeSeriesResponseHandler(
            timeSeriesRequestManager::handleJsonResponse
        );

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
        setupSessionEventListener();
        setupDragAndDrop();
    }

    /**
     * Shows the Run Manager window using singleton pattern.
     */
    public static void showRunManager(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        if (instance == null) {
            instance = new RunManager(parentFrame, stdioTaskManager, statusUpdater);
        }

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
        instance.refreshRuns();
    }

    /**
     * Sets the base directory supplier for file dialogs.
     * This should be called to provide the model's directory for saving results.
     *
     * @param supplier Supplier that returns the base directory (null if no file is loaded)
     */
    public static void setBaseDirectorySupplier(java.util.function.Supplier<java.io.File> supplier) {
        baseDirectorySupplier = supplier;
    }

    /**
     * Sets the editor text supplier for diff operations.
     * This should be called to provide access to the main editor's text.
     *
     * @param supplier Supplier that returns the current editor text (null if no text is loaded)
     */
    public static void setEditorTextSupplier(java.util.function.Supplier<String> supplier) {
        editorTextSupplier = supplier;
    }

    /**
     * Checks if the Run Manager window is currently open.
     */
    public static boolean isWindowOpen() {
        return instance != null && instance.isVisible();
    }

    /**
     * Refreshes the Run Manager if it's open.
     */
    public static void refreshRunManagerIfOpen() {
        if (instance != null) {
            instance.refreshRuns();
        }
    }

    /**
     * Selects the run associated with the given session key if the Run Manager is open.
     */
    public static void selectRunIfOpen(String sessionKey) {
        if (instance != null && instance.isVisible()) {
            instance.selectRun(sessionKey);
        }
    }

    /**
     * Gets the Run Manager instance if it exists and is open.
     * Used by ThemeManager for theme updates.
     */
    public static RunManager getOpenInstance() {
        return (instance != null && instance.isVisible()) ? instance : null;
    }

    /**
     * Gets the run name for a session key, if available.
     */
    public static String getRunNameForSession(String sessionKey) {
        if (instance != null) {
            return instance.sessionToRunName.get(sessionKey);
        }
        return null;
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Run Manager");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1100, 600);

        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 50, parentLocation.y + 50);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // Initialize tree structure
        rootNode = new DefaultMutableTreeNode("Runs");
        lastRunNode = new DefaultMutableTreeNode("Last run");
        currentRunsNode = new DefaultMutableTreeNode("Current runs");
        libraryNode = new DefaultMutableTreeNode("Run library");
        loadedDatasetsNode = new DefaultMutableTreeNode("Loaded datasets");

        rootNode.add(lastRunNode);
        rootNode.add(currentRunsNode);
        rootNode.add(libraryNode);
        rootNode.add(loadedDatasetsNode);

        treeModel = new DefaultTreeModel(rootNode);
        timeseriesSourceTree = new JTree(treeModel) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) {
                    return null;
                }
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath == null) {
                    return null;
                }
                Object component = curPath.getLastPathComponent();

                if (component instanceof DefaultMutableTreeNode) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) component;
                    Object userObject = node.getUserObject();

                    if (userObject instanceof RunInfo) {
                        RunInfo runInfo = (RunInfo) userObject;
                        String uid = runInfo.session.getKalixcliUid();
                        return uid;
                    } else if (userObject instanceof LoadedDatasetInfo) {
                        LoadedDatasetInfo datasetInfo = (LoadedDatasetInfo) userObject;
                        return datasetInfo.file.getAbsolutePath();
                    }
                }
                return null;
            }
        };
        timeseriesSourceTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        timeseriesSourceTree.setRootVisible(false);
        timeseriesSourceTree.setShowsRootHandles(true);
        timeseriesSourceTree.setCellRenderer(new RunTreeCellRenderer());

        // Enable tooltips for the tree
        ToolTipManager.sharedInstance().registerComponent(timeseriesSourceTree);

        // Expand the tree nodes by default
        timeseriesSourceTree.expandPath(new TreePath(lastRunNode.getPath()));
        timeseriesSourceTree.expandPath(new TreePath(currentRunsNode.getPath()));
        timeseriesSourceTree.expandPath(new TreePath(libraryNode.getPath()));
        timeseriesSourceTree.expandPath(new TreePath(loadedDatasetsNode.getPath()));

        // Add tree selection listener to update details panel with outputs
        timeseriesSourceTree.addTreeSelectionListener(this::onRunTreeSelectionChanged);

        // Add context menu
        setupContextMenu();

        // Initialize visualization components
        createDetailsComponents();
    }

    private void createDetailsComponents() {
        // Create timeseries tree
        DefaultMutableTreeNode outputsRootNode = new DefaultMutableTreeNode("Outputs");
        timeseriesTreeModel = new DefaultTreeModel(outputsRootNode);
        timeseriesTree = new JTree(timeseriesTreeModel);
        timeseriesTree.setRootVisible(false);
        timeseriesTree.setShowsRootHandles(true);
        timeseriesTree.setCellRenderer(new OutputsTreeCellRenderer());

        // Enable multiple selection for the timeseries tree to allow plotting multiple series
        timeseriesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);

        // Add selection listener to fetch timeseries data for leaf nodes and update plot
        timeseriesTree.addTreeSelectionListener(this::onOutputsTreeSelectionChanged);

        // Add context menu for expand/collapse all
        setupOutputsTreeContextMenu();

        timeseriesScrollPane = new JScrollPane(timeseriesTree);

        // Create shared dataset and color map
        plotDataSet = new DataSet();

        // Create tab manager with shared data
        tabManager = new VisualizationTabManager(plotDataSet, seriesColorMap);

        // Add default tabs (settings are applied by the tab manager)
        tabManager.addPlotTab();
        tabManager.addStatsTab();
    }


    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(240);
        splitPane.setResizeWeight(0);

        // Left side: vertical split with timeseries source tree and timeseries tree
        JSplitPane leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        leftSplitPane.setDividerLocation(200);
        leftSplitPane.setResizeWeight(0.5);

        // Top of left side: timeseries source tree
        JPanel runsPanel = new JPanel(new BorderLayout());
        runsPanel.setBorder(BorderFactory.createTitledBorder("Kalix"));
        JScrollPane treeScrollPane = new JScrollPane(timeseriesSourceTree);
        runsPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Bottom of left side: timeseries tree
        JPanel timeseriesPanel = new JPanel(new BorderLayout());
        timeseriesPanel.setBorder(BorderFactory.createTitledBorder("Timeseries"));
        timeseriesPanel.add(timeseriesScrollPane, BorderLayout.CENTER);

        leftSplitPane.setTopComponent(runsPanel);
        leftSplitPane.setBottomComponent(timeseriesPanel);

        // Right side: tabbed pane for visualizations
        splitPane.setLeftComponent(leftSplitPane);
        splitPane.setRightComponent(tabManager.getTabbedPane());

        add(splitPane, BorderLayout.CENTER);
    }

    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                // Event-based updates, no timer needed
                refreshRuns();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                // Cleanup singleton instance
                instance = null;
            }

            @Override
            public void windowIconified(WindowEvent e) {
                // Event-based updates continue in background
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                // Refresh to show any changes that occurred while minimized
                refreshRuns();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                refreshRuns();
            }
        });
    }

    /**
     * Sets up event-based session monitoring instead of polling.
     * Subscribes to SessionManager events for immediate notification of state changes.
     */
    private void setupSessionEventListener() {
        // Subscribe to session events from SessionManager
        stdioTaskManager.getSessionManager().addSessionEventListener(event -> {
            // Marshal to EDT for UI updates
            SwingUtilities.invokeLater(() -> handleSessionEvent(event));
        });

        // Do initial population of runs
        refreshRuns();
    }

    /**
     * Handles session state change events from SessionManager.
     * Called on EDT after marshaling from background thread.
     *
     * @param event The session event containing state change information
     */
    private void handleSessionEvent(SessionManager.SessionEvent event) {
        String sessionKey = event.getSessionKey();
        SessionManager.SessionState newState = event.getNewState();

        // Get the session to check if it's a RunModelProgram
        Optional<SessionManager.KalixSession> sessionOpt = stdioTaskManager.getSessionManager().getSession(sessionKey);
        if (sessionOpt.isEmpty()) {
            return;
        }

        SessionManager.KalixSession session = sessionOpt.get();

        // Only handle RunModelProgram sessions (filter out optimisation, etc.)
        if (!(session.getActiveProgram() instanceof RunModelProgram)) {
            return;
        }

        // Check if we need to refresh the tree (new session or state changed to READY/ERROR/TERMINATED)
        if (!sessionToTreeNode.containsKey(sessionKey) ||
            newState == SessionManager.SessionState.READY ||
            newState == SessionManager.SessionState.ERROR ||
            newState == SessionManager.SessionState.TERMINATED) {

            // Refresh the runs tree to show the new/updated session
            refreshRuns();
        }
    }

    /**
     * Sets up the context menu for the run tree.
     */
    private void setupContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(e -> renameRunFromContextMenu());
        contextMenu.add(renameItem);

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeRunFromContextMenu());
        contextMenu.add(removeItem);

        contextMenu.addSeparator();

        JMenuItem saveResultsItem = new JMenuItem("Save results (csv)");
        saveResultsItem.addActionListener(e -> saveResultsFromContextMenu());
        contextMenu.add(saveResultsItem);

        JMenuItem showModelItem = new JMenuItem("Show Model");
        showModelItem.addActionListener(e -> showModelFromContextMenu());
        contextMenu.add(showModelItem);

        JMenuItem diffItem = new JMenuItem("Show Model Changes");
        diffItem.addActionListener(e -> diffModelFromContextMenu());
        contextMenu.add(diffItem);

        JMenuItem sessionManagerItem = new JMenuItem("View in KalixCLI Session Manager");
        sessionManagerItem.addActionListener(e -> showInSessionManagerFromContextMenu());
        contextMenu.add(sessionManagerItem);

        // Add mouse listener for right-click
        timeseriesSourceTree.addMouseListener(new MouseAdapter() {
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
                // Get the path at the mouse location
                TreePath path = timeseriesSourceTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    // Select the node that was right-clicked
                    timeseriesSourceTree.setSelectionPath(path);

                    // Check if it's a run item (not a folder)
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof RunInfo) {
                        contextMenu.show(timeseriesSourceTree, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    /**
     * Sets up the context menu for the timeseries tree with expand/collapse operations.
     */
    private void setupOutputsTreeContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem expandAllItem = new JMenuItem("Expand All");
        expandAllItem.addActionListener(e -> expandAllFromSelected());
        contextMenu.add(expandAllItem);

        JMenuItem collapseAllItem = new JMenuItem("Collapse All");
        collapseAllItem.addActionListener(e -> collapseAllFromSelected());
        contextMenu.add(collapseAllItem);

        // Add mouse listener for right-click
        timeseriesTree.addMouseListener(new MouseAdapter() {
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
                // Get the path at the mouse location
                TreePath path = timeseriesTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    // Select the node that was right-clicked if not already selected
                    if (!timeseriesTree.isPathSelected(path)) {
                        timeseriesTree.setSelectionPath(path);
                    }
                    contextMenu.show(timeseriesTree, e.getX(), e.getY());
                } else {
                    // Right-clicked on empty space - still show menu (applies to root)
                    contextMenu.show(timeseriesTree, e.getX(), e.getY());
                }
            }
        });
    }

    /**
     * Sets up drag-and-drop functionality for loading dataset files.
     * Supports CSV files and Kalix compressed timeseries files (KAI/KAZ).
     */
    private void setupDragAndDrop() {
        new java.awt.dnd.DropTarget(this, new java.awt.dnd.DropTargetListener() {
            @Override
            public void dragEnter(java.awt.dnd.DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Drop CSV or KAI/KAZ files to load them...");
                    }
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragOver(java.awt.dnd.DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragExit(java.awt.dnd.DropTargetEvent dte) {
                if (statusUpdater != null) {
                    statusUpdater.accept("Ready");
                }
            }

            @Override
            public void dropActionChanged(java.awt.dnd.DropTargetDragEvent dtde) {
                if (isDragAcceptable(dtde)) {
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void drop(java.awt.dnd.DropTargetDropEvent dtde) {
                if (isDropAcceptable(dtde)) {
                    dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);

                    try {
                        java.awt.datatransfer.Transferable transferable = dtde.getTransferable();
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);

                        // Filter for supported files (CSV, KAI, and KAZ)
                        List<File> supportedFiles = files.stream()
                            .filter(file -> {
                                String name = file.getName().toLowerCase();
                                return name.endsWith(".csv") || name.endsWith(".kai") || name.endsWith(".kaz");
                            })
                            .toList();

                        if (supportedFiles.isEmpty()) {
                            if (statusUpdater != null) {
                                statusUpdater.accept("No supported files found in drop");
                            }
                            JOptionPane.showMessageDialog(RunManager.this,
                                "Please drop CSV or KAI/KAZ files only.",
                                "Invalid File Type",
                                JOptionPane.WARNING_MESSAGE);
                        } else {
                            // Load all dropped files
                            for (File file : supportedFiles) {
                                loadDatasetFile(file);
                            }
                        }

                        dtde.dropComplete(true);
                    } catch (Exception e) {
                        dtde.dropComplete(false);
                        if (statusUpdater != null) {
                            statusUpdater.accept("Failed to load dropped files");
                        }
                        JOptionPane.showMessageDialog(RunManager.this,
                            "Failed to load dropped files: " + e.getMessage(),
                            "Drop Error",
                            JOptionPane.ERROR_MESSAGE);
                        logger.error("Failed to load dropped files", e);
                    }
                } else {
                    dtde.rejectDrop();
                }
            }

            private boolean isDragAcceptable(java.awt.dnd.DropTargetDragEvent dtde) {
                return dtde.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            private boolean isDropAcceptable(java.awt.dnd.DropTargetDropEvent dtde) {
                return dtde.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
        });
    }

    /**
     * Loads a dataset file (CSV or KAI/KAZ) and adds it to the loaded datasets tree.
     * Automatically detects file type and uses the appropriate importer.
     *
     * @param file The dataset file to load (.csv, .kai, or .kaz)
     */
    private void loadDatasetFile(File file) {
        // Check if this file is already loaded
        if (isFileAlreadyLoaded(file)) {
            logger.info("File already loaded, skipping: {}", file.getName());
            if (statusUpdater != null) {
                statusUpdater.accept("File already loaded: " + file.getName());
            }
            JOptionPane.showMessageDialog(this,
                "This file is already loaded:\n" + file.getName(),
                "File Already Loaded",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".csv")) {
            loadCsvDataset(file);
        } else if (fileName.endsWith(".kai") || fileName.endsWith(".kaz")) {
            loadKalixDataset(file);
        } else {
            logger.warn("Unsupported file type: " + fileName);
            if (statusUpdater != null) {
                statusUpdater.accept("Unsupported file type: " + fileName);
            }
        }
    }

    /**
     * Checks if a file is already loaded in the dataset tree.
     */
    private boolean isFileAlreadyLoaded(File file) {
        String absolutePath = file.getAbsolutePath();
        DefaultMutableTreeNode loadedDatasetsRoot = (DefaultMutableTreeNode) treeModel.getRoot();

        // Find the "Loaded datasets" node
        for (int i = 0; i < loadedDatasetsRoot.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) loadedDatasetsRoot.getChildAt(i);
            if (child.getUserObject() instanceof String && "Loaded datasets".equals(child.getUserObject())) {
                // Check all children of "Loaded datasets"
                for (int j = 0; j < child.getChildCount(); j++) {
                    DefaultMutableTreeNode datasetNode = (DefaultMutableTreeNode) child.getChildAt(j);
                    if (datasetNode.getUserObject() instanceof LoadedDatasetInfo) {
                        LoadedDatasetInfo info = (LoadedDatasetInfo) datasetNode.getUserObject();
                        if (info.file.getAbsolutePath().equals(absolutePath)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Loads a CSV dataset file with progress dialog.
     *
     * @param csvFile The CSV file to load
     */
    private void loadCsvDataset(File csvFile) {
        if (statusUpdater != null) {
            statusUpdater.accept("Loading CSV dataset...");
        }

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

        // Create CSV import task
        TimeSeriesCsvImporter.CsvImportTask importTask = new TimeSeriesCsvImporter.CsvImportTask(csvFile, new TimeSeriesCsvImporter.CsvImportOptions()) {
            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int progress = chunks.get(chunks.size() - 1);
                    progressBar.setValue(progress);
                    progressBar.setString(String.format("Importing CSV... %d%%", progress));
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();

                try {
                    TimeSeriesCsvImporter.CsvImportResult importResult = get();
                    handleCsvImportResult(csvFile, importResult);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(RunManager.this,
                        "Error loading CSV file:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Error loading CSV file");
                    }
                    logger.error("Error loading CSV file: " + csvFile.getName(), e);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            importTask.cancel(true);
            progressDialog.dispose();
            if (statusUpdater != null) {
                statusUpdater.accept("CSV import cancelled");
            }
        });

        importTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Handles the result of a CSV import operation.
     *
     * @param csvFile The CSV file that was imported
     * @param importResult The import result containing data and statistics
     */
    private void handleCsvImportResult(File csvFile, TimeSeriesCsvImporter.CsvImportResult importResult) {
        if (importResult.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder("Failed to load CSV file:\n\n");
            for (String error : importResult.getErrors()) {
                errorMessage.append("• ").append(error).append("\n");
            }

            JOptionPane.showMessageDialog(this, errorMessage.toString(),
                "CSV Load Error", JOptionPane.ERROR_MESSAGE);
            if (statusUpdater != null) {
                statusUpdater.accept("Failed to load CSV file");
            }
            return;
        }

        // Add new data to plot dataset using hierarchical naming scheme
        String fileName = csvFile.getName();
        int seriesAdded = 0;

        for (TimeSeriesData series : importResult.getDataSet().getAllSeries()) {
            // Create hierarchical series name: file.sanitized_filename.sanitized_column
            String columnName = series.getName();
            String seriesName = createDatasetSeriesName(csvFile, columnName);

            logger.info("Loading CSV series: columnName='{}' -> seriesName='{}'", columnName, seriesName);

            // Create new series with hierarchical name
            TimeSeriesData namedSeries = new TimeSeriesData(
                seriesName,
                convertTimestampsToLocalDateTime(series.getTimestamps()),
                series.getValues()
            );

            // Store in cache (NOT in plotDataSet yet - added when plotted, like runs)
            datasetSeriesCache.put(seriesName, namedSeries);
            seriesAdded++;
        }

        // Add file to loaded datasets tree
        addLoadedDatasetToTree(csvFile);

        // Show warnings if any
        if (importResult.hasWarnings()) {
            StringBuilder warningMessage = new StringBuilder("CSV loaded successfully with warnings:\n\n");
            for (String warning : importResult.getWarnings()) {
                warningMessage.append("• ").append(warning).append("\n");
            }

            JOptionPane.showMessageDialog(this, warningMessage.toString(),
                "Load Warnings", JOptionPane.WARNING_MESSAGE);
        }

        // Update status with statistics
        TimeSeriesCsvImporter.ImportStatistics stats = importResult.getStatistics();
        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Loaded %s: %d series, %,d total points in %d ms",
                fileName, seriesAdded, importResult.getDataSet().getTotalPointCount(), stats.getParseTimeMs()));
        }
    }

    /**
     * Loads a Kalix compressed dataset file (.kai + .kaz) with progress dialog.
     *
     * @param ktmFile The KAI metadata file to load (or KAZ file, will find the .kai)
     */
    private void loadKalixDataset(File ktmFile) {
        if (statusUpdater != null) {
            statusUpdater.accept("Loading Kalix dataset...");
        }

        // Ensure we're working with the .kai file
        String basePath = ktmFile.getAbsolutePath().replaceAll("\\.(kai|kaz)$", "");
        File kaiFile = new File(basePath + ".kai");
        File kazFile = new File(basePath + ".kaz");

        // Verify both files exist
        if (!kaiFile.exists() || !kazFile.exists()) {
            JOptionPane.showMessageDialog(this,
                "Both .kai and .kaz files are required.\n" +
                "Missing: " + (!kaiFile.exists() ? kaiFile.getName() : kazFile.getName()),
                "Load Error",
                JOptionPane.ERROR_MESSAGE);
            if (statusUpdater != null) {
                statusUpdater.accept("Failed to load Kalix dataset");
            }
            return;
        }

        // Create progress dialog
        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Loading Kalix dataset...");

        JDialog progressDialog = new JDialog(this, "Loading Data", true);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(new JLabel("Loading: " + kaiFile.getName(), SwingConstants.CENTER), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        progressDialog.add(cancelButton, BorderLayout.SOUTH);

        progressDialog.setSize(400, 120);
        progressDialog.setLocationRelativeTo(this);

        // Create background loading task
        SwingWorker<List<TimeSeriesData>, Integer> loadTask = new SwingWorker<List<TimeSeriesData>, Integer>() {
            @Override
            protected List<TimeSeriesData> doInBackground() throws Exception {
                publish(25);
                KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
                publish(50);
                List<TimeSeriesData> seriesList = reader.readAllSeries(basePath);
                publish(100);
                return seriesList;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int progress = chunks.get(chunks.size() - 1);
                    progressBar.setValue(progress);
                }
            }

            @Override
            protected void done() {
                progressDialog.dispose();

                try {
                    List<TimeSeriesData> seriesList = get();

                    // Add all series to cache using hierarchical naming scheme
                    for (TimeSeriesData series : seriesList) {
                        // Create hierarchical series name: file.sanitized_filename.sanitized_series
                        String originalSeriesName = series.getName();
                        String seriesName = createDatasetSeriesName(kaiFile, originalSeriesName);

                        logger.info("Loading KAI series: originalName='{}' -> seriesName='{}'", originalSeriesName, seriesName);

                        TimeSeriesData namedSeries = new TimeSeriesData(
                            seriesName,
                            convertTimestampsToLocalDateTime(series.getTimestamps()),
                            series.getValues()
                        );

                        // Store in cache (NOT in plotDataSet yet - added when plotted, like runs)
                        datasetSeriesCache.put(seriesName, namedSeries);
                    }

                    // Add file to loaded datasets tree
                    addLoadedDatasetToTree(kaiFile);

                    // Update status
                    if (statusUpdater != null) {
                        statusUpdater.accept(String.format("Loaded %s: %d series",
                            kaiFile.getName(), seriesList.size()));
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(RunManager.this,
                        "Error loading Kalix dataset:\n" + e.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Error loading Kalix dataset");
                    }
                    logger.error("Error loading Kalix dataset: " + kaiFile.getName(), e);
                }
            }
        };

        cancelButton.addActionListener(e -> {
            loadTask.cancel(true);
            progressDialog.dispose();
            if (statusUpdater != null) {
                statusUpdater.accept("Kalix dataset load cancelled");
            }
        });

        loadTask.execute();
        progressDialog.setVisible(true);
    }

    /**
     * Generates a unique series name by appending a number if the name already exists.
     *
     * @param baseName The base name to make unique
     * @return A unique series name
     */
    private String getUniqueSeriesName(String baseName) {
        if (!plotDataSet.hasSeries(baseName)) {
            return baseName;
        }

        int counter = 2;
        String uniqueName;
        do {
            uniqueName = baseName + " (" + counter + ")";
            counter++;
        } while (plotDataSet.hasSeries(uniqueName));

        return uniqueName;
    }

    /**
     * Converts millisecond timestamps to LocalDateTime array.
     *
     * @param timestampsMillis Array of timestamps in milliseconds since epoch
     * @return Array of LocalDateTime objects in UTC
     */
    private java.time.LocalDateTime[] convertTimestampsToLocalDateTime(long[] timestampsMillis) {
        java.time.LocalDateTime[] result = new java.time.LocalDateTime[timestampsMillis.length];
        for (int i = 0; i < timestampsMillis.length; i++) {
            result[i] = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestampsMillis[i]),
                java.time.ZoneOffset.UTC);
        }
        return result;
    }

    /**
     * Sanitizes a string by converting all non-alphanumeric characters to underscores.
     * Used for creating valid hierarchical series names from filenames and column headers.
     *
     * @param input The string to sanitize
     * @return Sanitized string with only alphanumeric characters and underscores
     */
    private String sanitizeToIdentifier(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[^a-zA-Z0-9]", "_");
    }

    /**
     * Creates a hierarchical series name from a file and column name.
     * Format: file.{sanitized_filename}.{sanitized_column}
     *
     * @param file The data file
     * @param columnName The column name (will be trimmed and sanitized)
     * @return Hierarchical series name in format: file.filename_csv.column_name
     */
    private String createDatasetSeriesName(File file, String columnName) {
        String sanitizedFilename = sanitizeToIdentifier(file.getName());
        String sanitizedColumn = sanitizeToIdentifier(columnName.trim());
        return "file." + sanitizedFilename + "." + sanitizedColumn;
    }

    /**
     * Adds a loaded dataset file to the loaded datasets tree.
     * Uses treeModel.nodesWereInserted to preserve current selection.
     *
     * @param file The dataset file that was loaded
     */
    private void addLoadedDatasetToTree(File file) {
        // Create new dataset info and tree node
        LoadedDatasetInfo datasetInfo = new LoadedDatasetInfo(file.getName(), file);
        DefaultMutableTreeNode datasetNode = new DefaultMutableTreeNode(datasetInfo);

        // Add to loadedDatasetsNode
        int insertIndex = loadedDatasetsNode.getChildCount();
        loadedDatasetsNode.add(datasetNode);

        // Notify tree model to preserve selection
        int[] childIndices = new int[] { insertIndex };
        Object[] children = new Object[] { datasetNode };
        treeModel.nodesWereInserted(loadedDatasetsNode, childIndices);

        logger.info("Added dataset to tree: " + file.getName());
    }

    /**
     * Expands all nodes recursively from the selected node(s).
     */
    private void expandAllFromSelected() {
        TreePath[] selectedPaths = timeseriesTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            // No selection - expand all from root
            expandAllChildren(new TreePath(timeseriesTreeModel.getRoot()));
        } else {
            // Expand all from each selected node
            for (TreePath path : selectedPaths) {
                expandAllChildren(path);
            }
        }
    }

    /**
     * Recursively expands all children of a given tree path.
     */
    private void expandAllChildren(TreePath path) {
        timeseriesTree.expandPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath childPath = path.pathByAddingChild(node.getChildAt(i));
            expandAllChildren(childPath);
        }
    }

    /**
     * Collapses all nodes recursively from the selected node(s).
     */
    private void collapseAllFromSelected() {
        TreePath[] selectedPaths = timeseriesTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            // No selection - collapse all from root
            collapseAllChildren(new TreePath(timeseriesTreeModel.getRoot()));
        } else {
            // Collapse all from each selected node
            for (TreePath path : selectedPaths) {
                collapseAllChildren(path);
            }
        }
    }

    /**
     * Recursively collapses all children of a given tree path.
     */
    private void collapseAllChildren(TreePath path) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        // Collapse children first (bottom-up)
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath childPath = path.pathByAddingChild(node.getChildAt(i));
            collapseAllChildren(childPath);
        }
        timeseriesTree.collapsePath(path);
    }

    public void refreshRuns() {
        if (stdioTaskManager == null) return;

        SwingUtilities.invokeLater(() -> {
            Map<String, SessionManager.KalixSession> activeSessions = stdioTaskManager.getActiveSessions();

            // Track nodes that were inserted for proper tree notification
            List<Integer> insertedIndices = new ArrayList<>();
            List<DefaultMutableTreeNode> insertedNodes = new ArrayList<>();

            // Check for new sessions
            for (SessionManager.KalixSession session : activeSessions.values()) {
                // FILTER: Only show simulation runs (RunModelProgram)
                // Other session types (OptimisationProgram, etc.) are managed elsewhere
                if (!(session.getActiveProgram() instanceof RunModelProgram)) {
                    continue; // Skip non-simulation sessions
                }

                String sessionKey = session.getSessionKey();

                if (!sessionToTreeNode.containsKey(sessionKey)) {
                    // New session - add to tree
                    String runName = "Run_" + runCounter++;
                    sessionToRunName.put(sessionKey, runName);

                    RunInfo runInfo = new RunInfo(runName, session);
                    RunStatus initialStatus = runInfo.getRunStatus();

                    DefaultMutableTreeNode runNode = new DefaultMutableTreeNode(runInfo);
                    int insertIndex = currentRunsNode.getChildCount();
                    currentRunsNode.add(runNode);
                    sessionToTreeNode.put(sessionKey, runNode);
                    lastKnownStatus.put(sessionKey, initialStatus);

                    // Track insertion for tree notification
                    insertedIndices.add(insertIndex);
                    insertedNodes.add(runNode);

                    // If session is already DONE when first discovered, treat it as a completion
                    // (This handles fast-completing runs that finish before refreshRuns() is called)
                    if (initialStatus == RunStatus.DONE) {
                        long completionTime = System.currentTimeMillis();
                        completionTimestamps.put(sessionKey, completionTime);

                        if (completionTime > lastRunCompletionTime) {
                            updateLastRun(runInfo, completionTime);
                        }
                    }
                } else {
                    // Existing session - check for status changes
                    DefaultMutableTreeNode existingNode = sessionToTreeNode.get(sessionKey);
                    RunInfo runInfo = (RunInfo) existingNode.getUserObject();
                    RunStatus currentStatus = runInfo.getRunStatus();
                    RunStatus lastStatus = lastKnownStatus.get(sessionKey);

                    if (lastStatus != currentStatus) {
                        // Status changed - refresh this node's display
                        treeModel.nodeChanged(existingNode);

                        // Detect session reuse: if session was DONE and is now RUNNING/LOADING, it's a new run
                        if (lastStatus == RunStatus.DONE &&
                            (currentStatus == RunStatus.RUNNING || currentStatus == RunStatus.LOADING || currentStatus == RunStatus.STARTING)) {
                            // Session reused for new run - reset completion tracking for this session
                            completionTimestamps.remove(sessionKey);
                        }

                        lastKnownStatus.put(sessionKey, currentStatus);

                        // Check if run just completed
                        if (currentStatus == RunStatus.DONE && lastStatus != RunStatus.DONE) {
                            // Record completion timestamp
                            long completionTime = System.currentTimeMillis();
                            completionTimestamps.put(sessionKey, completionTime);

                            // Update Last if this is more recent
                            if (completionTime > lastRunCompletionTime) {
                                updateLastRun(runInfo, completionTime);
                            }
                        }

                        // Update outputs if this run is currently selected
                        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
                        if (selectedPath != null && selectedPath.getLastPathComponent() == existingNode) {
                            updateOutputsTree(runInfo);
                        }
                    }
                }
            }

            // Notify tree model of inserted nodes (preserves selection)
            if (!insertedIndices.isEmpty()) {
                int[] indices = insertedIndices.stream().mapToInt(Integer::intValue).toArray();
                Object[] children = insertedNodes.toArray();
                treeModel.nodesWereInserted(currentRunsNode, indices);

                timeseriesSourceTree.expandPath(new TreePath(currentRunsNode.getPath()));
            }

            // Check for removed sessions
            // Need to capture indices BEFORE removal
            List<Integer> removedIndices = new ArrayList<>();
            List<Object> removedChildren = new ArrayList<>();
            List<String> sessionsToRemove = new ArrayList<>();

            for (Map.Entry<String, DefaultMutableTreeNode> entry : sessionToTreeNode.entrySet()) {
                String sessionKey = entry.getKey();
                if (!activeSessions.containsKey(sessionKey)) {
                    DefaultMutableTreeNode nodeToRemove = entry.getValue();
                    int indexToRemove = currentRunsNode.getIndex(nodeToRemove);
                    if (indexToRemove >= 0) {
                        removedIndices.add(indexToRemove);
                        removedChildren.add(nodeToRemove);
                        sessionsToRemove.add(sessionKey);
                    }
                }
            }

            // Remove sessions and notify tree
            if (!removedIndices.isEmpty()) {
                // Remove nodes from tree
                for (Object child : removedChildren) {
                    currentRunsNode.remove((DefaultMutableTreeNode) child);
                }

                // Clean up tracking maps
                for (String sessionKey : sessionsToRemove) {
                    sessionToTreeNode.remove(sessionKey);
                    sessionToRunName.remove(sessionKey);
                    lastKnownStatus.remove(sessionKey);
                    completionTimestamps.remove(sessionKey);

                    // If this was the Last run, clear Last node
                    if (lastRunInfo != null && lastRunInfo.session.getSessionKey().equals(sessionKey)) {
                        lastRunInfo = null;
                        lastRunCompletionTime = 0L;

                        // Properly remove the Last child node
                        if (lastRunChildNode != null) {
                            int childIndex = lastRunNode.getIndex(lastRunChildNode);
                            Object[] removedChild = new Object[]{lastRunChildNode};
                            lastRunNode.removeAllChildren();
                            treeModel.nodesWereRemoved(lastRunNode, new int[]{childIndex}, removedChild);
                            lastRunChildNode = null;
                        }
                    }
                }

                // Notify tree model of removals
                int[] indices = removedIndices.stream().mapToInt(Integer::intValue).toArray();
                Object[] children = removedChildren.toArray();
                treeModel.nodesWereRemoved(currentRunsNode, indices, children);
            }
        });
    }

    /**
     * Updates the Last run node to point to the most recently completed run.
     */
    private void updateLastRun(RunInfo newLastRun, long completionTime) {
        lastRunInfo = newLastRun;
        lastRunCompletionTime = completionTime;

        // Check if we're replacing an existing Last child or creating the first one
        DefaultMutableTreeNode oldChildNode = null;
        int oldChildIndex = -1;
        boolean wasLastSelected = false;

        if (lastRunChildNode != null) {
            oldChildIndex = lastRunNode.getIndex(lastRunChildNode);
            oldChildNode = lastRunChildNode;

            // Check if the old Last child was selected
            TreePath[] selectedPaths = timeseriesSourceTree.getSelectionPaths();
            if (selectedPaths != null) {
                TreePath oldLastPath = new TreePath(lastRunChildNode.getPath());
                for (TreePath path : selectedPaths) {
                    if (path.equals(oldLastPath)) {
                        wasLastSelected = true;
                        break;
                    }
                }
            }
        }

        // If Last was selected, block all selection events during the entire update
        if (wasLastSelected) {
            isUpdatingSelection = true;
        }

        // Create new child node
        lastRunChildNode = new DefaultMutableTreeNode(
            new RunInfo("Last", newLastRun.session)
        );

        TreePath[] selectedBefore = timeseriesSourceTree.getSelectionPaths();

        if (oldChildNode != null) {
            // Replacing existing child - remove old, add new
            lastRunNode.remove(oldChildNode);
            treeModel.nodesWereRemoved(lastRunNode, new int[]{oldChildIndex}, new Object[]{oldChildNode});

            lastRunNode.add(lastRunChildNode);
            treeModel.nodesWereInserted(lastRunNode, new int[]{0});
        } else {
            // First time - just add
            lastRunNode.add(lastRunChildNode);
            treeModel.nodesWereInserted(lastRunNode, new int[]{0});
        }

        // If Last was previously selected, restore everything
        if (wasLastSelected) {
            TreePath newLastPath = new TreePath(lastRunChildNode.getPath());

            // Restore dataset tree selection
            timeseriesSourceTree.addSelectionPath(newLastPath);

            // Update RunInfo references in the existing tree without rebuilding
            // This preserves the tree structure and selection
            // IMPORTANT: Create a RunInfo with name "Last" (not the actual run name)
            // so timeseries display as "ds_1 [Last]" not "ds_1 [Run_3]"
            RunInfo lastRunInfoWrapper = new RunInfo("Last", newLastRun.session);
            updateRunInfoInTimeseriesTree(lastRunInfoWrapper);

            isUpdatingSelection = false;

            // Selection is automatically preserved since we updated in-place without reload()
            // No need to manually restore or trigger events
        }

        TreePath[] selectedAfter = timeseriesSourceTree.getSelectionPaths();

        // Expand the Last run node to show the new child
        timeseriesSourceTree.expandPath(new TreePath(lastRunNode.getPath()));

        // Refresh any plotted "[Last]" series to use the new Last run's data
        refreshLastSeries();
    }

    /**
     * Restores timeseries tree selection by searching for matching series keys.
     * Gracefully skips series that no longer exist in the rebuilt tree.
     *
     * @param selectedTimeseriesKeys Set of series keys to restore (format: "seriesName [RunName]")
     * @return Number of series successfully restored
     */
    private int restoreTimeseriesTreeSelection(Set<String> selectedTimeseriesKeys) {
        List<TreePath> pathsToSelect = new ArrayList<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();

        // Recursively search tree for matching series keys
        searchAndCollectPaths(root, selectedTimeseriesKeys, pathsToSelect);

        if (!pathsToSelect.isEmpty()) {
            // Restore selection without triggering events
            TreePath[] pathsArray = pathsToSelect.toArray(new TreePath[0]);
            timeseriesTree.setSelectionPaths(pathsArray);
            return pathsToSelect.size();
        } else {
            return 0;
        }
    }

    /**
     * Recursively searches tree nodes for matching series keys and collects their paths.
     */
    private void searchAndCollectPaths(DefaultMutableTreeNode node, Set<String> targetKeys, List<TreePath> results) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode that matches one of our target keys
        if (userObject instanceof SeriesLeafNode) {
            SeriesLeafNode leaf = (SeriesLeafNode) userObject;
            String seriesKey;
            if (leaf.source instanceof RunInfo) {
                // Run series: add run name suffix
                String runName = ((RunInfo) leaf.source).runName;
                seriesKey = leaf.seriesName + " [" + runName + "]";
            } else {
                // Dataset series: add filename suffix
                LoadedDatasetInfo datasetInfo = (LoadedDatasetInfo) leaf.source;
                seriesKey = leaf.seriesName + " [" + datasetInfo.fileName + "]";
            }
            if (targetKeys.contains(seriesKey)) {
                TreePath path = new TreePath(node.getPath());
                results.add(path);
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            searchAndCollectPaths(child, targetKeys, results);
        }
    }

    /**
     * Restores tree selection to match the current selectedSeries set.
     * This ensures the tree visually reflects what's plotted, even after tree rebuilds.
     * Also reconciles selectedSeries - removes series that are no longer available in the tree.
     * Returns the set of series that were successfully restored.
     */
    private Set<String> restoreTreeSelectionFromSelectedSeries() {
        if (selectedSeries.isEmpty()) {
            // Nothing to restore
            return Collections.emptySet();
        }

        List<TreePath> pathsToSelect = new ArrayList<>();
        Set<String> restoredSeriesKeys = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();

        // Search tree for nodes matching selectedSeries, collect which ones we found
        for (String seriesKey : selectedSeries) {
            List<TreePath> foundPaths = new ArrayList<>();
            searchAndCollectPaths(root, Collections.singleton(seriesKey), foundPaths);
            if (!foundPaths.isEmpty()) {
                pathsToSelect.addAll(foundPaths);
                restoredSeriesKeys.add(seriesKey);
            }
        }

        if (!pathsToSelect.isEmpty()) {
            // Note: Caller should have isUpdatingSelection set to block events
            // We don't set it here to avoid nesting issues
            TreePath[] pathsArray = pathsToSelect.toArray(new TreePath[0]);
            timeseriesTree.setSelectionPaths(pathsArray);
        }

        // Log any series that couldn't be restored
        Set<String> notRestored = new HashSet<>(selectedSeries);
        notRestored.removeAll(restoredSeriesKeys);

        return restoredSeriesKeys;
    }

    /**
     * Reconciles selectedSeries and plots with what's actually in the tree.
     * Removes series that couldn't be restored (e.g., when a run is deselected).
     *
     * @param restoredSeries Set of series that were successfully found and restored in the tree
     */
    private void reconcileSelectedSeriesWithTree(Set<String> restoredSeries) {
        // Find series that need to be removed (in selectedSeries but not restored)
        Set<String> seriesToRemove = new HashSet<>(selectedSeries);
        seriesToRemove.removeAll(restoredSeries);

        if (seriesToRemove.isEmpty()) {
            return;
        }

        // Remove from selectedSeries
        selectedSeries.removeAll(seriesToRemove);

        // Remove from plot dataset
        for (String seriesKey : seriesToRemove) {
            plotDataSet.removeSeries(seriesKey);
            seriesColorMap.remove(seriesKey);

            // Remove from all stats tables
            tabManager.removeSeriesFromStatsTabs(seriesKey);

            // Remove from legend in all plots
            for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                panel.removeLegendSeries(seriesKey);
            }
        }

        // Update plot visibility (don't reset zoom - we're just removing series)
        updatePlotVisibility(false);
    }

    /**
     * Updates RunInfo references in the timeseries tree without rebuilding.
     * Used when "Last" updates to point to a new run - the tree structure stays
     * the same, but the RunInfo objects need to be updated.
     *
     * @param newRunInfo The new RunInfo to replace old "Last" references
     */
    private void updateRunInfoInTimeseriesTree(RunInfo newRunInfo) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        updateRunInfoInNode(root, newRunInfo);
    }

    /**
     * Recursively updates RunInfo references in a tree node and its children.
     */
    private void updateRunInfoInNode(DefaultMutableTreeNode node, RunInfo newRunInfo) {
        Object userObject = node.getUserObject();

        // Update SeriesLeafNode if it references "Last"
        if (userObject instanceof SeriesLeafNode) {
            SeriesLeafNode leaf = (SeriesLeafNode) userObject;
            if (leaf.source instanceof RunInfo && "Last".equals(((RunInfo) leaf.source).runName)) {
                // Replace with new RunInfo
                SeriesLeafNode newLeaf = new SeriesLeafNode(leaf.seriesName, newRunInfo, leaf.showSeriesName);
                node.setUserObject(newLeaf);
                timeseriesTreeModel.nodeChanged(node);
            }
        }
        // Update SeriesParentNode if it contains "Last"
        else if (userObject instanceof SeriesParentNode) {
            SeriesParentNode parent = (SeriesParentNode) userObject;
            // Check if this parent contains "Last" run
            boolean containsLast = parent.sourcesWithSeries.stream()
                .filter(s -> s instanceof RunInfo)
                .anyMatch(s -> "Last".equals(((RunInfo) s).runName));

            if (containsLast) {
                // Create new list with updated sources
                List<Object> newSources = new ArrayList<>();
                for (Object source : parent.sourcesWithSeries) {
                    if (source instanceof RunInfo && "Last".equals(((RunInfo) source).runName)) {
                        newSources.add(newRunInfo);
                    } else {
                        newSources.add(source);
                    }
                }
                SeriesParentNode newParent = new SeriesParentNode(parent.seriesName, newSources);
                node.setUserObject(newParent);
                timeseriesTreeModel.nodeChanged(node);
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            updateRunInfoInNode(child, newRunInfo);
        }
    }

    /**
     * Resolves a RunInfo to its actual session.
     * If the run is "Last", returns the session of the actual last completed run.
     */
    private SessionManager.KalixSession resolveRunInfoSession(RunInfo runInfo) {
        if ("Last".equals(runInfo.runName) && lastRunInfo != null) {
            return lastRunInfo.session;
        }
        return runInfo.session;
    }

    /**
     * Extracts the base series name from a series key.
     * Series keys have format: "seriesName [RunName]"
     */
    private String extractSeriesName(String seriesKey) {
        int bracketIndex = seriesKey.lastIndexOf(" [");
        if (bracketIndex > 0) {
            return seriesKey.substring(0, bracketIndex);
        }
        return seriesKey;
    }

    /**
     * Refreshes all series that reference "[Last]" to use the new Last run's data.
     * Called when the Last run changes to a new completed run.
     */
    private void refreshLastSeries() {
        if (lastRunInfo == null) {
            return;
        }

        // Find all series keys that end with " [Last]"
        List<String> lastSeriesKeys = selectedSeries.stream()
            .filter(key -> key.endsWith(" [Last]"))
            .collect(java.util.stream.Collectors.toList());

        if (lastSeriesKeys.isEmpty()) {
            return;
        }

        String newSessionKey = lastRunInfo.session.getSessionKey();

        // Refresh each "[Last]" series
        for (String seriesKey : lastSeriesKeys) {
            String seriesName = extractSeriesName(seriesKey);
            Color seriesColor = seriesColorMap.get(seriesKey);

            // Remove old data from plot
            plotDataSet.removeSeries(seriesKey);

            // Check if we have cached data from the new Last run
            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(newSessionKey, seriesName);
            if (cachedData != null) {
                // Add immediately with cached data
                TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                addSeriesToPlot(renamedData, seriesColor);

                // Update all stats tables with aggregation
                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
            } else if (!timeSeriesRequestManager.isRequestInProgress(newSessionKey, seriesName)) {
                // Request the new data
                timeSeriesRequestManager.requestTimeSeries(newSessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            // Check if this series is still selected
                            if (selectedSeries.contains(seriesKey)) {
                                TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, seriesKey);
                                addSeriesToPlot(renamedData, seriesColor);

                                // Update all stats tables with aggregation
                                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);

                                // Don't reset zoom when Last updates - series keys haven't changed,
                                // just the data they point to
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            logger.error("Failed to fetch timeseries for Last run: {} from session {}",
                                seriesName, newSessionKey, throwable);
                        });
                        return null;
                    });
            }
        }

        // Repaint all plot panels to show the updated data
        // Don't call refreshData() as it would reset the zoom
        for (PlotPanel panel : tabManager.getAllPlotPanels()) {
            panel.repaint();
        }
    }

    /**
     * Shows a dialog to rename the selected run.
     */
    private void renameRunFromContextMenu() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String currentName = runInfo.runName;

        // Show input dialog for new name
        String newName = (String) JOptionPane.showInputDialog(
            this,
            "Enter new name for the run:",
            "Rename Run",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );

        if (newName != null) {
            newName = newName.trim();

            // Validate the new name
            if (newName.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Run name cannot be empty.",
                    "Invalid Name",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Check if name is already in use by another run
            String sessionKey = runInfo.session.getSessionKey();
            for (String existingName : sessionToRunName.values()) {
                if (existingName.equals(newName) && !existingName.equals(currentName)) {
                    JOptionPane.showMessageDialog(this,
                        "A run with the name '" + newName + "' already exists.\nPlease choose a different name.",
                        "Duplicate Name",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            // Update the run name
            runInfo.setRunName(newName);
            sessionToRunName.put(sessionKey, newName);

            // Simple node refresh - just update this one node
            treeModel.nodeChanged(selectedNode);

            // Update status
            statusUpdater.accept("Renamed run '" + currentName + "' to '" + newName + "'");
        }
    }

    /**
     * Opens the KalixCLI Session Manager window with the selected run's session.
     */
    private void showInSessionManagerFromContextMenu() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String sessionKey = runInfo.session.getSessionKey();
        SessionManagerWindow.showSessionManagerWindow(this, stdioTaskManager, statusUpdater, sessionKey);
    }

    /**
     * Shows the model INI string for the selected run in a MinimalEditorWindow.
     */
    private void showModelFromContextMenu() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();

        // Get the model text from the RunModelProgram
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            String modelText = program.getModelText();

            if (modelText != null && !modelText.isEmpty()) {
                // Create and show MinimalEditorWindow with the model text in INI mode
                MinimalEditorWindow editorWindow = new MinimalEditorWindow(modelText, true);
                editorWindow.setTitle(runInfo.runName);
                editorWindow.setVisible(true);
            } else {
                JOptionPane.showMessageDialog(
                    this,
                    "Model text is not available for this run.",
                    "Model Not Available",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        } else {
            JOptionPane.showMessageDialog(
                this,
                "This run does not contain model information.",
                "Not a Model Run",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    /**
     * Opens a diff window comparing the run's model with the main editor's model.
     */
    private void diffModelFromContextMenu() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();

        // Get the model text from the RunModelProgram
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            String runModelText = program.getModelText();

            if (runModelText == null || runModelText.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Model text is not available for this run.",
                    "Model Not Available",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }

            // Get the reference model text from the main editor
            if (editorTextSupplier == null) {
                JOptionPane.showMessageDialog(
                    this,
                    "Cannot access main editor text.",
                    "Editor Not Available",
                    JOptionPane.ERROR_MESSAGE
                );
                return;
            }

            String referenceModelText = editorTextSupplier.get();
            if (referenceModelText == null || referenceModelText.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "No model is loaded in the main editor.",
                    "No Reference Model",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }

            // Open diff window (run model vs reference model)
            String title = "Changes: " + runInfo.runName + " vs Reference Model";
            new DiffWindow(runModelText, referenceModelText, title, "Reference Model", runInfo.runName);

        } else {
            JOptionPane.showMessageDialog(
                this,
                "This run does not contain model information.",
                "Not a Model Run",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    /**
     * Removes a run from the context menu - terminates if active and removes from list.
     */
    private void removeRunFromContextMenu() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String sessionKey = runInfo.session.getSessionKey();
        boolean isActive = runInfo.session.isActive();

        String message = isActive
            ? "Are you sure you want to stop and remove " + runInfo.runName + "?\n\nThis will terminate the running session and remove it from the list."
            : "Are you sure you want to remove " + runInfo.runName + " from the list?";

        if (DialogUtils.showConfirmation(this, message, "Remove Run")) {
            if (isActive) {
                // First terminate the session, then remove it
                stdioTaskManager.terminateSession(sessionKey)
                    .thenCompose(v -> {
                        // After termination, remove from list
                        return stdioTaskManager.removeSession(sessionKey);
                    })
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Stopped and removed run: " + runInfo.runName);
                        sessionToRunName.remove(sessionKey);
                        refreshRuns();
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            statusUpdater.accept("Failed to stop/remove run: " + throwable.getMessage());
                            DialogUtils.showError(this,
                                "Failed to stop/remove run: " + throwable.getMessage(),
                                "Remove Run Error");
                        });
                        return null;
                    });
            } else {
                // Just remove from list (session already terminated)
                stdioTaskManager.removeSession(sessionKey)
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Removed run: " + runInfo.runName);
                        sessionToRunName.remove(sessionKey);
                        refreshRuns();
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            statusUpdater.accept("Failed to remove run: " + throwable.getMessage());
                            DialogUtils.showError(this,
                                "Failed to remove run: " + throwable.getMessage(),
                                "Remove Run Error");
                        });
                        return null;
                    });
            }
        }
    }

    /**
     * Handles save results action from context menu.
     */
    private void saveResultsFromContextMenu() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String sessionKey = runInfo.session.getSessionKey();
        String kalixcliUid = runInfo.session.getKalixcliUid();

        // Check if the run has completed successfully
        RunStatus status = runInfo.getRunStatus();
        if (status != RunStatus.DONE) {
            String statusText = status == RunStatus.ERROR ? "failed" :
                              status == RunStatus.RUNNING ? "still running" : "not completed";
            statusUpdater.accept("Cannot save results: run " + runInfo.runName + " has " + statusText);
            return;
        }

        // Generate default filename: {run_name}_{uid}.csv
        String safeRunName = runInfo.runName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String defaultFilename = safeRunName + "_" + (kalixcliUid != null ? kalixcliUid : "unknown") + ".csv";

        // Show save dialog
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Results");
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        fileChooser.setSelectedFile(new File(defaultFilename));

        // Set initial directory to model directory if available
        if (baseDirectorySupplier != null) {
            java.io.File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Add .csv extension if not present
            String fileName = selectedFile.getName();
            if (!fileName.toLowerCase().endsWith(".csv")) {
                selectedFile = new File(selectedFile.getParent(), fileName + ".csv");
            }

            // Send save_results command to kalixcli
            String command = String.format(
                "{\"m\":\"cmd\",\"c\":\"save_results\",\"p\":{\"path\":\"%s\",\"format\":\"csv\"}}",
                selectedFile.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"")
            );

            try {
                stdioTaskManager.sendCommand(sessionKey, command);
                statusUpdater.accept("Saving results to: " + selectedFile.getName());

                // TODO: We should ideally wait for the response and update status accordingly
                // For now, we'll show immediate feedback

            } catch (Exception e) {
                statusUpdater.accept("Failed to send save command: " + e.getMessage());
                DialogUtils.showError(this,
                    "Failed to save results: " + e.getMessage(),
                    "Save Results Error");
            }
        }
    }

    /**
     * Handles tree selection changes to update the timeseries tree.
     */
    private void onRunTreeSelectionChanged(TreeSelectionEvent e) {
        // Ignore selection changes during programmatic updates
        if (isUpdatingSelection) {
            return;
        }

        TreePath[] selectedPaths = timeseriesSourceTree.getSelectionPaths();

        // Block timeseries tree selection events during rebuild and restoration
        isUpdatingSelection = true;
        updateOutputsTree();
        isUpdatingSelection = false;
    }

    /**
     * Updates the timeseries tree based on current run tree selection.
     */
    private void updateOutputsTree() {
        TreePath[] selectedPaths = timeseriesSourceTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            // Clear timeseries tree when nothing selected
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
            root.removeAllChildren();
            root.add(new DefaultMutableTreeNode("Select one or more runs or datasets"));
            timeseriesTreeModel.reload();
            return;
        }

        // Collect all selected RunInfo and LoadedDatasetInfo objects
        List<RunInfo> selectedRuns = new ArrayList<>();
        List<LoadedDatasetInfo> selectedDatasets = new ArrayList<>();

        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObject = node.getUserObject();

            if (userObject instanceof RunInfo) {
                selectedRuns.add((RunInfo) userObject);
            } else if (userObject instanceof LoadedDatasetInfo) {
                selectedDatasets.add((LoadedDatasetInfo) userObject);
            }
        }

        if (selectedRuns.isEmpty() && selectedDatasets.isEmpty()) {
            // Selection is on parent nodes only (Last run, Current runs, Run library, Loaded datasets)
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
            root.removeAllChildren();
            root.add(new DefaultMutableTreeNode("Select one or more runs or datasets"));
            timeseriesTreeModel.reload();
        } else {
            // Update timeseries tree with all selected runs and datasets
            updateOutputsTreeForMultipleSources(selectedRuns, selectedDatasets);
        }
    }

    /**
     * Updates the timeseries tree based on the selected run.
     */
    private void updateOutputsTree(RunInfo runInfo) {
        // Remember current expansion state
        List<TreePath> expandedPaths = new ArrayList<>();
        for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
            TreePath path = timeseriesTree.getPathForRow(i);
            if (timeseriesTree.isExpanded(path)) {
                expandedPaths.add(path);
            }
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        root.removeAllChildren();

        // Get outputs from the run's program
        List<String> outputs = null;
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            outputs = program.getOutputsGenerated();
        }

        if (outputs != null && !outputs.isEmpty()) {
            // Sort outputs using natural sorting (case-insensitive, number-aware)
            outputs.sort(RunManager::naturalCompare);

            // Parse dot-delimited strings into tree structure
            for (String output : outputs) {
                addOutputToTree(root, output);
            }
        } else {
            // Add a message indicating no outputs are available
            String message = runInfo.getRunStatus() == RunStatus.DONE ?
                "No outputs available" :
                "Outputs will appear when simulation completes";
            root.add(new DefaultMutableTreeNode(message));
        }

        timeseriesTreeModel.reload();

        // Restore expansion state or expand all if first time
        if (expandedPaths.isEmpty()) {
            // First time - expand all nodes
            for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
                timeseriesTree.expandRow(i);
            }
        } else {
            // Restore previous expansion state
            for (TreePath expandedPath : expandedPaths) {
                // Try to find equivalent path in new tree
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    timeseriesTree.expandPath(newPath);
                }
            }
        }

        // Restore selection to match what's currently plotted
        // This ensures plots stay visible when tree rebuilds (e.g., adding Run_2)
        Set<String> restoredSeries = restoreTreeSelectionFromSelectedSeries();

        // Reconcile: remove series that are no longer in the tree from plots
        reconcileSelectedSeriesWithTree(restoredSeries);
    }

    /**
     * Gets all series names from a loaded dataset file.
     * Series names follow the format: file.sanitized_filename.sanitized_column
     *
     * @param datasetInfo The dataset to get series names from
     * @return List of series names from this dataset
     */
    private List<String> getSeriesNamesFromDataset(LoadedDatasetInfo datasetInfo) {
        String sanitizedFilename = sanitizeToIdentifier(datasetInfo.fileName);
        String prefix = "file." + sanitizedFilename + ".";

        // Get series from cache (NOT plotDataSet) - mirrors how runs work
        return datasetSeriesCache.keySet().stream()
            .filter(name -> name.startsWith(prefix))
            .sorted(RunManager::naturalCompare)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Updates the timeseries tree for multiple selected sources (runs and/or datasets).
     * Series available in multiple sources become parent nodes with source children.
     * Series available in only one source become simple leaf nodes.
     */
    private void updateOutputsTreeForMultipleSources(List<RunInfo> selectedRuns, List<LoadedDatasetInfo> selectedDatasets) {
        // Remember current expansion state
        List<TreePath> expandedPaths = new ArrayList<>();
        boolean hadDatasetPaths = false;  // Track if previous tree had "file" nodes
        for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
            TreePath path = timeseriesTree.getPathForRow(i);
            if (timeseriesTree.isExpanded(path)) {
                expandedPaths.add(path);
                // Check if this path contains "file" node (dataset tree)
                for (Object component : path.getPath()) {
                    if (component instanceof DefaultMutableTreeNode) {
                        Object userObj = ((DefaultMutableTreeNode) component).getUserObject();
                        if (userObj instanceof String && "file".equals(userObj)) {
                            hadDatasetPaths = true;
                            break;
                        }
                    }
                }
            }
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        root.removeAllChildren();

        // If only one source selected, use simplified structure
        boolean isDatasetTree = false;
        if (selectedRuns.size() + selectedDatasets.size() == 1) {
            if (selectedRuns.size() == 1) {
                updateOutputsTreeSingleRun(root, selectedRuns.get(0));
            } else {
                updateOutputsTreeSingleDataset(root, selectedDatasets.get(0));
                isDatasetTree = true;
            }
        } else {
            // Build multi-source hybrid tree
            updateOutputsTreeMultiSource(root, selectedRuns, selectedDatasets);
            isDatasetTree = !selectedDatasets.isEmpty();  // Has datasets if any selected
        }

        timeseriesTreeModel.reload();

        // Expand all if: (1) first time (no previous paths), OR (2) switching between run/dataset trees
        boolean switchedContext = (isDatasetTree != hadDatasetPaths);
        if (expandedPaths.isEmpty() || switchedContext) {
            // First time or switched context - expand all nodes
            for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
                timeseriesTree.expandRow(i);
            }
        } else {
            // Restore previous expansion state (same context)
            for (TreePath expandedPath : expandedPaths) {
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    timeseriesTree.expandPath(newPath);
                }
            }
        }

        // Restore selection to match what's currently plotted
        Set<String> restoredSeries = restoreTreeSelectionFromSelectedSeries();

        // Reconcile: remove series that are no longer in the tree from plots
        reconcileSelectedSeriesWithTree(restoredSeries);
    }

    /**
     * Populates the tree for a single dataset using SeriesLeafNode objects.
     */
    private void updateOutputsTreeSingleDataset(DefaultMutableTreeNode root, LoadedDatasetInfo datasetInfo) {
        List<String> seriesNames = getSeriesNamesFromDataset(datasetInfo);

        logger.info("Building tree for dataset: {}, found {} series", datasetInfo.fileName, seriesNames.size());
        for (String name : seriesNames) {
            logger.info("  Series: {}", name);
        }

        if (!seriesNames.isEmpty()) {
            for (String seriesName : seriesNames) {
                // Create standalone leaf node with showSeriesName=true
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, datasetInfo, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            }

            // Debug: log the tree structure
            logger.info("Tree structure after building:");
            logTreeStructure(root, 0);
        } else {
            root.add(new DefaultMutableTreeNode("No series available from this dataset"));
        }
    }

    /**
     * Debug helper to log tree structure.
     */
    private void logTreeStructure(DefaultMutableTreeNode node, int depth) {
        String indent = "  ".repeat(depth);
        Object userObj = node.getUserObject();
        String nodeDesc;
        if (userObj instanceof SeriesLeafNode) {
            SeriesLeafNode leaf = (SeriesLeafNode) userObj;
            nodeDesc = "SeriesLeafNode: displayString='" + leaf.toString() + "', fullName='" + leaf.seriesName + "'";
        } else if (userObj instanceof SeriesParentNode) {
            nodeDesc = "SeriesParentNode: " + userObj.toString();
        } else {
            nodeDesc = "String: '" + userObj + "'";
        }
        logger.info("{}└─ {}", indent, nodeDesc);

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            logTreeStructure(child, depth + 1);
        }
    }

    /**
     * Populates the tree for multiple sources (runs and/or datasets) using smart hybrid structure.
     */
    private void updateOutputsTreeMultiSource(DefaultMutableTreeNode root, List<RunInfo> selectedRuns, List<LoadedDatasetInfo> selectedDatasets) {
        // Map: series name -> list of sources (RunInfo or LoadedDatasetInfo) that have this series
        Map<String, List<Object>> seriesAvailability = new LinkedHashMap<>();

        // Collect all outputs from all selected runs
        for (RunInfo runInfo : selectedRuns) {
            List<String> outputs = null;
            if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
                RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
                outputs = program.getOutputsGenerated();
            }

            if (outputs != null) {
                for (String output : outputs) {
                    seriesAvailability.computeIfAbsent(output, k -> new ArrayList<>()).add(runInfo);
                }
            }
        }

        // Collect all series from all selected datasets
        for (LoadedDatasetInfo datasetInfo : selectedDatasets) {
            List<String> seriesNames = getSeriesNamesFromDataset(datasetInfo);
            for (String seriesName : seriesNames) {
                seriesAvailability.computeIfAbsent(seriesName, k -> new ArrayList<>()).add(datasetInfo);
            }
        }

        if (seriesAvailability.isEmpty()) {
            root.add(new DefaultMutableTreeNode("No outputs available from selected sources"));
            return;
        }

        // Sort series names using natural sorting
        List<String> sortedSeries = new ArrayList<>(seriesAvailability.keySet());
        sortedSeries.sort(RunManager::naturalCompare);

        // Build hybrid tree structure
        for (String seriesName : sortedSeries) {
            List<Object> sourcesWithSeries = seriesAvailability.get(seriesName);

            if (sourcesWithSeries.size() == 1) {
                // Only one source has this series - create standalone leaf node
                Object singleSource = sourcesWithSeries.get(0);
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, singleSource, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            } else {
                // Multiple sources have this series - create parent with children
                SeriesParentNode parentNode = new SeriesParentNode(seriesName, sourcesWithSeries);
                DefaultMutableTreeNode parentTreeNode = new DefaultMutableTreeNode(parentNode);

                // Add children for each source
                for (Object source : sourcesWithSeries) {
                    SeriesLeafNode childLeafNode = new SeriesLeafNode(seriesName, source, false);
                    parentTreeNode.add(new DefaultMutableTreeNode(childLeafNode));
                }

                addHierarchicalNodeToTree(root, seriesName, parentTreeNode);
            }
        }
    }

    /**
     * Updates the timeseries tree for multiple selected runs using smart hybrid structure.
     * Series available in multiple runs become parent nodes with run children.
     * Series available in only one run become simple leaf nodes.
     */
    private void updateOutputsTreeForMultipleRuns(List<RunInfo> selectedRuns) {
        // Remember current expansion state
        List<TreePath> expandedPaths = new ArrayList<>();
        for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
            TreePath path = timeseriesTree.getPathForRow(i);
            if (timeseriesTree.isExpanded(path)) {
                expandedPaths.add(path);
            }
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        root.removeAllChildren();

        // If only one run selected, use the original simple tree structure
        if (selectedRuns.size() == 1) {
            updateOutputsTreeSingleRun(root, selectedRuns.get(0));
        } else {
            // Build multi-run hybrid tree
            updateOutputsTreeMultiRun(root, selectedRuns);
        }

        timeseriesTreeModel.reload();

        // Restore expansion state or expand all if first time
        if (expandedPaths.isEmpty()) {
            // First time - expand all nodes
            for (int i = 0; i < timeseriesTree.getRowCount(); i++) {
                timeseriesTree.expandRow(i);
            }
        } else {
            // Restore previous expansion state
            for (TreePath expandedPath : expandedPaths) {
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    timeseriesTree.expandPath(newPath);
                }
            }
        }

        // Restore selection to match what's currently plotted
        // This ensures plots stay visible when tree rebuilds (e.g., adding Run_2)
        Set<String> restoredSeries = restoreTreeSelectionFromSelectedSeries();

        // Reconcile: remove series that are no longer in the tree from plots
        reconcileSelectedSeriesWithTree(restoredSeries);
    }

    /**
     * Populates the tree for a single run using SeriesLeafNode objects.
     */
    private void updateOutputsTreeSingleRun(DefaultMutableTreeNode root, RunInfo runInfo) {
        List<String> outputs = null;
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            outputs = program.getOutputsGenerated();
        }

        if (outputs != null && !outputs.isEmpty()) {
            outputs.sort(RunManager::naturalCompare);
            for (String seriesName : outputs) {
                // Create standalone leaf node with showSeriesName=true (shows "ds_1 [Run_1]")
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, runInfo, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            }
        } else {
            String message = runInfo.getRunStatus() == RunStatus.DONE ?
                "No outputs available" :
                "Outputs will appear when simulation completes";
            root.add(new DefaultMutableTreeNode(message));
        }
    }

    /**
     * Populates the tree for multiple runs using smart hybrid structure.
     */
    private void updateOutputsTreeMultiRun(DefaultMutableTreeNode root, List<RunInfo> selectedRuns) {
        // Map: series name -> list of RunInfo that have this series
        Map<String, List<RunInfo>> seriesAvailability = new LinkedHashMap<>();

        // Collect all outputs from all selected runs
        for (RunInfo runInfo : selectedRuns) {
            List<String> outputs = null;
            if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
                RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
                outputs = program.getOutputsGenerated();
            }

            if (outputs != null) {
                for (String output : outputs) {
                    seriesAvailability.computeIfAbsent(output, k -> new ArrayList<>()).add(runInfo);
                }
            }
        }

        if (seriesAvailability.isEmpty()) {
            root.add(new DefaultMutableTreeNode("No outputs available from selected runs"));
            return;
        }

        // Sort series names using natural sorting (case-insensitive, number-aware)
        List<String> sortedSeries = new ArrayList<>(seriesAvailability.keySet());
        sortedSeries.sort(RunManager::naturalCompare);

        // Build hybrid tree structure
        for (String seriesName : sortedSeries) {
            List<RunInfo> runsWithSeries = seriesAvailability.get(seriesName);

            if (runsWithSeries.size() == 1) {
                // Only one run has this series - create standalone leaf node
                RunInfo singleRun = runsWithSeries.get(0);
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, singleRun, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            } else {
                // Multiple runs have this series - create parent with children
                SeriesParentNode parentNode = new SeriesParentNode(seriesName, new ArrayList<>(runsWithSeries));
                DefaultMutableTreeNode parentTreeNode = new DefaultMutableTreeNode(parentNode);

                // Add children for each run
                for (RunInfo runInfo : runsWithSeries) {
                    SeriesLeafNode childLeaf = new SeriesLeafNode(seriesName, runInfo, false);
                    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(childLeaf);
                    parentTreeNode.add(childNode);
                }

                addHierarchicalNodeToTree(root, seriesName, parentTreeNode);
            }
        }
    }

    /**
     * Adds a node to the tree while preserving hierarchical structure (dot-delimited paths).
     */
    private void addHierarchicalNodeToTree(DefaultMutableTreeNode root, String seriesName, DefaultMutableTreeNode nodeToAdd) {
        String[] parts = seriesName.split("\\.");
        if (parts.length == 1) {
            // No hierarchy, add directly
            root.add(nodeToAdd);
        } else {
            // Build hierarchy
            DefaultMutableTreeNode current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                DefaultMutableTreeNode child = findOrCreateChild(current, parts[i]);
                current = child;
            }
            current.add(nodeToAdd);
        }
    }

    /**
     * Finds or creates a child node with the given name.
     */
    private DefaultMutableTreeNode findOrCreateChild(DefaultMutableTreeNode parent, String childName) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            Object userObject = child.getUserObject();
            if (userObject instanceof String && userObject.equals(childName)) {
                return child;
            }
        }
        // Not found, create new
        DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(childName);
        parent.add(newChild);
        return newChild;
    }

    // Helper classes to track series nodes with run information

    /**
     * Represents a leaf node that is a plottable series from a specific source (run or dataset).
     */
    private static class SeriesLeafNode {
        final String seriesName;
        final Object source;  // Either RunInfo or LoadedDatasetInfo
        final boolean showSeriesName;  // If true, show "ds_1 [Run_1]", else just "Run_1"

        SeriesLeafNode(String seriesName, Object source, boolean showSeriesName) {
            this.seriesName = seriesName;
            this.source = source;
            this.showSeriesName = showSeriesName;
        }

        @Override
        public String toString() {
            String sourceName = source instanceof RunInfo
                ? ((RunInfo) source).runName
                : ((LoadedDatasetInfo) source).fileName;

            if (showSeriesName) {
                // Standalone leaf: show "ds_1 [Run_1]" or "ds_1 [data.csv]"
                String lastSegment = seriesName.contains(".")
                    ? seriesName.substring(seriesName.lastIndexOf('.') + 1)
                    : seriesName;
                return lastSegment + " [" + sourceName + "]";
            } else {
                // Child of parent node: just show "Run_1" or "data.csv"
                return sourceName;
            }
        }
    }

    /**
     * Represents a parent node for a series available in multiple sources (runs and/or datasets).
     */
    private static class SeriesParentNode {
        final String seriesName;
        final List<Object> sourcesWithSeries;  // List of RunInfo and/or LoadedDatasetInfo

        SeriesParentNode(String seriesName, List<Object> sourcesWithSeries) {
            this.seriesName = seriesName;
            this.sourcesWithSeries = sourcesWithSeries;
        }

        @Override
        public String toString() {
            // Extract just the last segment of the series name (e.g., "ds_1" from "node.node9.ds_1")
            String lastSegment = seriesName.contains(".")
                ? seriesName.substring(seriesName.lastIndexOf('.') + 1)
                : seriesName;

            String[] sourceLabels = sourcesWithSeries.stream()
                .map(s -> s instanceof RunInfo ? ((RunInfo) s).runName : ((LoadedDatasetInfo) s).fileName)
                .toArray(String[]::new);
            return lastSegment + " [" + String.join(", ", sourceLabels) + "]";
        }
    }

    /**
     * Creates a new TimeSeriesData with a different name but same data.
     */
    private TimeSeriesData renameTimeSeriesData(TimeSeriesData original, String newName) {
        // Convert timestamps back to LocalDateTime array
        long[] timestamps = original.getTimestamps();
        java.time.LocalDateTime[] dateTimes = new java.time.LocalDateTime[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            dateTimes[i] = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                java.time.ZoneOffset.UTC
            );
        }

        // Create new TimeSeriesData with new name
        return new TimeSeriesData(newName, dateTimes, original.getValues());
    }

    /**
     * Handles selection changes in the timeseries tree.
     * Supports recursive selection: selecting a parent node plots all its leaf children.
     * Fetches timeseries data for leaf nodes and updates plot and stats.
     */
    private void onOutputsTreeSelectionChanged(TreeSelectionEvent e) {
        // Ignore selection changes during programmatic updates
        if (isUpdatingSelection) {
            return;
        }

        TreePath[] selectedPaths = timeseriesTree.getSelectionPaths();

        if (selectedPaths == null || selectedPaths.length == 0) {
            // Clear plot and stats when nothing is selected
            clearPlotAndStats();
            selectedSeries.clear();
            return;
        }

        // Collect all leaf nodes recursively (parent selection = all children)
        List<SeriesLeafNode> allLeaves = new ArrayList<>();
        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            collectLeafNodes(node, allLeaves);
        }

        // If no valid leaves found, clear everything
        if (allLeaves.isEmpty()) {
            clearPlotAndStats();
            selectedSeries.clear();
            return;
        }

        // Build new set of selected series (with unique keys)
        // For runs: "seriesName [RunName]"
        // For datasets: "seriesName [filename]"
        Set<String> newSelectedSeries = new HashSet<>();
        Map<String, SeriesLeafNode> seriesKeyToLeaf = new HashMap<>();

        for (SeriesLeafNode leaf : allLeaves) {
            String seriesKey;
            if (leaf.source instanceof RunInfo) {
                // Run series: add run name suffix
                String runName = ((RunInfo) leaf.source).runName;
                seriesKey = leaf.seriesName + " [" + runName + "]";
            } else {
                // Dataset series: add filename suffix for display
                LoadedDatasetInfo datasetInfo = (LoadedDatasetInfo) leaf.source;
                seriesKey = leaf.seriesName + " [" + datasetInfo.fileName + "]";
            }
            newSelectedSeries.add(seriesKey);
            seriesKeyToLeaf.put(seriesKey, leaf);
        }

        // Check if there's overlap between old and new selections
        // Reset zoom if: (1) first selection (old empty), OR (2) selection completely changed (no overlap)
        // Don't reset zoom if: there's overlap (adding series or Last updating)
        boolean hasOverlap = selectedSeries.stream().anyMatch(newSelectedSeries::contains);
        final boolean shouldResetZoom = selectedSeries.isEmpty() || !hasOverlap;

        // Determine which series to add and which to remove
        Set<String> seriesToAdd = new HashSet<>(newSelectedSeries);
        seriesToAdd.removeAll(selectedSeries);

        Set<String> seriesToRemove = new HashSet<>(selectedSeries);
        seriesToRemove.removeAll(newSelectedSeries);

        // Remove series that are no longer selected
        for (String seriesKey : seriesToRemove) {
            plotDataSet.removeSeries(seriesKey);
            seriesColorMap.remove(seriesKey);

            // Remove from all stats tables
            tabManager.removeSeriesFromStatsTabs(seriesKey);

            // Remove from legend in all plots
            for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                panel.removeLegendSeries(seriesKey);
            }
        }

        // Group series by their underlying data source
        // For runs: sessionKey + seriesName (handles multiple series referencing same data)
        // For datasets: just seriesName (already loaded in plotDataSet)
        Map<String, List<String>> dataSourceToSeriesKeys = new LinkedHashMap<>();
        Set<String> datasetSeriesKeys = new HashSet<>();  // Track dataset series separately

        for (String seriesKey : seriesToAdd) {
            SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            // Assign consistent color to new series
            Color seriesColor = getColorForSeries(seriesKey);
            seriesColorMap.put(seriesKey, seriesColor);

            if (leaf.source instanceof LoadedDatasetInfo) {
                // Dataset series - already loaded, just track for later processing
                datasetSeriesKeys.add(seriesKey);
            } else {
                // Run series - need to fetch from session
                RunInfo runInfo = (RunInfo) leaf.source;

                // Resolve actual session (handles "Last" run)
                SessionManager.KalixSession resolvedSession = resolveRunInfoSession(runInfo);
                if (resolvedSession == null) {
                    // Can happen if "Last" is selected but no run has completed yet
                    continue;
                }

                String sessionKey = resolvedSession.getSessionKey();
                String seriesName = leaf.seriesName;
                String dataSourceKey = sessionKey + "|" + seriesName;

                dataSourceToSeriesKeys.computeIfAbsent(dataSourceKey, k -> new ArrayList<>()).add(seriesKey);
            }
        }

        // Process each unique data source once
        for (Map.Entry<String, List<String>> entry : dataSourceToSeriesKeys.entrySet()) {
            String dataSourceKey = entry.getKey();
            List<String> seriesKeys = entry.getValue();

            String[] parts = dataSourceKey.split("\\|", 2);
            String sessionKey = parts[0];
            String seriesName = parts[1];

            // Check if we already have this data cached
            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, seriesName);
            if (cachedData != null) {
                // Add immediately with cached data for ALL series keys that reference it
                for (String seriesKey : seriesKeys) {
                    TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                    addSeriesToPlot(renamedData, seriesColorMap.get(seriesKey));

                    // Add to all stats tables with aggregation
                    tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
                }
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                // Show loading state for ALL series that reference this data
                for (String seriesKey : seriesKeys) {
                    tabManager.addLoadingSeriesInStatsTabs(seriesKey);
                }

                // Capture series keys for use in callback
                final List<String> capturedSeriesKeys = new ArrayList<>(seriesKeys);

                // Request the timeseries data ONCE for all series that reference it
                timeSeriesRequestManager.requestTimeSeries(sessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            // Add data for ALL series keys that reference this data source
                            for (String capturedSeriesKey : capturedSeriesKeys) {
                                // Check if this series is still selected
                                if (selectedSeries.contains(capturedSeriesKey)) {
                                    // Rename series to include run label
                                    TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, capturedSeriesKey);
                                    addSeriesToPlot(renamedData, seriesColorMap.get(capturedSeriesKey));

                                    // Update all stats tables with aggregation
                                    tabManager.updateSeriesInStatsTabsWithAggregation(capturedSeriesKey, renamedData);
                                }
                            }

                            // Zoom to fit in all plot panels (once for all series) if selection completely changed
                            if (shouldResetZoom) {
                                for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                                    panel.zoomToFit();
                                }
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            // Add error for ALL series that reference this data source
                            for (String capturedSeriesKey : capturedSeriesKeys) {
                                if (selectedSeries.contains(capturedSeriesKey)) {
                                    // Add error to all stats tables
                                    tabManager.addErrorSeriesInStatsTabs(capturedSeriesKey, throwable.getMessage());
                                }
                            }
                        });
                        return null;
                    });
            } else {
                // Request already in progress, show loading state for ALL series that reference this data
                for (String seriesKey : seriesKeys) {
                    tabManager.addLoadingSeriesInStatsTabs(seriesKey);
                }
            }
        }

        // Process dataset series (stored in datasetSeriesCache, not plotDataSet yet)
        for (String seriesKey : datasetSeriesKeys) {
            SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            // Get cached data (like runs do)
            TimeSeriesData cachedData = datasetSeriesCache.get(leaf.seriesName);
            if (cachedData != null) {
                // Rename to add display suffix for legend/export
                TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);

                // Add to plotDataSet and plot panels (just like runs)
                addSeriesToPlot(renamedData, seriesColorMap.get(seriesKey));

                // Add to stats tables with aggregation
                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
            } else {
                // Shouldn't happen, but handle gracefully
                logger.warn("Dataset series not found in cache: " + leaf.seriesName);
                tabManager.addErrorSeriesInStatsTabs(seriesKey, "Series not found");
            }
        }

        // Update the selected series set
        selectedSeries = newSelectedSeries;

        // Update plot visibility, passing zoom reset flag
        updatePlotVisibility(shouldResetZoom);

        // If selection completely changed and we don't have the new data yet, zoom after data loads
        if (!plotDataSet.isEmpty() && shouldResetZoom) {
            for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                panel.zoomToFit();
            }
        }
    }

    /**
     * Recursively collects all SeriesLeafNode objects from a tree node.
     * If the node is a leaf, adds it directly. If it's a parent, recursively collects from children.
     * This enables selecting a parent node (like "node.node9") to plot all its children.
     *
     * Top-level folder nodes (direct children of root) are NOT recursively expanded to prevent
     * accidentally plotting hundreds of series.
     */
    private void collectLeafNodes(DefaultMutableTreeNode node, List<SeriesLeafNode> leaves) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode (plottable leaf)
        if (userObject instanceof SeriesLeafNode) {
            leaves.add((SeriesLeafNode) userObject);
            return;
        }

        // Check if this is a SeriesParentNode (select all children)
        if (userObject instanceof SeriesParentNode) {
            SeriesParentNode parentNode = (SeriesParentNode) userObject;
            // Add all runs for this series
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectLeafNodes(child, leaves);
            }
            return;
        }

        // For regular folder nodes (String user objects), check depth
        if (!node.isLeaf() && userObject instanceof String) {
            // Check if this is a top-level folder (direct child of invisible root)
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
            if (node.getParent() == root) {
                // Top-level folder - don't recurse to prevent accidental mass plotting
                // User must select specific sub-folders or series
                return;
            }

            // Not top-level - recurse normally
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectLeafNodes(child, leaves);
            }
        }
    }

    /**
     * Get the full series name for a tree node by walking up the path
     */
    private String getFullSeriesName(DefaultMutableTreeNode node) {
        if (node == null) return null;

        List<String> pathParts = new ArrayList<>();
        TreeNode currentNode = node;

        while (currentNode != null && currentNode.getParent() != null) {
            if (currentNode instanceof DefaultMutableTreeNode) {
                String part = ((DefaultMutableTreeNode) currentNode).getUserObject().toString();
                pathParts.add(0, part); // Add to beginning to build path from root to leaf
            }
            currentNode = currentNode.getParent();
        }

        return pathParts.isEmpty() ? null : String.join(".", pathParts);
    }

    /**
     * Check if a node represents a special message (like "No outputs available")
     */
    private boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
        String variableName = node.getUserObject().toString();
        return variableName.equals("No outputs available") ||
               variableName.equals("Outputs will appear when simulation completes");
    }

    /**
     * Get the currently selected run information
     */
    private RunInfo getSelectedRunInfo() {
        TreePath selectedPath = timeseriesSourceTree.getSelectionPath();
        if (selectedPath == null) {
            return null;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        Object userObject = selectedNode.getUserObject();

        if (userObject instanceof RunInfo) {
            return (RunInfo) userObject;
        }

        return null;
    }

    /**
     * Adds a dot-delimited output string to the tree structure.
     * For example: "hydrology.streamflow.daily" becomes a tree:
     *   hydrology
     *     └── streamflow
     *           └── daily
     */
    private void addOutputToTree(DefaultMutableTreeNode root, String dotDelimitedOutput) {
        String[] parts = dotDelimitedOutput.split("\\.");
        DefaultMutableTreeNode currentNode = root;

        for (String part : parts) {
            // Look for existing child with this name
            DefaultMutableTreeNode childNode = null;
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (child.getUserObject().toString().equals(part)) {
                    childNode = child;
                    break;
                }
            }

            // If child doesn't exist, create it
            if (childNode == null) {
                childNode = new DefaultMutableTreeNode(part);
                currentNode.add(childNode);
            }

            currentNode = childNode;
        }
    }

    /**
     * Finds an equivalent path in the current tree based on node names.
     */
    private TreePath findEquivalentPath(TreePath oldPath) {
        if (oldPath == null || oldPath.getPathCount() <= 1) {
            return null;
        }

        // Build path of node names
        String[] pathNames = new String[oldPath.getPathCount() - 1]; // Skip root
        for (int i = 1; i < oldPath.getPathCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) oldPath.getPathComponent(i);
            pathNames[i - 1] = node.getUserObject().toString();
        }

        // Try to find equivalent path in current tree
        DefaultMutableTreeNode currentNode = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        List<Object> newPathComponents = new ArrayList<>();
        newPathComponents.add(currentNode);

        for (String pathName : pathNames) {
            DefaultMutableTreeNode foundChild = null;
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (child.getUserObject().toString().equals(pathName)) {
                    foundChild = child;
                    break;
                }
            }

            if (foundChild == null) {
                return null; // Path doesn't exist in new tree
            }

            newPathComponents.add(foundChild);
            currentNode = foundChild;
        }

        return new TreePath(newPathComponents.toArray());
    }

    /**
     * Selects the run associated with the given sessionKey.
     * This will expand the tree and select the run node if found.
     */
    private void selectRun(String sessionKey) {
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode runNode = sessionToTreeNode.get(sessionKey);
            if (runNode != null) {
                TreePath pathToRun = new TreePath(runNode.getPath());

                // Expand parent nodes to make the run visible
                timeseriesSourceTree.expandPath(new TreePath(currentRunsNode.getPath()));

                // Select the run
                isUpdatingSelection = true;
                timeseriesSourceTree.setSelectionPath(pathToRun);
                isUpdatingSelection = false;

                // Scroll to make the selection visible
                timeseriesSourceTree.scrollPathToVisible(pathToRun);

                // Update timeseries tree
                updateOutputsTree();
            }
        });
    }

    /**
     * Enum representing the status of a simulation run.
     * This is separate from CLI session status.
     */
    public enum RunStatus {
        STARTING("Starting"),
        LOADING("Loading Model"),
        RUNNING("Running"),
        DONE("Done"),
        ERROR("Error"),
        STOPPED("Stopped");

        private final String displayName;

        RunStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Data class to hold run information.
     */
    private static class RunInfo {
        String runName; // Made non-final to allow renaming
        final SessionManager.KalixSession session;

        RunInfo(String runName, SessionManager.KalixSession session) {
            this.runName = runName;
            this.session = session;
        }

        /**
         * Updates the run name. Used when renaming runs.
         */
        public void setRunName(String newName) {
            this.runName = newName;
        }

        /**
         * Gets the run status based on the session's active program state.
         */
        public RunStatus getRunStatus() {
            if (session.getActiveProgram() instanceof RunModelProgram) {
                RunModelProgram program = (RunModelProgram) session.getActiveProgram();

                if (program.isFailed()) {
                    return RunStatus.ERROR;
                } else if (program.isCompleted()) {
                    return RunStatus.DONE;
                } else {
                    String stateDesc = program.getStateDescription();
                    if (stateDesc.contains("Loading")) {
                        return RunStatus.LOADING;
                    } else if (stateDesc.contains("Running")) {
                        return RunStatus.RUNNING;
                    } else {
                        return RunStatus.STARTING;
                    }
                }
            } else {
                // No active program or different program type
                // Determine status based on session state
                switch (session.getState()) {
                    case STARTING:
                        return RunStatus.STARTING;
                    case RUNNING:
                        return RunStatus.RUNNING;
                    case READY:
                        return RunStatus.DONE;
                    case ERROR:
                        return RunStatus.ERROR;
                    case TERMINATED:
                        return RunStatus.STOPPED;
                    default:
                        return RunStatus.STARTING;
                }
            }
        }

        @Override
        public String toString() {
            return runName;
        }
    }

    /**
     * Data class to hold loaded dataset information.
     */
    private static class LoadedDatasetInfo {
        final String fileName;
        final File file;

        LoadedDatasetInfo(String fileName, File file) {
            this.fileName = fileName;
            this.file = file;
        }

        @Override
        public String toString() {
            return fileName;
        }
    }

    /**
     * Assigns the first unused color from the palette.
     * When a series is removed, its color becomes available for reuse.
     * Colors are assigned sequentially: Blue, Orange, Green, etc.
     * If a gap exists (e.g., Blue removed), new series fills that gap.
     */
    private Color getColorForSeries(String seriesName) {
        // Find which color indices are currently in use
        Set<Integer> usedIndices = new HashSet<>();
        for (Color color : seriesColorMap.values()) {
            for (int i = 0; i < SERIES_COLORS.length; i++) {
                if (SERIES_COLORS[i].equals(color)) {
                    usedIndices.add(i);
                    break;
                }
            }
        }

        // Find first available color index
        for (int i = 0; i < SERIES_COLORS.length; i++) {
            if (!usedIndices.contains(i)) {
                return SERIES_COLORS[i];
            }
        }

        // All colors used (10+ series), wrap around
        return SERIES_COLORS[seriesColorMap.size() % SERIES_COLORS.length];
    }

    /**
     * Adds a series to plotDataSet and all plot panels with the specified color.
     * Used for both run series (from session) and dataset series (from cache).
     */
    private void addSeriesToPlot(TimeSeriesData timeSeriesData, Color seriesColor) {
        plotDataSet.addSeries(timeSeriesData);
        seriesColorMap.put(timeSeriesData.getName(), seriesColor);
        updateAllTabs(false); // Don't reset zoom when adding series

        // Add to legend in all plot panels
        for (PlotPanel panel : tabManager.getAllPlotPanels()) {
            panel.addLegendSeries(timeSeriesData.getName(), seriesColor);
        }
    }

    /**
     * Updates all visualization tabs with current data.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    private void updatePlotVisibility(boolean resetZoom) {
        updateAllTabs(resetZoom);
    }

    /**
     * Updates all tabs with the current dataset and color map.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    private void updateAllTabs(boolean resetZoom) {
        tabManager.updateAllTabs(resetZoom);
    }

    /**
     * Clears all plots and stats tables.
     */
    private void clearPlotAndStats() {
        plotDataSet.removeAllSeries();
        seriesColorMap.clear();
        updateAllTabs(true); // Reset zoom when clearing (though no data to zoom)

        // Clear legend in all plot panels
        for (PlotPanel panel : tabManager.getAllPlotPanels()) {
            panel.clearLegend();
        }

        // Clear all stats tables
        for (StatsTableModel model : tabManager.getAllStatsModels()) {
            model.clear();
        }
    }


    /**
     * Custom tree cell renderer for timeseries tree.
     */
    private static class OutputsTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                String variableName = node.getUserObject().toString();

                // Only add icons to leaf nodes
                if (leaf && !isSpecialMessageNode(node)) {
                    int treeIconSize = 12; // Same size as run tree icons

                    // Determine icon based on the variable name
                    if (variableName.equals("dsflow") || variableName.equals("usflow") || variableName.matches("ds_\\d+")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.WATER, treeIconSize));
                    } else if (variableName.equals("storage") || variableName.equals("volume")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.GLASS_WHISKEY, treeIconSize));
                    } else if (variableName.equals("runoff_depth")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.TINT, treeIconSize));
                    } else if (variableName.equals("demand")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.ARROW_CIRCLE_LEFT, treeIconSize));
                    } else if (variableName.equals("diversion")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.ARROW_ALT_CIRCLE_LEFT, treeIconSize));
                    } else if (variableName.equals("inflow") || variableName.equals("runoff_volume")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.ARROW_ALT_CIRCLE_RIGHT, treeIconSize));
                    } else {
                        // Default icon for any other leaf nodes
                        setIcon(FontIcon.of(FontAwesomeSolid.WAVE_SQUARE, treeIconSize));
                    }
                } else {
                    // No icon for non-leaf nodes
                    setIcon(null);
                }
            }

            return this;
        }

        /**
         * Helper method to check if a node represents a special message (reused from parent class logic)
         */
        private boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
            String variableName = node.getUserObject().toString();
            return variableName.equals("No outputs available") ||
                   variableName.equals("Outputs will appear when simulation completes");
        }
    }

    /**
     * Custom tree cell renderer for run tree.
     */
    private static class RunTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object userObject = node.getUserObject();

                if (userObject instanceof RunInfo) {
                    RunInfo runInfo = (RunInfo) userObject;
                    setText(runInfo.runName);

                    RunStatus runStatus = runInfo.getRunStatus();

                    // Color code by run status
                    if (!sel) {
                        switch (runStatus) {
                            case DONE:
                                setForeground(new Color(0, 120, 0)); // Dark green
                                break;
                            case RUNNING:
                            case LOADING:
                                setForeground(new Color(0, 0, 200)); // Blue
                                break;
                            case ERROR:
                                setForeground(new Color(200, 0, 0)); // Red
                                break;
                            case STARTING:
                                setForeground(new Color(150, 150, 0)); // Dark yellow
                                break;
                            case STOPPED:
                                setForeground(new Color(128, 128, 128)); // Gray
                                break;
                            default:
                                setForeground(Color.BLACK);
                                break;
                        }
                    }

                    // Set appropriate FontAwesome icon based on run status (25% smaller than toolbar icons)
                    int treeIconSize = 12; // 75% of AppConstants.TOOLBAR_ICON_SIZE (16px)
                    switch (runStatus) {
                        case STARTING:
                        case LOADING:
                            setIcon(FontIcon.of(FontAwesomeSolid.SUN, treeIconSize));
                            break;
                        case RUNNING:
                            setIcon(FontIcon.of(FontAwesomeSolid.ROCKET, treeIconSize));
                            break;
                        case DONE:
                            setIcon(FontIcon.of(FontAwesomeSolid.GRIP_HORIZONTAL, treeIconSize));
                            break;
                        case ERROR:
                            setIcon(FontIcon.of(FontAwesomeSolid.BUG, treeIconSize));
                            break;
                        case STOPPED:
                            setIcon(FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, treeIconSize));
                            break;
                        default:
                            setIcon(null);
                            break;
                    }
                } else if (userObject instanceof LoadedDatasetInfo) {
                    LoadedDatasetInfo datasetInfo = (LoadedDatasetInfo) userObject;
                    setText(datasetInfo.fileName);

                    // Color loaded datasets blue
                    if (!sel) {
                        setForeground(new Color(0, 0, 200)); // Blue
                    }

                    // Set layer-group icon for loaded datasets
                    int treeIconSize = 12; // 75% of AppConstants.TOOLBAR_ICON_SIZE (16px)
                    setIcon(FontIcon.of(FontAwesomeSolid.LAYER_GROUP, treeIconSize));
                }
            }

            return this;
        }
    }

    /**
     * Table model for displaying statistics of multiple time series.
     */
    public static class StatsTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Series", "Min", "Max", "Mean", "Points"};
        private final List<SeriesStats> seriesData = new ArrayList<>();

        static class SeriesStats {
            String name;
            String min;
            String max;
            String mean;
            String points;
            SeriesStats(String name) {
                this.name = name;
                this.min = "";
                this.max = "";
                this.mean = "";
                this.points = "";
            }

            SeriesStats(String name, TimeSeriesData data) {
                this.name = name;
                if (data.getPointCount() == 0) {
                    this.min = "-";
                    this.max = "-";
                    this.mean = "-";
                    this.points = "0";
                } else {
                    this.min = String.format("%.3f", data.getMinValue());
                    this.max = String.format("%.3f", data.getMaxValue());
                    this.mean = String.format("%.3f", data.getMeanValue());
                    this.points = String.valueOf(data.getPointCount());
                }
            }
        }

        @Override
        public int getRowCount() {
            return seriesData.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= seriesData.size()) return "";

            SeriesStats stats = seriesData.get(rowIndex);
            switch (columnIndex) {
                case 0: return stats.name;
                case 1: return stats.min;
                case 2: return stats.max;
                case 3: return stats.mean;
                case 4: return stats.points;
                default: return "";
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        public void addLoadingSeries(String seriesName) {
            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(seriesName));
            seriesData.add(new SeriesStats(seriesName));
            fireTableDataChanged();
        }

        public void addOrUpdateSeries(TimeSeriesData data) {
            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(data.getName()));
            seriesData.add(new SeriesStats(data.getName(), data));
            fireTableDataChanged();
        }

        public void addErrorSeries(String seriesName, String errorMessage) {
            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(seriesName));
            SeriesStats errorStats = new SeriesStats(seriesName);
            errorStats.min = "Error";
            errorStats.max = "Error";
            errorStats.mean = "Error";
            errorStats.points = "Error";
            seriesData.add(errorStats);
            fireTableDataChanged();
        }

        public void removeSeries(String seriesName) {
            seriesData.removeIf(stats -> stats.name.equals(seriesName));
            fireTableDataChanged();
        }

        public void clear() {
            seriesData.clear();
            fireTableDataChanged();
        }
    }
}