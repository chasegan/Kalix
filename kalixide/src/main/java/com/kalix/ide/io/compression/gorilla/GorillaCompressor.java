package com.kalix.ide.io.compression.gorilla;

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
     * Primitive-array decode result for double series: parallel timestamp/value
     * arrays with no per-point heap objects. This is the allocation-light path
     * for large series ({@link #decompressDoubleArrays(byte[])});
     * {@link #decompressDouble(byte[])} wraps it in the historical List API.
     */
    public static final class DoubleArraySeries {
        public final long[] timestamps;
        public final double[] values;

        public DoubleArraySeries(long[] timestamps, double[] values) {
            this.timestamps = timestamps;
            this.values = values;
        }

        public int size() {
            return timestamps.length;
        }
    }

    /**
     * Primitive-array decode result for float series (see {@link DoubleArraySeries}).
     */
    public static final class FloatArraySeries {
        public final long[] timestamps;
        public final float[] values;

        public FloatArraySeries(long[] timestamps, float[] values) {
            this.timestamps = timestamps;
            this.values = values;
        }

        public int size() {
            return timestamps.length;
        }
    }

    /**
     * Bit writer for efficient bit-level operations.
     * Maintained in lockstep with BitWriter in gorilla.rs; the plain byte array
     * mirrors the Rust Vec&lt;u8&gt; (and avoids ByteArrayOutputStream's
     * synchronized per-byte writes).
     */
    private static class BitWriter {
        private byte[] buffer;
        private int length;
        private int currentByte;
        private int bitCount;

        public BitWriter() {
            this.buffer = new byte[256];
            this.length = 0;
            this.currentByte = 0;
            this.bitCount = 0;
        }

        private void push(int b) {
            if (length == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            buffer[length++] = (byte) b;
        }

        public void writeBit(boolean bit) {
            if (bit) {
                currentByte |= 1 << (7 - bitCount);
            }
            bitCount++;

            if (bitCount == 8) {
                push(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }

        /**
         * Append the low {@code numBits} of {@code value}, most-significant bit first.
         * Byte-at-a-time (up to 9 iterations for 64 bits) rather than the old
         * bit-at-a-time loop (64); the produced bitstream is identical, and is
         * pinned by the cross-language fixtures in GorillaCompressorTest.
         * Mirrors BitWriter::write_bits in gorilla.rs.
         */
        public void writeBits(long value, int numBits) {
            int remaining = numBits;
            while (remaining > 0) {
                int free = 8 - bitCount;
                int take = Math.min(free, remaining);
                int shift = remaining - take;
                int chunk = (int) ((value >>> shift) & ((1L << take) - 1));
                currentByte |= chunk << (free - take);
                bitCount += take;
                remaining -= take;
                if (bitCount == 8) {
                    push(currentByte);
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }

        public byte[] finish() {
            if (bitCount > 0) {
                push(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
            return Arrays.copyOf(buffer, length);
        }
    }

    /**
     * Bit reader for efficient bit-level operations.
     * Maintained in lockstep with BitReader in gorilla.rs. Where the Rust twin
     * returns {@code Option::None} at end of input (mapped to an error by
     * {@code ok_or_else} at each call site), these methods throw IOException
     * directly — same control flow, no boxed Boolean/Long per read.
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

        public boolean readBit() throws IOException {
            if (byteIndex >= data.length) {
                throw new IOException("Unexpected end of data");
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

        /**
         * Read {@code numBits} (MSB first), byte-at-a-time rather than the old
         * bit-at-a-time loop. Mirrors writeBits and BitReader::read_bits in
         * gorilla.rs; the bitstream interpretation is unchanged and pinned by
         * the cross-language fixtures in GorillaCompressorTest.
         */
        public long readBits(int numBits) throws IOException {
            long value = 0L;
            int remaining = numBits;
            while (remaining > 0) {
                if (byteIndex >= data.length) {
                    throw new IOException("Unexpected end of data");
                }
                int available = 8 - bitIndex;
                int take = Math.min(available, remaining);
                int chunk = ((data[byteIndex] & 0xFF) >>> (available - take)) & ((1 << take) - 1);
                value = (value << take) | chunk;
                bitIndex += take;
                remaining -= take;
                if (bitIndex == 8) {
                    byteIndex++;
                    bitIndex = 0;
                }
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
        // Raw bits throughout the codec: doubleToLongBits would canonicalize NaN
        // payloads, desynchronizing the XOR chain against the Rust encoder, which
        // uses to_bits() (raw) — see gorilla.rs.
        writer.writeBits(Double.doubleToRawLongBits(series.get(0).value), 64);

        long prevTimestamp = series.get(0).timestamp;
        long prevValueBits = Double.doubleToRawLongBits(series.get(0).value);
        long prevDelta = 0;

        for (int i = 1; i < series.size(); i++) {
            TimeValueDouble point = series.get(i);
            prevDelta = compressTimestamp(writer, point.timestamp, prevTimestamp, prevDelta);
            compressValueDouble(writer, point.value, prevValueBits);

            prevTimestamp = point.timestamp;
            prevValueBits = Double.doubleToRawLongBits(point.value);
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
        // Raw bits throughout the codec (see compressDouble).
        writer.writeBits(Float.floatToRawIntBits(series.get(0).value) & 0xFFFFFFFFL, 32);

        long prevTimestamp = series.get(0).timestamp;
        int prevValueBits = Float.floatToRawIntBits(series.get(0).value);
        long prevDelta = 0;

        for (int i = 1; i < series.size(); i++) {
            TimeValueFloat point = series.get(i);
            prevDelta = compressTimestamp(writer, point.timestamp, prevTimestamp, prevDelta);
            compressValueFloat(writer, point.value, prevValueBits);

            prevTimestamp = point.timestamp;
            prevValueBits = Float.floatToRawIntBits(point.value);
        }

        return writer.finish();
    }

    private long compressTimestamp(BitWriter writer, long timestamp, long prevTimestamp, long prevDelta)
            throws IOException {
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
            } else if (deltaOfDeltas >= Integer.MIN_VALUE && deltaOfDeltas <= Integer.MAX_VALUE) {
                writer.writeBits(3, 2); // 32-bit encoding
                writer.writeBits(deltaOfDeltas, 32);
            } else {
                // The largest escape is 32 bits; anything wider would be silently
                // truncated on encode and mis-decoded as a wrong timestamp. The Rust
                // encoder enforces the same limit (gorilla.rs) — keep them in lockstep.
                throw new IOException(
                    "Timestamp delta-of-deltas " + deltaOfDeltas + " exceeds the 32-bit encoding range");
            }

            return delta;
        }
    }

    private void compressValueDouble(BitWriter writer, double value, long prevValueBits) {
        long valueBits = Double.doubleToRawLongBits(value);

        if (valueBits == prevValueBits) {
            // Same value
            writer.writeBit(false);
        } else {
            writer.writeBit(true);
            long xor = valueBits ^ prevValueBits;
            // The leading-zeros count must fit the 5-bit field (max 31). A nonzero
            // 64-bit XOR can have up to 63 leading zeros, so clamp and widen the
            // meaningful window accordingly (extra leading bits of the window are
            // zeros of the XOR, so the reconstruction is unchanged).
            int leadingZeros = Math.min(Long.numberOfLeadingZeros(xor), 31);
            int trailingZeros = Long.numberOfTrailingZeros(xor);
            int meaningfulBits = 64 - leadingZeros - trailingZeros;

            // Compact form costs 5 + 6 + meaningfulBits; the raw fallback costs 64.
            // Use compact whenever it is no larger (meaningfulBits <= 53).
            if (meaningfulBits <= 53) {
                writer.writeBit(false);
                writer.writeBits(leadingZeros, 5);
                writer.writeBits(meaningfulBits, 6);
                writer.writeBits(xor >>> trailingZeros, meaningfulBits);
            } else {
                // Fallback: store all 64 bits
                writer.writeBit(true);
                writer.writeBits(valueBits, 64);
            }
        }
    }

    private void compressValueFloat(BitWriter writer, float value, int prevValueBits) {
        int valueBits = Float.floatToRawIntBits(value);

        if (valueBits == prevValueBits) {
            // Same value
            writer.writeBit(false);
        } else {
            writer.writeBit(true);
            int xor = valueBits ^ prevValueBits;
            // A nonzero 32-bit XOR has at most 31 leading zeros, so the 5-bit
            // field always fits — no clamp needed here.
            int leadingZeros = Integer.numberOfLeadingZeros(xor);
            int trailingZeros = Integer.numberOfTrailingZeros(xor);
            int meaningfulBits = 32 - leadingZeros - trailingZeros;

            // Compact form costs 5 + 6 + meaningfulBits; the raw fallback costs 32.
            // Use compact whenever it is no larger (meaningfulBits <= 21).
            if (meaningfulBits <= 21) {
                writer.writeBit(false);
                writer.writeBits(leadingZeros, 5);
                writer.writeBits(meaningfulBits, 6);
                writer.writeBits((xor >>> trailingZeros) & 0xFFFFFFFFL, meaningfulBits);
            } else {
                // Fallback: store all 32 bits
                writer.writeBit(true);
                writer.writeBits(valueBits & 0xFFFFFFFFL, 32);
            }
        }
    }

    /**
     * Decompress double timeseries data.
     * Thin wrapper over {@link #decompressDoubleArrays(byte[])}, preserving the
     * historical List API; prefer the array form on hot paths (it allocates two
     * arrays total rather than two heap objects per point).
     */
    public List<TimeValueDouble> decompressDouble(byte[] compressed) throws IOException {
        DoubleArraySeries series = decompressDoubleArrays(compressed);
        int n = series.size();
        List<TimeValueDouble> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(new TimeValueDouble(series.timestamps[i], series.values[i]));
        }
        return result;
    }

    /**
     * Decompress double timeseries data into primitive parallel arrays.
     * Control flow mirrors decompress_double in gorilla.rs — the two decoders
     * are maintained in lockstep.
     */
    public DoubleArraySeries decompressDoubleArrays(byte[] compressed) throws IOException {
        if (compressed.length == 0) {
            return new DoubleArraySeries(new long[0], new double[0]);
        }

        BitReader reader = new BitReader(compressed);

        // Read header
        long timestep = reader.readBits(64);
        long count = reader.readBits(32);
        long firstTimestamp = reader.readBits(64);
        long firstValueBits = reader.readBits(64);
        int n = checkedPointCount(count, compressed.length, 64);

        long[] timestamps = new long[n];
        double[] values = new double[n];
        timestamps[0] = firstTimestamp;
        values[0] = Double.longBitsToDouble(firstValueBits);

        long prevTimestamp = firstTimestamp;
        // The XOR chain state is carried as raw bits, never round-tripped through a
        // double: doubleToLongBits canonicalizes NaN payloads, which would desync
        // this decoder from the Rust encoder's raw-bits chain (gorilla.rs).
        long prevValueBits = firstValueBits;
        long prevDelta = 0;

        // Read remaining data points (count - 1 since we already have the first)
        for (int i = 1; i < n; i++) {
            boolean controlBit = reader.readBit();

            // Decompress timestamp
            long timestamp;
            if (!controlBit) {
                timestamp = prevTimestamp + timestep;
            } else {
                boolean deltaControl = reader.readBit();
                if (!deltaControl) {
                    timestamp = prevTimestamp + prevDelta;
                } else {
                    long deltaOfDeltas = readDeltaOfDeltas(reader);
                    prevDelta += deltaOfDeltas;
                    timestamp = prevTimestamp + prevDelta;
                }
            }

            // Decompress value
            boolean valueControl = reader.readBit();

            if (valueControl) {
                boolean encodingControl = reader.readBit();

                if (!encodingControl) {
                    // Compressed XOR encoding
                    int leadingZeros = (int) reader.readBits(5);
                    int meaningfulBits = (int) reader.readBits(6);

                    if (meaningfulBits != 0) {
                        long meaningfulValue = reader.readBits(meaningfulBits);
                        int trailingZeros = 64 - leadingZeros - meaningfulBits;
                        if (trailingZeros < 0) {
                            throw new IOException("Corrupt value encoding: leading + meaningful bits exceed 64");
                        }
                        prevValueBits ^= meaningfulValue << trailingZeros;
                    }
                } else {
                    // Full 64-bit value
                    prevValueBits = reader.readBits(64);
                }
            }
            // else: same value — bit state unchanged

            timestamps[i] = timestamp;
            values[i] = Double.longBitsToDouble(prevValueBits);
            prevTimestamp = timestamp;
        }

        return new DoubleArraySeries(timestamps, values);
    }

    /**
     * Decompress float timeseries data.
     * Thin wrapper over {@link #decompressFloatArrays(byte[])}, preserving the
     * historical List API; prefer the array form on hot paths.
     */
    public List<TimeValueFloat> decompressFloat(byte[] compressed) throws IOException {
        FloatArraySeries series = decompressFloatArrays(compressed);
        int n = series.size();
        List<TimeValueFloat> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(new TimeValueFloat(series.timestamps[i], series.values[i]));
        }
        return result;
    }

    /**
     * Decompress float timeseries data into primitive parallel arrays.
     * Control flow mirrors decompress_float in gorilla.rs — the two decoders
     * are maintained in lockstep.
     */
    public FloatArraySeries decompressFloatArrays(byte[] compressed) throws IOException {
        if (compressed.length == 0) {
            return new FloatArraySeries(new long[0], new float[0]);
        }

        BitReader reader = new BitReader(compressed);

        // Read header
        long timestep = reader.readBits(64);
        long count = reader.readBits(32);
        long firstTimestamp = reader.readBits(64);
        int firstValueBits = (int) reader.readBits(32);
        int n = checkedPointCount(count, compressed.length, 32);

        long[] timestamps = new long[n];
        float[] values = new float[n];
        timestamps[0] = firstTimestamp;
        values[0] = Float.intBitsToFloat(firstValueBits);

        long prevTimestamp = firstTimestamp;
        // Raw-bits chain state, as in decompressDoubleArrays.
        int prevValueBits = firstValueBits;
        long prevDelta = 0;

        // Read remaining data points (count - 1 since we already have the first)
        for (int i = 1; i < n; i++) {
            boolean controlBit = reader.readBit();

            // Decompress timestamp (same logic as double)
            long timestamp;
            if (!controlBit) {
                timestamp = prevTimestamp + timestep;
            } else {
                boolean deltaControl = reader.readBit();
                if (!deltaControl) {
                    timestamp = prevTimestamp + prevDelta;
                } else {
                    long deltaOfDeltas = readDeltaOfDeltas(reader);
                    prevDelta += deltaOfDeltas;
                    timestamp = prevTimestamp + prevDelta;
                }
            }

            // Decompress value (adapted for float)
            boolean valueControl = reader.readBit();

            if (valueControl) {
                boolean encodingControl = reader.readBit();

                if (!encodingControl) {
                    // Compressed XOR encoding
                    int leadingZeros = (int) reader.readBits(5);
                    int meaningfulBits = (int) reader.readBits(6);

                    if (meaningfulBits != 0) {
                        long meaningfulValue = reader.readBits(meaningfulBits);
                        int trailingZeros = 32 - leadingZeros - meaningfulBits;
                        if (trailingZeros < 0) {
                            throw new IOException("Corrupt value encoding: leading + meaningful bits exceed 32");
                        }
                        prevValueBits ^= (int) (meaningfulValue << trailingZeros);
                    }
                } else {
                    // Full 32-bit value
                    prevValueBits = (int) reader.readBits(32);
                }
            }
            // else: same value — bit state unchanged

            timestamps[i] = timestamp;
            values[i] = Float.intBitsToFloat(prevValueBits);
            prevTimestamp = timestamp;
        }

        return new FloatArraySeries(timestamps, values);
    }

    /**
     * Validate the header's point count before preallocating the decode arrays:
     * it must fit an int, be at least 1 (a non-empty stream always carries its
     * first point in the header), and not exceed what the payload could possibly
     * hold (each point after the first costs at least 2 bits) — so a corrupt
     * header cannot force a huge allocation.
     */
    private static int checkedPointCount(long count, int compressedLength, int firstValueBitsWidth)
            throws IOException {
        long headerBits = 64 + 32 + 64 + (long) firstValueBitsWidth;
        long maxPoints = 1 + (compressedLength * 8L - headerBits) / 2;
        if (count < 1 || count > Integer.MAX_VALUE || count > maxPoints) {
            throw new IOException("Invalid point count: " + count);
        }
        return (int) count;
    }

    private long readDeltaOfDeltas(BitReader reader) throws IOException {
        int encodingType = (int) reader.readBits(2);

        switch (encodingType) {
            case 0:
                // 7-bit encoding
                return reader.readBits(7) - 63;
            case 1:
                // 9-bit encoding
                return reader.readBits(9) - 255;
            case 2:
                // 12-bit encoding
                return reader.readBits(12) - 2047;
            case 3:
                // 32-bit encoding. The encoder wrote the low 32 bits of a signed
                // delta-of-deltas (two's complement), so sign-extend on the way back.
                return (int) reader.readBits(32); // int cast truncates to 32 bits; widening back sign-extends
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
     * Decompress from base64 into primitive parallel arrays
     * (see {@link #decompressDoubleArrays(byte[])}).
     */
    public DoubleArraySeries decompressDoubleArraysBase64(String base64Data) throws IOException {
        try {
            byte[] compressed = Base64.getDecoder().decode(base64Data);
            return decompressDoubleArrays(compressed);
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
}
