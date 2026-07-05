package com.kalix.ide.flowviz.rendering;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * The theme-supplied colour roles for FlowViz plots, resolved from the
 * {@code Kalix.plot.*} UIManager keys that every theme's properties file
 * defines (see {@code resources/themes/defaults.properties}).
 *
 * <p>Each renderer resolves one instance per paint (or on theme change) via
 * {@link #fromUIManager()} — never per draw call inside loops. Every lookup
 * falls back to the historical hard-coded light-plot colour, so plots render
 * unchanged under a look and feel that doesn't define the keys.
 */
public final class PlotColors {

    /** Plot canvas (and surrounding margin) background. */
    public final Color background;
    /** Grid lines aligned with axis ticks. */
    public final Color grid;
    /** Axis lines, tick marks, and the plot border. */
    public final Color axis;
    /** Tick labels and axis titles. */
    public final Color label;
    /** Legend panel background (rendered translucently by the legend). */
    public final Color legendBackground;
    /** Legend panel border. */
    public final Color legendBorder;
    /** Legend text. */
    public final Color legendForeground;
    /** Hover/crosshair readout box background (rendered translucently). */
    public final Color hoverBackground;
    /** Hover/crosshair readout box text. */
    public final Color hoverForeground;
    /** "No data loaded" / empty-state placeholder text. */
    public final Color emptyForeground;

    private PlotColors() {
        background = color("Kalix.plot.background", Color.WHITE);
        grid = color("Kalix.plot.grid", new Color(240, 240, 240));
        axis = color("Kalix.plot.axis", Color.BLACK);
        label = color("Kalix.plot.label", Color.BLACK);
        legendBackground = color("Kalix.plot.legendBackground", Color.WHITE);
        legendBorder = color("Kalix.plot.legendBorder", new Color(204, 204, 204));
        legendForeground = color("Kalix.plot.legendForeground", new Color(68, 68, 68));
        hoverBackground = color("Kalix.plot.hoverBackground", Color.WHITE);
        hoverForeground = color("Kalix.plot.hoverForeground", Color.BLACK);
        emptyForeground = color("Kalix.plot.emptyForeground", Color.LIGHT_GRAY);
    }

    /** Resolves the current theme's plot colours. Call once per paint, not per draw call. */
    public static PlotColors fromUIManager() {
        return new PlotColors();
    }

    /** Whether a colour reads as dark — used to derive sub-role tints in the right direction. */
    public static boolean isDark(Color reference) {
        return (reference.getRed() + reference.getGreen() + reference.getBlue()) / 3 < 128;
    }

    /** Returns {@code color} with the given alpha (0-255), preserving RGB. */
    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * Shifts a colour {@code amount} units per channel towards more contrast with
     * {@code background} (darker on a light background, lighter on a dark one).
     * With the historical light values this reproduces the pre-theming constants
     * exactly; negative amounts shift towards less contrast instead.
     */
    public static Color shiftForContrast(Color color, Color background, int amount) {
        int delta = isDark(background) ? amount : -amount;
        return new Color(
            clamp(color.getRed() + delta),
            clamp(color.getGreen() + delta),
            clamp(color.getBlue() + delta));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static Color color(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        // Copy into a plain Color: UIManager returns UIResource instances, which
        // Swing components may silently replace on LaF updates.
        return color != null ? new Color(color.getRGB(), true) : fallback;
    }
}
