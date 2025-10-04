package com.kalix.ide.themes;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages node color themes, shapes, and text styling for the map visualization.
 * Provides multiple color palettes, shape mappings, and text styling configurations.
 */
public class NodeTheme {

    /**
     * Available shapes for node visualization.
     */
    public enum NodeShape {
        TRIANGLE_DOWN,    // ▽ Equilateral triangle (pointing down)
        TRIANGLE_UP,      // ▲ Equilateral triangle (pointing up)
        TRIANGLE_RIGHT,   // ▷ Play button triangle (pointing right)
        TRIANGLE_LEFT,    // ◁ Triangle (pointing left)
        CIRCLE,           // ● Circle (existing)
        SQUARE,           // ■ Square
        DIAMOND,          // ◆ Diamond (rotated square)
        WATER_DROP,       // 💧 Symmetric water drop shape
        PODIUM            // 🏆 Three-step podium shape
    }

    /**
     * Mapping of node shape and display text for a node type.
     */
    public static class ShapeTextMapping {
        private final NodeShape shape;
        private final String text;

        public ShapeTextMapping(NodeShape shape, String text) {
            this.shape = shape;
            this.text = text;
        }

        public NodeShape getShape() { return shape; }
        public String getText() { return text; }
    }

    /**
     * Text styling configuration for text inside shapes.
     */
    public static class ShapeTextStyle {
        private final int fontSize;
        private final boolean bold;

        public ShapeTextStyle(int fontSize, boolean bold) {
            this.fontSize = fontSize;
            this.bold = bold;
        }

        public int getFontSize() { return fontSize; }
        public boolean isBold() { return bold; }

        public Font createFont() {
            int style = bold ? Font.BOLD : Font.PLAIN;
            return new Font(Font.SANS_SERIF, style, fontSize);
        }

        /**
         * Gets contrasting text color based on background color brightness.
         * @param backgroundColor The background color to contrast against
         * @return Black for light backgrounds, white for dark backgrounds
         */
        public Color getContrastingColor(Color backgroundColor) {
            int brightness = (backgroundColor.getRed() + backgroundColor.getGreen() + backgroundColor.getBlue()) / 3;
            return brightness > 128 ? Color.BLACK : Color.WHITE;
        }
    }
    
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
        LIGHT("Light",
                new String[]{
                    "F94144", "F3722C", "F8961E", "F9844A", "F9C74F",
                    "90BE6D", "43AA8B", "4D908E", "577590", "277DA1"
                },
                new TextStyle(10, Color.BLACK, 15, Color.WHITE, 180)),

        KEYLIME("Keylime",
                new String[]{
                    "65a30d", "84cc16", "a3e635", "bef264", "d9f99d",
                    "22c55e", "16a34a", "15803d", "166534", "14532d"
                },
                new TextStyle(10, new Color(45, 45, 45), 15, Color.WHITE, 190)),

        LAPLAND("Lapland",
                new String[]{
                    "2563eb", "3b82f6", "60a5fa", "93c5fd", "bfdbfe",
                    "0ea5e9", "0284c7", "0369a1", "075985", "0c4a6e"
                },
                new TextStyle(10, new Color(30, 41, 59), 15, new Color(248, 250, 252), 185)),

        NEMO("Nemo",
              new String[]{
                  "191970", "4169E1", "6495ED", "87CEEB", "00CED1",
                  "48D1CC", "20B2AA", "008B8B", "5F9EA0", "4682B4"
              },
              new TextStyle(10, new Color(25, 25, 112), 14, new Color(240, 248, 255), 190)),

        SUNSET_WARMTH("Sunset Warmth",
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
                  new TextStyle(11, new Color(34, 139, 34), 16, new Color(248, 248, 255), 200)),

        DRACULA("Dracula",
                new String[]{
                    "ff79c6", "bd93f9", "f1fa8c", "8be9fd", "ffb86c",
                    "50fa7b", "ff5555", "6272a4", "44475a", "282a36"
                },
                new TextStyle(10, new Color(248, 248, 242), 15, new Color(68, 71, 90), 200)),

        ONE_DARK("One Dark",
                 new String[]{
                     "56b6c2", "c678dd", "98c379", "e06c75", "d19a66",
                     "61afef", "e5c07b", "abb2bf", "5c6370", "3e4451"
                 },
                 new TextStyle(10, new Color(171, 178, 191), 15, new Color(62, 68, 81), 200)),

        OBSIDIAN("Obsidian",
                 new String[]{
                     "8b5cf6", "a855f7", "c084fc", "d8b4fe", "e9d5ff",
                     "7c3aed", "6d28d9", "5b21b6", "4c1d95", "3730a3"
                 },
                 new TextStyle(10, new Color(230, 230, 230), 15, new Color(55, 65, 81), 200)),

        SANNE("Sanne",
              new String[]{
                  "ff1493", "ff69b4", "ff6347", "ff4500", "dc143c",
                  "c71585", "ba55d3", "9370db", "8a2be2", "7b68ee"
              },
              new TextStyle(10, Color.WHITE, 15, new Color(42, 42, 46), 200));
        
        private final String displayName;
        private final String[] colors;
        private final TextStyle textStyle;
        private final Map<String, String> nodeTypeColorMap;

        Theme(String displayName, String[] colors, TextStyle textStyle) {
            this.displayName = displayName;
            this.colors = colors;
            this.textStyle = textStyle;
            this.nodeTypeColorMap = createNodeTypeColorMap();
        }

        private Map<String, String> createNodeTypeColorMap() {
            Map<String, String> map = new HashMap<>();
            switch (this) {
                case BOTANICAL:
                    // Current assignments for Botanical theme
                    map.put("storage", "4682B4");        // Steel Blue (index 5)
                    map.put("user", "A0522D");           // Sienna (index 9)
                    map.put("sacramento", "32CD32");     // Lime Green (index 10)
                    map.put("gr4j", "32CD32");           // Lime Green (index 10)
                    map.put("blackhole", "2F4F4F");     // Dark Slate Gray (index 2)
                    map.put("inflow", "2E8B57");         // Sea Green (index 14)
                    // Unused colors from botanical palette
                    map.put("unused_1", "228B22");       // Forest Green (index 0)
                    map.put("unused_2", "B8860B");       // Dark Goldenrod (index 1)
                    map.put("unused_3", "DAA520");       // Goldenrod (index 3)
                    map.put("unused_4", "556B2F");       // Dark Olive Green (index 4)
                    map.put("unused_5", "8B4513");       // Saddle Brown (index 6)
                    map.put("unused_6", "CCCC00");       // Yellow-lime (index 7)
                    map.put("unused_7", "1E90FF");       // Dodger Blue (index 8)
                    map.put("unused_8", "CD853F");       // Peru (index 11)
                    map.put("unused_9", "6495ED");       // Cornflower Blue (index 12)
                    map.put("unused_10", "D2691E");      // Chocolate (index 13)
                    break;
                case NEMO: // Nemo
                    // Map to similar concept colors in Nemo palette
                    map.put("storage", "48D1CC");        // Medium Turquoise (index 5) - water storage
                    map.put("user", "5F9EA0");           // Cadet Blue (index 8) - user interaction
                    map.put("sacramento", "20B2AA");     // Light Sea Green (index 6) - river/flow
                    map.put("gr4j", "20B2AA");           // Light Sea Green (index 6) - river/flow
                    map.put("blackhole", "191970");      // Midnight Blue (index 0) - void/dark
                    map.put("inflow", "4169E1");         // Royal Blue (index 1) - main flow
                    // Unused colors from Nemo palette
                    map.put("unused_1", "6495ED");       // Cornflower Blue (index 2)
                    map.put("unused_2", "87CEEB");       // Sky Blue (index 3)
                    map.put("unused_3", "00CED1");       // Dark Turquoise (index 4)
                    map.put("unused_4", "008B8B");       // Dark Cyan (index 7)
                    map.put("unused_5", "4682B4");       // Steel Blue (index 9)
                    break;
                case SUNSET_WARMTH:
                    // Sunset warmth color mappings with requested swaps
                    map.put("storage", "DAA520");        // Goldenrod (swapped from user)
                    map.put("user", "4682B4");           // Steel Blue (swapped from storage)
                    map.put("sacramento", "228B22");     // Forest Green
                    map.put("gr4j", "556B2F");           // Dark Olive Green
                    map.put("blackhole", "FF8C00");      // Dark Orange (swapped from routing)
                    map.put("routing", "2F4F4F");        // Dark Slate Gray (swapped from blackhole)
                    map.put("inflow", "4682B4");         // Steel Blue
                    break;
                case LIGHT:
                    // Light theme color mappings using the Light palette
                    map.put("storage", "277DA1");        // Dark Blue (index 9) - water storage
                    map.put("user", "F3722C");           // Orange-red (index 1) - user interaction
                    map.put("sacramento", "90BE6D");     // Green (index 5) - river/flow
                    map.put("gr4j", "90BE6D");           // Green (index 5) - river/flow
                    map.put("blackhole", "577590");      // Blue-gray (index 8) - blackhole
                    map.put("routing", "F9C74F");        // Yellow (index 4) - routing
                    map.put("inflow", "4D908E");         // Blue-green (index 7) - main flow
                    // Additional mappings using unused colors
                    map.put("outflow", "F94144");        // Red (index 0)
                    map.put("junction", "F8961E");       // Orange (index 2)
                    map.put("reservoir", "F9844A");      // Light orange (index 3)
                    map.put("diversion", "43AA8B");      // Teal (index 6)
                    break;
                default:
                    // For other themes, use cycling assignment
                    break;
            }
            return map;
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

        public Map<String, String> getNodeTypeColorMap() {
            return nodeTypeColorMap;
        }
    }
    
    // Node type to shape and text mappings
    private static final Map<String, ShapeTextMapping> NODE_TYPE_MAPPINGS = Map.of(
        "inflow", new ShapeTextMapping(NodeShape.TRIANGLE_RIGHT, "In"),
        "gr4j", new ShapeTextMapping(NodeShape.WATER_DROP, "G4"),
        "routing_node", new ShapeTextMapping(NodeShape.SQUARE, "Rt"),
        "sacramento", new ShapeTextMapping(NodeShape.WATER_DROP, "Sc"),
        "user", new ShapeTextMapping(NodeShape.PODIUM, "Us"),
        "storage", new ShapeTextMapping(NodeShape.TRIANGLE_UP, "St"),
        "blackhole", new ShapeTextMapping(NodeShape.CIRCLE, "Bh")
    );

    // Default shape text styling
    private static final ShapeTextStyle DEFAULT_SHAPE_TEXT_STYLE = new ShapeTextStyle(8, true);

    private Theme currentTheme;
    private final Map<String, Color> nodeTypeColors;
    private int nextColorIndex;
    
    /**
     * Creates a new NodeTheme with the default LIGHT theme.
     */
    public NodeTheme() {
        this(Theme.LIGHT);
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
     * Gets the color for a specific node type, using theme-specific mappings first,
     * then falls back to sequential assignment from the palette.
     * @param nodeType The type of the node
     * @return The color for this node type
     */
    public Color getColorForNodeType(String nodeType) {
        // Check theme-specific color mapping first
        Map<String, String> themeColorMap = currentTheme.getNodeTypeColorMap();
        String themeSpecificColor = themeColorMap.get(nodeType);
        if (themeSpecificColor != null) {
            return Color.decode("#" + themeSpecificColor);
        }

        // Fall back to sequential assignment from palette
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
     * @return The corresponding Theme enum, or LIGHT if not found
     */
    public static Theme themeFromString(String themeName) {
        if (themeName == null) {
            return Theme.LIGHT;
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
            return Theme.LIGHT; // Default fallback
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

    /**
     * Gets the shape for a specific node type.
     * @param nodeType The type of the node
     * @return The shape for this node type, or CIRCLE if not found
     */
    public NodeShape getShapeForNodeType(String nodeType) {
        ShapeTextMapping mapping = NODE_TYPE_MAPPINGS.get(nodeType);
        return mapping != null ? mapping.getShape() : NodeShape.CIRCLE;
    }

    /**
     * Gets the display text for a specific node type.
     * @param nodeType The type of the node
     * @return The display text for this node type, or auto-generated abbreviation if not found
     */
    public String getShapeTextForNodeType(String nodeType) {
        ShapeTextMapping mapping = NODE_TYPE_MAPPINGS.get(nodeType);
        return mapping != null ? mapping.getText() : generateAbbreviation(nodeType);
    }

    /**
     * Generates a 2-character abbreviation from a node type name.
     * Uses intelligent rules to create meaningful abbreviations.
     * @param nodeType The node type string
     * @return A 2-character abbreviation
     */
    private static String generateAbbreviation(String nodeType) {
        if (nodeType == null || nodeType.trim().isEmpty()) {
            return "??";
        }

        String clean = nodeType.trim().toLowerCase();

        // Handle common separators (underscore, dash, space)
        String[] parts = clean.split("[_\\-\\s]+");

        if (parts.length >= 2) {
            // Multiple words: take first letter of first two words
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        } else if (clean.length() >= 2) {
            // Single word: take first two letters
            return clean.substring(0, 2).toUpperCase();
        } else if (clean.length() == 1) {
            // Single character: duplicate it
            return (clean + clean).toUpperCase();
        } else {
            // Empty/invalid: fallback
            return "??";
        }
    }

    /**
     * Gets the shape text styling configuration.
     * @return The shape text style
     */
    public ShapeTextStyle getShapeTextStyle() {
        return DEFAULT_SHAPE_TEXT_STYLE;
    }

    /**
     * Gets the complete shape and text mapping for a node type.
     * @param nodeType The type of the node
     * @return The shape-text mapping, or null if not found
     */
    public ShapeTextMapping getShapeTextMapping(String nodeType) {
        return NODE_TYPE_MAPPINGS.get(nodeType);
    }
}