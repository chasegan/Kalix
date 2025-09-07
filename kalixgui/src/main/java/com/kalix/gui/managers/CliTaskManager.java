package com.kalix.gui.managers;

import com.kalix.gui.cli.*;
import com.kalix.gui.components.StatusProgressBar;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
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
    
    /**
     * Shuts down the task manager and cleans up resources.
     */
    public void shutdown() {
        if (processExecutor != null) {
            processExecutor.shutdown();
        }
    }
}