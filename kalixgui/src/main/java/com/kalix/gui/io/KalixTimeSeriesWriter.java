package com.kalix.gui.io;

import com.kalix.gui.flowviz.data.TimeSeriesData;
import com.kalix.gui.io.compression.gorilla.GorillaCompressor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Writer for Kalix compressed timeseries file format.
 *
 * Creates a pair of files:
 * - Binary file (.kaz) containing Gorilla-compressed timeseries data
 * - Metadata file (.kai) containing series metadata in CSV format
 *
 * File Format:
 * Binary file: Sequential blocks of [codec_id(2), length(4), compressed_data]
 * Metadata CSV: index,offset,start_time,end_time,timestep,length,series_name
 *
 * Example usage:
 * <pre>
 * KalixTimeSeriesWriter writer = new KalixTimeSeriesWriter();
 * List&lt;TimeSeriesData&gt; series = Arrays.asList(flowData, tempData);
 * writer.writeToFile("/path/to/data", series); // creates data.kaz and data.kai
 * </pre>
 */
public class KalixTimeSeriesWriter {

    private static final int CODEC_GORILLA_DOUBLE = 0;
    private static final int CODEC_GORILLA_FLOAT = 1;

    /**
     * Write timeseries data to files with the given base path using default 32-bit precision.
     * Creates basePath.kaz (binary) and basePath.kai (metadata)
     */
    public void writeToFile(String basePath, List<TimeSeriesData> seriesList) throws IOException {
        writeToFile(basePath, seriesList, false); // Default to 32-bit precision
    }

    /**
     * Write timeseries data to files with the given base path and specified precision.
     * Creates basePath.kaz (binary) and basePath.kai (metadata)
     *
     * @param basePath Base file path (without extension)
     * @param seriesList List of time series data to write
     * @param use64BitPrecision true for 64-bit double precision, false for 32-bit float precision
     */
    public void writeToFile(String basePath, List<TimeSeriesData> seriesList, boolean use64BitPrecision) throws IOException {
        if (seriesList == null || seriesList.isEmpty()) {
            throw new IllegalArgumentException("No series data to write");
        }

        String binaryPath = basePath + ".kaz";
        String metadataPath = basePath + ".kai";

        List<SeriesMetadata> metadataList = new ArrayList<>();

        // Write binary file and collect metadata
        try (FileOutputStream fos = new FileOutputStream(binaryPath)) {
            long currentOffset = 0;

            for (int i = 0; i < seriesList.size(); i++) {
                TimeSeriesData series = seriesList.get(i);
                long offset = currentOffset; // Current byte position

                // Determine codec based on precision preference
                int codec = use64BitPrecision ? CODEC_GORILLA_DOUBLE : CODEC_GORILLA_FLOAT;

                // Detect timestep
                long timestepSeconds = detectTimestep(series);
                GorillaCompressor compressor = new GorillaCompressor(timestepSeconds);

                // Compress data based on codec choice
                byte[] compressed;
                if (codec == CODEC_GORILLA_DOUBLE) {
                    // Convert to Gorilla double format
                    List<GorillaCompressor.TimeValueDouble> gorillaData = convertToGorillaDouble(series);
                    compressed = compressor.compressDouble(gorillaData);
                } else {
                    // Convert to Gorilla float format
                    List<GorillaCompressor.TimeValueFloat> gorillaData = convertToGorillaFloat(series);
                    compressed = compressor.compressFloat(gorillaData);
                }

                // Write block: codec(2) + length(4) + data
                writeUInt16(fos, codec);
                writeUInt32(fos, compressed.length);
                fos.write(compressed);

                // Update current offset
                currentOffset += 2 + 4 + compressed.length;

                // Store metadata
                SeriesMetadata metadata = new SeriesMetadata();
                metadata.index = i + 1; // Base-1 indexing
                metadata.offset = offset;
                metadata.startTime = series.getFirstTimestamp();
                metadata.endTime = series.getLastTimestamp();
                metadata.timestep = timestepSeconds; // Already in seconds
                metadata.length = series.getPointCount();
                metadata.seriesName = series.getName();
                metadataList.add(metadata);
            }
        }

        // Write metadata file
        writeMetadataFile(metadataPath, metadataList);
    }

    private List<GorillaCompressor.TimeValueDouble> convertToGorillaDouble(TimeSeriesData series) {
        List<GorillaCompressor.TimeValueDouble> result = new ArrayList<>();
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();

        for (int i = 0; i < series.getPointCount(); i++) {
            result.add(new GorillaCompressor.TimeValueDouble(timestamps[i], values[i]));
        }

        return result;
    }

    private List<GorillaCompressor.TimeValueFloat> convertToGorillaFloat(TimeSeriesData series) {
        List<GorillaCompressor.TimeValueFloat> result = new ArrayList<>();
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();

        for (int i = 0; i < series.getPointCount(); i++) {
            result.add(new GorillaCompressor.TimeValueFloat(timestamps[i], (float) values[i]));
        }

        return result;
    }

    private long detectTimestep(TimeSeriesData series) {
        if (series.hasRegularInterval()) {
            return series.getIntervalSeconds();
        }

        // For irregular intervals, calculate average interval
        if (series.getPointCount() < 2) {
            return 1; // Default 1 second
        }

        long totalInterval = series.getLastTimestamp() - series.getFirstTimestamp();
        return totalInterval / (series.getPointCount() - 1);
    }

    private void writeMetadataFile(String metadataPath, List<SeriesMetadata> metadataList) throws IOException {
        try (FileWriter writer = new FileWriter(metadataPath, StandardCharsets.UTF_8);
             PrintWriter pw = new PrintWriter(writer)) {

            // Write header
            pw.println("index,offset,start_time,end_time,timestep,length,series_name");

            // Calculate column widths for alignment
            ColumnWidths widths = calculateColumnWidths(metadataList);

            // Write data rows
            for (SeriesMetadata meta : metadataList) {
                String startTime = formatTimestamp(meta.startTime);
                String endTime = formatTimestamp(meta.endTime);

                pw.printf("%-" + widths.index + "s,%-" + widths.offset + "s,%-" + widths.startTime + "s,%-" + widths.endTime + "s,%-" + widths.timestep + "s,%-" + widths.length + "s,%s%n",
                    meta.index,
                    meta.offset,
                    startTime,
                    endTime,
                    meta.timestep,
                    meta.length,
                    meta.seriesName);
            }
        }
    }

    private String formatTimestamp(long timestampSeconds) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochSecond(timestampSeconds), ZoneOffset.UTC);

        // Check if it's a whole day (midnight)
        if (dateTime.getHour() == 0 && dateTime.getMinute() == 0 &&
            dateTime.getSecond() == 0 && dateTime.getNano() == 0) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        }
    }

    private ColumnWidths calculateColumnWidths(List<SeriesMetadata> metadataList) {
        ColumnWidths widths = new ColumnWidths();

        for (SeriesMetadata meta : metadataList) {
            widths.index = Math.max(widths.index, String.valueOf(meta.index).length());
            widths.offset = Math.max(widths.offset, String.valueOf(meta.offset).length());
            widths.startTime = Math.max(widths.startTime, formatTimestamp(meta.startTime).length());
            widths.endTime = Math.max(widths.endTime, formatTimestamp(meta.endTime).length());
            widths.timestep = Math.max(widths.timestep, String.valueOf(meta.timestep).length());
            widths.length = Math.max(widths.length, String.valueOf(meta.length).length());
        }

        // Ensure minimum column widths for header
        widths.index = Math.max(widths.index, "index".length());
        widths.offset = Math.max(widths.offset, "offset".length());
        widths.startTime = Math.max(widths.startTime, "start_time".length());
        widths.endTime = Math.max(widths.endTime, "end_time".length());
        widths.timestep = Math.max(widths.timestep, "timestep".length());
        widths.length = Math.max(widths.length, "length".length());

        return widths;
    }

    private void writeUInt16(OutputStream out, int value) throws IOException {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeUInt32(OutputStream out, long value) throws IOException {
        out.write((int)((value >>> 24) & 0xFF));
        out.write((int)((value >>> 16) & 0xFF));
        out.write((int)((value >>> 8) & 0xFF));
        out.write((int)(value & 0xFF));
    }

    private static class SeriesMetadata {
        int index;
        long offset;
        long startTime;
        long endTime;
        long timestep;
        int length;
        String seriesName;
    }

    private static class ColumnWidths {
        int index = 5;
        int offset = 6;
        int startTime = 10;
        int endTime = 8;
        int timestep = 8;
        int length = 6;
    }
}