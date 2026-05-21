package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;

/**
 * A time series paired with the raw name read from its source file — a CSV column
 * header or a {@code .kai} series-name metadata entry.
 *
 * <p>The {@code name} here is file <em>content</em>, not a series identity. Importers
 * read it from the file; consumers ({@code DatasetLoaderManager}, {@code FlowVizDataManager})
 * construct the actual {@link com.kalix.ide.flowviz.data.SeriesRef} identity from
 * {@code (sourceFile, name)}. This record exists so importers can hand back the column
 * name alongside the (nameless) data without baking identity into
 * {@link TimeSeriesData}.</p>
 */
public record NamedSeries(String name, TimeSeriesData data) {
}
