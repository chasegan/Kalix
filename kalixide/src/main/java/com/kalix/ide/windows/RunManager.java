package com.kalix.ide.windows;

import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.VizHost;
import com.kalix.ide.flowviz.data.AggregateLabel;
import com.kalix.ide.flowviz.data.AggregateSeries;
import com.kalix.ide.flowviz.data.AggregateSource;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DatasetSource;
import com.kalix.ide.flowviz.data.DefaultLabelResolver;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.LastSeries;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.RunSeries;
import com.kalix.ide.flowviz.data.RunSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.DatasetSeriesSource;
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
import com.kalix.ide.renderers.OutputsTreeCellRenderer;
import com.kalix.ide.renderers.RunTreeCellRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
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
 * ├── Loaded datasets   → Imported CSV/Pixie files
 * └── Aggregate series  → User-created aggregates, grouped by origin (Run_1 > sum_1)
 * </pre>
 *
 * <h2>Data Flow</h2>
 * <ol>
 *   <li>User checks source(s) in data source tree → {@link #onSourceTreeCheckedChanged}</li>
 *   <li>Timeseries tree is rebuilt with available outputs → {@link OutputsTreeBuilder#updateTree}</li>
 *   <li>User checks series in timeseries tree → {@link SeriesFetchCoordinator#onOutputsTreeCheckedChanged}</li>
 *   <li>Data is fetched via {@link TimeSeriesRequestManager} (cached by kalixcliUid:seriesName)</li>
 *   <li>Data is added to shared {@link DataSet} pool → {@link #addSeriesToPool}</li>
 *   <li>All plot tabs are updated → {@link VisualizationTabManager#updateAllTabs}</li>
 * </ol>
 *
 * <h2>Selection Tracking</h2>
 * <ul>
 *   <li>Per-tab selected series AND per-tab checked sources stored in
 *       {@link VisualizationTabManager} TabInfo — a tab is a complete view (sources +
 *       series + plot settings), restored as a whole by {@link #onTabChanged}</li>
 *   <li>{@link SeriesFetchCoordinator#isProgrammaticUpdate()} - Guard to prevent listener
 *       feedback loops during programmatic updates</li>
 *   <li>Checked state (what's plotted) is separate from plain tree selection (row highlight);
 *       checked state is restored after rebuilds via {@link #restoreTreeChecksForSeries}</li>
 * </ul>
 *
 * <h2>Window collaborators</h2>
 * This class is the window shell (layout, wiring, delegation). The run bookkeeping and
 * data orchestration live in four same-package collaborators:
 * <ul>
 *   <li>{@link RunTreeController} - session discovery/status/removal, run naming,
 *       rename and programmatic selection</li>
 *   <li>{@link LastRunTracker} - the "Last run" alias, its generation counter, and
 *       refreshing plotted "[Last]" series when a run completes</li>
 *   <li>{@link SeriesFetchCoordinator} - timeseries-tree selection diffing, cache
 *       probes, async fetches into the pool, and the selection-update guard</li>
 *   <li>{@link AggregatedSeriesController} - user-created aggregates: creating,
 *       holding and recomputing them, and their "Aggregate series" tree nodes</li>
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
    // Shows: Last run, Current runs, Run library, Loaded datasets, Aggregate series
    // Selection triggers rebuild of timeseries tree
    private JCheckboxTree timeseriesSourceTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode lastRunNode;
    private DefaultMutableTreeNode currentRunsNode;
    private DefaultMutableTreeNode libraryNode;
    private DefaultMutableTreeNode loadedDatasetsNode;
    private DefaultMutableTreeNode aggregateSeriesNode;

    // === TIMESERIES TREE (left-bottom) ===
    // Shows hierarchical output series from selected sources
    // Selection triggers data fetching and plotting
    private JCheckboxTree timeseriesTree;
    private DefaultTreeModel timeseriesTreeModel;
    private JScrollPane timeseriesScrollPane;

    // === VISUALIZATION (right side) ===
    // Shared DataSet is the single source of truth for plotted data
    // All plot tabs read from this shared dataset
    private VisualizationTabManager tabManager;
    private DataSet plotDataSet;

    // Loaded dataset series.
    // Key: DatasetSeries ref qualifying by absolute path + base name. The base name is the
    // series' own hierarchy only (no filename prefix), so it can collate with runs and other
    // files by name; the absolute-path qualifier is therefore essential to keep identically
    // named series from different files distinct. Keying by ref preserves that separation.
    // Value: where the data comes from - held here for formats parsed whole on load, a
    // PixieStore key for Pixie, so decoded Pixie data is never pinned by this map.
    // Keys double as the list of each dataset's series for the outputs tree.
    private final Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources = new HashMap<>();

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
    private AggregatedSeriesController aggregatedSeriesController;

    // Single point of authority for projecting SeriesRef → display label.
    // Consumed by stats tables, legends, and the outputs tree so that the user-visible
    // string for a run-derived series tracks the current run name automatically.
    private final DefaultLabelResolver labelResolver =
        new DefaultLabelResolver(this::runNameForId, this::aggregateLabel);

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
        aggregateSeriesNode = new DefaultMutableTreeNode("Aggregate series");

        rootNode.add(lastRunNode);
        rootNode.add(currentRunsNode);
        rootNode.add(libraryNode);
        rootNode.add(loadedDatasetsNode);
        rootNode.add(aggregateSeriesNode);

        treeModel = new DefaultTreeModel(rootNode);
        timeseriesSourceTree = new JCheckboxTree(treeModel) {
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

        // Add checked-state listener to update details panel with outputs. Plain tree
        // selection (mouse focus / right-click target) is deliberately left alone here.
        timeseriesSourceTree.addCheckChangeListener(this::onSourceTreeCheckedChanged);

        // Initialize visualization components
        createDetailsComponents();
    }

    private void createDetailsComponents() {
        // Create timeseries tree
        DefaultMutableTreeNode outputsRootNode = new DefaultMutableTreeNode("Outputs");
        timeseriesTreeModel = new DefaultTreeModel(outputsRootNode);
        timeseriesTree = new JCheckboxTree(timeseriesTreeModel);
        timeseriesTree.setRootVisible(false);
        timeseriesTree.setShowsRootHandles(true);
        timeseriesTree.setCellRenderer(new OutputsTreeCellRenderer());

        // Enable multiple selection for the timeseries tree to allow plotting multiple series
        timeseriesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);

        // Add checked-state listener to fetch timeseries data for leaf nodes and update plot
        timeseriesTree.addCheckChangeListener(this::onOutputsTreeCheckedChanged);

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
                ? new RunSeries(lastRunInfo.getRunId(), last.baseName())
                : null;
        });

        // Initialize series slot assignment. Slots are resolved against the global
        // palette so every plot tab in this window styles a series consistently.
        seriesSlotManager = new SeriesSlotManager();

        // Create tab manager with shared data and a palette-backed style resolver
        tabManager = new VisualizationTabManager(plotDataSet,
            new PaletteSeriesStyleResolver(seriesSlotManager, PlotPaletteManager.getInstance()));

        // Install this window as the manager's host: label projection for legends
        // and stats rows, the model directory as the "Save Data" dialog seed, and
        // tree re-sync when the active tab (or its record) changes. Must happen
        // *before* the default plot tab is added so the first panel picks it up.
        tabManager.setHost(new VizHost() {
            @Override
            public com.kalix.ide.flowviz.data.LabelResolver labelResolver() {
                return labelResolver;
            }

            @Override
            public java.io.File baseDirectory() {
                return baseDirectorySupplier != null ? baseDirectorySupplier.get() : null;
            }

            @Override
            public void onActiveTabChanged() {
                onTabChanged();
            }
        });

        // Add the default tab: one plot tab with "Last run" checked and nothing else.
        // The same factory repopulates the strip when the final tab is closed, so a
        // fresh window and a fully-cleared one look identical.
        tabManager.addDefaultPlotTab();
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
            datasetSeriesSources,
            () -> lastRunTracker.getGeneration(),
            () -> lastRunTracker.getLastRunInfo(),
            () -> aggregatedSeriesController.pixieBackedPoints()
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
            timeSeriesRequestManager,
            lastRunTracker,
            fetchCoordinator
        );
        aggregatedSeriesController = new AggregatedSeriesController(
            this,
            timeseriesSourceTree,
            timeseriesTree,
            treeModel,
            aggregateSeriesNode,
            tabManager,
            plotDataSet,
            timeSeriesRequestManager,
            labelResolver,
            datasetSeriesSources,
            lastRunTracker,
            fetchCoordinator,
            statusUpdater
        );
        lastRunTracker.addLastChangeListener(aggregatedSeriesController::onLastChanged);

        // DatasetLoaderManager - handles dataset file loading
        datasetLoaderManager = new DatasetLoaderManager(
            this,                             // Parent frame
            datasetSeriesSources,             // Series sources
            loadedDatasetsNode,               // Tree node
            treeModel,                        // Tree model
            statusUpdater,                    // Status updater
            this::onDatasetLoaded,            // Callback after load
            names -> aggregatedSeriesController.datasetNameClash(names)  // Refuse aggregate-name clashes
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
        runContextMenuManager.setupOutputsTreeContextMenu(aggregatedSeriesController::createAggregatedSeries,
                                                          this::expandAllFromSelected,
                                                          this::collapseAllFromSelected,
                                                          this::showChecked,
                                                          this::showSelected
        );
    }

    /**
     * Gets series names from a source (RunInfo, LoadedDatasetInfo, or AggregateInfo).
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
        } else if (source instanceof AggregateInfo aggregate) {
            return List.of(labelResolver.nameFor(aggregate.ref()));
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
        return datasetSeriesSources.keySet().stream()
            .filter(ref -> ref.datasetId().equals(datasetId))
            .map(DatasetSeries::baseName)
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

    private void showChecked() {
        // Collapse all and then expand to checked paths only
        collapseAllChildren(new TreePath(timeseriesTreeModel.getRoot()));
        TreePath[] checkedPaths = timeseriesTree.getCheckedPaths();
        for (TreePath path : checkedPaths) {
            timeseriesTree.makeVisible(path);
        }
        scrollToTopmost(checkedPaths);
    }

    private void showSelected() {
        // Captured first: collapsing a node replaces any selected descendants with the node itself.
        TreePath[] selectedPaths = timeseriesTree.getSelectionPaths();
        collapseAllChildren(new TreePath(timeseriesTreeModel.getRoot()));
        // Restoring the selection also reveals it: JTree expands selected paths by default.
        timeseriesTree.setSelectionPaths(selectedPaths);
        scrollToTopmost(selectedPaths);
    }

    /**
     * Scrolls to the highest revealed row, so the folded tree opens at the top of what was
     * revealed rather than wherever the viewport happened to sit. Topmost by row order, not
     * by the order the paths were ticked.
     */
    private void scrollToTopmost(TreePath[] paths) {
        if (paths == null) {
            return;
        }
        int topRow = Integer.MAX_VALUE;
        for (TreePath path : paths) {
            int row = timeseriesTree.getRowForPath(path);
            if (row >= 0) {
                topRow = Math.min(topRow, row);
            }
        }
        if (topRow != Integer.MAX_VALUE) {
            timeseriesTree.scrollRowToVisible(topRow);
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
        // The root is hidden, so collapsing it would hide every row rather than fold them.
        if (path.getParentPath() != null || timeseriesTree.isRootVisible()) {
            timeseriesTree.collapsePath(path);
        }
    }

    /**
     * Refreshes the data source tree with current session states. Delegates to
     * {@link RunTreeController#refreshRuns}, which marshals itself to the EDT.
     */
    public void refreshRuns() {
        runTreeController.refreshRuns();
    }

    /**
     * Checks {@code refs} in the outputs tree, in addition to what is already checked, so
     * the target tab plots them. Refs with no visible node (e.g. hidden by the filter) are
     * skipped.
     */
    void checkOutputsSeries(Set<SeriesRef> refs) {
        List<TreePath> paths = new ArrayList<>();
        searchAndCollectPaths((DefaultMutableTreeNode) timeseriesTreeModel.getRoot(), refs, paths);
        timeseriesTree.addCheckedPaths(paths);
    }

    /**
     * Recursively searches tree nodes for matching series keys and collects their paths.
     */
    private void searchAndCollectPaths(DefaultMutableTreeNode node,
                                        Set<SeriesRef> targetRefs,
                                        List<TreePath> results) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode that maps to one of our target refs
        if (userObject instanceof OutputsTreeBuilder.SeriesLeafNode leaf) {
            SeriesRef ref = seriesRefForLeaf(leaf);
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
     * Constructs the {@link SeriesRef} that identifies the
     * data behind a {@link OutputsTreeBuilder.SeriesLeafNode}. The leaf still carries
     * the legacy {@code source} object (RunInfoImpl or LoadedDatasetInfo) plus
     * {@code seriesName} — this helper produces the typed ref from that pair.
     * Will move onto the leaf itself when OutputsTreeBuilder is migrated.
     */
    private SeriesRef seriesRefForLeaf(OutputsTreeBuilder.SeriesLeafNode leaf) {
        return leaf.ref;
    }

    /**
     * Constructs the {@link SeriesRef} for a (seriesName, source)
     * pair. Used by {@link OutputsTreeBuilder} when building leaves and parents — the
     * resulting ref is cached on the leaf so subsequent lookups don't re-project.
     */
    private SeriesRef refForSource(String seriesName, Object source) {
        return switch (sourceRefForNode(source)) {
            case RunSource r -> new RunSeries(r.runId(), seriesName);
            case LastSource l -> new LastSeries(seriesName);
            case DatasetSource d -> new DatasetSeries(d.datasetId(), seriesName);
            case AggregateSource a -> new AggregateSeries(a.aggregateId());
            case null -> null;
        };
    }

    /**
     * Restores checked state to match the given series set.
     * This ensures the tree visually reflects what's plotted, even after tree rebuilds
     * (rebuilds reset checked state, since old TreePaths reference discarded nodes).
     *
     * <p>Purely a projection onto the <em>visible</em> tree: a series the filter hides
     * has no node to check, and that says nothing about whether it is still plotted.
     * Which series a tab keeps is {@link #reconcileTabSeriesWithSources}' question.</p>
     *
     * @param seriesToRestore The set of series keys to check in the tree
     */
    void restoreTreeChecksForSeries(Set<SeriesRef> seriesToRestore) {
        if (seriesToRestore.isEmpty()) {
            timeseriesTree.setCheckedPaths(Collections.emptyList());
            return;
        }

        List<TreePath> pathsToCheck = new ArrayList<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();

        // Tick in the tab's series order
        for (SeriesRef ref : seriesToRestore) {
            searchAndCollectPaths(root, Collections.singleton(ref), pathsToCheck);
        }

        // Note: Callers run inside a programmatic-update section to block events
        timeseriesTree.setCheckedPaths(pathsToCheck);
    }

    /**
     * Reconciles the target tab's selected series with what the checked sources offer,
     * removing series whose source has gone (a run unchecked) or no longer produces
     * them (a new Last run without that output).
     *
     * <p>Availability is asked of the sources, never of the visible tree: under a
     * filter the tree omits series that are still offered, and pruning against it
     * emptied the plot on any source change while filtering (#431).</p>
     */
    void reconcileTabSeriesWithSources(Set<SeriesRef> tabSeries) {
        List<Object> checkedRuns = new ArrayList<>();
        List<Object> checkedDatasets = new ArrayList<>();
        collectCheckedSources(checkedRuns, checkedDatasets);
        List<Object> checkedSources = new ArrayList<>(checkedRuns);
        checkedSources.addAll(checkedDatasets);

        // Find series that need to be removed from the tab
        Set<SeriesRef> seriesToRemove = new HashSet<>(tabSeries);
        seriesToRemove.removeAll(outputsTreeBuilder.availableRefs(checkedSources));

        if (seriesToRemove.isEmpty()) {
            return;
        }

        // Update the target tab's series (remove unrestorable ones)
        Set<SeriesRef> updatedSeries = new LinkedHashSet<>(tabSeries);
        updatedSeries.removeAll(seriesToRemove);
        tabManager.setTargetTabSelectedSeries(updatedSeries);

        // Remove from stats tables
        for (SeriesRef ref : seriesToRemove) {
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

    /** The aggregate lookup for {@link DefaultLabelResolver}. */
    private AggregateLabel aggregateLabel(long aggregateId) {
        return aggregatedSeriesController.labelFor(aggregateId);
    }

    /**
     * Returns the {@link LabelResolver} bound to this
     * RunManager's state. Components that need to render series labels — stats tables,
     * plot legends, the outputs tree — should obtain the resolver here rather than
     * constructing label strings themselves.
     */
    public LabelResolver getLabelResolver() {
        return labelResolver;
    }

    /**
     * Handles data-source-tree checked-state changes to update the timeseries tree.
     */
    private void onSourceTreeCheckedChanged() {
        // Ignore checked-state changes during programmatic updates
        if (fetchCoordinator.isProgrammaticUpdate()) {
            return;
        }

        // Record the new source context on the target tab, so switching back to this
        // tab later restores it (sources and series are both per-tab state).
        snapshotSourceChecksToTargetTab();

        // Block timeseries tree checked-state events during rebuild and restoration
        fetchCoordinator.beginProgrammaticUpdate();
        try {
            updateOutputsTree();

            // Restore checked state to match what's currently plotted on active tab
            Set<SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
            restoreTreeChecksForSeries(tabSeries);
            reconcileTabSeriesWithSources(tabSeries);
        } finally {
            fetchCoordinator.endProgrammaticUpdate();
        }

        // A source tick/untick is an undoable action in its own right. If the
        // reconcile above already pushed (series were pruned), this dedupes.
        tabManager.pushTargetTabHistory();
    }

    /**
     * Handles filter text changes. Rebuilds the timeseries tree with the current filter
     * applied. Purely visual - does NOT change selection or affect plots.
     */
    private void onFilterTextChanged() {
        fetchCoordinator.beginProgrammaticUpdate();
        try {
            outputsTreeBuilder.setFilterText(treeFilterManager.getFilterText());
            updateOutputsTree();
            restoreTreeChecksForSeries(tabManager.getTargetTabSelectedSeries());
        } finally {
            fetchCoordinator.endProgrammaticUpdate();
        }
    }

    /**
     * Updates the timeseries tree based on current run tree selection.
     */
    void updateOutputsTree() {
        List<Object> checkedRuns = new ArrayList<>();
        List<Object> checkedDatasets = new ArrayList<>();
        collectCheckedSources(checkedRuns, checkedDatasets);

        if (checkedRuns.isEmpty() && checkedDatasets.isEmpty()) {
            outputsTreeBuilder.showEmptyTree(OutputsTreeBuilder.SELECT_SOURCES_MESSAGE);
        } else {
            outputsTreeBuilder.updateTree(checkedRuns, checkedDatasets);
        }
    }

    /**
     * Collects the RunInfo, AggregateInfo and LoadedDatasetInfo objects currently checked in the data
     * source tree. Category paths (auto-checked parents) are neither, and are skipped.
     */
    private void collectCheckedSources(List<Object> checkedRuns, List<Object> checkedDatasets) {
        for (TreePath path : timeseriesSourceTree.getCheckedPaths()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObject = node.getUserObject();

            // Aggregates go with runs: the builder only tells datasets apart for expansion.
            if (userObject instanceof RunContextMenuManager.RunInfo
                    || userObject instanceof AggregateInfo) {
                checkedRuns.add(userObject);
            } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                checkedDatasets.add(userObject);
            }
        }
    }

    /**
     * Handles checked-state changes in the timeseries tree. Delegates to
     * {@link SeriesFetchCoordinator#onOutputsTreeCheckedChanged}.
     */
    private void onOutputsTreeCheckedChanged() {
        fetchCoordinator.onOutputsTreeCheckedChanged();
    }

    /**
     * Adds a series to the shared data pool under the given {@link SeriesRef}.
     * The data's legacy name field is ignored — identity comes from the ref.
     * Legend and visibility are managed per-tab via VisualizationTabManager.
     */
    void addSeriesToPool(SeriesRef ref, TimeSeriesData timeSeriesData) {
        plotDataSet.addSeries(ref, timeSeriesData);
    }

    /**
     * Removes a loaded dataset and every trace of its series from this window:
     * the shared {@code plotDataSet} pool, the per-context {@code datasetSeriesSources}
     * and {@link com.kalix.ide.flowviz.style.SeriesSlotManager}, every plot and
     * stats tab, and the source tree node itself. The outputs tree refreshes via
     * the tree's selection-change listener if the dataset was selected.
     *
     * <p>Wired as the {@code removeDatasetDelegate} of {@link RunContextMenuManager}.</p>
     */
    public void removeLoadedDataset(DatasetLoaderManager.LoadedDatasetInfo info) {
        String absPath = info.file.getAbsolutePath();

        // Collect all DatasetSeries refs that belong to this dataset.
        List<SeriesRef> refs = new ArrayList<>();
        for (DatasetSeries dsRef : datasetSeriesSources.keySet()) {
            if (dsRef.datasetId().equals(absPath)) {
                refs.add(dsRef);
            }
        }

        datasetSeriesSources.keySet().removeIf(dsRef -> dsRef.datasetId().equals(absPath));

        // Node first, exactly as run removal does: see removeSourceNode.
        removeSourceNode(loadedDatasetsNode, info);
        purgeSeries(refs, new DatasetSource(absPath));

        if (statusUpdater != null) {
            statusUpdater.accept("Removed dataset: " + info.fileName);
        }
    }

    /**
     * Removes the child of {@code parent} holding {@code userObject} from the source tree.
     *
     * <p>Call before {@link #purgeSeries}. If the source was checked, removePath fires the
     * tick listener synchronously: its snapshot+reconcile prunes the source AND its series
     * from the active tab in ONE history entry. Purging first pushed while the record still
     * held the dead source, and the listener then pushed again without it — two entries
     * differing only in sources, making the first Undo a visible no-op.</p>
     */
    void removeSourceNode(DefaultMutableTreeNode parent, Object userObject) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() == userObject) {
                timeseriesSourceTree.removePath(new TreePath(child.getPath()));
                parent.remove(i);
                treeModel.nodesWereRemoved(parent, new int[]{i}, new Object[]{child});
                return;
            }
        }
    }

    /**
     * Drops {@code refs} from the shared pool, their colour slots and every tab, and forgets
     * {@code source} from every tab's recorded source context. For the active tab the tab
     * scrubs are no-ops after {@link #removeSourceNode} (its listener already did both);
     * background tabs are scrubbed silently by design.
     */
    void purgeSeries(List<SeriesRef> refs, SourceRef source) {
        for (SeriesRef ref : refs) {
            plotDataSet.removeSeries(ref);
            seriesSlotManager.removeSlot(ref);
        }
        tabManager.removeSeriesFromAllTabs(refs);
        tabManager.removeSourceFromAllTabs(source);
    }

    /**
     * Projects a source-tree node's user object to its stable {@link SourceRef}
     * identity, or {@code null} for anything that isn't a data source (category
     * headers, the root).
     */
    SourceRef sourceRefForNode(Object userObject) {
        if (userObject instanceof RunInfoImpl runInfo) {
            return runInfo.isLastAlias() ? new LastSource() : new RunSource(runInfo.getRunId());
        }
        if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo info) {
            return new DatasetSource(info.file.getAbsolutePath());
        }
        if (userObject instanceof AggregateInfo aggregate) {
            return new AggregateSource(aggregate.id);
        }
        return null;
    }

    /**
     * Returns the {@link SourceRef}s of the sources currently checked in the data
     * source tree, in check order. Category paths (auto-checked parents) project to
     * {@code null} and are skipped.
     */
    private Set<SourceRef> currentCheckedSourceRefs() {
        Set<SourceRef> refs = new LinkedHashSet<>();
        for (TreePath path : timeseriesSourceTree.getCheckedPaths()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            SourceRef ref = sourceRefForNode(node.getUserObject());
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Records the currently checked sources on the target tab. Called from
     * {@link #onSourceTreeCheckedChanged} — i.e. whenever the checked set genuinely
     * changes — and nowhere else: recording must stay tied to check <em>changes</em>,
     * never to restore paths like {@link #onTabChanged}.
     *
     * <p>A tree snapshot only replaces what the tree can currently express. A recorded ref
     * with no tree path yet — the default tab's {@link LastSource} before the first run
     * completes — is neither confirmed nor denied by the tree, so it is carried forward
     * untouched; the tree gains a node for it later and the next snapshot takes over.
     * Sources that have genuinely gone are scrubbed by {@link #removeSourceFromAllTabs},
     * not here.</p>
     */
    private void snapshotSourceChecksToTargetTab() {
        Set<SourceRef> recorded = tabManager.getTargetTabCheckedSources();
        Set<SourceRef> snapshot = currentCheckedSourceRefs();
        // Build the new set fully before recording it: the recorded set is a live view of
        // the very set the setter replaces.
        //
        // Caution: "has a node" is not proof of representability DURING TEARDOWN — a
        // node mid-removal is still attached when its removePath check-change fires,
        // so an unguarded removal would see its ref as representable-but-unchecked
        // and silently drop it here (this stripped LastSource from the active tab
        // until Last-node removal was guarded; see LastRunTracker.clearLast).
        for (SourceRef ref : recorded) {
            if (pathForSourceRef(ref) == null) {
                snapshot.add(ref);
            }
        }
        tabManager.setTargetTabCheckedSources(snapshot);
    }

    /** The subset of {@code refs} the source tree can currently show a node for. */
    private Set<SourceRef> representable(Set<SourceRef> refs) {
        Set<SourceRef> result = new LinkedHashSet<>();
        for (SourceRef ref : refs) {
            if (pathForSourceRef(ref) != null) {
                result.add(ref);
            }
        }
        return result;
    }

    /**
     * Resolves a {@link SourceRef} back to its current tree path, or {@code null} if
     * the source no longer exists (run removed, dataset unloaded, no Last yet).
     */
    private TreePath pathForSourceRef(SourceRef ref) {
        DefaultMutableTreeNode parent = switch (ref) {
            case LastSource ignored -> lastRunNode;
            case RunSource ignored -> currentRunsNode;
            case DatasetSource ignored -> loadedDatasetsNode;
            case AggregateSource ignored -> aggregateSeriesNode;
        };
        // Traverse all descendants; aggregates sit a level further down.
        Enumeration<TreeNode> nodes = parent.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node != parent && ref.equals(sourceRefForNode(node.getUserObject()))) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }

    private List<TreePath> pathsForSourceRefs(Set<SourceRef> refs) {
        List<TreePath> paths = new ArrayList<>();
        for (SourceRef ref : refs) {
            TreePath path = pathForSourceRef(ref);
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * Called when the user switches tabs. Restores the new active tab's recorded context
     * verbatim: first the data-source checks (rebuilding the outputs tree for that
     * context), then the series checks. A tab is a complete view — sources + series +
     * plot settings — so switching tabs swaps the whole left panel to match; an empty
     * tab restores an empty panel.
     *
     * <p>This is strictly a <em>read</em> of tab state — it must never write any tab's
     * recorded context. (An earlier "unconfigured tabs adopt the current context"
     * heuristic wrote to the incoming tab here, which silently copied one tab's sources
     * onto another on every switch. Restore paths that also record state are how tabs
     * cross-contaminate; don't reintroduce one.)</p>
     */
    private void onTabChanged() {
        // Adding the default tab in createDetailsComponents() auto-selects it,
        // firing this callback before initializeManagers() has constructed fetchCoordinator.
        // Nothing to sync during construction (the tree is empty), so bail out early.
        if (fetchCoordinator == null) return;

        Set<SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
        Set<SourceRef> tabSources = tabManager.getTargetTabCheckedSources();

        fetchCoordinator.beginProgrammaticUpdate();
        try {
            // Skip the rebuild when the contexts are identical: no outputs-tree churn
            // when flicking between tabs that look at the same sources. Compare only what
            // the tree can show, or a ref with no node yet (see snapshotSourceChecksToTargetTab)
            // would make the tab look different from the tree on every switch.
            if (!representable(tabSources).equals(currentCheckedSourceRefs())) {
                timeseriesSourceTree.setCheckedPaths(pathsForSourceRefs(tabSources));
                updateOutputsTree();
            }
            restoreTreeChecksForSeries(tabSeries);
        } finally {
            fetchCoordinator.endProgrammaticUpdate();
        }
    }
}