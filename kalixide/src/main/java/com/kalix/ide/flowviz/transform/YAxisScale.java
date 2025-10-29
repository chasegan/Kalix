package com.kalix.ide.flowviz.transform;

/**
 * Y-axis scale transformations for plot display.
 */
public enum YAxisScale {
    /** Linear scale - no transformation. */
    LINEAR("Linear"),

    /** Logarithmic scale (base 10). */
    LOG("Log"),

    /** Square root scale. */
    SQRT("Sqrt");

    private final String displayName;

    YAxisScale(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
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
