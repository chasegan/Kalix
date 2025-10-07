package com.kalix.ide.themes.unified;

import java.awt.Color;
import java.util.Arrays;

/**
 * Unified theme definitions extracted from existing Light themes.
 * This demonstrates the migration from separate theme systems to the unified approach.
 */
public class LightThemeDefinitions {

    /**
     * Create the unified Light theme definition
     */
    public static UnifiedThemeDefinition createLightTheme() {
        // Extract colors from existing Light theme
        Color primary = Color.decode("#3b82f6");     // Blue from syntax theme
        Color secondary = Color.decode("#577590");   // Blue-gray
        Color background = Color.WHITE;              // Standard light background
        Color surface = Color.decode("#f5f5f5");     // Light gray surface
        Color onBackground = Color.BLACK;            // Black text on white
        Color onSurface = Color.decode("#2d2d2d");   // Dark gray text on surfaces

        // Create accent colors from existing Light node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#F94144"), // Red
            Color.decode("#F3722C"), // Orange-red
            Color.decode("#F8961E"), // Orange
            Color.decode("#F9C74F"), // Yellow
            Color.decode("#90BE6D"), // Green
            Color.decode("#43AA8B"), // Teal
            Color.decode("#4D908E"), // Dark teal
            Color.decode("#577590"), // Blue-gray
            Color.decode("#277DA1")  // Blue
        );

        ColorPalette palette = new ColorPalette(
            "Light",
            primary,
            secondary,
            background,
            surface,
            onBackground,
            onSurface,
            accentColors,
            false // not dark
        );

        return new UnifiedThemeDefinition("Light", palette);
    }

    /**
     * Create the unified Keylime theme definition
     */
    public static UnifiedThemeDefinition createKeylimeTheme() {
        Color primary = Color.decode("#65a30d");     // Main lime green
        Color secondary = Color.decode("#84cc16");   // Lighter lime
        Color background = Color.WHITE;
        Color surface = Color.decode("#f5f5f5");
        Color onBackground = Color.decode("#1a1a1a"); // Dark text
        Color onSurface = Color.decode("#2d2d2d");

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#65a30d"), Color.decode("#84cc16"), Color.decode("#a3e635"),
            Color.decode("#bef264"), Color.decode("#d9f99d"), Color.decode("#22c55e"),
            Color.decode("#16a34a"), Color.decode("#15803d"), Color.decode("#166534")
        );

        ColorPalette palette = new ColorPalette(
            "Keylime", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false
        );

        return new UnifiedThemeDefinition("Keylime", palette);
    }

    /**
     * Create the unified Lapland theme definition
     */
    public static UnifiedThemeDefinition createLaplandTheme() {
        Color primary = Color.decode("#2563eb");     // Main nordic blue
        Color secondary = Color.decode("#3b82f6");   // Lighter blue
        Color background = Color.WHITE;
        Color surface = Color.decode("#f8fafc");     // Very light blue-gray
        Color onBackground = Color.decode("#1e293b"); // Dark slate
        Color onSurface = Color.decode("#334155");

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#2563eb"), Color.decode("#3b82f6"), Color.decode("#60a5fa"),
            Color.decode("#93c5fd"), Color.decode("#bfdbfe"), Color.decode("#0ea5e9"),
            Color.decode("#0284c7"), Color.decode("#0369a1"), Color.decode("#075985")
        );

        ColorPalette palette = new ColorPalette(
            "Lapland", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false
        );

        return new UnifiedThemeDefinition("Lapland", palette);
    }

    /**
     * Create the unified Nemo theme definition
     */
    public static UnifiedThemeDefinition createNemoTheme() {
        Color primary = Color.decode("#191970");     // Midnight blue
        Color secondary = Color.decode("#ff6f00");   // Clownfish orange
        Color background = Color.WHITE;
        Color surface = Color.decode("#f0f8ff");     // Alice blue
        Color onBackground = Color.decode("#0d3d56"); // Deep sea blue
        Color onSurface = Color.decode("#1a365d");

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#191970"), Color.decode("#4169E1"), Color.decode("#6495ED"),
            Color.decode("#87CEEB"), Color.decode("#00CED1"), Color.decode("#48D1CC"),
            Color.decode("#20B2AA"), Color.decode("#008B8B"), Color.decode("#5F9EA0")
        );

        ColorPalette palette = new ColorPalette(
            "Nemo", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false
        );

        return new UnifiedThemeDefinition("Nemo", palette);
    }

    /**
     * Create the unified Sunset Warmth theme definition
     */
    public static UnifiedThemeDefinition createSunsetWarmthTheme() {
        Color primary = Color.decode("#FF6B35");     // Vibrant orange
        Color secondary = Color.decode("#F7931E");   // Orange
        Color background = Color.decode("#fff8dc");  // Cornsilk (warm white)
        Color surface = Color.decode("#ffefd5");     // Papaya whip
        Color onBackground = Color.decode("#8b4513"); // Saddle brown
        Color onSurface = Color.decode("#a0522d");    // Sienna

        // Create accent colors from existing Sunset Warmth node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#FF6B35"), Color.decode("#F7931E"), Color.decode("#FFD23F"),
            Color.decode("#06FFA5"), Color.decode("#4ECDC4"), Color.decode("#45B7D1"),
            Color.decode("#96CEB4"), Color.decode("#FECA57"), Color.decode("#FF9FF3"),
            Color.decode("#54A0FF")
        );

        ColorPalette palette = new ColorPalette(
            "Sunset Warmth", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false
        );

        return new UnifiedThemeDefinition("Sunset Warmth", palette);
    }
}