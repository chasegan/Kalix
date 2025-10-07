package com.kalix.ide.themes.unified;

import com.kalix.ide.themes.NodeTheme;
import java.awt.Color;
import java.util.List;

/**
 * Specification for generating node themes from a color palette.
 * Defines how palette colors are distributed across nodes.
 */
public class NodeThemeSpec {

    /**
     * Generate a NodeTheme.Theme from a color palette.
     * Uses intelligent color distribution from the palette.
     */
    public static NodeTheme.Theme generateNodeTheme(ColorPalette palette) {
        // Convert palette colors to hex strings
        String[] nodeColors = generateNodeColorArray(palette);

        // Create text style from palette
        NodeTheme.TextStyle textStyle = generateTextStyle(palette);

        // Use reflection to create the theme since NodeTheme.Theme constructor is package-private
        // For now, return one of the existing themes as a placeholder
        // TODO: Need to refactor NodeTheme.Theme to support dynamic creation
        return NodeTheme.Theme.LIGHT; // Placeholder - will be replaced with proper dynamic creation
    }

    /**
     * Generate an array of node colors from the palette.
     * Distributes palette colors intelligently across node types.
     */
    public static String[] generateNodeColorArray(ColorPalette palette) {
        List<Color> accentColors = palette.getAccentColors();

        // If we have enough accent colors, use them directly
        if (accentColors.size() >= 8) {
            return accentColors.subList(0, 8).stream()
                .map(NodeThemeSpec::colorToHex)
                .toArray(String[]::new);
        }

        // Generate a balanced set of colors from the palette
        Color[] colors = new Color[10];

        // Start with palette colors
        colors[0] = palette.getPrimary();
        colors[1] = palette.getSecondary();

        // Add accent colors if available
        for (int i = 0; i < Math.min(accentColors.size(), 6); i++) {
            colors[i + 2] = accentColors.get(i);
        }

        // Fill remaining slots with variations of primary and secondary
        int remaining = 10 - Math.min(accentColors.size() + 2, 10);
        for (int i = 0; i < remaining; i++) {
            Color baseColor = (i % 2 == 0) ? palette.getPrimary() : palette.getSecondary();
            float factor = 1.0f + (i * 0.2f); // Create variations
            colors[accentColors.size() + 2 + i] = palette.createVariant(baseColor, factor);
        }

        // Convert to hex strings
        String[] result = new String[colors.length];
        for (int i = 0; i < colors.length; i++) {
            result[i] = colorToHex(colors[i]);
        }

        return result;
    }

    /**
     * Generate text style from palette
     */
    public static NodeTheme.TextStyle generateTextStyle(ColorPalette palette) {
        int fontSize = 10;
        Color textColor = palette.getOnSurface();
        int yOffset = 15;
        Color backgroundColor = palette.getSurface();
        int backgroundAlpha = 200;

        return new NodeTheme.TextStyle(fontSize, textColor, yOffset, backgroundColor, backgroundAlpha);
    }

    /**
     * Generate semantic node type color mappings from the palette.
     * This provides intelligent defaults for specific node types.
     */
    public static java.util.Map<String, String> generateNodeTypeColorMap(ColorPalette palette) {
        java.util.Map<String, String> colorMap = new java.util.HashMap<>();

        // Get colors from palette
        List<Color> accents = palette.getAccentColors();
        Color primary = palette.getPrimary();
        Color secondary = palette.getSecondary();

        // Assign colors semantically
        colorMap.put("inflow", colorToHex(primary));           // Main color for input
        colorMap.put("storage", colorToHex(secondary));        // Secondary color for storage
        colorMap.put("user", colorToHex(palette.getSemanticColor("warning"))); // Warning color for demand
        colorMap.put("blackhole", colorToHex(palette.isDark() ?
            palette.createVariant(palette.getOnSurface(), 0.6f) :
            palette.createVariant(palette.getOnSurface(), 1.2f))); // Muted color

        // Use accent colors for other node types
        if (accents.size() > 0) {
            colorMap.put("gr4j", colorToHex(accents.get(0)));
            colorMap.put("sacramento", colorToHex(accents.get(accents.size() > 1 ? 1 : 0)));
            colorMap.put("routing", colorToHex(accents.get(accents.size() > 2 ? 2 : 0)));
            colorMap.put("confluence", colorToHex(accents.get(accents.size() > 3 ? 3 : 0)));
        }

        return colorMap;
    }

    private static String colorToHex(Color color) {
        if (color == null) return "000000";
        return String.format("%06x", color.getRGB() & 0xFFFFFF);
    }
}