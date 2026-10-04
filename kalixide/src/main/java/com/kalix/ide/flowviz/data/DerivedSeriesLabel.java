package com.kalix.ide.flowviz.data;

/**
 * What {@link DefaultLabelResolver} needs to project a {@link DerivedSeries}: the
 * derived series' current name and the source it was summed from. The source's label
 * is projected live, like any other, so a run rename carries through; a derived series
 * does not outlive its source.
 *
 * @param name   user-given name, without the {@code "derived."} prefix
 * @param origin the run, Last alias, or dataset the inputs were summed from
 */
public record DerivedSeriesLabel(String name, SourceRef origin) {
}
