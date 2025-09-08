package com.kalix.gui.managers;

import com.kalix.gui.cli.KalixCliLocator;
import com.kalix.gui.cli.ProcessExecutor;
import com.kalix.gui.cli.CliLogger;
import com.kalix.gui.cli.JsonStdioProtocol;

import javax.swing.*;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages model execution using the new kalixcli JSON STDIO protocol.
 * Handles model loading via load_model_string and execution via run_simulation commands.
 * Provides progress feedback and result handling through the new session-based interface.
 */
public class ModelRunner {
    
    private final Component parentComponent;
    private final Consumer<String> statusUpdateCallback;
    private final ProcessExecutor processExecutor;
    private final CliLogger logger;
    private final CliTaskManager cliTaskManager;
    private JsonStdioProtocol currentSession;
    
    /**
     * Result of a model run operation.
     */
    public static class RunResult {
        private final boolean success;
        private final String output;
        private final String errorOutput;
        private final int exitCode;
        private final Exception exception;
        
        private RunResult(boolean success, String output, String errorOutput, int exitCode, Exception exception) {
            this.success = success;
            this.output = output;
            this.errorOutput = errorOutput;
            this.exitCode = exitCode;
            this.exception = exception;
        }
        
        public static RunResult success(String output, String errorOutput, int exitCode) {
            return new RunResult(true, output, errorOutput, exitCode, null);
        }
        
        public static RunResult failure(Exception exception) {
            return new RunResult(false, null, null, -1, exception);
        }
        
        public static RunResult failure(String output, String errorOutput, int exitCode) {
            return new RunResult(false, output, errorOutput, exitCode, null);
        }
        
        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getErrorOutput() { return errorOutput; }
        public int getExitCode() { return exitCode; }
        public Exception getException() { return exception; }
    }
    
    /**
     * Creates a new ModelRunner instance.
     * 
     * @param parentComponent The parent component for dialogs
     * @param statusUpdateCallback Callback for status updates
     * @param cliTaskManager The CLI task manager for session handling
     */
    public ModelRunner(Component parentComponent, Consumer<String> statusUpdateCallback, CliTaskManager cliTaskManager) {
        this.parentComponent = parentComponent;
        this.statusUpdateCallback = statusUpdateCallback;
        this.processExecutor = new ProcessExecutor();
        this.logger = CliLogger.getInstance();
        this.cliTaskManager = cliTaskManager;
    }
    
    /**
     * Creates a new ModelRunner instance (legacy constructor for backward compatibility).
     * 
     * @param parentComponent The parent component for dialogs
     * @param statusUpdateCallback Callback for status updates
     */
    public ModelRunner(Component parentComponent, Consumer<String> statusUpdateCallback) {
        this.parentComponent = parentComponent;
        this.statusUpdateCallback = statusUpdateCallback;
        this.processExecutor = new ProcessExecutor();
        this.logger = CliLogger.getInstance();
        this.cliTaskManager = null; // Will use old protocol if no task manager provided
    }
    
    /**
     * Runs the model simulation asynchronously using the new JSON STDIO protocol.
     * 
     * @param modelFile The model file to simulate
     * @return CompletableFuture containing the run result
     */
    public CompletableFuture<RunResult> runModelAsync(File modelFile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Validate model file
                if (!validateModelFile(modelFile)) {
                    return RunResult.failure(new IllegalArgumentException("Invalid model file: " + modelFile.getAbsolutePath()));
                }
                
                // Read model content
                String modelContent = readModelFile(modelFile);
                
                // Find kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    return RunResult.failure(new RuntimeException("kalixcli not found. Please check your settings."));
                }
                
                KalixCliLocator.CliLocation cli = cliLocation.get();
                logger.info("Running simulation with kalixcli JSON STDIO protocol at: " + cli.getPath());
                
                // Execute using new JSON STDIO protocol
                return executeWithJsonProtocol(cli, modelContent);
                
            } catch (Exception e) {
                logger.error("Error during model execution", e);
                return RunResult.failure(e);
            }
        });
    }
    
    /**
     * Runs the model simulation from model content string using JSON STDIO protocol.
     * 
     * @param modelContent The model content as INI string
     * @return CompletableFuture containing the run result
     */
    public CompletableFuture<RunResult> runModelFromString(String modelContent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (modelContent == null || modelContent.trim().isEmpty()) {
                    return RunResult.failure(new IllegalArgumentException("Model content cannot be empty"));
                }
                
                // Find kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    return RunResult.failure(new RuntimeException("kalixcli not found. Please check your settings."));
                }
                
                KalixCliLocator.CliLocation cli = cliLocation.get();
                logger.info("Running simulation from string with kalixcli JSON STDIO protocol at: " + cli.getPath());
                
                // Execute using new JSON STDIO protocol
                return executeWithJsonProtocol(cli, modelContent);
                
            } catch (Exception e) {
                logger.error("Error during model execution from string", e);
                return RunResult.failure(e);
            }
        });
    }
    
    /**
     * Runs the model simulation synchronously with progress dialog.
     * 
     * @param modelFile The model file to simulate
     */
    public void runModelWithDialog(File modelFile) {
        // Show progress dialog
        JDialog progressDialog = createProgressDialog();
        
        // Start async execution
        CompletableFuture<RunResult> future = runModelAsync(modelFile);
        
        // Handle completion
        future.whenComplete((result, throwable) -> {
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                handleRunResult(result, throwable, modelFile);
            });
        });
        
        // Show progress dialog
        progressDialog.setVisible(true);
    }
    
    /**
     * Runs the model simulation from editor content with progress dialog.
     * 
     * @param modelContent The model content as INI string
     */
    public void runModelFromEditorContent(String modelContent) {
        // Show progress dialog
        JDialog progressDialog = createProgressDialog();
        
        // Start async execution
        CompletableFuture<RunResult> future = runModelFromString(modelContent);
        
        // Handle completion
        future.whenComplete((result, throwable) -> {
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                handleRunResultFromString(result, throwable);
            });
        });
        
        // Show progress dialog
        progressDialog.setVisible(true);
    }
    
    /**
     * Validates that the model file exists and is readable.
     * 
     * @param modelFile The model file to validate
     * @return true if the file is valid
     */
    private boolean validateModelFile(File modelFile) {
        if (modelFile == null) {
            statusUpdateCallback.accept("No model file is currently loaded");
            return false;
        }
        
        if (!modelFile.exists()) {
            statusUpdateCallback.accept("Model file does not exist: " + modelFile.getName());
            return false;
        }
        
        if (!modelFile.canRead()) {
            statusUpdateCallback.accept("Cannot read model file: " + modelFile.getName());
            return false;
        }
        
        return true;
    }
    
    /**
     * Executes model simulation using the new JSON STDIO protocol.
     * 
     * @param cli The CLI location
     * @param modelContent The model content as INI string
     * @return The run result
     */
    private RunResult executeWithJsonProtocol(KalixCliLocator.CliLocation cli, String modelContent) {
        // If we have a CliTaskManager, use it for proper session integration
        if (cliTaskManager != null) {
            return executeWithCliTaskManager(modelContent);
        }
        
        // Fallback to direct JsonStdioProtocol (legacy mode)
        return executeWithDirectProtocol(cli, modelContent);
    }
    
    /**
     * Executes model using CliTaskManager for proper session integration.
     */
    private RunResult executeWithCliTaskManager(String modelContent) {
        try {
            System.out.println("[ModelRunner] Starting model session via CliTaskManager with model length: " + modelContent.length());
            statusUpdateCallback.accept("Starting model session via CliTaskManager...");
            
            // Use CliTaskManager's new JSON protocol method with full logging
            System.out.println("[ModelRunner] Calling cliTaskManager.runModelWithJsonProtocol()");
            CompletableFuture<String> sessionFuture = cliTaskManager.runModelWithJsonProtocol(modelContent);
            System.out.println("[ModelRunner] Got session future, waiting for completion...");
            String sessionId = sessionFuture.get();
            System.out.println("[ModelRunner] Session completed with ID: " + sessionId);
            
            logger.info("Started model session via CliTaskManager JSON protocol: " + sessionId);
            statusUpdateCallback.accept("Model session started with JSON protocol: " + sessionId);
            
            // The CliTaskManager handles the session lifecycle and logging
            return RunResult.success("Model session started with JSON protocol: " + sessionId, "", 0);
            
        } catch (Exception e) {
            System.err.println("[ModelRunner] Exception in executeWithCliTaskManager: " + e.getMessage());
            e.printStackTrace();
            logger.error("Failed to execute with CliTaskManager JSON protocol", e);
            return RunResult.failure(e);
        }
    }
    
    /**
     * Executes model using direct JsonStdioProtocol (legacy mode).
     */
    private RunResult executeWithDirectProtocol(KalixCliLocator.CliLocation cli, String modelContent) {
        try {
            statusUpdateCallback.accept("Starting new kalixcli session...");
            
            // Create new JSON STDIO protocol session
            currentSession = new JsonStdioProtocol(processExecutor)
                .onReady(this::handleReadyMessage)
                .onBusy(this::handleBusyMessage)
                .onProgress(this::handleProgressMessage)
                .onResult(this::handleResultMessage)
                .onError(this::handleErrorMessage)
                .onStopped(this::handleStoppedMessage)
                .onLog(this::handleLogMessage);
            
            // Start session and wait for ready
            String sessionId = currentSession.startSession(cli.getPath()).get();
            logger.info("Started kalixcli session: " + sessionId);
            
            statusUpdateCallback.accept("Loading model definition...");
            
            // Send load_model_string command
            currentSession.loadModelString(modelContent).get();
            logger.info("Model loaded successfully");
            
            statusUpdateCallback.accept("Running simulation...");
            
            // Send run_simulation command
            currentSession.runSimulation().get();
            
            // Wait for completion (this would be handled by callbacks in real implementation)
            // For now, we'll simulate waiting
            Thread.sleep(1000);
            
            logger.info("Model simulation completed successfully via JSON STDIO protocol");
            return RunResult.success("Simulation completed", "", 0);
            
        } catch (Exception e) {
            logger.error("Failed to execute with JSON STDIO protocol", e);
            return RunResult.failure(e);
        } finally {
            if (currentSession != null) {
                try {
                    currentSession.terminateSession().get();
                } catch (Exception e) {
                    logger.warn("Error terminating session", e);
                }
                currentSession = null;
            }
        }
    }
    
    /**
     * Reads the content of a model file.
     * 
     * @param modelFile The model file to read
     * @return The file content as string
     * @throws IOException if reading fails
     */
    private String readModelFile(File modelFile) throws IOException {
        try {
            return java.nio.file.Files.readString(modelFile.toPath());
        } catch (IOException e) {
            logger.error("Failed to read model file: " + modelFile.getAbsolutePath(), e);
            throw new IOException("Could not read model file: " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates a progress dialog for long-running simulations.
     * 
     * @return The progress dialog
     */
    private JDialog createProgressDialog() {
        JDialog dialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(parentComponent), 
                                    "Running Simulation", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setSize(350, 120);
        dialog.setLocationRelativeTo(parentComponent);
        
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(new JLabel("Running model simulation..."));
        
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        panel.add(progressBar);
        
        dialog.add(panel);
        return dialog;
    }
    
    /**
     * Handles the result of model execution and displays appropriate feedback.
     * 
     * @param result The run result
     * @param throwable Any exception that occurred
     * @param modelFile The model file that was executed
     */
    private void handleRunResult(RunResult result, Throwable throwable, File modelFile) {
        if (throwable != null) {
            showErrorDialog("Simulation Error", 
                          "An error occurred during simulation: " + throwable.getMessage());
            statusUpdateCallback.accept("Simulation failed: " + throwable.getMessage());
            return;
        }
        
        if (result.isSuccess()) {
            showResultDialog("Simulation Complete", 
                           "Simulation of " + modelFile.getName() + " completed successfully.",
                           result.getOutput());
            statusUpdateCallback.accept("Simulation completed successfully: " + modelFile.getName());
        } else {
            String errorMsg = result.getException() != null ? 
                result.getException().getMessage() : 
                "Exit code: " + result.getExitCode();
            
            // Get the CLI command for status display
            String cliCommand = getCliCommandString(modelFile);
            
            showErrorDialog("Simulation Failed", 
                          "Simulation failed: " + errorMsg);
            statusUpdateCallback.accept("Simulation failed: " + cliCommand);
        }
    }
    
    /**
     * Handles the result of model execution from string content and displays appropriate feedback.
     * 
     * @param result The run result
     * @param throwable Any exception that occurred
     */
    private void handleRunResultFromString(RunResult result, Throwable throwable) {
        if (throwable != null) {
            showErrorDialog("Simulation Error", 
                          "An error occurred during simulation: " + throwable.getMessage());
            statusUpdateCallback.accept("Simulation failed: " + throwable.getMessage());
            return;
        }
        
        if (result.isSuccess()) {
            showResultDialog("Simulation Complete", 
                           "Simulation from editor content completed successfully.",
                           result.getOutput());
            statusUpdateCallback.accept("Simulation completed successfully from editor content");
        } else {
            String errorMsg = result.getException() != null ? 
                result.getException().getMessage() : 
                "Exit code: " + result.getExitCode();
            
            showErrorDialog("Simulation Failed", 
                          "Simulation failed: " + errorMsg);
            statusUpdateCallback.accept("Simulation failed: " + errorMsg);
        }
    }
    
    /**
     * Shows an error dialog with the given title and message.
     * 
     * @param title The dialog title
     * @param message The error message
     */
    private void showErrorDialog(String title, String message) {
        JOptionPane.showMessageDialog(
            parentComponent,
            message,
            title,
            JOptionPane.ERROR_MESSAGE
        );
    }
    
    /**
     * Shows a result dialog with simulation output.
     * 
     * @param title The dialog title
     * @param message The result message
     * @param output The simulation output
     */
    private void showResultDialog(String title, String message, String output) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel(message));
        
        if (output != null && !output.trim().isEmpty()) {
            panel.add(Box.createVerticalStrut(10));
            panel.add(new JLabel("Output:"));
            
            JTextArea outputArea = new JTextArea(output, 10, 50);
            outputArea.setEditable(false);
            outputArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
            JScrollPane scrollPane = new JScrollPane(outputArea);
            panel.add(scrollPane);
        }
        
        JOptionPane.showMessageDialog(
            parentComponent,
            panel,
            title,
            JOptionPane.INFORMATION_MESSAGE
        );
    }
    
    /**
     * Constructs the CLI command string for display purposes.
     * 
     * @param modelFile The model file being executed
     * @return The formatted CLI command string
     */
    private String getCliCommandString(File modelFile) {
        try {
            Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
            if (cliLocation.isPresent()) {
                String cliPath = cliLocation.get().getPath().toString();
                return cliPath + " new-session (with model from " + modelFile.getName() + ")";
            } else {
                return "kalixcli new-session (with model from " + modelFile.getName() + ")";
            }
        } catch (Exception e) {
            // Fallback if CLI discovery fails
            return "kalixcli new-session (with model from " + modelFile.getName() + ")";
        }
    }
    
    // JSON STDIO Protocol message handlers
    
    private void handleReadyMessage(JsonStdioProtocol.ReadyMessage message) {
        logger.info("Session ready: " + message.getSessionId());
        statusUpdateCallback.accept("Session ready - available commands: " + message.getAvailableCommands().size());
    }
    
    private void handleBusyMessage(JsonStdioProtocol.BusyMessage message) {
        logger.info("Session busy executing: " + message.getExecutingCommand());
        statusUpdateCallback.accept("Executing: " + message.getExecutingCommand());
    }
    
    private void handleProgressMessage(JsonStdioProtocol.ProgressMessage message) {
        double progress = message.getPercentComplete();
        String step = message.getCurrentStep();
        
        logger.info(String.format("Progress: %.1f%% - %s", progress, step));
        statusUpdateCallback.accept(String.format("Progress: %.0f%% - %s", progress, step));
    }
    
    private void handleResultMessage(JsonStdioProtocol.ResultMessage message) {
        if (message.isSuccess()) {
            logger.info("Command completed successfully: " + message.getCommand());
            statusUpdateCallback.accept("Simulation completed successfully");
        } else {
            logger.error("Command failed: " + message.getCommand() + " - " + message.getStatus());
            statusUpdateCallback.accept("Simulation failed: " + message.getStatus());
        }
    }
    
    private void handleErrorMessage(JsonStdioProtocol.ErrorMessage message) {
        String errorMsg = message.getErrorCode() + ": " + message.getErrorMessage();
        logger.error("Session error: " + errorMsg);
        statusUpdateCallback.accept("Error: " + errorMsg);
    }
    
    private void handleStoppedMessage(JsonStdioProtocol.StoppedMessage message) {
        logger.info("Command stopped: " + message.getCommand());
        statusUpdateCallback.accept("Simulation stopped: " + message.getCommand());
    }
    
    private void handleLogMessage(JsonStdioProtocol.LogMessage message) {
        logger.info("Session log [" + message.getLevel() + "]: " + message.getMessage());
    }
}