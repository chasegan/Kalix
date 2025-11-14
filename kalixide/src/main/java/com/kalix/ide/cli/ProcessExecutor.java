package com.kalix.ide.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Cross-platform process executor for running kalixcli commands.
 * Provides process lifecycle management, stream monitoring, and cancellation support.
 */
public class ProcessExecutor {
    
    private final ExecutorService executorService;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    
    /**
     * Creates a new ProcessExecutor with a dedicated thread pool.
     */
    public ProcessExecutor() {
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "KalixCli-ProcessExecutor");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Represents the result of a process execution.
     */
    public static class ProcessResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean cancelled;
        private final Exception exception;
        
        public ProcessResult(int exitCode, String stdout, String stderr) {
            this(exitCode, stdout, stderr, false, null);
        }
        
        public ProcessResult(boolean cancelled) {
            this(0, "", "", cancelled, null);
        }
        
        public ProcessResult(Exception exception) {
            this(0, "", "", false, exception);
        }
        
        private ProcessResult(int exitCode, String stdout, String stderr, boolean cancelled, Exception exception) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.cancelled = cancelled;
            this.exception = exception;
        }
        
        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public boolean wasCancelled() { return cancelled; }
        public Exception getException() { return exception; }
        public boolean isSuccess() { return !cancelled && exception == null && exitCode == 0; }
    }
    
    /**
     * Configuration for process execution.
     */
    public static class ProcessConfig {
        private Path workingDirectory;
        private long timeoutSeconds = 300; // 5 minutes default
        private Map<String, String> environmentVariables;
        private Consumer<String> stdoutCallback;
        private Consumer<String> stderrCallback;
        private Consumer<String> progressCallback;
        
        public ProcessConfig workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }
        
        public ProcessConfig timeout(long seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }
        
        public ProcessConfig environment(Map<String, String> env) {
            this.environmentVariables = env;
            return this;
        }
        
        public ProcessConfig onStdout(Consumer<String> callback) {
            this.stdoutCallback = callback;
            return this;
        }
        
        public ProcessConfig onStderr(Consumer<String> callback) {
            this.stderrCallback = callback;
            return this;
        }
        
        public ProcessConfig onProgress(Consumer<String> callback) {
            this.progressCallback = callback;
            return this;
        }
        
        // Getters
        public Path getWorkingDirectory() { return workingDirectory; }
        public long getTimeoutSeconds() { return timeoutSeconds; }
        public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
        public Consumer<String> getStdoutCallback() { return stdoutCallback; }
        public Consumer<String> getStderrCallback() { return stderrCallback; }
        public Consumer<String> getProgressCallback() { return progressCallback; }
    }
    
    /**
     * Represents a running process that can be monitored and cancelled.
     * Enhanced with interactive communication capabilities.
     */
    public static class RunningProcess {
        private final Process process;
        private final CompletableFuture<ProcessResult> future;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final BufferedWriter stdinWriter;
        private final BufferedReader stdoutReader;
        private final BufferedReader stderrReader;
        private final boolean interactive;
        
        RunningProcess(Process process, CompletableFuture<ProcessResult> future) {
            this(process, future, false);
        }
        
        RunningProcess(Process process, CompletableFuture<ProcessResult> future, boolean interactive) {
            this.process = process;
            this.future = future;
            this.interactive = interactive;
            
            if (interactive) {
                this.stdinWriter = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                this.stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                this.stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            } else {
                this.stdinWriter = null;
                this.stdoutReader = null;
                this.stderrReader = null;
            }
        }
        
        /**
         * Cancels the running process.
         * 
         * @param forceKill if true, forcibly kills the process; if false, attempts graceful termination
         * @return true if the process was cancelled successfully
         */
        public boolean cancel(boolean forceKill) {
            if (cancelled.compareAndSet(false, true)) {
                // Close interactive streams first if in interactive mode
                if (interactive) {
                    try {
                        if (stdinWriter != null) {
                            stdinWriter.close();
                        }
                        if (stdoutReader != null) {
                            stdoutReader.close();
                        }
                        if (stderrReader != null) {
                            stderrReader.close();
                        }
                    } catch (IOException e) {
                        // Log but don't fail cancellation
                        System.err.println("Warning: Failed to close interactive streams: " + e.getMessage());
                    }
                }
                
                if (process.isAlive()) {
                    if (forceKill) {
                        process.destroyForcibly();
                    } else {
                        process.destroy();
                    }
                }
                future.cancel(true);
                return true;
            }
            return false;
        }
        
        /**
         * Checks if the process is still running.
         */
        public boolean isRunning() {
            return process.isAlive() && !future.isDone();
        }
        
        /**
         * Gets the future result of the process execution.
         */
        public CompletableFuture<ProcessResult> getFuture() {
            return future;
        }
        
        /**
         * Waits for the process to complete and returns the result.
         */
        public ProcessResult waitFor() throws Exception {
            return future.get();
        }
        
        /**
         * Waits for the process to complete with a timeout.
         */
        public ProcessResult waitFor(long timeout, TimeUnit unit) throws Exception {
            return future.get(timeout, unit);
        }
        
        /**
         * Sends input to the process via STDIN (interactive mode only).
         * 
         * @param input the input to send
         * @throws IOException if the process is not interactive or writing fails
         */
        public void sendInput(String input) throws IOException {
            if (!interactive || stdinWriter == null) {
                throw new IOException("Process is not in interactive mode");
            }
            
            stdinWriter.write(input);
            if (!input.endsWith("\n")) {
                stdinWriter.newLine();
            }
            stdinWriter.flush();
        }
        
        /**
         * Sends multiple lines of input to the process (interactive mode only).
         * 
         * @param lines the lines to send
         * @throws IOException if the process is not interactive or writing fails
         */
        public void sendLines(List<String> lines) throws IOException {
            if (!interactive || stdinWriter == null) {
                throw new IOException("Process is not in interactive mode");
            }
            
            for (String line : lines) {
                stdinWriter.write(line);
                stdinWriter.newLine();
            }
            stdinWriter.flush();
        }
        
        /**
         * Reads a single line from STDOUT (interactive mode only).
         * This is a blocking operation.
         * 
         * @return the line read, or null if stream is closed
         * @throws IOException if the process is not interactive or reading fails
         */
        public String readOutputLine() throws IOException {
            if (!interactive || stdoutReader == null) {
                throw new IOException("Process is not in interactive mode");
            }
            
            return stdoutReader.readLine();
        }
        
        /**
         * Reads a single line from STDERR (interactive mode only).
         * This is a blocking operation.
         * 
         * @return the line read, or null if stream is closed
         * @throws IOException if the process is not interactive or reading fails
         */
        public String readErrorLine() throws IOException {
            if (!interactive || stderrReader == null) {
                throw new IOException("Process is not in interactive mode");
            }
            
            return stderrReader.readLine();
        }
        
        /**
         * Checks if STDOUT has data ready to read without blocking (interactive mode only).
         * 
         * @return true if data is available
         * @throws IOException if the process is not interactive or checking fails
         */
        public boolean isOutputReady() throws IOException {
            if (!interactive || stdoutReader == null) {
                return false;
            }
            
            return stdoutReader.ready();
        }
        
        /**
         * Checks if STDERR has data ready to read without blocking (interactive mode only).
         * 
         * @return true if data is available
         * @throws IOException if the process is not interactive or checking fails
         */
        public boolean isErrorReady() throws IOException {
            if (!interactive || stderrReader == null) {
                return false;
            }
            
            return stderrReader.ready();
        }
        
        /**
         * Closes the STDIN writer, signaling end of input to the process.
         * 
         * @throws IOException if closing fails
         */
        public void closeStdin() throws IOException {
            if (interactive && stdinWriter != null) {
                stdinWriter.close();
            }
        }
        
        /**
         * Checks if this process is in interactive mode.
         * 
         * @return true if interactive communication is available
         */
        public boolean isInteractive() {
            return interactive;
        }
        
        /**
         * Gets the underlying Process object for advanced operations.
         * 
         * @return the Process object
         */
        public Process getProcess() {
            return process;
        }
    }
    
    /**
     * Executes a command asynchronously with the given configuration.
     * 
     * @param command the command to execute
     * @param args command arguments
     * @param config process configuration
     * @return a RunningProcess that can be monitored and cancelled
     * @throws IOException if the process cannot be started
     */
    public RunningProcess executeAsync(String command, List<String> args, ProcessConfig config) throws IOException {
        if (shutdown.get()) {
            throw new IllegalStateException("ProcessExecutor has been shut down");
        }
        
        // Build command list
        List<String> commandList = new ArrayList<>();
        commandList.add(command);
        if (args != null) {
            commandList.addAll(args);
        }
        
        // Create ProcessBuilder
        ProcessBuilder builder = new ProcessBuilder(commandList);
        
        // Set working directory
        if (config.getWorkingDirectory() != null) {
            builder.directory(config.getWorkingDirectory().toFile());
        }
        
        // Set environment variables
        if (config.getEnvironmentVariables() != null) {
            builder.environment().putAll(config.getEnvironmentVariables());
        }
        
        // Redirect error stream to separate stream (don't merge with stdout)
        builder.redirectErrorStream(false);
        
        // Start the process
        Process process = builder.start();
        
        // Create future for the result
        CompletableFuture<ProcessResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Create stream monitors
                StreamMonitor stdoutMonitor = new StreamMonitor(
                    process.getInputStream(), 
                    config.getStdoutCallback(),
                    config.getProgressCallback()
                );
                
                StreamMonitor stderrMonitor = new StreamMonitor(
                    process.getErrorStream(),
                    config.getStderrCallback(),
                    null // No progress parsing on stderr
                );
                
                // Start monitoring streams
                CompletableFuture<String> stdoutFuture = stdoutMonitor.startMonitoring(executorService);
                CompletableFuture<String> stderrFuture = stderrMonitor.startMonitoring(executorService);
                
                // Wait for process completion with timeout
                boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
                
                if (!finished) {
                    // Timeout occurred
                    process.destroyForcibly();
                    return new ProcessResult(new RuntimeException("Process timed out after " + config.getTimeoutSeconds() + " seconds"));
                }
                
                // Get the output streams
                String stdout = stdoutFuture.get(5, TimeUnit.SECONDS); // Short timeout for stream completion
                String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
                
                return new ProcessResult(process.exitValue(), stdout, stderr);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new ProcessResult(true); // Cancelled
            } catch (Exception e) {
                process.destroyForcibly();
                return new ProcessResult(e);
            }
        }, executorService);
        
        return new RunningProcess(process, future);
    }
    
    /**
     * Executes a command synchronously.
     * 
     * @param command the command to execute
     * @param args command arguments
     * @param config process configuration
     * @return the process result
     * @throws Exception if execution fails
     */
    public ProcessResult execute(String command, List<String> args, ProcessConfig config) throws Exception {
        RunningProcess runningProcess = executeAsync(command, args, config);
        return runningProcess.waitFor();
    }
    
    /**
     * Starts an interactive process that allows bidirectional communication.
     * The process will not automatically terminate and streams will not be monitored
     * by StreamMonitor - use the RunningProcess methods for communication.
     * 
     * @param command the command to execute
     * @param args command arguments
     * @param config process configuration (callbacks will be ignored in interactive mode)
     * @return a RunningProcess in interactive mode
     * @throws IOException if the process cannot be started
     */
    public RunningProcess startInteractive(String command, List<String> args, ProcessConfig config) throws IOException {
        if (shutdown.get()) {
            throw new IllegalStateException("ProcessExecutor has been shut down");
        }
        
        // Build command list
        List<String> commandList = new ArrayList<>();
        commandList.add(command);
        if (args != null) {
            commandList.addAll(args);
        }
        
        // Create ProcessBuilder
        ProcessBuilder builder = new ProcessBuilder(commandList);
        
        // Set working directory
        if (config.getWorkingDirectory() != null) {
            builder.directory(config.getWorkingDirectory().toFile());
        }
        
        // Set environment variables
        if (config.getEnvironmentVariables() != null) {
            builder.environment().putAll(config.getEnvironmentVariables());
        }
        
        // Don't redirect streams - we need access to them
        builder.redirectErrorStream(false);
        
        // Start the process
        Process process = builder.start();
        
        // Create a future that completes only when the process terminates
        // (not when streams are exhausted like in normal execution)
        CompletableFuture<ProcessResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Wait for process to complete (no timeout in interactive mode)
                int exitCode = process.waitFor();
                
                // In interactive mode, we don't collect stream output automatically
                // The caller should use the interactive methods to communicate
                return new ProcessResult(exitCode, "", "");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new ProcessResult(true); // Cancelled
            } catch (Exception e) {
                process.destroyForcibly();
                return new ProcessResult(e);
            }
        }, executorService);
        
        return new RunningProcess(process, future, true); // true = interactive mode
    }
    
    /**
     * Shuts down the executor service and cancels all running processes.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Checks if the executor has been shut down.
     */
    public boolean isShutdown() {
        return shutdown.get();
    }
    
    /**
     * Gets the executor service for running async tasks.
     * This allows other components to use the same thread pool instead of the common ForkJoinPool.
     * 
     * @return the executor service
     */
    public ExecutorService getExecutorService() {
        if (shutdown.get()) {
            throw new IllegalStateException("ProcessExecutor has been shut down");
        }
        return executorService;
    }
}