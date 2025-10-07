package com.kalix.ide.themes.unified;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents a color palette for a unified theme.
 * Contains semantic color roles that can be used across application, node, and syntax themes.
 *
 * This is the foundation of the unified theme system - all theme variations
 * derive their colors from this palette to ensure consistency.
 */
public class ColorPalette {

    // Core semantic colors - these define the theme's character
    private final Color primary;           // Main theme color (e.g., blue for Light, purple for Obsidian)
    private final Color secondary;         // Secondary accent color
    private final Color background;        // Main background color
    private final Color surface;           // Surface/panel background color
    private final Color onBackground;      // Text color on background
    private final Color onSurface;         // Text color on surface

    // Extended palette for more variety
    private final List<Color> accentColors;     // Additional colors for nodes, highlights, etc.
    private final Map<String, Color> semanticColors; // Named semantic colors

    // Theme metadata
    private final String name;
    private final boolean isDark;

    public ColorPalette(String name,
                       Color primary,
                       Color secondary,
                       Color background,
                       Color surface,
                       Color onBackground,
                       Color onSurface,
                       List<Color> accentColors,
                       boolean isDark) {
        this.name = name;
        this.primary = primary;
        this.secondary = secondary;
        this.background = background;
        this.surface = surface;
        this.onBackground = onBackground;
        this.onSurface = onSurface;
        this.accentColors = List.copyOf(accentColors);
        this.isDark = isDark;
        this.semanticColors = new HashMap<>();

        // Initialize semantic color mappings
        initializeSemanticColors();
    }

    private void initializeSemanticColors() {
        // Common semantic mappings
        semanticColors.put("error", isDark ? Color.decode("#ff6b6b") : Color.decode("#d63031"));
        semanticColors.put("warning", isDark ? Color.decode("#feca57") : Color.decode("#f39c12"));
        semanticColors.put("success", isDark ? Color.decode("#00b894") : Color.decode("#00a085"));
        semanticColors.put("info", primary);

        // Selection colors - more subtle version of primary
        Color selectionColor = new Color(
            primary.getRed(),
            primary.getGreen(),
            primary.getBlue(),
            80  // More transparent
        );
        semanticColors.put("selection", selectionColor);

        // Border colors
        semanticColors.put("border", isDark ?
            new Color(255, 255, 255, 30) :
            new Color(0, 0, 0, 30));
    }

    // Getters
    public String getName() { return name; }
    public boolean isDark() { return isDark; }
    public Color getPrimary() { return primary; }
    public Color getSecondary() { return secondary; }
    public Color getBackground() { return background; }
    public Color getSurface() { return surface; }
    public Color getOnBackground() { return onBackground; }
    public Color getOnSurface() { return onSurface; }
    public List<Color> getAccentColors() { return accentColors; }

    /**
     * Get a semantic color by name (e.g., "error", "warning", "selection")
     */
    public Color getSemanticColor(String name) {
        return semanticColors.get(name);
    }

    /**
     * Get an accent color by index, with wraparound for safety
     */
    public Color getAccentColor(int index) {
        if (accentColors.isEmpty()) {
            return primary;
        }
        return accentColors.get(index % accentColors.size());
    }

    /**
     * Create a more transparent version of any color
     */
    public Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * Create a darker/lighter variant of a color for the theme
     */
    public Color createVariant(Color baseColor, float factor) {
        if (isDark) {
            // For dark themes, factor > 1.0 makes lighter, < 1.0 makes darker
            return new Color(
                Math.min(255, (int)(baseColor.getRed() * factor)),
                Math.min(255, (int)(baseColor.getGreen() * factor)),
                Math.min(255, (int)(baseColor.getBlue() * factor))
            );
        } else {
            // For light themes, factor < 1.0 makes darker, > 1.0 makes lighter
            return new Color(
                Math.max(0, (int)(baseColor.getRed() * factor)),
                Math.max(0, (int)(baseColor.getGreen() * factor)),
                Math.max(0, (int)(baseColor.getBlue() * factor))
            );
        }
    }

    @Override
    public String toString() {
        return String.format("ColorPalette{name='%s', isDark=%s, primary=%s}",
                           name, isDark, String.format("#%06x", primary.getRGB() & 0xFFFFFF));
    }
}