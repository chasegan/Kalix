package com.kalix.ide.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads series names from Kalix .kai metadata files.
 * The .kai format is a CSV with columns:
 * index, offset, start_time, end_time, timestep, length, seriesName
 */
public class KaiHeaderReader implements DataSourceHeaderReader {

    private static final int SERIES_NAME_COLUMN = 6;

    @Override
    public boolean canRead(String fileName) {
        return fileName.toLowerCase().endsWith(".kai");
    }

    @Override
    public List<String> readSeriesNames(File file) throws IOException {
        List<String> names = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file, StandardCharsets.UTF_8))) {
            // Skip the header line ("index,offset,start_time,...")
            String header = reader.readLine();
            if (header == null) {
                return names;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] fields = line.split(",", -1);
                if (fields.length > SERIES_NAME_COLUMN) {
                    String cleansed = DataSourceHeaderReader.cleanseName(fields[SERIES_NAME_COLUMN]);
                    if (!cleansed.isEmpty()) {
                        names.add(cleansed);
                    }
                }
            }
        }

        return names;
    }
}
