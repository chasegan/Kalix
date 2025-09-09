package com.kalix.gui.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * High-level wrapper for JSON-based communication with kalixcli processes.
 * Uses the new structured JSON protocol for communication.
 */
public class JsonInteractiveKalixProcess implements AutoCloseable {
    
    private final ProcessExecutor.RunningProcess runningProcess;
    private final CliLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    // Current session state tracking
    private volatile String sessionId;
    private volatile JsonStdioProtocol.SystemMessageType currentState;
    private volatile String currentExecutingCommand;
    private volatile boolean interruptible = false;
    
    /**
     * Callback interface for handling system messages.
     */
    @FunctionalInterface
    public interface SystemMessageHandler {
        void handle(JsonStdioProtocol.SystemMessage message);
    }
    
    /**
     * Creates a new JsonInteractiveKalixProcess wrapping a running process.
     * 
     * @param runningProcess the interactive process to wrap
     */
    public JsonInteractiveKalixProcess(ProcessExecutor.RunningProcess runningProcess) {
        if (!runningProcess.isInteractive()) {
            throw new IllegalArgumentException("Process must be in interactive mode");
        }
        
        this.runningProcess = runningProcess;
        this.logger = CliLogger.getInstance();
        this.currentState = null; // Will be set when we receive first ready message
    }
    
    /**
     * Starts a new interactive kalixcli process with STDIO mode.
     * 
     * @param cliPath path to kalixcli executable
     * @param processExecutor the process executor to use
     * @param args additional command line arguments
     * @return a new JsonInteractiveKalixProcess
     * @throws IOException if the process cannot be started
     */
    public static JsonInteractiveKalixProcess start(Path cliPath, ProcessExecutor processExecutor, String... args) throws IOException {
        // Start kalixcli in STDIO mode - we need to add "session" and "stdio" args
        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = "session";
        fullArgs[1] = "stdio";
        System.arraycopy(args, 0, fullArgs, 2, args.length);
        
        ProcessExecutor.RunningProcess process = processExecutor.startInteractive(cliPath.toString(), fullArgs);
        return new JsonInteractiveKalixProcess(process);
    }
    
    /**
     * Sends a JSON command message to kalixcli.
     * 
     * @param command the command name
     * @param parameters the command parameters
     * @throws IOException if sending fails
     */
    public void sendCommand(String command, Map<String, Object> parameters) throws IOException {
        checkNotClosed();
        String jsonMessage = JsonStdioProtocol.createCommandMessage(command, parameters);
        logger.info("Sending JSON command: " + jsonMessage);
        runningProcess.sendInput(jsonMessage);
        
        // Update tracking state
        currentExecutingCommand = command;
        // State will be updated when we receive busy message
    }
    
    /**
     * Sends a simple command without parameters.
     * 
     * @param command the command name
     * @throws IOException if sending fails
     */
    public void sendCommand(String command) throws IOException {
        sendCommand(command, null);
    }
    
    /**
     * Sends a model definition using the load_model_string command.
     * 
     * @param modelText the model definition text
     * @throws IOException if sending fails
     */
    public void sendModelDefinition(String modelText) throws IOException {
        checkNotClosed();
        logger.info("Sending model definition (" + modelText.length() + " characters)");
        sendCommand("load_model_string", Map.of("model_ini", modelText));
    }
    
    /**
     * Loads a model from a file path.
     * 
     * @param modelPath the path to the model file
     * @throws IOException if sending fails
     */
    public void loadModelFile(String modelPath) throws IOException {
        sendCommand("load_model_file", Map.of("model_path", modelPath));
    }
    
    /**
     * Starts running a simulation (assumes model is already loaded).
     * 
     * @throws IOException if sending fails
     */
    public void runSimulation() throws IOException {
        sendCommand("run_simulation");
    }
    
    /**
     * Sends a stop message to interrupt the current operation.
     * 
     * @param reason the reason for stopping
     * @throws IOException if sending fails
     */
    public void sendStop(String reason) throws IOException {
        checkNotClosed();
        if (!interruptible) {
            logger.warn("Attempting to stop non-interruptible operation");
        }
        String jsonMessage = JsonStdioProtocol.createStopMessage(reason);
        logger.info("Sending stop message: " + jsonMessage);
        runningProcess.sendInput(jsonMessage);
    }
    
    /**
     * Sends a query message to request information.
     * 
     * @param queryType the type of query
     * @param parameters query parameters
     * @throws IOException if sending fails
     */
    public void sendQuery(String queryType, Map<String, Object> parameters) throws IOException {
        checkNotClosed();
        String jsonMessage = JsonStdioProtocol.createQueryMessage(queryType, parameters);
        logger.info("Sending query: " + jsonMessage);
        runningProcess.sendInput(jsonMessage);
    }
    
    /**
     * Sends a query message without parameters.
     * 
     * @param queryType the type of query
     * @throws IOException if sending fails
     */
    public void sendQuery(String queryType) throws IOException {
        sendQuery(queryType, null);
    }
    
    /**
     * Requests current state information.
     * 
     * @throws IOException if sending fails
     */
    public void requestState() throws IOException {
        sendQuery("get_state");
    }
    
    /**
     * Requests version information.
     * 
     * @throws IOException if sending fails
     */
    public void requestVersion() throws IOException {
        sendQuery("get_version");
    }
    
    /**
     * Sends a terminate message to end the session gracefully.
     * 
     * @throws IOException if sending fails
     */
    public void terminate() throws IOException {
        checkNotClosed();
        String jsonMessage = JsonStdioProtocol.createTerminateMessage();
        logger.info("Sending terminate message: " + jsonMessage);
        runningProcess.sendInput(jsonMessage);
    }
    
    /**
     * Reads the next line from kalixcli output and tries to parse it as JSON.
     * This is a blocking operation.
     * 
     * @return the parsed message if it's valid JSON, or empty if stream is closed or invalid JSON
     * @throws IOException if reading fails
     */
    public Optional<JsonStdioProtocol.SystemMessage> readJsonMessage() throws IOException {
        checkNotClosed();
        String line = runningProcess.readOutputLine();
        if (line != null) {
            logger.debug("Received output: " + line);
            
            // Try to parse as JSON message
            Optional<JsonStdioProtocol.SystemMessage> message = JsonStdioProtocol.parseSystemMessage(line);
            if (message.isPresent()) {
                updateStateFromMessage(message.get());
                return message;
            } else {
                // Not a JSON message, might be legacy output or raw text
                logger.debug("Non-JSON output received: " + line);
            }
        }
        return Optional.empty();
    }
    
    /**
     * Reads the next line from kalixcli output (raw text, not parsed as JSON).
     * Useful for handling legacy output or debugging.
     * 
     * @return the line read, or empty if stream is closed
     * @throws IOException if reading fails
     */
    public Optional<String> readRawOutputLine() throws IOException {
        checkNotClosed();
        String line = runningProcess.readOutputLine();
        if (line != null) {
            logger.debug("Received raw output: " + line);
        }
        return Optional.ofNullable(line);
    }
    
    /**
     * Reads the next error line from kalixcli.
     * 
     * @return the line read, or empty if stream is closed
     * @throws IOException if reading fails
     */
    public Optional<String> readErrorLine() throws IOException {
        checkNotClosed();
        String line = runningProcess.readErrorLine();
        if (line != null) {
            logger.warn("Received error: " + line);
        }
        return Optional.ofNullable(line);
    }
    
    /**
     * Monitors output asynchronously and calls handler for each JSON message.
     * 
     * @param messageHandler callback for handling messages
     * @return CompletableFuture that completes when monitoring stops
     */
    public CompletableFuture<Void> monitorMessages(SystemMessageHandler messageHandler) {
        return CompletableFuture.runAsync(() -> {
            try {
                while (isRunning() && !closed.get()) {
                    if (hasOutputReady()) {
                        Optional<JsonStdioProtocol.SystemMessage> message = readJsonMessage();
                        if (message.isPresent() && messageHandler != null) {
                            try {
                                messageHandler.handle(message.get());
                            } catch (Exception e) {
                                logger.warn("Error in message handler: " + e.getMessage());
                            }
                        }
                    } else {
                        // Small sleep to avoid busy waiting
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logger.error("Error monitoring messages: " + e.getMessage());
            }
        });
    }
    
    /**
     * Waits for a specific message type with timeout.
     * 
     * @param expectedType the message type to wait for
     * @param timeoutSeconds timeout in seconds
     * @return the message if received, empty if timeout
     * @throws IOException if reading fails
     */
    public Optional<JsonStdioProtocol.SystemMessage> waitForMessage(JsonStdioProtocol.SystemMessageType expectedType, int timeoutSeconds) throws IOException {
        checkNotClosed();
        
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (hasOutputReady()) {
                Optional<JsonStdioProtocol.SystemMessage> message = readJsonMessage();
                if (message.isPresent() && message.get().getSystemMessageType() == expectedType) {
                    return message;
                }
            } else {
                // Small sleep to avoid busy waiting
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.warn("Timeout waiting for message type: " + expectedType);
        return Optional.empty();
    }
    
    /**
     * Waits for the ready message after starting kalixcli.
     * 
     * @param timeoutSeconds timeout in seconds
     * @return the ready message with session info
     * @throws IOException if reading fails
     */
    public Optional<JsonStdioProtocol.SystemMessage> waitForReady(int timeoutSeconds) throws IOException {
        return waitForMessage(JsonStdioProtocol.SystemMessageType.READY, timeoutSeconds);
    }
    
    /**
     * Checks if kalixcli has output ready to read without blocking.
     * 
     * @return true if output is available
     * @throws IOException if checking fails
     */
    public boolean hasOutputReady() throws IOException {
        checkNotClosed();
        return runningProcess.isOutputReady();
    }
    
    /**
     * Checks if kalixcli has error output ready to read without blocking.
     * 
     * @return true if error output is available
     * @throws IOException if checking fails
     */
    public boolean hasErrorReady() throws IOException {
        checkNotClosed();
        return runningProcess.isErrorReady();
    }
    
    /**
     * Checks if the kalixcli process is still running.
     * 
     * @return true if the process is alive
     */
    public boolean isRunning() {
        return runningProcess.isRunning();
    }
    
    /**
     * Gets the session ID from kalixcli (available after receiving ready message).
     * 
     * @return session ID if known, null otherwise
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * Gets the current state of the kalixcli session.
     * 
     * @return current state
     */
    public JsonStdioProtocol.SystemMessageType getCurrentState() {
        return currentState;
    }
    
    /**
     * Gets the currently executing command (if any).
     * 
     * @return command name or null if not executing
     */
    public String getCurrentExecutingCommand() {
        return currentExecutingCommand;
    }
    
    /**
     * Checks if the current operation can be interrupted.
     * 
     * @return true if interruptible
     */
    public boolean isInterruptible() {
        return interruptible;
    }
    
    /**
     * Checks if kalixcli is ready to accept commands.
     * 
     * @return true if ready
     */
    public boolean isReady() {
        return currentState == JsonStdioProtocol.SystemMessageType.READY;
    }
    
    /**
     * Checks if kalixcli is busy executing a command.
     * 
     * @return true if busy
     */
    public boolean isBusy() {
        return currentState == JsonStdioProtocol.SystemMessageType.BUSY;
    }
    
    /**
     * Closes the process and cleans up resources.
     * 
     * @param forceKill if true, forcibly kills the process
     */
    public void close(boolean forceKill) {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing JsonInteractiveKalixProcess");
            
            // Try to terminate gracefully first if not forcing
            if (!forceKill && isRunning()) {
                try {
                    terminate();
                    // Give process a moment to terminate gracefully
                    Thread.sleep(1000);
                } catch (IOException | InterruptedException e) {
                    logger.warn("Error during graceful termination: " + e.getMessage());
                }
            }
            
            runningProcess.cancel(forceKill);
        }
    }
    
    /**
     * Closes the process gracefully.
     */
    public void close() {
        close(false);
    }
    
    /**
     * Gets the underlying running process for advanced operations.
     * 
     * @return the running process
     */
    public ProcessExecutor.RunningProcess getRunningProcess() {
        return runningProcess;
    }
    
    /**
     * Updates internal state based on received system message.
     */
    private void updateStateFromMessage(JsonStdioProtocol.SystemMessage message) {
        // Update session ID if not set
        if (sessionId == null) {
            sessionId = message.getSessionId();
            logger.info("Session ID received: " + sessionId);
        }
        
        // Update current state
        JsonStdioProtocol.SystemMessageType messageType = message.getSystemMessageType();
        if (messageType != null) {
            currentState = messageType;
            
            // Update additional state based on message type
            switch (messageType) {
                case READY:
                    currentExecutingCommand = null;
                    interruptible = false;
                    logger.debug("Session is now ready");
                    break;
                    
                case BUSY:
                    try {
                        JsonStdioProtocol.BusyData busyData = JsonStdioProtocol.extractData(message, JsonStdioProtocol.BusyData.class);
                        currentExecutingCommand = busyData.getExecutingCommand();
                        interruptible = busyData.isInterruptible();
                        logger.debug("Session is now busy executing: " + currentExecutingCommand + " (interruptible: " + interruptible + ")");
                    } catch (Exception e) {
                        logger.warn("Failed to parse busy message data: " + e.getMessage());
                    }
                    break;
                    
                case RESULT:
                case STOPPED:
                case ERROR:
                    // Command finished (will likely get ready message next)
                    logger.debug("Command finished with: " + messageType);
                    break;
                    
                default:
                    // Other message types don't change state
                    break;
            }
        }
    }
    
    /**
     * Checks if the process has been closed and throws if so.
     */
    private void checkNotClosed() throws IOException {
        if (closed.get()) {
            throw new IOException("JsonInteractiveKalixProcess has been closed");
        }
    }
    
    /**
     * Convenience methods for common operations.
     */
    public static class CommonOperations {
        
        /**
         * Starts kalixcli and waits for it to be ready.
         * 
         * @param cliPath path to kalixcli
         * @param processExecutor process executor
         * @param timeoutSeconds timeout to wait for ready
         * @return ready process with session ID
         * @throws IOException if starting fails
         */
        public static JsonInteractiveKalixProcess startAndWaitForReady(Path cliPath, ProcessExecutor processExecutor, int timeoutSeconds) throws IOException {
            JsonInteractiveKalixProcess process = JsonInteractiveKalixProcess.start(cliPath, processExecutor);
            
            Optional<JsonStdioProtocol.SystemMessage> readyMessage = process.waitForReady(timeoutSeconds);
            if (readyMessage.isEmpty()) {
                process.close(true);
                throw new IOException("kalixcli did not become ready within " + timeoutSeconds + " seconds");
            }
            
            return process;
        }
        
        /**
         * Loads a model and runs simulation in one operation.
         * 
         * @param process the kalixcli process
         * @param modelText the model definition
         * @return CompletableFuture that completes when operation finishes
         */
        public static CompletableFuture<JsonStdioProtocol.SystemMessage> loadAndRunModel(JsonInteractiveKalixProcess process, String modelText) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!process.isReady()) {
                        throw new IllegalStateException("Process not ready for commands");
                    }
                    
                    // Load model
                    process.sendModelDefinition(modelText);
                    
                    // Wait for ready or result
                    Optional<JsonStdioProtocol.SystemMessage> response = process.waitForMessage(JsonStdioProtocol.SystemMessageType.READY, 30);
                    if (response.isEmpty()) {
                        throw new RuntimeException("Timeout waiting for model load completion");
                    }
                    
                    // Run simulation
                    process.runSimulation();
                    
                    // Wait for result
                    Optional<JsonStdioProtocol.SystemMessage> result = process.waitForMessage(JsonStdioProtocol.SystemMessageType.RESULT, 300);
                    if (result.isEmpty()) {
                        throw new RuntimeException("Timeout waiting for simulation completion");
                    }
                    
                    return result.get();
                    
                } catch (IOException e) {
                    throw new RuntimeException("IO error during model operation", e);
                }
            });
        }
    }
}