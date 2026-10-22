package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One tab's aggregated series, kept between passes of {@link AggregationPipeline} so
 * a series is aggregated again only when its data or the settings change.
 *
 * <p>Each entry remembers the source {@link TimeSeriesData} it was computed from.
 * Series data is immutable and the pool swaps in a new object when data arrives, so
 * an entry is current exactly while the pool still returns that same object: new data
 * is noticed without any invalidation call, even by a page that is hidden. A change of
 * period, method or seasonal mask clears every entry; a pass drops the entries for
 * series it was not asked for, so the cache never holds more than the selection.
 *
 * <p>Not thread-safe: one owner, on the EDT.
 */
public final class AggregatedSeriesCache {

    private record Settings(AggregationPeriod period, AggregationMethod method,
                            SeasonalMaskMode seasonalMaskMode) {
    }

    private record Entry(TimeSeriesData source, TimeSeriesData aggregated) {
    }

    private final Map<SeriesRef, Entry> entries = new HashMap<>();
    private Settings settings;

    /** Starts a pass: entries computed under other settings are dropped. */
    void useSettings(AggregationPeriod period, AggregationMethod method, SeasonalMaskMode seasonalMaskMode) {
        Settings next = new Settings(period, method, seasonalMaskMode);
        if (!next.equals(settings)) {
            entries.clear();
            settings = next;
        }
    }

    /** The cached aggregate of {@code source} for {@code ref}, or null when absent or stale. */
    TimeSeriesData get(SeriesRef ref, TimeSeriesData source) {
        Entry entry = entries.get(ref);
        return entry != null && entry.source() == source ? entry.aggregated() : null;
    }

    void put(SeriesRef ref, TimeSeriesData source, TimeSeriesData aggregated) {
        entries.put(ref, new Entry(Objects.requireNonNull(source), Objects.requireNonNull(aggregated)));
    }

    /** Ends a pass: only the series it produced stay cached. */
    void retainOnly(Set<SeriesRef> refs) {
        entries.keySet().retainAll(refs);
    }

    /** Entries held — package-private, for tests. */
    int size() {
        return entries.size();
    }
}
