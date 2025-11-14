package com.kalix.ide.components;

import javax.swing.Timer;

/**
 * Progress bar that automatically hides itself after a delay when no new progress updates are received.
 * Extends StatusProgressBar with auto-hiding functionality that resets whenever new progress is set.
 */
public class AutoHidingProgressBar extends StatusProgressBar {

    private static final int AUTO_HIDE_DELAY_MS = 2000; // 2 seconds

    private Timer autoHideTimer;

    /**
     * Creates a new AutoHidingProgressBar.
     */
    public AutoHidingProgressBar() {
        super();
        setupAutoHideTimer();
    }

    /**
     * Sets up the auto-hide timer.
     */
    private void setupAutoHideTimer() {
        autoHideTimer = new Timer(AUTO_HIDE_DELAY_MS, e -> {
            hideProgress();
        });
        autoHideTimer.setRepeats(false);
    }

    /**
     * Resets the auto-hide timer. Called whenever progress is updated.
     * Only starts the timer if progress is at 100%.
     */
    private void resetAutoHideTimer() {
        if (autoHideTimer != null) {
            autoHideTimer.stop();
            if (isProgressVisible() && getProgress() >= 1.0) {
                autoHideTimer.start();
            }
        }
    }

    /**
     * Cancels the auto-hide timer. Called when progress bar is manually hidden.
     */
    private void cancelAutoHideTimer() {
        if (autoHideTimer != null) {
            autoHideTimer.stop();
        }
    }

    // Override progress setting methods to reset timer

    @Override
    public void setProgress(double progress, boolean animate) {
        super.setProgress(progress, animate);
        resetAutoHideTimer();
    }

    @Override
    public void setProgressPercentage(double percentage) {
        super.setProgressPercentage(percentage);
        resetAutoHideTimer();
    }

    @Override
    public void setProgressText(String text) {
        super.setProgressText(text);
        resetAutoHideTimer();
    }

    @Override
    public void showProgress(double initialProgress, String text) {
        super.showProgress(initialProgress, text);
        resetAutoHideTimer();
    }

    @Override
    public void showProgress(double initialProgress, String text, String command) {
        super.showProgress(initialProgress, text, command);
        resetAutoHideTimer();
    }

    @Override
    public void hideProgress() {
        cancelAutoHideTimer();
        super.hideProgress();
    }
}