package com.kalix.ide.flowviz.rendering;

/**
 * Defines the type of X-axis domain for plotting.
 */
public enum XAxisType {
    /** Temporal X-axis with timestamps (default for most plot types). */
    TIME,

    /** Percentile X-axis for exceedance probability plots (0-100%). */
    PERCENTILE,

    /** Count-based X-axis for iteration/evaluation counts, event counts, etc. */
    COUNT
}
