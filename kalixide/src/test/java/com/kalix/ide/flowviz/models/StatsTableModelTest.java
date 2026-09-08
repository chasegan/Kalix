package com.kalix.ide.flowviz.models;

import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.MaskMode;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stats model is a projection of series it is HANDED: they arrive already
 * aggregated and, where a seasonal mask is active, already masked. It must not
 * filter them again.
 *
 * <p>This is a negative contract with real history behind it (#235): the model used
 * to apply the seasonal mask itself, which meant masking points that aggregation had
 * already stamped at their period's start. An Annual (Jan-Dec) point carries a
 * 1 January timestamp, so deselecting January dropped every row and the table came
 * back empty.</p>
 */
class StatsTableModelTest {

    private static final SeriesRef REF = new DatasetSeries("/t.csv", "flow");

    private static long jan1(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Three annual aggregates, each stamped at its period start, as the pipeline emits them. */
    private static LinkedHashMap<SeriesRef, TimeSeriesData> annualAggregates() {
        LinkedHashMap<SeriesRef, TimeSeriesData> series = new LinkedHashMap<>();
        series.put(REF, new TimeSeriesData(
            new long[] {jan1(2020), jan1(2021), jan1(2022)},
            new double[] {10, 20, 30}));
        return series;
    }

    private static String statValue(StatsTableModel model, String statisticName) {
        for (int column = 0; column < model.getColumnCount(); column++) {
            if (statisticName.equalsIgnoreCase(model.getColumnName(column))) {
                return String.valueOf(model.getValueAt(0, column));
            }
        }
        throw new AssertionError("no such statistic column: " + statisticName);
    }

    @Test
    void januaryStampedAggregatesAreNotFilteredOut() {
        StatsTableModel model = new StatsTableModel();
        model.setMaskMode(MaskMode.NONE);
        model.setSeries(annualAggregates());

        assertEquals(1, model.getRowCount(),
            "the series must produce a row - re-masking these January-stamped points emptied it");
        assertTrue(statValue(model, "Points").contains("3"),
            "all three annual points count; none were masked away");
    }

    @Test
    void statisticsCoverEveryPointHandedToTheModel() {
        StatsTableModel model = new StatsTableModel();
        model.setMaskMode(MaskMode.NONE);
        model.setSeries(annualAggregates());

        assertEquals(20.0, Double.parseDouble(statValue(model, "Mean")), 1e-9,
            "mean of 10/20/30 - computed over the points as given, unfiltered");
    }

    @Test
    void theIncrementalPathAgreesWithTheBatchPath() {
        StatsTableModel batch = new StatsTableModel();
        batch.setMaskMode(MaskMode.NONE);
        batch.setSeries(annualAggregates());

        StatsTableModel incremental = new StatsTableModel();
        incremental.setMaskMode(MaskMode.NONE);
        incremental.addOrUpdateSeries(REF, annualAggregates().get(REF));

        assertEquals(batch.getRowCount(), incremental.getRowCount());
        for (int column = 0; column < batch.getColumnCount(); column++) {
            assertEquals(String.valueOf(batch.getValueAt(0, column)),
                String.valueOf(incremental.getValueAt(0, column)),
                "addOrUpdateSeries used to mask where setSeries did too - both must now pass through");
        }
    }
}
