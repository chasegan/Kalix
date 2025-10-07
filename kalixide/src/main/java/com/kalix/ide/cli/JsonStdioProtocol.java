package com.kalix.ide.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;

/**
 * JSON-based STDIO protocol utility methods for kalixcli communication.
 * Provides parsing and message creation utilities for the compact JSON protocol.
 */
public class JsonStdioProtocol {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parses a JSON line into a SystemMessage.
     *
     * @param line the JSON line to parse
     * @return parsed system message if valid JSON, empty otherwise
     */
    public static Optional<JsonMessage.SystemMessage> parseSystemMessage(String line) {
        if (line == null || line.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            JsonMessage.SystemMessage message = objectMapper.readValue(line.trim(), JsonMessage.SystemMessage.class);

            // Validate required fields
            if (message.getMessageType() == null) {
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
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.COMMAND);

            message.setCommandName(command);

            // Convert parameters to JSON node
            ObjectNode paramsNode = objectMapper.createObjectNode();
            if (parameters != null && !parameters.isEmpty()) {
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
            }
            message.setParameters(paramsNode);

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
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.STOP);
            message.setStopReason(reason != null ? reason : "User requested cancellation");

            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create stop message", e);
        }
    }

    /**
     * Creates a query message to request information.
     *
     * @param queryType the type of query
     * @return JSON string representation
     */
    public static String createQueryMessage(String queryType) {
        try {
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.QUERY);
            message.setQueryType(queryType);

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
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.TERMINATE);
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
    public static <T> T extractData(JsonMessage.SystemMessage message, Class<T> dataClass) {
        try {
            return objectMapper.treeToValue(message.getResult(), dataClass);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to extract data from message", e);
        }
    }

    /**
     * Checks if a line looks like a JSON message (starts with { and contains "m").
     *
     * @param line the line to check
     * @return true if it might be a JSON protocol message
     */
    public static boolean looksLikeCompactJson(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("\"m\":");
    }


    /**
     * Helper method to get progress percentage from a progress message.
     *
     * @param message the progress message
     * @return percentage complete (0-100)
     */
    public static double getProgressPercentage(JsonMessage.SystemMessage message) {
        return JsonMessage.ProgressHelper.calculatePercentage(message);
    }

    /**
     * Helper method to check if a progress message indicates completion.
     *
     * @param message the progress message
     * @return true if progress is complete
     */
    public static boolean isProgressComplete(JsonMessage.SystemMessage message) {
        return JsonMessage.ProgressHelper.isComplete(message);
    }

    /**
     * Helper method to extract simulation result information.
     *
     * @param message the result message from run_simulation
     * @return simulation result data, or null if not a simulation result
     */
    public static JsonMessage.SimulationResult extractSimulationResult(JsonMessage.SystemMessage message) {
        if (message.getResult() != null) {
            try {
                return objectMapper.treeToValue(message.getResult(), JsonMessage.SimulationResult.class);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Utility methods for common queries.
     */
    public static class Queries {
        public static String getState() {
            return createQueryMessage("get_state");
        }

        public static String getSessionId() {
            return createQueryMessage("get_session_id");
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
            return createCommandMessage("run_simulation", Map.of());
        }

        public static String testProgress() {
            return createCommandMessage("test_progress", Map.of());
        }

        public static String testProgressWithDuration(int durationSeconds) {
            return createCommandMessage("test_progress", Map.of("duration_seconds", durationSeconds));
        }

        public static String getResult(String seriesName, String format) {
            return createCommandMessage("get_result", Map.of(
                "series_name", seriesName,
                "format", format
            ));
        }


        public static String echo(String text) {
            return createCommandMessage("echo", Map.of("string", text));
        }

        public static String getVersion() {
            return createCommandMessage("get_version", Map.of());
        }
    }
}