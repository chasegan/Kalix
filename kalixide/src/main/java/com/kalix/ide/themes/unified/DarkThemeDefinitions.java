package com.kalix.ide.themes.unified;

import java.awt.Color;
import java.util.Arrays;

/**
 * Unified theme definitions for dark themes.
 * Extracts colors from existing dark theme systems and creates unified definitions.
 */
public class DarkThemeDefinitions {

    /**
     * Create the unified Sanne theme definition
     */
    public static UnifiedThemeDefinition createSanneTheme() {
        // Sanne theme - exciting dark theme with vibrant pink accents
        Color primary = Color.decode("#ff1493");     // Deep pink
        Color secondary = Color.decode("#ff69b4");   // Hot pink
        Color background = Color.decode("#2a2a2e");  // Dark gray (main background)
        Color surface = Color.decode("#383840");     // Slightly lighter dark (text areas)
        Color onBackground = Color.decode("#f0f0f0"); // Light text on background
        Color onSurface = Color.decode("#e8e8e8");    // Text on surfaces

        // Create accent colors from existing Sanne node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#ff1493"), Color.decode("#ff69b4"), Color.decode("#ff6347"),
            Color.decode("#ff4500"), Color.decode("#dc143c"), Color.decode("#c71585"),
            Color.decode("#ba55d3"), Color.decode("#9370db"), Color.decode("#8a2be2")
        );

        ColorPalette palette = new ColorPalette(
            "Sanne", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, true // isDark = true
        );

        return new UnifiedThemeDefinition("Sanne", palette);
    }

    /**
     * Create the unified Obsidian theme definition
     */
    public static UnifiedThemeDefinition createObsidianTheme() {
        // Obsidian theme - dark theme with purple accents and deeper blacks
        Color primary = Color.decode("#8b5cf6");     // Purple
        Color secondary = Color.decode("#a855f7");   // Medium light purple
        Color background = Color.decode("#1a1a1a");  // Very dark background
        Color surface = Color.decode("#1e1e1e");     // Slightly lighter surface
        Color onBackground = Color.decode("#f0f6fc"); // Light text
        Color onSurface = Color.decode("#e6e6e6");    // Light gray text

        // Create accent colors from existing Obsidian node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#8b5cf6"), Color.decode("#a855f7"), Color.decode("#c084fc"),
            Color.decode("#d8b4fe"), Color.decode("#e9d5ff"), Color.decode("#7c3aed"),
            Color.decode("#6d28d9"), Color.decode("#5b21b6"), Color.decode("#4c1d95")
        );

        ColorPalette palette = new ColorPalette(
            "Obsidian", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, true
        );

        return new UnifiedThemeDefinition("Obsidian", palette);
    }

    /**
     * Create the unified Dracula theme definition
     */
    public static UnifiedThemeDefinition createDraculaTheme() {
        // Dracula theme - popular dark theme with purple and pink accents
        Color primary = Color.decode("#bd93f9");     // Purple
        Color secondary = Color.decode("#ff79c6");   // Pink
        Color background = Color.decode("#282a36");  // Dark purple-gray
        Color surface = Color.decode("#44475a");     // Lighter surface
        Color onBackground = Color.decode("#f8f8f2"); // Light text
        Color onSurface = Color.decode("#f8f8f2");    // Same light text

        // Create accent colors from existing Dracula node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#ff79c6"), Color.decode("#bd93f9"), Color.decode("#f1fa8c"),
            Color.decode("#8be9fd"), Color.decode("#ffb86c"), Color.decode("#50fa7b"),
            Color.decode("#ff5555"), Color.decode("#6272a4"), Color.decode("#44475a")
        );

        ColorPalette palette = new ColorPalette(
            "Dracula", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, true
        );

        return new UnifiedThemeDefinition("Dracula", palette);
    }

    /**
     * Create the unified One Dark theme definition
     */
    public static UnifiedThemeDefinition createOneDarkTheme() {
        // One Dark theme - VSCode inspired dark theme
        Color primary = Color.decode("#61afef");     // Blue
        Color secondary = Color.decode("#c678dd");   // Purple
        Color background = Color.decode("#282c34");  // Dark gray
        Color surface = Color.decode("#3e4451");     // Lighter surface
        Color onBackground = Color.decode("#abb2bf"); // Light gray text
        Color onSurface = Color.decode("#abb2bf");    // Same light gray

        // Create accent colors from existing One Dark node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#56b6c2"), Color.decode("#c678dd"), Color.decode("#98c379"),
            Color.decode("#e06c75"), Color.decode("#d19a66"), Color.decode("#61afef"),
            Color.decode("#e5c07b"), Color.decode("#abb2bf"), Color.decode("#5c6370")
        );

        ColorPalette palette = new ColorPalette(
            "One Dark", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, true
        );

        return new UnifiedThemeDefinition("One Dark", palette);
    }

    /**
     * Create the unified Botanical theme definition
     */
    public static UnifiedThemeDefinition createBotanicalTheme() {
        // Botanical theme - earth tones and natural colors
        Color primary = Color.decode("#228B22");     // Forest green
        Color secondary = Color.decode("#DAA520");   // Goldenrod
        Color background = Color.decode("#f8f8ff");  // Ghost white
        Color surface = Color.decode("#f5f5f5");     // White smoke
        Color onBackground = Color.decode("#228B22"); // Forest green text
        Color onSurface = Color.decode("#2F4F4F");    // Dark slate gray

        // Create accent colors from existing Botanical node theme
        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#228B22"), Color.decode("#B8860B"), Color.decode("#2F4F4F"),
            Color.decode("#DAA520"), Color.decode("#556B2F"), Color.decode("#4682B4"),
            Color.decode("#8B4513"), Color.decode("#CCCC00"), Color.decode("#1E90FF"),
            Color.decode("#A0522D"), Color.decode("#32CD32"), Color.decode("#CD853F"),
            Color.decode("#6495ED"), Color.decode("#D2691E"), Color.decode("#2E8B57")
        );

        ColorPalette palette = new ColorPalette(
            "Botanical", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false // Light theme
        );

        return new UnifiedThemeDefinition("Botanical", palette);
    }
}