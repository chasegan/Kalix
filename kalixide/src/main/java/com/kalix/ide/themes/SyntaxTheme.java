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
     * Available syntax themes with predefined color schemes.
     */
    public enum Theme {
        DEFAULT("Default",
            Color.BLACK,           // IDENTIFIER (keys, regular text)
            Color.BLUE,            // OPERATOR (equals signs)
            Color.decode("#8B4A02"), // LITERAL_STRING_DOUBLE_QUOTE (values, continuation lines) - brown
            Color.BLUE,            // RESERVED_WORD (section headers)
            Color.decode("#008000"), // COMMENT_EOL (comments) - green
            Color.LIGHT_GRAY       // WHITESPACE
        ),

        DARK("Dark",
            Color.decode("#D4D4D4"), // IDENTIFIER - light gray
            Color.decode("#569CD6"), // OPERATOR - light blue
            Color.decode("#CE9178"), // LITERAL_STRING_DOUBLE_QUOTE - orange
            Color.decode("#4EC9B0"), // RESERVED_WORD - cyan
            Color.decode("#6A9955"), // COMMENT_EOL - green
            Color.decode("#404040")  // WHITESPACE - dark gray
        ),

        GITHUB_LIGHT("GitHub Light",
            Color.decode("#24292e"), // IDENTIFIER - dark gray
            Color.decode("#d73a49"), // OPERATOR - red
            Color.decode("#032f62"), // LITERAL_STRING_DOUBLE_QUOTE - dark blue
            Color.decode("#6f42c1"), // RESERVED_WORD - purple
            Color.decode("#6a737d"), // COMMENT_EOL - gray
            Color.LIGHT_GRAY         // WHITESPACE
        ),

        MONOKAI("Monokai",
            Color.decode("#F8F8F2"), // IDENTIFIER - white
            Color.decode("#F92672"), // OPERATOR - pink
            Color.decode("#E6DB74"), // LITERAL_STRING_DOUBLE_QUOTE - yellow
            Color.decode("#66D9EF"), // RESERVED_WORD - cyan
            Color.decode("#75715E"), // COMMENT_EOL - gray
            Color.decode("#3E3D32")  // WHITESPACE - dark gray
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
        this(Theme.DEFAULT);
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
     * @return The corresponding Theme enum, or DEFAULT if not found
     */
    public static Theme getThemeByDisplayName(String displayName) {
        for (Theme theme : Theme.values()) {
            if (theme.getDisplayName().equals(displayName)) {
                return theme;
            }
        }
        return Theme.DEFAULT; // Default fallback
    }

    /**
     * Gets a theme by enum name (for preference storage).
     * @param name The enum name of the theme
     * @return The corresponding Theme enum, or DEFAULT if not found
     */
    public static Theme getThemeByName(String name) {
        try {
            return Theme.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Theme.DEFAULT; // Default fallback
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