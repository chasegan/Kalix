package com.kalix.ide.flowviz.transform;

/**
 * Plot type transformations applied after aggregation.
 * These transformations modify the data values and may change axis labels.
 */
public enum PlotType {
    /** Original values - no transformation. */
    VALUES("Values", "Value", false),

    /** Cumulative sum of values over time. */
    CUMULATIVE("Cumulative Values", "Cumulative Value", true),

    /** Difference from reference series (first selected series). */
    DIFFERENCE("Difference", "Difference from Reference", false),

    /** Cumulative difference from reference series. */
    CUMULATIVE_DIFFERENCE("Cumulative Difference", "Cumulative Difference", true),

    /** Exceedance probability distribution. */
    EXCEEDANCE("Exceedance", "Exceedance Probability (%)", true),

    /** Double mass curve: cumulative reference on X-axis vs cumulative series on Y-axis. */
    DOUBLE_MASS("Double Mass", "Cumulative Value", true),

    /** Residual mass curve: cumulative deviation from mean over time. */
    RESIDUAL_MASS("Residual Mass", "Residual Mass", true);

    private final String displayName;
    private final String yAxisLabel;
    private final boolean dataMaskDefault;

    /**
     * @param displayName UI label
     * @param yAxisLabel Y-axis label appropriate for this plot type
     * @param dataMaskDefault whether overlapping-data masking should start on for this plot type
     */
    PlotType(String displayName, String yAxisLabel, boolean dataMaskDefault) {
        this.displayName = displayName;
        this.yAxisLabel = yAxisLabel;
        this.dataMaskDefault = dataMaskDefault;
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
     * Whether overlapping-data masking should default to on when this plot type is selected.
     */
    public boolean isDataMaskDefault() {
        return dataMaskDefault;
    }

    /**
     * Returns true if this plot type requires a reference series.
     * Reference series is the first selected series.
     */
    public boolean requiresReferenceSeries() {
        return this == DIFFERENCE || this == CUMULATIVE_DIFFERENCE || this == DOUBLE_MASS;
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
