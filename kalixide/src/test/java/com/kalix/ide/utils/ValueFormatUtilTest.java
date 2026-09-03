package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the hover-hint value rules from issue #363: plain digits in the everyday range,
 * scientific notation at the extremes, and never a padded or summarised number.
 */
class ValueFormatUtilTest {

    // ---- the issue's own examples ----

    @Test
    void wholeNumbersShowEveryDigitWithoutSeparators() {
        assertEquals("123456", ValueFormatUtil.formatDataValue(123456));
        assertEquals("1000000000", ValueFormatUtil.formatDataValue(1e9));
    }

    @Test
    void threeDecimalPlacesWhenThatIsMorePreciseThanFiveSignificantFigures() {
        assertEquals("98.765", ValueFormatUtil.formatDataValue(98.765));
        assertEquals("123456.789", ValueFormatUtil.formatDataValue(123456.789));
        assertEquals("12345.679", ValueFormatUtil.formatDataValue(12345.6789));
    }

    @Test
    void fiveSignificantFiguresWhenThatIsMorePreciseThanThreeDecimalPlaces() {
        assertEquals("8.7654", ValueFormatUtil.formatDataValue(8.7654));
        assertEquals("8.7654", ValueFormatUtil.formatDataValue(8.76543));
        assertEquals("0.012346", ValueFormatUtil.formatDataValue(0.0123456));
    }

    @Test
    void tinyMagnitudesUseScientificNotationToFiveSignificantFigures() {
        // Issue #363 wrote this example as 1.2345e-4, but that truncates; the sixth figure is 6.
        assertEquals("1.2346e-4", ValueFormatUtil.formatDataValue(0.00012345678));
        assertEquals("1.2345e-4", ValueFormatUtil.formatDataValue(0.00012345));
        assertEquals("9.99e-4", ValueFormatUtil.formatDataValue(0.000999));
    }

    @Test
    void hugeMagnitudesUseScientificNotationToFiveSignificantFigures() {
        assertEquals("1.2346e9", ValueFormatUtil.formatDataValue(1234567890));
        assertEquals("1e10", ValueFormatUtil.formatDataValue(1e10));
        assertEquals("-3.5e12", ValueFormatUtil.formatDataValue(-3.5e12));
    }

    // ---- boundaries and edges ----

    @Test
    void thresholdsAreStrict() {
        assertEquals("0.001", ValueFormatUtil.formatDataValue(0.001));
        assertEquals("1000000000", ValueFormatUtil.formatDataValue(1_000_000_000));
    }

    @Test
    void trailingZerosAreNeverPadded() {
        assertEquals("1.5", ValueFormatUtil.formatDataValue(1.5));
        assertEquals("100.5", ValueFormatUtil.formatDataValue(100.5));
        assertEquals("0.25", ValueFormatUtil.formatDataValue(0.25));
    }

    @Test
    void negativesKeepTheirSign() {
        assertEquals("-98.765", ValueFormatUtil.formatDataValue(-98.7654));
        assertEquals("-123456", ValueFormatUtil.formatDataValue(-123456));
        assertEquals("-1.2346e-4", ValueFormatUtil.formatDataValue(-0.00012345678));
    }

    @Test
    void zeroAndNonFiniteValuesAreSpelledOut() {
        assertEquals("0", ValueFormatUtil.formatDataValue(0.0));
        assertEquals("0", ValueFormatUtil.formatDataValue(-0.0));
        assertEquals("NaN", ValueFormatUtil.formatDataValue(Double.NaN));
        assertEquals("Infinity", ValueFormatUtil.formatDataValue(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", ValueFormatUtil.formatDataValue(Double.NEGATIVE_INFINITY));
    }
}
