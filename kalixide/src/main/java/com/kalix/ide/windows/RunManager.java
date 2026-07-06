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
import com.kalix.ide.flowviz.models.StatsTableModel;
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
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * └── Loaded datasets   → Imported CSV/Pixie files
 * </pre>
 *
 * <h2>Data Flow</h2>
 * <ol>
 *   <li>User selects source(s) in data source tree → {@link #onRunTreeSelectionChanged}</li>
 *   <li>Timeseries tree is rebuilt with available outputs → {@link OutputsTreeBuilder#updateTree}</li>
 *   <li>User selects series in timeseries tree → {@link SeriesFetchCoordinator#onOutputsTreeSelectionChanged}</li>
 *   <li>Data is fetched via {@link TimeSeriesRequestManager} (cached by kalixcliUid:seriesName)</li>
 *   <li>Data is added to shared {@link DataSet} pool → {@link #addSeriesToPool}</li>
 *   <li>All plot tabs are updated → {@link VisualizationTabManager#updateAllTabs}</li>
 * </ol>
 *
 * <h2>Selection Tracking</h2>
 * <ul>
 *   <li>Per-tab selected series stored in {@link VisualizationTabManager} TabInfo</li>
 *   <li>{@link SeriesFetchCoordinator#isUpdatingSelection()} - Guard to prevent listener
 *       feedback loops during programmatic updates</li>
 *   <li>Tree selection is restored after rebuilds via {@link #restoreTreeSelectionForSeries}</li>
 * </ul>
 *
 * <h2>Window collaborators</h2>
 * This class is the window shell (layout, wiring, delegation). The run bookkeeping and
 * data orchestration live in three same-package collaborators:
 * <ul>
 *   <li>{@link RunTreeController} - session discovery/status/removal, run naming,
 *       rename and programmatic selection</li>
 *   <li>{@link LastRunTracker} - the "Last run" alias, its generation counter, and
 *       refreshing plotted "[Last]" series when a run completes</li>
 *   <li>{@link SeriesFetchCoordinator} - timeseries-tree selection diffing, cache
 *       probes, async fetches into the pool, and the selection-update guard</li>
 * </ul>
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
    // Key: DatasetSeries ref qualifying by absolute path + base name. The base name is the
    // series' own hierarchy only (no filename prefix), so it can collate with runs and other
    // files by name; the absolute-path qualifier is therefore essential to keep identically
    // named series from different files distinct. Keying by ref preserves that separation.
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

    // === WINDOW COLLABORATORS ===
    // Extracted run bookkeeping / data orchestration (see class javadoc). Constructed in
    // initializeManagers(); same-package, package-private classes.
    private SeriesFetchCoordinator fetchCoordinator;
    private LastRunTracker lastRunTracker;
    private RunTreeController runTreeController;

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
            timeSeriesRequestManager::handleResultMessage
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
            instance.runTreeController.selectRun(sessionKey);
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
            return instance.runTreeController.getRunNameForSession(sessionKey);
        }
        return null;
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Kalix - Run Manager");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1200, 600);

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
        plotDataSet.setLastSeriesResolver(last -> {
            RunInfoImpl lastRunInfo = lastRunTracker != null ? lastRunTracker.getLastRunInfo() : null;
            return lastRunInfo != null
                ? new com.kalix.ide.flowviz.data.RunSeries(lastRunInfo.getRunId(), last.baseName())
                : null;
        });

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

        // Seed each plot tab's "Save Data" dialog with the model directory so it opens
        // in the same folder as the run tree's "Save results (csv)". Must happen *before*
        // the default plot tab is added so the first PlotPanel picks it up.
        tabManager.setBaseDirectorySupplier(
            () -> baseDirectorySupplier != null ? baseDirectorySupplier.get() : null);

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

        // Window collaborators. The coordinator's Last-run suppliers defer through the
        // lastRunTracker field (assigned immediately below), breaking the construction
        // cycle between the guard owner and the Last tracker.
        fetchCoordinator = new SeriesFetchCoordinator(
            this,
            timeseriesTree,
            timeseriesTreeModel,
            treeFilterManager,
            outputsTreeBuilder,
            tabManager,
            plotDataSet,
            seriesSlotManager,
            timeSeriesRequestManager,
            datasetSeriesCache,
            () -> lastRunTracker.getGeneration(),
            () -> lastRunTracker.getLastRunInfo()
        );
        lastRunTracker = new LastRunTracker(
            this,
            timeseriesSourceTree,
            treeModel,
            lastRunNode,
            tabManager,
            timeSeriesRequestManager,
            fetchCoordinator
        );
        runTreeController = new RunTreeController(
            this,
            stdioTaskManager,
            timeseriesSourceTree,
            treeModel,
            currentRunsNode,
            tabManager,
            plotDataSet,
            seriesSlotManager,
            timeSeriesRequestManager,
            lastRunTracker,
            fetchCoordinator
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
            runTreeController.sessionToRunNameView(),  // Session to run name map (live)
            this::refreshRuns,                // Refresh callback
            runTreeController::renameRun,     // Rename delegate (validation + propagation)
            this::removeLoadedDataset         // Remove-dataset delegate (pool/cache/tab cleanup)
        );

        // Set up context menus
        runContextMenuManager.setupRunTreeContextMenu();
        // Top-level categories that support "Remove all". Last run holds a single alias to the
        // most-recent run's session, so removing it removes that one run (same as its "Remove").
        runContextMenuManager.setRemovableCategories(
            lastRunNode, currentRunsNode, libraryNode, loadedDatasetsNode);
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
        if (!runTreeController.hasSession(sessionKey) ||
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
     * Refreshes the data source tree with current session states. Delegates to
     * {@link RunTreeController#refreshRuns}, which marshals itself to the EDT.
     */
    public void refreshRuns() {
        runTreeController.refreshRuns();
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
    Set<com.kalix.ide.flowviz.data.SeriesRef> restoreTreeSelectionForSeries(
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
    void reconcileSelectedSeriesWithTree(Set<com.kalix.ide.flowviz.data.SeriesRef> restoredSeries,
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
     * no run with that id is currently known. Bound into the
     * {@link com.kalix.ide.flowviz.data.DefaultLabelResolver} at construction time;
     * delegates to {@link RunTreeController#runNameForId}.
     */
    private String runNameForId(long runId) {
        return runTreeController.runNameForId(runId);
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

    // extractSeriesName removed — there are no series-key strings to parse anymore.
    // Identity is the typed SeriesRef; baseName comes from ref.baseName().

    /**
     * Handles tree selection changes to update the timeseries tree.
     */
    private void onRunTreeSelectionChanged(TreeSelectionEvent e) {
        // Ignore selection changes during programmatic updates
        if (fetchCoordinator.isUpdatingSelection()) {
            return;
        }

        // Block timeseries tree selection events during rebuild and restoration
        fetchCoordinator.setUpdatingSelection(true);
        updateOutputsTree();

        // Restore visual selection to match what's currently plotted on active tab
        Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
        Set<com.kalix.ide.flowviz.data.SeriesRef> restoredSeries = restoreTreeSelectionForSeries(tabSeries);
        reconcileSelectedSeriesWithTree(restoredSeries, tabSeries);

        fetchCoordinator.setUpdatingSelection(false);
    }

    /**
     * Handles filter text changes. Rebuilds the timeseries tree with the current filter
     * applied. Purely visual - does NOT change selection or affect plots.
     */
    private void onFilterTextChanged() {
        fetchCoordinator.setUpdatingSelection(true);
        try {
            outputsTreeBuilder.setFilterText(treeFilterManager.getFilterText());
            updateOutputsTree();
            restoreTreeSelectionForSeries(tabManager.getTargetTabSelectedSeries());
        } finally {
            fetchCoordinator.setUpdatingSelection(false);
        }
    }

    /**
     * Updates the timeseries tree based on current run tree selection.
     */
    void updateOutputsTree() {
        TreePath[] selectedPaths = timeseriesSourceTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            outputsTreeBuilder.showEmptyTree(OutputsTreeBuilder.SELECT_SOURCES_MESSAGE);
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
            outputsTreeBuilder.showEmptyTree(OutputsTreeBuilder.SELECT_SOURCES_MESSAGE);
        } else {
            outputsTreeBuilder.updateTree(selectedRuns, selectedDatasets);
        }
    }


    // renameTimeSeriesData removed — TimeSeriesData no longer carries identity, so
    // there's no rename operation. The pool stores (ref, data) pairs and the same
    // TimeSeriesData instance can sit under any ref.

    /**
     * Handles selection changes in the timeseries tree. Delegates to
     * {@link SeriesFetchCoordinator#onOutputsTreeSelectionChanged}.
     */
    private void onOutputsTreeSelectionChanged(TreeSelectionEvent e) {
        fetchCoordinator.onOutputsTreeSelectionChanged(e);
    }

    /**
     * Adds a series to the shared data pool under the given {@link com.kalix.ide.flowviz.data.SeriesRef}.
     * The data's legacy name field is ignored — identity comes from the ref.
     * Legend and visibility are managed per-tab via VisualizationTabManager.
     */
    void addSeriesToPool(com.kalix.ide.flowviz.data.SeriesRef ref, TimeSeriesData timeSeriesData) {
        plotDataSet.addSeries(ref, timeSeriesData);
    }

    /**
     * Removes a loaded dataset and every trace of its series from this window:
     * the shared {@code plotDataSet} pool, the per-context {@code datasetSeriesCache}
     * and {@link com.kalix.ide.flowviz.style.SeriesSlotManager}, every plot and
     * stats tab, and the source tree node itself. The outputs tree refreshes via
     * the tree's selection-change listener if the dataset was selected.
     *
     * <p>Wired as the {@code removeDatasetDelegate} of {@link RunContextMenuManager}.</p>
     */
    public void removeLoadedDataset(DatasetLoaderManager.LoadedDatasetInfo info) {
        String absPath = info.file.getAbsolutePath();

        // Collect all DatasetSeries refs that belong to this dataset.
        List<com.kalix.ide.flowviz.data.SeriesRef> refs = new ArrayList<>();
        for (com.kalix.ide.flowviz.data.DatasetSeries dsRef : datasetSeriesCache.keySet()) {
            if (dsRef.datasetId().equals(absPath)) {
                refs.add(dsRef);
            }
        }

        // Drop them from the shared pool, the per-context cache, and slot assignment.
        for (com.kalix.ide.flowviz.data.SeriesRef ref : refs) {
            plotDataSet.removeSeries(ref);
            seriesSlotManager.removeSlot(ref);
        }
        datasetSeriesCache.keySet().removeIf(dsRef -> dsRef.datasetId().equals(absPath));

        // Strip them from every tab's selection, legend, visible-series, and stats.
        tabManager.removeSeriesFromAllTabs(refs);

        // Remove the tree node — selection changes (if any) refresh the outputs tree.
        for (int i = 0; i < loadedDatasetsNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) loadedDatasetsNode.getChildAt(i);
            if (child.getUserObject() == info) {
                loadedDatasetsNode.remove(i);
                treeModel.nodesWereRemoved(loadedDatasetsNode, new int[]{i}, new Object[]{child});
                break;
            }
        }

        if (statusUpdater != null) {
            statusUpdater.accept("Removed dataset: " + info.fileName);
        }
    }

    /**
     * Called when the user switches tabs. Syncs the timeseries tree selection
     * to reflect the new active tab's selected series.
     */
    private void onTabChanged() {
        // Adding the default tabs in createDetailsComponents() auto-selects the first tab,
        // firing this callback before initializeManagers() has constructed fetchCoordinator.
        // Nothing to sync during construction (the tree is empty), so bail out early.
        if (fetchCoordinator == null) return;

        Set<com.kalix.ide.flowviz.data.SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
        if (tabSeries == null) return;

        fetchCoordinator.setUpdatingSelection(true);
        try {
            restoreTreeSelectionForSeries(tabSeries);
        } finally {
            fetchCoordinator.setUpdatingSelection(false);
        }
    }

}