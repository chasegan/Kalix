package com.kalix.ide.flowviz.data;

/**
 * What {@link DefaultLabelResolver} needs to project an {@link AggregateSeries}: the
 * aggregate's current name, the source it was summed from, and when the source has
 * been removed the last known display name.
 *
 * <p>{@code removedOriginLabel} is the one stored label in the series-labelling scheme,
 * and it exists only because the thing it names is gone: while the origin is live it is
 * {@code null} and the label is projected from {@code origin} like any other source, so
 * a run rename still carries through.</p>
 *
 * @param name               user-given name, without the {@code "aggregate."} prefix
 * @param origin             the run, Last alias, or dataset the inputs were summed from
 * @param removedOriginLabel the origin's last display name once removed, else {@code null}
 */
public record AggregateLabel(String name, SourceRef origin, String removedOriginLabel) {
}
