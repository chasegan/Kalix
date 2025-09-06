package com.kalix.gui.cli;

import java.io.File;
import java.io.IOException;
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
        
        public ProcessConfig workingDirectory(String workingDirectory) {
            this.workingDirectory = Paths.get(workingDirectory);
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
     */
    public static class RunningProcess {
        private final Process process;
        private final CompletableFuture<ProcessResult> future;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        
        RunningProcess(Process process, CompletableFuture<ProcessResult> future) {
            this.process = process;
            this.future = future;
        }
        
        /**
         * Cancels the running process.
         * 
         * @param forceKill if true, forcibly kills the process; if false, attempts graceful termination
         * @return true if the process was cancelled successfully
         */
        public boolean cancel(boolean forceKill) {
            if (cancelled.compareAndSet(false, true)) {
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
     * Convenience method to execute a simple command.
     */
    public ProcessResult execute(String command, String... args) throws Exception {
        List<String> argList = args != null ? List.of(args) : List.of();
        return execute(command, argList, new ProcessConfig());
    }
    
    /**
     * Shuts down the executor service and cancels all running processes.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
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
}