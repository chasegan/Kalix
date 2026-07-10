package com.kalix.ide.flowviz.data;

/**
 * Stable internal identity for a <em>data source</em> in the Run Manager — a run, the
 * "Last" alias, or a loaded dataset file. The source-level counterpart of
 * {@link SeriesRef}: where a {@code SeriesRef} identifies one time-series, a
 * {@code SourceRef} identifies the thing that produced a whole family of them.
 *
 * <p>Used to remember, per visualization tab, which sources are checked in the data
 * source tree — so switching tabs can restore the tab's full context (sources <em>and</em>
 * series). Stored as typed refs rather than {@code TreePath}s for the same reason
 * {@code SeriesRef} exists: tree paths die on rebuilds and node replacement, while
 * {@code runId} / dataset path survive renames and refreshes.</p>
 *
 * <p>Sealed so all variants are known and pattern-matchable.</p>
 */
public sealed interface SourceRef permits RunSource, LastSource, DatasetSource {
}
