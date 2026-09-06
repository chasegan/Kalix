package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * The one aggregation orchestration for a selection of series: resolve each ref
 * against the pool, aggregate it, and return an order-preserving map. Both the
 * plot's display pipeline and the stats table are fed from this — the same
 * settings applied the same way, so the two views can never disagree about what
 * "Monthly by Mean" means. (Previously the plot, the stats rebuild and the stats
 * toolbar each carried their own copy of this loop.)
 *
 * <p>Order preservation matters: the first series is the bivariate statistics
 * reference and the legend anchor, so callers pass an ordered collection and get
 * a {@link LinkedHashMap} back. Refs missing from the pool (still loading,
 * removed) are skipped, not errors.
 */
public final class AggregationPipeline {

    private AggregationPipeline() {
    }

    /**
     * Aggregates every resolvable series in {@code orderedSeries} from the pool.
     *
     * @return ordered ref → aggregated data (nameless; identity is the ref)
     */
    public static LinkedHashMap<SeriesRef, TimeSeriesData> aggregate(
            DataSet pool, Collection<SeriesRef> orderedSeries,
            AggregationPeriod period, AggregationMethod method) {
        LinkedHashMap<SeriesRef, TimeSeriesData> aggregated = new LinkedHashMap<>();
        if (pool == null) {
            return aggregated;
        }
        for (SeriesRef ref : orderedSeries) {
            TimeSeriesData original = pool.getSeries(ref);
            if (original == null) {
                continue;
            }
            TimeSeriesData series = TimeSeriesAggregator.aggregate(original, period, method);
            if (series != null) {
                aggregated.put(ref, series);
            }
        }
        return aggregated;
    }
}
