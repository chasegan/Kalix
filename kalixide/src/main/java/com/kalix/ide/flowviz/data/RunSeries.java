package com.kalix.ide.flowviz.data;

/**
 * {@link SeriesRef} variant for a series produced by a specific simulation run.
 *
 * <p>{@code runId} is a stable identifier assigned at {@code RunInfoImpl} construction
 * and never mutates — renaming a run does not change it. This makes the identity
 * invariant across UI relabelling, which is the whole point of the {@code SeriesRef}
 * abstraction.</p>
 *
 * <p>{@code (runId, baseName)} is unique because a run produces a fixed set of distinct
 * output series.</p>
 */
public record RunSeries(long runId, String baseName) implements SeriesRef {
}
