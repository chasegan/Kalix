package com.kalix.gui.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON-based STDIO protocol handler for kalixcli communication.
 * Implements the new structured JSON message protocol as specified in kalixcli-stdio-spec.md.
 */
public class JsonStdioProtocol {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Message types sent from kalixcli to frontend.
     */
    public enum SystemMessageType {
        READY("ready"),
        BUSY("busy"),
        PROGRESS("progress"),
        RESULT("result"),
        STOPPED("stopped"),
        ERROR("error"),
        LOG("log");
        
        private final String value;
        
        SystemMessageType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static Optional<SystemMessageType> fromString(String value) {
            for (SystemMessageType type : values()) {
                if (type.value.equals(value)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }
    
    /**
     * Message types sent from frontend to kalixcli.
     */
    public enum CommandMessageType {
        COMMAND("command"),
        STOP("stop"),
        QUERY("query"),
        TERMINATE("terminate");
        
        private final String value;
        
        CommandMessageType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    /**
     * Base message structure for all JSON messages.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class BaseMessage {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("timestamp")
        private String timestamp;
        
        @JsonProperty("data")
        private JsonNode data;
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        
        public JsonNode getData() { return data; }
        public void setData(JsonNode data) { this.data = data; }
        
        protected void setCurrentTimestamp() {
            this.timestamp = Instant.now().toString();
        }
    }
    
    /**
     * Message received from kalixcli (includes session_id).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SystemMessage extends BaseMessage {
        @JsonProperty("session_id")
        private String sessionId;
        
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public SystemMessageType getSystemMessageType() {
            return SystemMessageType.fromString(getType()).orElse(null);
        }
        
        @Override
        public String toString() {
            return String.format("SystemMessage[type=%s, session_id=%s]", getType(), sessionId);
        }
    }
    
    /**
     * Message sent from frontend to kalixcli (no session_id needed).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandMessage extends BaseMessage {
        
        public CommandMessage() {
            setCurrentTimestamp();
        }
        
        public CommandMessage(CommandMessageType type) {
            this();
            setType(type.getValue());
        }
        
        public CommandMessageType getCommandMessageType() {
            for (CommandMessageType type : CommandMessageType.values()) {
                if (type.getValue().equals(getType())) {
                    return type;
                }
            }
            return null;
        }
        
        @Override
        public String toString() {
            return String.format("CommandMessage[type=%s]", getType());
        }
    }
    
    /**
     * Ready message data structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReadyData {
        @JsonProperty("status")
        private String status;
        
        @JsonProperty("available_commands")
        private List<AvailableCommand> availableCommands;
        
        @JsonProperty("current_state")
        private CurrentState currentState;
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public List<AvailableCommand> getAvailableCommands() { return availableCommands; }
        public void setAvailableCommands(List<AvailableCommand> availableCommands) { this.availableCommands = availableCommands; }
        
        public CurrentState getCurrentState() { return currentState; }
        public void setCurrentState(CurrentState currentState) { this.currentState = currentState; }
    }
    
    /**
     * Available command structure in ready messages.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AvailableCommand {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("parameters")
        private List<CommandParameter> parameters;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public List<CommandParameter> getParameters() { return parameters; }
        public void setParameters(List<CommandParameter> parameters) { this.parameters = parameters; }
    }
    
    /**
     * Command parameter structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandParameter {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("required")
        private boolean required;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
    }
    
    /**
     * Current state structure in ready messages.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentState {
        @JsonProperty("model_loaded")
        private boolean modelLoaded;
        
        @JsonProperty("data_loaded")
        private boolean dataLoaded;
        
        @JsonProperty("last_simulation")
        private String lastSimulation;
        
        public boolean isModelLoaded() { return modelLoaded; }
        public void setModelLoaded(boolean modelLoaded) { this.modelLoaded = modelLoaded; }
        
        public boolean isDataLoaded() { return dataLoaded; }
        public void setDataLoaded(boolean dataLoaded) { this.dataLoaded = dataLoaded; }
        
        public String getLastSimulation() { return lastSimulation; }
        public void setLastSimulation(String lastSimulation) { this.lastSimulation = lastSimulation; }
    }
    
    /**
     * Busy message data structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BusyData {
        @JsonProperty("status")
        private String status;
        
        @JsonProperty("executing_command")
        private String executingCommand;
        
        @JsonProperty("interruptible")
        private boolean interruptible;
        
        @JsonProperty("started_at")
        private String startedAt;
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getExecutingCommand() { return executingCommand; }
        public void setExecutingCommand(String executingCommand) { this.executingCommand = executingCommand; }
        
        public boolean isInterruptible() { return interruptible; }
        public void setInterruptible(boolean interruptible) { this.interruptible = interruptible; }
        
        public String getStartedAt() { return startedAt; }
        public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    }
    
    /**
     * Progress message data structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProgressData {
        @JsonProperty("command")
        private String command;
        
        @JsonProperty("progress")
        private ProgressInfo progress;
        
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        
        public ProgressInfo getProgress() { return progress; }
        public void setProgress(ProgressInfo progress) { this.progress = progress; }
    }
    
    /**
     * Progress information structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProgressInfo {
        @JsonProperty("percent_complete")
        private double percentComplete;
        
        @JsonProperty("current_step")
        private String currentStep;
        
        @JsonProperty("estimated_remaining")
        private String estimatedRemaining;
        
        @JsonProperty("details")
        private JsonNode details;
        
        public double getPercentComplete() { return percentComplete; }
        public void setPercentComplete(double percentComplete) { this.percentComplete = percentComplete; }
        
        public String getCurrentStep() { return currentStep; }
        public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
        
        public String getEstimatedRemaining() { return estimatedRemaining; }
        public void setEstimatedRemaining(String estimatedRemaining) { this.estimatedRemaining = estimatedRemaining; }
        
        public JsonNode getDetails() { return details; }
        public void setDetails(JsonNode details) { this.details = details; }
    }
    
    /**
     * Command data structure for sending commands to kalixcli.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandData {
        @JsonProperty("command")
        private String command;
        
        @JsonProperty("parameters")
        private Map<String, Object> parameters;
        
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        
        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    }
    
    /**
     * Stop message data structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopData {
        @JsonProperty("reason")
        private String reason;
        
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
    
    /**
     * Query message data structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryData {
        @JsonProperty("query_type")
        private String queryType;
        
        @JsonProperty("parameters")
        private Map<String, Object> parameters;
        
        public String getQueryType() { return queryType; }
        public void setQueryType(String queryType) { this.queryType = queryType; }
        
        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    }
    
    /**
     * Parses a JSON line into a SystemMessage.
     * 
     * @param line the JSON line to parse
     * @return parsed system message if valid JSON, empty otherwise
     */
    public static Optional<SystemMessage> parseSystemMessage(String line) {
        if (line == null || line.trim().isEmpty()) {
            return Optional.empty();
        }
        
        try {
            SystemMessage message = objectMapper.readValue(line.trim(), SystemMessage.class);
            
            // Validate required fields
            if (message.getType() == null || message.getSessionId() == null) {
                return Optional.empty();
            }
            
            return Optional.of(message);
        } catch (JsonProcessingException e) {
            // Not valid JSON or not a system message
            return Optional.empty();
        }
    }
    
    /**
     * Creates a command message to send to kalixcli.
     * 
     * @param command the command name
     * @param parameters the command parameters
     * @return JSON string representation
     */
    public static String createCommandMessage(String command, Map<String, Object> parameters) {
        try {
            CommandMessage message = new CommandMessage(CommandMessageType.COMMAND);
            
            ObjectNode dataNode = objectMapper.createObjectNode();
            dataNode.put("command", command);
            
            if (parameters != null && !parameters.isEmpty()) {
                ObjectNode paramsNode = objectMapper.createObjectNode();
                parameters.forEach((key, value) -> {
                    if (value instanceof String) {
                        paramsNode.put(key, (String) value);
                    } else if (value instanceof Integer) {
                        paramsNode.put(key, (Integer) value);
                    } else if (value instanceof Double) {
                        paramsNode.put(key, (Double) value);
                    } else if (value instanceof Boolean) {
                        paramsNode.put(key, (Boolean) value);
                    } else {
                        paramsNode.put(key, value.toString());
                    }
                });
                dataNode.set("parameters", paramsNode);
            }
            
            message.setData(dataNode);
            
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create command message", e);
        }
    }
    
    /**
     * Creates a stop message to interrupt current operation.
     * 
     * @param reason the reason for stopping
     * @return JSON string representation
     */
    public static String createStopMessage(String reason) {
        try {
            CommandMessage message = new CommandMessage(CommandMessageType.STOP);
            
            ObjectNode dataNode = objectMapper.createObjectNode();
            dataNode.put("reason", reason != null ? reason : "User requested cancellation");
            
            message.setData(dataNode);
            
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create stop message", e);
        }
    }
    
    /**
     * Creates a query message to request information.
     * 
     * @param queryType the type of query
     * @param parameters query parameters
     * @return JSON string representation
     */
    public static String createQueryMessage(String queryType, Map<String, Object> parameters) {
        try {
            CommandMessage message = new CommandMessage(CommandMessageType.QUERY);
            
            ObjectNode dataNode = objectMapper.createObjectNode();
            dataNode.put("query_type", queryType);
            
            if (parameters != null && !parameters.isEmpty()) {
                ObjectNode paramsNode = objectMapper.createObjectNode();
                parameters.forEach((key, value) -> {
                    if (value instanceof String) {
                        paramsNode.put(key, (String) value);
                    } else {
                        paramsNode.put(key, value.toString());
                    }
                });
                dataNode.set("parameters", paramsNode);
            }
            
            message.setData(dataNode);
            
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create query message", e);
        }
    }
    
    /**
     * Creates a terminate message to end the session.
     * 
     * @return JSON string representation
     */
    public static String createTerminateMessage() {
        try {
            CommandMessage message = new CommandMessage(CommandMessageType.TERMINATE);
            message.setData(objectMapper.createObjectNode());
            
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create terminate message", e);
        }
    }
    
    /**
     * Utility method to extract strongly-typed data from a system message.
     * 
     * @param message the system message
     * @param dataClass the class to deserialize data into
     * @param <T> the data type
     * @return deserialized data
     */
    public static <T> T extractData(SystemMessage message, Class<T> dataClass) {
        try {
            return objectMapper.treeToValue(message.getData(), dataClass);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to extract data from message", e);
        }
    }
    
    /**
     * Checks if a line looks like a JSON message (starts with {).
     * 
     * @param line the line to check
     * @return true if it might be JSON
     */
    public static boolean looksLikeJson(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }
    
    /**
     * Utility methods for common queries.
     */
    public static class Queries {
        public static String getState() {
            return createQueryMessage("get_state", null);
        }
        
        public static String getVersion() {
            return createQueryMessage("get_version", null);
        }
    }
    
    /**
     * Utility methods for common commands.
     */
    public static class Commands {
        public static String loadModelFile(String modelPath) {
            return createCommandMessage("load_model_file", Map.of("model_path", modelPath));
        }
        
        public static String loadModelString(String modelIni) {
            return createCommandMessage("load_model_string", Map.of("model_ini", modelIni));
        }
        
        public static String runSimulation() {
            return createCommandMessage("run_simulation", null);
        }
        
        public static String testProgress() {
            return createCommandMessage("test_progress", null);
        }
    }
}