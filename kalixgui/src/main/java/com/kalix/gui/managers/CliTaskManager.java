package com.kalix.gui.managers;

import com.kalix.gui.cli.*;
import com.kalix.gui.components.StatusProgressBar;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manager class for handling CLI session execution with progress monitoring.
 * Manages persistent kalixcli sessions for model execution and result querying.
 */
public class CliTaskManager {
    
    // Constants for configuration
    private static final int PROGRESS_HIDE_DELAY_MS = 2000;
    
    private final ProcessExecutor processExecutor;
    private final Consumer<String> statusUpdater;
    private final StatusProgressBar progressBar;
    private final JFrame parentFrame;
    private final SessionManager sessionManager;
    
    /**
     * Creates a new CliTaskManager.
     * 
     * @param processExecutor the process executor for running CLI commands
     * @param statusUpdater callback for status updates
     * @param progressBar the progress bar component
     * @param parentFrame parent frame for dialogs
     */
    public CliTaskManager(ProcessExecutor processExecutor, 
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
     * This creates a persistent session that stays alive for querying results.
     * 
     * @param modelText the model definition from the editor
     * @return CompletableFuture with the session ID
     */
    public CompletableFuture<String> runModelFromMemory(String modelText) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Locate kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCli();
                if (!cliLocation.isPresent()) {
                    handleCliNotFound();
                    throw new RuntimeException("kalixcli not found");
                }
                
                // Configure session for model run
                SessionManager.SessionConfig config = new SessionManager.SessionConfig("new-session")
                    .onProgress(this::updateProgressFromSession);
                
                // Start session
                CompletableFuture<String> sessionFuture = sessionManager.startSession(cliLocation.get().getPath(), config);
                
                // Send model definition once session starts
                sessionFuture.thenAccept(sessionId -> {
                    try {
                        Thread.sleep(500); // Give process time to start
                        sessionManager.sendModelDefinition(sessionId, modelText);
                    } catch (Exception e) {
                        statusUpdater.accept("Error sending model: " + e.getMessage());
                    }
                });
                
                return sessionFuture.get(); // Return session ID
                
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
        return requestResults(sessionId, KalixCliProtocol.ResultRequests.FLOWS);
    }
    
    /**
     * Requests water balance data from a model session.
     * 
     * @param sessionId the session to query
     * @return CompletableFuture with water balance data
     */
    public CompletableFuture<String> requestWaterBalanceData(String sessionId) {
        return requestResults(sessionId, KalixCliProtocol.ResultRequests.WATER_BALANCE);
    }
    
    /**
     * Requests convergence metrics from a model session.
     * 
     * @param sessionId the session to query
     * @return CompletableFuture with convergence data
     */
    public CompletableFuture<String> requestConvergenceData(String sessionId) {
        return requestResults(sessionId, KalixCliProtocol.ResultRequests.CONVERGENCE);
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