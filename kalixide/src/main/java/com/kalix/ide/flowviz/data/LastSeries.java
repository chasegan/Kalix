package com.kalix.ide.flowviz.data;

/**
 * {@link SeriesRef} variant for the "Last" alias — a series taken from whichever
 * simulation run is currently the most recent.
 *
 * <p>{@code LastSeries} carries no run identity of its own. At lookup time the data
 * layer resolves it to the {@link RunSeries} of whatever run is currently latest;
 * when the latest changes, the same {@code LastSeries(baseName)} reference resolves
 * to fresh data automatically.</p>
 *
 * <p>This replaces the historical convention of encoding "Last" as a string suffix
 * (e.g. {@code "node.x.ds_1 [Last]"}). The string projection is now produced only at
 * render time by {@link LabelResolver}.</p>
 */
public record LastSeries(String baseName) implements SeriesRef {
}
