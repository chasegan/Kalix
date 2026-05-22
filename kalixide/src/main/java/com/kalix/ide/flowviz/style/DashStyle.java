package com.kalix.ide.flowviz.style;

/**
 * Dash pattern for a plotted line.
 *
 * <p>The pixel pattern is exposed as a dash array suitable for
 * {@link java.awt.BasicStroke}; {@link #SOLID} has none. Dash is one half of a
 * {@link StrokeStyle} (the other being thickness).</p>
 *
 * <p>Note: the dense-data LOD render path deliberately ignores dash and always
 * draws solid — see {@code TimeSeriesRenderer.renderLOD}.</p>
 */
public enum DashStyle {
    /** Continuous line, no dash. */
    SOLID("Solid", null),

    /** Evenly spaced dashes. */
    DASHED("Dashed", new float[]{8f, 6f}),

    /** Round dots (relies on a round line cap to render as dots). */
    DOTTED("Dotted", new float[]{1.5f, 4f});

    private final String displayName;
    private final float[] dashArray;

    DashStyle(String displayName, float[] dashArray) {
        this.displayName = displayName;
        this.dashArray = dashArray;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns a fresh copy of the dash array, or {@code null} for {@link #SOLID}.
     *
     * <p>A copy is returned on every call so neither {@code BasicStroke} nor any
     * caller can mutate this enum's shared internal array.</p>
     */
    public float[] dashArray() {
        return dashArray == null ? null : dashArray.clone();
    }
}
