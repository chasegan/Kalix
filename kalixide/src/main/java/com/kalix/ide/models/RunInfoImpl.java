package com.kalix.ide.models;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.managers.RunContextMenuManager;

/**
 * Implementation of run information for the Run Manager.
 * Holds run metadata and provides status determination logic.
 */
public class RunInfoImpl implements RunContextMenuManager.RunInfo {

    /**
     * Enum representing the detailed status of a simulation run.
     * This provides more granular states than RunContextMenuManager.RunStatus.
     */
    public enum DetailedRunStatus {
        STARTING("Starting"),
        LOADING("Loading Model"),
        RUNNING("Running"),
        DONE("Done"),
        ERROR("Error"),
        STOPPED("Stopped");

        private final String displayName;

        DetailedRunStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private String runName; // Mutable to allow renaming
    private final SessionManager.KalixSession session;

    /**
     * Creates a new RunInfo instance.
     *
     * @param runName The display name for this run
     * @param session The underlying Kalix session
     */
    public RunInfoImpl(String runName, SessionManager.KalixSession session) {
        this.runName = runName;
        this.session = session;
    }

    @Override
    public String getRunName() {
        return runName;
    }

    @Override
    public void setRunName(String newName) {
        this.runName = newName;
    }

    @Override
    public SessionManager.KalixSession getSession() {
        return session;
    }

    /**
     * Gets the simplified run status for context menu operations.
     * Maps detailed status to the simpler RunContextMenuManager.RunStatus enum.
     */
    @Override
    public RunContextMenuManager.RunStatus getRunStatus() {
        DetailedRunStatus status = getDetailedRunStatus();
        // Map to manager's simpler enum
        switch (status) {
            case DONE:
                return RunContextMenuManager.RunStatus.DONE;
            case ERROR:
                return RunContextMenuManager.RunStatus.ERROR;
            case STOPPED:
                return RunContextMenuManager.RunStatus.STOPPED;
            default:
                return RunContextMenuManager.RunStatus.RUNNING;
        }
    }

    /**
     * Gets the detailed run status with more granular states.
     * Used for display purposes and fine-grained state tracking.
     *
     * @return The detailed run status
     */
    public DetailedRunStatus getDetailedRunStatus() {
        if (session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) session.getActiveProgram();

            if (program.isFailed()) {
                return DetailedRunStatus.ERROR;
            } else if (program.isCompleted()) {
                return DetailedRunStatus.DONE;
            } else {
                String stateDesc = program.getStateDescription();
                if (stateDesc.contains("Loading")) {
                    return DetailedRunStatus.LOADING;
                } else if (stateDesc.contains("Running")) {
                    return DetailedRunStatus.RUNNING;
                } else {
                    return DetailedRunStatus.STARTING;
                }
            }
        } else {
            // No active program or different program type
            // Determine status based on session state
            switch (session.getState()) {
                case STARTING:
                    return DetailedRunStatus.STARTING;
                case RUNNING:
                    return DetailedRunStatus.RUNNING;
                case READY:
                    return DetailedRunStatus.DONE;
                case ERROR:
                    return DetailedRunStatus.ERROR;
                case TERMINATED:
                    return DetailedRunStatus.STOPPED;
                default:
                    return DetailedRunStatus.STARTING;
            }
        }
    }

    @Override
    public String toString() {
        return runName;
    }
}