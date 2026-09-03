package com.kalix.ide.utils;

import java.util.Locale;

/**
 * Centralised formatting of a single data value for human reading: the plot hover hint and
 * the statistics table.
 * The counterpart of {@link TimeFormatUtil} for the value axis.
 *
 * <p>The rules (from issue #363) favour showing the number the modeller actually has,
 * rather than a rounded summary of it:
 * <ul>
 *   <li>magnitude above {@value #SCIENTIFIC_ABOVE} or below {@value #SCIENTIFIC_BELOW}
 *       — scientific notation to 5 significant figures ({@code 1.2345e-4});</li>
 *   <li>everything else — plain digits, no thousands separators, to 3 decimal places or
 *       5 significant figures, whichever is the more precise ({@code 123456.789},
 *       {@code 98.765}, {@code 8.7654}, {@code 0.012346}).</li>
 * </ul>
 * Trailing zeros never pad the result: the precision rules bound the rounding, they do not
 * dictate a width, so a whole number reads as {@code 123456}, not {@code 123456.000}.
 */
public final class ValueFormatUtil {

    /** Magnitudes strictly above this switch to scientific notation. */
    public static final double SCIENTIFIC_ABOVE = 1e9;
    /** Non-zero magnitudes strictly below this switch to scientific notation. */
    public static final double SCIENTIFIC_BELOW = 1e-3;

    private static final int SIGNIFICANT_FIGURES = 5;
    private static final int MIN_DECIMAL_PLACES = 3;

    private ValueFormatUtil() {}

    /**
     * Formats a data value for display. Zero is {@code "0"}; NaN and the infinities are
     * spelled out ({@code "NaN"}, {@code "Infinity"}, {@code "-Infinity"}).
     */
    public static String formatDataValue(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        if (value == 0.0) {
            return "0";
        }
        double magnitude = Math.abs(value);
        if (magnitude > SCIENTIFIC_ABOVE || magnitude < SCIENTIFIC_BELOW) {
            return scientific(value);
        }
        return fixed(value, magnitude);
    }

    /**
     * Plain digits. Decimal places are the greater of {@link #MIN_DECIMAL_PLACES} and
     * however many it takes to show {@link #SIGNIFICANT_FIGURES} significant figures.
     * {@code Math.log10} is exact at powers of ten, so the boundary cases land where
     * they read.
     */
    private static String fixed(double value, double magnitude) {
        int exponent = (int) Math.floor(Math.log10(magnitude));
        int decimalPlaces = Math.max(MIN_DECIMAL_PLACES, SIGNIFICANT_FIGURES - 1 - exponent);
        String text = String.format(Locale.ROOT, "%." + decimalPlaces + "f", value);
        return stripTrailingZeros(text);
    }

    /**
     * Scientific notation to {@link #SIGNIFICANT_FIGURES} significant figures, spelled
     * the way a modeller writes it: {@code 1.2345e-4} rather than Java's {@code 1.2345e-04},
     * and {@code 1.2345e9} rather than {@code 1.2345e+09}.
     */
    private static String scientific(double value) {
        String text = String.format(Locale.ROOT, "%." + (SIGNIFICANT_FIGURES - 1) + "e", value);
        int e = text.indexOf('e');
        String mantissa = stripTrailingZeros(text.substring(0, e));
        int exponent = Integer.parseInt(text.substring(e + 1));
        return mantissa + "e" + exponent;
    }

    /** Removes trailing zeros after the decimal point, and the point itself if nothing remains. */
    private static String stripTrailingZeros(String text) {
        if (text.indexOf('.') < 0) {
            return text;
        }
        int end = text.length();
        while (text.charAt(end - 1) == '0') {
            end--;
        }
        if (text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }
}
