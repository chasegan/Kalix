package com.kalix.ide.flowviz.transform;

import java.util.OptionalDouble;

/**
 * Y-axis scale transformations for plot display.
 */
public enum YAxisScale {
    /** Linear scale - no transformation. */
    LINEAR("Linear"),

    /** Logarithmic scale (base 10). */
    LOG("Log"),

    /** Square root scale. */
    SQRT("Sqrt"),

    /**
     * Symmetric log - linear on [-L, L] and log_10 outside it, with L = 10.
     * Inspired by, but not the same as, matplotlib's symlog. This implementation
     * keeps log10(10**n) on integral positions, and the linear zone on exactly one
     * decade of transformed space, so the axis reads as -100, -10, 0, 10, 100.
     */
    SYMLOG("Symlog")
    ;

    /** SYMLOG linear threshold: |y| below this is drawn linearly. */
    private static final double SYMLOG_THRESHOLD = 10.0;

    /** Transformed position of the threshold - where the two SYMLOG branches meet. */
    private static final double SYMLOG_LOG_THRESHOLD = Math.log10(SYMLOG_THRESHOLD);

    /**
     * Slope of the SYMLOG linear branch. Derived, not chosen: continuity at +/-L
     * requires L * slope == log10(L), so the linear zone occupies exactly one decade
     * of transformed space either side of zero.
     */
    private static final double SYMLOG_LINEAR_SLOPE = SYMLOG_LOG_THRESHOLD / SYMLOG_THRESHOLD;

    private final String displayName;

    YAxisScale(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * The linear-region threshold L, for scales that have one - the magnitude below which
     * values are drawn linearly rather than logarithmically. Empty for scales with no such
     * region, which is every other scale at present.
     *
     * <p>Exposed so renderers can mark where the axis changes behaviour without holding a
     * second copy of the constant; this enum stays the sole owner of each scale's parameters.
     * An exhaustive switch, so a new scale has to decide here whether it has a region to mark.</p>
     */
    public OptionalDouble linearThreshold() {
        return switch (this) {
            case SYMLOG -> OptionalDouble.of(SYMLOG_THRESHOLD);
            case LINEAR, LOG, SQRT -> OptionalDouble.empty();
        };
    }

    /**
     * Exclusive lower bound of this scale's domain: values at or below it have no position
     * on the axis, and {@link #transform} returns NaN for them. NEGATIVE_INFINITY for scales
     * defined everywhere, so {@code value <= domainFloor()} rejects no finite value and a
     * per-point domain check costs one compare whatever the scale.
     */
    public double domainFloor() {
        return switch (this) {
            case LOG -> 0.0;                                          // log10 needs y > 0
            case LINEAR, SQRT, SYMLOG -> Double.NEGATIVE_INFINITY;    // defined everywhere
        };
    }

    /**
     * Applies the scale transformation to a Y-value.
     *
     * @param y The original Y-value
     * @return Transformed Y-value, or NaN if invalid for this scale
     */
    public double transform(double y) {
        return switch(this) {
            case LINEAR -> y;
            case LOG -> y > 0 ? Math.log10(y) : Double.NaN;
            case SQRT -> {
                // Signed sqrt: sign(x) × √|x|
                // Standard approach used by matplotlib and ggplot2
                // Allows viewport to pan through negative space
                if (y >= 0) {
                    yield Math.sqrt(y);
                } else {
                    yield -Math.sqrt(-y);
                }
            }
            case SYMLOG -> {
                if (y >= SYMLOG_THRESHOLD) {
                    yield Math.log10(y);
                } else if (y <= -SYMLOG_THRESHOLD) {
                    yield -Math.log10(-y);
                } else {
                    yield y * SYMLOG_LINEAR_SLOPE;
                }
            }
        };
    }

    /**
     * Inverse transformation for converting display coordinates back to data values.
     *
     * @param transformedY The transformed Y-value
     * @return Original Y-value
     */
    public double inverseTransform(double transformedY) {
        return switch(this) {
            case LINEAR -> transformedY;
            case LOG -> Math.pow(10, transformedY);
            case SQRT -> {
                // Inverse of signed sqrt: sign(t) × t²
                if (transformedY >= 0) {
                    yield transformedY * transformedY;
                } else {
                    yield -(transformedY * transformedY);
                }
            }
            case SYMLOG -> {
                if (transformedY >= SYMLOG_LOG_THRESHOLD) {
                    yield Math.pow(10, transformedY);
                } else if (transformedY <= -SYMLOG_LOG_THRESHOLD) {
                    yield -Math.pow(10, -transformedY);
                } else {
                    yield transformedY / SYMLOG_LINEAR_SLOPE;
                }
            }
        };
    }

    /**
     * Parses display name to enum value.
     */
    public static YAxisScale fromDisplayName(String displayName) {
        for (YAxisScale scale : values()) {
            if (scale.displayName.equals(displayName)) {
                return scale;
            }
        }
        return LINEAR;
    }
}
