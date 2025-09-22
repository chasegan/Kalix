package com.kalix.gui.managers;

import com.kalix.gui.cli.*;
import com.kalix.gui.components.StatusProgressBar;
import com.kalix.gui.windows.RunManager;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manager class for handling STDIO session execution with progress monitoring.
 * Manages persistent kalixcli sessions for model execution and result querying.
 */
public class StdioTaskManager {
    
    // Constants for configuration
    private static final int PROGRESS_HIDE_DELAY_MS = 2000;
    
    private final ProcessExecutor processExecutor;
    private final Consumer<String> statusUpdater;
    private final StatusProgressBar progressBar;
    private final JFrame parentFrame;
    private final SessionManager sessionManager;
    
    /**
     * Creates a new StdioTaskManager.
     *
     * @param processExecutor the process executor for running CLI commands
     * @param statusUpdater callback for status updates
     * @param progressBar the progress bar component
     * @param parentFrame parent frame for dialogs
     */
    public StdioTaskManager(ProcessExecutor processExecutor, 
                         Consumer<String> statusUpdater,
                         StatusProgressBar progressBar,
                         JFrame parentFrame) {
        this.processExecutor = processExecutor;
        this.statusUpdater = statusUpdater;
        this.progressBar = progressBar;
        this.parentFrame = parentFrame;
        
        
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
                "kalixcli executable not found. Please ensure it's installed and in your PATH.",
                "CLI Not Found", JOptionPane.ERROR_MESSAGE);
        });
    }
    
    // ======================
    // SESSION-BASED METHODS 
    // ======================
    
    /**
     * Runs a model from the text editor without saving to disk.
     * This creates a persistent session and starts the Run Model program.
     * 
     * @param modelText the model definition from the editor
     * @return CompletableFuture with the session ID
     */
    public CompletableFuture<String> runModelFromMemory(String modelText) {
        // Use dedicated thread pool instead of common ForkJoinPool to avoid thread exhaustion
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Locate kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCli();
                if (!cliLocation.isPresent()) {
                    handleCliNotFound();
                    throw new RuntimeException("kalixcli not found");
                }
                
                // Configure session for model run (let SessionManager auto-generate unique ID)
                SessionManager.SessionConfig config = new SessionManager.SessionConfig("new-session")
                    .onProgress(this::updateProgressFromSession);
                
                // Start session and wait for it to be ready
                CompletableFuture<String> sessionFuture = sessionManager.startSession(cliLocation.get().getPath(), config);
                String sessionId = sessionFuture.get(); // Wait for session to start
                
                // Now set up the Run Model program synchronously
                try {
                    Thread.sleep(500); // Give process time to start
                    
                    // Create and start the Run Model program
                    RunModelProgram runModelProgram = new RunModelProgram(
                        sessionId,
                        sessionManager,
                        statusUpdater,
                        this::updateProgressFromSession
                    );
                    
                    // Attach program to session
                    Optional<SessionManager.KalixSession> session = sessionManager.getSession(sessionId);
                    if (session.isPresent()) {
                        session.get().setActiveProgram(runModelProgram);
                        runModelProgram.start(modelText);
                    } else {
                        throw new RuntimeException("Session not found: " + sessionId);
                    }
                    
                } catch (Exception e) {
                    // Clean up session if RunModelProgram setup fails
                    sessionManager.terminateSession(sessionId);
                    throw new RuntimeException("Error starting Run Model program: " + e.getMessage(), e);
                }
                
                return sessionId;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to run model from memory", e);
            }
        });
    }
    
    /**
     * Requests specific results from a model session.
     * 
     * @param sessionId the session to query
     * @param resultType the type of results (flows, water_balance, etc.)
     * @return CompletableFuture with the results
     */
    public CompletableFuture<String> requestResults(String sessionId, String resultType) {
        return sessionManager.requestResults(sessionId, resultType);
    }
    
    /**
     * Requests flows data from a model session.
     * 
     * @param sessionId the session to query
     * @return CompletableFuture with flows data
     */
    public CompletableFuture<String> requestFlowsData(String sessionId) {
        return requestResults(sessionId, KalixStdioProtocol.ResultRequests.FLOWS);
    }
    
    /**
     * Requests water balance data from a model session.
     * 
     * @param sessionId the session to query
     * @return CompletableFuture with water balance data
     */
    public CompletableFuture<String> requestWaterBalanceData(String sessionId) {
        return requestResults(sessionId, KalixStdioProtocol.ResultRequests.WATER_BALANCE);
    }
    
    /**
     * Requests convergence metrics from a model session.
     * 
     * @param sessionId the session to query
     * @return CompletableFuture with convergence data
     */
    public CompletableFuture<String> requestConvergenceData(String sessionId) {
        return requestResults(sessionId, KalixStdioProtocol.ResultRequests.CONVERGENCE);
    }
    
    /**
     * Gets all active sessions.
     * 
     * @return map of session IDs to session information
     */
    public Map<String, SessionManager.KalixSession> getActiveSessions() {
        return sessionManager.getActiveSessions();
    }
    
    /**
     * Gets information about a specific session.
     * 
     * @param sessionId the session ID
     * @return session information if found
     */
    public Optional<SessionManager.KalixSession> getSession(String sessionId) {
        return sessionManager.getSession(sessionId);
    }
    
    /**
     * Terminates a specific session.
     * 
     * @param sessionId the session to terminate
     * @return CompletableFuture that completes when session is terminated
     */
    public CompletableFuture<Void> terminateSession(String sessionId) {
        return sessionManager.terminateSession(sessionId);
    }
    
    /**
     * Removes a terminated session from the session list for cleanup.
     * Only works on terminated or error sessions.
     *
     * @param sessionId the session to remove
     * @return CompletableFuture that completes when session is removed
     */
    public CompletableFuture<Void> removeSession(String sessionId) {
        return sessionManager.removeSession(sessionId);
    }

    /**
     * Sends a command to an active session.
     *
     * @param sessionId the session to send command to
     * @param command the command to send
     * @return CompletableFuture that completes when command is sent
     */
    public CompletableFuture<Void> sendCommand(String sessionId, String command) {
        return sessionManager.sendCommand(sessionId, command);
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
                    progressBar.setProgressPercentage(100);
                    progressBar.setProgressText("Ready");
                    statusUpdater.accept("Model session ready - " + event.getSessionId());
                    
                    // Hide progress bar after delay but show session is ready
                    Timer readyTimer = new Timer(PROGRESS_HIDE_DELAY_MS, e -> {
                        progressBar.hideProgress();
                        statusUpdater.accept("Session ready for queries: " + event.getSessionId());
                    });
                    readyTimer.setRepeats(false);
                    readyTimer.start();
                    break;
                    
                case ERROR:
                    progressBar.hideProgress();
                    statusUpdater.accept("Session error: " + event.getMessage());
                    break;
                    
                case TERMINATED:
                    statusUpdater.accept("Session ended: " + event.getSessionId());
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
            progressBar.setProgressPercentage(progressInfo.getPercentage());
            progressBar.setProgressText(String.format("%.0f%%", progressInfo.getPercentage()));
            statusUpdater.accept(progressInfo.getDescription());
        });
    }
    
    /**
     * Shuts down the task manager and cleans up resources.
     */
    public void shutdown() {
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
        if (processExecutor != null) {
            processExecutor.shutdown();
        }
    }
}