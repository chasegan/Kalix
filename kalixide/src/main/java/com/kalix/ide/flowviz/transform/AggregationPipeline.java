package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * The one aggregation orchestration for a selection of series: resolve each ref
 * against the pool, aggregate it (applying the seasonal mask as it goes), and return an
 * order-preserving map. Both the plot's display pipeline and the stats table
 * are fed from this — the same settings applied the same way, so the two views
 * can never disagree about what "Monthly by Mean" means.
 * (Previously the plot, the stats rebuild and the stats toolbar each carried
 *  their own copy of this loop.)
 *
 * <p>Order preservation matters: the first series is the bivariate statistics
 * reference and the legend anchor, so callers pass an ordered collection and get
 * a {@link LinkedHashMap} back. Refs missing from the pool (still loading,
 * removed) are skipped, not errors.
 *
 * <p>The seasonal mask is threaded through to {@link TimeSeriesAggregator}, which is
 * where it is actually applied - inside the accumulation, the last point at which
 * individual calendar months are still distinguishable (#235). This class only carries
 * the setting, so a caller that reaches the aggregator directly is masked just the same;
 * do not move the mask up to here.</p>
 */
public final class AggregationPipeline {

    private AggregationPipeline() {
    }

    /**
     * Aggregates every resolvable series in {@code orderedSeries} from the pool.
     *
     * @param seasonalMaskMode is passed so the aggregator is aware of which NaNs are
     *                        missing data and which are seasonally masked.
     * @return ordered ref → aggregated data (nameless; identity is the ref)
     */
    public static LinkedHashMap<SeriesRef, TimeSeriesData> aggregate(
            DataSet pool, Collection<SeriesRef> orderedSeries,
            AggregationPeriod period, AggregationMethod method,
            SeasonalMaskMode seasonalMaskMode
    ) {
        LinkedHashMap<SeriesRef, TimeSeriesData> aggregated = new LinkedHashMap<>();
        if (pool == null) {
            return aggregated;
        }
        for (SeriesRef ref : orderedSeries) {
            TimeSeriesData original = pool.getSeries(ref);
            if (original == null) {
                continue;
            }
            TimeSeriesData series = TimeSeriesAggregator.aggregate(original, period, method, seasonalMaskMode);
            if (series != null) {
                aggregated.put(ref, series);
            }
        }
        return aggregated;
    }
}
