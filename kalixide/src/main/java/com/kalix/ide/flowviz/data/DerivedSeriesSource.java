package com.kalix.ide.flowviz.data;

/**
 * {@link SourceRef} variant for a user-created derived series, identified by the same stable
 * {@code derivedId} as {@link DerivedSeries}. Each derived series is its own source in the
 * data source tree (Derived series &gt; origin &gt; name), offering exactly one series.
 */
public record DerivedSeriesSource(long derivedId) implements SourceRef {
}
