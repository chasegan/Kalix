package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.flowviz.transform.YAxisScale;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link AxisLimitCodec}, the text &lt;-&gt; value codec behind the axis copy/paste
 * commands and the Set-axis-limits dialog. The load-bearing property is that
 * {@code parse(format(v)) == v} for every axis type, since that is what makes a copied
 * pair paste back unchanged.
 */
class AxisLimitCodecTest {

    private static final long MIDNIGHT = utc(2024, 1, 15, 0, 0, 0);
    private static final long AFTERNOON = utc(2024, 1, 15, 14, 30, 0);

    private static long utc(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    // ---- TIME ----

    @Test
    void timeFormatsMidnightAsDateOnly() {
        assertEquals("2024-01-15", AxisLimitCodec.formatX(MIDNIGHT, XAxisType.TIME));
    }

    @Test
    void timeFormatsOtherInstantsAsIsoDatetime() {
        assertEquals("2024-01-15T14:30:00", AxisLimitCodec.formatX(AFTERNOON, XAxisType.TIME));
    }

    @Test
    void timeRoundTripsAtBothResolutions() {
        for (long value : new long[]{ MIDNIGHT, AFTERNOON }) {
            assertEquals(value, AxisLimitCodec.parseX(AxisLimitCodec.formatX(value, XAxisType.TIME), XAxisType.TIME));
        }
    }

    /**
     * Pan and zoom leave viewport bounds on arbitrary millisecond values; the codec
     * exchanges TIME bounds at second resolution, so the fraction is dropped rather than
     * emitted in a form the parser refuses.
     */
    @Test
    void timeSubSecondBoundFormatsAtSecondResolutionAndReparses() {
        long withMillis = AFTERNOON + 123L;
        String text = AxisLimitCodec.formatX(withMillis, XAxisType.TIME);
        assertEquals("2024-01-15T14:30:00", text);
        assertEquals(AFTERNOON, AxisLimitCodec.parseX(text, XAxisType.TIME));
    }

    @Test
    void timeAcceptsDayFirstAndUnpaddedDates() {
        assertEquals(MIDNIGHT, AxisLimitCodec.parseX("15/1/2024", XAxisType.TIME));
        assertEquals(AFTERNOON, AxisLimitCodec.parseX("15-01-2024 14:30", XAxisType.TIME));
    }

    @Test
    void timeRejectsUnparseableTextWithGuidance() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> AxisLimitCodec.parseX("yesterday", XAxisType.TIME));
        assertEquals("Not a valid date/time: \"yesterday\" (expected 2024-02-01 or 01-02-2024, "
            + "optionally with HH:mm[:ss])", ex.getMessage());
    }

    // ---- PERCENTILE ----

    @Test
    void percentileFormatsWithPercentSignAndRoundTrips() {
        long encoded = Math.round(12.5 * PlotTypeTransformer.PERCENTILE_SCALE);
        assertEquals("12.5%", AxisLimitCodec.formatX(encoded, XAxisType.PERCENTILE));
        assertEquals(encoded, AxisLimitCodec.parseX("12.5%", XAxisType.PERCENTILE));
        assertEquals(encoded, AxisLimitCodec.parseX("12.5", XAxisType.PERCENTILE));
        assertEquals(encoded, AxisLimitCodec.parseX(" 12.5 % ", XAxisType.PERCENTILE));
    }

    @Test
    void percentileWholeNumbersFormatWithoutDecimalNoise() {
        assertEquals("50%", AxisLimitCodec.formatX(50 * PlotTypeTransformer.PERCENTILE_SCALE, XAxisType.PERCENTILE));
    }

    @Test
    void percentileRejectsNonFiniteInput() {
        for (String text : new String[]{ "NaN", "Infinity", "-Infinity", "NaN%" }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AxisLimitCodec.parseX(text, XAxisType.PERCENTILE), text);
            assertEquals("Not a valid percentile: \"" + text + "\"", ex.getMessage());
        }
    }

    // ---- NUMERIC ----

    @Test
    void numericRoundTripsThroughNumericScale() {
        long encoded = Math.round(1234.5678 * PlotTypeTransformer.NUMERIC_SCALE);
        assertEquals("1234.5678", AxisLimitCodec.formatX(encoded, XAxisType.NUMERIC));
        assertEquals(encoded, AxisLimitCodec.parseX("1234.5678", XAxisType.NUMERIC));
    }

    @Test
    void numericRejectsNonFiniteInput() {
        for (String text : new String[]{ "NaN", "Infinity" }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AxisLimitCodec.parseX(text, XAxisType.NUMERIC), text);
            assertEquals("Not a valid number: \"" + text + "\"", ex.getMessage());
        }
    }

    /**
     * Math.round saturates at Long.MAX_VALUE instead of failing, so a finite value whose
     * scaled form overflows a long would otherwise install a silently wrong bound.
     */
    @Test
    void scaledEncodingsRejectValuesTheLongCannotHold() {
        assertEquals("Not a valid number: \"1e13\"", assertThrows(IllegalArgumentException.class,
            () -> AxisLimitCodec.parseX("1e13", XAxisType.NUMERIC)).getMessage());
        assertEquals("Not a valid percentile: \"1e300%\"", assertThrows(IllegalArgumentException.class,
            () -> AxisLimitCodec.parseX("1e300%", XAxisType.PERCENTILE)).getMessage());
        assertEquals("Not a valid number: \"-1e13\"", assertThrows(IllegalArgumentException.class,
            () -> AxisLimitCodec.parseX("-1e13", XAxisType.NUMERIC)).getMessage());
        // Just inside the range still encodes.
        assertEquals(9_000_000_000_000_000_000L, AxisLimitCodec.parseX("9e12", XAxisType.NUMERIC));
    }

    // ---- COUNT ----

    @Test
    void countRoundTripsAsPlainInteger() {
        assertEquals("42", AxisLimitCodec.formatX(42L, XAxisType.COUNT));
        assertEquals(42L, AxisLimitCodec.parseX("42", XAxisType.COUNT));
    }

    @Test
    void countRejectsFractions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> AxisLimitCodec.parseX("4.2", XAxisType.COUNT));
        assertEquals("Not a valid integer count: \"4.2\"", ex.getMessage());
    }

    // ---- blank X ----

    @Test
    void blankXIsRejectedForEveryAxisType() {
        for (XAxisType type : XAxisType.values()) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AxisLimitCodec.parseX("   ", type), type.name());
            assertEquals("X value cannot be blank.", ex.getMessage());
        }
    }

    // ---- Y ----

    @Test
    void yFormatsWholeNumbersWithoutDecimalNoise() {
        assertEquals("0", AxisLimitCodec.formatY(0.0));
        assertEquals("-300", AxisLimitCodec.formatY(-300.0));
        assertEquals("0.25", AxisLimitCodec.formatY(0.25));
    }

    @Test
    void yRoundTripsFullDoublePrecision() {
        for (double value : new double[]{ 0.1 + 0.2, 1e-9, 123456789.123456789, 1e15, 1e300 }) {
            assertEquals(value, AxisLimitCodec.parseY(AxisLimitCodec.formatY(value)));
        }
    }

    @Test
    void yRejectsNonFiniteBlankAndText() {
        assertEquals("Y value cannot be blank.",
            assertThrows(IllegalArgumentException.class, () -> AxisLimitCodec.parseY("")).getMessage());
        for (String text : new String[]{ "NaN", "Infinity", "ten" }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AxisLimitCodec.parseY(text), text);
            assertEquals("Not a valid number: \"" + text + "\"", ex.getMessage());
        }
    }

    // ---- pairs ----

    @Test
    void splitPairTrimsAroundTheComma() {
        assertArrayEquals(new String[]{ "a", "b" }, AxisLimitCodec.splitPair("  a ,b  "));
    }

    @Test
    void splitPairRejectsAnythingButTwoValues() {
        for (String text : new String[]{ "", "a", "a, b, c" }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AxisLimitCodec.splitPair(text), text);
            assertEquals("Expected two comma-separated values but received \"" + text + "\"", ex.getMessage());
        }
    }

    @Test
    void pairsRoundTripThroughFormatAndSplit() {
        assertEquals("2024-01-15, 2024-01-15T14:30:00", AxisLimitCodec.formatPair(
            AxisLimitCodec.formatX(MIDNIGHT, XAxisType.TIME), AxisLimitCodec.formatX(AFTERNOON, XAxisType.TIME)));
        assertArrayEquals(new long[]{ MIDNIGHT, AFTERNOON },
            AxisLimitCodec.parseXLimits("2024-01-15, 2024-01-15T14:30:00", XAxisType.TIME));
        assertArrayEquals(new double[]{ -1.5, 2.0 }, AxisLimitCodec.parseYLimits("-1.5, 2"));
    }

    @Test
    void viewportLimitsFormatInTheViewportsOwnAxisUnits() {
        ViewPort time = new ViewPort(MIDNIGHT, AFTERNOON, 0.0, 12.5, 0, 0, 100, 100, YAxisScale.LINEAR, XAxisType.TIME);
        assertEquals("2024-01-15, 2024-01-15T14:30:00", AxisLimitCodec.formatXLimits(time));
        assertEquals("0, 12.5", AxisLimitCodec.formatYLimits(time));

        ViewPort percentile = new ViewPort(0L, 100 * PlotTypeTransformer.PERCENTILE_SCALE, 0.0, 1.0,
            0, 0, 100, 100, YAxisScale.LINEAR, XAxisType.PERCENTILE);
        assertEquals("0%, 100%", AxisLimitCodec.formatXLimits(percentile));
    }
}
