package com.kalix.ide.flowviz.models;

import com.kalix.ide.flowviz.SeriesLoadState;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.stats.MaskMode;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final SeriesRef OTHER = new DatasetSeries("/t.csv", "other");

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
        model.project(annualAggregates(), List.of(REF), MaskMode.NONE, Map.of());

        assertEquals(1, model.getRowCount(),
            "the series must produce a row - re-masking these January-stamped points emptied it");
        assertTrue(statValue(model, "Points").contains("3"),
            "all three annual points count; none were masked away");
    }

    @Test
    void statisticsCoverEveryPointHandedToTheModel() {
        StatsTableModel model = new StatsTableModel();
        model.project(annualAggregates(), List.of(REF), MaskMode.NONE, Map.of());

        assertEquals(20.0, Double.parseDouble(statValue(model, "Mean")), 1e-9,
            "mean of 10/20/30 - computed over the points as given, unfiltered");
    }

    @Test
    void rowsFollowTheSelectionWithPlaceholdersForSeriesStillFetching() {
        StatsTableModel model = new StatsTableModel();
        model.project(annualAggregates(), List.of(OTHER, REF), MaskMode.NONE,
            Map.of(OTHER, SeriesLoadState.LOADING));

        assertEquals(2, model.getRowCount());
        assertEquals("", String.valueOf(model.getValueAt(0, 2)), "the loading row is blank");
        assertTrue(statValue(model, "Points").isEmpty(), "row 0 is the placeholder");
    }

    @Test
    void dataInThePoolIsShownEvenWhileMarkedLoading() {
        StatsTableModel model = new StatsTableModel();
        model.project(annualAggregates(), List.of(REF), MaskMode.NONE, Map.of(REF, SeriesLoadState.LOADING));

        assertTrue(statValue(model, "Points").contains("3"),
            "a fetch that ended without clearing its state must not hide the data");
    }

    @Test
    void aFailedFetchShowsErrorInEveryColumnEvenOverOldData() {
        StatsTableModel model = new StatsTableModel();
        model.project(annualAggregates(), List.of(REF), MaskMode.NONE,
            Map.of(REF, new SeriesLoadState.Failed("gone")));

        assertEquals(1, model.getRowCount());
        for (int column = 2; column < model.getColumnCount(); column++) {
            assertEquals("Error", model.getValueAt(0, column));
        }
    }

    @Test
    void aSelectedSeriesWithNeitherDataNorALoadStateHasNoRow() {
        StatsTableModel model = new StatsTableModel();
        model.project(annualAggregates(), List.of(OTHER, REF), MaskMode.NONE, Map.of());

        assertEquals(1, model.getRowCount(), "OTHER has no data and isn't loading: no row");
        assertEquals(MaskMode.NONE, model.getMaskMode());
    }
}
