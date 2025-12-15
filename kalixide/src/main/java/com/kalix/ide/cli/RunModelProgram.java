package com.kalix.ide.cli;

import com.kalix.ide.windows.RunManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        STARTING,           // Initial state
        MODEL_LOADING,      // Sent load_model_string, waiting for ready
        SIMULATION_RUNNING, // Sent run_simulation, simulation in progress
        COMPLETED,          // Program completed successfully
        FAILED              // Program failed with error
    }

    private ProgramState currentState = ProgramState.STARTING;
    private String modelText; //Keeping this here in case I later want to save the model back out.
    private List<String> outputsGenerated;

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
     * Starts the Run Model program with the given model text.
     * 
     * @param modelText the model definition to load and run
     */
    public void start(String modelText) {
        this.modelText = modelText;
        this.currentState = ProgramState.MODEL_LOADING;
        
        // Step 1: Send load_model_string command via SessionManager
        // Get kalixcli_uid if available
        String kalixcliUid = "";
        Optional<SessionManager.KalixSession> session = sessionManager.getSession(sessionKey);
        if (session.isPresent() && session.get().getKalixcliUid() != null) {
            kalixcliUid = session.get().getKalixcliUid();
        }

        String loadCommand = JsonStdioProtocol.Commands.loadModelString(modelText);
        sessionManager.sendCommand(sessionKey, loadCommand)
            .exceptionally(throwable -> {
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Failed to send model to " + getDisplayName() + ": " + throwable.getMessage());
                return null;
            });
    }
    
    @Override
    public boolean handleMessage(JsonMessage.SystemMessage message) {
        JsonStdioTypes.SystemMessageType msgType = message.systemMessageType();
        if (msgType == null) {
            return false;
        }

        return switch (currentState) {
            case MODEL_LOADING -> handleModelLoadingState(msgType, message);
            case SIMULATION_RUNNING -> handleSimulationRunningState(msgType, message);
            case STARTING, COMPLETED, FAILED -> false; // Don't handle messages in these states
            default -> false;
        };
    }
    
    /**
     * Handles messages while waiting for model to load.
     */
    private boolean handleModelLoadingState(JsonStdioTypes.SystemMessageType msgType, 
                                          JsonMessage.SystemMessage message) {
        switch (msgType) {
            case READY:
                // Model loaded successfully, now start simulation
                currentState = ProgramState.SIMULATION_RUNNING;

                // Get kalixcli_uid if available
                String kalixcliUid = "";
                Optional<SessionManager.KalixSession> session = sessionManager.getSession(sessionKey);
                if (session.isPresent() && session.get().getKalixcliUid() != null) {
                    kalixcliUid = session.get().getKalixcliUid();
                }

                String runCommand = JsonStdioProtocol.Commands.runSimulation();
                sessionManager.sendCommand(sessionKey, runCommand)
                    .exceptionally(throwable -> {
                        currentState = ProgramState.FAILED;
                        statusUpdater.accept("Failed to start simulation in " + getDisplayName() + ": " + throwable.getMessage());
                        return null;
                    });
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
                // Simulation completed successfully
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
            case STARTING -> "Starting";
            case MODEL_LOADING -> "Loading Model";
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