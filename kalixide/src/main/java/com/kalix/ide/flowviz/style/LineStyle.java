package com.kalix.ide.flowviz.style;

import java.awt.Color;

/**
 * The complete visual style of one plotted series.
 *
 * <p>Decomposes exactly as the palette editor's two columns do:</p>
 * <ul>
 *   <li>{@code color} — the line colour; its alpha channel carries opacity, so a
 *       translucent series is simply a {@link Color} with {@code alpha < 255}.</li>
 *   <li>{@code stroke} — a {@link StrokeStyle} bundling thickness and dash.</li>
 * </ul>
 *
 * <p>Immutable value type. One {@code LineStyle} occupies one slot of a
 * {@link PlotPalette}; a series resolves to a {@code LineStyle} at render time
 * via its slot index.</p>
 */
public record LineStyle(Color color, StrokeStyle stroke) {

    public LineStyle {
        if (color == null) {
            throw new IllegalArgumentException("color must not be null");
        }
        if (stroke == null) {
            throw new IllegalArgumentException("stroke must not be null");
        }
    }

    /** Returns a copy with the colour replaced; stroke unchanged. */
    public LineStyle withColor(Color newColor) {
        return new LineStyle(newColor, stroke);
    }

    /** Returns a copy with the stroke replaced; colour unchanged. */
    public LineStyle withStroke(StrokeStyle newStroke) {
        return new LineStyle(color, newStroke);
    }
}
