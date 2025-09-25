package com.kalix.ide.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Enhanced stream monitor for interactive kalixcli communication.
 * Supports asynchronous monitoring with callbacks for prompts, progress, and custom patterns.
 */
public class InteractiveStreamMonitor {
    
    private final InputStream inputStream;
    private final Consumer<String> lineCallback;
    private final Consumer<KalixStdioSession.ProgressInfo> progressCallback;
    private final Consumer<KalixStdioSession.Prompt> promptCallback;
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    
    /**
     * Configuration for interactive monitoring.
     */
    public static class MonitorConfig {
        private Consumer<String> lineCallback;
        private Consumer<KalixStdioSession.ProgressInfo> progressCallback;
        private Consumer<KalixStdioSession.Prompt> promptCallback;
        private Consumer<String> errorCallback;
        private boolean autoParseProgress = true;
        private boolean autoDetectPrompts = true;
        
        public MonitorConfig onLine(Consumer<String> callback) {
            this.lineCallback = callback;
            return this;
        }
        
        public MonitorConfig onProgress(Consumer<KalixStdioSession.ProgressInfo> callback) {
            this.progressCallback = callback;
            return this;
        }
        
        public MonitorConfig onPrompt(Consumer<KalixStdioSession.Prompt> callback) {
            this.promptCallback = callback;
            return this;
        }
        
        public MonitorConfig onError(Consumer<String> callback) {
            this.errorCallback = callback;
            return this;
        }
        
        public MonitorConfig autoParseProgress(boolean enable) {
            this.autoParseProgress = enable;
            return this;
        }
        
        public MonitorConfig autoDetectPrompts(boolean enable) {
            this.autoDetectPrompts = enable;
            return this;
        }
        
        // Getters
        public Consumer<String> getLineCallback() { return lineCallback; }
        public Consumer<KalixStdioSession.ProgressInfo> getProgressCallback() { return progressCallback; }
        public Consumer<KalixStdioSession.Prompt> getPromptCallback() { return promptCallback; }
        public Consumer<String> getErrorCallback() { return errorCallback; }
        public boolean isAutoParseProgress() { return autoParseProgress; }
        public boolean isAutoDetectPrompts() { return autoDetectPrompts; }
    }
    
    /**
     * Creates a new InteractiveStreamMonitor.
     * 
     * @param inputStream the input stream to monitor
     * @param lineCallback callback for each line (can be null)
     * @param progressCallback callback for progress updates (can be null)
     * @param promptCallback callback for detected prompts (can be null)
     */
    public InteractiveStreamMonitor(InputStream inputStream, 
                                   Consumer<String> lineCallback,
                                   Consumer<KalixStdioSession.ProgressInfo> progressCallback,
                                   Consumer<KalixStdioSession.Prompt> promptCallback) {
        this.inputStream = inputStream;
        this.lineCallback = lineCallback;
        this.progressCallback = progressCallback;
        this.promptCallback = promptCallback;
    }
    
    /**
     * Factory method to create a monitor with configuration.
     */
    public static InteractiveStreamMonitor create(InputStream inputStream, MonitorConfig config) {
        return new InteractiveStreamMonitor(
            inputStream,
            config.getLineCallback(),
            config.getProgressCallback(),
            config.getPromptCallback()
        );
    }
    
    /**
     * Starts monitoring the stream asynchronously.
     * 
     * @param executorService the executor service for the monitoring task
     * @return a CompletableFuture that completes when monitoring stops
     */
    public CompletableFuture<Void> startMonitoring(ExecutorService executorService) {
        if (!monitoring.compareAndSet(false, true)) {
            throw new IllegalStateException("Monitor is already running");
        }
        
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && !stopped.get()) {
                    processLine(line);
                }
                
            } catch (IOException e) {
                if (!stopped.get()) {
                    System.err.println("Error reading stream: " + e.getMessage());
                }
            } finally {
                monitoring.set(false);
            }
        }, executorService);
    }
    
    /**
     * Waits for a line matching the given pattern.
     * 
     * @param pattern the pattern to match
     * @param timeoutSeconds timeout in seconds
     * @param executorService executor for the waiting task
     * @return CompletableFuture with the matching line, or null if timeout
     */
    public CompletableFuture<String> waitForPattern(Pattern pattern, int timeoutSeconds, ExecutorService executorService) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeoutSeconds * 1000L;
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && 
                       (System.currentTimeMillis() - startTime) < timeoutMillis) {
                    
                    if (pattern.matcher(line).matches()) {
                        return line;
                    }
                }
                
            } catch (IOException e) {
                // Return null on error
            }
            
            return null; // Timeout or error
        }, executorService);
    }
    
    /**
     * Waits for a prompt to be detected.
     * 
     * @param timeoutSeconds timeout in seconds
     * @param executorService executor for the waiting task
     * @return CompletableFuture with the prompt, or null if timeout
     */
    public CompletableFuture<KalixStdioSession.Prompt> waitForPrompt(int timeoutSeconds, ExecutorService executorService) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeoutSeconds * 1000L;
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null && 
                       (System.currentTimeMillis() - startTime) < timeoutMillis) {
                    
                    KalixStdioSession.Prompt prompt = parsePrompt(line);
                    if (prompt.getType() != KalixStdioSession.PromptType.UNKNOWN) {
                        return prompt;
                    }
                }
                
            } catch (IOException e) {
                // Return null on error
            }
            
            return null; // Timeout or error
        }, executorService);
    }
    
    /**
     * Stops the stream monitoring.
     */
    public void stop() {
        stopped.set(true);
    }
    
    /**
     * Checks if monitoring is currently active.
     */
    public boolean isMonitoring() {
        return monitoring.get();
    }
    
    /**
     * Processes a single line of output.
     */
    private void processLine(String line) {
        try {
            // Always call line callback first
            if (lineCallback != null) {
                lineCallback.accept(line);
            }
            
            // Progress updates are now handled only through JSON protocol messages
            
            // Check for prompts if enabled
            if (promptCallback != null) {
                KalixStdioSession.Prompt prompt = parsePrompt(line);
                if (prompt.getType() != KalixStdioSession.PromptType.UNKNOWN) {
                    promptCallback.accept(prompt);
                }
            }
            
        } catch (Exception e) {
            // Don't let callback exceptions stop monitoring
            System.err.println("Error in stream monitor callback: " + e.getMessage());
        }
    }
    
    /**
     * Parses a line to detect prompts (simplified version of KalixStdioSession logic).
     */
    private KalixStdioSession.Prompt parsePrompt(String line) {
        if (line == null || line.trim().isEmpty()) {
            return new KalixStdioSession.Prompt(line, KalixStdioSession.PromptType.UNKNOWN);
        }
        
        String trimmed = line.trim();
        
        // Common prompt patterns
        if (trimmed.toLowerCase().contains("enter") && trimmed.toLowerCase().contains("filename")) {
            return new KalixStdioSession.Prompt(trimmed, KalixStdioSession.PromptType.MODEL_FILENAME);
        }
        
        if (trimmed.toLowerCase().contains("enter") && trimmed.toLowerCase().contains("model")) {
            return new KalixStdioSession.Prompt(trimmed, KalixStdioSession.PromptType.MODEL_DEFINITION);
        }
        
        if (trimmed.toLowerCase().contains("enter") && (trimmed.toLowerCase().contains("parameter") || trimmed.toLowerCase().contains("value"))) {
            return new KalixStdioSession.Prompt(trimmed, KalixStdioSession.PromptType.PARAMETER_VALUE);
        }
        
        if (trimmed.toLowerCase().matches(".*\\b(yes|no|y/n|continue|proceed)\\b.*\\?.*")) {
            return new KalixStdioSession.Prompt(trimmed, KalixStdioSession.PromptType.CONFIRMATION);
        }
        
        // Generic prompt indicators
        if (trimmed.endsWith(":") || trimmed.endsWith("?") || trimmed.contains(">")) {
            return new KalixStdioSession.Prompt(trimmed, KalixStdioSession.PromptType.UNKNOWN);
        }
        
        return new KalixStdioSession.Prompt(trimmed, KalixStdioSession.PromptType.UNKNOWN);
    }
    
    /**
     * Factory methods for common use cases.
     */
    public static InteractiveStreamMonitor forStdout(InputStream stdout, MonitorConfig config) {
        return create(stdout, config);
    }
    
    public static InteractiveStreamMonitor forStderr(InputStream stderr, Consumer<String> errorCallback) {
        MonitorConfig config = new MonitorConfig()
            .onLine(errorCallback)
            .autoParseProgress(false)  // Usually no progress on stderr
            .autoDetectPrompts(false); // Usually no prompts on stderr
        return create(stderr, config);
    }
    
    /**
     * Simple factory for line-only monitoring.
     */
    public static InteractiveStreamMonitor withLineCallback(InputStream inputStream, Consumer<String> lineCallback) {
        return new InteractiveStreamMonitor(inputStream, lineCallback, null, null);
    }
}