package com.kalix.ide.themes;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages node color themes and text styling for the map visualization.
 * Provides multiple color palettes and text styling configurations.
 */
public class NodeTheme {
    
    /**
     * Text styling configuration for node labels.
     */
    public static class TextStyle {
        private final int fontSize;
        private final Color textColor;
        private final int yOffset;
        private final Color backgroundColor;
        private final int backgroundAlpha;
        
        public TextStyle(int fontSize, Color textColor, int yOffset, Color backgroundColor, int backgroundAlpha) {
            this.fontSize = fontSize;
            this.textColor = textColor;
            this.yOffset = yOffset;
            this.backgroundColor = backgroundColor;
            this.backgroundAlpha = backgroundAlpha;
        }
        
        public int getFontSize() { return fontSize; }
        public Color getTextColor() { return textColor; }
        public int getYOffset() { return yOffset; }
        public Color getBackgroundColor() { return backgroundColor; }
        public int getBackgroundAlpha() { return backgroundAlpha; }
        
        public Font createFont() {
            return new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        }
        
        public Color createBackgroundColorWithAlpha() {
            return new Color(
                backgroundColor.getRed(),
                backgroundColor.getGreen(), 
                backgroundColor.getBlue(),
                backgroundAlpha
            );
        }
    }
    
    /**
     * Available color themes for nodes with text styling
     */
    public enum Theme {
        VIBRANT("Vibrant", 
                new String[]{
                    "F94144", "F3722C", "F8961E", "F9844A", "F9C74F", 
                    "90BE6D", "43AA8B", "4D908E", "577590", "277DA1"
                },
                new TextStyle(10, Color.BLACK, 15, Color.WHITE, 180)),
        
        EARTH("Earth Tones", 
              new String[]{
                  "8B4513", "CD853F", "DEB887", "F4A460", "D2691E",
                  "BC8F8F", "A0522D", "8FBC8F", "556B2F", "9ACD32"
              },
              new TextStyle(11, new Color(92, 51, 23), 16, new Color(245, 245, 220), 200)),
        
        OCEAN("Ocean Blues", 
              new String[]{
                  "191970", "4169E1", "6495ED", "87CEEB", "00CED1",
                  "48D1CC", "20B2AA", "008B8B", "5F9EA0", "4682B4"
              },
              new TextStyle(10, new Color(25, 25, 112), 14, new Color(240, 248, 255), 190)),
        
        SUNSET("Sunset Warmth",
               new String[]{
                   "FF6B35", "F7931E", "FFD23F", "06FFA5", "4ECDC4",
                   "45B7D1", "96CEB4", "FECA57", "FF9FF3", "54A0FF"
               },
               new TextStyle(11, new Color(139, 69, 19), 17, new Color(255, 248, 220), 210)),

        BOTANICAL("Botanical",
                  new String[]{
                      "228B22", "B8860B", "2F4F4F", "DAA520", "556B2F",
                      "4682B4", "8B4513", "CCCC00", "1E90FF", "A0522D",
                      "32CD32", "CD853F", "6495ED", "D2691E", "2E8B57"
                  },
                  new TextStyle(11, new Color(34, 139, 34), 16, new Color(248, 248, 255), 200));
        
        private final String displayName;
        private final String[] colors;
        private final TextStyle textStyle;
        
        Theme(String displayName, String[] colors, TextStyle textStyle) {
            this.displayName = displayName;
            this.colors = colors;
            this.textStyle = textStyle;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String[] getColors() {
            return colors;
        }
        
        public TextStyle getTextStyle() {
            return textStyle;
        }
    }
    
    private Theme currentTheme;
    private final Map<String, Color> nodeTypeColors;
    private int nextColorIndex;
    
    /**
     * Creates a new NodeTheme with the default VIBRANT theme.
     */
    public NodeTheme() {
        this(Theme.VIBRANT);
    }
    
    /**
     * Creates a new NodeTheme with the specified theme.
     * @param theme The theme to use
     */
    public NodeTheme(Theme theme) {
        this.currentTheme = theme;
        this.nodeTypeColors = new HashMap<>();
        this.nextColorIndex = 0;
    }
    
    /**
     * Gets the color for a specific node type, assigning a new color if needed.
     * @param nodeType The type of the node
     * @return The color for this node type
     */
    public Color getColorForNodeType(String nodeType) {
        return nodeTypeColors.computeIfAbsent(nodeType, type -> {
            String[] palette = currentTheme.getColors();
            String hexColor = palette[nextColorIndex % palette.length];
            nextColorIndex++;
            return Color.decode("#" + hexColor);
        });
    }
    
    /**
     * Changes the current theme and clears existing color assignments.
     * This forces all node types to get new colors from the new theme.
     * @param theme The new theme to use
     */
    public void setTheme(Theme theme) {
        this.currentTheme = theme;
        this.nodeTypeColors.clear();
        this.nextColorIndex = 0;
    }
    
    /**
     * Gets the current theme.
     * @return The current theme
     */
    public Theme getCurrentTheme() {
        return currentTheme;
    }
    
    /**
     * Gets the text style for the current theme.
     * @return The text style configuration
     */
    public TextStyle getCurrentTextStyle() {
        return currentTheme.getTextStyle();
    }
    
    /**
     * Gets all available themes.
     * @return Array of all available themes
     */
    public static Theme[] getAllThemes() {
        return Theme.values();
    }
    
    /**
     * Clears all node type color assignments without changing the theme.
     * Useful for resetting colors while keeping the same theme.
     */
    public void resetColors() {
        nodeTypeColors.clear();
        nextColorIndex = 0;
    }
    
    /**
     * Gets the number of node types that have been assigned colors.
     * @return The number of assigned node types
     */
    public int getAssignedTypeCount() {
        return nodeTypeColors.size();
    }
    
    /**
     * Checks if a specific node type has been assigned a color.
     * @param nodeType The node type to check
     * @return true if the node type has an assigned color
     */
    public boolean hasColorForType(String nodeType) {
        return nodeTypeColors.containsKey(nodeType);
    }
    
    /**
     * Converts a theme name string to a Theme enum.
     * @param themeName The theme name string
     * @return The corresponding Theme enum, or VIBRANT if not found
     */
    public static Theme themeFromString(String themeName) {
        if (themeName == null) {
            return Theme.VIBRANT;
        }
        
        for (Theme theme : Theme.values()) {
            if (theme.getDisplayName().equals(themeName)) {
                return theme;
            }
        }
        
        // Fallback: try to match by enum name
        try {
            return Theme.valueOf(themeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Theme.VIBRANT; // Default fallback
        }
    }
    
    /**
     * Converts a Theme enum to its display name string.
     * @param theme The theme enum
     * @return The display name string
     */
    public static String themeToString(Theme theme) {
        return theme.getDisplayName();
    }
}