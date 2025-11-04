package com.kalix.ide.windows;

import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.RunContextMenuManager;
import com.kalix.ide.managers.SeriesDataCoordinator;
import com.kalix.ide.managers.RunSessionCoordinator;
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
    private SeriesDataCoordinator seriesDataCoordinator;
    private RunSessionCoordinator runSessionCoordinator;

    // Series color management (now managed by SeriesDataCoordinator)
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
     * Uses RunSessionCoordinator.
     */
    public static String getRunNameForSession(String sessionKey) {
        if (instance != null && instance.runSessionCoordinator != null) {
            return instance.runSessionCoordinator.getSessionToRunName().get(sessionKey);
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

        // Note: Selection listener is set up by SeriesDataCoordinator in initializeManagers()

        timeseriesScrollPane = new JScrollPane(timeseriesTree);

        // Create shared dataset
        plotDataSet = new DataSet();

        // Create tab manager with shared data (color map will be empty initially, updated by coordinator)
        tabManager = new VisualizationTabManager(plotDataSet, new HashMap<>());

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

        // RunSessionCoordinator - handles run/session lifecycle (MUST BE BEFORE RunContextMenuManager)
        runSessionCoordinator = new RunSessionCoordinator(
            stdioTaskManager,                    // Task manager
            currentRunsNode,                     // Current runs node
            lastRunNode,                         // Last run node
            treeModel,                           // Tree model
            timeseriesSourceTree,                // Run tree
            this::updateOutputsTree,             // Update outputs callback
            this::refreshLastSeries,             // Refresh last series callback
            this::createRunInfo,                 // Create RunInfo callback
            this::getRunStatusFromRunInfo,       // Get status callback
            (runInfo, ignored) -> updateRunInfoInTimeseriesTree((RunInfo) runInfo)  // Update tree callback
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
            runSessionCoordinator.getSessionToRunName(),  // Session to run name map
            this::refreshRuns                 // Refresh callback
        );

        // Set up context menus
        runContextMenuManager.setupRunTreeContextMenu();
        runContextMenuManager.setupOutputsTreeContextMenu(this::expandAllFromSelected, this::collapseAllFromSelected);

        // SeriesDataCoordinator - handles series data management and plotting
        seriesDataCoordinator = new SeriesDataCoordinator(
            timeseriesTree,                      // Timeseries tree
            timeSeriesRequestManager,            // Data fetcher
            tabManager,                          // Visualization manager
            plotDataSet,                         // Plot dataset
            datasetSeriesCache,                  // Dataset cache
            outputsTreeBuilder,                  // Tree builder
            this::resolveRunInfoSession,         // Session resolver
            this::clearPlotAndStats,             // Clear callback
            () -> updatePlotVisibility(false)    // Update callback
        );
        seriesDataCoordinator.setupSelectionListener();
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
     * Helper to create RunInfo objects. Used by RunSessionCoordinator.
     */
    private Object createRunInfo(Object params) {
        Object[] arr = (Object[]) params;
        String name = (String) arr[0];
        SessionManager.KalixSession session = (SessionManager.KalixSession) arr[1];
        return new RunInfo(name, session);
    }

    /**
     * Helper to get RunStatus from RunInfo. Used by RunSessionCoordinator.
     */
    private RunSessionCoordinator.RunStatus getRunStatusFromRunInfo(Object runInfo) {
        RunInfo info = (RunInfo) runInfo;
        RunStatus status = info.getLocalRunStatus();
        // Map to coordinator's enum
        switch (status) {
            case STARTING: return RunSessionCoordinator.RunStatus.STARTING;
            case LOADING: return RunSessionCoordinator.RunStatus.LOADING;
            case RUNNING: return RunSessionCoordinator.RunStatus.RUNNING;
            case DONE: return RunSessionCoordinator.RunStatus.DONE;
            case ERROR: return RunSessionCoordinator.RunStatus.ERROR;
            case STOPPED: return RunSessionCoordinator.RunStatus.STOPPED;
            default: return RunSessionCoordinator.RunStatus.STARTING;
        }
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
        stdioTaskManager.getSessionManager().addSessionEventListener(event -> {
            SwingUtilities.invokeLater(() -> handleSessionEvent(event));
        });
    }

    /**
     * Handles session state change events from SessionManager.
     * Called on EDT after marshaling from background thread.
     * Delegates to RunSessionCoordinator.
     *
     * @param event The session event containing state change information
     */
    private void handleSessionEvent(SessionManager.SessionEvent event) {
        runSessionCoordinator.handleSessionEvent(event);
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

    /**
     * Refreshes the run list by syncing with active sessions.
     * Delegates to RunSessionCoordinator.
     */
    public void refreshRuns() {
        runSessionCoordinator.refreshRuns();
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
     * Uses RunSessionCoordinator to get last run info.
     */
    private SessionManager.KalixSession resolveRunInfoSession(Object runInfo) {
        if (runInfo instanceof RunInfo) {
            RunInfo info = (RunInfo) runInfo;

            // Check if this is "Last" (name check)
            if (info.runName.equals("Last")) {
                // Resolve to the actual last completed run
                Object actualLastRunInfo = runSessionCoordinator.getLastRunInfo();
                if (actualLastRunInfo != null && actualLastRunInfo instanceof RunInfo) {
                    return ((RunInfo) actualLastRunInfo).session;
                }
                return null;  // No run has completed yet
            }

            // Regular run - return its session directly
            return info.session;
        }
        return null;
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
        Object lastRunInfo = runSessionCoordinator.getLastRunInfo();
        if (lastRunInfo == null) {
            return;
        }

        // Get selected series and color map from coordinator
        Set<String> selectedSeries = seriesDataCoordinator.getSelectedSeries();
        Map<String, Color> seriesColorMap = seriesDataCoordinator.getSeriesColorMap();

        // Find all series keys that end with " [Last]"
        List<String> lastSeriesKeys = selectedSeries.stream()
            .filter(key -> key.endsWith(" [Last]"))
            .collect(java.util.stream.Collectors.toList());

        if (lastSeriesKeys.isEmpty()) {
            return;
        }

        SessionManager.KalixSession session = ((RunInfo) lastRunInfo).session;
        String newSessionKey = session.getSessionKey();

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
                plotDataSet.addSeries(renamedData);
                updateAllTabs(false);
                for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                    panel.addLegendSeries(renamedData.getName(), seriesColor);
                }

                // Update all stats tables with aggregation
                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
            } else if (!timeSeriesRequestManager.isRequestInProgress(newSessionKey, seriesName)) {
                // Request the new data
                final Set<String> capturedSelectedSeries = selectedSeries;
                timeSeriesRequestManager.requestTimeSeries(newSessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            // Check if this series is still selected
                            if (capturedSelectedSeries.contains(seriesKey)) {
                                TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, seriesKey);
                                plotDataSet.addSeries(renamedData);
                                updateAllTabs(false);
                                for (PlotPanel panel : tabManager.getAllPlotPanels()) {
                                    panel.addLegendSeries(renamedData.getName(), seriesColor);
                                }

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
        TreePath[] selectedPaths = timeseriesSourceTree.getSelectionPaths();

        // Block timeseries tree selection events during rebuild and restoration
        seriesDataCoordinator.setUpdatingSelection(true);
        updateOutputsTree();
        seriesDataCoordinator.setUpdatingSelection(false);
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
            DefaultMutableTreeNode runNode = runSessionCoordinator.getSessionToTreeNode().get(sessionKey);
            if (runNode != null) {
                TreePath pathToRun = new TreePath(runNode.getPath());

                // Expand parent nodes to make the run visible
                timeseriesSourceTree.expandPath(new TreePath(currentRunsNode.getPath()));

                // Select the run
                seriesDataCoordinator.setUpdatingSelection(true);
                timeseriesSourceTree.setSelectionPath(pathToRun);
                seriesDataCoordinator.setUpdatingSelection(false);

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
     * Implements SeriesDataCoordinator.RunInfo interface for series data coordination.
     * Made public static to allow reflection access from managers.
     */
    public static class RunInfo implements RunContextMenuManager.RunInfo, SeriesDataCoordinator.RunInfo {
        public String runName; // Made non-final to allow renaming
        public final SessionManager.KalixSession session;

        public RunInfo(String runName, SessionManager.KalixSession session) {
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