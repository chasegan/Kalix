package com.kalix.ide.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads column headers from CSV files to extract series names.
 * Only reads the first line of the file, auto-detecting the delimiter.
 */
public class CsvHeaderReader implements DataSourceHeaderReader {

    @Override
    public boolean canRead(String fileName) {
        return fileName.toLowerCase().endsWith(".csv");
    }

    @Override
    public List<String> readSeriesNames(File file) throws IOException {
        List<String> names = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return names;
            }

            char delimiter = detectDelimiter(headerLine);
            String[] columns = headerLine.split(String.valueOf(delimiter == '\t' ? "\\t" : "\\" + delimiter), -1);

            // Skip the first column (date/timestamp) and cleanse the rest
            for (int i = 1; i < columns.length; i++) {
                String cleansed = DataSourceHeaderReader.cleanseName(columns[i]);
                if (!cleansed.isEmpty()) {
                    names.add(cleansed);
                }
            }
        }

        return names;
    }

    /**
     * Detects the delimiter from a header line by counting candidate characters.
     * Same approach as {@link TimeSeriesCsvImporter}.
     */
    private char detectDelimiter(String headerLine) {
        char[] candidates = {',', ';', '\t', '|'};
        int maxCount = 0;
        char best = ',';

        for (char c : candidates) {
            int count = 0;
            for (int i = 0; i < headerLine.length(); i++) {
                if (headerLine.charAt(i) == c) {
                    count++;
                }
            }
            if (count > maxCount) {
                maxCount = count;
                best = c;
            }
        }

        return best;
    }
}
