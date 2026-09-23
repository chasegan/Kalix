package com.kalix.ide.windows;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.FlowVizPanel;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.LastSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.io.PixieReader;
import com.kalix.ide.io.PixieStore;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.DatasetSeriesSource;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.managers.TreeFilterManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Orchestrates timeseries-tree selection changes for {@link RunManager}: diffing the
 * new selection against the target tab, probing caches, issuing async fetches, writing
 * results into the shared pool, and assigning palette slots.
 *
 * <p>Also owns the programmatic-update guard used across the window to prevent
 * listener feedback loops during programmatic tree updates: collaborators bracket their
 * tree mutations with {@link #beginProgrammaticUpdate()} / {@link #endProgrammaticUpdate()}
 * (always in try/finally), and both tree listeners consult {@link #isProgrammaticUpdate()}
 * before reacting. The guard is a depth counter, so nested programmatic sections are
 * safe: an inner end cannot prematurely unblock the outer section.</p>
 */
class SeriesFetchCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SeriesFetchCoordinator.class);

    private final RunManager window;
    private final JCheckboxTree timeseriesTree;
    private final DefaultTreeModel timeseriesTreeModel;
    private final TreeFilterManager treeFilterManager;
    private final OutputsTreeBuilder outputsTreeBuilder;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final SeriesSlotManager seriesSlotManager;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources;
    /**
     * Defers to {@link LastRunTracker#getGeneration()}. Captured at fetch-issue time so
     * async responses for "[Last]" series can be dropped when a newer run has become Last.
     */
    private final LongSupplier lastRunGeneration;
    /** Defers to {@link LastRunTracker#getLastRunInfo()} for resolving the Last alias. */
    private final Supplier<RunInfoImpl> lastRunInfoSupplier;

    // Depth of nested programmatic tree-update sections. Listeners stay suppressed while
    // any section is open. A counter rather than a boolean so that nesting is safe.
    private int programmaticUpdateDepth = 0;

    SeriesFetchCoordinator(RunManager window,
                           JCheckboxTree timeseriesTree,
                           DefaultTreeModel timeseriesTreeModel,
                           TreeFilterManager treeFilterManager,
                           OutputsTreeBuilder outputsTreeBuilder,
                           VisualizationTabManager tabManager,
                           DataSet plotDataSet,
                           SeriesSlotManager seriesSlotManager,
                           TimeSeriesRequestManager timeSeriesRequestManager,
                           Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources,
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
        this.datasetSeriesSources = datasetSeriesSources;
        this.lastRunGeneration = lastRunGeneration;
        this.lastRunInfoSupplier = lastRunInfoSupplier;
    }

    /** Returns whether a programmatic tree update is in progress. */
    boolean isProgrammaticUpdate() {
        return programmaticUpdateDepth > 0;
    }

    /** Opens a programmatic-update section. Pair with {@link #endProgrammaticUpdate()} in a finally block. */
    void beginProgrammaticUpdate() {
        programmaticUpdateDepth++;
    }

    /** Closes a programmatic-update section opened by {@link #beginProgrammaticUpdate()}. */
    void endProgrammaticUpdate() {
        if (programmaticUpdateDepth <= 0) {
            logger.warn("endProgrammaticUpdate() without matching begin — guard call sites are unbalanced");
            return;
        }
        programmaticUpdateDepth--;
    }

    /**
     * Handles checked-state changes in the timeseries tree.
     * Supports recursive checking: checking a parent node plots all its leaf children.
     * Fetches timeseries data for leaf nodes and updates plot and stats.
     */
    void onOutputsTreeCheckedChanged() {
        // Ignore checked-state changes during programmatic updates
        if (isProgrammaticUpdate()) {
            return;
        }

        TreePath[] checkedPaths = timeseriesTree.getCheckedPaths();

        if (checkedPaths.length == 0 && !treeFilterManager.isFiltering()) {
            // Clear the target tab's series when nothing is checked
            tabManager.setTargetTabSelectedSeries(new LinkedHashSet<>());
            return;
        }

        // Collect all leaf nodes recursively (parent checked = all children)
        List<OutputsTreeBuilder.SeriesLeafNode> allLeaves = new ArrayList<>();
        for (TreePath path : checkedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            collectLeafNodes(node, allLeaves);
        }

        // If no valid leaves found, clear the target tab
        if (allLeaves.isEmpty() && !treeFilterManager.isFiltering()) {
            tabManager.setTargetTabSelectedSeries(new LinkedHashSet<>());
            return;
        }

        // Build new set of checked series, ref-keyed directly from the leaves
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

        // Preserve series hidden by filter.
        if (treeFilterManager.isFiltering()) {
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

        // Capture the target FlowVizPanel for async callbacks
        final FlowVizPanel targetPanel = tabManager.getTargetVizPanel();

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

        // The plot pool holds every series it has fetched strongly, for every tab, and
        // does not shrink on deselect (undo, tab switches and duplication re-select from
        // it without fetching). So ticking a parent over a large Pixie file (e.g. #430's
        // 181 x 3.65M points) - or ticking different series in turn, or across tabs -
        // would decode the file into memory a series at a time. Judge the Pixie series
        // already in the pool plus the newly ticked ones by the same estimate and budget
        // as the whole-file views, and fetch none of the new ones if that is over.
        String pixieRefusal = pixieSelectionRefusal(newSelectedSeries);
        boolean pixieRefused = false;

        // Fetch dataset series into the pool. The sources map is keyed by the
        // DatasetSeries ref (absolutePath + baseName); we already have that ref.
        for (SeriesRef ref : datasetRefs) {
            if (!(ref instanceof DatasetSeries datasetRef)) continue;

            DatasetSeriesSource source = datasetSeriesSources.get(datasetRef);
            if (source instanceof DatasetSeriesSource.Loaded loaded) {
                window.addSeriesToPool(ref, loaded.data());
                tabManager.updateSeriesInStatsTabsWithAggregation(ref, loaded.data());
            } else if (source instanceof DatasetSeriesSource.Pixie pixie) {
                if (pixieRefusal != null) {
                    tabManager.addErrorSeriesInStatsTabs(ref, "Not loaded: too much Pixie data loaded");
                    pixieRefused = true;
                } else {
                    fetchPixieSeries(datasetRef, pixie, targetPanel, shouldResetZoom);
                }
            } else {
                logger.warn("Dataset series not found: {}", datasetRef);
                tabManager.addErrorSeriesInStatsTabs(ref, "Series not found");
            }
        }

        if (pixieRefused) {
            // After this listener returns, so a modal dialog never runs inside the tree event.
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(window, pixieRefusal,
                "Too Much Pixie Data Loaded", JOptionPane.WARNING_MESSAGE));
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
     * Why the newly ticked Pixie series in {@code selection} must not be fetched, or
     * {@code null} if they fit. Counts every Pixie series already in the pool (by its
     * decoded point count, so one from a since-changed file still counts) plus each
     * ticked one not yet there (by its index row), and compares their estimated size
     * ({@link PixieStore#estimateWholeLoadBytes}) with
     * {@link PixieStore#defaultWholeLoadBudget}. A ticked key whose file has changed
     * is left out; its fetch fails as stale anyway.
     */
    private String pixieSelectionRefusal(Set<SeriesRef> selection) {
        PixieStore store = PixieStore.shared();
        List<PixieReader.SeriesInfo> infos = new ArrayList<>();
        Set<SeriesRef> counted = new HashSet<>();
        for (SeriesRef ref : plotDataSet.getSeriesRefs()) {
            TimeSeriesData data = plotDataSet.getSeries(ref);
            if (data != null && isPixieSeries(ref) && counted.add(ref)) {
                PixieReader.SeriesInfo pooled = new PixieReader.SeriesInfo();
                pooled.pointCount = data.getPointCount();
                infos.add(pooled);
            }
        }
        for (SeriesRef ref : selection) {
            if (counted.contains(ref) || !(ref instanceof DatasetSeries datasetRef)
                    || !(datasetSeriesSources.get(datasetRef) instanceof DatasetSeriesSource.Pixie pixie)) {
                continue;
            }
            try {
                infos.add(store.info(pixie.key()));
                counted.add(ref);
            } catch (IllegalArgumentException stale) {
                // Not countable; fetchPixieSeries reports it.
            }
        }
        long estimate = PixieStore.estimateWholeLoadBytes(infos);
        long budget = PixieStore.defaultWholeLoadBudget();
        if (estimate <= budget) {
            return null;
        }
        return String.format(
            "The Pixie series loaded or ticked in the Run Manager (%,d) would need about %s"
                + " in memory, more than the %s it allows (half the IDE's memory).%n%n"
                + "The newly ticked series were not loaded. Series stay loaded, even when"
                + " unticked, until their dataset is removed: remove and re-add a dataset to"
                + " free its memory, then tick fewer series.",
            infos.size(), PixieStore.formatBytes(estimate), PixieStore.formatBytes(budget));
    }

    /** Whether {@code ref} is a loaded dataset series backed by a Pixie file. */
    private boolean isPixieSeries(SeriesRef ref) {
        return ref instanceof DatasetSeries datasetRef
            && datasetSeriesSources.get(datasetRef) instanceof DatasetSeriesSource.Pixie;
    }

    /**
     * Decodes a Pixie dataset series from {@link PixieStore} into the pool, following the
     * run-fetch pattern: a loading row in the stats tabs, then the pool and target tab
     * update on the EDT, or an error row if the decode fails (e.g. the file changed on
     * disk since it was loaded).
     *
     * <p>The result is dropped if the dataset was removed while the decode ran, so a
     * removed file's series never reappear in the pool.
     */
    private void fetchPixieSeries(DatasetSeries ref, DatasetSeriesSource.Pixie source,
                                  FlowVizPanel targetPanel, boolean shouldResetZoom) {
        tabManager.addLoadingSeriesInStatsTabs(ref);
        PixieStore.shared().get(source.key()).whenComplete((data, failure) ->
            SwingUtilities.invokeLater(() -> {
                if (!source.equals(datasetSeriesSources.get(ref))) {
                    return; // dataset removed (or reloaded) meanwhile
                }
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                    logger.warn("Failed to decode Pixie series {}", ref, cause);
                    tabManager.addErrorSeriesInStatsTabs(ref, cause.getMessage());
                    return;
                }
                window.addSeriesToPool(ref, data);
                tabManager.updateSeriesInStatsTabsWithAggregation(ref, data);
                if (targetPanel != null) {
                    tabManager.updateTab(targetPanel, shouldResetZoom);
                }
            }));
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
