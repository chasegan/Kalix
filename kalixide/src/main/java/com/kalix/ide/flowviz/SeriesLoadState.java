package com.kalix.ide.flowviz;

/**
 * Where a series' fetch stands while the pool has no current data for it: still loading,
 * or failed. Fetching is per series, not per tab, so the tab manager keeps one map of
 * these for every tab; a series whose data has arrived has no entry.
 */
public sealed interface SeriesLoadState {

    /** The series is being fetched. */
    record Loading() implements SeriesLoadState {
    }

    /** The fetch failed. */
    record Failed(String message) implements SeriesLoadState {
    }

    SeriesLoadState LOADING = new Loading();
}
