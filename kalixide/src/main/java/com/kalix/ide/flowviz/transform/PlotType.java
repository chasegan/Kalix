package com.kalix.ide.flowviz.transform;

/**
 * Plot type transformations applied after aggregation.
 * These transformations modify the data values and may change axis labels.
 */
public enum PlotType {
    /** Original values - no transformation. */
    VALUES("Values", "Value"),

    /** Cumulative sum of values over time. */
    CUMULATIVE("Cumulative Values", "Cumulative Value"),

    /** Difference from reference series (first selected series). */
    DIFFERENCE("Difference", "Difference from Reference"),

    /** Cumulative difference from reference series. */
    CUMULATIVE_DIFFERENCE("Cumulative Difference", "Cumulative Difference"),

    /** Exceedance probability distribution. */
    EXCEEDANCE("Exceedance", "Exceedance Probability (%)");

    private final String displayName;
    private final String yAxisLabel;

    PlotType(String displayName, String yAxisLabel) {
        this.displayName = displayName;
        this.yAxisLabel = yAxisLabel;
    }

    /**
     * Gets the display name for UI dropdowns.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the Y-axis label appropriate for this plot type.
     */
    public String getYAxisLabel() {
        return yAxisLabel;
    }

    /**
     * Returns true if this plot type requires a reference series.
     * Reference series is the first selected series.
     */
    public boolean requiresReferenceSeries() {
        return this == DIFFERENCE || this == CUMULATIVE_DIFFERENCE;
    }

    /**
     * Parses display name to enum value.
     */
    public static PlotType fromDisplayName(String displayName) {
        for (PlotType type : values()) {
            if (type.displayName.equals(displayName)) {
                return type;
            }
        }
        return VALUES;
    }
}
