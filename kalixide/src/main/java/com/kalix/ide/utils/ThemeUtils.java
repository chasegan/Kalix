package com.kalix.ide.utils;

import com.kalix.ide.constants.UIConstants;

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
}
