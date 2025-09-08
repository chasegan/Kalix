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
 * Manager class for handling CLI task execution with progress monitoring.
 * Encapsulates the complexity of running interactive kalixcli processes
 * and provides clean callbacks for UI updates.
 */
public class CliTaskManager {
    
    // Constants for configuration
    private static final int PROGRESS_UPDATE_DELAY_MS = 50;
    private static final int PROGRESS_HIDE_DELAY_MS = 2000;
    private static final String DEFAULT_SIM_DURATION = "8";
    
    private final ProcessExecutor processExecutor;
    private final Consumer<String> statusUpdater;
    private final StatusProgressBar progressBar;
    private final JFrame parentFrame;
    private final SessionManager sessionManager;
    
    /**
     * Configuration for CLI task execution.
     */
    public static class TaskConfig {
        private String[] args;
        private String successMessage = "Task completed successfully!";
        private String errorTitle = "Task Error";
        private boolean showProgressBar = true;
        private int simulationDurationSeconds = 8;
        
        public TaskConfig withArgs(String... args) {
            this.args = args;
            return this;
        }
        
        public TaskConfig withSuccessMessage(String message) {
            this.successMessage = message;
            return this;
        }
        
        public TaskConfig withErrorTitle(String title) {
            this.errorTitle = title;
            return this;
        }
        
        public TaskConfig showProgressBar(boolean show) {
            this.showProgressBar = show;
            return this;
        }
        
        public TaskConfig simulationDuration(int seconds) {
            this.simulationDurationSeconds = seconds;
            return this;
        }
        
        // Getters
        public String[] getArgs() { return args; }
        public String getSuccessMessage() { return successMessage; }
        public String getErrorTitle() { return errorTitle; }
        public boolean shouldShowProgressBar() { return showProgressBar; }
        public int getSimulationDurationSeconds() { return simulationDurationSeconds; }
    }
    
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
     * Runs a test simulation with the specified duration.
     * 
     * @param durationSeconds simulation duration in seconds
     */
    public void runTestSimulation(int durationSeconds) {
        TaskConfig config = new TaskConfig()
            .withArgs("test", "--sim-duration-seconds=" + durationSeconds)
            .withSuccessMessage("Test simulation completed successfully!")
            .withErrorTitle("Simulation Error")
            .simulationDuration(durationSeconds);
        
        runCliTask(config);
    }
    
    /**
     * Runs a test simulation with default duration.
     */
    public void runTestSimulation() {
        runTestSimulation(Integer.parseInt(DEFAULT_SIM_DURATION));
    }
    
    
    /**
     * Runs a CLI task with the given configuration.
     * 
     * @param config task configuration
     */
    public void runCliTask(TaskConfig config) {
        CompletableFuture.runAsync(() -> {
            try {
                // Locate kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCli();
                
                if (!cliLocation.isPresent()) {
                    handleCliNotFound();
                    return;
                }
                
                // Initialize progress tracking
                if (config.shouldShowProgressBar()) {
                    SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Starting task...");
                        progressBar.showProgress(0.0, "0%");
                    });
                }
                
                // Execute the task
                executeTask(cliLocation.get().getPath(), config);
                
            } catch (Exception e) {
                handleTaskError(e, config);
            }
        });
    }
    
    /**
     * Executes the actual CLI task.
     */
    private void executeTask(Path cliPath, TaskConfig config) throws IOException {
        InteractiveKalixProcess process = InteractiveKalixProcess.start(
            cliPath, processExecutor, config.getArgs());
        
        try {
            monitorProgress(process, config);
        } finally {
            process.close();
        }
    }
    
    /**
     * Monitors progress of the CLI task.
     */
    private void monitorProgress(InteractiveKalixProcess process, TaskConfig config) {
        boolean completed = false;
        
        try {
            while (process.isRunning() && !completed) {
                Optional<String> outputLine = process.readOutputLine();
                
                if (outputLine.isPresent()) {
                    String line = outputLine.get();
                    
                    // Parse progress
                    Optional<ProgressParser.ProgressInfo> progressInfo = ProgressParser.parseProgress(line);
                    if (progressInfo.isPresent() && config.shouldShowProgressBar()) {
                        updateProgress(progressInfo.get());
                    }
                    
                    // Check for completion
                    if (ProgressParser.isCompletionLine(line)) {
                        completed = true;
                        handleTaskCompletion(config);
                        break;
                    }
                } else if (!process.isRunning()) {
                    // Process ended - force completion
                    handleTaskCompletion(config);
                    break;
                }
                
                Thread.sleep(PROGRESS_UPDATE_DELAY_MS);
            }
            
            // Final check for any remaining output
            if (!completed) {
                checkFinalOutput(process, config);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(() -> {
                if (config.shouldShowProgressBar()) {
                    progressBar.hideProgress();
                }
                statusUpdater.accept("Task interrupted");
            });
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                if (config.shouldShowProgressBar()) {
                    progressBar.hideProgress();
                }
                statusUpdater.accept("Task I/O error: " + e.getMessage());
            });
        }
    }
    
    /**
     * Checks for any final output after process ends.
     */
    private void checkFinalOutput(InteractiveKalixProcess process, TaskConfig config) {
        try {
            while (true) {
                Optional<String> outputLine = process.readOutputLine();
                if (outputLine.isPresent()) {
                    String line = outputLine.get();
                    
                    if (ProgressParser.isCompletionLine(line)) {
                        handleTaskCompletion(config);
                        return;
                    }
                } else {
                    // No more output - assume completion
                    handleTaskCompletion(config);
                    break;
                }
            }
        } catch (IOException e) {
            // If we can't read final output, assume completion
            handleTaskCompletion(config);
        }
    }
    
    /**
     * Updates progress bar with parsed progress information.
     */
    private void updateProgress(ProgressParser.ProgressInfo progressInfo) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setProgressPercentage(progressInfo.getPercentage());
            progressBar.setProgressText(String.format("%.0f%%", progressInfo.getPercentage()));
            statusUpdater.accept("Task: " + String.format("%.0f%%", progressInfo.getPercentage()));
        });
    }
    
    /**
     * Handles task completion.
     */
    private void handleTaskCompletion(TaskConfig config) {
        SwingUtilities.invokeLater(() -> {
            if (config.shouldShowProgressBar()) {
                progressBar.setProgressPercentage(100);
                progressBar.setProgressText("Complete");
            }
            statusUpdater.accept(config.getSuccessMessage());
            
            // Hide progress bar after delay
            if (config.shouldShowProgressBar()) {
                Timer hideTimer = new Timer(PROGRESS_HIDE_DELAY_MS, e -> {
                    progressBar.hideProgress();
                    statusUpdater.accept("Ready");
                });
                hideTimer.setRepeats(false);
                hideTimer.start();
            }
        });
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
    
    /**
     * Handles task execution errors.
     */
    private void handleTaskError(Exception e, TaskConfig config) {
        SwingUtilities.invokeLater(() -> {
            if (config.shouldShowProgressBar()) {
                progressBar.hideProgress();
            }
            statusUpdater.accept("Error: Task failed");
            JOptionPane.showMessageDialog(parentFrame,
                "Task failed: " + e.getMessage(),
                config.getErrorTitle(), JOptionPane.ERROR_MESSAGE);
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