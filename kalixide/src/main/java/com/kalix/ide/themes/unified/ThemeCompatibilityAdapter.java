package com.kalix.ide.themes.unified;

import com.formdev.flatlaf.FlatPropertiesLaf;
import java.util.Properties;

/**
 * Adapter to bridge between the unified theme system and the existing legacy theme system.
 * Allows gradual migration by providing compatibility methods that generate legacy theme objects
 * from unified theme definitions.
 */
public class ThemeCompatibilityAdapter {

    /**
     * Create a FlatPropertiesLaf from a unified theme definition.
     * This replaces the need to load .properties files for application themes.
     */
    public static FlatPropertiesLaf createApplicationTheme(UnifiedThemeDefinition unifiedTheme) {
        Properties props = unifiedTheme.generateApplicationProperties();

        // Set the @dark annotation if this is a dark theme
        if (unifiedTheme.getColorPalette().isDark()) {
            props.setProperty("@dark", "");
        }

        return new FlatPropertiesLaf(unifiedTheme.getName(), props);
    }
}