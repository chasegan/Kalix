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
            case SQRT -> y >= 0 ? Math.sqrt(y) : Double.NaN;
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
            case SQRT -> transformedY * transformedY;
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
