package com.kalix.ide.flowviz.style;

import java.awt.Graphics2D;

/**
 * The canonical marker for a plotted series — a filled dot in the series' palette colour.
 *
 * <p>Single source of truth so a series' marker looks identical everywhere it appears: the
 * legend Key, the style picker, and gap-aware orphan markers. The colour always comes from the
 * series' resolved {@link LineStyle} — i.e. its palette slot — so the marker tracks the line.</p>
 */
public final class SeriesMarker {

    /** Diameter of the filled-dot marker, in pixels. */
    public static final int DIAMETER = 4;

    private SeriesMarker() {
    }

    /** Paints the marker centred at ({@code centerX}, {@code centerY}) in the style's colour. */
    public static void paint(Graphics2D g, LineStyle style, int centerX, int centerY) {
        g.setColor(style.color());
        g.fillOval(centerX - DIAMETER / 2, centerY - DIAMETER / 2, DIAMETER, DIAMETER);
    }
}
