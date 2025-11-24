package com.kalix.ide.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-level wrapper for STDIO communication with kalixcli sessions.
 * Provides structured methods for common kalixcli STDIO protocol interactions.
 */
public class KalixStdioSession {
    
    private final ProcessExecutor.RunningProcess runningProcess;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
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
     * Checks if the kalixcli process is still running.
     *
     * @return true if the process is alive
     */
    public boolean isRunning() {
        return runningProcess.isRunning();
    }

    /**
     * Gets the process ID (PID) of the kalixcli process.
     *
     * @return the process ID
     */
    public long pid() {
        return runningProcess.getProcess().pid();
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
     * Checks if the process has been closed and throws if so.
     */
    private void checkNotClosed() throws IOException {
        if (closed.get()) {
            throw new IOException("InteractiveKalixProcess has been closed");
        }
    }
}