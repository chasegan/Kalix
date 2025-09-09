package com.kalix.gui.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Session manager using the new JSON-based kalixcli STDIO protocol.
 * Manages kalixcli sessions with lifecycle tracking and JSON message handling.
 */
public class JsonSessionManager {
    
    private static final AtomicLong SESSION_COUNTER = new AtomicLong(0);
    
    private final ProcessExecutor processExecutor;
    private final Map<String, JsonKalixSession> activeSessions = new ConcurrentHashMap<>();
    private final Consumer<String> statusUpdater;
    private final Consumer<SessionEvent> eventCallback;
    
    /**
     * Enhanced session states for JSON protocol.
     */
    public enum SessionState {
        STARTING,       // Session process is starting up
        READY,          // Session is ready to accept commands  
        BUSY,           // Session is actively executing a command
        ERROR,          // Session encountered an error
        TERMINATED      // Session has ended
    }
    
    /**
     * Events that can occur during session lifecycle.
     */
    public static class SessionEvent {
        private final String sessionId;
        private final String kalixSessionId;
        private final SessionState oldState;
        private final SessionState newState;
        private final String message;
        private final LocalDateTime timestamp;
        private final JsonStdioProtocol.SystemMessage originalMessage;
        
        public SessionEvent(String sessionId, String kalixSessionId, SessionState oldState, SessionState newState, String message, JsonStdioProtocol.SystemMessage originalMessage) {
            this.sessionId = sessionId;
            this.kalixSessionId = kalixSessionId;
            this.oldState = oldState;
            this.newState = newState;
            this.message = message;
            this.timestamp = LocalDateTime.now();
            this.originalMessage = originalMessage;
        }
        
        public String getSessionId() { return sessionId; }
        public String getKalixSessionId() { return kalixSessionId; }
        public SessionState getOldState() { return oldState; }
        public SessionState getNewState() { return newState; }
        public String getMessage() { return message; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public JsonStdioProtocol.SystemMessage getOriginalMessage() { return originalMessage; }
        
        @Override
        public String toString() {
            return String.format("SessionEvent[%s/%s: %s -> %s, %s]", sessionId, kalixSessionId, oldState, newState, message);
        }
    }
    
    /**
     * Represents an active JSON-based kalixcli session.
     */
    public static class JsonKalixSession {
        private final String sessionId; // Our internal session ID
        private final JsonInteractiveKalixProcess process;
        private final LocalDateTime startTime;
        private final SessionCommunicationLog communicationLog;
        
        // State from JSON protocol
        private volatile String kalixSessionId; // Session ID from kalixcli
        private volatile SessionState state;
        private volatile String lastMessage;
        private volatile LocalDateTime lastActivity;
        private volatile String currentCommand;
        private volatile boolean interruptible;
        private volatile JsonStdioProtocol.ReadyData readyData;
        
        public JsonKalixSession(String sessionId, JsonInteractiveKalixProcess process) {
            this.sessionId = sessionId;
            this.process = process;
            this.startTime = LocalDateTime.now();
            this.communicationLog = new SessionCommunicationLog(sessionId);
            this.state = SessionState.STARTING;
            this.lastActivity = LocalDateTime.now();
        }
        
        public String getSessionId() { return sessionId; }
        public String getKalixSessionId() { return kalixSessionId; }
        public JsonInteractiveKalixProcess getProcess() { return process; }
        public LocalDateTime getStartTime() { return startTime; }
        public SessionCommunicationLog getCommunicationLog() { return communicationLog; }
        public SessionState getState() { return state; }
        public String getLastMessage() { return lastMessage; }
        public LocalDateTime getLastActivity() { return lastActivity; }
        public String getCurrentCommand() { return currentCommand; }
        public boolean isInterruptible() { return interruptible; }
        public JsonStdioProtocol.ReadyData getReadyData() { return readyData; }
        
        public void updateFromMessage(JsonStdioProtocol.SystemMessage message) {
            this.kalixSessionId = message.getSessionId();
            this.lastActivity = LocalDateTime.now();
            
            JsonStdioProtocol.SystemMessageType messageType = message.getSystemMessageType();
            if (messageType != null) {
                switch (messageType) {
                    case READY:
                        this.state = SessionState.READY;
                        this.currentCommand = null;
                        this.interruptible = false;
                        try {
                            this.readyData = JsonStdioProtocol.extractData(message, JsonStdioProtocol.ReadyData.class);
                            this.lastMessage = "Ready - " + (readyData.getStatus() != null ? readyData.getStatus() : "available for commands");
                        } catch (Exception e) {
                            this.lastMessage = "Ready";
                        }
                        break;
                        
                    case BUSY:
                        this.state = SessionState.BUSY;
                        try {
                            JsonStdioProtocol.BusyData busyData = JsonStdioProtocol.extractData(message, JsonStdioProtocol.BusyData.class);
                            this.currentCommand = busyData.getExecutingCommand();
                            this.interruptible = busyData.isInterruptible();
                            this.lastMessage = "Busy executing: " + currentCommand;
                        } catch (Exception e) {
                            this.lastMessage = "Busy";
                        }
                        break;
                        
                    case PROGRESS:
                        // Don't change state, just update message
                        try {
                            JsonStdioProtocol.ProgressData progressData = JsonStdioProtocol.extractData(message, JsonStdioProtocol.ProgressData.class);
                            JsonStdioProtocol.ProgressInfo progress = progressData.getProgress();
                            this.lastMessage = String.format("Progress: %.1f%% - %s", 
                                progress.getPercentComplete(), 
                                progress.getCurrentStep() != null ? progress.getCurrentStep() : "running");
                        } catch (Exception e) {
                            this.lastMessage = "Progress update";
                        }
                        break;
                        
                    case RESULT:
                        this.state = SessionState.READY; // Will likely get ready message next, but assume ready
                        this.currentCommand = null;
                        this.interruptible = false;
                        this.lastMessage = "Command completed successfully";
                        break;
                        
                    case STOPPED:
                        this.state = SessionState.READY; // Will likely get ready message next
                        this.currentCommand = null;
                        this.interruptible = false;
                        this.lastMessage = "Command was stopped";
                        break;
                        
                    case ERROR:
                        this.state = SessionState.ERROR;
                        this.currentCommand = null;
                        this.interruptible = false;
                        this.lastMessage = "Error occurred";
                        break;
                        
                    case LOG:
                        // Don't change state for log messages
                        this.lastMessage = "Log message received";
                        break;
                }
            }
        }
        
        public void setState(SessionState newState, String message) {
            this.state = newState;
            this.lastMessage = message;
            this.lastActivity = LocalDateTime.now();
        }
        
        public boolean isActive() {
            return state != SessionState.TERMINATED && state != SessionState.ERROR;
        }
        
        public boolean isReady() {
            return state == SessionState.READY;
        }
        
        public boolean isBusy() {
            return state == SessionState.BUSY;
        }
        
        @Override
        public String toString() {
            return String.format("JsonKalixSession[id=%s, kalixId=%s, state=%s]", sessionId, kalixSessionId, state);
        }
    }
    
    /**
     * Configuration for starting a new JSON session.
     */
    public static class SessionConfig {
        private final String[] args;
        private String customSessionId;
        private Consumer<JsonStdioProtocol.SystemMessage> messageCallback;
        private Consumer<JsonStdioProtocol.ProgressInfo> progressCallback;
        
        public SessionConfig(String... args) {
            this.args = args;
        }
        
        public SessionConfig withSessionId(String sessionId) {
            this.customSessionId = sessionId;
            return this;
        }
        
        public SessionConfig onMessage(Consumer<JsonStdioProtocol.SystemMessage> callback) {
            this.messageCallback = callback;
            return this;
        }
        
        public SessionConfig onProgress(Consumer<JsonStdioProtocol.ProgressInfo> callback) {
            this.progressCallback = callback;
            return this;
        }
        
        // Getters
        public String[] getArgs() { return args; }
        public String getCustomSessionId() { return customSessionId; }
        public Consumer<JsonStdioProtocol.SystemMessage> getMessageCallback() { return messageCallback; }
        public Consumer<JsonStdioProtocol.ProgressInfo> getProgressCallback() { return progressCallback; }
    }
    
    /**
     * Creates a new JsonSessionManager.
     * 
     * @param processExecutor the process executor for CLI operations
     * @param statusUpdater callback for status updates
     * @param eventCallback callback for session events (can be null)
     */
    public JsonSessionManager(ProcessExecutor processExecutor, 
                             Consumer<String> statusUpdater,
                             Consumer<SessionEvent> eventCallback) {
        this.processExecutor = processExecutor;
        this.statusUpdater = statusUpdater;
        this.eventCallback = eventCallback != null ? eventCallback : event -> {}; // No-op if null
    }
    
    /**
     * Starts a new JSON-based kalixcli session.
     * 
     * @param cliPath path to kalixcli executable
     * @param config session configuration
     * @return CompletableFuture with session ID when session is ready
     */
    public CompletableFuture<String> startSession(Path cliPath, SessionConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionId = generateSessionId(config.getCustomSessionId());
                
                // Start JSON interactive process
                JsonInteractiveKalixProcess process = JsonInteractiveKalixProcess.start(
                    cliPath, processExecutor, config.getArgs());
                
                // Create session
                JsonKalixSession session = new JsonKalixSession(sessionId, process);
                activeSessions.put(sessionId, session);
                
                // Start monitoring the session
                monitorSession(session, config);
                
                // Wait for ready message with timeout
                Optional<JsonStdioProtocol.SystemMessage> readyMessage = process.waitForReady(30);
                if (readyMessage.isEmpty()) {
                    // Cleanup failed session
                    activeSessions.remove(sessionId);
                    process.close(true);
                    throw new RuntimeException("kalixcli did not become ready within 30 seconds");
                }
                
                // Update session with ready information
                session.updateFromMessage(readyMessage.get());
                
                // Notify session started and ready
                fireSessionEvent(sessionId, session.getKalixSessionId(), SessionState.STARTING, SessionState.READY, "Session started and ready", readyMessage.get());
                updateStatus("Started session: " + sessionId + " (kalixcli: " + session.getKalixSessionId() + ")");
                
                return sessionId;
                
            } catch (IOException e) {
                throw new RuntimeException("Failed to start session: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Sends a command to an active session.
     * 
     * @param sessionId the session to send command to
     * @param command the command to send
     * @param parameters command parameters
     * @return CompletableFuture that completes when command is sent
     */
    public CompletableFuture<Void> sendCommand(String sessionId, String command, Map<String, Object> parameters) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonKalixSession session = validateActiveSession(sessionId, "Send command");
                if (!session.isReady()) {
                    throw new IllegalStateException("Session not ready for commands: " + sessionId + " (state: " + session.getState() + ")");
                }
                
                // Log outgoing command
                String commandStr = command + (parameters != null && !parameters.isEmpty() ? " " + parameters : "");
                session.getCommunicationLog().logGuiToCli("JSON Command: " + commandStr);
                
                session.getProcess().sendCommand(command, parameters);
                updateStatus("Sent command to session " + sessionId + ": " + command);
            } catch (IOException e) {
                handleSessionError(sessionId, e, "Send command");
                throw new RuntimeException("Failed to send command to session " + sessionId, e);
            }
        });
    }
    
    /**
     * Sends a command without parameters.
     * 
     * @param sessionId the session to send command to
     * @param command the command to send
     * @return CompletableFuture that completes when command is sent
     */
    public CompletableFuture<Void> sendCommand(String sessionId, String command) {
        return sendCommand(sessionId, command, null);
    }
    
    /**
     * Loads a model from text into a session.
     * 
     * @param sessionId the session to load model into
     * @param modelText the model definition text
     * @return CompletableFuture that completes when model is loaded
     */
    public CompletableFuture<Void> loadModelString(String sessionId, String modelText) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonKalixSession session = validateActiveSession(sessionId, "Load model");
                if (!session.isReady()) {
                    throw new IllegalStateException("Session not ready for commands: " + sessionId + " (state: " + session.getState() + ")");
                }
                
                // Log outgoing model definition (truncated for log readability)
                String logMessage = modelText.length() > 100 ? 
                    modelText.substring(0, 100) + "... (" + modelText.length() + " chars total)" : 
                    modelText;
                session.getCommunicationLog().logGuiToCli("Load model string: " + logMessage);
                
                session.getProcess().sendModelDefinition(modelText);
                updateStatus("Loading model into session: " + sessionId);
            } catch (IOException e) {
                handleSessionError(sessionId, e, "Load model");
                throw new RuntimeException("Failed to load model into session " + sessionId, e);
            }
        });
    }
    
    /**
     * Loads a model from file path.
     * 
     * @param sessionId the session to load model into
     * @param modelPath the path to model file
     * @return CompletableFuture that completes when model is loaded
     */
    public CompletableFuture<Void> loadModelFile(String sessionId, String modelPath) {
        return sendCommand(sessionId, "load_model_file", Map.of("model_path", modelPath));
    }
    
    /**
     * Runs simulation in a session.
     * 
     * @param sessionId the session to run simulation in
     * @return CompletableFuture that completes when command is sent
     */
    public CompletableFuture<Void> runSimulation(String sessionId) {
        return sendCommand(sessionId, "run_simulation");
    }
    
    /**
     * Stops the current operation in a session.
     * 
     * @param sessionId the session to stop
     * @param reason the reason for stopping
     * @return CompletableFuture that completes when stop is sent
     */
    public CompletableFuture<Void> stopOperation(String sessionId, String reason) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonKalixSession session = validateActiveSession(sessionId, "Stop operation");
                if (!session.isBusy()) {
                    throw new IllegalStateException("Session not busy: " + sessionId + " (state: " + session.getState() + ")");
                }
                
                if (!session.isInterruptible()) {
                    throw new IllegalStateException("Current operation is not interruptible");
                }
                
                session.getCommunicationLog().logGuiToCli("Stop request: " + reason);
                session.getProcess().sendStop(reason);
                updateStatus("Stopping operation in session: " + sessionId);
            } catch (IOException e) {
                handleSessionError(sessionId, e, "Stop operation");
                throw new RuntimeException("Failed to stop operation in session " + sessionId, e);
            }
        });
    }
    
    /**
     * Sends a query to a session.
     * 
     * @param sessionId the session to query
     * @param queryType the type of query
     * @param parameters query parameters
     * @return CompletableFuture that completes when query is sent
     */
    public CompletableFuture<Void> sendQuery(String sessionId, String queryType, Map<String, Object> parameters) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonKalixSession session = validateActiveSession(sessionId, "Send query");
                
                session.getCommunicationLog().logGuiToCli("Query: " + queryType + 
                    (parameters != null && !parameters.isEmpty() ? " " + parameters : ""));
                session.getProcess().sendQuery(queryType, parameters);
                updateStatus("Sent query to session " + sessionId + ": " + queryType);
            } catch (IOException e) {
                handleSessionError(sessionId, e, "Send query");
                throw new RuntimeException("Failed to send query to session " + sessionId, e);
            }
        });
    }
    
    /**
     * Sends a query without parameters.
     * 
     * @param sessionId the session to query
     * @param queryType the type of query
     * @return CompletableFuture that completes when query is sent
     */
    public CompletableFuture<Void> sendQuery(String sessionId, String queryType) {
        return sendQuery(sessionId, queryType, null);
    }
    
    /**
     * Terminates a session gracefully.
     * 
     * @param sessionId the session to terminate
     * @return CompletableFuture that completes when session is terminated
     */
    public CompletableFuture<Void> terminateSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            JsonKalixSession session = activeSessions.get(sessionId);
            if (session != null) {
                try {
                    SessionState oldState = session.getState();
                    session.setState(SessionState.TERMINATED, "Session terminated by user");
                    
                    // Send terminate message if process is still alive
                    if (session.getProcess().isRunning()) {
                        session.getProcess().terminate();
                    }
                    session.getProcess().close();
                    
                    fireSessionEvent(sessionId, session.getKalixSessionId(), oldState, SessionState.TERMINATED, "Session terminated", null);
                    updateStatus("Terminated session: " + sessionId);
                } catch (IOException e) {
                    // Force close if terminate fails
                    session.getProcess().close(true);
                    session.setState(SessionState.TERMINATED, "Session terminated (forced)");
                }
            }
        });
    }
    
    /**
     * Gets information about a session.
     * 
     * @param sessionId the session ID
     * @return session information if found
     */
    public Optional<JsonKalixSession> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }
    
    /**
     * Gets all active sessions.
     * 
     * @return map of session ID to session info
     */
    public Map<String, JsonKalixSession> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }
    
    /**
     * Removes a terminated session from the session list.
     * 
     * @param sessionId the session to remove
     * @return CompletableFuture that completes when session is removed
     */
    public CompletableFuture<Void> removeSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            JsonKalixSession session = activeSessions.get(sessionId);
            if (session != null) {
                // Only allow removal of terminated or error sessions
                if (session.getState() == SessionState.TERMINATED || session.getState() == SessionState.ERROR) {
                    activeSessions.remove(sessionId);
                    updateStatus("Removed session from list: " + sessionId);
                } else {
                    throw new IllegalStateException("Cannot remove active session: " + sessionId + " (state: " + session.getState() + ")");
                }
            }
        });
    }
    
    /**
     * Monitors a JSON session for messages and state changes.
     */
    private void monitorSession(JsonKalixSession session, SessionConfig config) {
        String sessionId = session.getSessionId();
        
        // Set up message handler
        JsonInteractiveKalixProcess.SystemMessageHandler messageHandler = (message) -> {
            try {
                // Log raw message
                session.getCommunicationLog().logCliToGuiStdout("JSON: " + message.toString());
                
                // Update session state
                SessionState oldState = session.getState();
                session.updateFromMessage(message);
                
                // Fire event if state changed
                if (oldState != session.getState()) {
                    fireSessionEvent(sessionId, session.getKalixSessionId(), oldState, session.getState(), 
                        session.getLastMessage(), message);
                }
                
                // Handle specific message types
                JsonStdioProtocol.SystemMessageType messageType = message.getSystemMessageType();
                if (messageType != null) {
                    switch (messageType) {
                        case PROGRESS:
                            if (config.getProgressCallback() != null) {
                                try {
                                    JsonStdioProtocol.ProgressData progressData = JsonStdioProtocol.extractData(message, JsonStdioProtocol.ProgressData.class);
                                    config.getProgressCallback().accept(progressData.getProgress());
                                } catch (Exception e) {
                                    // Ignore progress parsing errors
                                }
                            }
                            break;
                            
                        case ERROR:
                            updateStatus("Session " + sessionId + " error: " + session.getLastMessage());
                            break;
                            
                        default:
                            break;
                    }
                }
                
                // Call general message callback
                if (config.getMessageCallback() != null) {
                    config.getMessageCallback().accept(message);
                }
                
            } catch (Exception e) {
                System.err.println("Error processing session message: " + e.getMessage());
            }
        };
        
        // Start monitoring
        CompletableFuture<Void> monitor = session.getProcess().monitorMessages(messageHandler);
        
        // Handle monitoring completion/failure
        monitor.whenComplete((result, throwable) -> {
            if (throwable != null) {
                handleSessionError(sessionId, new RuntimeException(throwable), "message monitoring");
            } else {
                // Monitoring ended normally (process terminated)
                SessionState oldState = session.getState();
                if (oldState != SessionState.TERMINATED) {
                    session.setState(SessionState.TERMINATED, "Process terminated");
                    fireSessionEvent(sessionId, session.getKalixSessionId(), oldState, SessionState.TERMINATED, "Process terminated", null);
                }
            }
        });
    }
    
    /**
     * Fires a session event to registered callbacks.
     */
    private void fireSessionEvent(String sessionId, String kalixSessionId, SessionState oldState, SessionState newState, String message, JsonStdioProtocol.SystemMessage originalMessage) {
        if (eventCallback != null) {
            try {
                eventCallback.accept(new SessionEvent(sessionId, kalixSessionId, oldState, newState, message, originalMessage));
            } catch (Exception e) {
                // Don't let callback exceptions break session management
                System.err.println("Error in session event callback: " + e.getMessage());
            }
        }
    }
    
    /**
     * Updates status via callback.
     */
    private void updateStatus(String message) {
        if (statusUpdater != null) {
            try {
                statusUpdater.accept(message);
            } catch (Exception e) {
                // Don't let callback exceptions break session management
                System.err.println("Error in status update callback: " + e.getMessage());
            }
        }
    }
    
    /**
     * Handles session errors consistently.
     */
    private void handleSessionError(String sessionId, Exception e, String operation) {
        JsonKalixSession session = activeSessions.get(sessionId);
        if (session != null) {
            SessionState oldState = session.getState();
            session.setState(SessionState.ERROR, operation + " failed: " + e.getMessage());
            fireSessionEvent(sessionId, session.getKalixSessionId(), oldState, SessionState.ERROR, e.getMessage(), null);
        }
        updateStatus("Session " + sessionId + " error: " + operation + " failed");
    }
    
    /**
     * Validates session exists and is active.
     */
    private JsonKalixSession validateActiveSession(String sessionId, String operation) {
        JsonKalixSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        if (!session.isActive()) {
            throw new IllegalStateException(operation + " failed: Session not active: " + sessionId + " (state: " + session.getState() + ")");
        }
        return session;
    }
    
    /**
     * Generates a unique session ID.
     */
    private String generateSessionId(String customId) {
        if (customId != null && !customId.trim().isEmpty()) {
            return customId.trim();
        }
        
        return "gui-session-" + SESSION_COUNTER.incrementAndGet();
    }
    
    /**
     * Shuts down the session manager and terminates all active sessions.
     */
    public void shutdown() {
        updateStatus("Shutting down JSON session manager...");
        
        // Terminate all active sessions
        activeSessions.values().parallelStream().forEach(session -> {
            try {
                session.getProcess().close();
            } catch (Exception e) {
                System.err.println("Error closing session " + session.getSessionId() + ": " + e.getMessage());
            }
        });
        
        activeSessions.clear();
        updateStatus("JSON session manager shutdown complete");
    }
}