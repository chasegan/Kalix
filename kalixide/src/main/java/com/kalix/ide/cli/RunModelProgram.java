package com.kalix.ide.cli;

import com.kalix.ide.windows.RunManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles the complete "Run Model" program flow:
 * 1. Send load_model_string command
 * 2. Wait for ready response
 * 3. Send run_simulation command
 * 4. Monitor progress and handle completion/errors
 */
public class RunModelProgram extends AbstractSessionProgram {

    private enum ProgramState {
        WAITING_FOR_INITIAL_READY, // Waiting for the CLI's startup "rdy" before sending anything
        MODEL_LOADING,             // Sent load_model_string, waiting for its RESULT
        AWAITING_RUN_READY,        // Model loaded, waiting for "rdy" before run_simulation
        SIMULATION_RUNNING,        // Sent run_simulation, waiting for its RESULT
        COMPLETED,                 // Program completed successfully
        FAILED                     // Program failed with error
    }

    // Written on monitor threads (handleMessage) and the installer thread
    // (onSessionReady); read from the EDT (state descriptions, isActive).
    // Transitions are guarded by synchronized methods; volatile covers the reads.
    private volatile ProgramState currentState = ProgramState.WAITING_FOR_INITIAL_READY;
    private volatile String modelText; //Keeping this here in case I later want to save the model back out.
    private volatile List<String> outputsGenerated;

    /**
     * Creates a new Run Model program instance.
     *
     * @param sessionKey the session key
     * @param sessionManager the session manager to use for sending commands
     * @param statusUpdater callback for status updates
     * @param progressCallback callback for progress updates
     */
    public RunModelProgram(String sessionKey, SessionManager sessionManager,
                          Consumer<String> statusUpdater,
                          Consumer<ProgressParser.ProgressInfo> progressCallback) {
        super(sessionKey, sessionManager, statusUpdater, progressCallback);
    }
    
    /**
     * Starts the Run Model program with the given model text. No command is sent yet:
     * the program waits for the CLI's initial {@code rdy} (delivered either as a live
     * message or via {@link #onSessionReady()} when the rdy arrived before installation),
     * then drives load -> ready -> run, matching each RESULT to its command. Treating
     * just any rdy/res as the awaited one used to mis-fire on slow starts: the run was
     * marked complete with the load result and the real outputs were dropped.
     *
     * @param modelText the model definition to load and run
     */
    public void start(String modelText) {
        this.modelText = modelText;
    }

    @Override
    public void onSessionReady() {
        handleInitialReady();
    }

    @Override
    public synchronized boolean handleMessage(JsonMessage.SystemMessage message) {
        JsonStdioTypes.SystemMessageType msgType = message.systemMessageType();
        if (msgType == null) {
            return false;
        }

        return switch (currentState) {
            case WAITING_FOR_INITIAL_READY -> handleWaitingForInitialReady(msgType, message);
            case MODEL_LOADING -> handleModelLoadingState(msgType, message);
            case AWAITING_RUN_READY -> handleAwaitingRunReadyState(msgType, message);
            case SIMULATION_RUNNING -> handleSimulationRunningState(msgType, message);
            case COMPLETED, FAILED -> false; // Don't handle messages in these states
        };
    }

    /**
     * The initial-ready transition, callable from both the live message path and
     * {@link #onSessionReady()}. Synchronized and state-guarded so the two paths
     * cannot both fire it.
     */
    private synchronized void handleInitialReady() {
        if (currentState != ProgramState.WAITING_FOR_INITIAL_READY || modelText == null) {
            return;
        }
        currentState = ProgramState.MODEL_LOADING;
        String loadCommand = JsonStdioProtocol.Commands.loadModelString(modelText);
        sessionManager.sendCommand(sessionKey, loadCommand)
            .exceptionally(throwable -> {
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Failed to send model to " + getDisplayName() + ": " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Handles messages while waiting for the CLI's startup rdy.
     */
    private boolean handleWaitingForInitialReady(JsonStdioTypes.SystemMessageType msgType,
                                                 JsonMessage.SystemMessage message) {
        switch (msgType) {
            case READY:
                handleInitialReady();
                return true;

            case ERROR:
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Failed to start " + getDisplayName() + ": " + extractErrorMessage(message));
                return true;

            default:
                return false;
        }
    }

    /**
     * Handles messages while waiting for load_model_string's RESULT.
     */
    private boolean handleModelLoadingState(JsonStdioTypes.SystemMessageType msgType,
                                          JsonMessage.SystemMessage message) {
        switch (msgType) {
            case RESULT:
                // Only the load command's own result advances the state machine.
                if (!"load_model_string".equals(message.getCommand())) {
                    return false;
                }
                currentState = ProgramState.AWAITING_RUN_READY;
                return true;

            case ERROR:
                // Model loading failed
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Model loading failed in " + getDisplayName() + ": " + errorMsg);
                return true;

            default:
                // Other message types not relevant during model loading
                return false;
        }
    }

    /**
     * Handles messages after the model loaded, waiting for rdy to send run_simulation.
     */
    private boolean handleAwaitingRunReadyState(JsonStdioTypes.SystemMessageType msgType,
                                                JsonMessage.SystemMessage message) {
        switch (msgType) {
            case READY:
                currentState = ProgramState.SIMULATION_RUNNING;
                String runCommand = JsonStdioProtocol.Commands.runSimulation();
                sessionManager.sendCommand(sessionKey, runCommand)
                    .exceptionally(throwable -> {
                        currentState = ProgramState.FAILED;
                        statusUpdater.accept("Failed to start simulation in " + getDisplayName() + ": " + throwable.getMessage());
                        return null;
                    });
                return true;

            case ERROR:
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Error before simulation start in " + getDisplayName() + ": " + extractErrorMessage(message));
                return true;

            default:
                return false;
        }
    }

    /**
     * Handles messages while simulation is running.
     */
    private boolean handleSimulationRunningState(JsonStdioTypes.SystemMessageType msgType,
                                                JsonMessage.SystemMessage message) {
        switch (msgType) {
            case BUSY:
                // Simulation started - no status update needed
                return true;
                
            case PROGRESS:
                // Progress update during simulation - update progress bar only, not status bar
                try {
                    Integer current = message.getCurrent();
                    Integer total = message.getTotal();
                    String command = message.getCommand();

                    if (current != null && total != null && total > 0 && progressCallback != null) {
                        double percentComplete = (current.doubleValue() / total.doubleValue()) * 100.0;
                        ProgressParser.ProgressInfo progressInfo = ProgressParser.createFromJson(
                                percentComplete,
                                "Processing",
                                command != null ? command : "simulation"
                        );
                        progressCallback.accept(progressInfo);
                    }
                } catch (Exception e) {
                    // Ignore progress parsing errors
                }
                return true;
                
            case RESULT:
                // Only run_simulation's own result completes the run.
                if (!"run_simulation".equals(message.getCommand())) {
                    return false;
                }
                currentState = ProgramState.COMPLETED;

                // Extract outputs from compact protocol result message
                try {
                    JsonNode resultNode = message.getResult();
                    if (resultNode != null) {
                        // Try compact protocol format first: result.ts.outputs
                        if (resultNode.has("ts")) {
                            JsonNode tsNode = resultNode.get("ts");
                            if (tsNode.has("outputs")) {
                                JsonNode outputsNode = tsNode.get("outputs");
                                if (outputsNode.isArray()) {
                                    outputsGenerated = new ArrayList<>();
                                    for (JsonNode output : outputsNode) {
                                        if (output.isTextual()) {
                                            outputsGenerated.add(output.asText());
                                        }
                                    }
                                }
                            }
                        }
                        // Fallback to legacy format: result.outputs_generated
                        else if (resultNode.has("outputs_generated")) {
                            JsonNode outputsNode = resultNode.get("outputs_generated");
                            if (outputsNode.isArray()) {
                                outputsGenerated = new ArrayList<>();
                                for (JsonNode output : outputsNode) {
                                    if (output.isTextual()) {
                                        outputsGenerated.add(output.asText());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // If we can't parse the result data, notify user but continue
                    statusUpdater.accept("Warning: Could not parse outputs from result: " + e.getMessage());
                }

                statusUpdater.accept("Model run completed successfully in " + getDisplayName());
                return true;
                
            case STOPPED:
                // Simulation was interrupted
                currentState = ProgramState.COMPLETED; // Still considered completed, just stopped early
                statusUpdater.accept("Model run stopped in " + getDisplayName());
                return true;
                
            case ERROR:
                // Simulation failed
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Simulation failed in " + getDisplayName() + ": " + errorMsg);
                return true;
                
            default:
                // Other message types not relevant during simulation (including READY, which is handled by SessionManager)
                return false;
        }
    }
    
    /**
     * Gets the display name for this run (run name if available, otherwise session key).
     */
    private String getDisplayName() {
        String runName = RunManager.getRunNameForSession(sessionKey);
        return runName != null ? runName : sessionKey;
    }

    /**
     * Extracts error message from JSON error response.
     * Overrides base class to provide more detailed error extraction.
     */
    @Override
    protected String extractErrorMessage(JsonMessage.SystemMessage message) {
        String errorMsg = null;
        try {
            // In compact protocol, error message is in the errorMessage field
            errorMsg = message.getErrorMessage();
            if (errorMsg == null || errorMsg.isEmpty()) {
                // Fallback: check if error info is in the result field
                if (message.getResult() != null && message.getResult().has("error")) {
                    var errorNode = message.getResult().get("error");
                    // Check if error is an object with a message property
                    if (errorNode.has("message")) {
                        errorMsg = errorNode.get("message").asText();
                    } else {
                        // Fallback to treating error as a string
                        errorMsg = errorNode.asText();
                    }
                }
            }
        } catch (Exception e) {
            // If we can't parse error details, use the raw message
        }

        if (errorMsg == null || errorMsg.isEmpty()) {
            errorMsg = message.toString();
        }

        return cleanupErrorMessage(errorMsg);
    }

    /**
     * Cleans up error messages by removing redundant prefixes and normalizing format.
     * The CLI backend often wraps errors with repetitive prefixes like "Command execution error:".
     */
    private String cleanupErrorMessage(String errorMsg) {
        if (errorMsg == null) {
            return "Unknown error";
        }

        // Prefixes to strip (in order of priority)
        String[] redundantPrefixes = {
            "Command execution error: ",
            "Configuration failed: ",
            "Simulation error: "
        };

        String cleaned = errorMsg;
        boolean changed;

        // Keep stripping prefixes until no more changes (handles "Prefix: Prefix: actual message")
        do {
            changed = false;
            for (String prefix : redundantPrefixes) {
                if (cleaned.startsWith(prefix)) {
                    cleaned = cleaned.substring(prefix.length());
                    changed = true;
                }
            }
        } while (changed);

        return cleaned.isEmpty() ? errorMsg : cleaned;
    }
    
    /**
     * Gets the current state of the program.
     */
    @Override
    public boolean isActive() {
        return currentState != ProgramState.COMPLETED && currentState != ProgramState.FAILED;
    }

    @Override
    public boolean isCompleted() {
        return currentState == ProgramState.COMPLETED || currentState == ProgramState.FAILED;
    }

    @Override
    public boolean isFailed() {
        return currentState == ProgramState.FAILED;
    }

    @Override
    public String getStateDescription() {
        return switch (currentState) {
            case WAITING_FOR_INITIAL_READY -> "Starting";
            case MODEL_LOADING -> "Loading Model";
            case AWAITING_RUN_READY -> "Loading Model";
            case SIMULATION_RUNNING -> "Running Simulation";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }

    /**
     * Gets the outputs generated by the simulation.
     * @return the list of output names, or null if no outputs available
     */
    public List<String> getOutputsGenerated() {
        return outputsGenerated;
    }

    /**
     * Gets the model text (INI string) that was loaded for this run.
     * @return the model text, or null if not available
     */
    public String getModelText() {
        return modelText;
    }
}