package com.kalix.gui.cli;

import com.kalix.gui.windows.SessionsWindow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Implementation of the new kalixcli JSON STDIO communication protocol.
 * Handles JSON-based communication with kalixcli new-session command for
 * load_model_string and run_simulation operations.
 */
public class JsonStdioProtocol {
    
    private static final DateTimeFormatter ISO_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final int DEFAULT_TIMEOUT_SECONDS = 300; // 5 minutes
    
    private final ProcessExecutor processExecutor;
    private final CliLogger logger;
    private ProcessExecutor.RunningProcess process;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String sessionId;
    private volatile String tempSessionId; // For logging before real session ID is known
    
    // Callbacks for different message types
    private Consumer<ReadyMessage> readyCallback;
    private Consumer<BusyMessage> busyCallback;
    private Consumer<ProgressMessage> progressCallback;
    private Consumer<ResultMessage> resultCallback;
    private Consumer<StoppedMessage> stoppedCallback;
    private Consumer<ErrorMessage> errorCallback;
    private Consumer<LogMessage> logCallback;
    
    /**
     * Base class for all JSON protocol messages.
     */
    public static abstract class JsonMessage {
        public final String type;
        public final String timestamp;
        public final String session_id;
        public final Map<String, Object> data;
        
        protected JsonMessage(String type, String timestamp, String sessionId, Map<String, Object> data) {
            this.type = type;
            this.timestamp = timestamp;
            this.session_id = sessionId;
            this.data = data != null ? data : new HashMap<>();
        }
        
        public String getType() { return type; }
        public String getTimestamp() { return timestamp; }
        public String getSessionId() { return session_id; }
        public Map<String, Object> getData() { return data; }
    }
    
    /**
     * Ready message from kalixcli indicating it's ready for commands.
     */
    public static class ReadyMessage extends JsonMessage {
        private final List<Map<String, Object>> availableCommands;
        private final Map<String, Object> currentState;
        
        @SuppressWarnings("unchecked")
        public ReadyMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("ready", timestamp, sessionId, data);
            this.availableCommands = (List<Map<String, Object>>) data.getOrDefault("available_commands", List.of());
            this.currentState = (Map<String, Object>) data.getOrDefault("current_state", Map.of());
        }
        
        public List<Map<String, Object>> getAvailableCommands() { return availableCommands; }
        public Map<String, Object> getCurrentState() { return currentState; }
        public boolean isModelLoaded() { 
            return (Boolean) currentState.getOrDefault("model_loaded", false); 
        }
    }
    
    /**
     * Busy message indicating kalixcli is executing a command.
     */
    public static class BusyMessage extends JsonMessage {
        private final String executingCommand;
        private final boolean interruptible;
        
        public BusyMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("busy", timestamp, sessionId, data);
            this.executingCommand = (String) data.getOrDefault("executing_command", "");
            this.interruptible = (Boolean) data.getOrDefault("interruptible", false);
        }
        
        public String getExecutingCommand() { return executingCommand; }
        public boolean isInterruptible() { return interruptible; }
    }
    
    /**
     * Progress message with task progress updates.
     */
    public static class ProgressMessage extends JsonMessage {
        private final String command;
        private final Map<String, Object> progress;
        
        @SuppressWarnings("unchecked")
        public ProgressMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("progress", timestamp, sessionId, data);
            this.command = (String) data.getOrDefault("command", "");
            this.progress = (Map<String, Object>) data.getOrDefault("progress", Map.of());
        }
        
        public String getCommand() { return command; }
        public Map<String, Object> getProgress() { return progress; }
        
        public double getPercentComplete() {
            Object pct = progress.get("percent_complete");
            return pct instanceof Number ? ((Number) pct).doubleValue() : 0.0;
        }
        
        public String getCurrentStep() {
            return (String) progress.getOrDefault("current_step", "");
        }
        
        public String getEstimatedRemaining() {
            return (String) progress.getOrDefault("estimated_remaining", "");
        }
    }
    
    /**
     * Result message with command completion results.
     */
    public static class ResultMessage extends JsonMessage {
        private final String command;
        private final String status;
        private final String executionTime;
        private final Map<String, Object> result;
        
        @SuppressWarnings("unchecked")
        public ResultMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("result", timestamp, sessionId, data);
            this.command = (String) data.getOrDefault("command", "");
            this.status = (String) data.getOrDefault("status", "");
            this.executionTime = (String) data.getOrDefault("execution_time", "");
            this.result = (Map<String, Object>) data.getOrDefault("result", Map.of());
        }
        
        public String getCommand() { return command; }
        public String getStatus() { return status; }
        public String getExecutionTime() { return executionTime; }
        public Map<String, Object> getResult() { return result; }
        
        public boolean isSuccess() { return "success".equals(status); }
    }
    
    /**
     * Stopped message when a task is interrupted.
     */
    public static class StoppedMessage extends JsonMessage {
        private final String command;
        private final String status;
        private final Map<String, Object> partialResult;
        
        @SuppressWarnings("unchecked")
        public StoppedMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("stopped", timestamp, sessionId, data);
            this.command = (String) data.getOrDefault("command", "");
            this.status = (String) data.getOrDefault("status", "");
            this.partialResult = (Map<String, Object>) data.getOrDefault("partial_result", Map.of());
        }
        
        public String getCommand() { return command; }
        public String getStatus() { return status; }
        public Map<String, Object> getPartialResult() { return partialResult; }
    }
    
    /**
     * Error message for command execution errors.
     */
    public static class ErrorMessage extends JsonMessage {
        private final String command;
        private final Map<String, Object> error;
        
        @SuppressWarnings("unchecked")
        public ErrorMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("error", timestamp, sessionId, data);
            this.command = (String) data.getOrDefault("command", "");
            this.error = (Map<String, Object>) data.getOrDefault("error", Map.of());
        }
        
        public String getCommand() { return command; }
        public Map<String, Object> getError() { return error; }
        
        public String getErrorCode() {
            return (String) error.getOrDefault("code", "UNKNOWN_ERROR");
        }
        
        public String getErrorMessage() {
            return (String) error.getOrDefault("message", "An error occurred");
        }
    }
    
    /**
     * Log message for informational output.
     */
    public static class LogMessage extends JsonMessage {
        private final String level;
        private final String message;
        
        public LogMessage(String timestamp, String sessionId, Map<String, Object> data) {
            super("log", timestamp, sessionId, data);
            this.level = (String) data.getOrDefault("level", "info");
            this.message = (String) data.getOrDefault("message", "");
        }
        
        public String getLevel() { return level; }
        public String getMessage() { return message; }
    }
    
    /**
     * Creates a new JsonStdioProtocol instance.
     */
    public JsonStdioProtocol(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
        this.logger = CliLogger.getInstance();
    }
    
    /**
     * Starts a new kalixcli session with the new-session command.
     */
    public CompletableFuture<String> startSession(Path cliPath) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("Protocol is closed");
        }
        
        logger.info("Starting new kalixcli JSON STDIO session");
        
        // Create temporary session ID for early logging
        tempSessionId = "temp_session_" + System.currentTimeMillis();
        
        // Log session start
        logCommunication("SYSTEM", "Starting kalixcli with command: " + cliPath.toString() + " new-session");
        
        // Start kalixcli with new-session command
        process = processExecutor.startInteractive(cliPath.toString(), "new-session");
        
        // Start message processing
        CompletableFuture<String> sessionFuture = new CompletableFuture<>();
        startMessageProcessing(sessionFuture);
        
        return sessionFuture;
    }
    
    /**
     * Sends a load_model_string command with the model INI content.
     */
    public CompletableFuture<Void> loadModelString(String modelIni) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> command = new HashMap<>();
                command.put("type", "command");
                command.put("timestamp", getCurrentTimestamp());
                
                Map<String, Object> data = new HashMap<>();
                data.put("command", "load_model_string");
                
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("model_ini", modelIni);
                parameters.put("validation", true);
                data.put("parameters", parameters);
                
                command.put("data", data);
                
                sendJsonMessage(command);
                logger.info("Sent load_model_string command");
                
            } catch (IOException e) {
                throw new RuntimeException("Failed to send load_model_string command", e);
            }
        });
    }
    
    /**
     * Sends a run_simulation command.
     */
    public CompletableFuture<Void> runSimulation() {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> command = new HashMap<>();
                command.put("type", "command");
                command.put("timestamp", getCurrentTimestamp());
                
                Map<String, Object> data = new HashMap<>();
                data.put("command", "run_simulation");
                data.put("parameters", Map.of()); // No parameters for run_simulation
                
                command.put("data", data);
                
                sendJsonMessage(command);
                logger.info("Sent run_simulation command");
                
            } catch (IOException e) {
                throw new RuntimeException("Failed to send run_simulation command", e);
            }
        });
    }
    
    /**
     * Sends a stop command to interrupt the current task.
     */
    public CompletableFuture<Void> stopCurrentTask(String reason) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> command = new HashMap<>();
                command.put("type", "stop");
                command.put("timestamp", getCurrentTimestamp());
                
                Map<String, Object> data = new HashMap<>();
                data.put("reason", reason != null ? reason : "User requested cancellation");
                
                command.put("data", data);
                
                sendJsonMessage(command);
                logger.info("Sent stop command");
                
            } catch (IOException e) {
                throw new RuntimeException("Failed to send stop command", e);
            }
        });
    }
    
    /**
     * Terminates the session gracefully.
     */
    public CompletableFuture<Void> terminateSession() {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> command = new HashMap<>();
                command.put("type", "terminate");
                command.put("timestamp", getCurrentTimestamp());
                command.put("data", Map.of());
                
                sendJsonMessage(command);
                logger.info("Sent terminate command");
                
                // Close after a short delay to allow graceful termination
                CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                    .execute(this::close);
                
            } catch (IOException e) {
                logger.warn("Failed to send terminate command", e);
                close(); // Force close if terminate fails
            }
        });
    }
    
    /**
     * Sets callback for ready messages.
     */
    public JsonStdioProtocol onReady(Consumer<ReadyMessage> callback) {
        this.readyCallback = callback;
        return this;
    }
    
    /**
     * Sets callback for busy messages.
     */
    public JsonStdioProtocol onBusy(Consumer<BusyMessage> callback) {
        this.busyCallback = callback;
        return this;
    }
    
    /**
     * Sets callback for progress messages.
     */
    public JsonStdioProtocol onProgress(Consumer<ProgressMessage> callback) {
        this.progressCallback = callback;
        return this;
    }
    
    /**
     * Sets callback for result messages.
     */
    public JsonStdioProtocol onResult(Consumer<ResultMessage> callback) {
        this.resultCallback = callback;
        return this;
    }
    
    /**
     * Sets callback for stopped messages.
     */
    public JsonStdioProtocol onStopped(Consumer<StoppedMessage> callback) {
        this.stoppedCallback = callback;
        return this;
    }
    
    /**
     * Sets callback for error messages.
     */
    public JsonStdioProtocol onError(Consumer<ErrorMessage> callback) {
        this.errorCallback = callback;
        return this;
    }
    
    /**
     * Sets callback for log messages.
     */
    public JsonStdioProtocol onLog(Consumer<LogMessage> callback) {
        this.logCallback = callback;
        return this;
    }
    
    /**
     * Gets the current session ID.
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * Checks if the session is active.
     */
    public boolean isActive() {
        return process != null && process.isRunning() && !closed.get();
    }
    
    /**
     * Closes the protocol and terminates the session.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing JsonStdioProtocol");
            if (process != null) {
                process.cancel(false);
            }
        }
    }
    
    // Private helper methods
    
    private void startMessageProcessing(CompletableFuture<String> sessionFuture) {
        CompletableFuture.runAsync(() -> {
            try {
                while (process.isRunning() && !closed.get()) {
                    String line = process.readOutputLine();
                    if (line != null && !line.trim().isEmpty()) {
                        processMessage(line.trim(), sessionFuture);
                    }
                    
                    // Small delay to avoid busy waiting
                    Thread.sleep(10);
                }
            } catch (IOException | InterruptedException e) {
                logger.error("Error processing messages", e);
                if (!sessionFuture.isDone()) {
                    sessionFuture.completeExceptionally(e);
                }
            }
        });
    }
    
    private void processMessage(String jsonLine, CompletableFuture<String> sessionFuture) {
        try {
            // Log incoming message
            logCommunication("CLI->GUI", jsonLine);
            
            // Parse JSON manually (simple implementation)
            JsonMessage message = parseJsonMessage(jsonLine);
            
            if (message != null) {
                // Extract session ID from first ready message
                if (sessionId == null && message instanceof ReadyMessage) {
                    sessionId = message.getSessionId();
                    
                    // Log the session ID transition
                    logCommunication("SYSTEM", "Real session ID established: " + sessionId);
                    
                    if (!sessionFuture.isDone()) {
                        sessionFuture.complete(sessionId);
                    }
                }
                
                // Dispatch to appropriate callback
                dispatchMessage(message);
            }
            
        } catch (Exception e) {
            logger.error("Error parsing message: " + jsonLine, e);
            logCommunication("ERROR", "Failed to parse message: " + e.getMessage());
        }
    }
    
    private JsonMessage parseJsonMessage(String jsonLine) {
        // Simple JSON parsing - in a real implementation you'd use a proper JSON library
        // This is a basic implementation for demonstration
        try {
            Map<String, Object> json = parseJsonToMap(jsonLine);
            String type = (String) json.get("type");
            String timestamp = (String) json.get("timestamp");
            String sessionId = (String) json.get("session_id");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            
            switch (type) {
                case "ready":
                    return new ReadyMessage(timestamp, sessionId, data);
                case "busy":
                    return new BusyMessage(timestamp, sessionId, data);
                case "progress":
                    return new ProgressMessage(timestamp, sessionId, data);
                case "result":
                    return new ResultMessage(timestamp, sessionId, data);
                case "stopped":
                    return new StoppedMessage(timestamp, sessionId, data);
                case "error":
                    return new ErrorMessage(timestamp, sessionId, data);
                case "log":
                    return new LogMessage(timestamp, sessionId, data);
                default:
                    logger.warn("Unknown message type: " + type);
                    return null;
            }
        } catch (Exception e) {
            logger.error("Error parsing JSON message", e);
            return null;
        }
    }
    
    // Simple JSON parser implementation
    private Map<String, Object> parseJsonToMap(String json) {
        try {
            System.out.println("[JsonStdioProtocol] Parsing JSON: " + json);
            
            // Simple manual JSON parsing for the specific kalixcli format
            Map<String, Object> result = new HashMap<>();
            
            // Extract type
            String type = extractJsonString(json, "type");
            if (type != null) result.put("type", type);
            
            // Extract timestamp  
            String timestamp = extractJsonString(json, "timestamp");
            if (timestamp != null) result.put("timestamp", timestamp);
            
            // Extract session_id
            String sessionId = extractJsonString(json, "session_id");
            if (sessionId != null) result.put("session_id", sessionId);
            
            // Extract data object (simplified)
            Map<String, Object> data = extractJsonObject(json, "data");
            if (data != null) result.put("data", data);
            
            System.out.println("[JsonStdioProtocol] Parsed JSON: type=" + type + ", session=" + sessionId);
            return result;
            
        } catch (Exception e) {
            System.err.println("[JsonStdioProtocol] Failed to parse JSON: " + json);
            e.printStackTrace();
            return new HashMap<>();
        }
    }
    
    // Extract a string value from JSON
    private String extractJsonString(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start == -1) return null;
            start += pattern.length();
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
    
    // Extract a simple object from JSON (very basic implementation)
    private Map<String, Object> extractJsonObject(String json, String key) {
        try {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start == -1) return new HashMap<>();
            
            // For now, just return a basic data object
            Map<String, Object> data = new HashMap<>();
            
            // Extract executing_command for busy messages
            String executingCommand = extractJsonString(json, "executing_command");
            if (executingCommand != null) data.put("executing_command", executingCommand);
            
            // Extract progress info
            if (json.contains("\"progress\":")) {
                Map<String, Object> progress = new HashMap<>();
                String currentStep = extractJsonString(json, "current_step");
                if (currentStep != null) progress.put("current_step", currentStep);
                
                // Extract percent_complete
                String percentStr = extractJsonNumber(json, "percent_complete");
                if (percentStr != null) {
                    try {
                        progress.put("percent_complete", Double.parseDouble(percentStr));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
                data.put("progress", progress);
            }
            
            // Extract command for result messages
            String command = extractJsonString(json, "command");
            if (command != null) data.put("command", command);
            
            // Extract status
            String status = extractJsonString(json, "status");
            if (status != null) data.put("status", status);
            
            return data;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
    
    // Extract a number value from JSON
    private String extractJsonNumber(String json, String key) {
        try {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start == -1) return null;
            start += pattern.length();
            
            // Find the end of the number (comma, }, or whitespace)
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                    break;
                }
                end++;
            }
            
            return json.substring(start, end).trim();
        } catch (Exception e) {
            return null;
        }
    }
    
    private void dispatchMessage(JsonMessage message) {
        try {
            switch (message.getType()) {
                case "ready":
                    if (readyCallback != null) {
                        readyCallback.accept((ReadyMessage) message);
                    }
                    break;
                case "busy":
                    if (busyCallback != null) {
                        busyCallback.accept((BusyMessage) message);
                    }
                    break;
                case "progress":
                    if (progressCallback != null) {
                        progressCallback.accept((ProgressMessage) message);
                    }
                    break;
                case "result":
                    if (resultCallback != null) {
                        resultCallback.accept((ResultMessage) message);
                    }
                    break;
                case "stopped":
                    if (stoppedCallback != null) {
                        stoppedCallback.accept((StoppedMessage) message);
                    }
                    break;
                case "error":
                    if (errorCallback != null) {
                        errorCallback.accept((ErrorMessage) message);
                    }
                    break;
                case "log":
                    if (logCallback != null) {
                        logCallback.accept((LogMessage) message);
                    }
                    break;
            }
        } catch (Exception e) {
            logger.error("Error dispatching message", e);
        }
    }
    
    private void sendJsonMessage(Map<String, Object> message) throws IOException {
        if (process == null || !process.isRunning()) {
            throw new IOException("Process is not running");
        }
        
        // Convert to JSON string (simple implementation)
        String jsonString = mapToJsonString(message);
        
        // Log outgoing message
        logCommunication("GUI->CLI", jsonString);
        
        process.sendInput(jsonString);
        logger.debug("Sent JSON: " + jsonString);
    }
    
    // Simple JSON serialization to match kalixcli format
    private String mapToJsonString(Map<String, Object> map) {
        try {
            StringBuilder json = new StringBuilder("{");
            
            // Add type
            Object type = map.get("type");
            if (type != null) {
                json.append("\"type\":\"").append(type).append("\"");
            }
            
            // Add timestamp
            Object timestamp = map.get("timestamp");
            if (timestamp != null) {
                if (json.length() > 1) json.append(",");
                json.append("\"timestamp\":\"").append(timestamp).append("\"");
            }
            
            // Add data object
            Object data = map.get("data");
            if (data != null && data instanceof Map) {
                if (json.length() > 1) json.append(",");
                json.append("\"data\":");
                json.append(mapDataToJson((Map<String, Object>) data));
            }
            
            json.append("}");
            String result = json.toString();
            
            System.out.println("[JsonStdioProtocol] Generated JSON: " + result);
            return result;
            
        } catch (Exception e) {
            System.err.println("[JsonStdioProtocol] Failed to generate JSON: " + e.getMessage());
            return "{}";
        }
    }
    
    // Convert data map to JSON
    private String mapDataToJson(Map<String, Object> data) {
        StringBuilder json = new StringBuilder("{");
        
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) json.append(",");
            first = false;
            
            json.append("\"").append(entry.getKey()).append("\":");
            
            Object value = entry.getValue();
            if (value instanceof String) {
                // Escape the string value properly
                String escaped = ((String) value).replace("\\", "\\\\")
                                                 .replace("\"", "\\\"")
                                                 .replace("\n", "\\n")
                                                 .replace("\r", "\\r")
                                                 .replace("\t", "\\t");
                json.append("\"").append(escaped).append("\"");
            } else if (value instanceof Map) {
                json.append(mapDataToJson((Map<String, Object>) value));
            } else if (value instanceof Boolean) {
                json.append(value.toString());
            } else if (value instanceof Number) {
                json.append(value.toString());
            } else {
                json.append("\"").append(value.toString()).append("\"");
            }
        }
        
        json.append("}");
        return json.toString();
    }
    
    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(ISO_TIMESTAMP);
    }
    
    /**
     * Logs communication messages to the session window for debugging.
     */
    private void logCommunication(String direction, String message) {
        // Use temp session ID for early messages, real session ID once established
        String logSessionId = sessionId != null ? sessionId : tempSessionId;
        
        // ALWAYS log to console for debugging
        System.out.println("[JsonStdioProtocol] " + direction + " (" + logSessionId + "): " + message);
        
        if (logSessionId != null) {
            try {
                SessionsWindow.logSessionMessage(logSessionId, direction, message);
                System.out.println("[JsonStdioProtocol] Successfully sent to SessionsWindow: " + logSessionId);
            } catch (Exception e) {
                System.err.println("[JsonStdioProtocol] Failed to send to SessionsWindow: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("[JsonStdioProtocol] No session ID available for logging: " + message);
        }
        
        // When session ID transitions from temp to real, notify SessionsWindow
        if (sessionId != null && tempSessionId != null && !sessionId.equals(tempSessionId)) {
            try {
                System.out.println("[JsonStdioProtocol] Transferring session: " + tempSessionId + " -> " + sessionId);
                SessionsWindow.transferSession(tempSessionId, sessionId);
                tempSessionId = null; // Clear temp ID after transfer
            } catch (Exception e) {
                System.err.println("[JsonStdioProtocol] Failed to transfer session: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}