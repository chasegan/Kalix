package com.kalix.ide.flowviz.stats;

/**
 * Defines how time series masking is applied when calculating statistics.
 * Masking filters data to only include timestamps where certain conditions are met.
 */
public enum MaskMode {
    /**
     * All series must have valid (non-NaN) data at a timestamp for it to be included.
     * Creates a single mask that applies to all series.
     * Best for comparing multiple series on equal footing.
     */
    ALL("All"),

    /**
     * Each series is individually masked with the reference series.
     * Only timestamps where both that series AND the reference have valid data are included.
     * Allows pairwise comparison with different valid timestamps per series.
     */
    EACH("Each"),

    /**
     * No masking is applied - all data points are used as-is.
     * Note: Bivariate statistics may be meaningless or impossible with this mode.
     */
    NONE("None");

    private final String displayName;

    MaskMode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Gets the display name for UI dropdowns.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parses a display name back to the enum value.
     * Used when reading from UI components.
     *
     * @param displayName The display name
     * @return The matching MaskMode, or ALL if not found
     */
    public static MaskMode fromDisplayName(String displayName) {
        for (MaskMode mode : values()) {
            if (mode.displayName.equals(displayName)) {
                return mode;
            }
        }
        return ALL; // Default fallback
    }

    @Override
    public String toString() {
        return displayName;
    }
}
