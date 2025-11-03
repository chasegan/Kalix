package com.kalix.ide.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Compact JSON message data classes for the STDIO protocol.
 * These classes represent the structure of messages exchanged between the IDE and kalixcli.
 */
public class JsonMessage {

    /**
     * Base message structure for all JSON messages using compact protocol.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class BaseMessage {
        @JsonProperty("m")
        private String messageType;

        @JsonProperty(value = "uid", required = false)
        private String sessionId;

        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    /**
     * Message received from kalixcli (includes session ID).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SystemMessage extends BaseMessage {
        // All additional fields are flattened into this message

        // Ready message fields
        @JsonProperty("rc")
        private Integer returnCode;

        // Busy message fields
        @JsonProperty("cmd")
        private String command;

        @JsonProperty("int")
        private Boolean interruptible;

        // Progress message fields
        @JsonProperty("i")
        private Integer current;

        @JsonProperty("n")
        private Integer total;

        @JsonProperty("t")
        private String taskType;

        @JsonProperty("d")
        private JsonNode progressData;  // Array of progress-specific data (e.g., objective values)

        // Result message fields
        @JsonProperty("exec_ms")
        private Double executionTimeMs;

        @JsonProperty("ok")
        private Boolean success;

        @JsonProperty("r")
        private JsonNode result;

        // Error message fields
        @JsonProperty("msg")
        private String errorMessage;

        // Getters and setters
        public Integer getReturnCode() { return returnCode; }
        public void setReturnCode(Integer returnCode) { this.returnCode = returnCode; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public Boolean getInterruptible() { return interruptible; }
        public void setInterruptible(Boolean interruptible) { this.interruptible = interruptible; }

        public Integer getCurrent() { return current; }
        public void setCurrent(Integer current) { this.current = current; }

        public Integer getTotal() { return total; }
        public void setTotal(Integer total) { this.total = total; }

        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }

        public JsonNode getProgressData() { return progressData; }
        public void setProgressData(JsonNode progressData) { this.progressData = progressData; }

        public Double getExecutionTimeMs() { return executionTimeMs; }
        public void setExecutionTimeMs(Double executionTimeMs) { this.executionTimeMs = executionTimeMs; }

        public Boolean getSuccess() { return success; }
        public void setSuccess(Boolean success) { this.success = success; }

        public JsonNode getResult() { return result; }
        public void setResult(JsonNode result) { this.result = result; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }


        public JsonStdioTypes.SystemMessageType systemMessageType() {
            return JsonStdioTypes.SystemMessageType.fromString(getMessageType()).orElse(null);
        }

        @Override
        public String toString() {
            return String.format("SystemMessage[type=%s, sessionId=%s]", getMessageType(), getSessionId());
        }
    }

    /**
     * Message sent from frontend to kalixcli (no session ID needed).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"m", "c", "p", "q", "reason"})
    public static class CommandMessage extends BaseMessage {

        // Command message fields
        @JsonProperty("c")
        private String commandName;

        @JsonProperty("p")
        private JsonNode parameters;

        // Query message fields
        @JsonProperty("q")
        private String queryType;

        // Stop message fields
        @JsonProperty("reason")
        private String stopReason;

        public CommandMessage() {
            // No timestamp needed for compact protocol
        }

        public CommandMessage(JsonStdioTypes.CommandMessageType type) {
            this();
            setMessageType(type.getValue());
        }

        public String getCommandName() { return commandName; }
        public void setCommandName(String commandName) { this.commandName = commandName; }

        public JsonNode getParameters() { return parameters; }
        public void setParameters(JsonNode parameters) { this.parameters = parameters; }

        public String getQueryType() { return queryType; }
        public void setQueryType(String queryType) { this.queryType = queryType; }

        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }


        public JsonStdioTypes.CommandMessageType commandMessageType() {
            for (JsonStdioTypes.CommandMessageType type : JsonStdioTypes.CommandMessageType.values()) {
                if (type.getValue().equals(getMessageType())) {
                    return type;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return String.format("CommandMessage[type=%s]", getMessageType());
        }
    }


    /**
     * Helper class for parsing simulation results.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimulationResult {
        @JsonProperty("ts")
        private TimeseriesInfo timeseries;

        public TimeseriesInfo getTimeseries() { return timeseries; }
        public void setTimeseries(TimeseriesInfo timeseries) { this.timeseries = timeseries; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TimeseriesInfo {
            @JsonProperty("len")
            private Integer timesteps;

            @JsonProperty("start")
            private String startDate;

            @JsonProperty("end")
            private String endDate;

            @JsonProperty("o")
            private List<String> availableTypes;

            @JsonProperty("outputs")
            private List<String> outputSeries;

            public Integer getTimesteps() { return timesteps; }
            public void setTimesteps(Integer timesteps) { this.timesteps = timesteps; }

            public String getStartDate() { return startDate; }
            public void setStartDate(String startDate) { this.startDate = startDate; }

            public String getEndDate() { return endDate; }
            public void setEndDate(String endDate) { this.endDate = endDate; }

            public List<String> getAvailableTypes() { return availableTypes; }
            public void setAvailableTypes(List<String> availableTypes) { this.availableTypes = availableTypes; }

            public List<String> getOutputSeries() { return outputSeries; }
            public void setOutputSeries(List<String> outputSeries) { this.outputSeries = outputSeries; }
        }
    }

    /**
     * Helper methods for working with progress messages.
     */
    public static class ProgressHelper {
        public static double calculatePercentage(SystemMessage msg) {
            if (msg.getCurrent() != null && msg.getTotal() != null && msg.getTotal() > 0) {
                return (double) msg.getCurrent() / msg.getTotal() * 100.0;
            }
            return 0.0;
        }

        public static boolean isComplete(SystemMessage msg) {
            if (msg.getCurrent() != null && msg.getTotal() != null) {
                return msg.getCurrent().equals(msg.getTotal());
            }
            return false;
        }
    }
}