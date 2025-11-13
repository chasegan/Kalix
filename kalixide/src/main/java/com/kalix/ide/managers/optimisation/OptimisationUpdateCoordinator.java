package com.kalix.ide.managers.optimisation;

import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationStatus;

import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Coordinates updates between different optimisation managers when events occur.
 * Handles callbacks for tree updates, progress updates, and UI synchronization.
 */
public class OptimisationUpdateCoordinator {

    private final OptimisationTreeManager treeManager;
    private final OptimisationProgressManager progressManager;
    private final OptimisationResultsManager resultsManager;
    private final OptimisationPlotManager plotManager;
    private final OptimisationSessionManager sessionManager;

    /**
     * Creates a new OptimisationUpdateCoordinator.
     */
    public OptimisationUpdateCoordinator(
            OptimisationTreeManager treeManager,
            OptimisationProgressManager progressManager,
            OptimisationResultsManager resultsManager,
            OptimisationPlotManager plotManager,
            OptimisationSessionManager sessionManager) {
        this.treeManager = treeManager;
        this.progressManager = progressManager;
        this.resultsManager = resultsManager;
        this.plotManager = plotManager;
        this.sessionManager = sessionManager;
    }

    /**
     * Updates the tree node for a specific session (status, icon, display text).
     *
     * @param sessionKey The session key
     */
    public void updateTreeNodeForSession(String sessionKey) {
        DefaultMutableTreeNode node = sessionManager.getTreeNode(sessionKey);
        if (node != null) {
            // Get current status
            OptimisationInfo optInfo = (OptimisationInfo) node.getUserObject();
            OptimisationStatus currentStatus = optInfo.getStatus();
            OptimisationStatus previousStatus = sessionManager.getLastKnownStatus(sessionKey);

            // Update last known status
            sessionManager.updateStatus(sessionKey, currentStatus);

            // Delegate to tree manager for display update
            treeManager.updateTreeNodeForSession(node, currentStatus, previousStatus);
        }
    }

    /**
     * Gets the currently selected OptimisationInfo if it matches the given session key.
     *
     * @param sessionKey The session key to check
     * @return The matching OptimisationInfo, or null if not selected or doesn't match
     */
    private OptimisationInfo getSelectedOptimisationIfMatches(String sessionKey) {
        OptimisationInfo selectedInfo = treeManager.getSelectedOptimisation();
        if (selectedInfo != null && selectedInfo.getSession().getSessionKey().equals(sessionKey)) {
            return selectedInfo;
        }
        return null;
    }

    /**
     * Updates the results display if the given session is currently selected.
     *
     * @param sessionKey The session key
     */
    public void updateDetailsIfSelected(String sessionKey) {
        OptimisationInfo selectedInfo = getSelectedOptimisationIfMatches(sessionKey);
        if (selectedInfo != null && selectedInfo.hasStartedRunning()) {
            // Update timing labels (changes every update)
            progressManager.updateTimingLabels(selectedInfo);
            // Note: optimised model display is NOT updated here - it only changes on status changes
            // (start, complete, error) which are handled separately
        }
    }

    /**
     * Updates the convergence plot and labels if the given session is currently selected.
     *
     * @param sessionKey The session key
     */
    public void updateConvergencePlotIfSelected(String sessionKey) {
        OptimisationInfo selectedInfo = getSelectedOptimisationIfMatches(sessionKey);
        if (selectedInfo != null && selectedInfo.getResult() != null) {
            // Update the convergence plot with latest data
            plotManager.updatePlot(selectedInfo.getResult());
            // Also update labels in real-time
            progressManager.updateConvergenceLabels(selectedInfo.getResult());
        }
    }

    /**
     * Updates the model display if the given session is currently selected.
     * Called when status changes (e.g., optimization completes or errors).
     *
     * @param sessionKey The session key
     */
    public void updateModelDisplayIfSelected(String sessionKey) {
        OptimisationInfo selectedInfo = getSelectedOptimisationIfMatches(sessionKey);
        if (selectedInfo != null) {
            resultsManager.updateOptimisedModelDisplay(selectedInfo);
        }
    }
}
