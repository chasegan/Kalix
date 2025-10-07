package com.kalix.ide.themes.unified;

import java.util.Map;

/**
 * Base class for component-specific theme builders.
 * Each component type (Button, Menu, etc.) has a builder that applies exact colors
 * from the ColorPalette to eliminate duplication in theme definitions.
 */
public abstract class ComponentThemeBuilder {

    /**
     * Apply theme properties for this component type.
     * Uses exact colors from the palette rather than algorithmic generation.
     *
     * @param properties The properties map to populate
     * @param palette The color palette containing exact colors for this theme
     */
    public abstract void applyTheme(Map<String, String> properties, ColorPalette palette);

    /**
     * Helper method to convert Color to hex string
     */
    protected static String colorToHex(java.awt.Color color) {
        if (color == null) return "#000000";
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }
}