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
    private String configSnapshot;  // Configuration INI text (the per-node INI tab state)
    private OptimisationConfigModel configModel;  // Per-node structured GUI form state
    private OptimisationResult result;  // Cached result (null if not complete)
    private boolean hasStartedRunning = false;  // True once optimisation has been started
    private boolean iniLocked = false;  // True once the user has edited the INI text directly
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

        if (session.getActiveProgram() instanceof OptimisationProgram program) {

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

    /**
     * Gets the structured GUI form state for this optimisation.
     *
     * @return the config model, or null if the GUI form has not yet been captured
     */
    public OptimisationConfigModel getConfigModel() {
        return configModel;
    }

    public void setConfigModel(OptimisationConfigModel configModel) {
        this.configModel = configModel;
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

    /**
     * Whether the user has taken direct control of this optimisation's INI text.
     *
     * <p>Once locked, the Config (GUI) form is frozen for this optimisation: the
     * INI text becomes the single source of truth, since it may contain advanced
     * configuration the GUI form cannot represent. The lock is one-way and never
     * clears for the life of the optimisation.</p>
     *
     * @return true if the optimisation is configured via INI text
     */
    public boolean isIniLocked() {
        return iniLocked;
    }

    public void setIniLocked(boolean iniLocked) {
        this.iniLocked = iniLocked;
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

    @Override
    public String toString() {
        return String.format("OptimisationInfo[name=%s, status=%s, session=%s]",
                name, getStatus(), session.getSessionKey());
    }
}