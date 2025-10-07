package com.kalix.ide.themes.unified;

import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.themes.SyntaxTheme;
import com.formdev.flatlaf.FlatPropertiesLaf;
import java.awt.Color;
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

    /**
     * Create a NodeTheme.Theme from a unified theme definition.
     * This generates the same structure as the existing NodeTheme enum values.
     */
    public static NodeTheme.Theme createNodeTheme(UnifiedThemeDefinition unifiedTheme) {
        String[] nodeColors = unifiedTheme.generateNodeColors();
        NodeTheme.TextStyle textStyle = unifiedTheme.generateNodeTextStyle();

        // Since NodeTheme.Theme constructor is package-private, we need to use reflection
        // or add a factory method to NodeTheme. For now, return existing theme as placeholder.
        // TODO: Refactor NodeTheme to support dynamic creation
        return unifiedTheme.getColorPalette().isDark() ?
            NodeTheme.Theme.SANNE : NodeTheme.Theme.LIGHT;
    }

    /**
     * Create syntax theme colors from a unified theme definition.
     * This generates colors that can be used with the existing SyntaxTheme system.
     */
    public static Color[] createSyntaxThemeColors(UnifiedThemeDefinition unifiedTheme) {
        UnifiedThemeDefinition.SyntaxColors syntaxColors = unifiedTheme.generateSyntaxColors();

        // Convert to array format expected by SyntaxTheme
        return new Color[] {
            syntaxColors.comment,        // COMMENT_EOL
            syntaxColors.comment,        // COMMENT_MULTILINE
            syntaxColors.keyword,        // RESERVED_WORD
            syntaxColors.string,         // LITERAL_STRING_DOUBLE_QUOTE
            syntaxColors.number,         // LITERAL_NUMBER_DECIMAL_INT
            syntaxColors.number,         // LITERAL_NUMBER_FLOAT
            syntaxColors.error,          // ERROR_IDENTIFIER
            syntaxColors.error,          // ERROR_NUMBER_FORMAT
            syntaxColors.error,          // ERROR_STRING_DOUBLE
            syntaxColors.error,          // ERROR_CHAR
            syntaxColors.identifier,     // IDENTIFIER
            syntaxColors.function,       // FUNCTION
            syntaxColors.defaultColor    // DEFAULT
        };
    }

    /**
     * Extract color palette from existing .properties theme files.
     * This helps migrate existing themes to the unified system.
     */
    public static ColorPalette extractPaletteFromProperties(Properties themeProps, String themeName, boolean isDark) {
        // Extract key colors from properties
        Color primary = parseColorProperty(themeProps, "Component.focusedBorderColor", isDark ? "#6b7fff" : "#0066cc");
        Color secondary = parseColorProperty(themeProps, "Button.default.hoverBackground", isDark ? "#8b7fff" : "#0080ff");
        Color background = parseColorProperty(themeProps, "Panel.background", isDark ? "#2b2b2b" : "#ffffff");
        Color surface = parseColorProperty(themeProps, "Component.background", isDark ? "#3c3c3c" : "#f5f5f5");
        Color onBackground = parseColorProperty(themeProps, "Label.foreground", isDark ? "#ffffff" : "#000000");
        Color onSurface = parseColorProperty(themeProps, "Component.foreground", isDark ? "#e0e0e0" : "#333333");

        // Create basic accent colors from theme colors
        java.util.List<Color> accentColors = java.util.Arrays.asList(
            primary,
            secondary,
            parseColorProperty(themeProps, "ProgressBar.foreground", colorToHex(primary)),
            parseColorProperty(themeProps, "Button.pressedBackground", colorToHex(secondary))
        );

        return new ColorPalette(themeName, primary, secondary, background, surface,
                               onBackground, onSurface, accentColors, isDark);
    }

    private static Color parseColorProperty(Properties props, String key, String defaultValue) {
        String colorStr = props.getProperty(key, defaultValue);
        try {
            // Handle both #RRGGBB and RRGGBB formats
            if (colorStr.startsWith("#")) {
                colorStr = colorStr.substring(1);
            }
            return Color.decode("#" + colorStr);
        } catch (NumberFormatException e) {
            // Fallback to a reasonable default
            return Color.decode(defaultValue.startsWith("#") ? defaultValue : "#" + defaultValue);
        }
    }

    /**
     * Check if a properties file represents a dark theme
     */
    public static boolean isDarkTheme(Properties themeProps) {
        return themeProps.containsKey("@dark") ||
               themeProps.getProperty("@dark") != null;
    }

    private static String colorToHex(Color color) {
        if (color == null) return "#000000";
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }
}