package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.compression.gorilla.GorillaCompressor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Reader for Kalix compressed timeseries file format.
 *
 * Reads a pair of files:
 * - Binary file (.kaz) containing Gorilla-compressed timeseries data
 * - Metadata file (.kai) containing series metadata in CSV format
 *
 * Supports both sequential reading of all series and random access to specific series.
 *
 * Example usage:
 * <pre>
 * KalixTimeSeriesReader reader = new KalixTimeSeriesReader();
 * List&lt;TimeSeriesData&gt; allSeries = reader.readAllSeries("/path/to/data");
 * TimeSeriesData specificSeries = reader.readSeries("/path/to/data", "flow_rate");
 * </pre>
 */
public class KalixTimeSeriesReader {

    private static final int CODEC_GORILLA_DOUBLE = 0;
    private static final int CODEC_GORILLA_FLOAT = 1;

    /**
     * Read all timeseries from the file pair
     */
    public List<TimeSeriesData> readAllSeries(String basePath) throws IOException {
        String metadataPath = basePath + ".kai";
        String binaryPath = basePath + ".kaz";

        // Read metadata
        List<SeriesMetadata> metadataList = readMetadataFile(metadataPath);

        // Read all series from binary file
        List<TimeSeriesData> result = new ArrayList<>();
        try (RandomAccessFile binaryFile = new RandomAccessFile(binaryPath, "r")) {
            for (SeriesMetadata meta : metadataList) {
                TimeSeriesData series = readSeriesFromBinary(binaryFile, meta);
                result.add(series);
            }
        }

        return result;
    }

    /**
     * Read a specific timeseries by name
     */
    public TimeSeriesData readSeries(String basePath, String seriesName) throws IOException {
        String metadataPath = basePath + ".kai";
        String binaryPath = basePath + ".kaz";

        // Read metadata to find the series
        List<SeriesMetadata> metadataList = readMetadataFile(metadataPath);
        SeriesMetadata targetMetadata = null;

        for (SeriesMetadata meta : metadataList) {
            if (seriesName.equals(meta.seriesName)) {
                targetMetadata = meta;
                break;
            }
        }

        if (targetMetadata == null) {
            throw new IOException("Series not found: " + seriesName);
        }

        // Read specific series from binary file
        try (RandomAccessFile binaryFile = new RandomAccessFile(binaryPath, "r")) {
            return readSeriesFromBinary(binaryFile, targetMetadata);
        }
    }

    /**
     * Get series names and metadata without reading the binary data
     */
    public List<SeriesInfo> getSeriesInfo(String basePath) throws IOException {
        String metadataPath = basePath + ".kai";
        List<SeriesMetadata> metadataList = readMetadataFile(metadataPath);

        List<SeriesInfo> result = new ArrayList<>();
        for (SeriesMetadata meta : metadataList) {
            SeriesInfo info = new SeriesInfo();
            info.name = meta.seriesName;
            info.pointCount = meta.length;
            info.startTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(meta.startTime), ZoneOffset.UTC);
            info.endTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(meta.endTime), ZoneOffset.UTC);
            info.timestepSeconds = meta.timestep;
            result.add(info);
        }

        return result;
    }

    private List<SeriesMetadata> readMetadataFile(String metadataPath) throws IOException {
        List<SeriesMetadata> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new FileReader(metadataPath, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || !headerLine.startsWith("index,")) {
                throw new IOException("Invalid metadata file format");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                SeriesMetadata meta = parseMetadataLine(line);
                result.add(meta);
            }
        }

        // Sort by index to ensure correct order
        result.sort(Comparator.comparingInt(m -> m.index));

        return result;
    }

    private SeriesMetadata parseMetadataLine(String line) throws IOException {
        // Parse CSV line - handle potential commas in series names
        List<String> fields = parseCsvLine(line);

        if (fields.size() != 7) {
            throw new IOException("Invalid metadata line format: " + line);
        }

        try {
            SeriesMetadata meta = new SeriesMetadata();
            meta.index = Integer.parseInt(fields.get(0).trim());
            meta.offset = Long.parseLong(fields.get(1).trim());
            meta.startTime = parseTimestamp(fields.get(2).trim());
            meta.endTime = parseTimestamp(fields.get(3).trim());
            meta.timestep = Long.parseLong(fields.get(4).trim());
            meta.length = Integer.parseInt(fields.get(5).trim());
            meta.seriesName = fields.get(6).trim();

            return meta;
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IOException("Invalid metadata line format: " + line, e);
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(currentField.toString());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }

        result.add(currentField.toString());
        return result;
    }

    private long parseTimestamp(String timestampStr) throws DateTimeParseException {
        LocalDateTime dateTime;

        if (timestampStr.contains("T")) {
            // Full timestamp with time component
            dateTime = LocalDateTime.parse(timestampStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        } else {
            // Date only (midnight)
            dateTime = LocalDateTime.parse(timestampStr + "T00:00:00", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        }

        return dateTime.toInstant(ZoneOffset.UTC).getEpochSecond();
    }

    private TimeSeriesData readSeriesFromBinary(RandomAccessFile binaryFile, SeriesMetadata meta) throws IOException {
        // Seek to the series block
        binaryFile.seek(meta.offset);

        // Read block header
        int codec = readUInt16(binaryFile);
        long dataLength = readUInt32(binaryFile);

        // Read compressed data
        byte[] compressedData = new byte[(int) dataLength];
        binaryFile.readFully(compressedData);

        // Decompress based on codec
        List<GorillaCompressor.TimeValueDouble> gorillaData;
        long timestepSeconds = meta.timestep; // Already in seconds

        switch (codec) {
            case CODEC_GORILLA_DOUBLE:
                GorillaCompressor compressor = new GorillaCompressor(timestepSeconds);
                gorillaData = compressor.decompressDouble(compressedData);
                break;
            case CODEC_GORILLA_FLOAT:
                // For now, convert float to double
                GorillaCompressor floatCompressor = new GorillaCompressor(timestepSeconds);
                List<GorillaCompressor.TimeValueFloat> floatData = floatCompressor.decompressFloat(compressedData);
                gorillaData = new ArrayList<>();
                for (GorillaCompressor.TimeValueFloat point : floatData) {
                    gorillaData.add(new GorillaCompressor.TimeValueDouble(point.timestamp, point.value));
                }
                break;
            default:
                throw new IOException("Unsupported codec: " + codec);
        }

        // Convert to TimeSeriesData
        return convertToTimeSeriesData(meta.seriesName, gorillaData);
    }

    private TimeSeriesData convertToTimeSeriesData(String name, List<GorillaCompressor.TimeValueDouble> gorillaData) {
        LocalDateTime[] dateTimes = new LocalDateTime[gorillaData.size()];
        double[] values = new double[gorillaData.size()];

        for (int i = 0; i < gorillaData.size(); i++) {
            GorillaCompressor.TimeValueDouble point = gorillaData.get(i);
            dateTimes[i] = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(point.timestamp), ZoneOffset.UTC);
            values[i] = point.value;
        }

        return new TimeSeriesData(name, dateTimes, values);
    }

    private int readUInt16(RandomAccessFile file) throws IOException {
        int b1 = file.readUnsignedByte();
        int b2 = file.readUnsignedByte();
        return (b1 << 8) | b2;
    }

    private long readUInt32(RandomAccessFile file) throws IOException {
        long b1 = file.readUnsignedByte();
        long b2 = file.readUnsignedByte();
        long b3 = file.readUnsignedByte();
        long b4 = file.readUnsignedByte();
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
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

    /**
     * Information about a series without loading the actual data
     */
    public static class SeriesInfo {
        public String name;
        public int pointCount;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public long timestepSeconds;

        @Override
        public String toString() {
            return String.format("SeriesInfo{name='%s', points=%d, start=%s, end=%s, timestep=%ds}",
                name, pointCount, startTime, endTime, timestepSeconds);
        }
    }
}