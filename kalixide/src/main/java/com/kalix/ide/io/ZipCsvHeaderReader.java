package com.kalix.ide.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads column headers from zip-compressed CSV files ({@code .csv.zip}): the
 * same first-line parse as {@link CsvHeaderReader}, over the archive's single
 * entry — only the head of the stream is ever decompressed.
 */
public class ZipCsvHeaderReader extends CsvHeaderReader {

    @Override
    public boolean canRead(String fileName) {
        return CsvZipFormat.isCsvZip(fileName);
    }

    @Override
    public List<String> readSeriesNames(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                CsvZipFormat.openSingleEntry(file), StandardCharsets.UTF_8))) {
            return readSeriesNames(reader);
        }
    }
}
