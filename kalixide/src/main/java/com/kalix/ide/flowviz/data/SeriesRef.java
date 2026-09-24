package com.kalix.ide.flowviz.data;

/**
 * Stable internal identity for a time-series. Sealed so all variants are known and
 * pattern-matchable: pattern-matching code stays exhaustive at compile time.
 *
 * <p>This is the primary key everywhere the IDE refers to a series internally — pool
 * lookups, color assignments, tab selections, undo history, stats rows, output tree
 * leaves. The human-visible label (e.g. {@code "node.x.ds_1 [Run_3]"}) is a derived
 * projection produced by {@link LabelResolver}; it is not the identity. Renaming a run
 * therefore does not affect any {@code SeriesRef} — the same refs remain valid; only
 * their projected labels change.</p>
 *
 * <p>The four variants exist to model the four structurally different sources of
 * series data in the IDE:</p>
 * <ul>
 *   <li>{@link RunSeries} — output of a specific simulation run, identified by its
 *       stable {@code runId}.</li>
 *   <li>{@link LastSeries} — alias for the most recently completed run. Resolves at
 *       lookup time to whichever {@code RunSeries} is currently latest.</li>
 *   <li>{@link DatasetSeries} — column from a user-loaded {@code .csv}/{@code .pxt}
 *       file, identified by the dataset's absolute path.</li>
 *   <li>{@link AggregateSeries} — a user-created sum of series from one source,
 *       identified by its stable {@code aggregateId}.</li>
 * </ul>
 *
 * <p>All variants share {@link #baseName()} — the unsuffixed series name, e.g.
 * {@code "node.x.ds_1"} — except {@link AggregateSeries}, whose name is a label and
 * whose {@code baseName()} is an internal key. Display code uses
 * {@link LabelResolver#nameFor(SeriesRef)}.</p>
 */
public sealed interface SeriesRef permits RunSeries, LastSeries, DatasetSeries, AggregateSeries {
    /**
     * The bare series name, e.g. {@code "node.x.ds_1"}. Stable across renames. For
     * {@link AggregateSeries} this is an internal key, never displayed.
     */
    String baseName();
}
