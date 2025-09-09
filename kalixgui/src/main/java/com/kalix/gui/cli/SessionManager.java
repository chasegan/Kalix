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
 * Manages kalixcli sessions with lifecycle tracking and state management.
 * Handles both long-running model sessions and short-lived command executions.
 */
public class SessionManager {
    
    private static final AtomicLong SESSION_COUNTER = new AtomicLong(0);
    
    private final ProcessExecutor processExecutor;
    private final Map<String, KalixSession> activeSessions = new ConcurrentHashMap<>();
    private final Consumer<String> statusUpdater;
    private final Consumer<SessionEvent> eventCallback;
    
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
        private final String sessionId;
        private final SessionState oldState;
        private final SessionState newState;
        private final String message;
        private final LocalDateTime timestamp;
        
        public SessionEvent(String sessionId, SessionState oldState, SessionState newState, String message) {
            this.sessionId = sessionId;
            this.oldState = oldState;
            this.newState = newState;
            this.message = message;
            this.timestamp = LocalDateTime.now();
        }
        
        public String getSessionId() { return sessionId; }
        public SessionState getOldState() { return oldState; }
        public SessionState getNewState() { return newState; }
        public String getMessage() { return message; }
        public LocalDateTime getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("SessionEvent[%s: %s -> %s, %s]", sessionId, oldState, newState, message);
        }
    }
    
    /**
     * Represents an active kalixcli session.
     */
    public static class KalixSession {
        private final String sessionId;
        private final InteractiveKalixProcess process;
        private final LocalDateTime startTime;
        private final SessionCommunicationLog communicationLog;
        private volatile SessionState state;
        private volatile String lastMessage;
        private volatile LocalDateTime lastActivity;
        
        public KalixSession(String sessionId, InteractiveKalixProcess process) {
            this.sessionId = sessionId;
            this.process = process;
            this.startTime = LocalDateTime.now();
            this.communicationLog = new SessionCommunicationLog(sessionId);
            this.state = SessionState.STARTING;
            this.lastActivity = LocalDateTime.now();
        }
        
        public String getSessionId() { return sessionId; }
        public InteractiveKalixProcess getProcess() { return process; }
        public LocalDateTime getStartTime() { return startTime; }
        public SessionCommunicationLog getCommunicationLog() { return communicationLog; }
        public SessionState getState() { return state; }
        public String getLastMessage() { return lastMessage; }
        public LocalDateTime getLastActivity() { return lastActivity; }
        
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
        
        @Override
        public String toString() {
            return String.format("KalixSession[id=%s, state=%s]", sessionId, state);
        }
    }
    
    /**
     * Configuration for starting a new session.
     */
    public static class SessionConfig {
        private final String[] args;
        private String customSessionId;
        private Consumer<ProgressParser.ProgressInfo> progressCallback;
        
        public SessionConfig(String... args) {
            this.args = args;
        }
        
        public SessionConfig withSessionId(String sessionId) {
            this.customSessionId = sessionId;
            return this;
        }
        
        public SessionConfig onProgress(Consumer<ProgressParser.ProgressInfo> callback) {
            this.progressCallback = callback;
            return this;
        }
        
        // Getters
        public String[] getArgs() { return args; }
        public String getCustomSessionId() { return customSessionId; }
        public Consumer<ProgressParser.ProgressInfo> getProgressCallback() { return progressCallback; }
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
     * Starts a new kalixcli session.
     * 
     * @param cliPath path to kalixcli executable
     * @param config session configuration
     * @return CompletableFuture with session ID when session is started
     */
    public CompletableFuture<String> startSession(Path cliPath, SessionConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionId = generateSessionId(config.getCustomSessionId());
                
                // Start interactive process
                InteractiveKalixProcess process = InteractiveKalixProcess.start(
                    cliPath, processExecutor, config.getArgs());
                
                // Create session
                KalixSession session = new KalixSession(sessionId, process);
                activeSessions.put(sessionId, session);
                
                // Start monitoring the session
                monitorSession(session, config);
                
                // Notify session started
                fireSessionEvent(sessionId, null, SessionState.STARTING, "Session started");
                updateStatus("Started session: " + sessionId);
                
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
     * @return CompletableFuture that completes when command is sent
     */
    public CompletableFuture<Void> sendCommand(String sessionId, String command) {
        return CompletableFuture.runAsync(() -> {
            try {
                KalixSession session = validateActiveSession(sessionId, "Send command");
                // Log outgoing command first
                session.getCommunicationLog().logGuiToCli(command);
                session.getProcess().sendCommand(command);
                session.setState(SessionState.RUNNING, "Executing command: " + command);
                updateStatus("Sent command to session " + sessionId + ": " + command);
            } catch (IOException e) {
                handleSessionError(sessionId, e, "Send command");
                throw new RuntimeException("Failed to send command to session " + sessionId, e);
            }
        });
    }
    
    /**
     * Sends model definition to a session (for in-memory model execution).
     * Uses the JSON protocol with load_model_string command.
     * 
     * @param sessionId the session to send model to
     * @param modelText the model definition text
     * @return CompletableFuture that completes when model is sent
     */
    public CompletableFuture<Void> sendModelDefinition(String sessionId, String modelText) {
        return CompletableFuture.runAsync(() -> {
            try {
                KalixSession session = activeSessions.get(sessionId); // Don't validate active since we're just starting
                if (session == null) {
                    throw new IllegalArgumentException("Session not found: " + sessionId);
                }
                
                // Create JSON command using the new protocol
                String jsonCommand = JsonStdioProtocol.Commands.loadModelString(modelText);
                
                // Log the raw JSON command that will be sent to STDIN
                session.getCommunicationLog().logGuiToCli(jsonCommand);
                
                session.getProcess().sendCommand(jsonCommand);
                
                session.setState(SessionState.RUNNING, "Running model from memory");
                updateStatus("Sent model definition to session: " + sessionId);
            } catch (IOException e) {
                handleSessionError(sessionId, e, "Send model definition");
                throw new RuntimeException("Failed to send model to session " + sessionId, e);
            }
        });
    }
    
    /**
     * Requests results from a ready session.
     * 
     * @param sessionId the session to request results from
     * @param resultType the type of results to request
     * @return CompletableFuture with the results
     */
    public CompletableFuture<String> requestResults(String sessionId, String resultType) {
        return CompletableFuture.supplyAsync(() -> {
            KalixSession session = activeSessions.get(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            
            if (!session.isReady()) {
                throw new IllegalStateException("Session not ready for queries: " + sessionId + " (state: " + session.getState() + ")");
            }
            
            try {
                String command = KalixCliProtocol.formatResultRequest(resultType, null);
                session.getProcess().sendCommand(command);
                
                // Wait for response (this is a simplified implementation)
                // In practice, you might want more sophisticated result parsing
                Optional<String> response = session.getProcess().readOutputLine();
                return response.orElse("");
                
            } catch (IOException e) {
                session.setState(SessionState.ERROR, "Failed to request results: " + e.getMessage());
                throw new RuntimeException("Failed to request results from session " + sessionId, e);
            }
        });
    }
    
    /**
     * Terminates a session gracefully.
     * 
     * @param sessionId the session to terminate
     * @return CompletableFuture that completes when session is terminated
     */
    public CompletableFuture<Void> terminateSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            KalixSession session = activeSessions.get(sessionId);
            if (session != null) {
                SessionState oldState = session.getState();
                session.setState(SessionState.TERMINATED, "Session terminated by user");
                session.getProcess().close();
                // Keep session in activeSessions map for visibility, don't remove it
                
                fireSessionEvent(sessionId, oldState, SessionState.TERMINATED, "Session terminated");
                updateStatus("Terminated session: " + sessionId);
            }
        });
    }
    
    /**
     * Gets information about an active session.
     * 
     * @param sessionId the session ID
     * @return session information if found
     */
    public Optional<KalixSession> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }
    
    /**
     * Gets all active sessions.
     * 
     * @return map of session ID to session info
     */
    public Map<String, KalixSession> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }
    
    /**
     * Removes a terminated session from the session list.
     * This is for cleanup purposes - only works on TERMINATED or ERROR sessions.
     * 
     * @param sessionId the session to remove
     * @return CompletableFuture that completes when session is removed
     */
    public CompletableFuture<Void> removeSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            KalixSession session = activeSessions.get(sessionId);
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
     * Monitors a session for output and state changes on both stdout and stderr.
     */
    private void monitorSession(KalixSession session, SessionConfig config) {
        String sessionId = session.getSessionId();
        
        // Monitor stdout
        CompletableFuture<Void> stdoutMonitor = CompletableFuture.runAsync(() -> {
            try {
                while (session.getProcess().isRunning() && session.isActive()) {
                    Optional<String> outputLine = session.getProcess().readOutputLine();
                    
                    if (outputLine.isPresent()) {
                        String line = outputLine.get();
                        // Log the raw message first
                        session.getCommunicationLog().logCliToGuiStdout(line);
                        processSessionOutput(session, line, config);
                    }
                    Thread.sleep(50); // Small delay to avoid busy waiting
                }
            } catch (IOException | InterruptedException e) {
                handleSessionError(sessionId, e, "stdout monitoring");
            }
        });
        
        // Monitor stderr
        CompletableFuture<Void> stderrMonitor = CompletableFuture.runAsync(() -> {
            try {
                while (session.getProcess().isRunning() && session.isActive()) {
                    Optional<String> errorLine = session.getProcess().readErrorLine();
                    
                    if (errorLine.isPresent()) {
                        String line = errorLine.get();
                        // Log the raw message first
                        session.getCommunicationLog().logCliToGuiStderr(line);
                        processSessionError(session, line, config);
                    }
                    Thread.sleep(50); // Small delay to avoid busy waiting
                }
            } catch (IOException | InterruptedException e) {
                handleSessionError(sessionId, e, "stderr monitoring");
            }
        });
        
        // Combine both monitoring futures - session completes when both streams are done
        CompletableFuture.allOf(stdoutMonitor, stderrMonitor)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    handleSessionError(sessionId, new RuntimeException(throwable), "session monitoring");
                }
            });
    }
    
    /**
     * Processes output from a session and updates state accordingly.
     */
    private void processSessionOutput(KalixSession session, String line, SessionConfig config) {
        String sessionId = session.getSessionId();
        
        // Check for protocol messages
        Optional<KalixCliProtocol.ProtocolMessage> protocolMsg = KalixCliProtocol.parseProtocolMessage(line);
        if (protocolMsg.isPresent()) {
            handleProtocolMessage(session, protocolMsg.get());
            return;
        }
        
        // Check for progress updates
        if (config.getProgressCallback() != null) {
            Optional<ProgressParser.ProgressInfo> progress = ProgressParser.parseProgress(line);
            if (progress.isPresent()) {
                config.getProgressCallback().accept(progress.get());
            }
        }
        
        // Log stdout for debugging (only if verbose)
        // updateStatus("Session " + sessionId + " stdout: " + line);
    }
    
    /**
     * Processes error output from a session.
     * Handles stderr messages which may contain important error information or diagnostics.
     */
    private void processSessionError(KalixSession session, String errorLine, SessionConfig config) {
        String sessionId = session.getSessionId();
        
        // Always log stderr as it contains important error information
        updateStatus("Session " + sessionId + " stderr: " + errorLine);
        
        // Check if this is a critical error that should terminate the session
        if (isCriticalError(errorLine)) {
            SessionState oldState = session.getState();
            session.setState(SessionState.ERROR, "Critical error: " + errorLine);
            fireSessionEvent(sessionId, oldState, SessionState.ERROR, "Critical error from kalixcli: " + errorLine);
            return;
        }
        
        // Check for protocol messages on stderr (some CLIs send protocol info there)
        Optional<KalixCliProtocol.ProtocolMessage> protocolMsg = KalixCliProtocol.parseProtocolMessage(errorLine);
        if (protocolMsg.isPresent()) {
            handleProtocolMessage(session, protocolMsg.get());
            return;
        }
        
        // Check for progress updates on stderr (some CLIs send progress there)
        if (config.getProgressCallback() != null) {
            Optional<ProgressParser.ProgressInfo> progress = ProgressParser.parseProgress(errorLine);
            if (progress.isPresent()) {
                config.getProgressCallback().accept(progress.get());
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
     * Handles protocol messages and updates session state.
     */
    private void handleProtocolMessage(KalixSession session, KalixCliProtocol.ProtocolMessage message) {
        String sessionId = session.getSessionId();
        SessionState oldState = session.getState();
        
        if (message.isSessionReady()) {
            session.setState(SessionState.READY, message.getAdditionalInfo());
            fireSessionEvent(sessionId, oldState, SessionState.READY, "Session ready for queries");
            updateStatus("Session ready: " + sessionId);
            
        } else if (message.isCommandComplete()) {
            session.setState(SessionState.COMPLETING, message.getAdditionalInfo());
            fireSessionEvent(sessionId, oldState, SessionState.COMPLETING, "Command completed");
            
            
        } else if (message.isSessionEnding()) {
            session.setState(SessionState.COMPLETING, message.getAdditionalInfo());
            fireSessionEvent(sessionId, oldState, SessionState.COMPLETING, "Session ending");
            scheduleSessionCleanup(sessionId, 5000);
            
        } else if (message.isFatalError()) {
            session.setState(SessionState.ERROR, message.getAdditionalInfo());
            fireSessionEvent(sessionId, oldState, SessionState.ERROR, "Fatal error: " + message.getAdditionalInfo());
            updateStatus("Session error: " + sessionId + " - " + message.getAdditionalInfo());
            
        } else if (message.isRecoverableError()) {
            // Don't change state for recoverable errors, just log
            fireSessionEvent(sessionId, oldState, oldState, "Recoverable error: " + message.getAdditionalInfo());
            updateStatus("Session warning: " + sessionId + " - " + message.getAdditionalInfo());
        }
    }
    
    /**
     * Schedules cleanup of a session after a delay.
     */
    private void scheduleSessionCleanup(String sessionId, int delayMs) {
        CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> {
                KalixSession session = activeSessions.get(sessionId);
                if (session != null) {
                    session.setState(SessionState.TERMINATED, "Session cleanup completed");
                    session.getProcess().close();
                    // Keep session in activeSessions map for visibility, don't remove it
                    fireSessionEvent(sessionId, SessionState.COMPLETING, SessionState.TERMINATED, "Session cleaned up");
                }
            });
    }
    
    /**
     * Fires a session event to registered callbacks.
     */
    private void fireSessionEvent(String sessionId, SessionState oldState, SessionState newState, String message) {
        if (eventCallback != null) {
            try {
                eventCallback.accept(new SessionEvent(sessionId, oldState, newState, message));
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
        KalixSession session = activeSessions.get(sessionId);
        if (session != null) {
            SessionState oldState = session.getState();
            session.setState(SessionState.ERROR, operation + " failed: " + e.getMessage());
            fireSessionEvent(sessionId, oldState, SessionState.ERROR, e.getMessage());
        }
        updateStatus("Session " + sessionId + " error: " + operation + " failed");
    }
    
    /**
     * Validates session exists and is active.
     */
    private KalixSession validateActiveSession(String sessionId, String operation) {
        KalixSession session = activeSessions.get(sessionId);
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
        
        return "session-" + SESSION_COUNTER.incrementAndGet();
    }
    
    /**
     * Shuts down the session manager and terminates all active sessions.
     */
    public void shutdown() {
        updateStatus("Shutting down session manager...");
        
        // Terminate all active sessions
        activeSessions.values().parallelStream().forEach(session -> {
            try {
                session.getProcess().close();
            } catch (Exception e) {
                System.err.println("Error closing session " + session.getSessionId() + ": " + e.getMessage());
            }
        });
        
        activeSessions.clear();
        updateStatus("Session manager shutdown complete");
    }
}