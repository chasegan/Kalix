package com.kalix.gui.io;

import com.kalix.gui.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify the timestep fix works with the actual test data file.
 */
class VerifyTimestepFixTest {

    @Test
    void testOutput31d6f6b4Timesteps() throws IOException {
        KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
        String resourcePath = "src/test/resources/output_31d6f6b4";

        // Load series info to check timesteps
        List<KalixTimeSeriesReader.SeriesInfo> seriesInfo = reader.getSeriesInfo(resourcePath);

        assertEquals(2, seriesInfo.size());

        // Temperature series should have 3600 second timestep (hourly)
        KalixTimeSeriesReader.SeriesInfo tempInfo = seriesInfo.get(0);
        assertEquals("temperature", tempInfo.name);
        assertEquals(3600, tempInfo.timestepSeconds, "Temperature series should have 3600 second timestep");

        // Flow rate series should have 1800 second timestep (30 minutes)
        KalixTimeSeriesReader.SeriesInfo flowInfo = seriesInfo.get(1);
        assertEquals("flow_rate", flowInfo.name);
        assertEquals(1800, flowInfo.timestepSeconds, "Flow rate series should have 1800 second timestep");

        System.out.println("Verified timesteps in output_31d6f6b4.kai:");
        for (KalixTimeSeriesReader.SeriesInfo info : seriesInfo) {
            System.out.println("  " + info.name + ": " + info.timestepSeconds + " seconds");
        }
    }

    @Test
    void testLoadActualData() throws IOException {
        KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
        String resourcePath = "src/test/resources/output_31d6f6b4";

        // Load all series
        List<TimeSeriesData> loadedSeries = reader.readAllSeries(resourcePath);

        assertNotNull(loadedSeries);
        assertEquals(2, loadedSeries.size());

        // Verify data integrity
        TimeSeriesData tempSeries = loadedSeries.get(0);
        TimeSeriesData flowSeries = loadedSeries.get(1);

        assertEquals("temperature", tempSeries.getName());
        assertEquals("flow_rate", flowSeries.getName());

        assertTrue(tempSeries.getPointCount() > 0, "Temperature series should have data points");
        assertTrue(flowSeries.getPointCount() > 0, "Flow rate series should have data points");

        System.out.println("Successfully loaded data from output_31d6f6b4.kai:");
        System.out.println("  " + tempSeries.getName() + ": " + tempSeries.getPointCount() + " points");
        System.out.println("  " + flowSeries.getName() + ": " + flowSeries.getPointCount() + " points");
    }
}