package com.kalix.ide.io;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads column headers from gzip-compressed CSV files ({@code .csv.gz}): the
 * same first-line parse as {@link CsvHeaderReader}, behind a streaming gzip
 * decode — only the head of the stream is ever decompressed.
 */
public class GzipCsvHeaderReader extends CsvHeaderReader {

    @Override
    public boolean canRead(String fileName) {
        return CsvGzFormat.isCsvGz(fileName);
    }

    @Override
    public List<String> readSeriesNames(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file.toPath()))),
                StandardCharsets.UTF_8))) {
            return readSeriesNames(reader);
        }
    }
}
