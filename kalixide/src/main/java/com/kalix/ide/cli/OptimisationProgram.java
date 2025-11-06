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
        WAITING_FOR_INITIAL_READY,  // Waiting for initial "rdy" from CLI before sending any commands
        STARTING,           // Sent load_model_string, waiting for RESULT
        MODEL_LOADING,      // Received RESULT from load, waiting for READY
        FETCHING_PARAMS,    // Sent get_optimisable_params, waiting for RESULT
        READY,              // Model loaded and params fetched, waiting for user to configure and start optimisation
        OPTIMISING,         // Sent run_optimisation, optimisation in progress
        COMPLETED,          // Program completed successfully
        FAILED              // Program failed with error
    }

    private final Consumer<String> resultCallback;
    private final Consumer<java.util.List<String>> parametersCallback;
    private ProgramState currentState = ProgramState.WAITING_FOR_INITIAL_READY;
    private String configText;
    private String modelIni;

    /**
     * Creates a new Optimisation program instance.
     *
     * @param sessionKey the session key
     * @param sessionManager the session manager to use for sending commands
     * @param statusUpdater callback for status updates
     * @param progressCallback callback for progress updates
     * @param parametersCallback callback for receiving list of optimisable parameters
     * @param resultCallback callback for final result message
     */
    public OptimisationProgram(String sessionKey, SessionManager sessionManager,
                               Consumer<String> statusUpdater,
                               Consumer<ProgressParser.ProgressInfo> progressCallback,
                               Consumer<java.util.List<String>> parametersCallback,
                               Consumer<String> resultCallback) {
        super(sessionKey, sessionManager, statusUpdater, progressCallback);
        this.parametersCallback = parametersCallback;
        this.resultCallback = resultCallback;
    }

    /**
     * Initializes the Optimisation program by storing the model.
     * Waits for the initial "rdy" message from CLI before sending any commands.
     * After model loads, the program enters READY state and waits for runOptimisation() to be called.
     *
     * @param modelIni the model definition (INI format)
     */
    public void initialize(String modelIni) {
        this.modelIni = modelIni;
        statusUpdater.accept("Waiting for CLI to be ready...");
        // Don't send any commands yet - wait for the initial READY message from CLI
    }

    /**
     * Starts the optimisation with the given configuration.
     * Can only be called when the program is in READY state.
     *
     * @param configText the optimisation configuration (INI format)
     */
    public void runOptimisation(String configText) {
        if (currentState != ProgramState.READY) {
            statusUpdater.accept("Cannot start optimisation: program not ready (current state: " + currentState + ")");
            return;
        }

        this.configText = configText;

        // Send run_optimisation command
        currentState = ProgramState.OPTIMISING;
        String optCommand = JsonStdioProtocol.createCommandMessage("run_optimisation",
            java.util.Map.of("config", configText));

        sendCommand(optCommand, "Optimisation started", "Failed to start optimisation");
    }

    @Override
    public boolean handleMessage(JsonMessage.SystemMessage message) {
        JsonStdioTypes.SystemMessageType msgType = message.systemMessageType();
        if (msgType == null) {
            return false;
        }

        return switch (currentState) {
            case WAITING_FOR_INITIAL_READY -> handleWaitingForInitialReady(msgType, message);
            case STARTING -> handleStartingState(msgType, message);
            case MODEL_LOADING -> handleModelLoadingState(msgType, message);
            case FETCHING_PARAMS -> handleFetchingParamsState(msgType, message);
            case READY -> false; // In READY state, waiting for user to call runOptimisation()
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
            case WAITING_FOR_INITIAL_READY -> "Waiting for CLI";
            case STARTING -> "Starting";
            case MODEL_LOADING -> "Loading Model";
            case FETCHING_PARAMS -> "Fetching Parameters";
            case READY -> "Ready";
            case OPTIMISING -> "Optimising";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }

    /**
     * Handles messages while waiting for initial READY from CLI.
     * Once received, sends the load_model_string command.
     */
    private boolean handleWaitingForInitialReady(JsonStdioTypes.SystemMessageType msgType,
                                                  JsonMessage.SystemMessage message) {
        switch (msgType) {
            case READY:
                // CLI is ready, now send load_model_string command
                currentState = ProgramState.STARTING;
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
                return true;

            case ERROR:
                // Unexpected error during initialization
                currentState = ProgramState.FAILED;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Failed to initialize: " + errorMsg);
                return true;

            default:
                // Ignore other messages while waiting for initial READY
                return false;
        }
    }

    /**
     * Gets the model text that was loaded for this optimisation.
     *
     * @return The model INI text, or null if not yet loaded
     */
    public String getModelText() {
        return modelIni;
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
                // CLI is ready, now fetch optimisable parameters
                currentState = ProgramState.FETCHING_PARAMS;
                String paramsCommand = JsonStdioProtocol.createCommandMessage("get_optimisable_params",
                    java.util.Map.of());
                sendCommand(paramsCommand, "Fetching optimisable parameters", "Failed to fetch parameters");
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
     * Handles messages while waiting for parameters list.
     */
    private boolean handleFetchingParamsState(JsonStdioTypes.SystemMessageType msgType,
                                               JsonMessage.SystemMessage message) {
        switch (msgType) {
            case RESULT:
                // Check if this is the result from get_optimisable_params
                String cmd = message.getCommand();
                if ("get_optimisable_params".equals(cmd)) {
                    // Extract parameters list from result
                    java.util.List<String> parameters = extractParametersList(message);

                    // Call callback with parameters
                    if (parametersCallback != null && parameters != null) {
                        parametersCallback.accept(parameters);
                    }

                    // Transition to READY state
                    currentState = ProgramState.READY;
                    statusUpdater.accept("Model loaded, ready to configure optimisation");
                    return true;
                }
                return false;

            case ERROR:
                // Error fetching parameters - log but continue to READY anyway
                currentState = ProgramState.READY;
                String errorMsg = extractErrorMessage(message);
                statusUpdater.accept("Warning: Could not fetch parameters: " + errorMsg);
                return true;

            default:
                return false;
        }
    }

    /**
     * Extracts the parameters list from a get_optimisable_params RESULT message.
     */
    private java.util.List<String> extractParametersList(JsonMessage.SystemMessage message) {
        try {
            com.fasterxml.jackson.databind.JsonNode result = message.getResult();

            if (result != null && result.isObject()) {
                // Get the "parameters" field from the JsonNode
                com.fasterxml.jackson.databind.JsonNode parametersNode = result.get("parameters");

                if (parametersNode != null && parametersNode.isArray()) {
                    // Convert JsonNode array to List<String>
                    java.util.List<String> parameters = new java.util.ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode node : parametersNode) {
                        if (node.isTextual()) {
                            parameters.add(node.asText());
                        }
                    }
                    return parameters;
                }
            }
        } catch (Exception e) {
            // Log but don't fail - we can continue without parameters
            statusUpdater.accept("Warning: Could not parse parameters list: " + e.getMessage());
        }
        return null;
    }

    /**
     * Handles optimisation-specific progress updates that include objective values.
     * Extracts the "d" array from the PROGRESS message and passes it to the callback.
     */
    private void handleOptimisationProgressUpdate(JsonMessage.SystemMessage message) {
        try {
            Integer current = message.getCurrent();  // Evaluation count (i)
            Integer total = message.getTotal();      // Total evaluations (n)
            com.fasterxml.jackson.databind.JsonNode dataNode = message.getProgressData();  // Objective values (d)

            if (current != null && total != null && progressCallback != null) {
                // Calculate percentage
                double percentage = (current * 100.0) / total;

                // Extract objective values array
                java.util.List<Double> objectiveValues = new java.util.ArrayList<>();
                if (dataNode != null && dataNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode valueNode : dataNode) {
                        if (valueNode.isNumber()) {
                            objectiveValues.add(valueNode.asDouble());
                        }
                    }
                }

                // Create description
                String description = String.format("Evaluation %d/%d", current, total);

                // Create ProgressInfo with optimisation data
                ProgressParser.ProgressInfo progressInfo = new ProgressParser.ProgressInfo(
                    percentage,
                    description,
                    "run_optimisation",
                    ProgressParser.ProgressInfo.ProgressType.ITERATION,
                    current,
                    objectiveValues
                );

                // Invoke callback
                progressCallback.accept(progressInfo);
            }
        } catch (Exception e) {
            statusUpdater.accept("Warning: Could not parse optimisation progress: " + e.getMessage());
        }
    }

    /**
     * Handles messages while optimisation is running.
     */
    private boolean handleOptimisingState(JsonStdioTypes.SystemMessageType msgType,
                                          JsonMessage.SystemMessage message) {
        switch (msgType) {
            case BUSY:
                // Legacy progress update (fallback)
                handleProgressUpdate(message, "run_optimisation");
                return true;

            case PROGRESS:
                // Optimisation-specific progress with objective values
                handleOptimisationProgressUpdate(message);
                return true;

            case RESULT:
                // Optimisation complete
                currentState = ProgramState.COMPLETED;
                statusUpdater.accept("Optimisation completed");
                if (resultCallback != null) {
                    // Pass the entire result JSON for detailed parsing
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
