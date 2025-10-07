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
public class RunModelProgram {
    
    private enum ProgramState {
        STARTING,           // Initial state
        MODEL_LOADING,      // Sent load_model_string, waiting for ready
        SIMULATION_RUNNING, // Sent run_simulation, simulation in progress
        COMPLETED,          // Program completed successfully
        FAILED              // Program failed with error
    }
    
    private final String sessionKey;
    private final SessionManager sessionManager;
    private final Consumer<String> statusUpdater;
    private final Consumer<ProgressParser.ProgressInfo> progressCallback;
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
        this.sessionKey = sessionKey;
        this.sessionManager = sessionManager;
        this.statusUpdater = statusUpdater;
        this.progressCallback = progressCallback;
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

        String loadCommand = JsonStdioProtocol.Commands.loadModelString(modelText, kalixcliUid);
        sessionManager.sendCommand(sessionKey, loadCommand)
            .thenRun(() -> {
                statusUpdater.accept("Loading model in " + getDisplayName());
            })
            .exceptionally(throwable -> {
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Failed to send model to " + getDisplayName() + ": " + throwable.getMessage());
                return null;
            });
    }
    
    /**
     * Handles JSON protocol messages for this program.
     * Returns true if the message was handled by this program, false otherwise.
     * 
     * @param message the JSON message from kalixcli
     * @return true if message was handled
     */
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

                String runCommand = JsonStdioProtocol.Commands.runSimulation(kalixcliUid);
                sessionManager.sendCommand(sessionKey, runCommand)
                    .thenRun(() -> {
                        statusUpdater.accept("Model loaded, starting simulation in " + getDisplayName());
                    })
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
                // Simulation started
                statusUpdater.accept("Simulation running in " + getDisplayName());
                return true;
                
            case PROGRESS:
                // Progress update during simulation
                try {
                    JsonMessage.ProgressData progressData = JsonStdioProtocol.extractData(message, JsonMessage.ProgressData.class);
                    JsonMessage.ProgressInfo progress = progressData.getProgress();
                    if (progress != null) {
                        if (progressCallback != null) {
                            ProgressParser.ProgressInfo progressInfo = ProgressParser.createFromJson(
                                    progress.getPercentComplete(),
                                    progress.getCurrentStep(),
                                    progressData.getCommand()
                            );
                            progressCallback.accept(progressInfo);
                        }
                        statusUpdater.accept(String.format("%s: %.1f%% - %s",
                                getDisplayName(), progress.getPercentComplete(), progress.getCurrentStep()));
                    }
                    return true;
                } catch (Exception e) {
                    statusUpdater.accept("Error parsing progress from " + getDisplayName() + ": " + e.getMessage());
                    return true;
                }
                
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
                    // If we can't parse the result data, just continue without outputs
                    statusUpdater.accept("Warning: Could not parse outputs from result in " + getDisplayName() + ": " + e.getMessage());
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
     */
    private String extractErrorMessage(JsonMessage.SystemMessage message) {
        try {
            if (message.getData() != null && message.getData().has("error")) {
                var errorNode = message.getData().get("error");
                // Check if error is an object with a message property
                if (errorNode.has("message")) {
                    return errorNode.get("message").asText();
                }
                // Fallback to treating error as a string
                return errorNode.asText();
            }
        } catch (Exception e) {
            // If we can't parse error details, use the raw message
        }
        return message.toString();
    }
    
    /**
     * Gets the current state of the program.
     */
    public boolean isActive() {
        return currentState == ProgramState.MODEL_LOADING || currentState == ProgramState.SIMULATION_RUNNING;
    }
    
    /**
     * Gets the current state of the program.
     */
    public boolean isCompleted() {
        return currentState == ProgramState.COMPLETED;
    }
    
    /**
     * Gets the current state of the program.
     */
    public boolean isFailed() {
        return currentState == ProgramState.FAILED;
    }
    
    /**
     * Gets a description of the current program state.
     */
    public String getStateDescription() {
        return switch (currentState) {
            case STARTING -> "Starting Run Model program";
            case MODEL_LOADING -> "Loading model";
            case SIMULATION_RUNNING -> "Running simulation";
            case COMPLETED -> "Run Model completed";
            case FAILED -> "Run Model failed";
            default -> "Unknown state";
        };
    }

    /**
     * Gets the outputs generated by the simulation.
     * @return the list of output names, or null if no outputs available
     */
    public List<String> getOutputsGenerated() {
        return outputsGenerated;
    }
}