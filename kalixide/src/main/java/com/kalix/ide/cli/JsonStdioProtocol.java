package com.kalix.ide.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;

/**
 * JSON-based STDIO protocol utility methods for kalixcli communication.
 * Provides parsing and message creation utilities for the JSON protocol.
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
            if (message.getType() == null || message.getKalixcliUid() == null) {
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
     * @param kalixcliUid the kalixcli UID (use empty string if unknown)
     * @return JSON string representation
     */
    public static String createCommandMessage(String command, Map<String, Object> parameters, String kalixcliUid) {
        try {
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.COMMAND);

            // Set the kalixcli UID if provided (non-empty)
            if (kalixcliUid != null && !kalixcliUid.isEmpty()) {
                message.setKalixcliUid(kalixcliUid);
            }

            ObjectNode dataNode = objectMapper.createObjectNode();
            dataNode.put("command", command);
            
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
            dataNode.set("parameters", paramsNode);
            
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
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.STOP);
            
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
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.QUERY);
            
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
            JsonMessage.CommandMessage message = new JsonMessage.CommandMessage(JsonStdioTypes.CommandMessageType.TERMINATE);
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
    public static <T> T extractData(JsonMessage.SystemMessage message, Class<T> dataClass) {
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
        public static String loadModelFile(String modelPath, String kalixcliUid) {
            return createCommandMessage("load_model_file", Map.of("model_path", modelPath), kalixcliUid);
        }

        public static String loadModelString(String modelIni, String kalixcliUid) {
            return createCommandMessage("load_model_string", Map.of("model_ini", modelIni), kalixcliUid);
        }
        
        public static String runSimulation(String kalixcliUid) {
            return createCommandMessage("run_simulation", Map.of(), kalixcliUid);
        }
        
        public static String testProgress(String kalixcliUid) {
            return createCommandMessage("test_progress", null, kalixcliUid);
        }

        public static String getResult(String seriesName, String format, String kalixcliUid) {
            return createCommandMessage("get_result", Map.of(
                "series_name", seriesName,
                "format", format
            ), kalixcliUid);
        }
    }
}