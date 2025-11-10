package com.kalix.ide.themes.unified;

import com.kalix.ide.themes.NodeTheme;
import java.awt.Color;
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

    public UnifiedThemeDefinition(String name, ColorPalette palette) {
        this(name, palette, null);
    }

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

    /**
     * Generate node theme colors
     */
    public String[] generateNodeColors() {
        return NodeThemeSpec.generateNodeColorArray(palette);
    }

    /**
     * Generate node text style
     */
    public NodeTheme.TextStyle generateNodeTextStyle() {
        return NodeThemeSpec.generateTextStyle(palette);
    }

    /**
     * Generate semantic node type color mappings
     */
    public java.util.Map<String, String> generateNodeTypeColorMap() {
        return NodeThemeSpec.generateNodeTypeColorMap(palette);
    }

    /**
     * Generate syntax highlighting colors for different token types.
     * This replaces the hardcoded colors in SyntaxTheme.
     */
    public SyntaxColors generateSyntaxColors() {
        if (palette.isDark()) {
            return createDarkSyntaxColors();
        } else {
            return createLightSyntaxColors();
        }
    }

    private SyntaxColors createDarkSyntaxColors() {
        // Dark theme syntax colors based on palette
        Color commentColor = palette.createVariant(palette.getOnSurface(), 0.6f);
        Color keywordColor = palette.getPrimary();
        Color stringColor = palette.getAccentColor(0);
        Color numberColor = palette.getAccentColor(1);
        Color errorColor = palette.getSemanticColor("error");

        return new SyntaxColors(
            commentColor,        // COMMENT_EOL, COMMENT_MULTILINE
            keywordColor,        // RESERVED_WORD (section headers)
            stringColor,         // LITERAL_STRING_DOUBLE_QUOTE (property values)
            numberColor,         // LITERAL_NUMBER_DECIMAL_INT, LITERAL_NUMBER_FLOAT
            errorColor,          // ERROR_IDENTIFIER, ERROR_NUMBER_FORMAT, ERROR_STRING_DOUBLE, ERROR_CHAR
            palette.getOnBackground(), // IDENTIFIER (property names)
            palette.getSemanticColor("warning"), // FUNCTION (special values)
            palette.getOnSurface()    // DEFAULT (other text)
        );
    }

    private SyntaxColors createLightSyntaxColors() {
        // Light theme syntax colors based on palette
        Color commentColor = palette.createVariant(palette.getOnSurface(), 0.7f);
        Color keywordColor = palette.getPrimary();
        Color stringColor = palette.getAccentColor(0);
        Color numberColor = palette.getAccentColor(1);
        Color errorColor = palette.getSemanticColor("error");

        return new SyntaxColors(
            commentColor,        // COMMENT_EOL, COMMENT_MULTILINE
            keywordColor,        // RESERVED_WORD (section headers)
            stringColor,         // LITERAL_STRING_DOUBLE_QUOTE (property values)
            numberColor,         // LITERAL_NUMBER_DECIMAL_INT, LITERAL_NUMBER_FLOAT
            errorColor,          // ERROR_IDENTIFIER, ERROR_NUMBER_FORMAT, ERROR_STRING_DOUBLE, ERROR_CHAR
            palette.getOnBackground(), // IDENTIFIER (property names)
            palette.getSemanticColor("warning"), // FUNCTION (special values)
            palette.getOnSurface()    // DEFAULT (other text)
        );
    }

    /**
     * Container for syntax highlighting colors
     */
    public static class SyntaxColors {
        public final Color comment;
        public final Color keyword;
        public final Color string;
        public final Color number;
        public final Color error;
        public final Color identifier;
        public final Color function;
        public final Color defaultColor;

        public SyntaxColors(Color comment, Color keyword, Color string, Color number,
                           Color error, Color identifier, Color function, Color defaultColor) {
            this.comment = comment;
            this.keyword = keyword;
            this.string = string;
            this.number = number;
            this.error = error;
            this.identifier = identifier;
            this.function = function;
            this.defaultColor = defaultColor;
        }
    }

    @Override
    public String toString() {
        return String.format("UnifiedThemeDefinition{name='%s', palette=%s}", name, palette);
    }
}