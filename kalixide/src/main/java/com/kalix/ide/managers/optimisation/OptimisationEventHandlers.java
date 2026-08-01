package com.kalix.ide.managers.optimisation;

import com.kalix.ide.cli.ProgressParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Handles optimisation events (progress updates, results) and fans the resulting
 * UI updates out to the managers: tree node display, details/timing labels, the
 * convergence plot, and the optimised-model editor.
 *
 * <p>This is the single event → managers layer. It used to be two classes — an
 * event parser that forwarded through four positional {@code Consumer<String>}
 * callbacks into an "update coordinator" that finally called the managers — one
 * wrapping the other with no behaviour of its own. Merged per the July 2026
 * review (optimisation finding #15).</p>
 */
public class OptimisationEventHandlers {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationEventHandlers.class);

    private final OptimisationSessionManager sessionManager;
    private final OptimisationTreeManager treeManager;
    private final OptimisationProgressManager progressManager;
    private final OptimisationResultsManager resultsManager;
    private final OptimisationPlotManager plotManager;
    private final Consumer<String> statusUpdater;

    /**
     * Creates a new OptimisationEventHandlers instance.
     */
    public OptimisationEventHandlers(OptimisationSessionManager sessionManager,
                                     OptimisationTreeManager treeManager,
                                     OptimisationProgressManager progressManager,
                                     OptimisationResultsManager resultsManager,
                                     OptimisationPlotManager plotManager,
                                     Consumer<String> statusUpdater) {
        this.sessionManager = sessionManager;
        this.treeManager = treeManager;
        this.progressManager = progressManager;
        this.resultsManager = resultsManager;
        this.plotManager = plotManager;
        this.statusUpdater = statusUpdater;
    }

    /**
     * Handles progress updates during optimisation.
     *
     * @param sessionKey The session key
     * @param progressInfo The progress information
     */
    public void handleOptimisationProgress(String sessionKey, ProgressParser.ProgressInfo progressInfo) {
        SwingUtilities.invokeLater(() -> {
            // Update progress in result
            OptimisationResult result = sessionManager.getOptimisationResult(sessionKey);
            if (result != null) {
                result.setCurrentProgress((int) progressInfo.getPercentage());
                result.setProgressDescription(progressInfo.getDescription());

                // Store convergence data if available (optimization-specific progress)
                if (progressInfo.getEvaluationCount() != null && progressInfo.getObjectiveValues() != null) {
                    List<Double> objectiveValues = progressInfo.getObjectiveValues();
                    if (!objectiveValues.isEmpty()) {
                        // Store evaluation count and best objective
                        result.addConvergencePoint(progressInfo.getEvaluationCount(),
                                                  objectiveValues.get(0),
                                                  objectiveValues);

                        // Store current evaluation count
                        result.setEvaluations(progressInfo.getEvaluationCount());

                        // Update convergence plot if selected
                        updateConvergencePlotIfSelected(sessionKey);
                    }
                }

                // Update progress manager
                OptimisationInfo optInfo = sessionManager.getOptimisationInfo(sessionKey);
                if (optInfo != null) {
                    progressManager.updateProgress(optInfo, result);
                }
            }

            // Update tree node to show progress
            updateTreeNodeForSession(sessionKey);

            // Update details if selected
            updateDetailsIfSelected(sessionKey);
        });
    }

    /**
     * Handles the final optimisation result.
     *
     * @param sessionKey The session key
     * @param resultJson The result JSON string
     */
    /** Shared, thread-safe mapper - a fresh ObjectMapper per result is pure waste. */
    private static final ObjectMapper RESULT_MAPPER = new ObjectMapper();

    /**
     * Sentinel prefix OptimisationProgram puts on the result callback when the backend
     * reports an error instead of a result. Not JSON - must be handled before parsing.
     */
    private static final String ERROR_SENTINEL = "ERROR: ";

    public void handleOptimisationResult(String sessionKey, String resultJson) {
        SwingUtilities.invokeLater(() -> {
            OptimisationResult result = sessionManager.getOptimisationResult(sessionKey);
            if (result != null) {
                // A backend error arrives as "ERROR: <message>", not JSON. Feeding it
                // to the parser used to throw, skipping end-time/status/progress
                // cleanup - the elapsed timer ticked forever and the real error text
                // was lost behind "Failed to parse result".
                if (resultJson != null && resultJson.startsWith(ERROR_SENTINEL)) {
                    String errorText = resultJson.substring(ERROR_SENTINEL.length());
                    result.setSuccess(false);
                    result.setMessage(errorText);
                    result.setEndTime(java.time.LocalDateTime.now());
                    sessionManager.updateStatus(sessionKey, OptimisationStatus.ERROR);
                    OptimisationInfo errInfo = sessionManager.getOptimisationInfo(sessionKey);
                    if (errInfo != null) {
                        progressManager.completeProgress(errInfo, result);
                    }
                    if (statusUpdater != null) {
                        statusUpdater.accept("Optimisation failed: " + errorText);
                    }
                    updateTreeNodeForSession(sessionKey);
                    updateDetailsIfSelected(sessionKey);
                    return;
                }

                // Parse the result JSON to extract all fields
                try {
                    JsonNode rootNode = RESULT_MAPPER.readTree(resultJson);

                    // Extract fields from the result object
                    if (rootNode.has("best_objective")) {
                        result.setBestObjective(rootNode.get("best_objective").asDouble());
                    }
                    if (rootNode.has("evaluations")) {
                        result.setEvaluations(rootNode.get("evaluations").asInt());
                    }
                    if (rootNode.has("generations")) {
                        result.setGenerations(rootNode.get("generations").asInt());
                    }
                    if (rootNode.has("message")) {
                        result.setMessage(rootNode.get("message").asText());
                    }
                    if (rootNode.has("success")) {
                        result.setSuccess(rootNode.get("success").asBoolean());
                    }

                    // Extract the optimised model INI
                    if (rootNode.has("optimised_model_ini")) {
                        result.setOptimisedModelIni(rootNode.get("optimised_model_ini").asText());
                    }

                    // Extract parameters. The engine emits "params_physical"
                    // (commands.rs); "parameters_physical" is kept as a fallback for
                    // any older payloads.
                    JsonNode paramsNode = rootNode.has("params_physical")
                        ? rootNode.get("params_physical")
                        : rootNode.get("parameters_physical");
                    if (paramsNode != null && paramsNode.isObject()) {
                        Map<String, Double> paramsPhysical = new HashMap<>();
                        paramsNode.fields().forEachRemaining(entry ->
                            paramsPhysical.put(entry.getKey(), entry.getValue().asDouble()));
                        result.setParametersPhysical(paramsPhysical);
                    }

                    // Set end time
                    result.setEndTime(java.time.LocalDateTime.now());

                    // Update optimisation status
                    OptimisationStatus newStatus = result.isSuccess() ?
                        OptimisationStatus.DONE : OptimisationStatus.ERROR;
                    sessionManager.updateStatus(sessionKey, newStatus);

                    // Update displays
                    OptimisationInfo optInfo = sessionManager.getOptimisationInfo(sessionKey);
                    if (optInfo != null) {
                        progressManager.completeProgress(optInfo, result);
                    }

                    if (statusUpdater != null) {
                        if (result.isSuccess()) {
                            statusUpdater.accept("Optimisation completed successfully");
                        } else {
                            statusUpdater.accept("Optimisation failed: " + result.getMessage());
                        }
                    }

                } catch (Exception e) {
                    logger.error("Failed to parse optimisation result", e);
                    if (statusUpdater != null) {
                        statusUpdater.accept("Failed to parse result: " + e.getMessage());
                    }
                }

                // Update tree and UI
                updateTreeNodeForSession(sessionKey);
                updateDetailsIfSelected(sessionKey);
                updateConvergencePlotIfSelected(sessionKey);

                // Update model display (status changed to DONE or ERROR)
                updateModelDisplayIfSelected(sessionKey);
            }
        });
    }

    // === UI update fan-out (formerly OptimisationUpdateCoordinator) ===

    /**
     * Updates the tree node for a specific session (status, icon, display text).
     *
     * @param sessionKey The session key
     */
    public void updateTreeNodeForSession(String sessionKey) {
        DefaultMutableTreeNode node = treeManager.getNodeForSession(sessionKey);
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
