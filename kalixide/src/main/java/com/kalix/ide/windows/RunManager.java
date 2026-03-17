package com.kalix.ide.windows;

import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.RunContextMenuManager;
import com.kalix.ide.managers.SeriesColorManager;
import com.kalix.ide.managers.TreeFilterManager;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.utils.NaturalSortUtils;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.models.RunInfoImpl;
import com.kalix.ide.renderers.OutputsTreeCellRenderer;
import com.kalix.ide.renderers.RunTreeCellRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Run Manager window for managing simulation runs, exploring outputs, and plotting results.
 *
 * <h2>Architecture Overview</h2>
 * The window has three main components:
 * <ul>
 *   <li><b>Left-top: Data Source Tree</b> ({@code timeseriesSourceTree}) - Shows runs and datasets</li>
 *   <li><b>Left-bottom: Timeseries Tree</b> ({@code timeseriesTree}) - Shows available output series</li>
 *   <li><b>Right: Visualization Tabs</b> ({@link VisualizationTabManager}) - Plots and statistics</li>
 * </ul>
 *
 * <h2>Data Source Tree Structure</h2>
 * <pre>
 * Root (hidden)
 * ├── Last run          → Most recently completed run (updates automatically)
 * ├── Current runs      → All runs in current session (Run_1, Run_2, ...)
 * ├── Run library       → Saved runs (future feature)
 * └── Loaded datasets   → Imported CSV/KAI files
 * </pre>
 *
 * <h2>Data Flow</h2>
 * <ol>
 *   <li>User selects source(s) in data source tree → {@link #onRunTreeSelectionChanged}</li>
 *   <li>Timeseries tree is rebuilt with available outputs → {@link OutputsTreeBuilder#updateTree}</li>
 *   <li>User selects series in timeseries tree → {@link #onOutputsTreeSelectionChanged}</li>
 *   <li>Data is fetched via {@link TimeSeriesRequestManager} (cached by kalixcliUid:seriesName)</li>
 *   <li>Data is added to shared {@link DataSet} pool → {@link #addSeriesToPool}</li>
 *   <li>All plot tabs are updated → {@link VisualizationTabManager#updateAllTabs}</li>
 * </ol>
 *
 * <h2>Selection Tracking</h2>
 * <ul>
 *   <li>Per-tab selected series stored in {@link VisualizationTabManager} TabInfo</li>
 *   <li>{@code isUpdatingSelection} - Flag to prevent listener feedback loops during programmatic updates</li>
 *   <li>Tree selection is restored after rebuilds via {@link #restoreTreeSelectionForSeries}</li>
 * </ul>
 *
 * <h2>"Last" Run Handling</h2>
 * When a run completes ({@link #updateLastRun}):
 * <ol>
 *   <li>Cache is cleared for the session ({@link TimeSeriesRequestManager#clearCacheForSession})</li>
 *   <li>If "Last" was selected, timeseries tree is rebuilt to show new outputs</li>
 *   <li>Plotted "[Last]" series are refreshed with new data ({@link #refreshLastSeries})</li>
 * </ol>
 *
 * @see OutputsTreeBuilder
 * @see TimeSeriesRequestManager
 * @see VisualizationTabManager
 */
public class RunManager extends JFrame {

    private static final Logger logger = LoggerFactory.getLogger(RunManager.class);

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private static RunManager instance;
    private static java.util.function.Supplier<java.io.File> baseDirectorySupplier;
    private static java.util.function.Supplier<String> editorTextSupplier;

    // === DATA SOURCE TREE (left-top) ===
    // Shows: Last run, Current runs, Run library, Loaded datasets
    // Selection triggers rebuild of timeseries tree
    private JTree timeseriesSourceTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode lastRunNode;
    private DefaultMutableTreeNode currentRunsNode;
    private DefaultMutableTreeNode libraryNode;
    private DefaultMutableTreeNode loadedDatasetsNode;

    // === TIMESERIES TREE (left-bottom) ===
    // Shows hierarchical output series from selected sources
    // Selection triggers data fetching and plotting
    private JTree timeseriesTree;
    private DefaultTreeModel timeseriesTreeModel;
    private JScrollPane timeseriesScrollPane;

    // === VISUALIZATION (right side) ===
    // Shared DataSet is the single source of truth for plotted data
    // All plot tabs read from this shared dataset
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
    private SeriesColorManager seriesColorManager;
    private TreeFilterManager treeFilterManager;

    // === SELECTION STATE ===
    // Per-tab selected series are managed by VisualizationTabManager (TabInfo.selectedSeries).
    // The shared plotDataSet acts as a data pool — series are added but never removed on deselect.


    // === RUN TRACKING ===
    private final Map<String, String> sessionToRunName = new HashMap<>();
    private final Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private final Map<String, RunInfoImpl.DetailedRunStatus> lastKnownStatus = new HashMap<>();
    private int runCounter = 1;

    // === LAST RUN TRACKING ===
    // lastRunInfo points to the most recently completed run
    // Used to resolve "[Last]" series to actual session data
    private RunInfoImpl lastRunInfo = null;
    private DefaultMutableTreeNode lastRunChildNode = null;
    private final Map<String, Long> completionTimestamps = new HashMap<>();
    private long lastRunCompletionTime = 0L;

    // Flag to prevent selection listener feedback loops during programmatic tree updates
    // Set to true before modifying tree selection, false after
    private boolean isUpdatingSelection = false;

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
        setTitle("Kalix - Run Manager");
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

                if (component instanceof DefaultMutableTreeNode node) {
                    Object userObject = node.getUserObject();

                    if (userObject instanceof RunContextMenuManager.RunInfo runInfo) {
                        String uid = runInfo.getSession().getKalixcliUid();
                        return uid;
                    } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo datasetInfo) {
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

        // Initialize tree filter manager
        treeFilterManager = new TreeFilterManager(this::onFilterTextChanged);

        // Create shared dataset
        plotDataSet = new DataSet();

        // Initialize series color manager
        seriesColorManager = new SeriesColorManager();

        // Create tab manager with shared data
        tabManager = new VisualizationTabManager(plotDataSet, seriesColorManager.getColorMap());

        // Sync tree selection when user switches tabs
        tabManager.setOnTabChangedCallback(this::onTabChanged);

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

        // Bottom of left side: timeseries tree with filter
        JPanel timeseriesPanel = new JPanel(new BorderLayout());
        timeseriesPanel.setBorder(BorderFactory.createTitledBorder("Timeseries"));
        timeseriesPanel.add(treeFilterManager.getFilterPanel(), BorderLayout.NORTH);
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
            NaturalSortUtils::naturalCompare  // Natural sorting callback
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
        if (source instanceof RunInfoImpl runInfo) {
            if (runInfo.getSession().getActiveProgram() instanceof RunModelProgram program) {
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
            .sorted(NaturalSortUtils::naturalCompare)
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
     * Refreshes the data source tree with current session states.
     *
     * Called by session event listener when sessions change state. This method:
     * <ol>
     *   <li>Discovers new sessions and adds them to "Current runs"</li>
     *   <li>Detects status changes (RUNNING→DONE) for existing sessions</li>
     *   <li>Updates "Last run" when a run completes (calls {@link #updateLastRun})</li>
     *   <li>Triggers timeseries tree rebuild if the selected run's status changed</li>
     * </ol>
     *
     * Only shows RunModelProgram sessions - other types (OptimisationProgram) are filtered out.
     */
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

                    RunInfoImpl runInfo = new RunInfoImpl(runName, session);
                    RunInfoImpl.DetailedRunStatus initialStatus = runInfo.getDetailedRunStatus();

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
                    if (initialStatus == RunInfoImpl.DetailedRunStatus.DONE) {
                        long completionTime = System.currentTimeMillis();
                        completionTimestamps.put(sessionKey, completionTime);

                        if (completionTime > lastRunCompletionTime) {
                            updateLastRun(runInfo, completionTime);
                        }
                    }
                } else {
                    // Existing session - check for status changes
                    DefaultMutableTreeNode existingNode = sessionToTreeNode.get(sessionKey);
                    RunInfoImpl runInfo = (RunInfoImpl) existingNode.getUserObject();
                    RunInfoImpl.DetailedRunStatus currentStatus = runInfo.getDetailedRunStatus();
                    RunInfoImpl.DetailedRunStatus lastStatus = lastKnownStatus.get(sessionKey);

                    if (lastStatus != currentStatus) {
                        // Status changed - refresh this node's display
                        treeModel.nodeChanged(existingNode);

                        // Detect session reuse: if session was DONE and is now RUNNING/LOADING, it's a new run
                        if (lastStatus == RunInfoImpl.DetailedRunStatus.DONE &&
                            (currentStatus == RunInfoImpl.DetailedRunStatus.RUNNING || currentStatus == RunInfoImpl.DetailedRunStatus.LOADING || currentStatus == RunInfoImpl.DetailedRunStatus.STARTING)) {
                            // Session reused for new run - reset completion tracking for this session
                            completionTimestamps.remove(sessionKey);
                        }

                        lastKnownStatus.put(sessionKey, currentStatus);

                        // Check if run just completed
                        if (currentStatus == RunInfoImpl.DetailedRunStatus.DONE && lastStatus != RunInfoImpl.DetailedRunStatus.DONE) {
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
                    if (lastRunInfo != null && lastRunInfo.getSession().getSessionKey().equals(sessionKey)) {
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
     * Updates the "Last run" node to point to the most recently completed run.
     *
     * This is a critical method for the "[Last]" feature. When a run completes:
     * <ol>
     *   <li>Updates {@code lastRunInfo} to point to the new run</li>
     *   <li>Replaces the tree node under "Last run" with a new RunInfoImpl("Last", session)</li>
     *   <li>If "Last" was selected in the data source tree:
     *     <ul>
     *       <li>Rebuilds timeseries tree to show outputs from new run (may have new/removed outputs)</li>
     *       <li>Restores selection for series that still exist</li>
     *       <li>Removes stale series from plot via {@link #reconcileSelectedSeriesWithTree}</li>
     *     </ul>
     *   </li>
     *   <li>Calls {@link #refreshLastSeries} to update plotted "[Last]" data with new values</li>
     * </ol>
     *
     * The RunInfoImpl for "Last" uses name="Last" so series display as "ds_1 [Last]" not "ds_1 [Run_3]".
     */
    private void updateLastRun(RunInfoImpl newLastRun, long completionTime) {
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
            new RunInfoImpl("Last", newLastRun.getSession())
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

        // If Last was previously selected, rebuild the timeseries tree to show new outputs
        if (wasLastSelected) {
            TreePath newLastPath = new TreePath(lastRunChildNode.getPath());

            // Restore run tree selection to the new Last node
            timeseriesSourceTree.addSelectionPath(newLastPath);

            // Rebuild the timeseries tree to include any new outputs from the new run
            // This is necessary because the new run may have different outputs than the old one
            updateOutputsTree();

            // Restore selection for series that still exist in the new tree
            Set<String> tabSeries = tabManager.getTargetTabSelectedSeries();
            Set<String> restoredSeries = restoreTreeSelectionForSeries(tabSeries);

            // Remove from tab any series that no longer exist (e.g., outputs that were removed)
            reconcileSelectedSeriesWithTree(restoredSeries, tabSeries);

            isUpdatingSelection = false;
        }

        TreePath[] selectedAfter = timeseriesSourceTree.getSelectionPaths();

        // Expand the Last run node to show the new child
        timeseriesSourceTree.expandPath(new TreePath(lastRunNode.getPath()));

        // Refresh any plotted "[Last]" series to use the new Last run's data
        refreshLastSeries();
    }

    /**
     * Recursively searches tree nodes for matching series keys and collects their paths.
     */
    private void searchAndCollectPaths(DefaultMutableTreeNode node, Set<String> targetKeys, List<TreePath> results) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode that matches one of our target keys
        if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
            String seriesKey;
            if (leaf.source instanceof RunInfoImpl) {
                // Run series: add run name suffix
                String runName = ((RunInfoImpl) leaf.source).getRunName();
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
     * Restores tree selection to match the given series set.
     * This ensures the tree visually reflects what's plotted, even after tree rebuilds.
     * Returns the set of series that were successfully restored (found in the tree).
     *
     * @param seriesToRestore The set of series keys to select in the tree
     */
    private Set<String> restoreTreeSelectionForSeries(Set<String> seriesToRestore) {
        if (seriesToRestore.isEmpty()) {
            timeseriesTree.clearSelection();
            return Collections.emptySet();
        }

        List<TreePath> pathsToSelect = new ArrayList<>();
        Set<String> restoredSeriesKeys = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();

        // Search tree for nodes matching the series set, collect which ones we found
        for (String seriesKey : seriesToRestore) {
            List<TreePath> foundPaths = new ArrayList<>();
            searchAndCollectPaths(root, Collections.singleton(seriesKey), foundPaths);
            if (!foundPaths.isEmpty()) {
                pathsToSelect.addAll(foundPaths);
                restoredSeriesKeys.add(seriesKey);
            }
        }

        if (!pathsToSelect.isEmpty()) {
            // Note: Caller should have isUpdatingSelection set to block events
            TreePath[] pathsArray = pathsToSelect.toArray(new TreePath[0]);
            timeseriesTree.setSelectionPaths(pathsArray);
        } else {
            timeseriesTree.clearSelection();
        }

        return restoredSeriesKeys;
    }

    /**
     * Reconciles the target tab's selected series with what's actually available in the tree.
     * Removes series from the tab that couldn't be restored (e.g., when a run is deselected).
     *
     * @param restoredSeries Set of series that were successfully found in the tree
     * @param tabSeries The tab's current selected series to reconcile against
     */
    private void reconcileSelectedSeriesWithTree(Set<String> restoredSeries, Set<String> tabSeries) {
        // Find series that need to be removed from the tab
        Set<String> seriesToRemove = new HashSet<>(tabSeries);
        seriesToRemove.removeAll(restoredSeries);

        if (seriesToRemove.isEmpty()) {
            return;
        }

        // Update the target tab's series (remove unrestorable ones)
        Set<String> updatedSeries = new LinkedHashSet<>(tabSeries);
        updatedSeries.removeAll(seriesToRemove);
        tabManager.setTargetTabSelectedSeries(updatedSeries);

        // Remove from stats tables
        for (String seriesKey : seriesToRemove) {
            tabManager.removeSeriesFromStatsTabs(seriesKey);
        }
    }

    /**
     * Resolves a RunInfo to its actual session.
     * If the run is "Last", returns the session of the actual last completed run.
     */
    private SessionManager.KalixSession resolveRunInfoSession(RunInfoImpl runInfo) {
        if ("Last".equals(runInfo.getRunName()) && lastRunInfo != null) {
            return lastRunInfo.getSession();
        }
        return runInfo.getSession();
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
     * Refreshes all plotted "[Last]" series to use data from the new Last run.
     *
     * Called by {@link #updateLastRun} when a run completes. This method:
     * <ol>
     *   <li>Clears the {@link TimeSeriesRequestManager} cache for this session -
     *       CRITICAL because cache is keyed by kalixcliUid which persists across runs</li>
     *   <li>For each series ending with " [Last]" across all tabs:
     *     <ul>
     *       <li>Removes old data from plot</li>
     *       <li>Requests fresh data from the new run (async)</li>
     *       <li>Callback adds new data and triggers plot refresh</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * The cache clear happens unconditionally (even if no "[Last]" series are selected)
     * so that future selections will fetch fresh data.
     */
    private void refreshLastSeries() {
        if (lastRunInfo == null) {
            return;
        }

        String newSessionKey = lastRunInfo.getSession().getSessionKey();

        // Clear stale cached data from the previous run on this session
        // This MUST happen unconditionally so that future requests for [Last] data
        // will fetch fresh data, even if no [Last] series are currently selected
        timeSeriesRequestManager.clearCacheForSession(newSessionKey);

        // Find all "[Last]" series across all tabs
        Set<String> allTabSeries = tabManager.getAllSelectedSeriesAcrossTabs();
        List<String> lastSeriesKeys = allTabSeries.stream()
            .filter(key -> key.endsWith(" [Last]"))
            .collect(java.util.stream.Collectors.toList());

        if (lastSeriesKeys.isEmpty()) {
            return;
        }

        // Refresh each "[Last]" series in the pool
        for (String seriesKey : lastSeriesKeys) {
            String seriesName = extractSeriesName(seriesKey);

            // Remove old data from pool
            plotDataSet.removeSeries(seriesKey);

            // Check if we have cached data from the new Last run
            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(newSessionKey, seriesName);
            if (cachedData != null) {
                TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                addSeriesToPool(renamedData);
                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
            } else if (!timeSeriesRequestManager.isRequestInProgress(newSessionKey, seriesName)) {
                timeSeriesRequestManager.requestTimeSeries(newSessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            if (tabManager.isSeriesSelectedOnAnyTab(seriesKey)) {
                                TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, seriesKey);
                                addSeriesToPool(renamedData);
                                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);

                                // Refresh all tabs that show this series (don't reset zoom)
                                tabManager.updateAllTabs(false);
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

        // Refresh all tabs that show "[Last]" series (don't reset zoom)
        tabManager.updateAllTabs(false);
    }


    /**
     * Handles tree selection changes to update the timeseries tree.
     */
    private void onRunTreeSelectionChanged(TreeSelectionEvent e) {
        // Ignore selection changes during programmatic updates
        if (isUpdatingSelection) {
            return;
        }

        // Block timeseries tree selection events during rebuild and restoration
        isUpdatingSelection = true;
        updateOutputsTree();

        // Restore visual selection to match what's currently plotted on active tab
        Set<String> tabSeries = tabManager.getTargetTabSelectedSeries();
        Set<String> restoredSeries = restoreTreeSelectionForSeries(tabSeries);
        reconcileSelectedSeriesWithTree(restoredSeries, tabSeries);

        isUpdatingSelection = false;
    }

    /**
     * Handles filter text changes. Rebuilds the timeseries tree with the current filter
     * applied. Purely visual - does NOT change selection or affect plots.
     */
    private void onFilterTextChanged() {
        isUpdatingSelection = true;
        try {
            outputsTreeBuilder.setFilterText(treeFilterManager.getFilterText());
            updateOutputsTree();
            restoreTreeSelectionForSeries(tabManager.getTargetTabSelectedSeries());
        } finally {
            isUpdatingSelection = false;
        }
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

            if (userObject instanceof RunContextMenuManager.RunInfo) {
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
            // Clear the target tab's series when nothing is selected
            tabManager.setTargetTabSelectedSeries(new LinkedHashSet<>());
            return;
        }

        // Collect all leaf nodes recursively (parent selection = all children)
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            collectLeafNodes(node, allLeaves);
        }

        // If no valid leaves found, clear the target tab
        if (allLeaves.isEmpty()) {
            tabManager.setTargetTabSelectedSeries(new LinkedHashSet<>());
            return;
        }

        // Build new set of selected series (with unique keys)
        Set<String> newSelectedSeries = new LinkedHashSet<>();
        Map<String, OutputsTreeBuilder.SeriesLeafNode> seriesKeyToLeaf = new HashMap<>();

        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            String seriesKey;
            if (leaf.source instanceof RunInfoImpl) {
                String runName = ((RunInfoImpl) leaf.source).getRunName();
                seriesKey = leaf.seriesName + " [" + runName + "]";
            } else {
                DatasetLoaderManager.LoadedDatasetInfo datasetInfo = (DatasetLoaderManager.LoadedDatasetInfo) leaf.source;
                seriesKey = leaf.seriesName + " [" + datasetInfo.fileName + "]";
            }
            newSelectedSeries.add(seriesKey);
            seriesKeyToLeaf.put(seriesKey, leaf);
        }

        // Get the target tab's current series for diffing
        Set<String> currentTabSeries = tabManager.getTargetTabSelectedSeries();

        // When filtering with an additive click, preserve series hidden by the filter
        if (treeFilterManager.isFiltering() && isAdditiveSelectionEvent()) {
            Set<String> visibleSeriesKeys = getVisibleSeriesKeys();
            for (String key : currentTabSeries) {
                if (!visibleSeriesKeys.contains(key)) {
                    newSelectedSeries.add(key);
                }
            }
        }

        // Check if there's overlap between old and new selections for zoom decision
        boolean hasOverlap = currentTabSeries.stream().anyMatch(newSelectedSeries::contains);
        final boolean shouldResetZoom = currentTabSeries.isEmpty() || !hasOverlap;

        // Determine which series need data fetched (not yet in the pool)
        Set<String> seriesToFetch = new HashSet<>(newSelectedSeries);
        for (String key : seriesToFetch.toArray(new String[0])) {
            if (plotDataSet.getSeries(key) != null) {
                seriesToFetch.remove(key); // Already in pool
            }
        }

        // Capture the target PlotPanel for async callbacks (not "active panel at callback time")
        final PlotPanel targetPanel = tabManager.getTargetPlotPanel();

        // Group new series needing fetch by data source
        Map<String, List<String>> dataSourceToSeriesKeys = new LinkedHashMap<>();
        Set<String> datasetSeriesKeys = new HashSet<>();

        for (String seriesKey : seriesToFetch) {
            OutputsTreeBuilder.SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            // Assign color if not already assigned
            seriesColorManager.assignColor(seriesKey);

            if (leaf.source instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                datasetSeriesKeys.add(seriesKey);
            } else {
                RunInfoImpl runInfo = (RunInfoImpl) leaf.source;
                SessionManager.KalixSession resolvedSession = resolveRunInfoSession(runInfo);
                if (resolvedSession == null) continue;

                String sessionKey = resolvedSession.getSessionKey();
                String seriesName = leaf.seriesName;
                String dataSourceKey = sessionKey + "|" + seriesName;

                dataSourceToSeriesKeys.computeIfAbsent(dataSourceKey, k -> new ArrayList<>()).add(seriesKey);
            }
        }

        // Also assign colors for series new to this tab but already in pool
        for (String seriesKey : newSelectedSeries) {
            if (!currentTabSeries.contains(seriesKey)) {
                seriesColorManager.assignColor(seriesKey);
            }
        }

        // Fetch run series data into the pool
        for (Map.Entry<String, List<String>> entry : dataSourceToSeriesKeys.entrySet()) {
            String dataSourceKey = entry.getKey();
            List<String> seriesKeys = entry.getValue();

            String[] parts = dataSourceKey.split("\\|", 2);
            String sessionKey = parts[0];
            String seriesName = parts[1];

            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, seriesName);
            if (cachedData != null) {
                for (String seriesKey : seriesKeys) {
                    TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                    addSeriesToPool(renamedData);
                    tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
                }
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                for (String seriesKey : seriesKeys) {
                    tabManager.addLoadingSeriesInStatsTabs(seriesKey);
                }

                final List<String> capturedSeriesKeys = new ArrayList<>(seriesKeys);
                final Set<String> capturedNewSelection = new LinkedHashSet<>(newSelectedSeries);

                timeSeriesRequestManager.requestTimeSeries(sessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            for (String capturedSeriesKey : capturedSeriesKeys) {
                                // Check if series is still selected on the target tab
                                if (capturedNewSelection.contains(capturedSeriesKey)) {
                                    TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, capturedSeriesKey);
                                    addSeriesToPool(renamedData);
                                    tabManager.updateSeriesInStatsTabsWithAggregation(capturedSeriesKey, renamedData);
                                }
                            }

                            // Refresh the target tab (data now in pool)
                            if (targetPanel != null) {
                                tabManager.updateTab(targetPanel, shouldResetZoom);
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            for (String capturedSeriesKey : capturedSeriesKeys) {
                                tabManager.addErrorSeriesInStatsTabs(capturedSeriesKey, throwable.getMessage());
                            }
                        });
                        return null;
                    });
            } else {
                for (String seriesKey : seriesKeys) {
                    tabManager.addLoadingSeriesInStatsTabs(seriesKey);
                }
            }
        }

        // Fetch dataset series into the pool
        for (String seriesKey : datasetSeriesKeys) {
            OutputsTreeBuilder.SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            TimeSeriesData cachedData = datasetSeriesCache.get(leaf.seriesName);
            if (cachedData != null) {
                TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                addSeriesToPool(renamedData);
                tabManager.updateSeriesInStatsTabsWithAggregation(seriesKey, renamedData);
            } else {
                logger.warn("Dataset series not found in cache: " + leaf.seriesName);
                tabManager.addErrorSeriesInStatsTabs(seriesKey, "Series not found");
            }
        }

        // Update the target tab's selected series (rebuilds legend, visible series, display)
        tabManager.setTargetTabSelectedSeries(newSelectedSeries);

        // Only reset zoom when the selection completely changed (no overlap with previous).
        // Additive selection (Ctrl+click) intentionally preserves both X and Y zoom so the
        // user can see how the new series looks in their current view window. Auto-Y is not
        // triggered here — it only applies during pan/zoom interactions and setting changes.
        if (shouldResetZoom && targetPanel != null) {
            targetPanel.zoomToFit();
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
     * Checks whether the event currently being dispatched is an additive selection gesture
     * (Cmd/Ctrl/Shift held). Uses EventQueue.getCurrentEvent() so that this works correctly
     * even when called from a TreeSelectionListener (which fires during the UI delegate's
     * mouse handler, before any separately registered MouseListeners).
     */
    private boolean isAdditiveSelectionEvent() {
        AWTEvent event = EventQueue.getCurrentEvent();
        if (event instanceof InputEvent inputEvent) {
            int modifiers = inputEvent.getModifiersEx()
                    & (InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK);
            return modifiers != 0;
        }
        return false;
    }

    /**
     * Collects all series keys visible in the current (possibly filtered) timeseries tree.
     */
    private Set<String> getVisibleSeriesKeys() {
        Set<String> keys = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
        collectAllLeafNodesRecursive(root, allLeaves);
        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            if (leaf.source instanceof RunInfoImpl) {
                keys.add(leaf.seriesName + " [" + ((RunInfoImpl) leaf.source).getRunName() + "]");
            } else if (leaf.source instanceof DatasetLoaderManager.LoadedDatasetInfo datasetInfo) {
                keys.add(leaf.seriesName + " [" + datasetInfo.fileName + "]");
            }
        }
        return keys;
    }

    /**
     * Recursively collects all SeriesLeafNode objects from the entire tree (no depth guard).
     */
    private void collectAllLeafNodesRecursive(DefaultMutableTreeNode node, List<OutputsTreeBuilder.SeriesLeafNode> leaves) {
        Object userObject = node.getUserObject();
        if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
            leaves.add(leaf);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectAllLeafNodesRecursive((DefaultMutableTreeNode) node.getChildAt(i), leaves);
        }
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
     * Adds a series to the shared data pool.
     * Legend and visibility are managed per-tab via VisualizationTabManager.
     */
    private void addSeriesToPool(TimeSeriesData timeSeriesData) {
        plotDataSet.addSeries(timeSeriesData);
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
     * Called when the user switches tabs. Syncs the timeseries tree selection
     * to reflect the new active tab's selected series.
     */
    private void onTabChanged() {
        Set<String> tabSeries = tabManager.getTargetTabSelectedSeries();
        if (tabSeries == null) return;

        isUpdatingSelection = true;
        try {
            restoreTreeSelectionForSeries(tabSeries);
        } finally {
            isUpdatingSelection = false;
        }
    }

    /**
     * Clears the data pool, all per-tab series, and all stats tables.
     */
    private void clearPlotAndStats() {
        plotDataSet.removeAllSeries();
        seriesColorManager.clearAll();
        tabManager.clearAllTabSeries();
        tabManager.updateAllTabs(true);

        for (StatsTableModel model : tabManager.getAllStatsModels()) {
            model.clear();
        }
    }



}