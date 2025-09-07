package com.kalix.gui.cli;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized utility for parsing progress information from kalixcli output.
 * Consolidates all progress parsing logic to avoid duplication across the codebase.
 */
public class ProgressParser {
    
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
    
    // Completion patterns
    private static final Pattern COMPLETION_PATTERN = Pattern.compile("(?i).*(completed|finished|done).*");
    
    /**
     * Represents parsed progress information.
     */
    public static class ProgressInfo {
        private final double percentage;
        private final String description;
        private final String rawLine;
        private final ProgressType type;
        
        public enum ProgressType {
            PERCENTAGE, FRACTION, STEP, ITERATION, TIME_STEP, PHASE, COMPLETION, UNKNOWN
        }
        
        public ProgressInfo(double percentage, String description, String rawLine, ProgressType type) {
            this.percentage = Math.max(0.0, Math.min(100.0, percentage));
            this.description = description != null ? description : "";
            this.rawLine = rawLine != null ? rawLine : "";
            this.type = type != null ? type : ProgressType.UNKNOWN;
        }
        
        public double getPercentage() { return percentage; }
        public String getDescription() { return description; }
        public String getRawLine() { return rawLine; }
        public ProgressType getType() { return type; }
        public boolean isCompletion() { return type == ProgressType.COMPLETION || percentage >= 100.0; }
        
        @Override
        public String toString() {
            return String.format("Progress[%.1f%% - %s]", percentage, description);
        }
    }
    
    /**
     * Parses a line for progress information with enhanced kalixcli-specific patterns.
     * 
     * @param line the line to parse
     * @return progress information if found, empty otherwise
     */
    public static Optional<ProgressInfo> parseProgress(String line) {
        if (line == null || line.trim().isEmpty()) {
            return Optional.empty();
        }
        
        String trimmedLine = line.trim();
        
        // Check for completion first
        if (COMPLETION_PATTERN.matcher(trimmedLine).matches()) {
            return Optional.of(new ProgressInfo(100.0, trimmedLine, line, ProgressInfo.ProgressType.COMPLETION));
        }
        
        // Check kalixcli-specific patterns first (most specific)
        
        // Kalixcli progress with phase (e.g., "Simulation progress: 45%")
        Matcher kalixMatcher = KALIX_PROGRESS.matcher(trimmedLine);
        if (kalixMatcher.find()) {
            double percentage = Double.parseDouble(kalixMatcher.group(1));
            return Optional.of(new ProgressInfo(percentage, "Progress: " + percentage + "%", 
                line, ProgressInfo.ProgressType.PERCENTAGE));
        }
        
        // Kalixcli phase progress (e.g., "Initialization: 25%", "Simulation phase - 67%")
        Matcher phaseMatcher = KALIX_PHASE.matcher(trimmedLine);
        if (phaseMatcher.find()) {
            String phase = capitalizeFirst(phaseMatcher.group(1));
            double percentage = Double.parseDouble(phaseMatcher.group(2));
            return Optional.of(new ProgressInfo(percentage, phase + ": " + percentage + "%", 
                line, ProgressInfo.ProgressType.PHASE));
        }
        
        // Kalixcli iteration with optional percentage (e.g., "Iteration 5 of 20 - 45%")
        Matcher iterMatcher = KALIX_ITERATION.matcher(trimmedLine);
        if (iterMatcher.find()) {
            int current = Integer.parseInt(iterMatcher.group(1));
            String totalStr = iterMatcher.group(2);
            String percentageStr = iterMatcher.group(3);
            
            if (totalStr != null) {
                int total = Integer.parseInt(totalStr);
                double calculatedPercentage = (current * 100.0) / total;
                
                String description;
                if (percentageStr != null) {
                    description = "Iteration " + current + "/" + total + " (" + percentageStr + "%)";
                    calculatedPercentage = Double.parseDouble(percentageStr);
                } else {
                    description = "Iteration " + current + "/" + total + " (" + String.format("%.0f", calculatedPercentage) + "%)";
                }
                
                return Optional.of(new ProgressInfo(calculatedPercentage, description, 
                    line, ProgressInfo.ProgressType.ITERATION));
            } else {
                String description = "Iteration " + current + (percentageStr != null ? " (" + percentageStr + "%)" : "");
                double percentage = percentageStr != null ? Double.parseDouble(percentageStr) : 0.0;
                return Optional.of(new ProgressInfo(percentage, description, 
                    line, ProgressInfo.ProgressType.ITERATION));
            }
        }
        
        // Kalixcli time step (e.g., "Time step 150 of 1000")
        Matcher timeStepMatcher = KALIX_TIME_STEP.matcher(trimmedLine);
        if (timeStepMatcher.find()) {
            int current = Integer.parseInt(timeStepMatcher.group(1));
            String totalStr = timeStepMatcher.group(2);
            
            if (totalStr != null) {
                int total = Integer.parseInt(totalStr);
                double percentage = (current * 100.0) / total;
                String description = "Time step " + current + "/" + total + " (" + String.format("%.0f", percentage) + "%)";
                return Optional.of(new ProgressInfo(percentage, description, 
                    line, ProgressInfo.ProgressType.TIME_STEP));
            } else {
                return Optional.of(new ProgressInfo(0.0, "Time step " + current, 
                    line, ProgressInfo.ProgressType.TIME_STEP));
            }
        }
        
        // Generic patterns (less specific)
        
        // Look for percentage (e.g., "50%", "Progress: 75%")
        Matcher percentageMatcher = PROGRESS_PERCENTAGE.matcher(trimmedLine);
        if (percentageMatcher.find()) {
            double percentage = Double.parseDouble(percentageMatcher.group(1));
            return Optional.of(new ProgressInfo(percentage, "Progress: " + percentage + "%", 
                line, ProgressInfo.ProgressType.PERCENTAGE));
        }
        
        // Look for fraction (e.g., "5/10", "Processing 3/7")
        Matcher fractionMatcher = PROGRESS_FRACTION.matcher(trimmedLine);
        if (fractionMatcher.find()) {
            int current = Integer.parseInt(fractionMatcher.group(1));
            int total = Integer.parseInt(fractionMatcher.group(2));
            double percentage = (current * 100.0) / total;
            String description = "Progress: " + current + "/" + total + " (" + String.format("%.0f", percentage) + "%)";
            return Optional.of(new ProgressInfo(percentage, description, 
                line, ProgressInfo.ProgressType.FRACTION));
        }
        
        // Look for step indicators (e.g., "Step 2 of 5")
        Matcher stepMatcher = PROGRESS_STEP.matcher(trimmedLine);
        if (stepMatcher.find()) {
            int current = Integer.parseInt(stepMatcher.group(1));
            int total = Integer.parseInt(stepMatcher.group(2));
            double percentage = (current * 100.0) / total;
            String description = "Step " + current + " of " + total + " (" + String.format("%.0f", percentage) + "%)";
            return Optional.of(new ProgressInfo(percentage, description, 
                line, ProgressInfo.ProgressType.STEP));
        }
        
        // Look for progress words
        Matcher wordMatcher = PROGRESS_WORDS.matcher(trimmedLine);
        if (wordMatcher.find()) {
            String word = wordMatcher.group(1);
            return Optional.of(new ProgressInfo(0.0, capitalizeFirst(word) + "...", 
                line, ProgressInfo.ProgressType.UNKNOWN));
        }
        
        return Optional.empty();
    }
    
    /**
     * Checks if a line indicates completion.
     * 
     * @param line the line to check
     * @return true if the line indicates completion
     */
    public static boolean isCompletionLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        
        return line.toLowerCase().contains("completed") ||
               line.toLowerCase().contains("finished") ||
               line.toLowerCase().contains("simulation completed") ||
               line.toLowerCase().contains("done");
    }
    
    /**
     * Capitalizes the first letter of a string.
     */
    private static String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}