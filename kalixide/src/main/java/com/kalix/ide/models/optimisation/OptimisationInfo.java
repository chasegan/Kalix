package com.kalix.ide.models.optimisation;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.OptimisationProgram;
import java.time.LocalDateTime;

/**
 * Tracks information about a single optimisation run.
 * Maintains the session, configuration, and result data.
 */
public class OptimisationInfo {

    private String name;  // Mutable to allow renaming
    private final SessionManager.KalixSession session;
    private String configSnapshot;  // Configuration text at time of run
    private OptimisationResult result;  // Cached result (null if not complete)
    private boolean hasStartedRunning = false;  // True once optimisation has been started
    private boolean isConfigModified = false;  // True if config was manually edited
    private LocalDateTime createdTime;

    /**
     * Creates a new OptimisationInfo instance.
     *
     * @param name The display name for this optimisation
     * @param session The Kalix session managing this optimisation
     */
    public OptimisationInfo(String name, SessionManager.KalixSession session) {
        this.name = name;
        this.session = session;
        this.createdTime = LocalDateTime.now();
    }

    /**
     * Gets the current status of the optimisation based on the session state.
     *
     * @return The current optimisation status
     */
    public OptimisationStatus getStatus() {
        if (!hasStartedRunning) {
            return OptimisationStatus.CONFIGURING;
        }

        if (session.getActiveProgram() instanceof OptimisationProgram) {
            OptimisationProgram program = (OptimisationProgram) session.getActiveProgram();

            if (program.isFailed()) {
                return OptimisationStatus.ERROR;
            }
            if (program.isCompleted()) {
                return OptimisationStatus.DONE;
            }

            String stateDesc = program.getStateDescription();
            if (stateDesc.contains("Ready")) {
                return OptimisationStatus.STARTING;
            }
            if (stateDesc.contains("Loading")) {
                return OptimisationStatus.LOADING;
            }
            if (stateDesc.contains("Optimising") || stateDesc.contains("Running")) {
                return OptimisationStatus.RUNNING;
            }

            return OptimisationStatus.STARTING;
        } else {
            // No active program or different program type
            switch (session.getState()) {
                case STARTING:
                    return OptimisationStatus.STARTING;
                case RUNNING:
                    return OptimisationStatus.RUNNING;
                case READY:
                    return result != null ? OptimisationStatus.DONE : OptimisationStatus.CONFIGURING;
                case ERROR:
                    return OptimisationStatus.ERROR;
                case TERMINATED:
                    return OptimisationStatus.STOPPED;
                default:
                    return OptimisationStatus.CONFIGURING;
            }
        }
    }

    /**
     * Gets the session key for this optimisation.
     * Useful for tracking and identifying the optimisation.
     *
     * @return The session key
     */
    public String getSessionKey() {
        return session.getSessionKey();
    }

    // Getters and setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SessionManager.KalixSession getSession() {
        return session;
    }

    public String getConfigSnapshot() {
        return configSnapshot;
    }

    public void setConfigSnapshot(String configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public OptimisationResult getResult() {
        return result;
    }

    public void setResult(OptimisationResult result) {
        this.result = result;
    }

    public boolean hasStartedRunning() {
        return hasStartedRunning;
    }

    public void setHasStartedRunning(boolean hasStartedRunning) {
        this.hasStartedRunning = hasStartedRunning;
    }

    public boolean isConfigModified() {
        return isConfigModified;
    }

    public void setConfigModified(boolean configModified) {
        isConfigModified = configModified;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    /**
     * Checks if this optimisation is currently running.
     *
     * @return true if the optimisation is in a running state
     */
    public boolean isRunning() {
        OptimisationStatus status = getStatus();
        return status == OptimisationStatus.RUNNING ||
               status == OptimisationStatus.LOADING ||
               status == OptimisationStatus.STARTING;
    }

    /**
     * Checks if this optimisation has completed (successfully or with error).
     *
     * @return true if the optimisation is done or errored
     */
    public boolean isComplete() {
        OptimisationStatus status = getStatus();
        return status == OptimisationStatus.DONE ||
               status == OptimisationStatus.ERROR ||
               status == OptimisationStatus.STOPPED;
    }

    @Override
    public String toString() {
        return String.format("OptimisationInfo[name=%s, status=%s, session=%s]",
                name, getStatus(), session.getSessionKey());
    }
}