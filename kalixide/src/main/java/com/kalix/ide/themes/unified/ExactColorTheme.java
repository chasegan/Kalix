package com.kalix.ide.themes.unified;

import java.awt.Color;
import java.util.Map;
import java.util.HashMap;

/**
 * Stores exact color mappings for a theme to preserve visual fidelity while
 * enabling code reuse through component builders.
 *
 * This approach stores the exact colors from your theme definitions and
 * applies them through component builders to eliminate the massive duplication
 * in theme creation methods.
 */
public class ExactColorTheme {

    private final String name;
    private final boolean isDark;
    private final Map<String, String> colors;

    public ExactColorTheme(String name, boolean isDark) {
        this.name = name;
        this.isDark = isDark;
        this.colors = new HashMap<>();
    }

    /**
     * Set an exact color for a property
     */
    public ExactColorTheme setColor(String property, String hexColor) {
        colors.put(property, hexColor);
        return this;
    }

    /**
     * Get color for a property, with fallback
     */
    public String getColor(String property, String fallback) {
        return colors.getOrDefault(property, fallback);
    }

    /**
     * Get color for a property
     */
    public String getColor(String property) {
        return colors.get(property);
    }

    /**
     * Check if this is a dark theme
     */
    public boolean isDark() {
        return isDark;
    }

    /**
     * Get theme name
     */
    public String getName() {
        return name;
    }

    /**
     * Apply all colors to a properties map using component builders
     */
    public Map<String, String> generateThemeProperties() {
        Map<String, String> properties = new HashMap<>();

        // Apply component themes in order
        new BaseComponentTheme().applyTheme(properties, this);
        new ButtonComponentTheme().applyTheme(properties, this);
        new TextComponentTheme().applyTheme(properties, this);
        new MenuComponentTheme().applyTheme(properties, this);
        new TableComponentTheme().applyTheme(properties, this);
        new ToolbarComponentTheme().applyTheme(properties, this);
        new TabComponentTheme().applyTheme(properties, this);
        new ScrollbarComponentTheme().applyTheme(properties, this);
        new FormComponentTheme().applyTheme(properties, this);
        new CustomKalixTheme().applyTheme(properties, this);

        return properties;
    }

    /**
     * Create a UnifiedThemeDefinition from this exact color theme
     */
    public UnifiedThemeDefinition toUnifiedTheme() {
        // Create a basic palette from key colors for backwards compatibility
        Color primary = Color.decode(getColor("Component.focusedBorderColor", "#0066cc"));
        Color background = Color.decode(getColor("Panel.background", isDark ? "#2b2b2b" : "#f2f2f2"));
        Color surface = Color.decode(getColor("Component.background", isDark ? "#3c3c3c" : "#ffffff"));
        Color onBackground = Color.decode(getColor("Label.foreground", isDark ? "#ffffff" : "#000000"));

        ColorPalette palette = new ColorPalette(
            name, primary, primary, background, surface, onBackground, onBackground,
            java.util.List.of(primary), isDark
        );

        return new UnifiedThemeDefinition(name, palette, generateThemeProperties());
    }
}