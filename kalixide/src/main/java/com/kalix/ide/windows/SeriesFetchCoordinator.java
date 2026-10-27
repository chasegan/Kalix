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
import com.kalix.ide.io.PixieStore;
import com.kalix.ide.managers.DatasetLoaderManager;
import com.kalix.ide.managers.DatasetSeriesSource;
import com.kalix.ide.managers.OutputsTreeBuilder;
import com.kalix.ide.managers.TimeSeriesRequestManager;

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
    private final OutputsTreeBuilder outputsTreeBuilder;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final SeriesSlotManager seriesSlotManager;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources;

    /**
     * Pixie series whose decode has been requested but has not yet landed in the pool
     * (or failed), by point count. Read and written on the EDT only. The memory gate
     * counts these with the pool: a tick that follows another before its decodes
     * complete must not pass because the pool does not show them yet.
     */
    private final Map<DatasetSeries, Integer> pendingPixiePoints = new HashMap<>();
    /** Pixie points of derived series being created, counted until each creation ends. */
    private long reservedDerivedPixiePoints;
    /**
     * Defers to {@link LastRunTracker#getGeneration()}. Captured at fetch-issue time so
     * async responses for "[Last]" series can be dropped when a newer run has become Last.
     */
    private final LongSupplier lastRunGeneration;
    /** Defers to {@link LastRunTracker#getLastRunInfo()} for resolving the Last alias. */
    private final Supplier<RunInfoImpl> lastRunInfoSupplier;
    /** Point counts of derived series made from Pixie data, which count towards the budget. */
    private final Supplier<Map<SeriesRef, Integer>> pixieDerivedPoints;

    // Depth of nested programmatic tree-update sections. Listeners stay suppressed while
    // any section is open. A counter rather than a boolean so that nesting is safe.
    private int programmaticUpdateDepth = 0;

    SeriesFetchCoordinator(RunManager window,
                           JCheckboxTree timeseriesTree,
                           DefaultTreeModel timeseriesTreeModel,
                           OutputsTreeBuilder outputsTreeBuilder,
                           VisualizationTabManager tabManager,
                           DataSet plotDataSet,
                           SeriesSlotManager seriesSlotManager,
                           TimeSeriesRequestManager timeSeriesRequestManager,
                           Map<DatasetSeries, DatasetSeriesSource> datasetSeriesSources,
                           LongSupplier lastRunGeneration,
                           Supplier<RunInfoImpl> lastRunInfoSupplier,
                           Supplier<Map<SeriesRef, Integer>> pixieDerivedPoints) {
        this.window = window;
        this.timeseriesTree = timeseriesTree;
        this.timeseriesTreeModel = timeseriesTreeModel;
        this.outputsTreeBuilder = outputsTreeBuilder;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.seriesSlotManager = seriesSlotManager;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.datasetSeriesSources = datasetSeriesSources;
        this.lastRunGeneration = lastRunGeneration;
        this.lastRunInfoSupplier = lastRunInfoSupplier;
        this.pixieDerivedPoints = pixieDerivedPoints;
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

        if (checkedPaths.length == 0 && !outputsTreeBuilder.isFiltered()) {
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
        if (allLeaves.isEmpty() && !outputsTreeBuilder.isFiltered()) {
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
        if (outputsTreeBuilder.isFiltered()) {
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

        // Capture the target FlowVizPanel for async callbacks
        final FlowVizPanel targetPanel = tabManager.getTargetVizPanel();

        fetchIntoPool(newSelectedSeries, refToLeaf, targetPanel, shouldResetZoom, true);

        // Also assign palette slots for series new to this tab but already in pool
        for (SeriesRef ref : newSelectedSeries) {
            if (!currentTabSeries.contains(ref)) {
                seriesSlotManager.assignSlot(ref);
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
     * Fetches into the pool every series in {@code wanted} that it lacks: run series from
     * the request cache or the session, derived series from their records, dataset series
     * from their sources (Pixie through the memory gate, whose refusal is reported in a
     * dialog when {@code announceRefusal}, else only in the stats rows). {@code refToLeaf}
     * names each series' tree leaf, which says where it comes from; a series without one
     * is skipped. Data arriving later refreshes {@code targetPanel}'s tab, zooming to fit
     * when {@code shouldResetZoom}.
     */
    private void fetchIntoPool(Set<SeriesRef> wanted, Map<SeriesRef, OutputsTreeBuilder.SeriesLeafNode> refToLeaf,
                               FlowVizPanel targetPanel, boolean shouldResetZoom, boolean announceRefusal) {
        // Determine which series need data fetched (not yet in the pool)
        Set<SeriesRef> seriesToFetch = new HashSet<>(wanted);
        seriesToFetch.removeIf(ref -> plotDataSet.getSeries(ref) != null);

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
            } else if (leaf.source instanceof DerivedSeriesInfo derived) {
                // Held by the controller, as loaded datasets are by datasetSeriesSources.
                TimeSeriesData values = derived.values();
                if (values != null) {
                    window.publishSeries(ref, values);
                } else {
                    tabManager.addErrorSeriesInStatsTabs(ref, derived.unavailableReason());
                }
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
                    window.publishSeries(ref, cachedData);
                }
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                for (SeriesRef ref : refs) {
                    tabManager.addLoadingSeriesInStatsTabs(ref);
                }

                final List<SeriesRef> capturedRefs = new ArrayList<>(refs);
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
                                // Unticked meanwhile: the pool keeps only what some tab selects.
                                if (tabManager.isSeriesSelectedOnAnyTab(capturedRef)) {
                                    window.publishSeries(capturedRef, timeSeriesData);
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
                                // Unticked meanwhile: no tab shows its row, so nothing to record.
                                if (tabManager.isSeriesSelectedOnAnyTab(capturedRef)) {
                                    tabManager.addErrorSeriesInStatsTabs(capturedRef, throwable.getMessage());
                                }
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

        // The plot pool holds strongly every series some tab selects, and PixieStore keeps
        // decoded series only softly, so the pool is what holds Pixie data in memory.
        // Ticking a parent over a large Pixie file (e.g. #430's 181 x 3.65M points) would
        // decode it a series at a time. Judge the Pixie series already in the pool plus
        // the newly wanted ones by the same estimate and budget as the whole-file views,
        // and fetch none of the new ones if that is over.
        String pixieRefusal = pixieSelectionRefusal(wanted);
        boolean pixieRefused = false;

        // Fetch dataset series into the pool. The sources map is keyed by the
        // DatasetSeries ref (absolutePath + baseName); we already have that ref.
        for (SeriesRef ref : datasetRefs) {
            if (!(ref instanceof DatasetSeries datasetRef)) continue;

            DatasetSeriesSource source = datasetSeriesSources.get(datasetRef);
            switch (source) {
                case DatasetSeriesSource.Loaded loaded -> {
                    window.publishSeries(ref, loaded.data());
                }
                case DatasetSeriesSource.Pixie pixie -> {
                    if (pixieRefusal != null) {
                        tabManager.addErrorSeriesInStatsTabs(ref, "Not loaded: too much Pixie data loaded");
                        pixieRefused = true;
                    } else {
                        fetchPixieSeries(datasetRef, pixie, targetPanel, shouldResetZoom);
                    }
                }
                case null -> {
                    logger.warn("Dataset series not found: {}", datasetRef);
                    tabManager.addErrorSeriesInStatsTabs(ref, "Series not found");
                }
            }
        }

        if (pixieRefused && announceRefusal) {
            // After this listener returns, so a modal dialog never runs inside the tree event.
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(window, pixieRefusal,
                "Too Much Pixie Data Loaded", JOptionPane.WARNING_MESSAGE));
        }
    }

    /**
     * Fetches what the target tab selects but the pool lacks, as a restored selection
     * (undo, redo) may: it comes back without a tick, so nothing else fetches it. Leaves
     * come from the whole outputs tree; a series whose source is gone has none and is
     * skipped. The tab keeps its view while the data returns.
     */
    void fetchMissingForTargetTab() {
        Set<SeriesRef> wanted = new LinkedHashSet<>(tabManager.getTargetTabSelectedSeries());
        wanted.removeIf(ref -> plotDataSet.getSeries(ref) != null);
        if (wanted.isEmpty()) {
            return;
        }
        Map<SeriesRef, OutputsTreeBuilder.SeriesLeafNode> refToLeaf = new HashMap<>();
        List<OutputsTreeBuilder.SeriesLeafNode> leaves = new ArrayList<>();
        collectAllLeafNodesRecursive((DefaultMutableTreeNode) timeseriesTreeModel.getRoot(), leaves);
        for (OutputsTreeBuilder.SeriesLeafNode leaf : leaves) {
            SeriesRef ref = seriesRefForLeaf(leaf);
            if (ref != null && wanted.contains(ref)) {
                refToLeaf.put(ref, leaf);
            }
        }
        wanted.retainAll(refToLeaf.keySet());
        if (!wanted.isEmpty()) {
            // No dialog: a refused tick would otherwise re-announce itself on every tab switch.
            fetchIntoPool(wanted, refToLeaf, tabManager.getTargetVizPanel(), false, false);
        }
    }

    /**
     * Why the newly ticked Pixie series in {@code selection} must not be fetched, or
     * {@code null} if they fit. Counts the Pixie data already held ({@link #pixieInUse})
     * and each ticked one not yet among it (by its index row), and compares their estimated
     * size ({@link PixieStore#estimateWholeLoadBytes}) with
     * {@link PixieStore#defaultWholeLoadBudget}. A ticked key whose file has changed
     * is left out; its fetch fails as stale anyway.
     */
    private String pixieSelectionRefusal(Set<SeriesRef> selection) {
        PixieStore store = PixieStore.shared();
        PixieTally tally = pixieInUse();
        for (SeriesRef ref : selection) {
            if (tally.counted.contains(ref) || !(ref instanceof DatasetSeries datasetRef)
                    || !(datasetSeriesSources.get(datasetRef) instanceof DatasetSeriesSource.Pixie pixie)) {
                continue;
            }
            try {
                tally.add(ref, store.info(pixie.key()).pointCount);
            } catch (IllegalArgumentException stale) {
                // Not countable; fetchPixieSeries reports it.
            }
        }
        long estimate = tally.estimate();
        long budget = PixieStore.defaultWholeLoadBudget();
        if (estimate <= budget) {
            return null;
        }
        return String.format(
            "The Pixie series loaded, loading or ticked in the Run Manager (%,d) would need"
                + " about %s in memory, more than the %s it allows (half the IDE's memory).%n%n"
                + "The newly ticked series were not loaded. Untick series you no longer need,"
                + " in every plot tab, to free their memory, then tick fewer series.",
            tally.series, PixieStore.formatBytes(estimate), PixieStore.formatBytes(budget));
    }

    /**
     * Why creating derived series from Pixie data must not go ahead, or {@code null} if it fits:
     * {@code newPoints} for the new derived series, plus one input of {@code longestInput}
     * points decoded at a time, on top of the Pixie data already held.
     */
    /**
     * Counts {@code points} of Pixie data towards the budget while a derived series is being
     * created, so a second creation or a tick that overlaps it sees them; released by
     * {@link #releaseDerivedPixiePoints} when the creation ends either way.
     */
    void reserveDerivedPixiePoints(long points) {
        reservedDerivedPixiePoints += points;
    }

    void releaseDerivedPixiePoints(long points) {
        reservedDerivedPixiePoints -= points;
    }

    String pixieDerivedRefusal(long newPoints, int longestInput) {
        PixieTally tally = pixieInUse();
        tally.points += newPoints + longestInput;
        tally.longest = Math.max(tally.longest, longestInput);
        long estimate = tally.estimate();
        long budget = PixieStore.defaultWholeLoadBudget();
        if (estimate <= budget) {
            return null;
        }
        return String.format(
            "Summing these Pixie series would bring the Pixie data held by the Run Manager to"
                + " about %s, more than the %s it allows (half the IDE's memory).%n%n"
                + "Nothing was created. Remove a Pixie dataset, or a derived series made from one,"
                + " to free memory.",
            PixieStore.formatBytes(estimate), PixieStore.formatBytes(budget));
    }

    /**
     * The Pixie data held now: every Pixie series in the pool (by its decoded point count,
     * so one from a since-changed file still counts), every one still being decoded for an
     * earlier tick ({@link #pendingPixiePoints}), every derived series made from Pixie data,
     * and those still being created from it.
     */
    private PixieTally pixieInUse() {
        PixieTally tally = new PixieTally();
        for (SeriesRef ref : plotDataSet.getSeriesRefs()) {
            TimeSeriesData data = plotDataSet.getSeries(ref);
            if (data != null && isPixieSeries(ref)) {
                tally.add(ref, data.getPointCount());
            }
        }
        for (Map.Entry<DatasetSeries, Integer> pending : pendingPixiePoints.entrySet()) {
            tally.add(pending.getKey(), pending.getValue());
        }
        pixieDerivedPoints.get().forEach(tally::add);
        tally.points += reservedDerivedPixiePoints;
        return tally;
    }

    /** Pixie series counted towards the memory budget, each once. */
    private static final class PixieTally {
        final Set<SeriesRef> counted = new HashSet<>();
        int series;
        long points;
        long longest;

        void add(SeriesRef ref, int pointCount) {
            if (counted.add(ref)) {
                series++;
                points += pointCount;
                longest = Math.max(longest, pointCount);
            }
        }

        long estimate() {
            return PixieStore.estimateWholeLoadBytes(points, longest);
        }
    }

    /** Whether {@code ref} is a loaded dataset series backed by a Pixie file. */
    private boolean isPixieSeries(SeriesRef ref) {
        if (!(ref instanceof DatasetSeries datasetRef)) {
            return false;
        }
        // Exhaustive, so a new kind of dataset source must decide whether it counts
        // towards the Pixie memory budget.
        return switch (datasetSeriesSources.get(datasetRef)) {
            case DatasetSeriesSource.Pixie ignored -> true;
            case DatasetSeriesSource.Loaded ignored -> false;
            case null -> false;
        };
    }

    /**
     * Decodes a Pixie dataset series from {@link PixieStore} into the pool, following the
     * run-fetch pattern: a loading row in the stats tabs, then the pool and target tab
     * update on the EDT, or an error row if the decode fails (e.g. the file changed on
     * disk since it was loaded).
     *
     * <p>The result is dropped if the dataset was removed while the decode ran, so a
     * removed file's series never reappear in the pool.
     *
     * <p>Until the decode settles here on the EDT the series is counted as pending by
     * {@link #pixieSelectionRefusal}, so a tick issued meanwhile sees it.
     */
    private void fetchPixieSeries(DatasetSeries ref, DatasetSeriesSource.Pixie source,
                                  FlowVizPanel targetPanel, boolean shouldResetZoom) {
        tabManager.addLoadingSeriesInStatsTabs(ref);
        PixieStore store = PixieStore.shared();
        try {
            pendingPixiePoints.put(ref, store.info(source.key()).pointCount);
        } catch (IllegalArgumentException stale) {
            // Not countable; the fetch below fails and reports it.
        }
        store.get(source.key()).whenComplete((data, failure) ->
            SwingUtilities.invokeLater(() -> {
                pendingPixiePoints.remove(ref);
                if (!source.equals(datasetSeriesSources.get(ref))) {
                    return; // dataset removed (or reloaded) meanwhile
                }
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
                if (cause != null) {
                    logger.warn("Failed to decode Pixie series {}", ref, cause);
                }
                if (!tabManager.isSeriesSelectedOnAnyTab(ref)) {
                    return; // unticked while decoding: neither its data nor its failure is kept
                }
                if (cause != null) {
                    tabManager.addErrorSeriesInStatsTabs(ref, cause.getMessage());
                    return;
                }
                // A tab switch fetches a series still decoding again; the first to settle publishes.
                if (plotDataSet.getSeries(ref) != data) {
                    window.publishSeries(ref, data);
                }
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
