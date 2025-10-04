package com.kalix.ide.themes;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages syntax highlighting themes for the text editor.
 * Provides color definitions for different token types used in the KalixIniTokenMaker.
 */
public class SyntaxTheme {

    /**
     * Available syntax themes with predefined color schemes that match application themes.
     */
    public enum Theme {
        // Light Application Themes
        LIGHT("Light",
            Color.decode("#2d2d2d"), // IDENTIFIER (keys, regular text) - dark gray
            Color.decode("#3b82f6"), // OPERATOR (equals signs) - blue
            Color.decode("#577590"), // LITERAL_STRING_DOUBLE_QUOTE (values) - blue-gray (matches blackhole node)
            Color.decode("#059669"), // RESERVED_WORD (section headers) - green
            Color.decode("#F3722C"), // COMMENT_EOL (comments) - orange-red (matches user node)
            Color.LIGHT_GRAY         // WHITESPACE
        ),

        KEYLIME("Keylime",
            Color.decode("#1a1a1a"), // IDENTIFIER - dark text
            Color.decode("#65a30d"), // OPERATOR - lime green accent
            Color.decode("#4b5563"), // LITERAL_STRING_DOUBLE_QUOTE - medium dark grey
            Color.decode("#15803d"), // RESERVED_WORD - darker green for section headers
            Color.decode("#a3e635"), // COMMENT_EOL - lighter vibrant green
            Color.LIGHT_GRAY         // WHITESPACE
        ),

        LAPLAND("Lapland",
            Color.decode("#1e293b"), // IDENTIFIER - dark slate
            Color.decode("#2563eb"), // OPERATOR - nordic blue
            Color.decode("#0ea5e9"), // LITERAL_STRING_DOUBLE_QUOTE - sky blue
            Color.decode("#3b82f6"), // RESERVED_WORD - medium blue
            Color.decode("#64748b"), // COMMENT_EOL - slate gray
            Color.decode("#e2e8f0")  // WHITESPACE - light slate
        ),

        NEMO("Nemo",
            Color.decode("#0d3d56"), // IDENTIFIER - deep sea blue
            Color.decode("#ff6f00"), // OPERATOR - clownfish orange
            Color.decode("#0288d1"), // LITERAL_STRING_DOUBLE_QUOTE - ocean blue
            Color.decode("#ff8f00"), // RESERVED_WORD - bright orange
            Color.decode("#546e7a"), // COMMENT_EOL - sea gray
            Color.decode("#e1f5fe")  // WHITESPACE - light ocean
        ),

        SUNSET_WARMTH("Sunset Warmth",
            Color.decode("#8b4513"), // IDENTIFIER - saddle brown (for readability)
            Color.decode("#ff6b35"), // OPERATOR - vibrant sunset orange
            Color.decode("#8b4513"), // LITERAL_STRING_DOUBLE_QUOTE - saddle brown (for readability)
            Color.decode("#45b7d1"), // RESERVED_WORD - sky blue
            Color.decode("#feca57"), // COMMENT_EOL - golden yellow
            Color.decode("#fffcf8")  // WHITESPACE - warm cream
        ),

        BOTANICAL("Botanical",
            Color.decode("#0f2a0f"), // IDENTIFIER - darker forest green
            Color.decode("#A0522D"), // OPERATOR - sienna brown (from botanical node theme)
            Color.decode("#5a8a4a"), // LITERAL_STRING_DOUBLE_QUOTE - darker botanical green
            Color.decode("#5a8a5a"), // RESERVED_WORD - darker nature green
            Color.decode("#8b9098"), // COMMENT_EOL - lighter gray
            Color.decode("#f0f4f0")  // WHITESPACE - soft green tint
        ),

        // Dark Application Themes
        DRACULA("Dracula",
            Color.decode("#f8f8f2"), // IDENTIFIER - foreground
            Color.decode("#ff79c6"), // OPERATOR - pink
            Color.decode("#f1fa8c"), // LITERAL_STRING_DOUBLE_QUOTE - yellow
            Color.decode("#bd93f9"), // RESERVED_WORD - purple
            Color.decode("#6272a4"), // COMMENT_EOL - comment blue
            Color.decode("#44475a")  // WHITESPACE - current line
        ),

        ONE_DARK("One Dark",
            Color.decode("#abb2bf"), // IDENTIFIER - light gray
            Color.decode("#56b6c2"), // OPERATOR - cyan
            Color.decode("#98c379"), // LITERAL_STRING_DOUBLE_QUOTE - green
            Color.decode("#c678dd"), // RESERVED_WORD - purple
            Color.decode("#5c6370"), // COMMENT_EOL - gray
            Color.decode("#3e4451")  // WHITESPACE - dark gray
        ),

        OBSIDIAN("Obsidian",
            Color.decode("#e6e6e6"), // IDENTIFIER - light gray
            Color.decode("#8b5cf6"), // OPERATOR - purple accent
            Color.decode("#c084fc"), // LITERAL_STRING_DOUBLE_QUOTE - lighter purple (property values)
            Color.decode("#a855f7"), // RESERVED_WORD - medium light purple (section headers)
            Color.decode("#6b7280"), // COMMENT_EOL - muted gray
            Color.decode("#374151")  // WHITESPACE - dark gray
        ),

        SANNE("Sanne",
            Color.decode("#f0f0f0"), // IDENTIFIER - crisp white
            Color.decode("#ff1493"), // OPERATOR - vibrant pink
            Color.decode("#ff69b4"), // LITERAL_STRING_DOUBLE_QUOTE - hot pink
            Color.decode("#ff1493"), // RESERVED_WORD - deep pink
            Color.decode("#a0a0a0"), // COMMENT_EOL - light gray
            Color.decode("#404040")  // WHITESPACE - dark gray
        );

        private final String displayName;
        private final Color identifierColor;
        private final Color operatorColor;
        private final Color stringColor;
        private final Color reservedWordColor;
        private final Color commentColor;
        private final Color whitespaceColor;

        Theme(String displayName, Color identifierColor, Color operatorColor,
              Color stringColor, Color reservedWordColor, Color commentColor, Color whitespaceColor) {
            this.displayName = displayName;
            this.identifierColor = identifierColor;
            this.operatorColor = operatorColor;
            this.stringColor = stringColor;
            this.reservedWordColor = reservedWordColor;
            this.commentColor = commentColor;
            this.whitespaceColor = whitespaceColor;
        }

        public String getDisplayName() { return displayName; }
        public Color getIdentifierColor() { return identifierColor; }
        public Color getOperatorColor() { return operatorColor; }
        public Color getStringColor() { return stringColor; }
        public Color getReservedWordColor() { return reservedWordColor; }
        public Color getCommentColor() { return commentColor; }
        public Color getWhitespaceColor() { return whitespaceColor; }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private Theme currentTheme;

    /**
     * Creates a new SyntaxTheme with the default theme.
     */
    public SyntaxTheme() {
        this(Theme.LIGHT);
    }

    /**
     * Creates a new SyntaxTheme with the specified theme.
     */
    public SyntaxTheme(Theme theme) {
        this.currentTheme = theme;
    }

    /**
     * Gets the current theme.
     */
    public Theme getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Sets the current theme.
     */
    public void setCurrentTheme(Theme theme) {
        this.currentTheme = theme;
    }

    /**
     * Gets all available syntax themes.
     */
    public static Theme[] getAllThemes() {
        return Theme.values();
    }

    /**
     * Gets a theme by display name.
     * @param displayName The display name of the theme
     * @return The corresponding Theme enum, or LIGHT if not found
     */
    public static Theme getThemeByDisplayName(String displayName) {
        for (Theme theme : Theme.values()) {
            if (theme.getDisplayName().equals(displayName)) {
                return theme;
            }
        }
        return Theme.LIGHT; // Default fallback
    }

    /**
     * Gets a theme by enum name (for preference storage).
     * @param name The enum name of the theme
     * @return The corresponding Theme enum, or LIGHT if not found
     */
    public static Theme getThemeByName(String name) {
        try {
            return Theme.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Theme.LIGHT; // Default fallback
        }
    }

    /**
     * Gets a color map for all token types in the current theme.
     * Keys match RSyntaxTextArea token type constants.
     */
    public Map<String, Color> getTokenColorMap() {
        Map<String, Color> colorMap = new HashMap<>();
        colorMap.put("Token.IDENTIFIER", currentTheme.getIdentifierColor());
        colorMap.put("Token.OPERATOR", currentTheme.getOperatorColor());
        colorMap.put("Token.LITERAL_STRING_DOUBLE_QUOTE", currentTheme.getStringColor());
        colorMap.put("Token.RESERVED_WORD", currentTheme.getReservedWordColor());
        colorMap.put("Token.COMMENT_EOL", currentTheme.getCommentColor());
        colorMap.put("Token.WHITESPACE", currentTheme.getWhitespaceColor());
        return colorMap;
    }
}