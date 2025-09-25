package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify the timestep fix works with the three_rex_rain test data files.
 */
class VerifyTimestepFixTest {

    @Test
    void testThreeRexRainTimesteps() throws IOException {
        KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
        String resourcePath = "src/test/resources/three_rex_rain";

        // Load series info to check timesteps
        List<KalixTimeSeriesReader.SeriesInfo> seriesInfo = reader.getSeriesInfo(resourcePath);

        assertEquals(3, seriesInfo.size());

        // All series should have 86400 second timestep (daily)
        for (int i = 0; i < seriesInfo.size(); i++) {
            KalixTimeSeriesReader.SeriesInfo info = seriesInfo.get(i);
            assertEquals(86400, info.timestepSeconds, "Series " + (i+1) + " should have 86400 second timestep (daily)");
            assertTrue(info.name.contains("rex_rain"), "Series name should contain 'rex_rain'");
        }

        System.out.println("Verified timesteps in three_rex_rain.kai:");
        for (KalixTimeSeriesReader.SeriesInfo info : seriesInfo) {
            System.out.println("  " + info.name + ": " + info.timestepSeconds + " seconds");
        }
    }

    @Test
    void testLoadActualData() throws IOException {
        KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
        String resourcePath = "src/test/resources/three_rex_rain";

        // Load all series
        List<TimeSeriesData> loadedSeries = reader.readAllSeries(resourcePath);

        assertNotNull(loadedSeries);
        assertEquals(3, loadedSeries.size());

        // Verify data integrity for all three series
        for (int i = 0; i < loadedSeries.size(); i++) {
            TimeSeriesData series = loadedSeries.get(i);
            assertTrue(series.getName().contains("rex_rain"), "Series " + (i+1) + " name should contain 'rex_rain'");
            assertTrue(series.getPointCount() > 0, "Series " + (i+1) + " should have data points");
        }

        System.out.println("Successfully loaded data from three_rex_rain.kai:");
        for (TimeSeriesData series : loadedSeries) {
            System.out.println("  " + series.getName() + ": " + series.getPointCount() + " points");
        }
    }
}