package com.kalix.ide.managers;

import com.kalix.ide.cli.*;
import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.windows.RunManager;

import javax.swing.*;
import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manager class for handling STDIO session execution with progress monitoring.
 * Manages persistent kalixcli sessions for model execution and result querying.
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
            statusUpdater.accept("Error: kalixcli not found");
            JOptionPane.showMessageDialog(parentFrame,
                "Kalixcli not found. Please fix this in File > Preferences > Kalixcli.",
                "Kalixcli Not Found", JOptionPane.ERROR_MESSAGE);
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
                // Locate kalixcli using preferences
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    handleCliNotFound();
                    throw new RuntimeException("kalixcli not found");
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