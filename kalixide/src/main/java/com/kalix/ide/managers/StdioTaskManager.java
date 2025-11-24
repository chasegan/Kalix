package com.kalix.ide.managers;

import com.kalix.ide.cli.*;
import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.windows.RunManager;

import javax.swing.*;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Manager class for handling STDIO session execution with progress monitoring.
 * Manages persistent kalix sessions for model execution and result querying.
 */
public class StdioTaskManager {

    // Constants for configuration

    private final Consumer<String> statusUpdater;
    private final StatusProgressBar progressBar;
    private final JFrame parentFrame;
    private final SessionManager sessionManager;
    private final Supplier<File> workingDirectorySupplier;

    /**
     * Creates a new StdioTaskManager.
     *
     * @param processExecutor the process executor for running CLI commands
     * @param statusUpdater callback for status updates
     * @param progressBar the progress bar component
     * @param parentFrame parent frame for dialogs
     * @param workingDirectorySupplier supplier for getting the current working directory
     */
    public StdioTaskManager(ProcessExecutor processExecutor,
                         Consumer<String> statusUpdater,
                         StatusProgressBar progressBar,
                         JFrame parentFrame,
                         Supplier<File> workingDirectorySupplier) {
        this.statusUpdater = statusUpdater;
        this.progressBar = progressBar;
        this.parentFrame = parentFrame;
        this.workingDirectorySupplier = workingDirectorySupplier;


        // Initialize session manager
        this.sessionManager = new SessionManager(
            processExecutor,
            statusUpdater,
            this::handleSessionEvent
        );
    }
    
    /**
     * Handles CLI not found error.
     */
    private void handleCliNotFound() {
        SwingUtilities.invokeLater(() -> {
            statusUpdater.accept("Error: kalix not found");
            JOptionPane.showMessageDialog(parentFrame,
                "Kalix not found. Please fix this in File > Preferences > Kalix.",
                "Kalix Not Found", JOptionPane.ERROR_MESSAGE);
        });
    }
    
    /**
     * Runs a model from the text editor without saving to disk.
     * This creates a persistent session and starts the Run Model program.
     * 
     * @param modelText the model definition from the editor
     * @return CompletableFuture with the session key
     */
    public CompletableFuture<String> runModelFromMemory(String modelText) {
        // Use dedicated thread pool instead of common ForkJoinPool to avoid thread exhaustion
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Locate kalix using preferences
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    handleCliNotFound();
                    throw new RuntimeException("kalix not found");
                }

                // Configure session for model run (let SessionManager auto-generate unique ID)
                SessionManager.SessionConfig config = new SessionManager.SessionConfig("new-session");

                // Set working directory to current file's directory if available
                File workingDir = workingDirectorySupplier.get();
                if (workingDir != null) {
                    config.workingDirectory(workingDir.toPath());
                }

                // Start session and wait for it to be ready
                CompletableFuture<String> sessionFuture = sessionManager.startSession(cliLocation.get().getPath(), config);
                String sessionKey = sessionFuture.get(); // Wait for session to start
                
                // Now set up the Run Model program synchronously
                try {
                    Thread.sleep(500); // Give process time to start
                    
                    // Create and start the Run Model program
                    RunModelProgram runModelProgram = new RunModelProgram(
                        sessionKey,
                        sessionManager,
                        statusUpdater,
                        this::updateProgressFromSession
                    );
                    
                    // Attach program to session
                    Optional<SessionManager.KalixSession> session = sessionManager.getSession(sessionKey);
                    if (session.isPresent()) {
                        session.get().setActiveProgram(runModelProgram);
                        runModelProgram.start(modelText);
                    } else {
                        throw new RuntimeException("Session not found: " + sessionKey);
                    }
                    
                } catch (Exception e) {
                    // Clean up session if RunModelProgram setup fails
                    sessionManager.terminateSession(sessionKey);
                    throw new RuntimeException("Error starting Run Model program: " + e.getMessage(), e);
                }
                
                return sessionKey;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to run model from memory", e);
            }
        });
    }


    /**
     * Gets all active sessions.
     * 
     * @return map of session key to session information
     */
    public Map<String, SessionManager.KalixSession> getActiveSessions() {
        return sessionManager.getActiveSessions();
    }


    /**
     * Gets the underlying SessionManager instance.
     *
     * @return the SessionManager instance
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Terminates a specific session.
     *
     * @param sessionKey the session to terminate
     * @return CompletableFuture that completes when session is terminated
     */
    public CompletableFuture<Void> terminateSession(String sessionKey) {
        return sessionManager.terminateSession(sessionKey);
    }
    
    /**
     * Removes a terminated session from the session list for cleanup.
     * Only works on terminated or error sessions.
     *
     * @param sessionKey the session to remove
     * @return CompletableFuture that completes when session is removed
     */
    public CompletableFuture<Void> removeSession(String sessionKey) {
        return sessionManager.removeSession(sessionKey);
    }

    /**
     * Sends a command to an active session.
     *
     * @param sessionKey the session to send command to
     * @param command    the command to send
     */
    public void sendCommand(String sessionKey, String command) {
        sessionManager.sendCommand(sessionKey, command);
    }

    /**
     * Detects foreign kalix/kalixcli processes running on the system that are not managed
     * by the current SessionManager. These may be managed by other KalixIDE instances.
     *
     * @return list of foreign ProcessHandle objects
     */
    public List<ForeignProcess> detectForeignKalixProcesses() {
        // Get PIDs of all managed sessions
        Set<Long> managedPids = sessionManager.getActiveSessions().values().stream()
            .map(session -> session.getProcess().pid())
            .collect(Collectors.toSet());

        // Find all kalix processes on the system
        return ProcessHandle.allProcesses()
            .filter(ph -> ph.isAlive())
            .filter(ph -> {
                // Check if command contains "kalix"
                Optional<String> cmdOpt = ph.info().command();
                if (cmdOpt.isEmpty()) {
                    return false;
                }

                String cmd = cmdOpt.get().toLowerCase();
                // Match kalix, kalixcli, or paths ending with kalix/kalixcli
                return cmd.contains("kalix") || cmd.endsWith("kalix") ||
                       cmd.endsWith("kalix.exe") || cmd.endsWith("kalixcli") ||
                       cmd.endsWith("kalixcli.exe");
            })
            .filter(ph -> !managedPids.contains(ph.pid()))
            .map(ForeignProcess::new)
            .collect(Collectors.toList());
    }

    /**
     * Kills a foreign process by PID.
     *
     * @param pid the process ID to kill
     * @return CompletableFuture that completes when the process is killed
     */
    public CompletableFuture<Boolean> killForeignProcess(long pid) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<ProcessHandle> processOpt = ProcessHandle.of(pid);
            if (processOpt.isEmpty()) {
                return false; // Process already dead
            }

            ProcessHandle process = processOpt.get();

            // Try graceful termination first
            boolean destroyed = process.destroy();
            if (!destroyed) {
                return false;
            }

            // Wait up to 5 seconds for graceful termination
            try {
                process.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                // Graceful termination failed, force kill
                return process.destroyForcibly();
            }
        });
    }

    /**
     * Represents a foreign kalix process detected on the system.
     * These processes are not managed by the current SessionManager and may
     * belong to other KalixIDE instances or background processes.
     */
    public static class ForeignProcess {
        private final ProcessHandle handle;
        private final long pid;
        private final String command;
        private final String user;
        private final Instant startTime;
        private final Duration cpuDuration;

        public ForeignProcess(ProcessHandle handle) {
            this.handle = handle;
            this.pid = handle.pid();

            ProcessHandle.Info info = handle.info();
            this.command = info.command().orElse("unknown");
            this.user = info.user().orElse("unknown");
            this.startTime = info.startInstant().orElse(null);
            this.cpuDuration = info.totalCpuDuration().orElse(null);
        }

        public long getPid() {
            return pid;
        }

        public String getCommand() {
            return command;
        }

        public String getUser() {
            return user;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public Duration getCpuDuration() {
            return cpuDuration;
        }

        public boolean isAlive() {
            return handle.isAlive();
        }

        public String getDisplayName() {
            // Extract just the executable name from full path
            String execName = command;
            int lastSlash = Math.max(execName.lastIndexOf('/'), execName.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                execName = execName.substring(lastSlash + 1);
            }
            return String.format("%s (PID %d)", execName, pid);
        }

        public String getUptimeString() {
            if (startTime == null) {
                return "unknown";
            }

            Duration uptime = Duration.between(startTime, Instant.now());
            long hours = uptime.toHours();
            long minutes = uptime.toMinutesPart();
            long seconds = uptime.toSecondsPart();

            if (hours > 0) {
                return String.format("%dh %dm", hours, minutes);
            } else if (minutes > 0) {
                return String.format("%dm %ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        }
    }

    /**
     * Handles session events for UI updates.
     */
    private void handleSessionEvent(SessionManager.SessionEvent event) {
        SwingUtilities.invokeLater(() -> {
            switch (event.getNewState()) {
                case STARTING:
                    progressBar.showProgress(0.0, "Starting...");
                    break;
                    
                case RUNNING:
                    // Progress updates handled by progress callback
                    break;
                    
                case READY:
                    // Session is ready but don't show this message in status bar as it's not useful to users
                    // Progress bar already at 100% from CLI progress updates
                    // AutoHidingProgressBar will automatically hide after delay
                    break;
                    
                case ERROR:
                    progressBar.hideProgress();
                    statusUpdater.accept("Session error: " + event.getMessage());
                    break;
                    
                case TERMINATED:
                    statusUpdater.accept("Session ended: " + event.getSessionKey());
                    break;
            }

            // Refresh Run Manager if it's open
            RunManager.refreshRunManagerIfOpen();
        });
    }
    
    /**
     * Updates progress bar from session progress callbacks.
     */
    private void updateProgressFromSession(ProgressParser.ProgressInfo progressInfo) {
        SwingUtilities.invokeLater(() -> {
            // Use showProgress with command to set both progress and color
            progressBar.showProgress(
                progressInfo.getPercentage() / 100.0,
                String.format("%.0f%%", progressInfo.getPercentage()),
                progressInfo.getRawLine() // Contains the command
            );
            statusUpdater.accept(progressInfo.getDescription());
        });
    }
}