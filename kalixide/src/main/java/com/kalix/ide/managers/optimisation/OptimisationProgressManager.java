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
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
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

        // Update start time label
        startTimeLabel.setText("Start: " + startTime.format(TIME_FORMATTER));

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
            String evalText = String.format("Evaluations: %d", result.getEvaluations());
            if (result.getGenerations() != null) {
                evalText += String.format(" (Gen %d)", result.getGenerations());
            }
            evaluationProgressLabel.setText(evalText);
        } else if (result.getCurrentProgress() != null) {
            evaluationProgressLabel.setText(String.format("Progress: %d%%", result.getCurrentProgress()));
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
                String elapsed = formatDuration(duration);
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
     * Stops progress tracking for an optimisation.
     *
     * @param sessionKey The session key
     */
    public void stopProgress(String sessionKey) {
        sessionStartTimes.remove(sessionKey);

        if (currentOptimisation != null &&
            currentOptimisation.getSessionKey().equals(sessionKey)) {
            elapsedTimer.stop();
            currentOptimisation = null;

            if (progressBar != null) {
                progressBar.setVisible(false);
            }
        }
    }

    /**
     * Updates the elapsed time display.
     * Called by the timer for real-time updates.
     */
    private void updateElapsedTime() {
        if (currentOptimisation == null) {
            return;
        }

        LocalDateTime startTime = sessionStartTimes.get(currentOptimisation.getSessionKey());
        if (startTime == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Duration duration = Duration.between(startTime, now);

        String elapsed = formatDuration(duration);
        elapsedTimeLabel.setText(String.format("Elapsed: %s", elapsed));
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
                startTimeLabel.setText("Start: " + result.getStartTime().format(TIME_FORMATTER));
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
     * Cleans up resources.
     */
    public void dispose() {
        elapsedTimer.stop();
        sessionStartTimes.clear();
        currentOptimisation = null;
    }
}