package com.kalix.gui.cli;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitors process output streams in real-time, providing callbacks for output lines
 * and parsing progress indicators.
 */
public class StreamMonitor {
    
    private final InputStream inputStream;
    private final Consumer<String> lineCallback;
    private final Consumer<String> progressCallback;
    
    // Enhanced progress patterns for kalixcli and similar tools
    private static final Pattern PROGRESS_PERCENTAGE = Pattern.compile("(?i)(?:progress:?\\s*)?(\\d+(?:\\.\\d+)?)%");
    private static final Pattern PROGRESS_FRACTION = Pattern.compile("(?i)(?:progress:?\\s*)?(\\d+)/(\\d+)");
    private static final Pattern PROGRESS_STEP = Pattern.compile("(?i)step\\s+(\\d+)\\s+(?:of|/)\\s+(\\d+)");
    private static final Pattern PROGRESS_WORDS = Pattern.compile("(?i)(processing|analyzing|building|running|completed|finished|done|executing|simulating|calibrating|optimizing)");
    
    // Kalixcli-specific patterns
    private static final Pattern KALIX_PROGRESS = Pattern.compile("(?i)(?:simulation|calibration|optimization)\\s+progress:?\\s*(\\d+(?:\\.\\d+)?)%");
    private static final Pattern KALIX_ITERATION = Pattern.compile("(?i)iteration\\s+(\\d+)(?:\\s+of\\s+(\\d+))?(?:.*?(\\d+(?:\\.\\d+)?)%)?");
    private static final Pattern KALIX_TIME_STEP = Pattern.compile("(?i)time\\s+step\\s+(\\d+)(?:\\s+of\\s+(\\d+))?");
    private static final Pattern KALIX_PHASE = Pattern.compile("(?i)(initialization|simulation|calibration|optimization|post-processing)\\s*(?:phase)?\\s*(?::|-)\\s*(\\d+(?:\\.\\d+)?)%");
    
    /**
     * Creates a new StreamMonitor.
     * 
     * @param inputStream the input stream to monitor (stdout or stderr)
     * @param lineCallback callback for each line of output (can be null)
     * @param progressCallback callback for progress updates (can be null)
     */
    public StreamMonitor(InputStream inputStream, Consumer<String> lineCallback, Consumer<String> progressCallback) {
        this.inputStream = inputStream;
        this.lineCallback = lineCallback;
        this.progressCallback = progressCallback;
    }
    
    /**
     * Starts monitoring the stream asynchronously.
     * 
     * @param executorService the executor service to run the monitoring task
     * @return a CompletableFuture that completes with the full stream content when the stream is closed
     */
    public CompletableFuture<String> startMonitoring(ExecutorService executorService) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder fullOutput = new StringBuilder();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // Add to full output
                    fullOutput.append(line).append(System.lineSeparator());
                    
                    // Call line callback if provided
                    if (lineCallback != null) {
                        try {
                            lineCallback.accept(line);
                        } catch (Exception e) {
                            // Don't let callback exceptions stop stream monitoring
                            System.err.println("Error in line callback: " + e.getMessage());
                        }
                    }
                    
                    // Parse and report progress if callback provided
                    if (progressCallback != null) {
                        String progressInfo = parseProgressInfo(line);
                        if (progressInfo != null) {
                            try {
                                progressCallback.accept(progressInfo);
                            } catch (Exception e) {
                                // Don't let callback exceptions stop stream monitoring
                                System.err.println("Error in progress callback: " + e.getMessage());
                            }
                        }
                    }
                }
                
            } catch (Exception e) {
                // Log the error but return what we have so far
                System.err.println("Error reading stream: " + e.getMessage());
            }
            
            return fullOutput.toString();
        }, executorService);
    }
    
    /**
     * Parses a line for progress information.
     * Enhanced with kalixcli-specific patterns.
     * 
     * @param line the line to parse
     * @return progress information string, or null if no progress found
     */
    private String parseProgressInfo(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        
        String trimmedLine = line.trim();
        
        // Check kalixcli-specific patterns first (most specific)
        
        // Kalixcli progress with phase (e.g., "Simulation progress: 45%")
        Matcher kalixMatcher = KALIX_PROGRESS.matcher(trimmedLine);
        if (kalixMatcher.find()) {
            String percentage = kalixMatcher.group(1);
            return "Progress: " + percentage + "%";
        }
        
        // Kalixcli phase progress (e.g., "Initialization: 25%", "Simulation phase - 67%")
        Matcher phaseMatcher = KALIX_PHASE.matcher(trimmedLine);
        if (phaseMatcher.find()) {
            String phase = capitalizeFirst(phaseMatcher.group(1));
            String percentage = phaseMatcher.group(2);
            return phase + ": " + percentage + "%";
        }
        
        // Kalixcli iteration with optional percentage (e.g., "Iteration 5 of 20 - 45%")
        Matcher iterMatcher = KALIX_ITERATION.matcher(trimmedLine);
        if (iterMatcher.find()) {
            int current = Integer.parseInt(iterMatcher.group(1));
            String totalStr = iterMatcher.group(2);
            String percentageStr = iterMatcher.group(3);
            
            if (totalStr != null) {
                int total = Integer.parseInt(totalStr);
                int calculatedPercentage = (int) ((current * 100.0) / total);
                
                if (percentageStr != null) {
                    return "Iteration " + current + "/" + total + " (" + percentageStr + "%)";
                } else {
                    return "Iteration " + current + "/" + total + " (" + calculatedPercentage + "%)";
                }
            } else {
                return "Iteration " + current + (percentageStr != null ? " (" + percentageStr + "%)" : "");
            }
        }
        
        // Kalixcli time step (e.g., "Time step 150 of 1000")
        Matcher timeStepMatcher = KALIX_TIME_STEP.matcher(trimmedLine);
        if (timeStepMatcher.find()) {
            int current = Integer.parseInt(timeStepMatcher.group(1));
            String totalStr = timeStepMatcher.group(2);
            
            if (totalStr != null) {
                int total = Integer.parseInt(totalStr);
                int percentage = (int) ((current * 100.0) / total);
                return "Time step " + current + "/" + total + " (" + percentage + "%)";
            } else {
                return "Time step " + current;
            }
        }
        
        // Generic patterns (less specific)
        
        // Look for percentage (e.g., "50%", "Progress: 75%")
        Matcher percentageMatcher = PROGRESS_PERCENTAGE.matcher(trimmedLine);
        if (percentageMatcher.find()) {
            String percentage = percentageMatcher.group(1);
            return "Progress: " + percentage + "%";
        }
        
        // Look for fraction (e.g., "5/10", "Processing 3/7")
        Matcher fractionMatcher = PROGRESS_FRACTION.matcher(trimmedLine);
        if (fractionMatcher.find()) {
            int current = Integer.parseInt(fractionMatcher.group(1));
            int total = Integer.parseInt(fractionMatcher.group(2));
            int percentage = (int) ((current * 100.0) / total);
            return "Progress: " + current + "/" + total + " (" + percentage + "%)";
        }
        
        // Look for step indicators (e.g., "Step 2 of 5")
        Matcher stepMatcher = PROGRESS_STEP.matcher(trimmedLine);
        if (stepMatcher.find()) {
            int current = Integer.parseInt(stepMatcher.group(1));
            int total = Integer.parseInt(stepMatcher.group(2));
            int percentage = (int) ((current * 100.0) / total);
            return "Step " + current + " of " + total + " (" + percentage + "%)";
        }
        
        // Look for progress words
        Matcher wordMatcher = PROGRESS_WORDS.matcher(trimmedLine);
        if (wordMatcher.find()) {
            String word = wordMatcher.group(1);
            return capitalizeFirst(word) + "...";
        }
        
        // Look for other common patterns
        if (containsProgressKeywords(trimmedLine)) {
            return trimmedLine; // Return the whole line if it seems progress-related
        }
        
        return null;
    }
    
    /**
     * Checks if a line contains keywords that suggest it's progress-related.
     */
    private boolean containsProgressKeywords(String line) {
        String lowerLine = line.toLowerCase();
        
        // Common progress-related keywords including kalixcli-specific terms
        String[] keywords = {
            "loading", "initializing", "starting", "executing", "running",
            "processing", "analyzing", "building", "compiling", "generating",
            "writing", "reading", "parsing", "validating", "computing",
            "calculating", "optimizing", "finishing", "completing", "done",
            
            // Kalixcli-specific terms
            "simulating", "simulation", "calibrating", "calibration",
            "optimization", "iteration", "time step", "convergence",
            "solving", "model", "parameter", "objective", "constraint",
            "initialization", "post-processing", "results", "output"
        };
        
        for (String keyword : keywords) {
            if (lowerLine.contains(keyword)) {
                return true;
            }
        }
        
        // Look for time indicators
        if (lowerLine.matches(".*\\b\\d+\\s*(ms|seconds?|minutes?)\\b.*")) {
            return true;
        }
        
        // Look for file/item counts
        if (lowerLine.matches(".*\\b\\d+\\s*(files?|items?|records?)\\b.*")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Capitalizes the first letter of a string.
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
    
    /**
     * Factory method to create a monitor for stdout with progress parsing.
     */
    public static StreamMonitor forStdout(InputStream stdout, Consumer<String> lineCallback, Consumer<String> progressCallback) {
        return new StreamMonitor(stdout, lineCallback, progressCallback);
    }
    
    /**
     * Factory method to create a monitor for stderr (typically no progress parsing).
     */
    public static StreamMonitor forStderr(InputStream stderr, Consumer<String> lineCallback) {
        return new StreamMonitor(stderr, lineCallback, null);
    }
    
    /**
     * Factory method to create a simple monitor with just line callbacks.
     */
    public static StreamMonitor withLineCallback(InputStream inputStream, Consumer<String> lineCallback) {
        return new StreamMonitor(inputStream, lineCallback, null);
    }
    
    /**
     * Factory method to create a monitor that only collects output (no callbacks).
     */
    public static StreamMonitor collectOnly(InputStream inputStream) {
        return new StreamMonitor(inputStream, null, null);
    }
}