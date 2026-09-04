package com.kalix.ide.windows;

import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.data.LastSeries;
import com.kalix.ide.flowviz.data.LastSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.managers.TimeSeriesRequestManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.List;
import java.util.Set;

/**
 * Tracks the "Last run" alias for {@link RunManager}: which run most recently
 * completed, the generation counter that guards async "[Last]" fetches, and the
 * child node under the "Last run" tree category.
 *
 * <h2>"Last" Run Handling</h2>
 * When a run completes ({@link #onRunCompleted}):
 * <ol>
 *   <li>Cache is cleared for the session ({@link TimeSeriesRequestManager#clearCacheForSession})</li>
 *   <li>If "Last" was checked, timeseries tree is rebuilt to show new outputs</li>
 *   <li>Plotted "[Last]" series are refreshed with new data ({@link #refreshLastSeries})</li>
 * </ol>
 *
 * <p>{@link RunTreeController} drives this class: it calls {@link #onRunCompleted}
 * when it detects a completion; when the session that IS Last leaves the tree it
 * calls {@link #rebindLast} with the most recently completed survivor (Last is a
 * standing subscription to "whichever run is newest", so removal self-heals), or
 * {@link #clearLast} when no completed run survives; and {@link #onRunRenamed}
 * when a run's display name changes.</p>
 */
class LastRunTracker {

    private static final Logger logger = LoggerFactory.getLogger(LastRunTracker.class);

    private final RunManager window;
    private final JCheckboxTree timeseriesSourceTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode lastRunNode;
    private final VisualizationTabManager tabManager;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final SeriesFetchCoordinator fetchCoordinator;

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
    private long lastRunCompletionTime = 0L;

    LastRunTracker(RunManager window,
                   JCheckboxTree timeseriesSourceTree,
                   DefaultTreeModel treeModel,
                   DefaultMutableTreeNode lastRunNode,
                   VisualizationTabManager tabManager,
                   TimeSeriesRequestManager timeSeriesRequestManager,
                   SeriesFetchCoordinator fetchCoordinator) {
        this.window = window;
        this.timeseriesSourceTree = timeseriesSourceTree;
        this.treeModel = treeModel;
        this.lastRunNode = lastRunNode;
        this.tabManager = tabManager;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.fetchCoordinator = fetchCoordinator;
    }

    /** The most recently completed run, or {@code null} if none. */
    RunInfoImpl getLastRunInfo() {
        return lastRunInfo;
    }

    /** Current "Last" generation; async "[Last]" fetches capture this at issue time. */
    long getGeneration() {
        return lastRunGeneration;
    }

    /**
     * Listener called by {@link RunTreeController} when a run completes. Promotes the
     * run to "Last" only if its completion is more recent than the current Last run's.
     */
    void onRunCompleted(RunInfoImpl runInfo, long completionTime) {
        if (completionTime > lastRunCompletionTime) {
            updateLastRun(runInfo, completionTime);
        }
    }

    /**
     * Keeps {@code lastRunInfo} pointing at the live {@link RunInfoImpl} for a session
     * across a rename — otherwise it would dangle to the now-orphan instance. The Last
     * child node's wrapper (with name "Last") is untouched; {@link #refreshLastSeries}
     * resolves Last via session.
     */
    void onRunRenamed(RunInfoImpl oldRunInfo, RunInfoImpl newRunInfo) {
        if (lastRunInfo == oldRunInfo) {
            lastRunInfo = newRunInfo;
        }
    }

    /** Whether {@code sessionKey} is the session the Last alias currently points at. */
    boolean isLastSession(String sessionKey) {
        return lastRunInfo != null && lastRunInfo.getSession().getSessionKey().equals(sessionKey);
    }

    /**
     * Re-points Last at {@code runnerUp} after the run it referenced was removed —
     * the self-heal half of the "Last is whichever run is newest" contract. Goes
     * straight to {@link #updateLastRun} (NOT {@link #onRunCompleted}, whose recency
     * guard correctly rejects promotions backwards in time — which is exactly what
     * healing is): the guarded replace-existing-child path swaps the node, honours
     * the tab's recorded LastSource, rebuilds the outputs tree, reconciles, and
     * {@code refreshLastSeries} silently re-points every plotted [Last] series.
     */
    void rebindLast(RunInfoImpl runnerUp, long completionTime) {
        updateLastRun(runnerUp, completionTime);
    }

    /**
     * Clears the Last state when the removed session was Last and no completed run
     * survives, restoring every tab to the default tab's birth state: LastSeries
     * selections and LastSource are deliberately KEPT everywhere (Last identity is
     * generational, not run-bound — the next completion re-lights them via
     * {@link #updateLastRun}'s recorded-context contract), the Last node leaves the
     * tree, and stale renders are dropped uniformly.
     *
     * <p>The tree surgery is guarded: {@code removePath} on a checked node fires the
     * check-change listener, and unguarded that ran the full user-tick path against
     * whichever tab was active — stripping its [Last] selection and sources. That
     * was the root cause of the one-tab-loses-its-lines asymmetry.</p>
     */
    void clearLast() {
        if (lastRunInfo == null) {
            return;
        }
        lastRunInfo = null;
        lastRunCompletionTime = 0L;
        // In-flight [Last] fetches must not repopulate the pool after removal: with
        // the resolver now null they would store under a raw LastSeries key. The
        // generation guard in refreshLastSeries' callbacks drops them.
        lastRunGeneration++;

        fetchCoordinator.beginProgrammaticUpdate();
        try {
            if (lastRunChildNode != null) {
                int childIndex = lastRunNode.getIndex(lastRunChildNode);
                Object[] removedChild = new Object[]{lastRunChildNode};
                timeseriesSourceTree.removePath(new TreePath(lastRunChildNode.getPath()));
                lastRunNode.removeAllChildren();
                treeModel.nodesWereRemoved(lastRunNode, new int[]{childIndex}, removedChild);
                lastRunChildNode = null;
            }
            // Read-only reprojection, mirroring onTabChanged: no snapshot, no
            // reconcile, no history push.
            window.updateOutputsTree();
            window.restoreTreeChecksForSeries(tabManager.getTargetTabSelectedSeries());
        } finally {
            fetchCoordinator.endProgrammaticUpdate();
        }

        // The pool data died with the run (stored under RunSeries keys via the
        // resolver); drop the stale rendered lines on every tab, not just whichever
        // one next happens to rebuild.
        tabManager.updateAllTabs(false);
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
     *       <li>Removes stale series from plot via {@link RunManager#reconcileCheckedSeriesWithTree}</li>
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
        TreePath oldLastPath = null;
        boolean wasLastChecked = false;

        if (lastRunChildNode != null) {
            oldChildIndex = lastRunNode.getIndex(lastRunChildNode);
            oldChildNode = lastRunChildNode;
            oldLastPath = new TreePath(lastRunChildNode.getPath());
            wasLastChecked = timeseriesSourceTree.isPathChecked(oldLastPath);
        }

        // The active tab may record "Last run" as a checked source without the tree
        // showing it checked — that is exactly the state a default tab is in before the
        // first run of the session, when there is no Last node to check yet. Honour the
        // tab's recorded context so Last lights up the moment it exists.
        boolean checkLast = wasLastChecked
            || tabManager.getTargetTabCheckedSources().contains(new LastSource());

        // If Last is to be checked, block all checked-state events during the entire update
        if (checkLast) {
            fetchCoordinator.beginProgrammaticUpdate();
        }
        try {
            // Create new child node. The Last subtree's wrapper carries the structural
            // "is last alias" marker via RunInfoImpl.lastAlias — seriesRefForLeaf consults
            // that marker (not the runName string) to mint LastSeries refs.
            lastRunChildNode = new DefaultMutableTreeNode(
                RunInfoImpl.lastAlias(newLastRun.getSession())
            );

            if (oldChildNode != null) {
                // Old node is leaving the tree - forget its checked-state entry entirely (not
                // just uncheck it) so it doesn't dangle in nodeCheckedStateMap, unreachable via
                // the tree, for the life of the app. Matches RunTreeController's run-removal and
                // RunManager's dataset-removal handling.
                timeseriesSourceTree.removePath(oldLastPath);

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

            // If Last is checked, rebuild the timeseries tree to show its outputs
            if (checkLast) {
                TreePath newLastPath = new TreePath(lastRunChildNode.getPath());

                // Check the new Last node so its outputs are picked up below
                timeseriesSourceTree.checkPath(newLastPath);

                // Rebuild the timeseries tree to include any new outputs from the new run
                // This is necessary because the new run may have different outputs than the old one
                window.updateOutputsTree();

                // Restore checked state for series that still exist in the new tree
                Set<SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
                Set<SeriesRef> restoredSeries = window.restoreTreeChecksForSeries(tabSeries);

                // Remove from tab any series that no longer exist (e.g., outputs that were removed)
                window.reconcileCheckedSeriesWithTree(restoredSeries, tabSeries);
            }
        } finally {
            if (checkLast) {
                fetchCoordinator.endProgrammaticUpdate();
            }
        }

        // Expand the Last run node to show the new child
        timeseriesSourceTree.expandPath(new TreePath(lastRunNode.getPath()));

        // Refresh any plotted "[Last]" series to use the new Last run's data
        refreshLastSeries();
    }

    /**
     * Fetches data for the new Last run into the pool, for every plotted "[Last]" series.
     *
     * Called by {@link #updateLastRun} when a run completes. This method:
     * <ol>
     *   <li>Clears the {@link TimeSeriesRequestManager} cache for this session -
     *       CRITICAL because cache is keyed by kalixcliUid which persists across runs</li>
     *   <li>For each {@link LastSeries} ref selected across all
     *       tabs, requests fresh data from the new run (sync if cached, async otherwise)
     *       and writes it into the shared pool.</li>
     * </ol>
     *
     * The cache clear happens unconditionally (even if no "[Last]" series are selected)
     * so that future selections will fetch fresh data.
     *
     * <h3>Why no stale-data handling is needed</h3>
     * The pool stores "Last" data under the underlying run's stable {@code RunSeries}
     * identity — the pool's {@code LastSeriesResolver} (installed by RunManager)
     * redirects every {@code LastSeries} access to {@code RunSeries(lastRunId, baseName)}.
     * So the pool never holds a {@code LastSeries} key that could go stale: when Last
     * changes, a {@code LastSeries} ref simply resolves to a different {@code RunSeries}.
     * This method just ensures that target is populated. The
     * {@code onOutputsTreeCheckedChanged} "already in pool" short-circuit is therefore
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
        Set<SeriesRef> allTabSeries = tabManager.getAllSelectedSeriesAcrossTabs();
        List<LastSeries> lastSeriesRefs = allTabSeries.stream()
            .filter(r -> r instanceof LastSeries)
            .map(r -> (LastSeries) r)
            .collect(java.util.stream.Collectors.toList());

        if (lastSeriesRefs.isEmpty()) {
            return;
        }

        boolean anySyncReplacement = false;

        for (LastSeries ref : lastSeriesRefs) {
            String seriesName = ref.baseName();

            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(newSessionKey, seriesName);
            if (cachedData != null) {
                // Synchronous replacement on EDT — atomic via DataSet.addSeries replacing
                // the existing entry for this ref.
                window.addSeriesToPool(ref, cachedData);
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
                            window.addSeriesToPool(ref, timeSeriesData);
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
}
