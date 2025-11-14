package com.kalix.ide.managers.optimisation;

import com.kalix.ide.cli.ProgressParser;
import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationResult;
import com.kalix.ide.models.optimisation.OptimisationStatus;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Handles various events and callbacks for optimisation operations.
 * Centralizes event handling logic for progress updates, results, and status changes.
 */
public class OptimisationEventHandlers {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationEventHandlers.class);

    private final OptimisationSessionManager sessionManager;
    private final OptimisationTreeManager treeManager;
    private final OptimisationProgressManager progressManager;
    private final Consumer<String> statusUpdater;

    // UI update callbacks
    private Consumer<String> treeNodeUpdater;
    private Consumer<String> detailsUpdater;
    private Consumer<String> convergencePlotUpdater;
    private Consumer<String> modelDisplayUpdater;

    /**
     * Creates a new OptimisationEventHandlers instance.
     */
    public OptimisationEventHandlers(OptimisationSessionManager sessionManager,
                                    OptimisationTreeManager treeManager,
                                    OptimisationProgressManager progressManager,
                                    Consumer<String> statusUpdater) {
        this.sessionManager = sessionManager;
        this.treeManager = treeManager;
        this.progressManager = progressManager;
        this.statusUpdater = statusUpdater;
    }

    /**
     * Sets the callback for updating tree nodes.
     */
    public void setTreeNodeUpdater(Consumer<String> updater) {
        this.treeNodeUpdater = updater;
    }

    /**
     * Sets the callback for updating details panel.
     */
    public void setDetailsUpdater(Consumer<String> updater) {
        this.detailsUpdater = updater;
    }

    /**
     * Sets the callback for updating convergence plot.
     */
    public void setConvergencePlotUpdater(Consumer<String> updater) {
        this.convergencePlotUpdater = updater;
    }

    /**
     * Sets the callback for updating model display (optimised model text editor).
     */
    public void setModelDisplayUpdater(Consumer<String> updater) {
        this.modelDisplayUpdater = updater;
    }

    /**
     * Handles the list of optimisable parameters from kalixcli.
     *
     * @param sessionKey The session key
     * @param parameters The list of parameter names
     * @param guiBuilder The GUI builder to update
     */
    public void handleOptimisableParameters(String sessionKey, List<String> parameters,
                                           OptimisationGuiBuilder guiBuilder) {
        SwingUtilities.invokeLater(() -> {
            // Update the parameters table in the GUI builder
            guiBuilder.setOptimisableParameters(parameters);

            // Auto-generate expressions for all parameters
            guiBuilder.autoGenerateParameterExpressions();

            if (statusUpdater != null) {
                statusUpdater.accept("Found " + parameters.size() + " optimisable parameters");
            }
        });
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
                        if (convergencePlotUpdater != null) {
                            convergencePlotUpdater.accept(sessionKey);
                        }
                    }
                }

                // Update progress manager
                OptimisationInfo optInfo = sessionManager.getOptimisationInfo(sessionKey);
                if (optInfo != null) {
                    progressManager.updateProgress(optInfo, result);
                }
            }

            // Update tree node to show progress
            if (treeNodeUpdater != null) {
                treeNodeUpdater.accept(sessionKey);
            }

            // Update details if selected
            if (detailsUpdater != null) {
                detailsUpdater.accept(sessionKey);
            }
        });
    }

    /**
     * Handles the final optimisation result.
     *
     * @param sessionKey The session key
     * @param resultJson The result JSON string
     */
    public void handleOptimisationResult(String sessionKey, String resultJson) {
        SwingUtilities.invokeLater(() -> {
            OptimisationResult result = sessionManager.getOptimisationResult(sessionKey);
            if (result != null) {
                // Parse the result JSON to extract all fields
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode rootNode = mapper.readTree(resultJson);

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

                    // Extract parameters
                    if (rootNode.has("parameters_physical")) {
                        Map<String, Double> paramsPhysical = new HashMap<>();
                        JsonNode paramsNode = rootNode.get("parameters_physical");
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
                if (treeNodeUpdater != null) {
                    treeNodeUpdater.accept(sessionKey);
                }

                if (detailsUpdater != null) {
                    detailsUpdater.accept(sessionKey);
                }

                if (convergencePlotUpdater != null) {
                    convergencePlotUpdater.accept(sessionKey);
                }

                // Update model display (status changed to DONE or ERROR)
                if (modelDisplayUpdater != null) {
                    modelDisplayUpdater.accept(sessionKey);
                }
            }
        });
    }
}