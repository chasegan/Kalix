package com.kalix.ide.cli;

import java.util.function.Consumer;

/**
 * Handles the "Optimisation" program flow:
 * 1. Send load_model_string command
 * 2. Wait for RESULT response from load_model_string
 * 3. Wait for READY response (kalixcli ready for next command)
 * 4. Send run_optimisation command with configuration
 * 5. Monitor progress messages
 * 6. Handle RESULT message from run_optimisation
 */
public class OptimisationProgram extends AbstractSessionProgram {

    private enum ProgramState {
        STARTING,           // Sent load_model_string, waiting for RESULT
        MODEL_LOADING,      // Received RESULT from load, waiting for READY
        OPTIMISING,         // Sent run_optimisation, optimisation in progress
        COMPLETED,          // Program completed successfully
        FAILED              // Program failed with error
    }

    private final Consumer<String> resultCallback;
    private ProgramState currentState = ProgramState.STARTING;
    private String configText;
    private String modelIni;

    /**
     * Creates a new Optimisation program instance.
     *
     * @param sessionKey the session key
     * @param sessionManager the session manager to use for sending commands
     * @param statusUpdater callback for status updates
     * @param progressCallback callback for progress updates
     * @param resultCallback callback for final result message
     */
    public OptimisationProgram(String sessionKey, SessionManager sessionManager,
                               Consumer<String> statusUpdater,
                               Consumer<ProgressParser.ProgressInfo> progressCallback,
                               Consumer<String> resultCallback) {
        super(sessionKey, sessionManager, statusUpdater, progressCallback);
        this.resultCallback = resultCallback;
    }

    /**
     * Starts the Optimisation program with the given configuration and model.
     * Immediately sends load_model_string command to kalixcli.
     *
     * @param configText the optimisation configuration (INI format)
     * @param modelIni the model definition (INI format)
     */
    public void start(String configText, String modelIni) {
        this.configText = configText;
        this.modelIni = modelIni;

        // Step 1: Send load_model_string command
        // We stay in STARTING state and wait for RESULT message to know model is loaded
        String loadCommand = JsonStdioProtocol.Commands.loadModelString(modelIni);
        sessionManager.sendCommand(sessionKey, loadCommand)
            .thenRun(() -> {
                statusUpdater.accept("Loading model for optimisation");
            })
            .exceptionally(throwable -> {
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Failed to send model: " + throwable.getMessage());
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
            case STARTING -> handleStartingState(msgType, message);
            case MODEL_LOADING -> handleModelLoadingState(msgType, message);
            case OPTIMISING -> handleOptimisingState(msgType, message);
            case COMPLETED, FAILED -> false; // Don't handle messages in these states
        };
    }

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
            case OPTIMISING -> "Optimising";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }

    /**
     * Handles messages in STARTING state (waiting for model load to complete).
     */
    private boolean handleStartingState(JsonStdioTypes.SystemMessageType msgType,
                                        JsonMessage.SystemMessage message) {
        switch (msgType) {
            case RESULT:
                // Check if this is the result from load_model_string
                String cmd = message.getCommand();
                if ("load_model_string".equals(cmd)) {
                    // Model loaded successfully, now wait for READY before sending run_optimisation
                    currentState = ProgramState.MODEL_LOADING;
                    statusUpdater.accept("Model loaded, waiting for ready signal");
                    return true;
                }
                return false;

            case ERROR:
                // Model loading failed
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Model loading failed: " + errorMsg);
                return true;

            default:
                // Other message types (including READY) are ignored in STARTING state
                return false;
        }
    }

    /**
     * Handles messages while waiting for READY after model load RESULT.
     */
    private boolean handleModelLoadingState(JsonStdioTypes.SystemMessageType msgType,
                                             JsonMessage.SystemMessage message) {
        switch (msgType) {
            case READY:
                // CLI is ready for next command, now send run_optimisation
                currentState = ProgramState.OPTIMISING;

                // Send run_optimisation command with only config (model already loaded)
                String optCommand = JsonStdioProtocol.createCommandMessage("run_optimisation",
                    java.util.Map.of("config", configText));

                sendCommand(optCommand, "Model loaded, optimisation started", "Failed to start optimisation");
                return true;

            case ERROR:
                // Unexpected error
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Error after model load: " + errorMsg);
                return true;

            default:
                // Other message types are not relevant in MODEL_LOADING state
                return false;
        }
    }

    /**
     * Handles messages while optimisation is running.
     */
    private boolean handleOptimisingState(JsonStdioTypes.SystemMessageType msgType,
                                          JsonMessage.SystemMessage message) {
        switch (msgType) {
            case BUSY:
                // Progress update using base class utility
                handleProgressUpdate(message, "run_optimisation");
                return true;

            case RESULT:
                // Optimisation complete
                currentState = ProgramState.COMPLETED;
                statusUpdater.accept("Optimisation completed");
                if (resultCallback != null) {
                    resultCallback.accept(extractResultMessage(message));
                }
                return true;

            case ERROR:
                // Optimisation failed
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Optimisation failed: " + errorMsg);
                if (resultCallback != null) {
                    resultCallback.accept("ERROR: " + errorMsg);
                }
                return true;

            default:
                return false;
        }
    }
}
