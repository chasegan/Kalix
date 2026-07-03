package com.kalix.ide.themes.unified;

import com.formdev.flatlaf.FlatPropertiesLaf;
import java.util.Properties;

/**
 * Converts a {@link UnifiedThemeDefinition} into the FlatLaf look-and-feel
 * object that {@code ThemeManager} installs.
 */
public class ThemeCompatibilityAdapter {

    /**
     * Create a FlatPropertiesLaf from a unified theme definition.
     * This replaces the need to load .properties files for application themes.
     */
    public static FlatPropertiesLaf createApplicationTheme(UnifiedThemeDefinition unifiedTheme) {
        Properties props = unifiedTheme.generateApplicationProperties();

        // FlatPropertiesLaf only reads "@baseTheme" to decide whether the FlatLaf
        // dark or light defaults underpin the theme (and what isDark() reports).
        // Dark themes must declare it, or they inherit LIGHT defaults for every
        // key they don't explicitly override.
        props.setProperty("@baseTheme", unifiedTheme.isDark() ? "dark" : "light");

        return new FlatPropertiesLaf(unifiedTheme.getName(), props);
    }
}