package com.kalix.ide.flowviz.data;

/**
 * {@link SourceRef} variant for a user-created aggregate, identified by the same stable
 * {@code aggregateId} as {@link AggregateSeries}. Each aggregate is its own source in the
 * data source tree (Aggregate series &gt; origin &gt; name), offering exactly one series.
 */
public record AggregateSource(long aggregateId) implements SourceRef {
}
