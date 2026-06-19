package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.util.List;

/**
 * A time series paired with the raw name read from its source file — a CSV column
 * header, a {@code .pxt} series-name metadata entry, or a {@code .res.csv} {@code Name}
 * attribute — plus the series' natural hierarchy {@code path} within that file.
 *
 * <p>The {@code name} here is file <em>content</em>, not a series identity. Importers
 * read it from the file; consumers ({@code DatasetLoaderManager}, {@code FlowVizDataManager})
 * construct the actual {@link com.kalix.ide.flowviz.data.SeriesRef} identity from
 * {@code (sourceFile, name)}. This record exists so importers can hand back the column
 * name alongside the (nameless) data without baking identity into
 * {@link TimeSeriesData}.</p>
 *
 * <p>{@code path} is the ordered list of <em>raw, un-sanitised</em> hierarchy segments the
 * series occupies within its file — the one piece of structure only the importer can know
 * (a flat CSV column is one segment; a Source result series decomposes into several). The
 * consumer turns these into a dot-delimited tree key via a single generic step (sanitise
 * each segment, join with {@code .}); see {@code DatasetLoaderManager.composeDatasetSeriesName}.
 * Formats with no inherent hierarchy default {@code path} to {@code [name]}, reproducing the
 * original flat behaviour.</p>
 */
public record NamedSeries(String name, List<String> path, TimeSeriesData data) {

    /**
     * Convenience for formats with no inherent hierarchy: the path is a single segment
     * equal to {@code name}.
     */
    public NamedSeries(String name, TimeSeriesData data) {
        this(name, List.of(name), data);
    }
}
