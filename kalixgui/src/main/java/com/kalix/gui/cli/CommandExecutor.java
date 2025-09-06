package com.kalix.gui.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes CLI commands and parses results based on command type.
 * Handles streaming vs one-shot commands and provides structured result parsing.
 */
public class CommandExecutor {
    
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final CliLogger logger;
    private final Path cliPath;
    
    /**
     * Represents the execution result with parsed data.
     */
    public static class ExecutionResult {
        private final boolean success;
        private final String command;
        private final int exitCode;
        private final String rawOutput;
        private final String rawError;
        private final Map<String, Object> parsedData;
        private final List<String> messages;
        private final Exception exception;
        private final long executionTimeMs;
        
        public ExecutionResult(boolean success, String command, int exitCode, String rawOutput, 
                             String rawError, Map<String, Object> parsedData, List<String> messages,
                             Exception exception, long executionTimeMs) {
            this.success = success;
            this.command = command;
            this.exitCode = exitCode;
            this.rawOutput = rawOutput;
            this.rawError = rawError;
            this.parsedData = parsedData != null ? new HashMap<>(parsedData) : new HashMap<>();
            this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
            this.exception = exception;
            this.executionTimeMs = executionTimeMs;
        }
        
        public boolean isSuccess() { return success; }
        public String getCommand() { return command; }
        public int getExitCode() { return exitCode; }
        public String getRawOutput() { return rawOutput; }
        public String getRawError() { return rawError; }
        public Map<String, Object> getParsedData() { return new HashMap<>(parsedData); }
        public List<String> getMessages() { return new ArrayList<>(messages); }
        public Exception getException() { return exception; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        
        /**
         * Gets a parsed data value by key.
         */
        @SuppressWarnings("unchecked")
        public <T> Optional<T> getData(String key, Class<T> type) {
            Object value = parsedData.get(key);
            if (value != null && type.isAssignableFrom(value.getClass())) {
                return Optional.of((T) value);
            }
            return Optional.empty();
        }
        
        /**
         * Checks if the result contains specific data.
         */
        public boolean hasData(String key) {
            return parsedData.containsKey(key);
        }
        
        @Override
        public String toString() {
            return String.format("ExecutionResult[success=%s, exitCode=%d, executionTime=%dms, dataKeys=%s]",
                success, exitCode, executionTimeMs, parsedData.keySet());
        }
    }
    
    /**
     * Configuration for command execution.
     */
    public static class ExecutionConfig {
        private long timeoutSeconds = 300; // 5 minutes default
        private Consumer<String> outputCallback;
        private Consumer<String> errorCallback;
        private Consumer<String> progressCallback;
        private boolean parseJson = true;
        private boolean streaming = false;
        private Path workingDirectory;
        
        public ExecutionConfig timeout(long seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }
        
        public ExecutionConfig onOutput(Consumer<String> callback) {
            this.outputCallback = callback;
            return this;
        }
        
        public ExecutionConfig onError(Consumer<String> callback) {
            this.errorCallback = callback;
            return this;
        }
        
        public ExecutionConfig onProgress(Consumer<String> callback) {
            this.progressCallback = callback;
            return this;
        }
        
        public ExecutionConfig workingDirectory(Path directory) {
            this.workingDirectory = directory;
            return this;
        }
        
        public ExecutionConfig parseJson(boolean parse) {
            this.parseJson = parse;
            return this;
        }
        
        public ExecutionConfig streaming(boolean isStreaming) {
            this.streaming = isStreaming;
            return this;
        }
        
        // Getters
        public long getTimeoutSeconds() { return timeoutSeconds; }
        public Consumer<String> getOutputCallback() { return outputCallback; }
        public Consumer<String> getErrorCallback() { return errorCallback; }
        public Consumer<String> getProgressCallback() { return progressCallback; }
        public boolean isParseJson() { return parseJson; }
        public boolean isStreaming() { return streaming; }
        public Path getWorkingDirectory() { return workingDirectory; }
    }
    
    /**
     * Creates a new CommandExecutor.
     */
    public CommandExecutor(Path cliPath) {
        this.cliPath = cliPath;
        this.processExecutor = new ProcessExecutor();
        this.objectMapper = new ObjectMapper();
        this.logger = CliLogger.getInstance();
    }
    
    /**
     * Creates a CommandExecutor with custom ProcessExecutor.
     */
    public CommandExecutor(Path cliPath, ProcessExecutor processExecutor) {
        this.cliPath = cliPath;
        this.processExecutor = processExecutor;
        this.objectMapper = new ObjectMapper();
        this.logger = CliLogger.getInstance();
    }
    
    /**
     * Executes a command built by CommandBuilder.
     */
    public CompletableFuture<ExecutionResult> execute(CommandBuilder.BuildResult buildResult, ExecutionConfig config) {
        if (!buildResult.isValid()) {
            return CompletableFuture.completedFuture(
                new ExecutionResult(false, buildResult.getCommandString(), -1, "", 
                    "Build validation failed: " + String.join(", ", buildResult.getErrors()),
                    null, null, new IllegalArgumentException("Invalid command build"), 0)
            );
        }
        
        return execute(buildResult.getCommandArgs(), config);
    }
    
    /**
     * Executes a command with the given arguments.
     */
    public CompletableFuture<ExecutionResult> execute(List<String> commandArgs, ExecutionConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            String commandString = String.join(" ", commandArgs);
            
            try {
                logger.info("Executing command: " + commandString);
                
                // Prepare process configuration
                ProcessExecutor.ProcessConfig processConfig = new ProcessExecutor.ProcessConfig()
                    .timeout(config.getTimeoutSeconds())
                    .onStdout(config.getOutputCallback())
                    .onStderr(config.getErrorCallback())
                    .onProgress(config.getProgressCallback());
                
                if (config.getWorkingDirectory() != null) {
                    processConfig.workingDirectory(config.getWorkingDirectory());
                }
                
                // Execute the command (skip the first arg which should be the command name)
                List<String> args = commandArgs.size() > 1 ? commandArgs.subList(1, commandArgs.size()) : List.of();
                ProcessExecutor.ProcessResult processResult = processExecutor.execute(
                    cliPath.toString(), args, processConfig);
                
                long executionTime = System.currentTimeMillis() - startTime;
                
                if (processResult.isSuccess()) {
                    logger.info("Command completed successfully in " + executionTime + "ms");
                    return parseSuccessResult(commandString, processResult, config, executionTime);
                } else {
                    logger.warn("Command failed with exit code: " + processResult.getExitCode());
                    return parseFailureResult(commandString, processResult, config, executionTime);
                }
                
            } catch (Exception e) {
                long executionTime = System.currentTimeMillis() - startTime;
                logger.error("Command execution failed", e);
                return new ExecutionResult(false, commandString, -1, "", e.getMessage(), 
                    null, List.of("Execution failed: " + e.getMessage()), e, executionTime);
            }
        });
    }
    
    /**
     * Parses a successful command result.
     */
    private ExecutionResult parseSuccessResult(String command, ProcessExecutor.ProcessResult processResult,
                                             ExecutionConfig config, long executionTime) {
        String output = processResult.getStdout();
        String error = processResult.getStderr();
        Map<String, Object> parsedData = new HashMap<>();
        List<String> messages = new ArrayList<>();
        
        // Determine command type from the command string
        String commandType = extractCommandType(command);
        
        // Parse based on command type
        switch (commandType.toLowerCase()) {
            case "sim":
            case "simulate":
                parseSimulationResult(output, error, parsedData, messages);
                break;
            case "calibrate":
                parseCalibrationResult(output, error, parsedData, messages);
                break;
            case "test":
                parseTestResult(output, error, parsedData, messages);
                break;
            case "get-api":
                parseApiResult(output, error, parsedData, messages, config.isParseJson());
                break;
            default:
                parseGenericResult(output, error, parsedData, messages, config.isParseJson());
                break;
        }
        
        return new ExecutionResult(true, command, processResult.getExitCode(), output, error,
            parsedData, messages, null, executionTime);
    }
    
    /**
     * Parses a failed command result.
     */
    private ExecutionResult parseFailureResult(String command, ProcessExecutor.ProcessResult processResult,
                                             ExecutionConfig config, long executionTime) {
        String output = processResult.getStdout();
        String error = processResult.getStderr();
        List<String> messages = new ArrayList<>();
        
        // Parse common error patterns
        parseErrorMessages(error, messages);
        
        if (messages.isEmpty() && !error.trim().isEmpty()) {
            messages.add("Command failed: " + error.trim());
        }
        
        if (messages.isEmpty()) {
            messages.add("Command failed with exit code " + processResult.getExitCode());
        }
        
        return new ExecutionResult(false, command, processResult.getExitCode(), output, error,
            null, messages, null, executionTime);
    }
    
    /**
     * Extracts the command type from the command string.
     */
    private String extractCommandType(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length >= 2) {
            return parts[1]; // Skip the executable name
        }
        return "unknown";
    }
    
    /**
     * Parses simulation results.
     */
    private void parseSimulationResult(String output, String error, Map<String, Object> parsedData, List<String> messages) {
        // Look for common simulation output patterns
        parseNumericResults(output, parsedData);
        parseTimeMetrics(output, parsedData);
        parseFileReferences(output, parsedData);
        
        messages.add("Simulation completed successfully");
        if (!output.trim().isEmpty()) {
            messages.add("Output: " + output.trim());
        }
    }
    
    /**
     * Parses calibration results.
     */
    private void parseCalibrationResult(String output, String error, Map<String, Object> parsedData, List<String> messages) {
        // Look for calibration-specific patterns
        parseNumericResults(output, parsedData);
        parseIterationResults(output, parsedData);
        parseConvergenceMetrics(output, parsedData);
        
        messages.add("Calibration completed successfully");
        if (!output.trim().isEmpty()) {
            messages.add("Output: " + output.trim());
        }
    }
    
    /**
     * Parses test results.
     */
    private void parseTestResult(String output, String error, Map<String, Object> parsedData, List<String> messages) {
        // Look for test result patterns
        parseTestMetrics(output, parsedData);
        parseTimeMetrics(output, parsedData);
        
        messages.add("Tests completed successfully");
        if (!output.trim().isEmpty()) {
            messages.add("Output: " + output.trim());
        }
    }
    
    /**
     * Parses API results (JSON).
     */
    private void parseApiResult(String output, String error, Map<String, Object> parsedData, 
                              List<String> messages, boolean parseJson) {
        if (parseJson && !output.trim().isEmpty()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(output);
                parsedData.put("api_spec", jsonNode);
                messages.add("API specification retrieved successfully");
            } catch (Exception e) {
                logger.warn("Failed to parse API JSON output", e);
                parsedData.put("raw_api_output", output);
                messages.add("API output received (failed to parse as JSON)");
            }
        } else {
            parsedData.put("raw_api_output", output);
            messages.add("API output received");
        }
    }
    
    /**
     * Parses generic command results.
     */
    private void parseGenericResult(String output, String error, Map<String, Object> parsedData, 
                                  List<String> messages, boolean parseJson) {
        // Try to parse as JSON first if requested
        if (parseJson && !output.trim().isEmpty()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(output);
                parsedData.put("json_output", jsonNode);
                messages.add("JSON output parsed successfully");
                return;
            } catch (Exception e) {
                // Not JSON, continue with text parsing
            }
        }
        
        // Parse as text
        parsedData.put("raw_output", output);
        if (!output.trim().isEmpty()) {
            messages.add("Command output: " + output.trim());
        } else {
            messages.add("Command completed successfully (no output)");
        }
    }
    
    /**
     * Parses numeric results from output.
     */
    private void parseNumericResults(String output, Map<String, Object> parsedData) {
        // Pattern for floating point numbers with labels
        Pattern numberPattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*?)\\s*[=:]\\s*([+-]?\\d*\\.?\\d+(?:[eE][+-]?\\d+)?)");
        Matcher matcher = numberPattern.matcher(output);
        
        Map<String, Double> numbers = new HashMap<>();
        while (matcher.find()) {
            try {
                String key = matcher.group(1).toLowerCase();
                double value = Double.parseDouble(matcher.group(2));
                numbers.put(key, value);
            } catch (NumberFormatException e) {
                // Skip invalid numbers
            }
        }
        
        if (!numbers.isEmpty()) {
            parsedData.put("numeric_results", numbers);
        }
    }
    
    /**
     * Parses time-related metrics.
     */
    private void parseTimeMetrics(String output, Map<String, Object> parsedData) {
        // Look for time patterns
        Pattern timePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|milliseconds?|s|seconds?|m|minutes?|h|hours?)");
        Matcher matcher = timePattern.matcher(output);
        
        Map<String, String> times = new HashMap<>();
        while (matcher.find()) {
            String value = matcher.group(1);
            String unit = matcher.group(2);
            times.put("execution_time", value + " " + unit);
        }
        
        if (!times.isEmpty()) {
            parsedData.put("time_metrics", times);
        }
    }
    
    /**
     * Parses file references from output.
     */
    private void parseFileReferences(String output, Map<String, Object> parsedData) {
        // Look for file path patterns
        Pattern filePattern = Pattern.compile("(?:file|output|result|written to|saved as)\\s*:?\\s*([^\\s\"]+(?:\\.\\w+))");
        Matcher matcher = filePattern.matcher(output);
        
        List<String> files = new ArrayList<>();
        while (matcher.find()) {
            files.add(matcher.group(1));
        }
        
        if (!files.isEmpty()) {
            parsedData.put("output_files", files);
        }
    }
    
    /**
     * Parses iteration results from calibration output.
     */
    private void parseIterationResults(String output, Map<String, Object> parsedData) {
        // Look for iteration patterns
        Pattern iterPattern = Pattern.compile("iteration\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = iterPattern.matcher(output);
        
        if (matcher.find()) {
            try {
                int iterations = Integer.parseInt(matcher.group(1));
                parsedData.put("iterations_completed", iterations);
            } catch (NumberFormatException e) {
                // Ignore invalid iteration numbers
            }
        }
    }
    
    /**
     * Parses convergence metrics.
     */
    private void parseConvergenceMetrics(String output, Map<String, Object> parsedData) {
        // Look for convergence indicators
        if (output.toLowerCase().contains("converged")) {
            parsedData.put("converged", true);
        }
        
        // Look for error/tolerance values
        Pattern errorPattern = Pattern.compile("(?:error|tolerance|residual)\\s*[=:]\\s*([+-]?\\d*\\.?\\d+(?:[eE][+-]?\\d+)?)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = errorPattern.matcher(output);
        
        Map<String, Double> metrics = new HashMap<>();
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                metrics.put("convergence_error", value);
            } catch (NumberFormatException e) {
                // Skip invalid numbers
            }
        }
        
        if (!metrics.isEmpty()) {
            parsedData.put("convergence_metrics", metrics);
        }
    }
    
    /**
     * Parses test metrics.
     */
    private void parseTestMetrics(String output, Map<String, Object> parsedData) {
        Map<String, Object> testMetrics = new HashMap<>();
        
        // Look for pass/fail counts
        Pattern testPattern = Pattern.compile("(\\d+)\\s+(?:test[s]?\\s+)?(passed|failed|skipped)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = testPattern.matcher(output);
        
        while (matcher.find()) {
            try {
                int count = Integer.parseInt(matcher.group(1));
                String status = matcher.group(2).toLowerCase();
                testMetrics.put("tests_" + status, count);
            } catch (NumberFormatException e) {
                // Skip invalid test counts
            }
        }
        
        if (!testMetrics.isEmpty()) {
            parsedData.put("test_metrics", testMetrics);
        }
    }
    
    /**
     * Parses error messages from stderr.
     */
    private void parseErrorMessages(String error, List<String> messages) {
        if (error == null || error.trim().isEmpty()) {
            return;
        }
        
        // Split by lines and filter meaningful error messages
        String[] lines = error.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("WARNING:") && !line.startsWith("INFO:")) {
                messages.add(line);
            }
        }
    }
    
    /**
     * Shuts down the executor.
     */
    public void shutdown() {
        processExecutor.shutdown();
    }
}