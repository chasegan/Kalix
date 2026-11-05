package com.kalix.ide.flowviz.transform;

/**
 * What a series' timestamp slot holds after the plot-type transform: the X-axis domain
 * it is plotted against. Declared by {@link PlotType#xAxisType()}.
 */
public enum XAxisType {
    /** Temporal X-axis with timestamps (default for most plot types). */
    TIME,

    /** Percentile X-axis for exceedance probability plots (0-100%). */
    PERCENTILE,

    /** Count-based X-axis for iteration/evaluation counts, event counts, etc. */
    COUNT,

    /** Numeric X-axis for arbitrary value domains (e.g., double mass cumulative reference). */
    NUMERIC
}
