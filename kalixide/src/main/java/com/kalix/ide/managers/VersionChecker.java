package com.kalix.ide.managers;

import com.kalix.ide.cli.KalixCliLocator;
import com.kalix.ide.cli.ProcessExecutor;
import com.kalix.ide.cli.StdioLogger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages CLI version checking functionality.
 * Handles CLI discovery and version retrieval with proper error handling.
 */
public class VersionChecker {
    
    private final Consumer<String> statusUpdateCallback;
    private final ProcessExecutor processExecutor;
    private final StdioLogger logger;
    
    /**
     * Result of a version check operation.
     */
    public static class VersionResult {
        private final boolean success;
        private final String version;
        private final String cliPath;
        private final Exception exception;
        
        private VersionResult(boolean success, String version, String cliPath, Exception exception) {
            this.success = success;
            this.version = version;
            this.cliPath = cliPath;
            this.exception = exception;
        }
        
        public static VersionResult success(String version, String cliPath) {
            return new VersionResult(true, version, cliPath, null);
        }
        
        public static VersionResult failure(Exception exception) {
            return new VersionResult(false, null, null, exception);
        }
        
        public boolean isSuccess() { return success; }
        public String getVersion() { return version; }
        public String getCliPath() { return cliPath; }
        public Exception getException() { return exception; }
    }
    
    /**
     * Creates a new VersionChecker instance.
     * 
     * @param statusUpdateCallback Callback for status updates
     */
    public VersionChecker(Consumer<String> statusUpdateCallback) {
        this.statusUpdateCallback = statusUpdateCallback;
        this.processExecutor = new ProcessExecutor();
        this.logger = StdioLogger.getInstance();
    }
    
    /**
     * Gets the CLI version asynchronously.
     * 
     * @return CompletableFuture containing the version result
     */
    public CompletableFuture<VersionResult> getVersionAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                statusUpdateCallback.accept("Checking kalixcli version...");
                
                // Find kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    return VersionResult.failure(new RuntimeException("kalixcli not found. Please check your settings."));
                }
                
                KalixCliLocator.CliLocation cli = cliLocation.get();

                // Execute version command
                return executeVersionCommand(cli);
                
            } catch (Exception e) {
                logger.error("Error during version check", e);
                return VersionResult.failure(e);
            }
        });
    }
    
    /**
     * Gets the CLI version and updates status bar immediately.
     */
    public void checkVersionWithStatusUpdate() {
        CompletableFuture<VersionResult> future = getVersionAsync();
        
        future.whenComplete((result, throwable) -> {
            handleVersionResult(result, throwable);
        });
    }
    
    /**
     * Executes the kalixcli --version command.
     * 
     * @param cli The CLI location
     * @return The version result
     */
    private VersionResult executeVersionCommand(KalixCliLocator.CliLocation cli) {
        try {
            // Configure process execution with short timeout for version check
            ProcessExecutor.ProcessConfig config = new ProcessExecutor.ProcessConfig()
                .timeout(10); // 10 seconds should be more than enough for version check
            
            // Execute kalixcli --version
            ProcessExecutor.ProcessResult result = processExecutor.execute(
                cli.getPath().toString(),
                List.of("--version"),
                config
            );
            
            if (result.isSuccess()) {
                String version = result.getStdout().trim();
                return VersionResult.success(version, cli.getPath().toString());
            } else {
                logger.error("Version command failed with exit code: " + result.getExitCode());
                return VersionResult.failure(new RuntimeException("Version command failed: " + result.getStderr()));
            }
            
        } catch (Exception e) {
            logger.error("Failed to execute version command", e);
            return VersionResult.failure(e);
        }
    }
    
    /**
     * Handles the result of version checking and updates status bar.
     * 
     * @param result The version result
     * @param throwable Any exception that occurred
     */
    private void handleVersionResult(VersionResult result, Throwable throwable) {
        if (throwable != null) {
            String errorMsg = "Version check failed: " + throwable.getMessage();
            logger.error(errorMsg, throwable);
            statusUpdateCallback.accept(errorMsg);
            return;
        }
        
        if (result.isSuccess()) {
            String statusMsg = "kalixcli version: " + result.getVersion();
            statusUpdateCallback.accept(statusMsg);
        } else {
            String errorMsg = result.getException() != null ? 
                "Version check failed: " + result.getException().getMessage() : 
                "Version check failed: Unknown error";
            
            logger.error(errorMsg);
            statusUpdateCallback.accept(errorMsg);
        }
    }
}