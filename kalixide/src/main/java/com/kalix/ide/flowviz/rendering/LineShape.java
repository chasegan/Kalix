package com.kalix.ide.flowviz.rendering;

/**
 * Plot-wide shape of the line joining consecutive values (context menu > Line shape). Independently
 * controlled to the {@link SeriesRenderMode} - this shapes the lines a series' mode draws, and
 * does not affect {@link SeriesRenderMode#POINTS} render mode.
 */
public enum LineShape {
    /// Straight segments from each value to the next (default).
    STRAIGHT("Straight"),

    /// Each value held flat from its own timestamp to the start of the next time step.
    STEPPED("Stepped");

    private final String displayName;

    LineShape(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
