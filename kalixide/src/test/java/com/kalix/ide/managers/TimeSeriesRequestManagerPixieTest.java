package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.compression.gorilla.GorillaCompressor;
import com.kalix.ide.io.compression.gorilla.GorillaCompressor.TimeValueDouble;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip tests for the Pixie wire format used by get_result responses.
 * Encodes a known series via GorillaCompressor → base64 (mimicking the CLI),
 * then decodes it via the IDE-side decoder and verifies values and timestamps.
 */
class TimeSeriesRequestManagerPixieTest {

    private static final long TIMESTEP_SECONDS = 3600L; // hourly
    private static final long START_EPOCH_SEC = 1577836800L; // 2020-01-01T00:00:00Z

    @Test
    void roundTrip_preservesPlainValues() throws Exception {
        double[] expected = {1.0, 2.5, 3.14159, -7.0, 0.0, 1e-9, 1e9};
        String b64 = encode(expected);

        TimeSeriesData decoded = TimeSeriesRequestManager.decodePixiePayload("test.series", b64);

        assertEquals(expected.length, decoded.getValues().length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], decoded.getValues()[i], 0.0,
                "value mismatch at index " + i);
        }
    }

    @Test
    void roundTrip_preservesNaNAndInfinity() throws Exception {
        double[] expected = {
            1.0,
            Double.NaN,
            2.0,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -0.0,
            0.0,
            Double.NaN
        };
        String b64 = encode(expected);

        TimeSeriesData decoded = TimeSeriesRequestManager.decodePixiePayload("nan.series", b64);

        double[] actual = decoded.getValues();
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            // Use raw bit comparison so NaN and ±0.0 distinctions are preserved.
            assertEquals(Double.doubleToRawLongBits(expected[i]),
                         Double.doubleToRawLongBits(actual[i]),
                         "bit mismatch at index " + i);
        }
    }

    @Test
    void roundTrip_reconstructsCorrectTimestamps() throws Exception {
        double[] vals = {10.0, 20.0, 30.0, 40.0};
        String b64 = encode(vals);

        TimeSeriesData decoded = TimeSeriesRequestManager.decodePixiePayload("ts.series", b64);

        long[] timestamps = decoded.getTimestamps();
        assertEquals(vals.length, timestamps.length);
        for (int i = 0; i < vals.length; i++) {
            long expectedMs = (START_EPOCH_SEC + i * TIMESTEP_SECONDS) * 1000L;
            assertEquals(expectedMs, timestamps[i], "timestamp mismatch at index " + i);
        }
    }

    @Test
    void roundTrip_pre1970Timestamps() throws Exception {
        // 1889-01-01T00:00:00Z — negative Unix seconds, the case the IDE was getting wrong.
        long startEpochSec = LocalDateTime.of(1889, 1, 1, 0, 0)
            .toEpochSecond(ZoneOffset.UTC);
        double[] vals = {1.0, 2.0, 3.0};
        String b64 = encode(vals, startEpochSec);

        TimeSeriesData decoded = TimeSeriesRequestManager.decodePixiePayload("old.series", b64);
        long[] timestamps = decoded.getTimestamps();
        assertEquals(vals.length, timestamps.length);
        for (int i = 0; i < vals.length; i++) {
            long expectedMs = (startEpochSec + i * TIMESTEP_SECONDS) * 1000L;
            assertEquals(expectedMs, timestamps[i],
                "timestamp mismatch at index " + i + " (year ~1889)");
        }
    }

    @Test
    void roundTrip_largeSeriesIsLossless() throws Exception {
        int n = 100_000;
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            vals[i] = Math.sin(i * 0.001) * 100.0;
        }

        String b64 = encode(vals);
        TimeSeriesData decoded = TimeSeriesRequestManager.decodePixiePayload("big.series", b64);

        assertEquals(n, decoded.getValues().length);
        for (int i = 0; i < n; i++) {
            assertEquals(vals[i], decoded.getValues()[i], 0.0,
                "value mismatch at index " + i);
        }
    }

    /** Encode the given values via Gorilla + base64, mimicking what the CLI sends.
     *  Timestamps are written in Kalix's offset-binary u64 form (Rust's wrap_to_u64). */
    private static String encode(double[] values) throws Exception {
        return encode(values, START_EPOCH_SEC);
    }

    private static String encode(double[] values, long startEpochSec) throws Exception {
        List<TimeValueDouble> series = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            long signed = startEpochSec + i * TIMESTEP_SECONDS;
            // Kalix's wrap_to_u64: bits are XOR'd with 2^63, then read back as a Java long.
            long wrappedBits = signed ^ Long.MIN_VALUE;
            series.add(new TimeValueDouble(wrappedBits, values[i]));
        }
        GorillaCompressor codec = new GorillaCompressor(TIMESTEP_SECONDS);
        byte[] compressed = codec.compressDouble(series);
        return Base64.getEncoder().encodeToString(compressed);
    }
}
