package com.kalix.ide.flowviz.data;

/**
 * Single point of authority for projecting a {@link SeriesRef} to its user-visible
 * label string.
 *
 * <p>Examples of the strings produced:</p>
 * <ul>
 *   <li>{@link RunSeries} → {@code "node.x.ds_1 [Run_3]"} (current run name in brackets)</li>
 *   <li>{@link LastSeries} → {@code "node.x.ds_1 [Last]"}</li>
 *   <li>{@link DatasetSeries} → {@code "flow [mydata.csv]"} (short filename in brackets)</li>
 * </ul>
 *
 * <p>All UI code that needs to display a series — legend, status bar, dialog text,
 * exported file headers — calls this resolver instead of constructing the string
 * itself. Renaming a run is a change in {@code RunInfoImpl} only; the same
 * {@code RunSeries} ref then produces a different label here, and the UI repaint
 * picks it up.</p>
 */
public interface LabelResolver {
    /**
     * Returns the human-visible label for the given {@link SeriesRef}. The string
     * carries no semantic weight beyond display — it must never be used as a key,
     * cache index, or comparison target.
     */
    String labelFor(SeriesRef ref);
}
