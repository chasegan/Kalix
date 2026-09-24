package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesFileWriterTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @TempDir
    Path dir;

    private static DataSet oneSeries() {
        DataSet data = new DataSet();
        data.addSeries(new DatasetSeries("/data/flows.csv", "flow"),
            new TimeSeriesData(new long[]{0, DAY_MS}, new double[]{1.5, 2.5}));
        return data;
    }

    @Test
    void csvWithoutExtensionGainsOne() throws IOException {
        File written = SeriesFileWriter.write(oneSeries(), dir.resolve("out").toFile(), null, null, true);
        assertEquals("out.csv", written.getName());
        assertTrue(Files.readString(written.toPath()).contains("1.5"));
    }

    @Test
    void zippedCsvKeepsItsExtension() throws IOException {
        File written = SeriesFileWriter.write(oneSeries(), dir.resolve("out.csv.zip").toFile(), null, null, true);
        assertEquals("out.csv.zip", written.getName());
        assertTrue(written.isFile());
    }

    @Test
    void pixieWritesThePair() throws IOException {
        File written = SeriesFileWriter.write(oneSeries(), dir.resolve("out.pxt").toFile(), null, null, true);
        assertEquals("out.pxt", written.getName());
        assertTrue(written.isFile());
        assertTrue(dir.resolve("out.pxb").toFile().isFile());
    }
}
