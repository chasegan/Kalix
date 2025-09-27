package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify the timestep bug fix for daily data.
 * Daily data should have timestep = 86400 seconds (24 hours * 60 minutes * 60 seconds).
 */
class TimestepBugTest {

    @TempDir
    Path tempDir;

    @Test
    void testDailyDataTimestep() throws IOException {
        // Create daily data (24-hour intervals)
        LocalDateTime[] dailyTimes = {
            LocalDateTime.of(2023, 1, 1, 0, 0, 0),
            LocalDateTime.of(2023, 1, 2, 0, 0, 0),
            LocalDateTime.of(2023, 1, 3, 0, 0, 0),
            LocalDateTime.of(2023, 1, 4, 0, 0, 0)
        };
        double[] dailyValues = {10.0, 15.0, 12.0, 18.0};
        TimeSeriesData dailySeries = new TimeSeriesData("daily_temperature", dailyTimes, dailyValues);

        // Verify the interval detection
        assertTrue(dailySeries.hasRegularInterval(), "Daily data should be detected as regular interval");
        assertEquals(86400 * 1000, dailySeries.getIntervalMillis(), "Daily interval should be 86400 seconds (86400000 ms)");

        // Write to file
        String basePath = tempDir.resolve("daily_test").toString();
        KalixTimeSeriesWriter writer = new KalixTimeSeriesWriter();
        writer.writeToFile(basePath, List.of(dailySeries), false);

        // Read back the metadata to check timestep
        KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
        List<KalixTimeSeriesReader.SeriesInfo> seriesInfo = reader.getSeriesInfo(basePath);

        assertEquals(1, seriesInfo.size());
        KalixTimeSeriesReader.SeriesInfo info = seriesInfo.get(0);
        assertEquals("daily_temperature", info.name);
        assertEquals(86400, info.timestepSeconds, "Timestep should be 86400 seconds for daily data, not 86");

        System.out.println("Daily data timestep test:");
        System.out.println("  Series: " + info.name);
        System.out.println("  Points: " + info.pointCount);
        System.out.println("  Timestep: " + info.timestepSeconds + " seconds");
        System.out.println("  Expected: 86400 seconds (24 hours)");
    }

    @Test
    void testHourlyDataTimestep() throws IOException {
        // Create hourly data (1-hour intervals)
        LocalDateTime[] hourlyTimes = {
            LocalDateTime.of(2023, 1, 1, 0, 0, 0),
            LocalDateTime.of(2023, 1, 1, 1, 0, 0),
            LocalDateTime.of(2023, 1, 1, 2, 0, 0),
            LocalDateTime.of(2023, 1, 1, 3, 0, 0)
        };
        double[] hourlyValues = {20.0, 22.0, 19.0, 21.0};
        TimeSeriesData hourlySeries = new TimeSeriesData("hourly_temperature", hourlyTimes, hourlyValues);

        // Verify the interval detection
        assertTrue(hourlySeries.hasRegularInterval(), "Hourly data should be detected as regular interval");
        assertEquals(3600 * 1000, hourlySeries.getIntervalMillis(), "Hourly interval should be 3600 seconds (3600000 ms)");

        // Write to file
        String basePath = tempDir.resolve("hourly_test").toString();
        KalixTimeSeriesWriter writer = new KalixTimeSeriesWriter();
        writer.writeToFile(basePath, List.of(hourlySeries), false);

        // Read back the metadata to check timestep
        KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
        List<KalixTimeSeriesReader.SeriesInfo> seriesInfo = reader.getSeriesInfo(basePath);

        assertEquals(1, seriesInfo.size());
        KalixTimeSeriesReader.SeriesInfo info = seriesInfo.get(0);
        assertEquals("hourly_temperature", info.name);
        assertEquals(3600, info.timestepSeconds, "Timestep should be 3600 seconds for hourly data");

        System.out.println("Hourly data timestep test:");
        System.out.println("  Series: " + info.name);
        System.out.println("  Points: " + info.pointCount);
        System.out.println("  Timestep: " + info.timestepSeconds + " seconds");
        System.out.println("  Expected: 3600 seconds (1 hour)");
    }
}