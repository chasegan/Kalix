package com.kalix.ide.themes.unified;

import java.awt.Color;

/**
 * Specification for generating syntax theme colors from a color palette.
 * Defines how palette colors map to different syntax highlighting elements.
 */
public class SyntaxThemeSpec {

    /**
     * Generate syntax colors for INI syntax highlighting from the palette.
     * Creates appropriate colors for different token types based on theme brightness.
     */
    public static SyntaxColors generateSyntaxColors(ColorPalette palette) {
        if (palette.isDark()) {
            return createDarkSyntaxColors(palette);
        } else {
            return createLightSyntaxColors(palette);
        }
    }

    private static SyntaxColors createDarkSyntaxColors(ColorPalette palette) {
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

    private static SyntaxColors createLightSyntaxColors(ColorPalette palette) {
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

        /**
         * Get color for a specific token type by name
         */
        public Color getColorForToken(String tokenType) {
            switch (tokenType.toLowerCase()) {
                case "comment_eol":
                case "comment_multiline":
                    return comment;
                case "reserved_word":
                    return keyword;
                case "literal_string_double_quote":
                    return string;
                case "literal_number_decimal_int":
                case "literal_number_float":
                    return number;
                case "error_identifier":
                case "error_number_format":
                case "error_string_double":
                case "error_char":
                    return error;
                case "identifier":
                    return identifier;
                case "function":
                    return function;
                default:
                    return defaultColor;
            }
        }
    }
}