package com.kalix.gui.cli;

/**
 * Utility for creating progress information from kalixcli JSON protocol messages.
 * Kalixcli only sends progress via JSON messages with "type":"progress" and "percent_complete" field.
 */
public class ProgressParser {
    
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
     * Creates a ProgressInfo from JSON progress data with command information.
     *
     * @param percentComplete the percentage complete (0.0-100.0)
     * @param currentStep the current step description
     * @param command the command being executed (for color coding)
     * @return progress information
     */
    public static ProgressInfo createFromJson(double percentComplete, String currentStep, String command) {
        String description = currentStep != null ? currentStep : "Processing...";

        // Add phase-specific prefixes for clarity
        if ("load_model_string".equals(command)) {
            description = "Loading: " + description;
        } else if ("run_simulation".equals(command)) {
            description = "Simulating: " + description;
        }

        return new ProgressInfo(percentComplete, description, command, ProgressInfo.ProgressType.PERCENTAGE);
    }

    /**
     * Creates a ProgressInfo from JSON progress data (legacy method for backward compatibility).
     *
     * @param percentComplete the percentage complete (0.0-100.0)
     * @param currentStep the current step description
     * @return progress information
     */
    public static ProgressInfo createFromJson(double percentComplete, String currentStep) {
        return createFromJson(percentComplete, currentStep, "unknown");
    }
}