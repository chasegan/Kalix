package com.kalix.ide.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Manages kalixcli sessions with lifecycle tracking and state management.
 * Handles both long-running model sessions and short-lived command executions.
 */
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    
    private static final AtomicLong SESSION_COUNTER = new AtomicLong(0);
    
    private final ProcessExecutor processExecutor;
    private final Map<String, KalixSession> activeSessions = new ConcurrentHashMap<>();
    private final Consumer<String> statusUpdater;
    private final Consumer<SessionEvent> eventCallback;
    private Consumer<String> timeSeriesResponseHandler;

    // Multi-listener support for session events
    private final List<Consumer<SessionEvent>> sessionEventListeners = new CopyOnWriteArrayList<>();
    
    /**
     * States a kalixcli session can be in.
     */
    public enum SessionState {
        STARTING,       // Session process is starting up
        RUNNING,        // Session is actively executing (e.g., running simulation)
        READY,          // Session completed main task, ready for queries
        COMPLETING,     // Session is finishing up and will terminate
        ERROR,          // Session encountered an error
        TERMINATED      // Session has ended
    }
    
    
    /**
     * Events that can occur during session lifecycle.
     */
    public static class SessionEvent {
        private final String sessionKey;
        private final SessionState oldState;
        private final SessionState newState;
        private final String message;
        private final LocalDateTime timestamp;
        
        public SessionEvent(String sessionKey, SessionState oldState, SessionState newState, String message) {
            this.sessionKey = sessionKey;
            this.oldState = oldState;
            this.newState = newState;
            this.message = message;
            this.timestamp = LocalDateTime.now();
        }

        public String getSessionKey() { return sessionKey; }
        public SessionState getOldState() { return oldState; }
        public SessionState getNewState() { return newState; }
        public String getMessage() { return message; }
        public LocalDateTime getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("SessionEvent[%s: %s -> %s, %s]", sessionKey, oldState, newState, message);
        }
    }
    
    /**
     * Represents an active kalixcli session.
     */
    public static class KalixSession {
        private final String sessionKey; // Internal IDE identifier
        private final KalixStdioSession process;
        private final LocalDateTime startTime;
        private final SessionCommunicationLog communicationLog;
        private volatile SessionState state;
        private volatile String lastMessage;
        private volatile LocalDateTime lastActivity;
        private volatile AbstractSessionProgram activeProgram;
        private volatile String kalixcliUid;
        
        public KalixSession(String sessionKey, KalixStdioSession process) {
            this.sessionKey = sessionKey;
            this.process = process;
            this.startTime = LocalDateTime.now();
            this.communicationLog = new SessionCommunicationLog(sessionKey);
            this.state = SessionState.STARTING;
            this.lastActivity = LocalDateTime.now();
        }

        public String getSessionKey() { return sessionKey; } // Internal IDE identifier
        public KalixStdioSession getProcess() { return process; }
        public LocalDateTime getStartTime() { return startTime; }
        public SessionCommunicationLog getCommunicationLog() { return communicationLog; }
        public SessionState getState() { return state; }
        public String getLastMessage() { return lastMessage; }
        public LocalDateTime getLastActivity() { return lastActivity; }
        public String getKalixcliUid() { return kalixcliUid; }
        public void setKalixcliUid(String kalixcliUid) { this.kalixcliUid = kalixcliUid; }
        
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
        
        public AbstractSessionProgram getActiveProgram() { return activeProgram; }
        public void setActiveProgram(AbstractSessionProgram program) { this.activeProgram = program; }
        
        @Override
        public String toString() {
            return String.format("KalixSession[key=%s, state=%s]", sessionKey, state);
        }
    }
    
    /**
     * Configuration for starting a new session.
     */
    public static class SessionConfig {
        private final String[] args;
        private Path workingDirectory;

        public SessionConfig(String... args) {
            this.args = args;
        }

        /**
         * Sets the working directory for the session.
         *
         * @param workingDirectory the working directory
         * @return this config for chaining
         */
        public SessionConfig workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        // Getters
        public String[] getArgs() { return args; }
        public Path getWorkingDirectory() { return workingDirectory; }
    }
    
    /**
     * Creates a new SessionManager.
     *
     * @param processExecutor the process executor for CLI operations
     * @param statusUpdater callback for status updates
     * @param eventCallback callback for session events (can be null)
     */
    public SessionManager(ProcessExecutor processExecutor,
                         Consumer<String> statusUpdater,
                         Consumer<SessionEvent> eventCallback) {
        this.processExecutor = processExecutor;
        this.statusUpdater = statusUpdater;
        this.eventCallback = eventCallback != null ? eventCallback : event -> {}; // No-op if null
    }

    /**
     * Sets the time series response handler for processing get_result JSON responses.
     *
     * @param handler callback to handle JSON responses containing time series data
     */
    public void setTimeSeriesResponseHandler(Consumer<String> handler) {
        this.timeSeriesResponseHandler = handler;
    }
    
    /**
     * Starts a new kalixcli session.
     *
     * @param cliPath path to kalixcli executable
     * @param config session configuration
     * @return CompletableFuture with session key when session is started
     */
    public CompletableFuture<String> startSession(Path cliPath, SessionConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionKey = generateSessionKey();

                // Start interactive process with working directory from config
                KalixStdioSession process = KalixStdioSession.start(
                    cliPath, processExecutor, config.getWorkingDirectory(), config.getArgs());

                // Create session
                KalixSession session = new KalixSession(sessionKey, process);
                activeSessions.put(sessionKey, session);

                // Start monitoring the session
                monitorSession(session, config);

                // Notify session started
                fireSessionEvent(sessionKey, null, SessionState.STARTING, "Session started");
                updateStatus("Started session: " + sessionKey);

                return sessionKey;

            } catch (IOException e) {
                throw new RuntimeException("Failed to start session: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Sends a command to an active session.
     * 
     * @param sessionKey the session to send command to
     * @param command the command to send
     * @return CompletableFuture that completes when command is sent
     */
    public CompletableFuture<Void> sendCommand(String sessionKey, String command) {
        return CompletableFuture.runAsync(() -> {
            try {
                KalixSession session = validateActiveSession(sessionKey);
                // Log outgoing command first
                session.getCommunicationLog().logIdeToCli(command);
                session.getProcess().sendCommand(command);
                session.setState(SessionState.RUNNING, "Executing command: " + command);
                updateStatus("Sent command to session " + sessionKey + ": " + command);
            } catch (IOException e) {
                handleSessionError(sessionKey, e, "Send command");
                throw new RuntimeException("Failed to send command to session " + sessionKey, e);
            }
        }, processExecutor.getExecutorService());
    }
    
    
    /**
     * Requests results from a ready session.
     * 
     * @param sessionKey the session to request results from
     * @param resultType the type of results to request
     * @return CompletableFuture with the results
     */
    public CompletableFuture<String> requestResults(String sessionKey, String resultType) {
        return CompletableFuture.supplyAsync(() -> {
            KalixSession session = activeSessions.get(sessionKey);
            if (session == null) {
                throw new IllegalArgumentException("Session not found: " + sessionKey);
            }
            
            if (!session.isReady()) {
                throw new IllegalStateException("Session not ready for queries: " + sessionKey + " (state: " + session.getState() + ")");
            }
            
            try {
                String command = KalixStdioProtocol.formatResultRequest(resultType, null);
                session.getProcess().sendCommand(command);
                
                // Wait for response (this is a simplified implementation)
                // In practice, you might want more sophisticated result parsing
                Optional<String> response = session.getProcess().readOutputLine();
                return response.orElse("");
                
            } catch (IOException e) {
                session.setState(SessionState.ERROR, "Failed to request results: " + e.getMessage());
                throw new RuntimeException("Failed to request results from session " + sessionKey, e);
            }
        });
    }
    
    /**
     * Terminates a session gracefully.
     * 
     * @param sessionKey the session to terminate
     * @return CompletableFuture that completes when session is terminated
     */
    public CompletableFuture<Void> terminateSession(String sessionKey) {
        return CompletableFuture.runAsync(() -> {
            KalixSession session = activeSessions.get(sessionKey);
            if (session != null) {
                SessionState oldState = session.getState();
                session.setState(SessionState.TERMINATED, "Session terminated by user");
                session.getProcess().close(true); // Force kill to ensure termination
                // Keep session in activeSessions map for visibility, don't remove it

                fireSessionEvent(sessionKey, oldState, SessionState.TERMINATED, "Session terminated");
                updateStatus("Terminated session: " + sessionKey);
            }
        });
    }
    
    /**
     * Gets information about an active session.
     * 
     * @param sessionKey the session key
     * @return session information if found
     */
    public Optional<KalixSession> getSession(String sessionKey) {
        return Optional.ofNullable(activeSessions.get(sessionKey));
    }
    
    /**
     * Gets all active sessions.
     * 
     * @return map of sessionKey to session info
     */
    public Map<String, KalixSession> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }

    /**
     * Registers a listener to receive session state change events.
     * Listeners are called from background threads and must handle EDT marshaling if needed.
     *
     * @param listener Consumer that will receive SessionEvent notifications
     */
    public void addSessionEventListener(Consumer<SessionEvent> listener) {
        if (listener != null) {
            sessionEventListeners.add(listener);
        }
    }

    /**
     * Unregisters a previously registered session event listener.
     *
     * @param listener The listener to remove
     */
    public void removeSessionEventListener(Consumer<SessionEvent> listener) {
        sessionEventListeners.remove(listener);
    }

    /**
     * Removes a terminated session from the session list.
     * This is for cleanup purposes - only works on TERMINATED or ERROR sessions.
     * 
     * @param sessionKey the session to remove
     * @return CompletableFuture that completes when session is removed
     */
    public CompletableFuture<Void> removeSession(String sessionKey) {
        return CompletableFuture.runAsync(() -> {
            KalixSession session = activeSessions.get(sessionKey);
            if (session != null) {
                // Only allow removal of terminated or error sessions
                if (session.getState() == SessionState.TERMINATED || session.getState() == SessionState.ERROR) {
                    activeSessions.remove(sessionKey);
                    updateStatus("Removed session from list: " + sessionKey);
                } else {
                    throw new IllegalStateException("Cannot remove active session: " + sessionKey + " (state: " + session.getState() + ")");
                }
            }
        });
    }
    
    /**
     * Monitors a session for output and state changes on both stdout and stderr.
     */
    private void monitorSession(KalixSession session, SessionConfig config) {
        String sessionKey = session.getSessionKey();
        
        // Monitor stdout
        CompletableFuture<Void> stdoutMonitor = CompletableFuture.runAsync(() -> {
            try {
                while (session.getProcess().isRunning() && session.isActive()) {
                    Optional<String> outputLine = session.getProcess().readOutputLine();
                    
                    if (outputLine.isPresent()) {
                        String line = outputLine.get();
                        // Log the raw message first
                        session.getCommunicationLog().logCliToIdeStdout(line);
                        processSessionOutput(session, line, config);
                    }
                    Thread.sleep(16); // ~60 FPS polling rate for responsive progress updates
                }
            } catch (IOException | InterruptedException e) {
                handleSessionError(sessionKey, e, "stdout monitoring");
            }
        }, processExecutor.getExecutorService());
        
        // Monitor stderr
        CompletableFuture<Void> stderrMonitor = CompletableFuture.runAsync(() -> {
            try {
                while (session.getProcess().isRunning() && session.isActive()) {
                    Optional<String> errorLine = session.getProcess().readErrorLine();
                    
                    if (errorLine.isPresent()) {
                        String line = errorLine.get();
                        // Log the raw message first
                        session.getCommunicationLog().logCliToIdeStderr(line);
                        processSessionError(session, line, config);
                    }
                    Thread.sleep(16); // ~60 FPS polling rate for responsive progress updates
                }
            } catch (IOException | InterruptedException e) {
                handleSessionError(sessionKey, e, "stderr monitoring");
            }
        }, processExecutor.getExecutorService());
        
        // Combine both monitoring futures - session completes when both streams are done
        CompletableFuture.allOf(stdoutMonitor, stderrMonitor)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    handleSessionError(sessionKey, new RuntimeException(throwable), "session monitoring");
                }
            });
    }
    
    /**
     * Processes output from a session and updates state accordingly.
     * Handles JSON protocol messages from kalixcli.
     */
    private void processSessionOutput(KalixSession session, String line, SessionConfig config) {

        // Check for JSON protocol messages
        if (JsonStdioProtocol.looksLikeCompactJson(line)) {
            Optional<JsonMessage.SystemMessage> jsonMsg = JsonStdioProtocol.parseSystemMessage(line);
            if (jsonMsg.isPresent())
                handleJsonProtocolMessage(session, jsonMsg.get(), config);
        }
    }
    
    /**
     * Processes error output from a session.
     * Handles stderr messages which may contain important error information or diagnostics.
     */
    private void processSessionError(KalixSession session, String errorLine, SessionConfig config) {
        String sessionKey = session.getSessionKey();
        
        // Always log stderr as it contains important error information
        updateStatus("Session " + sessionKey + " stderr: " + errorLine);
        
        // Check if this is a critical error that should terminate the session
        if (isCriticalError(errorLine)) {
            SessionState oldState = session.getState();
            session.setState(SessionState.ERROR, "Critical error: " + errorLine);
            fireSessionEvent(sessionKey, oldState, SessionState.ERROR, "Critical error from kalixcli: " + errorLine);
            return;
        }
        
        // Check for JSON protocol messages on stderr (some CLIs send protocol info there)
        if (JsonStdioProtocol.looksLikeCompactJson(errorLine)) {
            Optional<JsonMessage.SystemMessage> jsonMsg = JsonStdioProtocol.parseSystemMessage(errorLine);
            if (jsonMsg.isPresent()) {
                handleJsonProtocolMessage(session, jsonMsg.get(), config);
            }
        }
    }
    
    /**
     * Determines if an error line indicates a critical error that should terminate the session.
     */
    private boolean isCriticalError(String errorLine) {
        String lowerError = errorLine.toLowerCase();
        return lowerError.contains("fatal") || 
               lowerError.contains("critical") ||
               lowerError.contains("exception") ||
               lowerError.contains("error:") ||
               lowerError.contains("failed to") ||
               lowerError.matches(".*error.*\\d+.*"); // Pattern like "Error 123"
    }
    
    /**
     * Handles JSON protocol messages by delegating to active programs or handling generically.
     */
    private void handleJsonProtocolMessage(KalixSession session, JsonMessage.SystemMessage message, SessionConfig config) {
        String sessionKey = session.getSessionKey();

        // Store kalixcli_uid from the first message we receive
        if (session.getKalixcliUid() == null && message.getSessionId() != null) {
            session.setKalixcliUid(message.getSessionId());
        }

        // Check if this is a get_result response and route to TimeSeriesRequestManager
        if (isTimeSeriesResponse(message)) {
            if (timeSeriesResponseHandler != null) {
                try {
                    // Convert the message back to JSON string for the TimeSeriesRequestManager
                    String jsonResponse = convertMessageToJsonString(message);
                    timeSeriesResponseHandler.accept(jsonResponse);
                    return; // Message was handled by TimeSeriesRequestManager
                } catch (Exception e) {
                    logger.error("Failed to handle timeseries response", e);
                }
            } else {
                logger.warn("TimeSeriesResponseHandler is null, cannot route response");
            }
        }

        // First try to delegate to any active program
        if (session.getActiveProgram() != null && session.getActiveProgram().isActive()) {
            boolean handled = session.getActiveProgram().handleMessage(message);
            if (handled) {
                return; // Message was handled by the program
            }
        }

        // Handle generic messages that aren't part of a specific program
        JsonStdioTypes.SystemMessageType msgType = message.systemMessageType();
        if (msgType == null) {
            updateStatus("Unknown JSON message type from session " + sessionKey);
            return;
        }

        switch (msgType) {
            case LOG:
                // Generic log message
                try {
                    String logMsg = message.getResult().asText();
                    updateStatus("Session " + sessionKey + " log: " + logMsg);
                } catch (Exception e) {
                    updateStatus("Session " + sessionKey + " log message received");
                }
                break;

            case READY:
                // Session is ready for commands (generic session state)
                SessionState oldState = session.getState();
                session.setState(SessionState.READY, "Session ready for commands");
                fireSessionEvent(sessionKey, oldState, SessionState.READY, "Session ready");
                // Don't show session ready message in status bar as it's not useful to users
                break;

            default:
                // Other message types should be handled by programs
                updateStatus("Unhandled JSON message type from session " + sessionKey + ": " + msgType);
                break;
        }
    }

    /**
     * Checks if a JSON message is a get_result response that should be handled by TimeSeriesRequestManager.
     */
    private boolean isTimeSeriesResponse(JsonMessage.SystemMessage message) {
        try {
            // Check if this is a result message
            JsonStdioTypes.SystemMessageType msgType = message.systemMessageType();
            if (msgType != JsonStdioTypes.SystemMessageType.RESULT) {
                return false;
            }

            // Check if the result is for a get_result command
            String command = message.getCommand();
            return "get_result".equals(command);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts a JsonMessage.SystemMessage back to a JSON string for processing by TimeSeriesRequestManager.
     */
    private String convertMessageToJsonString(JsonMessage.SystemMessage message) {
        try {
            // Use Jackson ObjectMapper to convert the message back to JSON string
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert message to JSON string", e);
        }
    }

    /**
     * Fires a session event to registered callbacks.
     */
    private void fireSessionEvent(String sessionKey, SessionState oldState, SessionState newState, String message) {
        SessionEvent event = new SessionEvent(sessionKey, oldState, newState, message);

        // Fire to legacy single callback (backwards compatible)
        if (eventCallback != null) {
            try {
                eventCallback.accept(event);
            } catch (Exception e) {
                // Don't let callback exceptions break session management
                logger.warn("Error in session event callback: {}", e.getMessage());
            }
        }

        // Fire to all registered listeners
        for (Consumer<SessionEvent> listener : sessionEventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Don't let listener exceptions break session management or other listeners
                logger.warn("Error in session event listener: {}", e.getMessage(), e);
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
                logger.warn("Error in status update callback: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Handles session errors consistently.
     */
    private void handleSessionError(String sessionKey, Exception e, String operation) {
        KalixSession session = activeSessions.get(sessionKey);
        if (session != null) {
            SessionState oldState = session.getState();
            session.setState(SessionState.ERROR, operation + " failed: " + e.getMessage());
            fireSessionEvent(sessionKey, oldState, SessionState.ERROR, e.getMessage());
        }
        updateStatus("Session " + sessionKey + " error: " + operation + " failed");
    }
    
    /**
     * Validates session exists and is active.
     */
    private KalixSession validateActiveSession(String sessionKey) {
        KalixSession session = activeSessions.get(sessionKey);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionKey);
        }
        if (!session.isActive()) {
            throw new IllegalStateException("Send command failed. Session not active: " + sessionKey + " (state: " + session.getState() + ")");
        }
        return session;
    }
    
    /**
     * Generates a unique sessionKey.
     */
    private String generateSessionKey() {
        return "session-" + SESSION_COUNTER.incrementAndGet();
    }
    
    /**
     * Shuts down the session manager and terminates all active sessions.
     */
    public void shutdown() {
        updateStatus("Shutting down session manager...");

        // Terminate all active sessions with brutal force for fast shutdown
        activeSessions.values().parallelStream().forEach(session -> {
            try {
                session.getProcess().close(true); // Force kill immediately
            } catch (Exception e) {
                logger.error("Error closing session {}: {}", session.getSessionKey(), e.getMessage());
            }
        });

        activeSessions.clear();
        updateStatus("Session manager shutdown complete");
    }
}