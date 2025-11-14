package com.kalix.ide.managers.optimisation;

import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages progress tracking and time display for optimisation runs.
 * Handles progress bars, timing labels, and evaluation tracking.
 */
public class OptimisationProgressManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationProgressManager.class);

    // Time formatters
    private static final DateTimeFormatter TIME_WITH_MILLIS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // UI Components
    private final StatusProgressBar progressBar;
    private final JLabel startTimeLabel;
    private final JLabel elapsedTimeLabel;
    private final JLabel evaluationProgressLabel;
    private final JLabel bestObjectiveLabel;

    // Timer for elapsed time updates
    private final Timer elapsedTimer;

    // Tracking
    private final Map<String, LocalDateTime> sessionStartTimes = new HashMap<>();
    private OptimisationInfo currentOptimisation;

    /**
     * Creates a new OptimisationProgressManager.
     *
     * @param progressBar The progress bar to manage (can be null)
     */
    public OptimisationProgressManager(StatusProgressBar progressBar) {
        this.progressBar = progressBar;

        // Create labels with consistent styling
        Font labelFont = new Font("SansSerif", Font.PLAIN, 13);

        this.startTimeLabel = new JLabel("Start: —");
        this.startTimeLabel.setFont(labelFont);

        this.elapsedTimeLabel = new JLabel("Elapsed: —");
        this.elapsedTimeLabel.setFont(labelFont);

        this.evaluationProgressLabel = new JLabel("Evaluations: —");
        this.evaluationProgressLabel.setFont(labelFont);

        this.bestObjectiveLabel = new JLabel("Best: —");
        this.bestObjectiveLabel.setFont(labelFont);

        // Create timer for elapsed time updates (50ms for smooth display)
        this.elapsedTimer = new Timer(50, e -> updateElapsedTime());
        this.elapsedTimer.setRepeats(true);
    }

    /**
     * Gets the start time label component.
     */
    public JLabel getStartTimeLabel() {
        return startTimeLabel;
    }

    /**
     * Gets the elapsed time label component.
     */
    public JLabel getElapsedTimeLabel() {
        return elapsedTimeLabel;
    }

    /**
     * Gets the evaluation progress label component.
     */
    public JLabel getEvaluationProgressLabel() {
        return evaluationProgressLabel;
    }

    /**
     * Gets the best objective label component.
     */
    public JLabel getBestObjectiveLabel() {
        return bestObjectiveLabel;
    }

    /**
     * Starts progress tracking for an optimisation.
     *
     * @param info The optimisation to track
     */
    public void startProgress(OptimisationInfo info) {
        if (info == null) {
            return;
        }

        this.currentOptimisation = info;
        LocalDateTime startTime = LocalDateTime.now();
        sessionStartTimes.put(info.getSessionKey(), startTime);

        // Set start time on the result object so it's available everywhere
        OptimisationResult result = info.getResult();
        if (result != null) {
            result.setStartTime(startTime);
        }

        // Update start time label with milliseconds
        startTimeLabel.setText("Start: " + startTime.format(TIME_WITH_MILLIS));

        // Reset progress displays
        elapsedTimeLabel.setText("Elapsed: 00:00:00");
        evaluationProgressLabel.setText("Evaluations: 0");
        bestObjectiveLabel.setText("Best: —");

        // Start elapsed timer
        elapsedTimer.start();

        // Update progress bar if available
        if (progressBar != null) {
            progressBar.setProgress(0);
            progressBar.setProgressText("Starting optimisation...");
            progressBar.setVisible(true);
        }

        logger.info("Started progress tracking for optimisation: {}", info.getName());
    }

    /**
     * Updates progress based on result data.
     *
     * @param info The optimisation info
     * @param result The current result
     */
    public void updateProgress(OptimisationInfo info, OptimisationResult result) {
        if (info == null || result == null) {
            return;
        }

        // Update progress bar
        if (progressBar != null && result.getCurrentProgress() != null) {
            progressBar.setProgress(result.getCurrentProgress());
            String message = result.getProgressDescription() != null ?
                result.getProgressDescription() : "Optimising...";
            progressBar.setProgressText(message);
        }

        // Update labels
        updateProgressLabels(result);

        // Check if completed
        if (result.isSuccess() || result.getEndTime() != null) {
            completeProgress(info, result);
        }
    }

    /**
     * Updates the progress labels with current data.
     */
    private void updateProgressLabels(OptimisationResult result) {
        // Update best objective
        if (result.getBestObjective() != null) {
            bestObjectiveLabel.setText(String.format("Best: %.6f", result.getBestObjective()));
        }

        // Update evaluations
        if (result.getEvaluations() != null) {
            String evalText = String.format("Evaluations: %,d", result.getEvaluations());
            if (result.getCurrentProgress() != null && result.getCurrentProgress() > 0) {
                evalText += String.format(" (%.1f%%)", result.getCurrentProgress().doubleValue());
            }
            evaluationProgressLabel.setText(evalText);
        }
    }

    /**
     * Completes progress tracking for an optimisation.
     *
     * @param info The optimisation info
     * @param result The final result
     */
    public void completeProgress(OptimisationInfo info, OptimisationResult result) {
        if (info == null) {
            return;
        }

        // Stop timer
        elapsedTimer.stop();

        // Final update
        if (result != null) {
            updateProgressLabels(result);

            // Update elapsed time one final time
            if (result.getEndTime() != null && result.getStartTime() != null) {
                Duration duration = Duration.between(result.getStartTime(), result.getEndTime());
                elapsedTimeLabel.setText(String.format("Elapsed: %s (%.3fs)",
                    result.getEndTime().format(TIME_WITH_MILLIS),
                    duration.toMillis() / 1000.0));
            }
        }

        // Hide progress bar
        if (progressBar != null) {
            progressBar.setVisible(false);
        }

        // Clean up tracking
        sessionStartTimes.remove(info.getSessionKey());
        if (currentOptimisation == info) {
            currentOptimisation = null;
        }

        logger.info("Completed progress tracking for optimisation: {}", info.getName());
    }

    /**
     * Updates the elapsed time display.
     * Called by the timer for real-time updates.
     */
    public void updateElapsedTime() {
        if (currentOptimisation == null) {
            return;
        }

        OptimisationResult result = currentOptimisation.getResult();
        if (result == null || result.getStartTime() == null) {
            return;
        }

        java.time.LocalDateTime startTime = result.getStartTime();
        java.time.LocalDateTime endTime = result.getEndTime();

        java.time.LocalDateTime currentTime = (endTime != null) ? endTime : java.time.LocalDateTime.now();
        java.time.Duration duration = java.time.Duration.between(startTime, currentTime);

        double seconds = duration.toMillis() / 1000.0;

        // Format current/finish time with milliseconds
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        String timestamp = currentTime.format(formatter);

        elapsedTimeLabel.setText(String.format("Elapsed: %s (%.3fs)", timestamp, seconds));
    }

    /**
     * Sets the current optimisation being displayed.
     *
     * @param info The optimisation info
     */
    public void setCurrentOptimisation(OptimisationInfo info) {
        // Stop timer if switching
        if (currentOptimisation != info) {
            elapsedTimer.stop();
        }

        this.currentOptimisation = info;

        if (info == null) {
            resetLabels();
            return;
        }

        // Update labels based on current state
        OptimisationResult result = info.getResult();
        if (result != null) {
            // Update start time
            if (result.getStartTime() != null) {
                startTimeLabel.setText("Start: " + result.getStartTime().format(TIME_WITH_MILLIS));
            }

            // Update progress labels
            updateProgressLabels(result);

            // Update elapsed time
            if (result.getEndTime() != null) {
                // Completed - show final time
                Duration duration = result.getElapsedTime();
                if (duration != null) {
                    elapsedTimeLabel.setText(String.format("Elapsed: %s (%.3fs)",
                        result.getEndTime().format(TIME_WITH_MILLIS),
                        duration.toMillis() / 1000.0));
                }
            } else if (result.getStartTime() != null) {
                // Still running - start timer
                sessionStartTimes.put(info.getSessionKey(), result.getStartTime());
                elapsedTimer.start();
            }
        } else {
            resetLabels();
        }
    }

    /**
     * Resets all labels to default state.
     */
    public void resetLabels() {
        startTimeLabel.setText("Start: —");
        elapsedTimeLabel.setText("Elapsed: —");
        evaluationProgressLabel.setText("Evaluations: —");
        bestObjectiveLabel.setText("Best: —");
    }

    /**
     * Formats a duration as HH:mm:ss.
     *
     * @param duration The duration to format
     * @return Formatted string
     */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Updates the timing labels (start time and elapsed time) for an optimisation.
     *
     * @param optInfo The optimisation info
     */
    public void updateTimingLabels(OptimisationInfo optInfo) {
        if (optInfo == null || optInfo.getResult() == null) {
            startTimeLabel.setText("Start: Not started");
            elapsedTimeLabel.setText("Elapsed: N/A");
            return;
        }

        OptimisationResult result = optInfo.getResult();

        // Update start time with milliseconds
        if (result.getStartTime() != null) {
            startTimeLabel.setText("Start: " + result.getStartTime().format(TIME_WITH_MILLIS));
        } else {
            startTimeLabel.setText("Start: Not started");
        }

        // Update elapsed time
        if (result.getStartTime() != null) {
            java.time.LocalDateTime endTime = result.getEndTime();
            if (endTime != null) {
                // Completed - show final elapsed time
                java.time.Duration duration = java.time.Duration.between(result.getStartTime(), endTime);
                double seconds = duration.toMillis() / 1000.0;
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
                elapsedTimeLabel.setText(String.format("Elapsed: %s (%.3fs)", endTime.format(formatter), seconds));
            } else {
                // Still running - updateElapsedTime() will handle real-time updates
                updateElapsedTime();
            }
        } else {
            elapsedTimeLabel.setText("Elapsed: N/A");
        }
    }

    /**
     * Updates the convergence labels (best objective and evaluation progress) with current values.
     *
     * @param result The optimisation result
     */
    public void updateConvergenceLabels(OptimisationResult result) {
        if (result == null) {
            bestObjectiveLabel.setText("Best: —");
            evaluationProgressLabel.setText("Evaluations: —");
            return;
        }

        // Update best objective label
        if (!result.getConvergenceBestObjective().isEmpty()) {
            double bestValue = result.getConvergenceBestObjective().get(result.getConvergenceBestObjective().size() - 1);
            bestObjectiveLabel.setText(String.format("Best: %.6f", bestValue));
        }

        // Update evaluation progress label
        if (!result.getConvergenceEvaluations().isEmpty()) {
            int currentEval = result.getConvergenceEvaluations().get(result.getConvergenceEvaluations().size() - 1);

            // Display count with percentage from backend if available
            String progressText;
            Integer currentProgress = result.getCurrentProgress();
            if (currentProgress != null && currentProgress > 0) {
                progressText = String.format("Evaluations: %,d (%.1f%%)", currentEval, currentProgress.doubleValue());
            } else {
                // No percentage available yet, just show count
                progressText = String.format("Evaluations: %,d", currentEval);
            }

            evaluationProgressLabel.setText(progressText);
        }
    }

    /**
     * Cleans up resources.
     */
    public void dispose() {
        elapsedTimer.stop();
        sessionStartTimes.clear();
        currentOptimisation = null;
    }
}