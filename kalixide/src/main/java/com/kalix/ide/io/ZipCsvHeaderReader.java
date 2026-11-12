package com.kalix.ide.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads column headers from compressed CSV files ({@link CompressedCsvFormat}):
 * the same first-line parse as {@link CsvHeaderReader}, over the decompressed
 * stream — only its head is ever decompressed.
 */
public class ZipCsvHeaderReader extends CsvHeaderReader {

    @Override
    public boolean canRead(String fileName) {
        return CompressedCsvFormat.isCompressedCsv(fileName);
    }

    @Override
    public List<String> readSeriesNames(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                CompressedCsvFormat.openDecompressed(file), StandardCharsets.UTF_8))) {
            return readSeriesNames(reader);
        }
    }
}
