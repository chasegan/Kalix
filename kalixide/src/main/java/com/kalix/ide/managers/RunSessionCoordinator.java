package com.kalix.ide.managers;

import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.cli.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.*;

/**
 * Coordinates run and session lifecycle management for RunManager.
 *
 * Responsibilities:
 * - Tracking active sessions and mapping them to run names
 * - Creating and updating run tree nodes
 * - Managing "Last run" tracking
 * - Detecting run state changes
 * - Handling session additions and removals
 * - Run counter management
 *
 * Usage:
 * 1. Create coordinator with required dependencies
 * 2. Call refreshRuns() to sync with active sessions
 * 3. Coordinator maintains all run tracking state
 */
public class RunSessionCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(RunSessionCoordinator.class);

    // Dependencies
    private final StdioTaskManager stdioTaskManager;
    private final DefaultMutableTreeNode currentRunsNode;
    private final DefaultMutableTreeNode lastRunNode;
    private final DefaultTreeModel treeModel;
    private final JTree runTree;

    // Run tracking state
    private final Map<String, String> sessionToRunName = new HashMap<>();
    private final Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private final Map<String, RunStatus> lastKnownStatus = new HashMap<>();
    private final Map<String, Long> completionTimestamps = new HashMap<>();
    private int runCounter = 1;

    // Last run tracking
    private Object lastRunInfo = null;  // RunInfo type
    private DefaultMutableTreeNode lastRunChildNode = null;
    private long lastRunCompletionTime = 0L;

    // Callbacks to RunManager
    private final Runnable updateOutputsTreeCallback;
    private final Runnable refreshLastSeriesCallback;
    private final java.util.function.Function<Object, Object> createRunInfoCallback;  // (String name, Session) -> RunInfo
    private final java.util.function.Function<Object, RunStatus> getRunStatusCallback;  // RunInfo -> RunStatus
    private final java.util.function.BiConsumer<Object, Object> updateRunInfoInTreeCallback;  // (RunInfo, updater)

    /**
     * Run status enum (mirrors RunManager.RunStatus).
     */
    public enum RunStatus {
        STARTING, LOADING, RUNNING, DONE, ERROR, STOPPED
    }

    /**
     * Creates a new RunSessionCoordinator.
     *
     * @param stdioTaskManager Task manager for session access
     * @param currentRunsNode Tree node for current runs
     * @param lastRunNode Tree node for last run
     * @param treeModel Tree model for updates
     * @param runTree The run tree
     * @param updateOutputsTreeCallback Callback to update outputs tree
     * @param refreshLastSeriesCallback Callback to refresh "[Last]" series
     * @param createRunInfoCallback Callback to create RunInfo objects
     * @param getRunStatusCallback Callback to get status from RunInfo
     * @param updateRunInfoInTreeCallback Callback to update RunInfo in timeseries tree
     */
    public RunSessionCoordinator(
            StdioTaskManager stdioTaskManager,
            DefaultMutableTreeNode currentRunsNode,
            DefaultMutableTreeNode lastRunNode,
            DefaultTreeModel treeModel,
            JTree runTree,
            Runnable updateOutputsTreeCallback,
            Runnable refreshLastSeriesCallback,
            java.util.function.Function<Object, Object> createRunInfoCallback,
            java.util.function.Function<Object, RunStatus> getRunStatusCallback,
            java.util.function.BiConsumer<Object, Object> updateRunInfoInTreeCallback) {
        this.stdioTaskManager = stdioTaskManager;
        this.currentRunsNode = currentRunsNode;
        this.lastRunNode = lastRunNode;
        this.treeModel = treeModel;
        this.runTree = runTree;
        this.updateOutputsTreeCallback = updateOutputsTreeCallback;
        this.refreshLastSeriesCallback = refreshLastSeriesCallback;
        this.createRunInfoCallback = createRunInfoCallback;
        this.getRunStatusCallback = getRunStatusCallback;
        this.updateRunInfoInTreeCallback = updateRunInfoInTreeCallback;
    }

    /**
     * Gets the session-to-run-name mapping.
     */
    public Map<String, String> getSessionToRunName() {
        return sessionToRunName;
    }

    /**
     * Gets the session-to-tree-node mapping.
     */
    public Map<String, DefaultMutableTreeNode> getSessionToTreeNode() {
        return sessionToTreeNode;
    }

    /**
     * Gets the last run info.
     */
    public Object getLastRunInfo() {
        return lastRunInfo;
    }

    /**
     * Handles session events (state changes).
     */
    public void handleSessionEvent(SessionManager.SessionEvent event) {
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

        // Check if we need to refresh the tree
        if (!sessionToTreeNode.containsKey(sessionKey) ||
            newState == SessionManager.SessionState.READY ||
            newState == SessionManager.SessionState.ERROR ||
            newState == SessionManager.SessionState.TERMINATED) {

            refreshRuns();
        }
    }

    /**
     * Refreshes the run list by syncing with active sessions.
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
                if (!(session.getActiveProgram() instanceof RunModelProgram)) {
                    continue;
                }

                String sessionKey = session.getSessionKey();

                if (!sessionToTreeNode.containsKey(sessionKey)) {
                    // New session - add to tree
                    String runName = "Run_" + runCounter++;
                    sessionToRunName.put(sessionKey, runName);

                    Object runInfo = createRunInfoCallback.apply(new Object[]{runName, session});
                    RunStatus initialStatus = getRunStatusCallback.apply(runInfo);

                    DefaultMutableTreeNode runNode = new DefaultMutableTreeNode(runInfo);
                    int insertIndex = currentRunsNode.getChildCount();
                    currentRunsNode.add(runNode);
                    sessionToTreeNode.put(sessionKey, runNode);
                    lastKnownStatus.put(sessionKey, initialStatus);

                    // Track insertion for tree notification
                    insertedIndices.add(insertIndex);
                    insertedNodes.add(runNode);

                    // If session is already DONE when first discovered, treat it as a completion
                    if (initialStatus == RunStatus.DONE) {
                        long completionTime = System.currentTimeMillis();
                        completionTimestamps.put(sessionKey, completionTime);

                        if (completionTime > lastRunCompletionTime) {
                            updateLastRun(runInfo, completionTime);
                        }
                    }
                } else {
                    // Existing session - check for status changes
                    DefaultMutableTreeNode existingNode = sessionToTreeNode.get(sessionKey);
                    Object runInfo = existingNode.getUserObject();
                    RunStatus currentStatus = getRunStatusCallback.apply(runInfo);
                    RunStatus lastStatus = lastKnownStatus.get(sessionKey);

                    if (lastStatus != currentStatus) {
                        // Status changed - refresh this node's display
                        treeModel.nodeChanged(existingNode);

                        // Detect session reuse
                        if (lastStatus == RunStatus.DONE &&
                            (currentStatus == RunStatus.RUNNING || currentStatus == RunStatus.LOADING || currentStatus == RunStatus.STARTING)) {
                            completionTimestamps.remove(sessionKey);
                        }

                        lastKnownStatus.put(sessionKey, currentStatus);

                        // Check if run just completed
                        if (currentStatus == RunStatus.DONE && lastStatus != RunStatus.DONE) {
                            long completionTime = System.currentTimeMillis();
                            completionTimestamps.put(sessionKey, completionTime);

                            if (completionTime > lastRunCompletionTime) {
                                updateLastRun(runInfo, completionTime);
                            }
                        }

                        // Update outputs if this run is currently selected
                        TreePath selectedPath = runTree.getSelectionPath();
                        if (selectedPath != null && selectedPath.getLastPathComponent() == existingNode) {
                            updateOutputsTreeCallback.run();
                        }
                    }
                }
            }

            // Notify tree model of inserted nodes (preserves selection)
            if (!insertedIndices.isEmpty()) {
                int[] indices = insertedIndices.stream().mapToInt(Integer::intValue).toArray();
                treeModel.nodesWereInserted(currentRunsNode, indices);
                runTree.expandPath(new TreePath(currentRunsNode.getPath()));
            }

            // Check for removed sessions
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
                    if (lastRunInfo != null && getSessionKey(lastRunInfo).equals(sessionKey)) {
                        lastRunInfo = null;
                        lastRunCompletionTime = 0L;

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
     * Updates the Last run node to point to the most recently completed run.
     */
    private void updateLastRun(Object newLastRun, long completionTime) {
        lastRunInfo = newLastRun;
        lastRunCompletionTime = completionTime;

        // Check if we're replacing an existing Last child
        DefaultMutableTreeNode oldChildNode = null;
        int oldChildIndex = -1;
        boolean wasLastSelected = false;

        if (lastRunChildNode != null) {
            oldChildIndex = lastRunNode.getIndex(lastRunChildNode);
            oldChildNode = lastRunChildNode;

            // Check if the old Last child was selected
            TreePath[] selectedPaths = runTree.getSelectionPaths();
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

        // Create new child node with "Last" label
        SessionManager.KalixSession session = getSession(newLastRun);
        Object lastRunInfoWrapper = createRunInfoCallback.apply(new Object[]{"Last", session});
        lastRunChildNode = new DefaultMutableTreeNode(lastRunInfoWrapper);

        if (oldChildNode != null) {
            // Replacing existing child
            lastRunNode.remove(oldChildNode);
            treeModel.nodesWereRemoved(lastRunNode, new int[]{oldChildIndex}, new Object[]{oldChildNode});

            lastRunNode.add(lastRunChildNode);
            treeModel.nodesWereInserted(lastRunNode, new int[]{0});
        } else {
            // First time
            lastRunNode.add(lastRunChildNode);
            treeModel.nodesWereInserted(lastRunNode, new int[]{0});
        }

        // If Last was previously selected, restore selection and update tree
        if (wasLastSelected) {
            TreePath newLastPath = new TreePath(lastRunChildNode.getPath());
            runTree.addSelectionPath(newLastPath);

            // Update RunInfo references in timeseries tree
            updateRunInfoInTreeCallback.accept(lastRunInfoWrapper, null);
        }

        // Expand the Last run node
        runTree.expandPath(new TreePath(lastRunNode.getPath()));

        // Refresh "[Last]" series in plots
        refreshLastSeriesCallback.run();
    }

    /**
     * Helper to get session key from RunInfo.
     * Assumes runInfo is com.kalix.ide.windows.RunManager.RunInfo.
     */
    private String getSessionKey(Object runInfo) {
        if (runInfo == null) {
            return "";
        }
        try {
            // Cast to the actual RunInfo class (now public)
            var runInfoClass = Class.forName("com.kalix.ide.windows.RunManager$RunInfo");
            if (runInfoClass.isInstance(runInfo)) {
                var sessionField = runInfoClass.getField("session");
                SessionManager.KalixSession session = (SessionManager.KalixSession) sessionField.get(runInfo);
                return session != null ? session.getSessionKey() : "";
            }
        } catch (Exception e) {
            logger.error("Failed to get session key from runInfo", e);
        }
        return "";
    }

    /**
     * Helper to get session from RunInfo.
     * Assumes runInfo is com.kalix.ide.windows.RunManager.RunInfo.
     */
    private SessionManager.KalixSession getSession(Object runInfo) {
        if (runInfo == null) {
            return null;
        }
        try {
            // Cast to the actual RunInfo class (now public)
            var runInfoClass = Class.forName("com.kalix.ide.windows.RunManager$RunInfo");
            if (runInfoClass.isInstance(runInfo)) {
                var sessionField = runInfoClass.getField("session");
                return (SessionManager.KalixSession) sessionField.get(runInfo);
            }
        } catch (Exception e) {
            logger.error("Failed to get session from runInfo", e);
        }
        return null;
    }
}
