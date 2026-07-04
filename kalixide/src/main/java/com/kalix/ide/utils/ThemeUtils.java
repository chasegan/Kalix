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
}
