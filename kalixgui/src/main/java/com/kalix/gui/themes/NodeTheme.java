package com.kalix.gui.themes;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages node color themes for the map visualization.
 * Provides multiple color palettes and handles node type to color mapping.
 */
public class NodeTheme {
    
    /**
     * Available color themes for nodes
     */
    public enum Theme {
        VIBRANT("Vibrant", new String[]{
            "F94144", "F3722C", "F8961E", "F9844A", "F9C74F", 
            "90BE6D", "43AA8B", "4D908E", "577590", "277DA1"
        }),
        
        EARTH("Earth Tones", new String[]{
            "8B4513", "CD853F", "DEB887", "F4A460", "D2691E",
            "BC8F8F", "A0522D", "8FBC8F", "556B2F", "9ACD32"
        }),
        
        OCEAN("Ocean Blues", new String[]{
            "191970", "4169E1", "6495ED", "87CEEB", "00CED1",
            "48D1CC", "20B2AA", "008B8B", "5F9EA0", "4682B4"
        }),
        
        SUNSET("Sunset Warmth", new String[]{
            "FF6B35", "F7931E", "FFD23F", "06FFA5", "4ECDC4",
            "45B7D1", "96CEB4", "FECA57", "FF9FF3", "54A0FF"
        });
        
        private final String displayName;
        private final String[] colors;
        
        Theme(String displayName, String[] colors) {
            this.displayName = displayName;
            this.colors = colors;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String[] getColors() {
            return colors;
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