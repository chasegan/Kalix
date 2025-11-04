package com.kalix.ide.windows;

import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.RunContextMenuManager;
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

    // Manager instances
    private OutputsTreeBuilder outputsTreeBuilder;
    private DatasetLoaderManager datasetLoaderManager;
    private RunContextMenuManager runContextMenuManager;

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
        initializeManagers();
        setupWindowListeners();
        setupSessionEventListener();
        datasetLoaderManager.setupDragAndDrop(this);
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
                    } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                        DatasetLoaderManager.LoadedDatasetInfo datasetInfo = (DatasetLoaderManager.LoadedDatasetInfo) userObject;
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

    /**
     * Initializes manager instances with required dependencies.
     */
    private void initializeManagers() {
        // OutputsTreeBuilder - handles tree building logic
        outputsTreeBuilder = new OutputsTreeBuilder(
            timeseriesTree,
            timeseriesTreeModel,
            this::getSeriesNamesFromSource,  // Callback to get series names
            RunManager::naturalCompare        // Natural sorting callback
        );

        // DatasetLoaderManager - handles dataset file loading
        datasetLoaderManager = new DatasetLoaderManager(
            this,                             // Parent frame
            datasetSeriesCache,               // Series cache
            loadedDatasetsNode,               // Tree node
            treeModel,                        // Tree model
            statusUpdater,                    // Status updater
            this::onDatasetLoaded             // Callback after load
        );

        // RunContextMenuManager - handles context menus
        runContextMenuManager = new RunContextMenuManager(
            this,                             // Parent frame
            timeseriesSourceTree,             // Run tree
            timeseriesTree,                   // Outputs tree
            treeModel,                        // Tree model
            stdioTaskManager,                 // Task manager
            statusUpdater,                    // Status updater
            () -> baseDirectorySupplier != null ? baseDirectorySupplier.get() : null,  // Base directory supplier
            () -> editorTextSupplier != null ? editorTextSupplier.get() : null,        // Editor text supplier
            sessionToRunName,                 // Session to run name map
            this::refreshRuns                 // Refresh callback
        );

        // Set up context menus
        runContextMenuManager.setupRunTreeContextMenu();
        runContextMenuManager.setupOutputsTreeContextMenu(this::expandAllFromSelected, this::collapseAllFromSelected);
    }

    /**
     * Gets series names from a source (RunInfo or LoadedDatasetInfo).
     * Used by OutputsTreeBuilder.
     */
    private List<String> getSeriesNamesFromSource(Object source) {
        if (source instanceof RunInfo) {
            RunInfo runInfo = (RunInfo) source;
            if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
                RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
                List<String> outputs = program.getOutputsGenerated();
                return (outputs != null) ? outputs : Collections.emptyList();
            }
            return Collections.emptyList();
        } else if (source instanceof DatasetLoaderManager.LoadedDatasetInfo) {
            return getSeriesNamesFromDataset((DatasetLoaderManager.LoadedDatasetInfo) source);
        }
        return Collections.emptyList();
    }

    /**
     * Gets series names from a dataset by querying the cache.
     * Filters series names that match the dataset's file prefix.
     *
     * @param datasetInfo The dataset to get series names from
     * @return List of series names from this dataset
     */
    private List<String> getSeriesNamesFromDataset(DatasetLoaderManager.LoadedDatasetInfo datasetInfo) {
        String sanitizedFilename = sanitizeToIdentifier(datasetInfo.fileName);
        String prefix = "file." + sanitizedFilename + ".";

        // Get series from cache (NOT plotDataSet) - mirrors how runs work
        return datasetSeriesCache.keySet().stream()
            .filter(name -> name.startsWith(prefix))
            .sorted(RunManager::naturalCompare)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Callback invoked after a dataset is loaded.
     * Used by DatasetLoaderManager.
     */
    private void onDatasetLoaded() {
        // Refresh the tree to show the newly loaded dataset
        refreshRuns();
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
        renameItem.addActionListener(e -> runContextMenuManager.renameRun());
        contextMenu.add(renameItem);

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> runContextMenuManager.removeRun());
        contextMenu.add(removeItem);

        contextMenu.addSeparator();

        JMenuItem saveResultsItem = new JMenuItem("Save results (csv)");
        saveResultsItem.addActionListener(e -> runContextMenuManager.saveResults());
        contextMenu.add(saveResultsItem);

        JMenuItem showModelItem = new JMenuItem("Show Model");
        showModelItem.addActionListener(e -> runContextMenuManager.showModel());
        contextMenu.add(showModelItem);

        JMenuItem diffItem = new JMenuItem("Show Model Changes");
        diffItem.addActionListener(e -> runContextMenuManager.diffModel());
        contextMenu.add(diffItem);

        JMenuItem sessionManagerItem = new JMenuItem("View in KalixCLI Session Manager");
        sessionManagerItem.addActionListener(e -> runContextMenuManager.showInSessionManager());
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
                                datasetLoaderManager.loadDatasetFile(file);
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
                    RunStatus initialStatus = runInfo.getLocalRunStatus();

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
                    RunStatus currentStatus = runInfo.getLocalRunStatus();
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
                            updateOutputsTree();
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
        if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode) {
            OutputsTreeBuilder.SeriesLeafNode leaf = (OutputsTreeBuilder.SeriesLeafNode) userObject;
            String seriesKey;
            if (leaf.source instanceof RunInfo) {
                // Run series: add run name suffix
                String runName = ((RunInfo) leaf.source).runName;
                seriesKey = leaf.seriesName + " [" + runName + "]";
            } else {
                // Dataset series: add filename suffix
                DatasetLoaderManager.LoadedDatasetInfo datasetInfo = (DatasetLoaderManager.LoadedDatasetInfo) leaf.source;
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
        if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode) {
            OutputsTreeBuilder.SeriesLeafNode leaf = (OutputsTreeBuilder.SeriesLeafNode) userObject;
            if (leaf.source instanceof RunInfo && "Last".equals(((RunInfo) leaf.source).runName)) {
                // Replace with new RunInfo
                OutputsTreeBuilder.SeriesLeafNode newLeaf = new OutputsTreeBuilder.SeriesLeafNode(leaf.seriesName, newRunInfo, leaf.showSeriesName);
                node.setUserObject(newLeaf);
                timeseriesTreeModel.nodeChanged(node);
            }
        }
        // Update SeriesParentNode if it contains "Last"
        else if (userObject instanceof OutputsTreeBuilder.SeriesParentNode) {
            OutputsTreeBuilder.SeriesParentNode parent = (OutputsTreeBuilder.SeriesParentNode) userObject;
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
                OutputsTreeBuilder.SeriesParentNode newParent = new OutputsTreeBuilder.SeriesParentNode(parent.seriesName, newSources);
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
            outputsTreeBuilder.showEmptyTree("Select one or more runs or datasets");
            return;
        }

        // Collect all selected RunInfo and LoadedDatasetInfo objects
        List<Object> selectedRuns = new ArrayList<>();
        List<Object> selectedDatasets = new ArrayList<>();

        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObject = node.getUserObject();

            if (userObject instanceof RunInfo) {
                selectedRuns.add(userObject);
            } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                selectedDatasets.add(userObject);
            }
        }

        if (selectedRuns.isEmpty() && selectedDatasets.isEmpty()) {
            outputsTreeBuilder.showEmptyTree("Select one or more runs or datasets");
        } else {
            outputsTreeBuilder.updateTree(selectedRuns, selectedDatasets);
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
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
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
        Map<String, OutputsTreeBuilder.SeriesLeafNode> seriesKeyToLeaf = new HashMap<>();

        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            String seriesKey;
            if (leaf.source instanceof RunInfo) {
                // Run series: add run name suffix
                String runName = ((RunInfo) leaf.source).runName;
                seriesKey = leaf.seriesName + " [" + runName + "]";
            } else {
                // Dataset series: add filename suffix for display
                DatasetLoaderManager.LoadedDatasetInfo datasetInfo = (DatasetLoaderManager.LoadedDatasetInfo) leaf.source;
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
            OutputsTreeBuilder.SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            // Assign consistent color to new series
            Color seriesColor = getColorForSeries(seriesKey);
            seriesColorMap.put(seriesKey, seriesColor);

            if (leaf.source instanceof DatasetLoaderManager.LoadedDatasetInfo) {
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
            OutputsTreeBuilder.SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
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
     * Delegates to OutputsTreeBuilder.
     */
    private void collectLeafNodes(DefaultMutableTreeNode node, List<OutputsTreeBuilder.SeriesLeafNode> leaves) {
        outputsTreeBuilder.collectLeafNodes(node, leaves);
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
     * Delegates to OutputsTreeBuilder.
     */
    private boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
        return OutputsTreeBuilder.isSpecialMessageNode(node);
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
     * Implements RunContextMenuManager.RunInfo interface for compatibility with the context menu manager.
     */
    private static class RunInfo implements RunContextMenuManager.RunInfo {
        String runName; // Made non-final to allow renaming
        final SessionManager.KalixSession session;

        RunInfo(String runName, SessionManager.KalixSession session) {
            this.runName = runName;
            this.session = session;
        }

        @Override
        public String getRunName() {
            return runName;
        }

        @Override
        public void setRunName(String newName) {
            this.runName = newName;
        }

        @Override
        public SessionManager.KalixSession getSession() {
            return session;
        }

        @Override
        public RunContextMenuManager.RunStatus getRunStatus() {
            RunStatus status = getRunStatusInternal();
            // Map to manager's enum
            switch (status) {
                case DONE: return RunContextMenuManager.RunStatus.DONE;
                case ERROR: return RunContextMenuManager.RunStatus.ERROR;
                case STOPPED: return RunContextMenuManager.RunStatus.STOPPED;
                default: return RunContextMenuManager.RunStatus.RUNNING;
            }
        }

        /**
         * Gets the run status (local enum for internal use).
         */
        public RunStatus getLocalRunStatus() {
            return getRunStatusInternal();
        }

        /**
         * Internal method to get run status.
         */
        private RunStatus getRunStatusInternal() {
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

    // Helper classes for backward compatibility with internal tree-building methods
    // These reference the manager's versions but provide simplified access


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
         * Helper method to check if a node represents a special message (delegates to OutputsTreeBuilder)
         */
        private boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
            return OutputsTreeBuilder.isSpecialMessageNode(node);
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

                    RunStatus runStatus = runInfo.getLocalRunStatus();

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
                } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                    DatasetLoaderManager.LoadedDatasetInfo datasetInfo = (DatasetLoaderManager.LoadedDatasetInfo) userObject;
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