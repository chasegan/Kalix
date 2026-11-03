package com.kalix.ide.flowviz.transform;

import com.kalix.ide.flowviz.rendering.XAxisType;

/**
 * Plot type transformations applied after aggregation.
 * These transformations modify the data values and may change axis labels.
 */
public enum PlotType {
    /** Original values - no transformation. */
    VALUES("Values", "Value", XAxisType.TIME, null, false),

    /** Cumulative sum of values over time. */
    CUMULATIVE("Cumulative Vals", "Cumulative Value", XAxisType.TIME, null, true),

    /** Difference from reference series (first selected series). */
    DIFFERENCE("Difference", "Difference from Reference", XAxisType.TIME, null, false),

    /** Cumulative difference from reference series. */
    CUMULATIVE_DIFFERENCE("Cumulative Diff", "Cumulative Difference", XAxisType.TIME, null, true),

    /** Exceedance probability distribution. */
    EXCEEDANCE("Exceedance", "Exceedance Probability (%)", XAxisType.PERCENTILE, "percentile", true),

    /** Double mass curve: cumulative reference on X-axis vs cumulative series on Y-axis. */
    DOUBLE_MASS("Double Mass", "Cumulative Value", XAxisType.NUMERIC, "cumulative reference", true),

    /** Residual mass curve: cumulative deviation from mean over time. */
    RESIDUAL_MASS("Residual Mass", "Residual Mass", XAxisType.TIME, null, true);

    private final String displayName;
    private final String yAxisLabel;
    private final XAxisType xAxisType;
    private final String xAxisLabel;
    private final boolean dataMaskDefault;

    /**
     * @param displayName UI label
     * @param yAxisLabel Y-axis label appropriate for this plot type
     * @param xAxisType what the transformed series' timestamp slot holds
     * @param xAxisLabel the name of the X quantity, as the data table heads its first column;
     *                   null when X is time (the table then says "date" or "datetime")
     * @param dataMaskDefault whether overlapping-data masking should start on for this plot type
     */
    PlotType(String displayName, String yAxisLabel, XAxisType xAxisType, String xAxisLabel,
             boolean dataMaskDefault) {
        this.displayName = displayName;
        this.yAxisLabel = yAxisLabel;
        this.xAxisType = xAxisType;
        this.xAxisLabel = xAxisLabel;
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
     * The X-axis domain this plot type plots against: what {@link PlotTypeTransformer}
     * leaves in the timestamp slot. TIME holds real timestamps; PERCENTILE and NUMERIC
     * hold a value scaled by the transformer's constants. Every reader of the slot (the
     * axis, the coordinate readout, the data table) decides by this, so a new plot type
     * on an existing domain needs no change there.
     */
    public XAxisType xAxisType() {
        return xAxisType;
    }

    /**
     * The name of the X quantity for a plot type whose X is not time, as the data table
     * heads its first column ("percentile", "cumulative reference"); null when X is time.
     */
    public String xAxisLabel() {
        return xAxisLabel;
    }

    /**
     * Whether overlapping-data masking should default to on when this plot type is selected
     * from the toolbar dropdown (issue #369). This flag concerns the overlapping-data mask
     * ({@code MaskMode}) only; other filter dimensions (e.g. a seasonal filter, issue #235)
     * are deliberately independent of plot type and of this flag.
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
}
