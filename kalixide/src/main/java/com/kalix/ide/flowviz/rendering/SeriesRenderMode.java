package com.kalix.ide.flowviz.rendering;

/**
 * Defines how a series should be rendered in the plot.
 */
public enum SeriesRenderMode {
    /** Draw lines connecting data points (default). */
    LINE,

    /** Draw only individual data points without connecting lines. */
    POINTS,

    /** Draw both lines and points. */
    LINE_AND_POINTS
}
