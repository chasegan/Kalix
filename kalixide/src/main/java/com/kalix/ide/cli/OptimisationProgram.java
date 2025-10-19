package com.kalix.ide.cli;

import java.util.function.Consumer;

/**
 * Handles the "Optimisation" program flow:
 * 1. Wait for session ready
 * 2. Send run_optimisation command with configuration and model
 * 3. Monitor progress messages
 * 4. Handle result message
 */
public class OptimisationProgram extends AbstractSessionProgram {

    private enum ProgramState {
        WAITING_FOR_READY,  // Waiting for session to be ready
        OPTIMISING,         // Opt command sent, optimisation in progress
        COMPLETED,          // Program completed successfully
        FAILED              // Program failed with error
    }

    private final Consumer<String> resultCallback;
    private ProgramState currentState = ProgramState.WAITING_FOR_READY;
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
     * The session must already be started.
     *
     * @param configText the optimisation configuration (INI format)
     * @param modelIni the model definition (INI format)
     */
    public void start(String configText, String modelIni) {
        this.configText = configText;
        this.modelIni = modelIni;
        this.currentState = ProgramState.WAITING_FOR_READY;
        statusUpdater.accept("Waiting for session to be ready...");
    }

    @Override
    public boolean handleMessage(JsonMessage.SystemMessage message) {
        JsonStdioTypes.SystemMessageType msgType = message.systemMessageType();
        if (msgType == null) {
            return false;
        }

        return switch (currentState) {
            case WAITING_FOR_READY -> handleWaitingForReadyState(msgType, message);
            case OPTIMISING -> handleOptimisingState(msgType, message);
            case COMPLETED, FAILED -> false;
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
            case WAITING_FOR_READY -> "Waiting for Ready";
            case OPTIMISING -> "Optimising";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }

    /**
     * Handles messages while waiting for session to be ready.
     */
    private boolean handleWaitingForReadyState(JsonStdioTypes.SystemMessageType msgType,
                                                JsonMessage.SystemMessage message) {
        switch (msgType) {
            case READY:
                // Session ready, send run_optimisation command with both config and model
                currentState = ProgramState.OPTIMISING;

                String optCommand = JsonStdioProtocol.createCommandMessage("run_optimisation",
                    java.util.Map.of(
                        "config", configText,
                        "model_ini", modelIni
                    ));

                sendCommand(optCommand, "Optimisation started", "Failed to start optimisation");
                return true;

            case ERROR:
                // Session failed
                currentState = ProgramState.FAILED;
                statusUpdater.accept("Session error: " + extractErrorMessage(message));
                return true;

            default:
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
