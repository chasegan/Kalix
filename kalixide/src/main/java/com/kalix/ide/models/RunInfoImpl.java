package com.kalix.ide.models;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.managers.RunContextMenuManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of run information for the Run Manager.
 * Holds run metadata and provides status determination logic.
 *
 * <p>Each instance carries an immutable {@code runId} (assigned at construction from
 * a process-wide monotonic counter) that is used as the stable internal identity of
 * the run by {@link com.kalix.ide.flowviz.data.RunSeries}. The {@code runId} is
 * preserved across renames — renaming constructs a fresh {@code RunInfoImpl} with
 * the same session reference but a new {@code runName}, and the new instance carries
 * its own {@code runId} only because the constructor unconditionally generates one.
 * The series-identity layer therefore sees a rename as a no-op: it operates on the
 * runId of whichever {@code RunInfoImpl} is in scope, and the rename swaps in a new
 * instance with a new runId.</p>
 *
 * <p>(In the post-refactor world the pool will be keyed by runId, so callers that
 * want "the data for this run" hold onto the runId rather than the {@code RunInfoImpl}
 * reference. Renames will change which {@code RunInfoImpl} the tree node carries
 * without invalidating the runId of plotted data.)</p>
 */
public class RunInfoImpl implements RunContextMenuManager.RunInfo {

    /** Process-wide monotonic counter. Each {@code RunInfoImpl} gets its own id. */
    private static final AtomicLong NEXT_RUN_ID = new AtomicLong(1);

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

    private final long runId;
    private final String runName;
    private final SessionManager.KalixSession session;

    /**
     * Creates a new RunInfo instance. {@code RunInfoImpl} is immutable once constructed —
     * renaming a run constructs a fresh instance via {@link com.kalix.ide.windows.RunManager#renameRun},
     * which also propagates the new name to all dependent state (plot pool, color map,
     * tab selections, stats models, outputs tree).
     *
     * <p>The constructor allocates a fresh {@link #getRunId() runId}; in the
     * post-refactor world this is the durable internal handle used by all series
     * identity ({@link com.kalix.ide.flowviz.data.RunSeries}). The propagation work
     * done by {@code RunManager.renameRun} today disappears once collections are
     * keyed by runId rather than by the rendered string.</p>
     *
     * @param runName The display name for this run
     * @param session The underlying Kalix session
     */
    public RunInfoImpl(String runName, SessionManager.KalixSession session) {
        this(NEXT_RUN_ID.getAndIncrement(), runName, session);
    }

    /**
     * Internal constructor used by {@link #withName(String)} to construct a renamed
     * instance that shares the same {@code runId} as the original. Direct callers
     * should use {@link #RunInfoImpl(String, SessionManager.KalixSession)}, which
     * allocates a fresh id.
     */
    private RunInfoImpl(long runId, String runName, SessionManager.KalixSession session) {
        this.runId = runId;
        this.runName = runName;
        this.session = session;
    }

    /**
     * Returns the stable internal identifier for this run. Used as the discriminator
     * in {@link com.kalix.ide.flowviz.data.RunSeries} — never changes for the lifetime
     * of the instance, and is preserved across renames (see {@link #withName}).
     */
    public long getRunId() {
        return runId;
    }

    /**
     * Returns a new {@code RunInfoImpl} with the given display name but the same
     * {@code runId} and session as this instance. Used by the rename flow so that
     * series identity ({@link com.kalix.ide.flowviz.data.RunSeries}) survives the
     * relabelling — the runId is the durable handle.
     */
    public RunInfoImpl withName(String newName) {
        if (newName.equals(this.runName)) {
            return this;
        }
        return new RunInfoImpl(this.runId, newName, this.session);
    }

    @Override
    public String getRunName() {
        return runName;
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
        if (session.getActiveProgram() instanceof RunModelProgram program) {

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