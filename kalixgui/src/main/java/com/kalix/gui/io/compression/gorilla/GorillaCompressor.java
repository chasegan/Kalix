package com.kalix.gui.io.compression.gorilla;

import java.io.*;
import java.util.*;
import java.util.Base64;

/**
 * Gorilla compression algorithm for timeseries data.
 * Compatible with the Rust implementation - uses identical compression scheme.
 */
public class GorillaCompressor {
    private final long timestep;

    public GorillaCompressor(long timestep) {
        this.timestep = timestep;
    }

    /**
     * Represents a timestamp-value pair for double values
     */
    public static class TimeValueDouble {
        public final long timestamp;
        public final double value;

        public TimeValueDouble(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TimeValueDouble that = (TimeValueDouble) obj;
            return timestamp == that.timestamp &&
                    Double.doubleToLongBits(value) == Double.doubleToLongBits(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestamp, value);
        }

        @Override
        public String toString() {
            return String.format("(%d, %f)", timestamp, value);
        }
    }

    /**
     * Represents a timestamp-value pair for float values
     */
    public static class TimeValueFloat {
        public final long timestamp;
        public final float value;

        public TimeValueFloat(long timestamp, float value) {
            this.timestamp = timestamp;
            this.value = value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TimeValueFloat that = (TimeValueFloat) obj;
            return timestamp == that.timestamp &&
                    Float.floatToIntBits(value) == Float.floatToIntBits(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestamp, value);
        }

        @Override
        public String toString() {
            return String.format("(%d, %f)", timestamp, value);
        }
    }

    /**
     * Bit writer for efficient bit-level operations
     */
    private static class BitWriter {
        private final ByteArrayOutputStream buffer;
        private int currentByte;
        private int bitCount;

        public BitWriter() {
            this.buffer = new ByteArrayOutputStream();
            this.currentByte = 0;
            this.bitCount = 0;
        }

        public void writeBit(boolean bit) {
            if (bit) {
                currentByte |= 1 << (7 - bitCount);
            }
            bitCount++;

            if (bitCount == 8) {
                buffer.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }

        public void writeBits(long value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                boolean bit = ((value >> i) & 1) == 1;
                writeBit(bit);
            }
        }

        public byte[] finish() {
            if (bitCount > 0) {
                buffer.write(currentByte);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * Bit reader for efficient bit-level operations
     */
    private static class BitReader {
        private final byte[] data;
        private int byteIndex;
        private int bitIndex;

        public BitReader(byte[] data) {
            this.data = data;
            this.byteIndex = 0;
            this.bitIndex = 0;
        }

        public Boolean readBit() {
            if (byteIndex >= data.length) {
                return null;
            }

            byte b = data[byteIndex];
            boolean bit = ((b >> (7 - bitIndex)) & 1) == 1;

            bitIndex++;
            if (bitIndex == 8) {
                byteIndex++;
                bitIndex = 0;
            }

            return bit;
        }

        public Long readBits(int numBits) {
            long value = 0L;
            for (int i = 0; i < numBits; i++) {
                Boolean bit = readBit();
                if (bit == null) {
                    return null;
                }
                value = (value << 1) | (bit ? 1L : 0L);
            }
            return value;
        }
    }

    /**
     * Compress a timeseries of double values
     */
    public byte[] compressDouble(List<TimeValueDouble> series) throws IOException {
        if (series.isEmpty()) {
            return new byte[0];
        }

        BitWriter writer = new BitWriter();

        // Write header: timestep, count, and first timestamp/value
        writer.writeBits(timestep, 64);
        writer.writeBits(series.size(), 32);
        writer.writeBits(series.get(0).timestamp, 64);
        writer.writeBits(Double.doubleToLongBits(series.get(0).value), 64);

        long prevTimestamp = series.get(0).timestamp;
        long prevValueBits = Double.doubleToLongBits(series.get(0).value);
        long prevDelta = 0;

        for (int i = 1; i < series.size(); i++) {
            TimeValueDouble point = series.get(i);
            prevDelta = compressTimestamp(writer, point.timestamp, prevTimestamp, prevDelta);
            compressValueDouble(writer, point.value, prevValueBits);

            prevTimestamp = point.timestamp;
            prevValueBits = Double.doubleToLongBits(point.value);
        }

        return writer.finish();
    }

    /**
     * Compress a timeseries of float values
     */
    public byte[] compressFloat(List<TimeValueFloat> series) throws IOException {
        if (series.isEmpty()) {
            return new byte[0];
        }

        BitWriter writer = new BitWriter();

        // Write header: timestep, count, and first timestamp/value
        writer.writeBits(timestep, 64);
        writer.writeBits(series.size(), 32);
        writer.writeBits(series.get(0).timestamp, 64);
        writer.writeBits(Float.floatToIntBits(series.get(0).value) & 0xFFFFFFFFL, 32);

        long prevTimestamp = series.get(0).timestamp;
        int prevValueBits = Float.floatToIntBits(series.get(0).value);
        long prevDelta = 0;

        for (int i = 1; i < series.size(); i++) {
            TimeValueFloat point = series.get(i);
            prevDelta = compressTimestamp(writer, point.timestamp, prevTimestamp, prevDelta);
            compressValueFloat(writer, point.value, prevValueBits);

            prevTimestamp = point.timestamp;
            prevValueBits = Float.floatToIntBits(point.value);
        }

        return writer.finish();
    }

    private long compressTimestamp(BitWriter writer, long timestamp, long prevTimestamp, long prevDelta) {
        long delta = timestamp - prevTimestamp;

        if (delta == timestep) {
            // Common case: regular timestep
            writer.writeBit(false);
            return prevDelta;
        } else if (delta == prevDelta) {
            // Delta of deltas is 0
            writer.writeBit(true);
            writer.writeBit(false);
            return prevDelta;
        } else {
            // Need to encode delta of deltas
            long deltaOfDeltas = delta - prevDelta;
            writer.writeBit(true);
            writer.writeBit(true);

            if (deltaOfDeltas >= -63 && deltaOfDeltas <= 64) {
                writer.writeBits(0, 2); // 7-bit encoding
                writer.writeBits(deltaOfDeltas + 63, 7);
            } else if (deltaOfDeltas >= -255 && deltaOfDeltas <= 256) {
                writer.writeBits(1, 2); // 9-bit encoding
                writer.writeBits(deltaOfDeltas + 255, 9);
            } else if (deltaOfDeltas >= -2047 && deltaOfDeltas <= 2048) {
                writer.writeBits(2, 2); // 12-bit encoding
                writer.writeBits(deltaOfDeltas + 2047, 12);
            } else {
                writer.writeBits(3, 2); // 32-bit encoding
                writer.writeBits(deltaOfDeltas, 32);
            }

            return delta;
        }
    }

    private void compressValueDouble(BitWriter writer, double value, long prevValueBits) {
        long valueBits = Double.doubleToLongBits(value);

        if (valueBits == prevValueBits) {
            // Same value
            writer.writeBit(false);
        } else {
            writer.writeBit(true);
            long xor = valueBits ^ prevValueBits;
            int leadingZeros = Long.numberOfLeadingZeros(xor);
            int trailingZeros = Long.numberOfTrailingZeros(xor);
            int meaningfulBits = 64 - leadingZeros - trailingZeros;

            if (leadingZeros >= 5 && meaningfulBits <= 6) {
                // Use control bit pattern for common case
                writer.writeBit(false);
                writer.writeBits(leadingZeros, 5);
                writer.writeBits(meaningfulBits, 6);
                if (meaningfulBits > 0) {
                    writer.writeBits(xor >>> trailingZeros, meaningfulBits);
                }
            } else {
                // Fallback: store all 64 bits
                writer.writeBit(true);
                writer.writeBits(valueBits, 64);
            }
        }
    }

    private void compressValueFloat(BitWriter writer, float value, int prevValueBits) {
        int valueBits = Float.floatToIntBits(value);

        if (valueBits == prevValueBits) {
            // Same value
            writer.writeBit(false);
        } else {
            writer.writeBit(true);
            int xor = valueBits ^ prevValueBits;
            int leadingZeros = Integer.numberOfLeadingZeros(xor);
            int trailingZeros = Integer.numberOfTrailingZeros(xor);
            int meaningfulBits = 32 - leadingZeros - trailingZeros;

            if (leadingZeros >= 5 && meaningfulBits <= 6) {
                // Use control bit pattern for common case
                writer.writeBit(false);
                writer.writeBits(leadingZeros, 5);
                writer.writeBits(meaningfulBits, 6);
                if (meaningfulBits > 0) {
                    writer.writeBits((xor >>> trailingZeros) & 0xFFFFFFFFL, meaningfulBits);
                }
            } else {
                // Fallback: store all 32 bits
                writer.writeBit(true);
                writer.writeBits(valueBits & 0xFFFFFFFFL, 32);
            }
        }
    }

    /**
     * Decompress double timeseries data
     */
    public List<TimeValueDouble> decompressDouble(byte[] compressed) throws IOException {
        if (compressed.length == 0) {
            return new ArrayList<>();
        }

        BitReader reader = new BitReader(compressed);
        List<TimeValueDouble> result = new ArrayList<>();

        // Read header
        Long timestep = reader.readBits(64);
        Long count = reader.readBits(32);
        Long firstTimestamp = reader.readBits(64);
        Long firstValueBits = reader.readBits(64);

        if (timestep == null || count == null || firstTimestamp == null || firstValueBits == null) {
            throw new IOException("Invalid header");
        }

        double firstValue = Double.longBitsToDouble(firstValueBits);
        result.add(new TimeValueDouble(firstTimestamp, firstValue));

        long prevTimestamp = firstTimestamp;
        long prevValueBits = firstValueBits;
        long prevDelta = 0;

        // Read remaining data points (count - 1 since we already have the first)
        for (int i = 1; i < count; i++) {
            Boolean controlBit = reader.readBit();
            if (controlBit == null) {
                throw new IOException("Unexpected end of data");
            }
            // Decompress timestamp
            long timestamp;
            if (!controlBit) {
                timestamp = prevTimestamp + timestep;
            } else {
                Boolean deltaControl = reader.readBit();
                if (deltaControl == null) {
                    throw new IOException("Unexpected end of data");
                }
                if (!deltaControl) {
                    timestamp = prevTimestamp + prevDelta;
                } else {
                    long deltaOfDeltas = readDeltaOfDeltas(reader);
                    prevDelta += deltaOfDeltas;
                    timestamp = prevTimestamp + prevDelta;
                }
            }

            // Decompress value
            Boolean valueControl = reader.readBit();
            if (valueControl == null) {
                throw new IOException("Unexpected end of data");
            }

            double value;
            if (!valueControl) {
                // Same value
                value = Double.longBitsToDouble(prevValueBits);
            } else {
                Boolean encodingControl = reader.readBit();
                if (encodingControl == null) {
                    throw new IOException("Unexpected end of data");
                }

                if (!encodingControl) {
                    // Compressed XOR encoding
                    Long leadingZeros = reader.readBits(5);
                    Long meaningfulBits = reader.readBits(6);

                    if (leadingZeros == null || meaningfulBits == null) {
                        throw new IOException("Invalid value encoding");
                    }

                    if (meaningfulBits == 0) {
                        value = Double.longBitsToDouble(prevValueBits);
                    } else {
                        Long meaningfulValue = reader.readBits(meaningfulBits.intValue());
                        if (meaningfulValue == null) {
                            throw new IOException("Invalid value encoding");
                        }
                        int trailingZeros = 64 - leadingZeros.intValue() - meaningfulBits.intValue();
                        long xor = meaningfulValue << trailingZeros;
                        value = Double.longBitsToDouble(prevValueBits ^ xor);
                    }
                } else {
                    // Full 64-bit value
                    Long valueBits = reader.readBits(64);
                    if (valueBits == null) {
                        throw new IOException("Invalid value encoding");
                    }
                    value = Double.longBitsToDouble(valueBits);
                }
            }

            result.add(new TimeValueDouble(timestamp, value));
            prevTimestamp = timestamp;
            prevValueBits = Double.doubleToLongBits(value);
        }

        return result;
    }

    /**
     * Decompress float timeseries data
     */
    public List<TimeValueFloat> decompressFloat(byte[] compressed) throws IOException {
        if (compressed.length == 0) {
            return new ArrayList<>();
        }

        BitReader reader = new BitReader(compressed);
        List<TimeValueFloat> result = new ArrayList<>();

        // Read header
        Long timestep = reader.readBits(64);
        Long count = reader.readBits(32);
        Long firstTimestamp = reader.readBits(64);
        Long firstValueBits = reader.readBits(32);

        if (timestep == null || count == null || firstTimestamp == null || firstValueBits == null) {
            throw new IOException("Invalid header");
        }

        float firstValue = Float.intBitsToFloat(firstValueBits.intValue());
        result.add(new TimeValueFloat(firstTimestamp, firstValue));

        long prevTimestamp = firstTimestamp;
        int prevValueBits = firstValueBits.intValue();
        long prevDelta = 0;

        // Read remaining data points (count - 1 since we already have the first)
        for (int i = 1; i < count; i++) {
            Boolean controlBit = reader.readBit();
            if (controlBit == null) {
                throw new IOException("Unexpected end of data");
            }
            // Decompress timestamp (same logic as double)
            long timestamp;
            if (!controlBit) {
                timestamp = prevTimestamp + timestep;
            } else {
                Boolean deltaControl = reader.readBit();
                if (deltaControl == null) {
                    throw new IOException("Unexpected end of data");
                }
                if (!deltaControl) {
                    timestamp = prevTimestamp + prevDelta;
                } else {
                    long deltaOfDeltas = readDeltaOfDeltas(reader);
                    prevDelta += deltaOfDeltas;
                    timestamp = prevTimestamp + prevDelta;
                }
            }

            // Decompress value (adapted for float)
            Boolean valueControl = reader.readBit();
            if (valueControl == null) {
                throw new IOException("Unexpected end of data");
            }

            float value;
            if (!valueControl) {
                // Same value
                value = Float.intBitsToFloat(prevValueBits);
            } else {
                Boolean encodingControl = reader.readBit();
                if (encodingControl == null) {
                    throw new IOException("Unexpected end of data");
                }

                if (!encodingControl) {
                    // Compressed XOR encoding
                    Long leadingZeros = reader.readBits(5);
                    Long meaningfulBits = reader.readBits(6);

                    if (leadingZeros == null || meaningfulBits == null) {
                        throw new IOException("Invalid value encoding");
                    }

                    if (meaningfulBits == 0) {
                        value = Float.intBitsToFloat(prevValueBits);
                    } else {
                        Long meaningfulValue = reader.readBits(meaningfulBits.intValue());
                        if (meaningfulValue == null) {
                            throw new IOException("Invalid value encoding");
                        }
                        int trailingZeros = 32 - leadingZeros.intValue() - meaningfulBits.intValue();
                        int xor = (int)(meaningfulValue << trailingZeros);
                        value = Float.intBitsToFloat(prevValueBits ^ xor);
                    }
                } else {
                    // Full 32-bit value
                    Long valueBits = reader.readBits(32);
                    if (valueBits == null) {
                        throw new IOException("Invalid value encoding");
                    }
                    value = Float.intBitsToFloat(valueBits.intValue());
                }
            }

            result.add(new TimeValueFloat(timestamp, value));
            prevTimestamp = timestamp;
            prevValueBits = Float.floatToIntBits(value);
        }

        return result;
    }

    private long readDeltaOfDeltas(BitReader reader) throws IOException {
        Long encodingType = reader.readBits(2);
        if (encodingType == null) {
            throw new IOException("Invalid delta encoding");
        }

        switch (encodingType.intValue()) {
            case 0: {
                // 7-bit encoding
                Long value = reader.readBits(7);
                if (value == null) {
                    throw new IOException("Invalid delta encoding");
                }
                return value - 63;
            }
            case 1: {
                // 9-bit encoding
                Long value = reader.readBits(9);
                if (value == null) {
                    throw new IOException("Invalid delta encoding");
                }
                return value - 255;
            }
            case 2: {
                // 12-bit encoding
                Long value = reader.readBits(12);
                if (value == null) {
                    throw new IOException("Invalid delta encoding");
                }
                return value - 2047;
            }
            case 3: {
                // 32-bit encoding
                Long value = reader.readBits(32);
                if (value == null) {
                    throw new IOException("Invalid delta encoding");
                }
                return value;
            }
            default:
                throw new IOException("Invalid encoding type");
        }
    }

    /**
     * Compress and encode as base64 for JSON embedding
     */
    public String compressDoubleBase64(List<TimeValueDouble> series) throws IOException {
        byte[] compressed = compressDouble(series);
        return Base64.getEncoder().encodeToString(compressed);
    }

    /**
     * Compress and encode as base64 for JSON embedding
     */
    public String compressFloatBase64(List<TimeValueFloat> series) throws IOException {
        byte[] compressed = compressFloat(series);
        return Base64.getEncoder().encodeToString(compressed);
    }

    /**
     * Decompress from base64
     */
    public List<TimeValueDouble> decompressDoubleBase64(String base64Data) throws IOException {
        try {
            byte[] compressed = Base64.getDecoder().decode(base64Data);
            return decompressDouble(compressed);
        } catch (IllegalArgumentException e) {
            throw new IOException("Base64 decode error: " + e.getMessage(), e);
        }
    }

    /**
     * Decompress from base64
     */
    public List<TimeValueFloat> decompressFloatBase64(String base64Data) throws IOException {
        try {
            byte[] compressed = Base64.getDecoder().decode(base64Data);
            return decompressFloat(compressed);
        } catch (IllegalArgumentException e) {
            throw new IOException("Base64 decode error: " + e.getMessage(), e);
        }
    }

    // Utility methods for easy creation of time-value pairs
    public static TimeValueDouble doublePoint(long timestamp, double value) {
        return new TimeValueDouble(timestamp, value);
    }

    public static TimeValueFloat floatPoint(long timestamp, float value) {
        return new TimeValueFloat(timestamp, value);
    }

    // Unit tests
    public static void main(String[] args) {
        try {
            testRegularSeriesDouble();
            testRepeatedValuesDouble();
            testSpecialValuesDouble();
            testFloatCompression();
            testBase64Encoding();
            testEmptySeries();
            System.out.println("All tests passed!");
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testRegularSeriesDouble() throws IOException {
        GorillaCompressor compressor = new GorillaCompressor(1000);
        List<TimeValueDouble> series = Arrays.asList(
                doublePoint(1000, 1.0),
                doublePoint(2000, 2.0),
                doublePoint(3000, 3.0),
                doublePoint(4000, 4.0),
                doublePoint(5000, 5.0)
        );

        byte[] compressed = compressor.compressDouble(series);
        List<TimeValueDouble> decompressed = compressor.decompressDouble(compressed);

        if (!series.equals(decompressed)) {
            throw new RuntimeException("Regular series test failed");
        }
    }

    private static void testRepeatedValuesDouble() throws IOException {
        GorillaCompressor compressor = new GorillaCompressor(1000);
        List<TimeValueDouble> series = Arrays.asList(
                doublePoint(1000, 42.0),
                doublePoint(2000, 42.0),
                doublePoint(3000, 42.0),
                doublePoint(4000, 43.0),
                doublePoint(5000, 42.0)
        );

        byte[] compressed = compressor.compressDouble(series);
        List<TimeValueDouble> decompressed = compressor.decompressDouble(compressed);

        if (!series.equals(decompressed)) {
            throw new RuntimeException("Repeated values test failed");
        }
    }

    private static void testSpecialValuesDouble() throws IOException {
        GorillaCompressor compressor = new GorillaCompressor(1000);
        List<TimeValueDouble> series = Arrays.asList(
                doublePoint(1000, 0.0),
                doublePoint(2000, Double.NaN),
                doublePoint(3000, Double.POSITIVE_INFINITY),
                doublePoint(4000, Double.NEGATIVE_INFINITY),
                doublePoint(5000, -0.0)
        );

        byte[] compressed = compressor.compressDouble(series);
        List<TimeValueDouble> decompressed = compressor.decompressDouble(compressed);

        // NaN comparison requires special handling
        if (decompressed.size() != series.size()) {
            throw new RuntimeException("Special values test failed - size mismatch");
        }
        if (!decompressed.get(0).equals(doublePoint(1000, 0.0))) {
            throw new RuntimeException("Special values test failed - first value");
        }
        if (!Double.isNaN(decompressed.get(1).value)) {
            throw new RuntimeException("Special values test failed - NaN");
        }
        if (!decompressed.get(2).equals(doublePoint(3000, Double.POSITIVE_INFINITY))) {
            throw new RuntimeException("Special values test failed - positive infinity");
        }
        if (!decompressed.get(3).equals(doublePoint(4000, Double.NEGATIVE_INFINITY))) {
            throw new RuntimeException("Special values test failed - negative infinity");
        }
        if (!decompressed.get(4).equals(doublePoint(5000, -0.0))) {
            throw new RuntimeException("Special values test failed - negative zero");
        }
    }

    private static void testFloatCompression() throws IOException {
        GorillaCompressor compressor = new GorillaCompressor(500);
        List<TimeValueFloat> series = Arrays.asList(
                floatPoint(500, 1.5f),
                floatPoint(1000, 2.5f),
                floatPoint(1500, 2.5f), // repeated value
                floatPoint(2000, Float.NaN),
                floatPoint(2500, 0.0f)
        );

        byte[] compressed = compressor.compressFloat(series);
        List<TimeValueFloat> decompressed = compressor.decompressFloat(compressed);

        if (decompressed.size() != series.size()) {
            throw new RuntimeException("Float compression test failed - size mismatch");
        }
        if (!decompressed.get(0).equals(floatPoint(500, 1.5f))) {
            throw new RuntimeException("Float compression test failed - first value");
        }
        if (!decompressed.get(1).equals(floatPoint(1000, 2.5f))) {
            throw new RuntimeException("Float compression test failed - second value");
        }
        if (!decompressed.get(2).equals(floatPoint(1500, 2.5f))) {
            throw new RuntimeException("Float compression test failed - repeated value");
        }
        if (!Float.isNaN(decompressed.get(3).value)) {
            throw new RuntimeException("Float compression test failed - NaN");
        }
        if (!decompressed.get(4).equals(floatPoint(2500, 0.0f))) {
            throw new RuntimeException("Float compression test failed - zero value");
        }
    }

    private static void testBase64Encoding() throws IOException {
        GorillaCompressor compressor = new GorillaCompressor(1000);
        List<TimeValueDouble> series = Arrays.asList(
                doublePoint(1000, 1.0),
                doublePoint(2000, 2.0),
                doublePoint(3000, 2.0), // repeated
                doublePoint(4000, 4.0)
        );

        String base64Compressed = compressor.compressDoubleBase64(series);
        List<TimeValueDouble> decompressed = compressor.decompressDoubleBase64(base64Compressed);

        if (!series.equals(decompressed)) {
            throw new RuntimeException("Base64 encoding test failed");
        }
    }

    private static void testEmptySeries() throws IOException {
        GorillaCompressor compressor = new GorillaCompressor(1000);
        List<TimeValueDouble> series = new ArrayList<>();

        byte[] compressed = compressor.compressDouble(series);
        List<TimeValueDouble> decompressed = compressor.decompressDouble(compressed);

        if (!series.equals(decompressed)) {
            throw new RuntimeException("Empty series test failed");
        }
    }
}
