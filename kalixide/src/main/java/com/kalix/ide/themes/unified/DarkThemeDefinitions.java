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
        // Create exact color mapping from original sanne-theme.properties
        java.util.Map<String, String> sanneColors = new java.util.HashMap<>();

        // Base backgrounds - rich dark grays with subtle warmth
        sanneColors.put("Component.background", "#2a2a2e");
        sanneColors.put("Panel.background", "#2a2a2e");
        sanneColors.put("OptionPane.background", "#2a2a2e");
        sanneColors.put("PopupMenu.background", "#2a2a2e");
        sanneColors.put("MenuItem.background", "#2a2a2e");

        // Text areas and input fields - slightly lighter dark with subtle pink tint
        sanneColors.put("TextArea.background", "#383840");
        sanneColors.put("TextPane.background", "#383840");
        sanneColors.put("TextField.background", "#2a2a2e");
        sanneColors.put("FormattedTextField.background", "#2a2a2e");
        sanneColors.put("PasswordField.background", "#2a2a2e");
        sanneColors.put("EditorPane.background", "#383840");

        // Text colors - crisp whites and light grays
        sanneColors.put("Component.foreground", "#e8e8e8");
        sanneColors.put("TextArea.foreground", "#f0f0f0");
        sanneColors.put("TextPane.foreground", "#f0f0f0");
        sanneColors.put("TextField.foreground", "#f0f0f0");
        sanneColors.put("Label.foreground", "#e8e8e8");

        // Selection colors - exciting pink theme with vibrant accents
        sanneColors.put("TextArea.selectionBackground", "#ff69b4");
        sanneColors.put("TextPane.selectionBackground", "#ff69b4");
        sanneColors.put("TextField.selectionBackground", "#ff69b4");
        sanneColors.put("Component.focusedBorderColor", "#ff1493");

        // Buttons - dark with bold pink accents
        sanneColors.put("Button.background", "#404048");
        sanneColors.put("Button.foreground", "#e8e8e8");
        sanneColors.put("Button.focusedBorderColor", "#ff1493");
        sanneColors.put("Button.hoverBackground", "#ff69b460");
        sanneColors.put("Button.pressedBackground", "#ff1493");
        sanneColors.put("Button.default.background", "#ff1493");
        sanneColors.put("Button.default.foreground", "#ffffff");
        sanneColors.put("Button.default.hoverBackground", "#ff69b4");

        // Menu bar and menus - dark with pink highlights
        sanneColors.put("MenuBar.background", "#2a2a2e");
        sanneColors.put("Menu.background", "#2a2a2e");
        sanneColors.put("Menu.foreground", "#e8e8e8");
        sanneColors.put("MenuItem.foreground", "#e8e8e8");
        sanneColors.put("MenuItem.hoverBackground", "#ff69b440");
        sanneColors.put("MenuItem.selectionBackground", "#ff69b460");

        // Toolbar - sleek dark
        sanneColors.put("ToolBar.background", "#2a2a2e");
        sanneColors.put("ToolBar.borderColor", "#404048");

        // Borders and separators - subtle gray with pink undertone
        sanneColors.put("Component.borderColor", "#505058");
        sanneColors.put("Separator.foreground", "#505058");

        // Tables - dark with exciting pink selection
        sanneColors.put("Table.background", "#383840");
        sanneColors.put("Table.foreground", "#f0f0f0");
        sanneColors.put("Table.selectionBackground", "#ff69b4");
        sanneColors.put("Table.selectionForeground", "#ffffff");
        sanneColors.put("Table.gridColor", "#404048");

        // Lists - consistent with tables
        sanneColors.put("List.background", "#383840");
        sanneColors.put("List.foreground", "#f0f0f0");
        sanneColors.put("List.selectionBackground", "#ff69b4");
        sanneColors.put("List.selectionForeground", "#ffffff");

        // Trees - consistent styling
        sanneColors.put("Tree.background", "#383840");
        sanneColors.put("Tree.foreground", "#f0f0f0");
        sanneColors.put("Tree.selectionBackground", "#ff69b4");
        sanneColors.put("Tree.selectionForeground", "#ffffff");

        // Progress bars - vibrant pink accent
        sanneColors.put("ProgressBar.background", "#404048");
        sanneColors.put("ProgressBar.foreground", "#ff1493");
        sanneColors.put("ProgressBar.selectionBackground", "#ffffff");
        sanneColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - dark with pink hover effects
        sanneColors.put("TabbedPane.background", "#2a2a2e");
        sanneColors.put("TabbedPane.foreground", "#e8e8e8");
        sanneColors.put("TabbedPane.selectedBackground", "#383840");
        sanneColors.put("TabbedPane.hoverColor", "#ff69b430");

        // Scrollbars - sleek dark with pink accents
        sanneColors.put("ScrollBar.track", "#2a2a2e");
        sanneColors.put("ScrollBar.thumb", "#505058");
        sanneColors.put("ScrollBar.hoverThumbColor", "#ff69b4");
        sanneColors.put("ScrollBar.pressedThumbColor", "#ff1493");

        // Status bar area
        sanneColors.put("StatusBar.background", "#2a2a2e");
        sanneColors.put("StatusBar.foreground", "#e8e8e8");

        // Additional dark elements with pink touches
        sanneColors.put("CheckBox.background", "#2a2a2e");
        sanneColors.put("RadioButton.background", "#2a2a2e");
        sanneColors.put("Spinner.background", "#2a2a2e");

        // Dialog backgrounds
        sanneColors.put("Dialog.background", "#2a2a2e");

        // TitledBorder styling for dark theme
        sanneColors.put("TitledBorder.titleColor", "#e8e8e8");

        // Custom map panel background - rich dark
        sanneColors.put("MapPanel.background", "#2a2a2e");

        // Sanne-specific custom properties with pink accents
        sanneColors.put("MapPanel.gridlineColor", "#ff69b430");
        sanneColors.put("Component.splitPaneDividerColor", "#505058");

        // Additional exciting pink elements
        sanneColors.put("CheckBox.icon.checkmarkColor", "#ff1493");
        sanneColors.put("RadioButton.icon.centerColor", "#ff1493");

        // Tooltip styling
        sanneColors.put("ToolTip.background", "#ff1493");
        sanneColors.put("ToolTip.foreground", "#ffffff");

        // ComboBox (drop-down) styling - dark with pink accents
        sanneColors.put("ComboBox.background", "#383840");
        sanneColors.put("ComboBox.foreground", "#f0f0f0");
        sanneColors.put("ComboBox.buttonBackground", "#ff1493");
        sanneColors.put("ComboBox.buttonArrowColor", "#ffffff");
        sanneColors.put("ComboBox.selectionBackground", "#ff69b4");
        sanneColors.put("ComboBox.selectionForeground", "#ffffff");

        // Split pane with dark background and pink highlights
        sanneColors.put("SplitPane.background", "#2a2a2e");
        sanneColors.put("SplitPaneDivider.draggingColor", "#505058");
        sanneColors.put("SplitPane.dividerSize", "8");
        sanneColors.put("SplitPane.oneTouchButtonColor", "#ff69b4");
        sanneColors.put("SplitPane.oneTouchArrowColor", "#ffffff");

        // Create minimal palette for backward compatibility
        Color primary = Color.decode("#ff1493");     // Deep pink
        Color secondary = Color.decode("#ff69b4");   // Hot pink
        Color background = Color.decode("#2a2a2e");  // Dark gray (main background)
        Color surface = Color.decode("#383840");     // Slightly lighter dark (text areas)
        Color onBackground = Color.decode("#f0f0f0"); // Light text on background
        Color onSurface = Color.decode("#e8e8e8");    // Text on surfaces

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#ff1493"), Color.decode("#ff69b4"), Color.decode("#ff6347"),
            Color.decode("#ff4500"), Color.decode("#dc143c"), Color.decode("#c71585"),
            Color.decode("#ba55d3"), Color.decode("#9370db"), Color.decode("#8a2be2")
        );

        ColorPalette palette = new ColorPalette(
            "Sanne", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, true // isDark = true
        );

        return new UnifiedThemeDefinition("Sanne", palette, sanneColors);
    }

    /**
     * Create the unified Obsidian theme definition
     */
    public static UnifiedThemeDefinition createObsidianTheme() {
        // Create exact color mapping from original obsidian-theme.properties
        java.util.Map<String, String> obsidianColors = new java.util.HashMap<>();

        // Base backgrounds - deeper than standard dark theme
        obsidianColors.put("Component.background", "#1e1e1e");
        obsidianColors.put("Panel.background", "#1a1a1a");
        obsidianColors.put("OptionPane.background", "#1a1a1a");
        obsidianColors.put("PopupMenu.background", "#1e1e1e");
        obsidianColors.put("MenuItem.background", "#1e1e1e");

        // Text areas and input fields - very dark
        obsidianColors.put("TextArea.background", "#0d1117");
        obsidianColors.put("TextPane.background", "#0d1117");
        obsidianColors.put("TextField.background", "#0d1117");
        obsidianColors.put("FormattedTextField.background", "#0d1117");
        obsidianColors.put("PasswordField.background", "#0d1117");
        obsidianColors.put("EditorPane.background", "#0d1117");

        // Text colors - light gray/white for good contrast
        obsidianColors.put("Component.foreground", "#e6e6e6");
        obsidianColors.put("TextArea.foreground", "#f0f6fc");
        obsidianColors.put("TextPane.foreground", "#f0f6fc");
        obsidianColors.put("TextField.foreground", "#f0f6fc");
        obsidianColors.put("Label.foreground", "#e6e6e6");

        // Selection colors - subtle purple
        obsidianColors.put("TextArea.selectionBackground", "#3d2a5c");
        obsidianColors.put("TextPane.selectionBackground", "#3d2a5c");
        obsidianColors.put("TextField.selectionBackground", "#3d2a5c");
        obsidianColors.put("Component.focusedBorderColor", "#8b5cf6");

        // Buttons - dark with purple accents
        obsidianColors.put("Button.background", "#2d2d30");
        obsidianColors.put("Button.foreground", "#e6e6e6");
        obsidianColors.put("Button.focusedBorderColor", "#8b5cf6");
        obsidianColors.put("Button.hoverBackground", "#3d3d42");
        obsidianColors.put("Button.pressedBackground", "#1e1e1e");
        obsidianColors.put("Button.default.background", "#7c3aed");
        obsidianColors.put("Button.default.foreground", "#ffffff");
        obsidianColors.put("Button.default.hoverBackground", "#8b5cf6");

        // Menu bar and menus - very dark
        obsidianColors.put("MenuBar.background", "#161b22");
        obsidianColors.put("Menu.background", "#161b22");
        obsidianColors.put("Menu.foreground", "#e6e6e6");
        obsidianColors.put("MenuItem.foreground", "#e6e6e6");
        obsidianColors.put("MenuItem.hoverBackground", "#21262d");
        obsidianColors.put("MenuItem.selectionBackground", "#21262d");

        // Toolbar - dark to match
        obsidianColors.put("ToolBar.background", "#161b22");
        obsidianColors.put("ToolBar.borderColor", "#30363d");

        // Borders and separators - subtle
        obsidianColors.put("Component.borderColor", "#30363d");
        obsidianColors.put("Separator.foreground", "#30363d");

        // Tables - dark theme
        obsidianColors.put("Table.background", "#0d1117");
        obsidianColors.put("Table.foreground", "#f0f6fc");
        obsidianColors.put("Table.selectionBackground", "#3d2a5c");
        obsidianColors.put("Table.selectionForeground", "#ffffff");
        obsidianColors.put("Table.gridColor", "#21262d");

        // Lists - consistent with tables
        obsidianColors.put("List.background", "#0d1117");
        obsidianColors.put("List.foreground", "#f0f6fc");
        obsidianColors.put("List.selectionBackground", "#3d2a5c");
        obsidianColors.put("List.selectionForeground", "#ffffff");

        // Trees - consistent styling
        obsidianColors.put("Tree.background", "#0d1117");
        obsidianColors.put("Tree.foreground", "#f0f6fc");
        obsidianColors.put("Tree.selectionBackground", "#3d2a5c");
        obsidianColors.put("Tree.selectionForeground", "#ffffff");

        // Progress bars - purple accent
        obsidianColors.put("ProgressBar.background", "#21262d");
        obsidianColors.put("ProgressBar.foreground", "#8b5cf6");
        obsidianColors.put("ProgressBar.selectionBackground", "#ffffff");
        obsidianColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - dark with subtle hover
        obsidianColors.put("TabbedPane.background", "#1a1a1a");
        obsidianColors.put("TabbedPane.foreground", "#e6e6e6");
        obsidianColors.put("TabbedPane.selectedBackground", "#0d1117");
        obsidianColors.put("TabbedPane.hoverColor", "#21262d");

        // Scrollbars - minimal dark design
        obsidianColors.put("ScrollBar.track", "#161b22");
        obsidianColors.put("ScrollBar.thumb", "#30363d");
        obsidianColors.put("ScrollBar.hoverThumbColor", "#484f58");
        obsidianColors.put("ScrollBar.pressedThumbColor", "#6e7681");

        // Status bar area
        obsidianColors.put("StatusBar.background", "#161b22");
        obsidianColors.put("StatusBar.foreground", "#e6e6e6");

        // Split pane dividers - dark theme appropriate
        obsidianColors.put("SplitPane.background", "#1a1a1a");
        obsidianColors.put("SplitPaneDivider.draggingColor", "#30363d");
        obsidianColors.put("SplitPane.dividerSize", "8");
        obsidianColors.put("Component.splitPaneDividerColor", "#30363d");

        // ComboBox (drop-downs) - dark theme appropriate
        obsidianColors.put("ComboBox.background", "#0d1117");
        obsidianColors.put("ComboBox.foreground", "#f0f6fc");
        obsidianColors.put("ComboBox.buttonBackground", "#8b5cf6");
        obsidianColors.put("ComboBox.buttonArrowColor", "#ffffff");
        obsidianColors.put("ComboBox.selectionBackground", "#3d2a5c");
        obsidianColors.put("ComboBox.selectionForeground", "#ffffff");

        // TitledBorder - fix for black text in dark themes
        obsidianColors.put("TitledBorder.titleColor", "#e6e6e6");

        // Create minimal palette for backward compatibility
        Color primary = Color.decode("#8b5cf6");     // Purple
        Color secondary = Color.decode("#a855f7");   // Medium light purple
        Color background = Color.decode("#1a1a1a");  // Very dark background
        Color surface = Color.decode("#1e1e1e");     // Slightly lighter surface
        Color onBackground = Color.decode("#f0f6fc"); // Light text
        Color onSurface = Color.decode("#e6e6e6");    // Light gray text

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#8b5cf6"), Color.decode("#a855f7"), Color.decode("#c084fc"),
            Color.decode("#d8b4fe"), Color.decode("#e9d5ff"), Color.decode("#7c3aed"),
            Color.decode("#6d28d9"), Color.decode("#5b21b6"), Color.decode("#4c1d95")
        );

        ColorPalette palette = new ColorPalette(
            "Obsidian", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, true
        );

        return new UnifiedThemeDefinition("Obsidian", palette, obsidianColors);
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
        // Create exact color mapping from original botanical-theme.properties
        java.util.Map<String, String> botanicalColors = new java.util.HashMap<>();

        // Base backgrounds - warm cream with more visible botanical greens
        botanicalColors.put("Component.background", "#f5f8f3");
        botanicalColors.put("Panel.background", "#edf5e8");
        botanicalColors.put("OptionPane.background", "#edf5e8");
        botanicalColors.put("PopupMenu.background", "#f5f8f3");
        botanicalColors.put("MenuItem.background", "#f5f8f3");

        // Text areas and input fields - soft cream background with subtle green tint
        botanicalColors.put("TextArea.background", "#fdfffe");
        botanicalColors.put("TextPane.background", "#fdfffe");
        botanicalColors.put("TextField.background", "#fefefe");
        botanicalColors.put("FormattedTextField.background", "#fefefe");
        botanicalColors.put("PasswordField.background", "#fefefe");
        botanicalColors.put("EditorPane.background", "#fdfffe");

        // Text colors - deep forest green for excellent readability
        botanicalColors.put("Component.foreground", "#2d4a2d");
        botanicalColors.put("TextArea.foreground", "#1a331a");
        botanicalColors.put("TextPane.foreground", "#1a331a");
        botanicalColors.put("TextField.foreground", "#1a331a");
        botanicalColors.put("Label.foreground", "#2d4a2d");

        // Selection colors - soft matcha green theme
        botanicalColors.put("TextArea.selectionBackground", "#d4edd4");
        botanicalColors.put("TextPane.selectionBackground", "#d4edd4");
        botanicalColors.put("TextField.selectionBackground", "#d4edd4");
        botanicalColors.put("Component.focusedBorderColor", "#6b9d6b");

        // Buttons - cream with botanical green accents
        botanicalColors.put("Button.background", "#f5f7f3");
        botanicalColors.put("Button.foreground", "#2d4a2d");
        botanicalColors.put("Button.focusedBorderColor", "#6b9d6b");
        botanicalColors.put("Button.hoverBackground", "#f0f5f0");
        botanicalColors.put("Button.pressedBackground", "#e8f0e8");
        botanicalColors.put("Button.default.background", "#5a8a5a");
        botanicalColors.put("Button.default.foreground", "#ffffff");
        botanicalColors.put("Button.default.hoverBackground", "#6b9d6b");

        // Menu bar and menus - prominent botanical green
        botanicalColors.put("MenuBar.background", "#e8f2e0");
        botanicalColors.put("Menu.background", "#e8f2e0");
        botanicalColors.put("Menu.foreground", "#2d4a2d");
        botanicalColors.put("MenuItem.foreground", "#2d4a2d");
        botanicalColors.put("MenuItem.hoverBackground", "#d8ead0");
        botanicalColors.put("MenuItem.selectionBackground", "#c8e0b8");

        // Toolbar - visible botanical green background
        botanicalColors.put("ToolBar.background", "#e8f2e0");
        botanicalColors.put("ToolBar.borderColor", "#c8e0b8");

        // Borders and separators - soft sage
        botanicalColors.put("Component.borderColor", "#d9e3d9");
        botanicalColors.put("Separator.foreground", "#d9e3d9");

        // Tables - clean with botanical green selection
        botanicalColors.put("Table.background", "#fdfffe");
        botanicalColors.put("Table.foreground", "#1a331a");
        botanicalColors.put("Table.selectionBackground", "#e8f0e8");
        botanicalColors.put("Table.selectionForeground", "#1a331a");
        botanicalColors.put("Table.gridColor", "#f0f5f0");

        // Lists - consistent with tables
        botanicalColors.put("List.background", "#fdfffe");
        botanicalColors.put("List.foreground", "#1a331a");
        botanicalColors.put("List.selectionBackground", "#e8f0e8");
        botanicalColors.put("List.selectionForeground", "#1a331a");

        // Trees - consistent styling
        botanicalColors.put("Tree.background", "#fdfffe");
        botanicalColors.put("Tree.foreground", "#1a331a");
        botanicalColors.put("Tree.selectionBackground", "#e8f0e8");
        botanicalColors.put("Tree.selectionForeground", "#1a331a");

        // Progress bars - botanical green accent
        botanicalColors.put("ProgressBar.background", "#f0f5f0");
        botanicalColors.put("ProgressBar.foreground", "#5a8a5a");
        botanicalColors.put("ProgressBar.selectionBackground", "#ffffff");
        botanicalColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - prominent botanical green theme
        botanicalColors.put("TabbedPane.background", "#e8f2e0");
        botanicalColors.put("TabbedPane.foreground", "#2d4a2d");
        botanicalColors.put("TabbedPane.selectedBackground", "#fdfffe");
        botanicalColors.put("TabbedPane.hoverColor", "#d8ead0");

        // Scrollbars - rich botanical green design
        botanicalColors.put("ScrollBar.track", "#e8f2e0");
        botanicalColors.put("ScrollBar.thumb", "#7fb069");
        botanicalColors.put("ScrollBar.hoverThumbColor", "#6b9d57");
        botanicalColors.put("ScrollBar.pressedThumbColor", "#5a8a4a");

        // Status bar area - botanical green
        botanicalColors.put("StatusBar.background", "#e8f2e0");
        botanicalColors.put("StatusBar.foreground", "#2d4a2d");

        // Additional botanical elements
        botanicalColors.put("CheckBox.background", "#f5f8f3");
        botanicalColors.put("RadioButton.background", "#f5f8f3");
        botanicalColors.put("Spinner.background", "#f5f8f3");

        // Dialog backgrounds
        botanicalColors.put("Dialog.background", "#edf5e8");

        // Custom map panel background - very subtle warm cream
        botanicalColors.put("MapPanel.background", "#fefef9");

        // Botanical-specific custom properties
        botanicalColors.put("MapPanel.gridlineColor", "#c8e0b8");
        botanicalColors.put("Component.splitPaneDividerColor", "#c8e0b8");

        // Additional rich green elements
        botanicalColors.put("Spinner.buttonBackground", "#7fb069");
        botanicalColors.put("Spinner.buttonArrowColor", "#ffffff");
        botanicalColors.put("ComboBox.buttonBackground", "#7fb069");
        botanicalColors.put("ComboBox.buttonArrowColor", "#ffffff");

        // Create minimal palette for backward compatibility
        Color primary = Color.decode("#5a8a5a");     // Botanical green
        Color secondary = Color.decode("#6b9d6b");   // Lighter botanical green
        Color background = Color.decode("#edf5e8");  // Light botanical background
        Color surface = Color.decode("#f5f8f3");     // Very light botanical surface
        Color onBackground = Color.decode("#1a331a"); // Dark forest green text
        Color onSurface = Color.decode("#2d4a2d");    // Medium forest green text

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

        return new UnifiedThemeDefinition("Botanical", palette, botanicalColors);
    }
}