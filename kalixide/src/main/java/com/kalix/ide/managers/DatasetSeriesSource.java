package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.PixieSeriesKey;

/**
 * Where a loaded dataset series' data comes from, as recorded by
 * {@link DatasetLoaderManager} for the Run Manager.
 *
 * <p>Formats that are parsed whole on load hold their data here; Pixie series hold
 * only a key into {@link com.kalix.ide.io.PixieStore}, which decodes on demand and may
 * reclaim what nothing is using. Holding Pixie data here would pin every series ever
 * plotted for the life of the dataset, so the store's soft cache could never free it.
 * Sealed per ADR-0003 §2.4, so every consumer handles both cases.
 */
public sealed interface DatasetSeriesSource {

    /** Data parsed on load (CSV, zipped CSV, res.csv), held for the dataset's life. */
    record Loaded(TimeSeriesData data) implements DatasetSeriesSource {
    }

    /** A Pixie series, decoded from the shared store when it is first plotted. */
    record Pixie(PixieSeriesKey key) implements DatasetSeriesSource {
    }
}
