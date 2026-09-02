package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.utils.TimeFormatUtil;

import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;

/**
 * Text &lt;-&gt; value codec for axis limits, in the axis' own units: the pure half of the
 * "Copy/Paste X axis", "Copy/Paste Y axis" and "Set axis limits…" commands.
 *
 * <p>X bounds are viewport longs: real epoch millis on a {@link XAxisType#TIME} axis, and
 * fake-timestamp encodings on the others (percentile &times;
 * {@link PlotTypeTransformer#PERCENTILE_SCALE}, value &times;
 * {@link PlotTypeTransformer#NUMERIC_SCALE}, raw counts). Formatting a percentile or
 * numeric bound as a date would render it as a meaningless 1970 instant, so every
 * {@code format} here is the exact inverse of its {@code parse} for the given axis type,
 * and a copied pair pastes back unchanged. One documented exception: TIME bounds are
 * exchanged at second resolution, so two bounds within the same second collapse to one
 * instant and {@link ViewPort#validateBounds} rejects them on paste. A sub-second
 * viewport is not a hydrological concern.</p>
 *
 * <p>Every failure is an {@link IllegalArgumentException} carrying the message shown to
 * the user, so callers need a single catch. This is also the one gate against input the
 * encodings cannot hold: {@code Double.parseDouble} accepts {@code "NaN"} and
 * {@code "Infinity"}, and {@code Math.round} would silently turn those into {@code 0}
 * and {@code Long.MAX_VALUE}, as it would any finite value whose scaled form overflows
 * a long. {@link ViewPort#validateBounds} then only has to check the ordering of each
 * pair.</p>
 */
public final class AxisLimitCodec {

    private AxisLimitCodec() {}

    private static final long MS_PER_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long SECONDS_PER_DAY = TimeUnit.DAYS.toSeconds(1);
    private static final String PAIR_SEPARATOR = ", ";

    // ---- pairs ----

    /** The current X limits of {@code viewport} as {@code "lower, upper"} in the axis' units. */
    public static String formatXLimits(ViewPort viewport) {
        XAxisType xAxisType = viewport.getXAxisType();
        return formatPair(formatX(viewport.getStartTimeMs(), xAxisType), formatX(viewport.getEndTimeMs(), xAxisType));
    }

    /** The current Y limits of {@code viewport} as {@code "lower, upper"}. */
    public static String formatYLimits(ViewPort viewport) {
        return formatPair(formatY(viewport.getMinValue()), formatY(viewport.getMaxValue()));
    }

    /**
     * Parses {@code "lower, upper"} into two viewport X values for {@code xAxisType}.
     *
     * @throws IllegalArgumentException if the text is not two comma-separated values, or
     *                                  either value is not valid for the axis type
     */
    public static long[] parseXLimits(String text, XAxisType xAxisType) {
        String[] parts = splitPair(text);
        return new long[]{ parseX(parts[0], xAxisType), parseX(parts[1], xAxisType) };
    }

    /**
     * Parses {@code "lower, upper"} into two Y values.
     *
     * @throws IllegalArgumentException if the text is not two comma-separated values, or
     *                                  either value is not a finite number
     */
    public static double[] parseYLimits(String text) {
        String[] parts = splitPair(text);
        return new double[]{ parseY(parts[0]), parseY(parts[1]) };
    }

    /** Joins two formatted bounds the way {@link #splitPair} splits them. */
    public static String formatPair(String lower, String upper) {
        return lower + PAIR_SEPARATOR + upper;
    }

    /**
     * Splits {@code "lower, upper"} into its two trimmed halves.
     *
     * @throws IllegalArgumentException if {@code text} does not hold exactly two
     *                                  comma-separated values
     */
    public static String[] splitPair(String text) {
        String[] parts = text == null ? new String[0] : text.split("\\s*,\\s*");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                String.format("Expected two comma-separated values but received \"%s\"", text));
        }
        return new String[]{ parts[0].trim(), parts[1].trim() };
    }

    // ---- single values ----

    /**
     * Parses one X bound in the units of {@code xAxisType}: a date/time (see
     * {@link TimeFormatUtil#parseFlexible}) for TIME, a percentage with optional
     * {@code %} for PERCENTILE, an integer for COUNT, a number for NUMERIC.
     *
     * @throws IllegalArgumentException if the text is blank or not valid for the axis type
     */
    public static long parseX(String text, XAxisType xAxisType) {
        String trimmed = requireText(text, "X");
        return switch (xAxisType) {
            case PERCENTILE -> {
                String number = trimmed.endsWith("%") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
                yield encodeScaled(number, PlotTypeTransformer.PERCENTILE_SCALE,
                    "Not a valid percentile: \"" + trimmed + "\"");
            }
            case COUNT -> {
                try {
                    yield Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a valid integer count: \"" + trimmed + "\"");
                }
            }
            case NUMERIC -> encodeScaled(trimmed, PlotTypeTransformer.NUMERIC_SCALE,
                "Not a valid number: \"" + trimmed + "\"");
            case TIME -> {
                try {
                    yield TimeFormatUtil.parseFlexible(trimmed);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Not a valid date/time: \"" + trimmed
                        + "\" (expected 2024-02-01 or 01-02-2024, optionally with HH:mm[:ss])");
                }
            }
        };
    }

    /**
     * Formats one X bound in the units of {@code xAxisType}; the inverse of
     * {@link #parseX}. TIME bounds format as a bare date when midnight-aligned and as a
     * full ISO datetime otherwise, both of which {@link TimeFormatUtil#parseFlexible} reads.
     */
    public static String formatX(long value, XAxisType xAxisType) {
        return switch (xAxisType) {
            case PERCENTILE -> formatNumber((double) value / PlotTypeTransformer.PERCENTILE_SCALE) + "%";
            case COUNT -> String.valueOf(value);
            case NUMERIC -> formatNumber((double) value / PlotTypeTransformer.NUMERIC_SCALE);
            case TIME -> TimeFormatUtil.formatForStepSize(value, value % MS_PER_DAY == 0 ? SECONDS_PER_DAY : 1L);
        };
    }

    /**
     * Parses one Y bound.
     *
     * @throws IllegalArgumentException if the text is blank or not a finite number
     */
    public static double parseY(String text) {
        String trimmed = requireText(text, "Y");
        return parseFinite(trimmed, "Not a valid number: \"" + trimmed + "\"");
    }

    /** Formats one Y bound; the inverse of {@link #parseY}. */
    public static String formatY(double value) {
        return formatNumber(value);
    }

    // ---- helpers ----

    private static String requireText(String text, String axis) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(axis + " value cannot be blank.");
        }
        return text.trim();
    }

    private static double parseFinite(String number, String errorMessage) {
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }

    /**
     * Parses a number and encodes it as {@code value * scale}, rejecting anything the
     * long encoding cannot hold: {@code Math.round} saturates at {@code Long.MAX_VALUE}
     * rather than failing, which would install a silently wrong bound.
     */
    private static long encodeScaled(String number, long scale, String errorMessage) {
        double scaled = parseFinite(number, errorMessage) * scale;
        if (!Double.isFinite(scaled) || Math.abs(scaled) >= 0x1p63) {
            throw new IllegalArgumentException(errorMessage);
        }
        return Math.round(scaled);
    }

    /**
     * Formats a double for editing without scientific notation or a spurious {@code .0}
     * on whole numbers. Both branches print something {@code Double.parseDouble} reads
     * back to the same value, which the Set-axis-limits dialog relies on to tell an
     * untouched Y field from an edited one.
     */
    private static String formatNumber(double value) {
        if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
