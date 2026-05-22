package com.kalix.ide.windows;

import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.RunContextMenuManager;
import com.kalix.ide.managers.TreeFilterManager;
import com.kalix.ide.flowviz.style.PaletteSeriesStyleResolver;
import com.kalix.ide.flowviz.style.PlotPaletteManager;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
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

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    // Cache for loaded dataset series.
    // Key: DatasetSeries ref qualifying by absolute path + base name. The path qualifier
    // matters because the legacy base name embeds only the sanitized filename (replaceAll
    // "[^a-zA-Z0-9]" → "_"), so two distinct files whose names sanitize to the same
    // identifier would collide on the bare base name. Keying by ref preserves their
    // separate identity.
    // Value: TimeSeriesData
    // Mirrors how runs store data in TimeSeriesRequestManager's cache.
    private final Map<com.kalix.ide.flowviz.data.DatasetSeries, TimeSeriesData> datasetSeriesCache = new HashMap<>();

    // Manager instances
    private OutputsTreeBuilder outputsTreeBuilder;
    private DatasetLoaderManager datasetLoaderManager;
    private RunContextMenuManager runContextMenuManager;
    private SeriesSlotManager seriesSlotManager;
    private TreeFilterManager treeFilterManager;
    private java.util.function.Consumer<SessionManager.SessionEvent> sessionEventListener;

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
    // Generation counter for "Last" run identity. Incremented inside updateLastRun() each time
    // a new run becomes "Last". Async fetches for "[Last]" series capture this value at issue
    // time and verify it has not changed before applying the result on the EDT. If the value
    // has changed, the response belongs to a previous Last run and is discarded — the newer
    // updateLastRun() has already issued (or will issue) a fetch for the correct data.
    //
    // Mutated only on the EDT (updateLastRun is invoked from session-event handlers via
    // SwingUtilities.invokeLater). Marked volatile so future cross-thread reads see the
    // current value; the increment is safe because it has a single writer thread.
    private volatile long lastRunGeneration = 0;
    private DefaultMutableTreeNode lastRunChildNode = null;
    private final Map<String, Long> completionTimestamps = new HashMap<>();
    private long lastRunCompletionTime = 0L;

    // Flag to prevent selection listener feedback loops during programmatic tree updates
    // Set to true before modifying tree selection, false after
    private boolean isUpdatingSelection = false;

    // Single point of authority for projecting SeriesRef → display label.
    // Consumed by stats tables, legends, and the outputs tree so that the user-visible
    // string for a run-derived series tracks the current run name automatically.
    private final com.kalix.ide.flowviz.data.LabelResolver labelResolver =
        new com.kalix.ide.flowviz.data.DefaultLabelResolver(this::runNameForId);

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

        // Create shared dataset. Install the LastSeries alias resolver so the pool stores
        // "Last" data under the underlying run's stable RunSeries identity — the pool never
        // holds a LastSeries key, which makes stale-Last data structurally impossible.
        plotDataSet = new DataSet();
        plotDataSet.setLastSeriesResolver(last ->
            lastRunInfo != null
                ? new com.kalix.ide.flowviz.data.RunSeries(lastRunInfo.getRunId(), last.baseName())
                : null);

        // Initialize series slot assignment. Slots are resolved against the global
        // palette so every plot tab in this window styles a series consistently.
        seriesSlotManager = new SeriesSlotManager();

        // Create tab manager with shared data and a palette-backed style resolver
        tabManager = new VisualizationTabManager(plotDataSet,
            new PaletteSeriesStyleResolver(seriesSlotManager, PlotPaletteManager.getInstance()));

        // Wire the label resolver so legends, stats column 0, etc. project SeriesRef
        // → user-visible label at render time. Must happen *before* the default plot
        // tab is added so the new PlotPanel picks it up.
        tabManager.setLabelResolver(labelResolver);

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
            NaturalSortUtils::naturalCompare,  // Natural sorting callback
            this::refForSource,                // Project (seriesName, source) to SeriesRef
            labelResolver                      // Display label projection
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
            this::refreshRuns,                // Refresh callback
            this::renameRun                   // Rename delegate (validation + propagation)
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
        String datasetId = datasetInfo.file.getAbsolutePath();

        // Get series from cache (NOT plotDataSet) - mirrors how runs work
        return datasetSeriesCache.keySet().stream()
            .filter(ref -> ref.datasetId().equals(datasetId))
            .map(com.kalix.ide.flowviz.data.DatasetSeries::baseName)
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


    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                // Event-based updates, no timer needed
                refreshRuns();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                // Unregister session listener to prevent memory leak
                if (sessionEventListener != null && stdioTaskManager != null) {
                    stdioTaskManager.getSessionManager().removeSessionEventListener(sessionEventListener);
                }
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
        sessionEventListener = event -> {
            SwingUtilities.invokeLater(() -> handleSessionEvent(event));
        };
        stdioTaskManager.getSessionManager().addSessionEventListener(sessionEventListener);

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
        lastRunGeneration++;
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

        // Create new child node. The Last subtree's wrapper carries the structural
        // "is last alias" marker via RunInfoImpl.lastAlias — seriesRefForLeaf consults
        // that marker (not the runName string) to mint LastSeries refs.
        lastRunChildNode = new DefaultMutableTreeNode(
            RunInfoImpl.lastAlias(newLastRun.getSession())
        );

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
            Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
            Set<com.kalix.ide.flowviz.data.SeriesRef> restoredSeries = restoreTreeSelectionForSeries(tabSeries);

            // Remove from tab any series that no longer exist (e.g., outputs that were removed)
            reconcileSelectedSeriesWithTree(restoredSeries, tabSeries);

            isUpdatingSelection = false;
        }

        // Expand the Last run node to show the new child
        timeseriesSourceTree.expandPath(new TreePath(lastRunNode.getPath()));

        // Refresh any plotted "[Last]" series to use the new Last run's data
        refreshLastSeries();
    }

    /**
     * Recursively searches tree nodes for matching series keys and collects their paths.
     */
    private void searchAndCollectPaths(DefaultMutableTreeNode node,
                                        Set<com.kalix.ide.flowviz.data.SeriesRef> targetRefs,
                                        List<TreePath> results) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode that maps to one of our target refs
        if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
            com.kalix.ide.flowviz.data.SeriesRef ref = seriesRefForLeaf(leaf);
            if (ref != null && targetRefs.contains(ref)) {
                TreePath path = new TreePath(node.getPath());
                results.add(path);
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            searchAndCollectPaths(child, targetRefs, results);
        }
    }

    /**
     * Constructs the {@link com.kalix.ide.flowviz.data.SeriesRef} that identifies the
     * data behind a {@link OutputsTreeBuilder.SeriesLeafNode}. The leaf still carries
     * the legacy {@code source} object (RunInfoImpl or LoadedDatasetInfo) plus
     * {@code seriesName} — this helper produces the typed ref from that pair.
     * Will move onto the leaf itself when OutputsTreeBuilder is migrated.
     */
    private com.kalix.ide.flowviz.data.SeriesRef seriesRefForLeaf(OutputsTreeBuilder.SeriesLeafNode leaf) {
        return leaf.ref;
    }

    /**
     * Constructs the {@link com.kalix.ide.flowviz.data.SeriesRef} for a (seriesName, source)
     * pair. Used by {@link OutputsTreeBuilder} when building leaves and parents — the
     * resulting ref is cached on the leaf so subsequent lookups don't re-project.
     */
    private com.kalix.ide.flowviz.data.SeriesRef refForSource(String seriesName, Object source) {
        if (source instanceof RunInfoImpl runInfo) {
            if (runInfo.isLastAlias()) {
                return new com.kalix.ide.flowviz.data.LastSeries(seriesName);
            }
            return new com.kalix.ide.flowviz.data.RunSeries(runInfo.getRunId(), seriesName);
        }
        if (source instanceof DatasetLoaderManager.LoadedDatasetInfo info) {
            return new com.kalix.ide.flowviz.data.DatasetSeries(info.file.getAbsolutePath(), seriesName);
        }
        return null;
    }

    /**
     * Restores tree selection to match the given series set.
     * This ensures the tree visually reflects what's plotted, even after tree rebuilds.
     * Returns the set of series that were successfully restored (found in the tree).
     *
     * @param seriesToRestore The set of series keys to select in the tree
     */
    private Set<com.kalix.ide.flowviz.data.SeriesRef> restoreTreeSelectionForSeries(
            Set<com.kalix.ide.flowviz.data.SeriesRef> seriesToRestore) {
        if (seriesToRestore.isEmpty()) {
            timeseriesTree.clearSelection();
            return Collections.emptySet();
        }

        List<TreePath> pathsToSelect = new ArrayList<>();
        Set<com.kalix.ide.flowviz.data.SeriesRef> restoredRefs = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();

        // Search tree for nodes matching the ref set, collect which ones we found
        for (com.kalix.ide.flowviz.data.SeriesRef ref : seriesToRestore) {
            List<TreePath> foundPaths = new ArrayList<>();
            searchAndCollectPaths(root, Collections.singleton(ref), foundPaths);
            if (!foundPaths.isEmpty()) {
                pathsToSelect.addAll(foundPaths);
                restoredRefs.add(ref);
            }
        }

        if (!pathsToSelect.isEmpty()) {
            // Note: Caller should have isUpdatingSelection set to block events
            TreePath[] pathsArray = pathsToSelect.toArray(new TreePath[0]);
            timeseriesTree.setSelectionPaths(pathsArray);
        } else {
            timeseriesTree.clearSelection();
        }

        return restoredRefs;
    }

    /**
     * Reconciles the target tab's selected series with what's actually available in the tree.
     * Removes series from the tab that couldn't be restored (e.g., when a run is deselected).
     */
    private void reconcileSelectedSeriesWithTree(Set<com.kalix.ide.flowviz.data.SeriesRef> restoredSeries,
                                                  Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries) {
        // Find series that need to be removed from the tab
        Set<com.kalix.ide.flowviz.data.SeriesRef> seriesToRemove = new HashSet<>(tabSeries);
        seriesToRemove.removeAll(restoredSeries);

        if (seriesToRemove.isEmpty()) {
            return;
        }

        // Update the target tab's series (remove unrestorable ones)
        Set<com.kalix.ide.flowviz.data.SeriesRef> updatedSeries = new LinkedHashSet<>(tabSeries);
        updatedSeries.removeAll(seriesToRemove);
        tabManager.setTargetTabSelectedSeries(updatedSeries);

        // Remove from stats tables
        for (com.kalix.ide.flowviz.data.SeriesRef ref : seriesToRemove) {
            tabManager.removeSeriesFromStatsTabs(ref);
        }
    }

    /**
     * Returns the current display name for a given {@code runId}, or {@code null} if
     * no run with that id is currently known. Used by {@link com.kalix.ide.flowviz.data.DefaultLabelResolver}
     * to project {@link com.kalix.ide.flowviz.data.RunSeries} refs to user-visible
     * labels.
     *
     * <p>Linear over current runs; trivially small in practice. If profiling later
     * shows this on a hot path, swap to an explicit {@code Map<Long, RunInfoImpl>}
     * maintained in {@code refreshRuns} and {@code renameRun}.</p>
     */
    private String runNameForId(long runId) {
        for (DefaultMutableTreeNode node : sessionToTreeNode.values()) {
            if (node.getUserObject() instanceof RunInfoImpl info && info.getRunId() == runId) {
                return info.getRunName();
            }
        }
        return null;
    }

    /**
     * Returns the {@link com.kalix.ide.flowviz.data.LabelResolver} bound to this
     * RunManager's state. Components that need to render series labels — stats tables,
     * plot legends, the outputs tree — should obtain the resolver here rather than
     * constructing label strings themselves.
     */
    public com.kalix.ide.flowviz.data.LabelResolver getLabelResolver() {
        return labelResolver;
    }

    /**
     * Resolves a RunInfo to its actual session.
     * If the run is "Last", returns the session of the actual last completed run.
     */
    private SessionManager.KalixSession resolveRunInfoSession(RunInfoImpl runInfo) {
        if (runInfo.isLastAlias() && lastRunInfo != null) {
            return lastRunInfo.getSession();
        }
        return runInfo.getSession();
    }

    // extractSeriesName removed — there are no series-key strings to parse anymore.
    // Identity is the typed SeriesRef; baseName comes from ref.baseName().

    /**
     * Renames a run. Series identity in the pool, color map, tab selections, stats models,
     * and undo history is the runId on {@link com.kalix.ide.flowviz.data.RunSeries}, which
     * is preserved by {@link RunInfoImpl#withName} — no data structures need propagation.
     * The tree node's user object is swapped to a fresh {@link RunInfoImpl} carrying the
     * same runId, and the outputs tree is rebuilt so leaf labels re-render via the new
     * name; the {@link com.kalix.ide.flowviz.data.LabelResolver} reprojects every other
     * surface on the next paint.
     *
     * <p>Validates the new name on entry. Returns {@code null} on success, or a
     * user-facing error string on rejection. EDT-only.</p>
     *
     * <p>The reserved name {@code "Last"} is rejected for display-disambiguation only:
     * series identity is structural ({@code RunInfoImpl.isLastAlias()} distinguishes the
     * placeholder from user runs), so a user-named "Last" run wouldn't corrupt data —
     * but its rendered label {@code "<base> [Last]"} would be indistinguishable from the
     * actual {@code LastSeries} alias, which is a UX hazard.</p>
     */
    public String renameRun(RunContextMenuManager.RunInfo oldRunInfoIface, String newName) {
        if (!(oldRunInfoIface instanceof RunInfoImpl oldRunInfo)) {
            return "Only simulation runs can be renamed.";
        }

        String oldName = oldRunInfo.getRunName();
        if (newName.equals(oldName)) {
            return null;
        }

        if (newName.isEmpty()) {
            return "Run name cannot be empty.";
        }
        if ("Last".equals(newName)) {
            return "'Last' is reserved and cannot be used as a run name.";
        }
        for (String existing : sessionToRunName.values()) {
            if (existing.equals(newName)) {
                return "A run with the name '" + newName + "' already exists.";
            }
        }

        // Defensive: rename is initiated from a tree-node context menu, but the node may
        // have been removed asynchronously between click and dialog close (rare; e.g. a
        // concurrent session-terminate). Reject rather than leave inconsistent state.
        String sessionKey = oldRunInfo.getSession().getSessionKey();
        DefaultMutableTreeNode node = sessionToTreeNode.get(sessionKey);
        if (node == null) {
            return "This run is no longer available.";
        }

        // Construct a renamed instance that preserves the runId. The runId is the
        // durable internal handle (used by RunSeries in the post-refactor identity
        // model); renaming must NOT mint a new id, otherwise plotted series stored
        // under the old runId would be orphaned the next time the pool is keyed by
        // ref. The label changes; identity does not.
        RunInfoImpl newRunInfo = oldRunInfo.withName(newName);
        node.setUserObject(newRunInfo);
        treeModel.nodeChanged(node);

        sessionToRunName.put(sessionKey, newName);

        // Keep lastRunInfo pointing at the live RunInfoImpl for this session — otherwise
        // it would dangle to the now-orphan instance. The Last child node's wrapper
        // (with name "Last") is untouched; refreshLastSeries resolves Last via session.
        if (lastRunInfo == oldRunInfo) {
            lastRunInfo = newRunInfo;
        }

        // No propagation needed: series identity is the runId on RunSeries refs, which is
        // preserved by RunInfoImpl.withName. The pool, color map, tab selections, stats
        // models, and undo history all key by ref — they don't know or care that the
        // user-visible run name changed. The label is reprojected via LabelResolver on
        // the next paint, so legends and the stats column refresh automatically.
        //
        // We just need to: (a) rebuild the outputs tree so its leaves regenerate against
        // the new RunInfoImpl (the leaf display via toString() picks up the new run name);
        // and (b) trigger a repaint so any text surfaces that aren't actively reading the
        // resolver see the update.
        isUpdatingSelection = true;
        try {
            updateOutputsTree();
            Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
            restoreTreeSelectionForSeries(tabSeries);
        } finally {
            isUpdatingSelection = false;
        }

        // Cheap repaint to pick up the new label in plot legends / stats column headers
        // that already cache projected strings.
        tabManager.updateAllTabs(false);

        return null;
    }

    /**
     * Fetches data for the new Last run into the pool, for every plotted "[Last]" series.
     *
     * Called by {@link #updateLastRun} when a run completes. This method:
     * <ol>
     *   <li>Clears the {@link TimeSeriesRequestManager} cache for this session -
     *       CRITICAL because cache is keyed by kalixcliUid which persists across runs</li>
     *   <li>For each {@link com.kalix.ide.flowviz.data.LastSeries} ref selected across all
     *       tabs, requests fresh data from the new run (sync if cached, async otherwise)
     *       and writes it into {@code plotDataSet}.</li>
     * </ol>
     *
     * The cache clear happens unconditionally (even if no "[Last]" series are selected)
     * so that future selections will fetch fresh data.
     *
     * <h3>Why no stale-data handling is needed</h3>
     * The pool stores "Last" data under the underlying run's stable {@code RunSeries}
     * identity — {@code plotDataSet}'s {@code LastSeriesResolver} (installed in
     * {@code initializeManagers}) redirects every {@code LastSeries} access to
     * {@code RunSeries(lastRunId, baseName)}. So the pool never holds a {@code LastSeries}
     * key that could go stale: when Last changes, a {@code LastSeries} ref simply resolves
     * to a different {@code RunSeries}. This method just ensures that target is populated.
     * The {@code onOutputsTreeSelectionChanged} "already in pool" short-circuit is therefore
     * correct by construction — a {@code getSeries(LastSeries)} probe resolves to the
     * current run's data or to {@code null} (triggering a fetch).
     *
     * <h3>Atomic swap (no empty-plot gap)</h3>
     * Writing the new data via {@code addSeries} replaces the {@code RunSeries} entry in a
     * single EDT operation; the previous Last's {@code RunSeries} entry is left untouched
     * (it remains valid data for that specific run).
     */
    private void refreshLastSeries() {
        if (lastRunInfo == null) {
            return;
        }

        // Capture the generation at issue time. All async fetches below carry this value so
        // that responses arriving after a newer updateLastRun() can be detected and dropped.
        final long capturedGeneration = lastRunGeneration;

        String newSessionKey = lastRunInfo.getSession().getSessionKey();

        // Clear stale cached data from the previous run on this session
        // This MUST happen unconditionally so that future requests for [Last] data
        // will fetch fresh data, even if no [Last] series are currently selected
        timeSeriesRequestManager.clearCacheForSession(newSessionKey);

        // Find all LastSeries refs across all tabs. Identity is the typed ref now —
        // the obsolete `endsWith(" [Last]")` string matching has been deleted, which
        // structurally closes issue #8 (no string-suffix collisions are possible).
        Set<com.kalix.ide.flowviz.data.SeriesRef> allTabSeries = tabManager.getAllSelectedSeriesAcrossTabs();
        List<com.kalix.ide.flowviz.data.LastSeries> lastSeriesRefs = allTabSeries.stream()
            .filter(r -> r instanceof com.kalix.ide.flowviz.data.LastSeries)
            .map(r -> (com.kalix.ide.flowviz.data.LastSeries) r)
            .collect(java.util.stream.Collectors.toList());

        if (lastSeriesRefs.isEmpty()) {
            return;
        }

        boolean anySyncReplacement = false;

        for (com.kalix.ide.flowviz.data.LastSeries ref : lastSeriesRefs) {
            String seriesName = ref.baseName();

            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(newSessionKey, seriesName);
            if (cachedData != null) {
                // Synchronous replacement on EDT — atomic via DataSet.addSeries replacing
                // the existing entry for this ref.
                addSeriesToPool(ref, cachedData);
                tabManager.updateSeriesInStatsTabsWithAggregation(ref, cachedData);
                anySyncReplacement = true;
            } else {
                // Async fetch. requestTimeSeries returns an existing in-flight future
                // for the same (session, seriesName) if one exists, so duplicate
                // requests piggyback cheaply.
                timeSeriesRequestManager.requestTimeSeries(newSessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            // Drop response if a newer run has become "Last" since this
                            // request was issued.
                            if (capturedGeneration != lastRunGeneration) {
                                return;
                            }
                            addSeriesToPool(ref, timeSeriesData);
                            tabManager.updateSeriesInStatsTabsWithAggregation(ref, timeSeriesData);

                            // Only refresh tabs if something on screen needs to redraw.
                            if (tabManager.isSeriesSelectedOnAnyTab(ref)) {
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

        // Refresh once after the synchronous work, only if any series was swapped now. Async
        // callbacks refresh independently when their data arrives. Avoids the prior bug
        // where this unconditional refresh fired before async data was ready, rebuilding
        // displayDataSet with the series missing.
        if (anySyncReplacement) {
            tabManager.updateAllTabs(false);
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

        // Block timeseries tree selection events during rebuild and restoration
        isUpdatingSelection = true;
        updateOutputsTree();

        // Restore visual selection to match what's currently plotted on active tab
        Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
        Set<com.kalix.ide.flowviz.data.SeriesRef> restoredSeries = restoreTreeSelectionForSeries(tabSeries);
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


    // renameTimeSeriesData removed — TimeSeriesData no longer carries identity, so
    // there's no rename operation. The pool stores (ref, data) pairs and the same
    // TimeSeriesData instance can sit under any ref.

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

        // Build new set of selected series, ref-keyed directly from the leaves
        Set<com.kalix.ide.flowviz.data.SeriesRef> newSelectedSeries = new LinkedHashSet<>();
        Map<com.kalix.ide.flowviz.data.SeriesRef, OutputsTreeBuilder.SeriesLeafNode> refToLeaf = new HashMap<>();

        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            com.kalix.ide.flowviz.data.SeriesRef ref = seriesRefForLeaf(leaf);
            if (ref == null) continue;
            newSelectedSeries.add(ref);
            refToLeaf.put(ref, leaf);
        }

        // Get the target tab's current series for diffing
        Set<com.kalix.ide.flowviz.data.SeriesRef> currentTabSeries = tabManager.getTargetTabSelectedSeries();

        // When filtering with an additive click, preserve series hidden by the filter
        if (treeFilterManager.isFiltering() && isAdditiveSelectionEvent()) {
            Set<com.kalix.ide.flowviz.data.SeriesRef> visibleRefs = getVisibleSeriesKeys();
            for (com.kalix.ide.flowviz.data.SeriesRef ref : currentTabSeries) {
                if (!visibleRefs.contains(ref)) {
                    newSelectedSeries.add(ref);
                }
            }
        }

        // Check if there's overlap between old and new selections for zoom decision
        boolean hasOverlap = currentTabSeries.stream().anyMatch(newSelectedSeries::contains);
        final boolean shouldResetZoom = currentTabSeries.isEmpty() || !hasOverlap;

        // Determine which series need data fetched (not yet in the pool)
        Set<com.kalix.ide.flowviz.data.SeriesRef> seriesToFetch = new HashSet<>(newSelectedSeries);
        seriesToFetch.removeIf(ref -> plotDataSet.getSeries(ref) != null);

        // Capture the target PlotPanel for async callbacks
        final PlotPanel targetPanel = tabManager.getTargetPlotPanel();

        // Group new run series needing fetch by data source. Dataset series take their own
        // path below.
        Map<String, List<com.kalix.ide.flowviz.data.SeriesRef>> dataSourceToRefs = new LinkedHashMap<>();
        Set<com.kalix.ide.flowviz.data.SeriesRef> datasetRefs = new HashSet<>();

        for (com.kalix.ide.flowviz.data.SeriesRef ref : seriesToFetch) {
            OutputsTreeBuilder.SeriesLeafNode leaf = refToLeaf.get(ref);
            if (leaf == null) continue;

            // Assign a palette slot if not already assigned
            seriesSlotManager.assignSlot(ref);

            if (leaf.source instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                datasetRefs.add(ref);
            } else {
                RunInfoImpl runInfo = (RunInfoImpl) leaf.source;
                SessionManager.KalixSession resolvedSession = resolveRunInfoSession(runInfo);
                if (resolvedSession == null) continue;

                String sessionKey = resolvedSession.getSessionKey();
                String seriesName = leaf.seriesName;
                String dataSourceKey = sessionKey + "|" + seriesName;

                dataSourceToRefs.computeIfAbsent(dataSourceKey, k -> new ArrayList<>()).add(ref);
            }
        }

        // Also assign palette slots for series new to this tab but already in pool
        for (com.kalix.ide.flowviz.data.SeriesRef ref : newSelectedSeries) {
            if (!currentTabSeries.contains(ref)) {
                seriesSlotManager.assignSlot(ref);
            }
        }

        // Fetch run series data into the pool
        for (Map.Entry<String, List<com.kalix.ide.flowviz.data.SeriesRef>> entry : dataSourceToRefs.entrySet()) {
            String dataSourceKey = entry.getKey();
            List<com.kalix.ide.flowviz.data.SeriesRef> refs = entry.getValue();

            String[] parts = dataSourceKey.split("\\|", 2);
            String sessionKey = parts[0];
            String seriesName = parts[1];

            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, seriesName);
            if (cachedData != null) {
                for (com.kalix.ide.flowviz.data.SeriesRef ref : refs) {
                    addSeriesToPool(ref, cachedData);
                    tabManager.updateSeriesInStatsTabsWithAggregation(ref, cachedData);
                }
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                for (com.kalix.ide.flowviz.data.SeriesRef ref : refs) {
                    tabManager.addLoadingSeriesInStatsTabs(ref);
                }

                final List<com.kalix.ide.flowviz.data.SeriesRef> capturedRefs = new ArrayList<>(refs);
                final Set<com.kalix.ide.flowviz.data.SeriesRef> capturedNewSelection = new LinkedHashSet<>(newSelectedSeries);
                // Captured to detect whether "Last" has changed since this request was issued.
                // Only applied to LastSeries refs; RunSeries / DatasetSeries refs are tied to
                // their own immutable identity and are not generation-dependent.
                final long capturedGeneration = lastRunGeneration;

                timeSeriesRequestManager.requestTimeSeries(sessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            final boolean lastIsStale = capturedGeneration != lastRunGeneration;
                            for (com.kalix.ide.flowviz.data.SeriesRef capturedRef : capturedRefs) {
                                // Drop LastSeries writes if Last has changed since this
                                // request was issued — refreshLastSeries() for the new Last
                                // will fetch the correct data.
                                if (lastIsStale && capturedRef instanceof com.kalix.ide.flowviz.data.LastSeries) {
                                    continue;
                                }
                                // Check if series is still selected on the target tab
                                if (capturedNewSelection.contains(capturedRef)) {
                                    addSeriesToPool(capturedRef, timeSeriesData);
                                    tabManager.updateSeriesInStatsTabsWithAggregation(capturedRef, timeSeriesData);
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
                            for (com.kalix.ide.flowviz.data.SeriesRef capturedRef : capturedRefs) {
                                tabManager.addErrorSeriesInStatsTabs(capturedRef, throwable.getMessage());
                            }
                        });
                        return null;
                    });
            } else {
                for (com.kalix.ide.flowviz.data.SeriesRef ref : refs) {
                    tabManager.addLoadingSeriesInStatsTabs(ref);
                }
            }
        }

        // Fetch dataset series into the pool. The dataset cache is keyed by the
        // DatasetSeries ref (absolutePath + baseName); we already have that ref.
        for (com.kalix.ide.flowviz.data.SeriesRef ref : datasetRefs) {
            if (!(ref instanceof com.kalix.ide.flowviz.data.DatasetSeries datasetRef)) continue;

            TimeSeriesData cachedData = datasetSeriesCache.get(datasetRef);
            if (cachedData != null) {
                addSeriesToPool(ref, cachedData);
                tabManager.updateSeriesInStatsTabsWithAggregation(ref, cachedData);
            } else {
                logger.warn("Dataset series not found in cache: {}", datasetRef);
                tabManager.addErrorSeriesInStatsTabs(ref, "Series not found");
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
    private Set<com.kalix.ide.flowviz.data.SeriesRef> getVisibleSeriesKeys() {
        Set<com.kalix.ide.flowviz.data.SeriesRef> refs = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
        collectAllLeafNodesRecursive(root, allLeaves);
        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            com.kalix.ide.flowviz.data.SeriesRef ref = seriesRefForLeaf(leaf);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
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
     * Adds a series to the shared data pool under the given {@link com.kalix.ide.flowviz.data.SeriesRef}.
     * The data's legacy name field is ignored — identity comes from the ref.
     * Legend and visibility are managed per-tab via VisualizationTabManager.
     */
    private void addSeriesToPool(com.kalix.ide.flowviz.data.SeriesRef ref, TimeSeriesData timeSeriesData) {
        plotDataSet.addSeries(ref, timeSeriesData);
    }

    /**
     * Called when the user switches tabs. Syncs the timeseries tree selection
     * to reflect the new active tab's selected series.
     */
    private void onTabChanged() {
        Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
        if (tabSeries == null) return;

        isUpdatingSelection = true;
        try {
            restoreTreeSelectionForSeries(tabSeries);
        } finally {
            isUpdatingSelection = false;
        }
    }

}