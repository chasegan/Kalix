package com.kalix.gui.cli;

import java.io.IOException;
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
    
    private final String sessionId;
    private final SessionManager sessionManager;
    private final Consumer<String> statusUpdater;
    private final Consumer<ProgressParser.ProgressInfo> progressCallback;
    private ProgramState currentState = ProgramState.STARTING;
    private String modelText;
    private List<String> outputsGenerated;
    
    /**
     * Creates a new Run Model program instance.
     * 
     * @param sessionId the session ID
     * @param sessionManager the session manager to use for sending commands
     * @param statusUpdater callback for status updates
     * @param progressCallback callback for progress updates
     */
    public RunModelProgram(String sessionId, SessionManager sessionManager, 
                          Consumer<String> statusUpdater, 
                          Consumer<ProgressParser.ProgressInfo> progressCallback) {
        this.sessionId = sessionId;
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
        // Get CLI session ID if available
        String cliSessionId = "";
        Optional<SessionManager.KalixSession> session = sessionManager.getSession(sessionId);
        if (session.isPresent() && session.get().getCliSessionId() != null) {
            cliSessionId = session.get().getCliSessionId();
        }

        String loadCommand = JsonStdioProtocol.Commands.loadModelString(modelText, cliSessionId);
        sessionManager.sendCommand(sessionId, loadCommand)
            .thenRun(() -> {
                statusUpdater.accept("Loading model in session: " + sessionId);
            })
            .exceptionally(throwable -> {
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Failed to send model to session " + sessionId + ": " + throwable.getMessage());
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
        
        switch (currentState) {
            case MODEL_LOADING:
                return handleModelLoadingState(msgType, message);
                
            case SIMULATION_RUNNING:
                return handleSimulationRunningState(msgType, message);
                
            case STARTING:
            case COMPLETED:
            case FAILED:
                // Don't handle messages in these states
                return false;
                
            default:
                return false;
        }
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

                // Get CLI session ID if available
                String cliSessionId = "";
                Optional<SessionManager.KalixSession> session = sessionManager.getSession(sessionId);
                if (session.isPresent() && session.get().getCliSessionId() != null) {
                    cliSessionId = session.get().getCliSessionId();
                }

                String runCommand = JsonStdioProtocol.Commands.runSimulation(cliSessionId);
                sessionManager.sendCommand(sessionId, runCommand)
                    .thenRun(() -> {
                        statusUpdater.accept("Model loaded, starting simulation in session: " + sessionId);
                    })
                    .exceptionally(throwable -> {
                        currentState = ProgramState.FAILED;
                        statusUpdater.accept("Failed to start simulation in session " + sessionId + ": " + throwable.getMessage());
                        return null;
                    });
                return true;
                
            case ERROR:
                // Model loading failed
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Model loading failed in session " + sessionId + ": " + errorMsg);
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
                statusUpdater.accept("Simulation running in session: " + sessionId);
                return true;
                
            case PROGRESS:
                // Progress update during simulation
                try {
                    JsonMessage.ProgressData progressData = JsonStdioProtocol.extractData(message, JsonMessage.ProgressData.class);
                    JsonMessage.ProgressInfo progress = progressData.getProgress();
                    if (progress != null && progressCallback != null) {
                        ProgressParser.ProgressInfo progressInfo = ProgressParser.createFromJson(
                            progress.getPercentComplete(),
                            progress.getCurrentStep(),
                            progressData.getCommand()
                        );
                        progressCallback.accept(progressInfo);
                    }
                    statusUpdater.accept(String.format("Session %s: %.1f%% - %s", 
                        sessionId, progress.getPercentComplete(), progress.getCurrentStep()));
                    return true;
                } catch (Exception e) {
                    statusUpdater.accept("Error parsing progress from session " + sessionId + ": " + e.getMessage());
                    return true;
                }
                
            case RESULT:
                // Simulation completed successfully
                currentState = ProgramState.COMPLETED;

                // Extract outputs_generated from result message
                try {
                    JsonMessage.ResultData resultData = JsonStdioProtocol.extractData(message, JsonMessage.ResultData.class);
                    if (resultData != null && resultData.getResult() != null) {
                        outputsGenerated = resultData.getResult().getOutputsGenerated();
                    }
                } catch (Exception e) {
                    // If we can't parse the result data, just continue without outputs
                    statusUpdater.accept("Warning: Could not parse outputs from result in session " + sessionId + ": " + e.getMessage());
                }

                statusUpdater.accept("Model run completed successfully in session: " + sessionId);
                return true;
                
            case STOPPED:
                // Simulation was interrupted
                currentState = ProgramState.COMPLETED; // Still considered completed, just stopped early
                statusUpdater.accept("Model run stopped in session: " + sessionId);
                return true;
                
            case ERROR:
                // Simulation failed
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Simulation failed in session " + sessionId + ": " + errorMsg);
                return true;
                
            default:
                // Other message types not relevant during simulation (including READY, which is handled by SessionManager)
                return false;
        }
    }
    
    /**
     * Extracts error message from JSON error response.
     */
    private String extractErrorMessage(JsonMessage.SystemMessage message) {
        try {
            if (message.getData() != null && message.getData().has("error")) {
                return message.getData().get("error").asText();
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
        switch (currentState) {
            case STARTING: return "Starting Run Model program";
            case MODEL_LOADING: return "Loading model";
            case SIMULATION_RUNNING: return "Running simulation";
            case COMPLETED: return "Run Model completed";
            case FAILED: return "Run Model failed";
            default: return "Unknown state";
        }
    }

    /**
     * Gets the outputs generated by the simulation.
     * @return the list of output names, or null if no outputs available
     */
    public List<String> getOutputsGenerated() {
        return outputsGenerated;
    }
}