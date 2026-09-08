package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixieRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadedSeriesNestsByDottedName() throws Exception {
        LocalDateTime[] times = {
            LocalDateTime.of(2020, 1, 1, 0, 0),
            LocalDateTime.of(2020, 1, 2, 0, 0)
        };
        TimeSeriesData data = new TimeSeriesData(times, new double[] { 1.0, 2.0 });

        String base = tempDir.resolve("run_out").toString();
        new PixieWriter().writeToFile(base,
            List.of(new NamedSeries("node.x.dsflow", data)), true);

        List<NamedSeries> read = new PixieReader().readAllSeries(base);
        assertEquals(1, read.size());
        // Name stays whole for the legend; path nests like the in-memory run (matching CSV).
        assertEquals("node.x.dsflow", read.get(0).name());
        assertEquals(List.of("node", "x", "dsflow"), read.get(0).path());
    }

    /**
     * Timestamps and values must round-trip exactly. The Pixie convention is epoch
     * SECONDS (established by the Rust engine, pixie_io.rs) while TimeSeriesData is
     * epoch MILLISECONDS; historically the writer fed milliseconds into the
     * seconds-convention stream, so daily 2020 data reloaded as dates in year +51969.
     */
    @Test
    void timestampsAndValuesRoundTripExactly() throws Exception {
        LocalDateTime[] times = new LocalDateTime[365];
        double[] values = new double[365];
        for (int i = 0; i < times.length; i++) {
            times[i] = LocalDateTime.of(2020, 1, 1, 0, 0).plusDays(i);
            values[i] = 100.0 + 10.0 * Math.sin(i * 0.1);
        }
        TimeSeriesData data = new TimeSeriesData(times, values);

        String base = tempDir.resolve("daily").toString();
        new PixieWriter().writeToFile(base, List.of(new NamedSeries("node.x.dsflow", data)), true);

        TimeSeriesData reloaded = new PixieReader().readAllSeries(base).get(0).data();
        assertArrayEquals(data.getTimestamps(), reloaded.getTimestamps(),
            "epoch-millisecond timestamps must survive the s<->ms boundary");
        assertArrayEquals(data.getValues(), reloaded.getValues(),
            "values must round-trip bit-exactly");

        // The human-readable .pxt metadata must carry real dates and a seconds timestep.
        String pxt = Files.readString(Path.of(base + ".pxt"));
        assertTrue(pxt.contains("2020-01-01"), "start_time should be a 2020 date, got:\n" + pxt);
        assertTrue(pxt.contains("2020-12-30"), "end_time should be a 2020 date, got:\n" + pxt);
        String dataRow = pxt.lines().skip(1).findFirst().orElseThrow();
        assertEquals("86400", dataRow.split(",")[4].trim(),
            "timestep column must be 86400 seconds (daily), got row: " + dataRow);
    }

    /** Irregular (non-constant-interval) series must also round-trip exactly. */
    @Test
    void irregularSeriesRoundTripsExactly() throws Exception {
        LocalDateTime base = LocalDateTime.of(2021, 6, 1, 0, 0);
        LocalDateTime[] times = {
            base, base.plusDays(1), base.plusDays(2), base.plusDays(40), base.plusDays(41)
        };
        TimeSeriesData data = new TimeSeriesData(times, new double[] { 1.0, 2.5, 2.5, -3.75, 0.0 });

        String path = tempDir.resolve("irregular").toString();
        new PixieWriter().writeToFile(path, List.of(new NamedSeries("gauge.q", data)), true);

        TimeSeriesData reloaded = new PixieReader().readAllSeries(path).get(0).data();
        assertArrayEquals(data.getTimestamps(), reloaded.getTimestamps());
        assertArrayEquals(data.getValues(), reloaded.getValues());
    }

    /**
     * Long gauge records start well before 1970, where epoch seconds are negative. The
     * stream carries signed seconds, so this must be nothing special - but every
     * ms-to-s conversion in the writer has to floor rather than truncate for it to be.
     */
    @Test
    void pre1970TimestampsRoundTripExactly() throws Exception {
        LocalDateTime[] times = new LocalDateTime[400];
        double[] values = new double[400];
        for (int i = 0; i < times.length; i++) {
            times[i] = LocalDateTime.of(1889, 1, 1, 0, 0).plusDays(i);
            values[i] = i;
        }
        TimeSeriesData data = new TimeSeriesData(times, values);
        assertTrue(data.getFirstTimestamp() < 0, "fixture sanity: negative epoch millis");

        String base = tempDir.resolve("old").toString();
        new PixieWriter().writeToFile(base, List.of(new NamedSeries("gauge.flow", data)), true);

        List<NamedSeries> read = new PixieReader().readAllSeries(base);
        TimeSeriesData reloaded = read.get(0).data();
        assertArrayEquals(data.getTimestamps(), reloaded.getTimestamps(), "1889 dates reload exactly");
        assertArrayEquals(data.getValues(), reloaded.getValues(), 0.0);

        // The .pxt metadata goes through the same conversion.
        PixieReader.SeriesInfo info = new PixieReader().getSeriesInfo(base).get(0);
        assertEquals(LocalDateTime.of(1889, 1, 1, 0, 0), info.startTime);
        assertEquals(LocalDateTime.of(1890, 2, 4, 0, 0), info.endTime);
    }

    /**
     * Sub-second instants before 1970 must floor to the earlier second. Truncating toward
     * zero rounds them towards 1970 instead, and here would land two distinct points on
     * the same second. (Pixie is second-resolution, so losing the fraction is expected;
     * losing a point is not.)
     */
    @Test
    void pre1970SubSecondInstantsFloorRatherThanTruncate() throws Exception {
        long[] millis = {-1_500L, -500L, 500L};
        TimeSeriesData data = new TimeSeriesData(millis, new double[] {1, 2, 3});

        String base = tempDir.resolve("subsec").toString();
        new PixieWriter().writeToFile(base, List.of(new NamedSeries("s", data)), true);

        TimeSeriesData reloaded = new PixieReader().readAllSeries(base).get(0).data();
        assertArrayEquals(new long[] {-2_000L, -1_000L, 0L}, reloaded.getTimestamps(),
            "each instant floors to its own second; truncation would give -1, 0, 0");
    }
}
