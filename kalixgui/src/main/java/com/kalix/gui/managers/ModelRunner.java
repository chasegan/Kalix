package com.kalix.gui.managers;

import com.kalix.gui.cli.KalixCliLocator;
import com.kalix.gui.cli.ProcessExecutor;
import com.kalix.gui.cli.CliLogger;

import javax.swing.*;
import java.awt.Component;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages model execution using the kalixcli sim command.
 * Handles file validation, CLI discovery, and asynchronous execution with progress feedback.
 */
public class ModelRunner {
    
    private final Component parentComponent;
    private final Consumer<String> statusUpdateCallback;
    private final ProcessExecutor processExecutor;
    private final CliLogger logger;
    
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
     */
    public ModelRunner(Component parentComponent, Consumer<String> statusUpdateCallback) {
        this.parentComponent = parentComponent;
        this.statusUpdateCallback = statusUpdateCallback;
        this.processExecutor = new ProcessExecutor();
        this.logger = CliLogger.getInstance();
    }
    
    /**
     * Runs the model simulation asynchronously.
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
                
                // Find kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    return RunResult.failure(new RuntimeException("kalixcli not found. Please check your settings."));
                }
                
                KalixCliLocator.CliLocation cli = cliLocation.get();
                logger.info("Running simulation with kalixcli at: " + cli.getPath());
                
                // Execute sim command
                return executeSimCommand(cli, modelFile);
                
            } catch (Exception e) {
                logger.error("Error during model execution", e);
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
     * Executes the kalixcli sim command.
     * 
     * @param cli The CLI location
     * @param modelFile The model file to simulate
     * @return The run result
     */
    private RunResult executeSimCommand(KalixCliLocator.CliLocation cli, File modelFile) {
        try {
            statusUpdateCallback.accept("Running simulation: " + modelFile.getName());
            
            // Configure process execution with longer timeout for simulations
            ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig()
                .timeout(300) // 5 minutes timeout
                .onStdout(line -> logger.info("CLI stdout: " + line))
                .onStderr(line -> logger.warn("CLI stderr: " + line));
            
            // Execute kalixcli sim <model-file>
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                cli.getPath().toString(),
                List.of("sim", modelFile.getAbsolutePath()),
                config
            );
            
            if (result.isSuccess()) {
                logger.info("Model simulation completed successfully");
                return RunResult.success(result.getStdout(), result.getStderr(), result.getExitCode());
            } else {
                logger.error("Model simulation failed with exit code: " + result.getExitCode());
                return RunResult.failure(result.getStdout(), result.getStderr(), result.getExitCode());
            }
            
        } catch (Exception e) {
            logger.error("Failed to execute sim command", e);
            return RunResult.failure(e);
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
                String modelPath = modelFile.getAbsolutePath();
                return cliPath + " sim " + modelPath;
            } else {
                return "kalixcli sim " + modelFile.getAbsolutePath();
            }
        } catch (Exception e) {
            // Fallback if CLI discovery fails
            return "kalixcli sim " + modelFile.getAbsolutePath();
        }
    }
}