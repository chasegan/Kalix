package com.kalix.ide.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * High-level wrapper for STDIO communication with kalixcli sessions.
 * Provides structured methods for common kalixcli STDIO protocol interactions.
 */
public class KalixStdioSession {
    
    private final ProcessExecutor.RunningProcess runningProcess;
    private final StdioLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    // Common kalixcli prompt patterns
    private static final Pattern MODEL_FILENAME_PROMPT = Pattern.compile("(?i).*(?:enter|input|specify|provide).*(?:model|filename|file|path).*:?\\s*$");
    private static final Pattern MODEL_DEFINITION_PROMPT = Pattern.compile("(?i).*(?:enter|input|provide).*(?:model|definition|content).*:?\\s*$");
    private static final Pattern PARAMETER_PROMPT = Pattern.compile("(?i).*(?:enter|input|specify|provide).*(?:parameter|value).*:?\\s*$");
    private static final Pattern CONFIRMATION_PROMPT = Pattern.compile("(?i).*(?:continue|proceed|confirm|yes/no|y/n).*\\?\\s*$");
    
    /**
     * Represents different types of prompts that kalixcli might show.
     */
    public enum PromptType {
        MODEL_FILENAME,
        MODEL_DEFINITION, 
        PARAMETER_VALUE,
        CONFIRMATION,
        UNKNOWN
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
     * Represents progress information parsed from kalixcli output.
     */
    public static class ProgressInfo {
        private final double percentage;
        private final String currentTask;
        private final String rawLine;
        
        public ProgressInfo(double percentage, String currentTask, String rawLine) {
            this.percentage = percentage;
            this.currentTask = currentTask;
            this.rawLine = rawLine;
        }
        
        public double getPercentage() { return percentage; }
        public String getCurrentTask() { return currentTask; }
        public String getRawLine() { return rawLine; }
        
        @Override
        public String toString() {
            return String.format("Progress[%.1f%% - %s]", percentage, currentTask);
        }
    }
    
    /**
     * Creates a new KalixStdioSession wrapping a running process.
     * 
     * @param runningProcess the interactive process to wrap
     */
    public KalixStdioSession(ProcessExecutor.RunningProcess runningProcess) {
        if (!runningProcess.isInteractive()) {
            throw new IllegalArgumentException("Process must be in interactive mode");
        }
        
        this.runningProcess = runningProcess;
        this.logger = StdioLogger.getInstance();
    }
    
    /**
     * Starts a new interactive kalixcli process.
     *
     * @param cliPath path to kalixcli executable
     * @param processExecutor the process executor to use
     * @param args additional command line arguments
     * @return a new KalixStdioSession
     * @throws IOException if the process cannot be started
     */
    public static KalixStdioSession start(Path cliPath, ProcessExecutor processExecutor, String... args) throws IOException {
        return start(cliPath, processExecutor, null, args);
    }

    /**
     * Starts a new interactive kalixcli process with a working directory.
     *
     * @param cliPath path to kalixcli executable
     * @param processExecutor the process executor to use
     * @param workingDir working directory for the process (null for default)
     * @param args additional command line arguments
     * @return a new KalixStdioSession
     * @throws IOException if the process cannot be started
     */
    public static KalixStdioSession start(Path cliPath, ProcessExecutor processExecutor, Path workingDir, String... args) throws IOException {
        ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig();
        if (workingDir != null) {
            config.workingDirectory(workingDir);
        }

        java.util.List<String> argList = args != null ? java.util.List.of(args) : java.util.List.of();
        ProcessExecutor.RunningProcess process = processExecutor.startInteractive(
            cliPath.toString(),
            argList,
            config
        );
        return new KalixStdioSession(process);
    }
    
    /**
     * Sends a command to kalixcli.
     * 
     * @param command the command to send
     * @throws IOException if sending fails
     */
    public void sendCommand(String command) throws IOException {
        checkNotClosed();
        runningProcess.sendInput(command);
    }
    
    /**
     * Sends a model definition to kalixcli using the JSON protocol.
     * Creates a load_model_string command with the model content properly JSON-escaped.
     * 
     * @param modelText the model definition text
     * @throws IOException if sending fails
     */
    public void sendModelDefinition(String modelText) throws IOException {
        checkNotClosed();

        // Create JSON command using the new protocol
        String jsonCommand = JsonStdioProtocol.Commands.loadModelString(modelText);

        // Send the JSON command
        runningProcess.sendInput(jsonCommand);
    }
    
    /**
     * Sends multiple lines of input to kalixcli.
     * 
     * @param lines the lines to send
     * @throws IOException if sending fails
     */
    public void sendLines(List<String> lines) throws IOException {
        checkNotClosed();
        runningProcess.sendLines(lines);
    }
    
    /**
     * Reads the next line from kalixcli output.
     * This is a blocking operation.
     * 
     * @return the line read, or empty if stream is closed
     * @throws IOException if reading fails
     */
    public Optional<String> readOutputLine() throws IOException {
        checkNotClosed();
        String line = runningProcess.readOutputLine();
        return Optional.ofNullable(line);
    }
    
    /**
     * Reads the next error line from kalixcli.
     * This is a blocking operation.
     * 
     * @return the line read, or empty if stream is closed
     * @throws IOException if reading fails
     */
    public Optional<String> readErrorLine() throws IOException {
        checkNotClosed();
        String line = runningProcess.readErrorLine();
        return Optional.ofNullable(line);
    }
    
    /**
     * Checks if kalixcli has output ready to read without blocking.
     * 
     * @return true if output is available
     * @throws IOException if checking fails
     */
    public boolean hasOutputReady() throws IOException {
        checkNotClosed();
        return runningProcess.isOutputReady();
    }
    
    /**
     * Checks if kalixcli has error output ready to read without blocking.
     * 
     * @return true if error output is available
     * @throws IOException if checking fails
     */
    public boolean hasErrorReady() throws IOException {
        checkNotClosed();
        return runningProcess.isErrorReady();
    }
    
    /**
     * Waits for a prompt from kalixcli and returns it.
     * This method blocks until a prompt is detected or timeout occurs.
     * 
     * @param timeoutSeconds timeout in seconds
     * @return the prompt if detected, empty if timeout or no prompt
     * @throws IOException if reading fails
     */
    public Optional<Prompt> waitForPrompt(int timeoutSeconds) throws IOException {
        checkNotClosed();
        
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        
        StringBuilder currentOutput = new StringBuilder();
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (hasOutputReady()) {
                Optional<String> line = readOutputLine();
                if (line.isPresent()) {
                    currentOutput.append(line.get()).append("\n");
                    
                    // Check if this looks like a prompt
                    Prompt prompt = parsePrompt(line.get());
                    if (prompt.getType() != PromptType.UNKNOWN) {
                        return Optional.of(prompt);
                    }
                }
            } else {
                // Small sleep to avoid busy waiting
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.warn("No prompt detected within " + timeoutSeconds + " seconds");
        return Optional.empty();
    }
    
    // Progress parsing is now handled only through JSON protocol messages
    
    /**
     * Responds to a prompt with the given answer.
     * 
     * @param prompt the prompt to respond to
     * @param response the response to send
     * @throws IOException if sending fails
     */
    public void respondToPrompt(Prompt prompt, String response) throws IOException {
        checkNotClosed();
        runningProcess.sendInput(response);
    }
    
    /**
     * Checks if the kalixcli process is still running.
     * 
     * @return true if the process is alive
     */
    public boolean isRunning() {
        return runningProcess.isRunning();
    }
    
    /**
     * Closes the process and cleans up resources.
     * 
     * @param forceKill if true, forcibly kills the process
     */
    public void close(boolean forceKill) {
        if (closed.compareAndSet(false, true)) {
            runningProcess.cancel(forceKill);
        }
    }
    
    /**
     * Closes the process gracefully.
     */
    public void close() {
        close(false);
    }
    
    /**
     * Gets the underlying running process for advanced operations.
     * 
     * @return the running process
     */
    public ProcessExecutor.RunningProcess getRunningProcess() {
        return runningProcess;
    }
    
    /**
     * Parses a line to determine if it's a prompt and what type.
     */
    private Prompt parsePrompt(String line) {
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
        if (trimmedLine.endsWith(":") || trimmedLine.endsWith("?") || trimmedLine.contains(">")) {
            return new Prompt(trimmedLine, PromptType.UNKNOWN);
        }
        
        return new Prompt(trimmedLine, PromptType.UNKNOWN);
    }
    
    /**
     * Checks if the process has been closed and throws if so.
     */
    private void checkNotClosed() throws IOException {
        if (closed.get()) {
            throw new IOException("InteractiveKalixProcess has been closed");
        }
    }
}