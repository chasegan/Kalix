package com.kalix.ide.windows;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.LastSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.TreeFilterManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Orchestrates timeseries-tree selection changes for {@link RunManager}: diffing the
 * new selection against the target tab, probing caches, issuing async fetches, writing
 * results into the shared pool, and assigning palette slots.
 *
 * <p>Also owns the {@code isUpdatingSelection} guard used across the window to prevent
 * selection-listener feedback loops during programmatic tree updates: collaborators set
 * it to {@code true} before modifying tree selection and {@code false} after, and both
 * tree listeners consult it before reacting.</p>
 */
class SeriesFetchCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SeriesFetchCoordinator.class);

    private final RunManager window;
    private final JTree timeseriesTree;
    private final DefaultTreeModel timeseriesTreeModel;
    private final TreeFilterManager treeFilterManager;
    private final OutputsTreeBuilder outputsTreeBuilder;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final SeriesSlotManager seriesSlotManager;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final Map<DatasetSeries, TimeSeriesData> datasetSeriesCache;
    /**
     * Defers to {@link LastRunTracker#getGeneration()}. Captured at fetch-issue time so
     * async responses for "[Last]" series can be dropped when a newer run has become Last.
     */
    private final LongSupplier lastRunGeneration;
    /** Defers to {@link LastRunTracker#getLastRunInfo()} for resolving the Last alias. */
    private final Supplier<RunInfoImpl> lastRunInfoSupplier;

    // Flag to prevent selection listener feedback loops during programmatic tree updates
    // Set to true before modifying tree selection, false after
    private boolean isUpdatingSelection = false;

    SeriesFetchCoordinator(RunManager window,
                           JTree timeseriesTree,
                           DefaultTreeModel timeseriesTreeModel,
                           TreeFilterManager treeFilterManager,
                           OutputsTreeBuilder outputsTreeBuilder,
                           VisualizationTabManager tabManager,
                           DataSet plotDataSet,
                           SeriesSlotManager seriesSlotManager,
                           TimeSeriesRequestManager timeSeriesRequestManager,
                           Map<DatasetSeries, TimeSeriesData> datasetSeriesCache,
                           LongSupplier lastRunGeneration,
                           Supplier<RunInfoImpl> lastRunInfoSupplier) {
        this.window = window;
        this.timeseriesTree = timeseriesTree;
        this.timeseriesTreeModel = timeseriesTreeModel;
        this.treeFilterManager = treeFilterManager;
        this.outputsTreeBuilder = outputsTreeBuilder;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.seriesSlotManager = seriesSlotManager;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.datasetSeriesCache = datasetSeriesCache;
        this.lastRunGeneration = lastRunGeneration;
        this.lastRunInfoSupplier = lastRunInfoSupplier;
    }

    /** Returns whether a programmatic tree update is in progress. */
    boolean isUpdatingSelection() {
        return isUpdatingSelection;
    }

    /** Sets the programmatic-update guard. Callers must clear it when done (try/finally). */
    void setUpdatingSelection(boolean updating) {
        this.isUpdatingSelection = updating;
    }

    /**
     * Handles selection changes in the timeseries tree.
     * Supports recursive selection: selecting a parent node plots all its leaf children.
     * Fetches timeseries data for leaf nodes and updates plot and stats.
     */
    void onOutputsTreeSelectionChanged(TreeSelectionEvent e) {
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
        Set<SeriesRef> newSelectedSeries = new LinkedHashSet<>();
        Map<SeriesRef, OutputsTreeBuilder.SeriesLeafNode> refToLeaf = new HashMap<>();

        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            SeriesRef ref = seriesRefForLeaf(leaf);
            if (ref == null) continue;
            newSelectedSeries.add(ref);
            refToLeaf.put(ref, leaf);
        }

        // Get the target tab's current series for diffing
        Set<SeriesRef> currentTabSeries = tabManager.getTargetTabSelectedSeries();

        // When filtering with an additive click, preserve series hidden by the filter
        if (treeFilterManager.isFiltering() && isAdditiveSelectionEvent()) {
            Set<SeriesRef> visibleRefs = getVisibleSeriesKeys();
            for (SeriesRef ref : currentTabSeries) {
                if (!visibleRefs.contains(ref)) {
                    newSelectedSeries.add(ref);
                }
            }
        }

        // Check if there's overlap between old and new selections for zoom decision
        boolean hasOverlap = currentTabSeries.stream().anyMatch(newSelectedSeries::contains);
        final boolean shouldResetZoom = currentTabSeries.isEmpty() || !hasOverlap;

        // Determine which series need data fetched (not yet in the pool)
        Set<SeriesRef> seriesToFetch = new HashSet<>(newSelectedSeries);
        seriesToFetch.removeIf(ref -> plotDataSet.getSeries(ref) != null);

        // Capture the target PlotPanel for async callbacks
        final PlotPanel targetPanel = tabManager.getTargetPlotPanel();

        // Group new run series needing fetch by data source. Dataset series take their own
        // path below.
        Map<String, List<SeriesRef>> dataSourceToRefs = new LinkedHashMap<>();
        Set<SeriesRef> datasetRefs = new HashSet<>();

        for (SeriesRef ref : seriesToFetch) {
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
        for (SeriesRef ref : newSelectedSeries) {
            if (!currentTabSeries.contains(ref)) {
                seriesSlotManager.assignSlot(ref);
            }
        }

        // Fetch run series data into the pool
        for (Map.Entry<String, List<SeriesRef>> entry : dataSourceToRefs.entrySet()) {
            String dataSourceKey = entry.getKey();
            List<SeriesRef> refs = entry.getValue();

            String[] parts = dataSourceKey.split("\\|", 2);
            String sessionKey = parts[0];
            String seriesName = parts[1];

            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, seriesName);
            if (cachedData != null) {
                for (SeriesRef ref : refs) {
                    window.addSeriesToPool(ref, cachedData);
                    tabManager.updateSeriesInStatsTabsWithAggregation(ref, cachedData);
                }
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                for (SeriesRef ref : refs) {
                    tabManager.addLoadingSeriesInStatsTabs(ref);
                }

                final List<SeriesRef> capturedRefs = new ArrayList<>(refs);
                final Set<SeriesRef> capturedNewSelection = new LinkedHashSet<>(newSelectedSeries);
                // Captured to detect whether "Last" has changed since this request was issued.
                // Only applied to LastSeries refs; RunSeries / DatasetSeries refs are tied to
                // their own immutable identity and are not generation-dependent.
                final long capturedGeneration = lastRunGeneration.getAsLong();

                timeSeriesRequestManager.requestTimeSeries(sessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            final boolean lastIsStale = capturedGeneration != lastRunGeneration.getAsLong();
                            for (SeriesRef capturedRef : capturedRefs) {
                                // Drop LastSeries writes if Last has changed since this
                                // request was issued — refreshLastSeries() for the new Last
                                // will fetch the correct data.
                                if (lastIsStale && capturedRef instanceof LastSeries) {
                                    continue;
                                }
                                // Check if series is still selected on the target tab
                                if (capturedNewSelection.contains(capturedRef)) {
                                    window.addSeriesToPool(capturedRef, timeSeriesData);
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
                            for (SeriesRef capturedRef : capturedRefs) {
                                tabManager.addErrorSeriesInStatsTabs(capturedRef, throwable.getMessage());
                            }
                        });
                        return null;
                    });
            } else {
                for (SeriesRef ref : refs) {
                    tabManager.addLoadingSeriesInStatsTabs(ref);
                }
            }
        }

        // Fetch dataset series into the pool. The dataset cache is keyed by the
        // DatasetSeries ref (absolutePath + baseName); we already have that ref.
        for (SeriesRef ref : datasetRefs) {
            if (!(ref instanceof DatasetSeries datasetRef)) continue;

            TimeSeriesData cachedData = datasetSeriesCache.get(datasetRef);
            if (cachedData != null) {
                window.addSeriesToPool(ref, cachedData);
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
     * Constructs the {@link SeriesRef} that identifies the data behind a
     * {@link OutputsTreeBuilder.SeriesLeafNode}. See {@code RunManager#seriesRefForLeaf}.
     */
    private SeriesRef seriesRefForLeaf(OutputsTreeBuilder.SeriesLeafNode leaf) {
        return leaf.ref;
    }

    /**
     * Resolves a RunInfo to its actual session.
     * If the run is "Last", returns the session of the actual last completed run.
     */
    private SessionManager.KalixSession resolveRunInfoSession(RunInfoImpl runInfo) {
        RunInfoImpl lastRunInfo = lastRunInfoSupplier.get();
        if (runInfo.isLastAlias() && lastRunInfo != null) {
            return lastRunInfo.getSession();
        }
        return runInfo.getSession();
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
    private Set<SeriesRef> getVisibleSeriesKeys() {
        Set<SeriesRef> refs = new HashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) timeseriesTreeModel.getRoot();
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
        collectAllLeafNodesRecursive(root, allLeaves);
        for (OutputsTreeBuilder.SeriesLeafNode leaf : allLeaves) {
            SeriesRef ref = seriesRefForLeaf(leaf);
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
}
