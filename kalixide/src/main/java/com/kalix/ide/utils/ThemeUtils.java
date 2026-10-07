package com.kalix.ide.utils;

import com.kalix.ide.constants.UIConstants;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Shared light/dark heuristics for theme-aware rendering.
 *
 * <p>The single home of the RGB-sum dark check that was previously copy-pasted
 * across the toolbar, menu icons, and map renderer.
 */
public final class ThemeUtils {

    private ThemeUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Determines whether a background colour reads as dark.
     *
     * @param background the background colour to classify (may be null)
     * @return {@code true} if the colour is dark; {@code false} for light or null
     *         (light is the safe default when the colour is unavailable)
     */
    public static boolean isDark(Color background) {
        if (background == null) {
            return false;
        }
        int sum = background.getRed() + background.getGreen() + background.getBlue();
        return sum < UIConstants.Theme.LIGHT_THEME_RGB_THRESHOLD;
    }

    /**
     * The standard theme-aware icon grey — dark grey on light themes, light grey on dark —
     * shared by the toolbar buttons, menu icons, and the project tree's folder glyphs so
     * they all read as one family.
     *
     * @param background the surface the icon sits on (drives the dark check; may be null,
     *                   which classifies as light)
     * @return the icon colour for that surface
     */
    public static Color iconColor(Color background) {
        return isDark(background) ? Color.LIGHT_GRAY : Color.DARK_GRAY;
    }

    /**
     * The current theme's accent colour, for the one coloured mark among grey icons (the
     * tab insertion line, the error-log button). Resolved on each call so it tracks theme
     * switches. The themes carry their accent in {@code Component.focusedBorderColor};
     * {@code Component.accentColor} is FlatLaf's own default and the same blue in every
     * theme, so it is only a fallback, as is a fixed blue when no look and feel is set.
     *
     * @return the accent colour, never null
     */
    public static Color accentColor() {
        Color c = UIManager.getColor("Component.focusedBorderColor");
        if (c == null) {
            c = UIManager.getColor("Component.accentColor");
        }
        if (c == null) {
            c = UIManager.getColor("Component.focusColor");
        }
        return c != null ? c : FALLBACK_ACCENT;
    }

    private static final Color FALLBACK_ACCENT = new Color(0x1E88E5);
}
