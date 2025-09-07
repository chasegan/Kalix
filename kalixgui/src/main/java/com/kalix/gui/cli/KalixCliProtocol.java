package com.kalix.gui.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protocol handler for kalixcli STDIO communication.
 * Parses standardized termination messages and handles interactive prompts.
 */
public class KalixCliProtocol {
    
    // Protocol message patterns
    private static final Pattern PROTOCOL_MESSAGE = Pattern.compile("KALIX_([A-Z]+)_([A-Z]+)(?:\\s*:\\s*(.*))?");
    
    // Prompt detection patterns
    private static final Pattern MODEL_FILENAME_PROMPT = Pattern.compile("(?i).*(?:enter|input|specify|provide).*(?:model|filename|file|path).*[?:]\\s*$");
    private static final Pattern MODEL_DEFINITION_PROMPT = Pattern.compile("(?i).*(?:enter|input|provide).*(?:model|definition|content).*[?:]\\s*$");
    private static final Pattern PARAMETER_PROMPT = Pattern.compile("(?i).*(?:enter|input|specify|provide).*(?:parameter|value).*[?:]\\s*$");
    private static final Pattern CONFIRMATION_PROMPT = Pattern.compile("(?i).*(?:continue|proceed|confirm|yes/no|y/n).*\\?\\s*$");
    
    /**
     * Types of kalixcli protocol messages.
     */
    public enum MessageType {
        SESSION, COMMAND, ERROR, UNKNOWN
    }
    
    /**
     * Status values for protocol messages.
     */
    public enum MessageStatus {
        READY, COMPLETE, ENDING, FATAL, RECOVERABLE, UNKNOWN
    }
    
    /**
     * Types of prompts that kalixcli might show.
     */
    public enum PromptType {
        MODEL_FILENAME,
        MODEL_DEFINITION,
        PARAMETER_VALUE,
        CONFIRMATION,
        UNKNOWN
    }
    
    /**
     * Represents a parsed protocol message from kalixcli.
     */
    public static class ProtocolMessage {
        private final MessageType type;
        private final MessageStatus status;
        private final String additionalInfo;
        private final String rawMessage;
        
        public ProtocolMessage(MessageType type, MessageStatus status, String additionalInfo, String rawMessage) {
            this.type = type;
            this.status = status;
            this.additionalInfo = additionalInfo != null ? additionalInfo.trim() : "";
            this.rawMessage = rawMessage;
        }
        
        public MessageType getType() { return type; }
        public MessageStatus getStatus() { return status; }
        public String getAdditionalInfo() { return additionalInfo; }
        public String getRawMessage() { return rawMessage; }
        
        public boolean isSessionReady() { return type == MessageType.SESSION && status == MessageStatus.READY; }
        public boolean isCommandComplete() { return type == MessageType.COMMAND && status == MessageStatus.COMPLETE; }
        public boolean isSessionEnding() { return type == MessageType.SESSION && status == MessageStatus.ENDING; }
        public boolean isFatalError() { return type == MessageType.ERROR && status == MessageStatus.FATAL; }
        public boolean isRecoverableError() { return type == MessageType.ERROR && status == MessageStatus.RECOVERABLE; }
        
        @Override
        public String toString() {
            return String.format("ProtocolMessage[%s_%s: %s]", type, status, additionalInfo);
        }
    }
    
    /**
     * Represents a prompt from kalixcli waiting for user input.
     */
    public static class Prompt {
        private final String text;
        private final PromptType type;
        
        public Prompt(String text, PromptType type) {
            this.text = text;
            this.type = type;
        }
        
        public String getText() { return text; }
        public PromptType getType() { return type; }
        
        @Override
        public String toString() {
            return String.format("Prompt[type=%s, text=%s]", type, text);
        }
    }
    
    /**
     * Parses a line for kalixcli protocol messages.
     * 
     * @param line the line to parse
     * @return protocol message if found, empty otherwise
     */
    public static Optional<ProtocolMessage> parseProtocolMessage(String line) {
        if (line == null || line.trim().isEmpty()) {
            return Optional.empty();
        }
        
        String trimmedLine = line.trim();
        Matcher matcher = PROTOCOL_MESSAGE.matcher(trimmedLine);
        
        if (matcher.matches()) {
            try {
                MessageType type = MessageType.valueOf(matcher.group(1));
                MessageStatus status = MessageStatus.valueOf(matcher.group(2));
                String additionalInfo = matcher.group(3);
                
                return Optional.of(new ProtocolMessage(type, status, additionalInfo, line));
                
            } catch (IllegalArgumentException e) {
                // Unknown type or status - return as unknown
                return Optional.of(new ProtocolMessage(MessageType.UNKNOWN, MessageStatus.UNKNOWN, 
                    matcher.group(3), line));
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Checks if a line is a kalixcli protocol message.
     * 
     * @param line the line to check
     * @return true if the line is a protocol message
     */
    public static boolean isProtocolMessage(String line) {
        return parseProtocolMessage(line).isPresent();
    }
    
    /**
     * Parses a line to determine if it's a prompt and what type.
     * 
     * @param line the line to parse
     * @return prompt information if detected
     */
    public static Prompt parsePrompt(String line) {
        if (line == null || line.trim().isEmpty()) {
            return new Prompt(line, PromptType.UNKNOWN);
        }
        
        String trimmedLine = line.trim();
        
        // Check for model filename prompt
        if (MODEL_FILENAME_PROMPT.matcher(trimmedLine).matches()) {
            return new Prompt(trimmedLine, PromptType.MODEL_FILENAME);
        }
        
        // Check for model definition prompt
        if (MODEL_DEFINITION_PROMPT.matcher(trimmedLine).matches()) {
            return new Prompt(trimmedLine, PromptType.MODEL_DEFINITION);
        }
        
        // Check for parameter prompt
        if (PARAMETER_PROMPT.matcher(trimmedLine).matches()) {
            return new Prompt(trimmedLine, PromptType.PARAMETER_VALUE);
        }
        
        // Check for confirmation prompt
        if (CONFIRMATION_PROMPT.matcher(trimmedLine).matches()) {
            return new Prompt(trimmedLine, PromptType.CONFIRMATION);
        }
        
        // Check for other common prompt indicators
        if (trimmedLine.endsWith(":") || trimmedLine.endsWith("?") || 
            trimmedLine.toLowerCase().contains("enter") ||
            trimmedLine.toLowerCase().contains("input")) {
            return new Prompt(trimmedLine, PromptType.UNKNOWN);
        }
        
        return new Prompt(trimmedLine, PromptType.UNKNOWN);
    }
    
    /**
     * Formats a response to a specific prompt type.
     * 
     * @param promptType the type of prompt
     * @param response the response data
     * @return formatted response string
     */
    public static String formatResponse(PromptType promptType, Object response) {
        if (response == null) {
            return "";
        }
        
        switch (promptType) {
            case MODEL_FILENAME:
                // For model filename prompts, we typically want to use STDIN
                return "-";
                
            case MODEL_DEFINITION:
                // Model definition should be the raw model text
                return response.toString();
                
            case PARAMETER_VALUE:
                // Parameter values as strings
                return response.toString();
                
            case CONFIRMATION:
                // Boolean responses converted to y/n
                if (response instanceof Boolean) {
                    return ((Boolean) response) ? "y" : "n";
                }
                return response.toString().toLowerCase();
                
            default:
                return response.toString();
        }
    }
    
    /**
     * Formats a result request command for a background session.
     * 
     * @param resultType the type of result to request
     * @param params optional parameters for the request
     * @return formatted command string
     */
    public static String formatResultRequest(String resultType, Map<String, Object> params) {
        StringBuilder command = new StringBuilder("get-results ").append(resultType);
        
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                command.append(" --").append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        
        return command.toString();
    }
    
    /**
     * Creates a result request for flows data.
     * 
     * @return formatted flows request command
     */
    public static String requestFlowsData() {
        return formatResultRequest("flows", null);
    }
    
    /**
     * Creates a result request for water balance data.
     * 
     * @return formatted water balance request command
     */
    public static String requestWaterBalanceData() {
        return formatResultRequest("water_balance", null);
    }
    
    /**
     * Creates a result request for convergence metrics.
     * 
     * @return formatted convergence request command
     */
    public static String requestConvergenceMetrics() {
        return formatResultRequest("convergence", null);
    }
    
    /**
     * Creates a result request with custom parameters.
     * 
     * @param resultType the type of result
     * @param params request parameters
     * @return formatted request command
     */
    public static String requestCustomData(String resultType, Map<String, Object> params) {
        return formatResultRequest(resultType, params);
    }
    
    /**
     * Factory methods for common result requests.
     */
    public static class ResultRequests {
        public static final String FLOWS = "flows";
        public static final String WATER_BALANCE = "water_balance";
        public static final String CONVERGENCE = "convergence";
        public static final String SUMMARY = "summary";
        public static final String DIAGNOSTICS = "diagnostics";
        
        public static String withTimeRange(String resultType, String startTime, String endTime) {
            Map<String, Object> params = new HashMap<>();
            params.put("start", startTime);
            params.put("end", endTime);
            return formatResultRequest(resultType, params);
        }
        
        public static String withFormat(String resultType, String format) {
            Map<String, Object> params = new HashMap<>();
            params.put("format", format);
            return formatResultRequest(resultType, params);
        }
    }
}