package com.kalix.ide.windows;

import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.RunSeries;
import com.kalix.ide.flowviz.data.RunSource;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.managers.RunContextMenuManager;
import com.kalix.ide.managers.SessionTreeBookkeeping;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;

import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the data-source tree's run bookkeeping for {@link RunManager}: which CLI
 * sessions are shown as runs, their display names and last-seen statuses, discovery
 * of new sessions and removal of dead ones ({@link #refreshRuns}), renaming
 * ({@link #renameRun}), and programmatic selection ({@link #selectRun}).
 *
 * <p>Completions are forwarded to {@link LastRunTracker#onRunCompleted}; removals to
 * {@link LastRunTracker#rebindLast}/{@link LastRunTracker#clearLast} (the Last
 * alias self-heals when its run is removed). All methods are EDT-only ({@code refreshRuns}
 * and {@code selectRun} marshal themselves via {@code SwingUtilities.invokeLater}).</p>
 */
class RunTreeController {

    private final RunManager window;
    private final StdioTaskManager stdioTaskManager;
    private final JCheckboxTree timeseriesSourceTree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode currentRunsNode;
    private final VisualizationTabManager tabManager;
    private final DataSet plotDataSet;
    private final SeriesSlotManager seriesSlotManager;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private final LastRunTracker lastRunTracker;
    private final SeriesFetchCoordinator fetchCoordinator;

    // === RUN TRACKING ===
    // sessionKey -> node/name/status/completion, with single-shot removal cleanup.
    private final SessionTreeBookkeeping<RunInfoImpl.DetailedRunStatus> sessions =
        new SessionTreeBookkeeping<>();
    private int runCounter = 1;

    RunTreeController(RunManager window,
                      StdioTaskManager stdioTaskManager,
                      JCheckboxTree timeseriesSourceTree,
                      DefaultTreeModel treeModel,
                      DefaultMutableTreeNode currentRunsNode,
                      VisualizationTabManager tabManager,
                      DataSet plotDataSet,
                      SeriesSlotManager seriesSlotManager,
                      TimeSeriesRequestManager timeSeriesRequestManager,
                      LastRunTracker lastRunTracker,
                      SeriesFetchCoordinator fetchCoordinator) {
        this.window = window;
        this.stdioTaskManager = stdioTaskManager;
        this.timeseriesSourceTree = timeseriesSourceTree;
        this.treeModel = treeModel;
        this.currentRunsNode = currentRunsNode;
        this.tabManager = tabManager;
        this.plotDataSet = plotDataSet;
        this.seriesSlotManager = seriesSlotManager;
        this.timeSeriesRequestManager = timeSeriesRequestManager;
        this.lastRunTracker = lastRunTracker;
        this.fetchCoordinator = fetchCoordinator;
    }

    /**
     * The live sessionKey → display-name map. Shared with
     * {@link RunContextMenuManager}, which removes entries when a run is removed
     * via its context menu.
     */
    Map<String, String> sessionToRunNameView() {
        return sessions.namesView();
    }

    /** Whether the given session is currently shown in the tree. */
    boolean hasSession(String sessionKey) {
        return sessions.hasNode(sessionKey);
    }

    /** The display name for a session, or {@code null} if unknown. */
    String getRunNameForSession(String sessionKey) {
        return sessions.name(sessionKey);
    }

    /**
     * Returns the current display name for a given {@code runId}, or {@code null} if
     * no run with that id is currently known. Used by {@link com.kalix.ide.flowviz.data.DefaultLabelResolver}
     * to project {@link RunSeries} refs to user-visible labels.
     *
     * <p>Linear over current runs; trivially small in practice. If profiling later
     * shows this on a hot path, swap to an explicit {@code Map<Long, RunInfoImpl>}
     * maintained in {@code refreshRuns} and {@code renameRun}.</p>
     */
    String runNameForId(long runId) {
        for (DefaultMutableTreeNode node : sessions.nodes()) {
            if (node.getUserObject() instanceof RunInfoImpl info && info.getRunId() == runId) {
                return info.getRunName();
            }
        }
        return null;
    }

    /**
     * Refreshes the data source tree with current session states.
     *
     * Called by session event listener when sessions change state. This method:
     * <ol>
     *   <li>Discovers new sessions and adds them to "Current runs"</li>
     *   <li>Detects status changes (RUNNING→DONE) for existing sessions</li>
     *   <li>Updates "Last run" when a run completes (via {@link LastRunTracker#onRunCompleted})</li>
     *   <li>Triggers timeseries tree rebuild if the selected run's status changed</li>
     * </ol>
     *
     * Only shows RunModelProgram sessions - other types (OptimisationProgram) are filtered out.
     */
    void refreshRuns() {
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

                if (!sessions.hasNode(sessionKey)) {
                    // New session - add to tree
                    String runName = "Run_" + runCounter++;
                    sessions.putName(sessionKey, runName);

                    RunInfoImpl runInfo = new RunInfoImpl(runName, session);
                    RunInfoImpl.DetailedRunStatus initialStatus = runInfo.getDetailedRunStatus();

                    DefaultMutableTreeNode runNode = new DefaultMutableTreeNode(runInfo);
                    int insertIndex = currentRunsNode.getChildCount();
                    currentRunsNode.add(runNode);
                    sessions.putNode(sessionKey, runNode);
                    sessions.putStatus(sessionKey, initialStatus);

                    // Track insertion for tree notification
                    insertedIndices.add(insertIndex);
                    insertedNodes.add(runNode);

                    // If session is already DONE when first discovered, treat it as a completion
                    // (This handles fast-completing runs that finish before refreshRuns() is called)
                    if (initialStatus == RunInfoImpl.DetailedRunStatus.DONE) {
                        long completionTime = System.currentTimeMillis();
                        sessions.putCompletionTimestamp(sessionKey, completionTime);

                        lastRunTracker.onRunCompleted(runInfo, completionTime);
                    }
                } else {
                    // Existing session - check for status changes
                    DefaultMutableTreeNode existingNode = sessions.node(sessionKey);
                    RunInfoImpl runInfo = (RunInfoImpl) existingNode.getUserObject();
                    RunInfoImpl.DetailedRunStatus currentStatus = runInfo.getDetailedRunStatus();
                    RunInfoImpl.DetailedRunStatus lastStatus = sessions.status(sessionKey);

                    if (lastStatus != currentStatus) {
                        // Status changed - refresh this node's display
                        treeModel.nodeChanged(existingNode);

                        // Detect session reuse: if session was DONE and is now RUNNING/LOADING, it's a new run
                        if (lastStatus == RunInfoImpl.DetailedRunStatus.DONE &&
                            (currentStatus == RunInfoImpl.DetailedRunStatus.RUNNING || currentStatus == RunInfoImpl.DetailedRunStatus.LOADING || currentStatus == RunInfoImpl.DetailedRunStatus.STARTING)) {
                            // Session reused for new run - reset completion tracking for this session
                            sessions.clearCompletionTimestamp(sessionKey);
                        }

                        sessions.putStatus(sessionKey, currentStatus);

                        // Check if run just completed
                        if (currentStatus == RunInfoImpl.DetailedRunStatus.DONE && lastStatus != RunInfoImpl.DetailedRunStatus.DONE) {
                            // Record completion timestamp
                            long completionTime = System.currentTimeMillis();
                            sessions.putCompletionTimestamp(sessionKey, completionTime);

                            // Update Last if this is more recent
                            lastRunTracker.onRunCompleted(runInfo, completionTime);
                        }

                        // Update outputs if this run is currently checked
                        TreePath existingPath = new TreePath(existingNode.getPath());
                        if (timeseriesSourceTree.isPathChecked(existingPath)) {
                            window.updateOutputsTree();
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

            for (Map.Entry<String, DefaultMutableTreeNode> entry : sessions.nodeEntries()) {
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
                    var node = (DefaultMutableTreeNode) child;
                    var path = node.getPath();
                    timeseriesSourceTree.removePath(new TreePath(path));
                    currentRunsNode.remove(node);
                }

                // Clean up tracking maps (single-shot removal via the bookkeeping)
                boolean lastWasRemoved = false;
                for (String sessionKey : sessionsToRemove) {
                    lastWasRemoved |= lastRunTracker.isLastSession(sessionKey);
                    removeRunData(sessions.remove(sessionKey));
                }

                // Last is a standing subscription to "whichever run is newest": if its
                // run was removed, SELF-HEAL to the most recently completed survivor
                // (sessions.remove above already cleared the removed runs' timestamps,
                // so the bookkeeping now holds exactly the survivors). Silent by
                // design — symmetric with a new run arriving. With no completed
                // survivor, restore the pre-first-run birth state instead.
                if (lastWasRemoved) {
                    String runnerUpKey = sessions.latestCompletedSession();
                    if (runnerUpKey != null) {
                        RunInfoImpl runnerUp =
                            (RunInfoImpl) sessions.node(runnerUpKey).getUserObject();
                        lastRunTracker.rebindLast(runnerUp, sessions.completionTimestamp(runnerUpKey));
                    } else {
                        lastRunTracker.clearLast();
                    }
                }

                // Notify tree model of removals. nodesWereRemoved requires ascending
                // indices; collection order here follows HashMap iteration, so sort the
                // (index, child) pairs together.
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < removedIndices.size(); i++) order.add(i);
                order.sort(java.util.Comparator.comparingInt(removedIndices::get));
                int[] indices = new int[order.size()];
                Object[] children = new Object[order.size()];
                for (int i = 0; i < order.size(); i++) {
                    indices[i] = removedIndices.get(order.get(i));
                    children[i] = removedChildren.get(order.get(i));
                }
                treeModel.nodesWereRemoved(currentRunsNode, indices, children);
            }
        });
    }

    /**
     * Renames a run. Series identity in the pool, color map, tab selections, stats models,
     * and undo history is the runId on {@link RunSeries}, which
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
    String renameRun(RunContextMenuManager.RunInfo oldRunInfoIface, String newName) {
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
        for (String existing : sessions.names()) {
            if (existing.equals(newName)) {
                return "A run with the name '" + newName + "' already exists.";
            }
        }

        // Defensive: rename is initiated from a tree-node context menu, but the node may
        // have been removed asynchronously between click and dialog close (rare; e.g. a
        // concurrent session-terminate). Reject rather than leave inconsistent state.
        String sessionKey = oldRunInfo.getSession().getSessionKey();
        DefaultMutableTreeNode node = sessions.node(sessionKey);
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

        sessions.putName(sessionKey, newName);

        // Keep lastRunInfo pointing at the live RunInfoImpl for this session — otherwise
        // it would dangle to the now-orphan instance.
        lastRunTracker.onRunRenamed(oldRunInfo, newRunInfo);

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
        fetchCoordinator.beginProgrammaticUpdate();
        try {
            window.updateOutputsTree();
            Set<SeriesRef> tabSeries = tabManager.getTargetTabSelectedSeries();
            window.restoreTreeChecksForSeries(tabSeries);
        } finally {
            fetchCoordinator.endProgrammaticUpdate();
        }

        // Cheap repaint to pick up the new label in plot legends / stats column headers
        // that already cache projected strings.
        tabManager.updateAllTabs(false);

        return null;
    }

    /**
     * Selects and checks the run associated with the given sessionKey, so its outputs are
     * shown. Expands the tree, selects the run node (visual focus only — selection drives
     * nothing), and checks it. The check goes through the normal check-change event, so
     * {@code RunManager.onSourceTreeCheckedChanged} does everything a user click would:
     * snapshot the tab's source context, rebuild the outputs tree, restore and reconcile
     * the tab's series checks. One code path, deliberately not mirrored here.
     */
    void selectRun(String sessionKey) {
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode runNode = sessions.node(sessionKey);
            if (runNode != null) {
                TreePath pathToRun = new TreePath(runNode.getPath());

                // Expand parent nodes to make the run visible
                timeseriesSourceTree.expandPath(new TreePath(currentRunsNode.getPath()));

                timeseriesSourceTree.setSelectionPath(pathToRun);
                timeseriesSourceTree.addCheckedPaths(java.util.List.of(pathToRun));

                // Scroll to make the selection visible
                timeseriesSourceTree.scrollPathToVisible(pathToRun);
            }
        });
    }

    /**
     * Scrubs a removed run's data everywhere outside the source tree: the shared
     * {@code plotDataSet} pool, slot assignments, every tab's selections, and both
     * levels of the fetch cache. The runs-side counterpart of
     * {@link RunManager#removeLoadedDataset} - without it a day of modelling retains
     * every removed run's series (multi-decade double[]s) until application exit.
     */
    private void removeRunData(DefaultMutableTreeNode runNode) {
        if (runNode == null || !(runNode.getUserObject() instanceof RunInfoImpl runInfo)) {
            return;
        }

        long runId = runInfo.getRunId();
        List<SeriesRef> refs = new ArrayList<>();
        for (SeriesRef ref : plotDataSet.getSeriesRefs()) {
            if (ref instanceof RunSeries runSeries
                    && runSeries.runId() == runId) {
                refs.add(ref);
            }
        }
        for (SeriesRef ref : refs) {
            plotDataSet.removeSeries(ref);
            seriesSlotManager.removeSlot(ref);
        }
        if (!refs.isEmpty()) {
            tabManager.removeSeriesFromAllTabs(refs);
        }

        // Forget the run from every tab's recorded source context — runIds are never
        // reused, so no tab should try to restore this source again.
        tabManager.removeSourceFromAllTabs(new RunSource(runId));

        // Clear by UID, not session key: the session has already left the session
        // manager, so key-based lookup cannot reach these entries any more.
        String kalixcliUid = runInfo.getSession().getKalixcliUid();
        if (kalixcliUid != null) {
            timeSeriesRequestManager.clearCacheForKalixcliUid(kalixcliUid);
        }
    }
}
