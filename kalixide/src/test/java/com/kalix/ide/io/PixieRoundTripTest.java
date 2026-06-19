package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
