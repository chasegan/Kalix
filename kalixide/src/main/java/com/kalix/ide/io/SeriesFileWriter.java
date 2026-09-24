package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.PlotType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a data set's series as CSV, zipped CSV or Pixie, chosen by the file's extension
 * (the extension is the format, as in every Kalix save dialog). Shared by the plot's
 * "Save Data" and the Run Manager's aggregate save.
 */
public final class SeriesFileWriter {

    private SeriesFileWriter() {
    }

    /** Whether {@code file} names the Pixie format (its {@code .pxt} half). */
    public static boolean isPixie(File file) {
        return file.getName().toLowerCase().endsWith(".pxt");
    }

    /**
     * Writes {@code dataSet} to {@code file}: Pixie for {@code .pxt} (writing the
     * {@code .pxt}/{@code .pxb} pair), otherwise CSV, zipped when the name ends
     * {@code .csv.zip} and given {@code .csv} when it has neither. Series are named by
     * {@code labels}. Returns the file written ({@code .pxt} for Pixie).
     */
    public static File write(DataSet dataSet, File file, PlotType plotType, LabelResolver labels,
                             boolean pixie64BitPrecision) throws IOException {
        if (isPixie(file)) {
            String basePath = file.getAbsolutePath();
            basePath = basePath.substring(0, basePath.length() - ".pxt".length());
            List<NamedSeries> series = new ArrayList<>();
            for (SeriesRef ref : dataSet.getSeriesRefs()) {
                TimeSeriesData data = dataSet.getSeries(ref);
                if (data != null) {
                    series.add(new NamedSeries(labels != null ? labels.labelFor(ref) : String.valueOf(ref), data));
                }
            }
            new PixieWriter().writeToFile(basePath, series, pixie64BitPrecision);
            return new File(basePath + ".pxt");
        }
        String lower = file.getName().toLowerCase();
        if (!lower.endsWith(".csv") && !lower.endsWith(".csv.zip")) {
            file = new File(file.getAbsolutePath() + ".csv");
        }
        TimeSeriesCsvExporter.export(dataSet, file, plotType, labels);
        return file;
    }
}
