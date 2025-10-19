package com.kalix.ide.cli;

import java.util.function.Consumer;

/**
 * Abstract base class for programs that execute in a kalixcli session.
 * Provides common infrastructure for state management, message handling,
 * and callback patterns.
 *
 * Subclasses implement specific program logic (e.g., model simulation, optimisation).
 */
public abstract class AbstractSessionProgram {

    // Common fields shared by all programs
    protected final String sessionKey;
    protected final SessionManager sessionManager;
    protected final Consumer<String> statusUpdater;
    protected final Consumer<ProgressParser.ProgressInfo> progressCallback;

    /**
     * Creates a new session program.
     *
     * @param sessionKey the session key
     * @param sessionManager the session manager
     * @param statusUpdater callback for status updates
     * @param progressCallback callback for progress updates (may be null)
     */
    protected AbstractSessionProgram(String sessionKey,
                                     SessionManager sessionManager,
                                     Consumer<String> statusUpdater,
                                     Consumer<ProgressParser.ProgressInfo> progressCallback) {
        this.sessionKey = sessionKey;
        this.sessionManager = sessionManager;
        this.statusUpdater = statusUpdater;
        this.progressCallback = progressCallback;
    }

    /**
     * Handles a JSON protocol message.
     * This is the main entry point for message routing.
     * Subclasses should implement their state-specific logic.
     *
     * @param message the message to handle
     * @return true if the message was handled by this program
     */
    public abstract boolean handleMessage(JsonMessage.SystemMessage message);

    /**
     * Checks if the program is still active (not completed or failed).
     *
     * @return true if the program is still active
     */
    public abstract boolean isActive();

    /**
     * Checks if the program has completed (successfully or with error).
     *
     * @return true if completed
     */
    public abstract boolean isCompleted();

    /**
     * Checks if the program has failed.
     *
     * @return true if failed
     */
    public abstract boolean isFailed();

    /**
     * Gets a human-readable description of the current state.
     *
     * @return state description
     */
    public abstract String getStateDescription();

    /**
     * Common utility: Extract error message from a system message.
     * Uses the errorMessage field if available, falls back to taskType.
     *
     * @param message the system message
     * @return error message string
     */
    protected String extractErrorMessage(JsonMessage.SystemMessage message) {
        if (message.getErrorMessage() != null) {
            return message.getErrorMessage();
        } else if (message.getTaskType() != null) {
            return message.getTaskType();
        }
        return "Unknown error";
    }

    /**
     * Common utility: Extract result message from a system message.
     *
     * @param message the system message
     * @return result message string
     */
    protected String extractResultMessage(JsonMessage.SystemMessage message) {
        if (message.getResult() != null) {
            return message.getResult().toString();
        }
        return "Completed (no result data)";
    }

    /**
     * Common utility: Send a command to the session.
     *
     * @param command the JSON command string
     * @param successMessage message to show on successful send
     * @param errorMessage message to show on error
     */
    protected void sendCommand(String command, String successMessage, String errorMessage) {
        sessionManager.sendCommand(sessionKey, command)
            .thenRun(() -> {
                if (successMessage != null) {
                    statusUpdater.accept(successMessage);
                }
            })
            .exceptionally(throwable -> {
                if (errorMessage != null) {
                    statusUpdater.accept(errorMessage + ": " + throwable.getMessage());
                }
                return null;
            });
    }

    /**
     * Common utility: Handle progress updates from BUSY messages.
     *
     * @param message the BUSY message
     * @param commandName the command name for context
     */
    protected void handleProgressUpdate(JsonMessage.SystemMessage message, String commandName) {
        if (message.getCurrent() != null && message.getTotal() != null && progressCallback != null) {
            double percentage = JsonMessage.ProgressHelper.calculatePercentage(message);
            String task = message.getTaskType() != null ? message.getTaskType() : "Processing";
            ProgressParser.ProgressInfo progressInfo =
                ProgressParser.createFromJson(percentage, task, commandName);
            progressCallback.accept(progressInfo);
        }
    }
}
