package com.kalix.ide.themes.unified;

import java.util.Map;
import java.util.Properties;

/**
 * Unified theme definition that generates all three theme types from a single color palette.
 * This is the central class that ties together the unified theme system.
 */
public class UnifiedThemeDefinition {

    private final ColorPalette palette;
    private final String name;
    private final Map<String, String> customApplicationProperties;

    public UnifiedThemeDefinition(String name, ColorPalette palette, Map<String, String> customApplicationProperties) {
        this.name = name;
        this.palette = palette;
        this.customApplicationProperties = customApplicationProperties;
    }

    /**
     * Get the color palette for this theme
     */
    public ColorPalette getColorPalette() {
        return palette;
    }

    /**
     * Get the theme name
     */
    public String getName() {
        return name;
    }

    /**
     * Generate application theme properties
     */
    public Properties generateApplicationProperties() {
        return ApplicationThemeSpec.generateProperties(palette, customApplicationProperties);
    }

    @Override
    public String toString() {
        return String.format("UnifiedThemeDefinition{name='%s', palette=%s}", name, palette);
    }
}