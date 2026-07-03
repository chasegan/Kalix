package com.kalix.ide.io.compression.gorilla;

import com.kalix.ide.io.compression.gorilla.GorillaCompressor.TimeValueDouble;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec-level tests for GorillaCompressor, mirroring the Rust test suite in
 * src/io/compression/gorilla.rs. The two implementations must stay bit-identical:
 * any encoding change on either side must be reflected in both files and in the
 * cross-language fixture below.
 */
class GorillaCompressorTest {

    private static final long TIMESTEP = 86400L;

    private static List<TimeValueDouble> regularSeries(double[] values) {
        List<TimeValueDouble> series = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            series.add(new TimeValueDouble(TIMESTEP * i, values[i]));
        }
        return series;
    }

    /** Round-trips a series and returns the compressed size in bits per value. */
    private static double assertRoundTrip(List<TimeValueDouble> series) throws Exception {
        GorillaCompressor compressor = new GorillaCompressor(TIMESTEP);
        byte[] compressed = compressor.compressDouble(series);
        List<TimeValueDouble> decompressed = compressor.decompressDouble(compressed);

        assertEquals(series.size(), decompressed.size(), "size mismatch");
        for (int i = 0; i < series.size(); i++) {
            assertEquals(series.get(i).timestamp, decompressed.get(i).timestamp,
                "timestamp mismatch at index " + i);
            // Raw bits: the codec's contract is bit-exact round-tripping, including
            // NaN payloads (doubleToLongBits would canonicalize them and hide drift).
            assertEquals(Double.doubleToRawLongBits(series.get(i).value),
                Double.doubleToRawLongBits(decompressed.get(i).value),
                "value bits mismatch at index " + i);
        }
        return compressed.length * 8.0 / series.size();
    }

    /**
     * Adjacent values differing only in low mantissa bits have >31 leading zeros in
     * their XOR. The unclamped encoder truncated the count into the 5-bit field and
     * corrupted the value on decode.
     */
    @Test
    void highLeadingZeroXorRoundTrips() throws Exception {
        double oneUlpUp = Double.longBitsToDouble(Double.doubleToLongBits(1.0) + 1);
        double lowBitsFlipped = Double.longBitsToDouble(Double.doubleToLongBits(123.456) ^ 0b111111);
        assertRoundTrip(regularSeries(new double[]{1.0, oneUlpUp}));
        assertRoundTrip(regularSeries(new double[]{123.456, lowBitsFlipped}));
    }

    /**
     * Round operational numbers (1000, 240, 86.4, ...) have sparse mantissas, so their
     * XORs have few meaningful bits despite the values being far apart. These must both
     * round-trip and actually compress (the old {@code meaningfulBits <= 6} gate pushed
     * them all to the raw 64-bit fallback).
     */
    @Test
    void roundNumberStepsCompress() throws Exception {
        double[] pattern = {1000.0, 240.0, 86.4, 500.0, 750.0, 120.0, 1000.0, 60.0, 480.0, 240.0};
        double[] values = new double[2000];
        for (int i = 0; i < values.length; i++) {
            values[i] = pattern[i % pattern.length];
        }
        double bitsPerValue = assertRoundTrip(regularSeries(values));
        assertTrue(bitsPerValue < 40.0,
            "round-number steps should compress well below raw 64 bits/value, got " + bitsPerValue);
    }

    /** NaN, infinities and signed zero must survive bit-exactly. */
    @Test
    void specialValuesRoundTrip() throws Exception {
        assertRoundTrip(regularSeries(new double[]{
            0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -0.0, 1.0e300, Double.MIN_VALUE
        }));
    }

    /**
     * Irregular timestamps whose delta-of-deltas goes negative beyond the 12-bit range
     * exercise the 32-bit fallback, which the decoder must sign-extend.
     */
    @Test
    void largeNegativeDeltaOfDeltasRoundTrips() throws Exception {
        List<TimeValueDouble> series = new ArrayList<>();
        series.add(new TimeValueDouble(0L, 1.0));
        series.add(new TimeValueDouble(1_000_000L, 2.0));  // dod = +913_600 -> 32-bit branch
        series.add(new TimeValueDouble(1_086_400L, 3.0));  // dod = -913_600 -> negative 32-bit branch
        series.add(new TimeValueDouble(1_172_800L, 4.0));  // regular step resumes
        assertRoundTrip(series);
    }

    /** Deterministic pseudo-random walk (splitmix64), mirroring the Rust test. */
    @Test
    void randomWalkRoundTrips() throws Exception {
        long state = 0x9E3779B97F4A7C15L;
        double value = 100.0;
        double[] values = new double[5000];
        for (int i = 0; i < values.length; i++) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            double step = (z >>> 11) / (double) (1L << 53) - 0.5;
            value = Math.max(0.0, value + step);
            values[i] = value;
        }
        assertRoundTrip(regularSeries(values));
    }

    /**
     * Non-canonical NaN payloads (e.g. {@code 0xFFF8…} from {@code 0.0/0.0} on x86) must
     * pass through the codec bit-exactly: a decoder that canonicalizes NaN mid-stream
     * desynchronizes the XOR chain and corrupts every subsequent value (historically,
     * 1.5 after such a NaN decoded as -1.5). Cross-language fixture: same series and
     * base64 pinned in the Rust suite (test_nan_payload_fixture_matches_java).
     */
    @Test
    void nanPayloadFixtureMatchesRust() throws Exception {
        String base64 = "AAAAAAABUYAAAAAGAAAAAAAAAAA/8AAAAAAAAEA3ACgBbf/gAAAAAvu9oAIAAAAAAAAhGf/g";

        long[] valueBits = {
            0x3ff0000000000000L, // 1.0
            0xfff8000000000000L, // negative quiet NaN (x86 0.0/0.0)
            0x3ff8000000000000L, // 1.5 — the value corrupted by a canonicalizing decoder
            0x7ff800000000beefL, // quiet NaN with payload bits
            0x4004000000000000L, // 2.5
            0x7ff8000000000000L  // canonical quiet NaN
        };

        GorillaCompressor compressor = new GorillaCompressor(TIMESTEP);
        List<TimeValueDouble> decoded = compressor.decompressDoubleBase64(base64);

        assertEquals(valueBits.length, decoded.size(), "fixture size mismatch");
        for (int i = 0; i < valueBits.length; i++) {
            assertEquals(TIMESTEP * i, decoded.get(i).timestamp, "timestamp mismatch at index " + i);
            assertEquals(valueBits[i], Double.doubleToRawLongBits(decoded.get(i).value),
                "value bits mismatch at index " + i);
        }

        // And the Java encoder must produce the identical byte stream for the same input.
        List<TimeValueDouble> series = new ArrayList<>();
        for (int i = 0; i < valueBits.length; i++) {
            series.add(new TimeValueDouble(TIMESTEP * i, Double.longBitsToDouble(valueBits[i])));
        }
        assertEquals(base64, compressor.compressDoubleBase64(series),
            "Java encoder diverged from Rust encoder for the NaN-payload fixture");
    }

    /**
     * Cross-language fixture: this base64 payload was produced by the Rust encoder
     * (src/io/compression/gorilla.rs) from the series below. Decoding it here proves the
     * Java decoder matches the Rust encoder bit-for-bit. If either implementation's
     * encoding changes, regenerate this fixture from Rust and update both test suites.
     */
    @Test
    void decodesRustEncodedFixture() throws Exception {
        String base64 = "AAAAAAABUYAAAAAMAAAAAAAAAABAj0AAAAAAABIK4VK17mZmZmZmavwgAAAAFn9/Nbpujexc3/4AAAAAAAATAfgAehIEAz/9AQwEUSiOkA==";

        long[] timestamps = {
            0L, 86400L, 172800L, 259200L, 345600L, 432000L,
            518400L, 604800L, 1604800L, 1691200L, 1777600L, 1864000L
        };
        long[] valueBits = {
            0x408f400000000000L, 0x408f400000000000L, 0x406e000000000000L,
            0x405599999999999aL, 0x405599999999999bL, 0x3fbf9add3746f62eL,
            0x7ff8000000000000L, 0x7ff0000000000000L, 0x8000000000000000L,
            0x4045000000000000L, 0x4045000000000000L, 0x407f400000000000L
        };

        GorillaCompressor compressor = new GorillaCompressor(TIMESTEP);
        List<TimeValueDouble> decoded = compressor.decompressDoubleBase64(base64);

        assertEquals(timestamps.length, decoded.size(), "fixture size mismatch");
        for (int i = 0; i < timestamps.length; i++) {
            assertEquals(timestamps[i], decoded.get(i).timestamp, "timestamp mismatch at index " + i);
            assertEquals(valueBits[i], Double.doubleToRawLongBits(decoded.get(i).value),
                "value bits mismatch at index " + i);
        }

        // And the Java encoder must produce the identical byte stream for the same input.
        List<TimeValueDouble> series = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            series.add(new TimeValueDouble(timestamps[i], Double.longBitsToDouble(valueBits[i])));
        }
        assertEquals(base64, compressor.compressDoubleBase64(series),
            "Java encoder diverged from Rust encoder for the fixture series");
    }
}
