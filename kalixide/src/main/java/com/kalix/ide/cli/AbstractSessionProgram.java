package com.kalix.ide.cli;

import com.kalix.ide.utils.StatusReporter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    protected final StatusReporter statusUpdater;
    protected final Consumer<ProgressParser.ProgressInfo> progressCallback;

    /** Names of every command this program has sent; an {@code err} naming any other is not ours. */
    private final Set<String> sentCommands = ConcurrentHashMap.newKeySet();

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
                                     StatusReporter statusUpdater,
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
     * Invoked by {@link SessionManager#installProgram} when the program is attached to a
     * session that has ALREADY emitted its initial {@code rdy} (consumed generically before
     * the program existed). Programs that wait for the initial ready must treat this
     * exactly like receiving that {@code rdy} — otherwise a fast-starting CLI wedges the
     * program in its waiting state forever. Default: no-op.
     */
    public void onSessionReady() {
    }

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
     * Sends a command on the program's session, remembering its name. Every command a
     * program sends goes through here, so {@link #answersAnotherCommand} cannot drift
     * from what was actually sent.
     */
    protected CompletableFuture<Void> send(String commandName, Map<String, Object> parameters) {
        sentCommands.add(commandName);
        return sessionManager.sendCommand(sessionKey,
            JsonStdioProtocol.createCommandMessage(commandName, parameters));
    }

    /**
     * True for an {@code err} that does not name a command this program sent, and so is
     * not the program's to act on (ADR-0014 §3). The engine names the command it is
     * failing or rejecting, so a fetch ({@code get_result}) rejected while the run is
     * busy is the fetch's failure, and a Stop that lands while the engine is idle
     * ("No task is running", naming nothing) is nobody's; neither must fail the run.
     * Such an error still falls through to the status bar.
     */
    protected boolean answersAnotherCommand(JsonMessage.SystemMessage message) {
        String command = message.getCommand();
        return message.systemMessageType() == JsonStdioTypes.SystemMessageType.ERROR
            && (command == null || !sentCommands.contains(command));
    }

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
     * Common utility: Send a command to the session, reporting the outcome of the send.
     *
     * @param commandName the command's name
     * @param parameters the command's parameters
     * @param successMessage message to show on successful send
     * @param errorMessage message to show on error
     */
    protected void sendCommand(String commandName, Map<String, Object> parameters,
                               String successMessage, String errorMessage) {
        send(commandName, parameters)
            .thenRun(() -> {
                if (successMessage != null) {
                    statusUpdater.accept(successMessage);
                }
            })
            .exceptionally(throwable -> {
                if (errorMessage != null) {
                    statusUpdater.error(errorMessage + ": " + throwable.getMessage());
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
