package com.kalix.ide.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JSON message data classes for the STDIO protocol.
 * These classes represent the structure of messages exchanged between the IDE and kalixcli.
 */
public class JsonMessage {
    
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
        
        public JsonStdioTypes.SystemMessageType systemMessageType() {
            return JsonStdioTypes.SystemMessageType.fromString(getType()).orElse(null);
        }
        
        @Override
        public String toString() {
            return String.format("SystemMessage[type=%s, session_id=%s]", getType(), sessionId);
        }
    }
    
    /**
     * Message sent from frontend to kalixcli (includes session_id).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"type", "timestamp", "session_id", "data"})
    public static class CommandMessage extends BaseMessage {
        @JsonProperty("session_id")
        private String sessionId;
        
        public CommandMessage() {
            setCurrentTimestamp();
        }
        
        public CommandMessage(JsonStdioTypes.CommandMessageType type) {
            this();
            setType(type.getValue());
        }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public JsonStdioTypes.CommandMessageType commandMessageType() {
            for (JsonStdioTypes.CommandMessageType type : JsonStdioTypes.CommandMessageType.values()) {
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
     * Result message data structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultData {
        @JsonProperty("command")
        private String command;

        @JsonProperty("status")
        private String status;

        @JsonProperty("execution_time")
        private String executionTime;

        @JsonProperty("result")
        private ResultInfo result;

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getExecutionTime() { return executionTime; }
        public void setExecutionTime(String executionTime) { this.executionTime = executionTime; }

        public ResultInfo getResult() { return result; }
        public void setResult(ResultInfo result) { this.result = result; }
    }

    /**
     * Result information structure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultInfo {
        @JsonProperty("summary")
        private JsonNode summary;

        @JsonProperty("data_available")
        private List<String> dataAvailable;

        @JsonProperty("outputs_generated")
        private List<String> outputsGenerated;

        public JsonNode getSummary() { return summary; }
        public void setSummary(JsonNode summary) { this.summary = summary; }

        public List<String> getDataAvailable() { return dataAvailable; }
        public void setDataAvailable(List<String> dataAvailable) { this.dataAvailable = dataAvailable; }

        public List<String> getOutputsGenerated() { return outputsGenerated; }
        public void setOutputsGenerated(List<String> outputsGenerated) { this.outputsGenerated = outputsGenerated; }
    }
}