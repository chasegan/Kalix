package com.kalix.ide.flowviz.data;

/**
 * {@link SourceRef} variant for a specific simulation run, identified by the same
 * stable {@code runId} that {@link RunSeries} uses. Renaming the run does not change
 * it; a removed run's id is never reused, so stale refs can simply be scrubbed.
 */
public record RunSource(long runId) implements SourceRef {
}
