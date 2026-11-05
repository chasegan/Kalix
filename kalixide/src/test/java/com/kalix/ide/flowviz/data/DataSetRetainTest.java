package com.kalix.ide.flowviz.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link DataSet#retainSeries}: the pool keeps exactly what it is asked to, resolving
 * "Last" to the run it is stored under.
 */
class DataSetRetainTest {

    private static final DatasetSeries A = new DatasetSeries("/x.csv", "a");
    private static final DatasetSeries B = new DatasetSeries("/x.csv", "b");

    private static TimeSeriesData series(double... values) {
        long[] times = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = i * 86_400_000L;
        }
        return new TimeSeriesData(times, values);
    }

    @Test
    void keepsWhatIsWantedAndRemovesTheRest() {
        DataSet pool = new DataSet();
        pool.addSeries(A, series(1, 2));
        pool.addSeries(B, series(100, 200));

        pool.retainSeries(Set.of(A));

        assertTrue(pool.hasSeries(A));
        assertFalse(pool.hasSeries(B));
        assertEquals(2.0, pool.getGlobalMaxValue(), 1e-12, "bounds no longer include b");
    }

    @Test
    void aLastSelectionKeepsTheRunItIsStoredUnder() {
        RunSeries run = new RunSeries(7, "flow");
        DataSet pool = new DataSet();
        pool.setLastSeriesResolver(last -> new RunSeries(7, last.baseName()));
        pool.addSeries(new LastSeries("flow"), series(1));
        pool.addSeries(A, series(2));

        pool.retainSeries(Set.of(new LastSeries("flow")));

        assertTrue(pool.hasSeries(run), "stored under the run, kept through the alias");
        assertFalse(pool.hasSeries(A));
    }

    @Test
    void eachRemovalIsNotified() {
        DataSet pool = new DataSet();
        pool.addSeries(A, series(1));
        pool.addSeries(B, series(2));
        List<SeriesRef> removed = new ArrayList<>();
        pool.addListener(new DataSet.DataSetListener() {
            @Override
            public void onSeriesAdded(SeriesRef ref, TimeSeriesData data) {
            }

            @Override
            public void onSeriesRemoved(SeriesRef ref) {
                removed.add(ref);
            }

            @Override
            public void onDataChanged() {
            }
        });

        pool.retainSeries(Set.of());

        assertEquals(List.of(A, B), removed);
        assertTrue(pool.isEmpty());
    }
}
