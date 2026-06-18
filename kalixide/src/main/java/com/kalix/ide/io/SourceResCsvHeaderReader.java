package com.kalix.ide.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads series names from a Source result CSV ({@code .res.csv}) file's extended header,
 * without loading the (large) data region. Each name is the series' {@code Name} attribute,
 * cleansed for use as a data reference like the other {@link DataSourceHeaderReader}s.
 */
public class SourceResCsvHeaderReader implements DataSourceHeaderReader {

    @Override
    public boolean canRead(String fileName) {
        return SourceResCsvFormat.isResCsv(fileName);
    }

    @Override
    public List<String> readSeriesNames(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new FileReader(file, StandardCharsets.UTF_8))) {
            SourceResCsvHeader header = SourceResCsvHeader.parse(reader);

            List<String> names = new ArrayList<>();
            for (String rawName : header.getSeriesNames()) {
                String cleansed = DataSourceHeaderReader.cleanseName(rawName);
                if (!cleansed.isEmpty()) {
                    names.add(cleansed);
                }
            }
            return names;
        }
    }
}
