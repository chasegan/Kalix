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
        // Create exact color mapping from resolved FlatLightLaf colors
        java.util.Map<String, String> lightColors = new java.util.HashMap<>();

        // Base backgrounds
        lightColors.put("Component.background", "#f2f2f2");
        lightColors.put("Panel.background", "#f2f2f2");
        lightColors.put("OptionPane.background", "#f2f2f2");
        lightColors.put("PopupMenu.background", "#ffffff");
        lightColors.put("MenuItem.background", "#ffffff");
        lightColors.put("Dialog.background", "#f2f2f2");

        // Text areas and input fields
        lightColors.put("TextArea.background", "#ffffff");
        lightColors.put("TextPane.background", "#ffffff");
        lightColors.put("TextField.background", "#ffffff");
        lightColors.put("FormattedTextField.background", "#ffffff");
        lightColors.put("PasswordField.background", "#ffffff");
        lightColors.put("EditorPane.background", "#ffffff");

        // Text colors
        lightColors.put("Component.foreground", "#000000");
        lightColors.put("TextArea.foreground", "#000000");
        lightColors.put("TextPane.foreground", "#000000");
        lightColors.put("TextField.foreground", "#000000");
        lightColors.put("Label.foreground", "#000000");

        // Selection colors
        lightColors.put("TextArea.selectionBackground", "#2675bf");
        lightColors.put("TextPane.selectionBackground", "#2675bf");
        lightColors.put("TextField.selectionBackground", "#2675bf");
        lightColors.put("Component.focusedBorderColor", "#89b0d4");

        // Buttons
        lightColors.put("Button.background", "#ffffff");
        lightColors.put("Button.foreground", "#000000");
        lightColors.put("Button.focusedBorderColor", "#89b0d4");
        lightColors.put("Button.hoverBackground", "#f7f7f7");
        lightColors.put("Button.pressedBackground", "#e6e6e6");
        lightColors.put("Button.default.background", "#ffffff");
        lightColors.put("Button.default.foreground", "#000000");
        lightColors.put("Button.default.hoverBackground", "#f7f7f7");

        // Menu bar and menus
        lightColors.put("MenuBar.background", "#ffffff");
        lightColors.put("Menu.background", "#ffffff");
        lightColors.put("Menu.foreground", "#000000");
        lightColors.put("MenuItem.foreground", "#000000");
        lightColors.put("MenuItem.hoverBackground", "#f2f2f2");
        lightColors.put("MenuItem.selectionBackground", "#2675bf");

        // Toolbar
        lightColors.put("ToolBar.background", "#f2f2f2");
        lightColors.put("ToolBar.borderColor", "#c2c2c2");

        // Borders and separators
        lightColors.put("Component.borderColor", "#c2c2c2");
        lightColors.put("Separator.foreground", "#cecece");

        // Tables
        lightColors.put("Table.background", "#ffffff");
        lightColors.put("Table.foreground", "#000000");
        lightColors.put("Table.selectionBackground", "#2675bf");
        lightColors.put("Table.selectionForeground", "#ffffff");
        lightColors.put("Table.gridColor", "#ebebeb");

        // Lists
        lightColors.put("List.background", "#ffffff");
        lightColors.put("List.foreground", "#000000");
        lightColors.put("List.selectionBackground", "#2675bf");
        lightColors.put("List.selectionForeground", "#ffffff");

        // Trees
        lightColors.put("Tree.background", "#ffffff");
        lightColors.put("Tree.foreground", "#000000");
        lightColors.put("Tree.selectionBackground", "#2675bf");
        lightColors.put("Tree.selectionForeground", "#ffffff");

        // Progress bars
        lightColors.put("ProgressBar.background", "#d1d1d1");
        lightColors.put("ProgressBar.foreground", "#2285e1");
        lightColors.put("ProgressBar.selectionBackground", "#000000");
        lightColors.put("ProgressBar.selectionForeground", "#ffffff");

        // Tabs
        lightColors.put("TabbedPane.background", "#f2f2f2");
        lightColors.put("TabbedPane.foreground", "#000000");
        lightColors.put("TabbedPane.selectedBackground", "#f2f2f2");
        lightColors.put("TabbedPane.hoverColor", "#e0e0e0");

        // Scrollbars
        lightColors.put("ScrollBar.track", "#f5f5f5");
        lightColors.put("ScrollBar.thumb", "#dcdcdc");
        lightColors.put("ScrollBar.hoverThumbColor", "#c3c3c3");
        lightColors.put("ScrollBar.pressedThumbColor", "#a9a9a9");

        // Status bar area
        lightColors.put("StatusBar.background", "#f2f2f2");
        lightColors.put("StatusBar.foreground", "#000000");

        // Additional elements
        lightColors.put("CheckBox.background", "#f2f2f2");
        lightColors.put("CheckBox.icon.checkmarkColor", "#4e9de7");
        lightColors.put("RadioButton.background", "#f2f2f2");
        lightColors.put("RadioButton.icon.centerColor", "#000000");
        lightColors.put("Spinner.background", "#ffffff");
        lightColors.put("Spinner.buttonBackground", "#fafafa");
        lightColors.put("Spinner.buttonArrowColor", "#666666");

        // ComboBox
        lightColors.put("ComboBox.background", "#ffffff");
        lightColors.put("ComboBox.foreground", "#000000");
        lightColors.put("ComboBox.buttonBackground", "#ffffff");
        lightColors.put("ComboBox.buttonArrowColor", "#666666");
        lightColors.put("ComboBox.selectionBackground", "#2675bf");
        lightColors.put("ComboBox.selectionForeground", "#ffffff");

        // Split pane
        lightColors.put("SplitPane.background", "#f2f2f2");
        lightColors.put("SplitPaneDivider.draggingColor", "#c2c2c2");
        lightColors.put("Component.splitPaneDividerColor", "#c2c2c2");
        lightColors.put("SplitPane.oneTouchButtonColor", "#ffffff");
        lightColors.put("SplitPane.oneTouchArrowColor", "#000000");

        // Internal frames
        lightColors.put("InternalFrame.activeTitleBackground", "#ffffff");
        lightColors.put("InternalFrame.inactiveTitleBackground", "#fafafa");
        lightColors.put("Window.background", "#ffffff");

        // Title bar properties for light theme
        lightColors.put("TitlePane.background", "#f2f2f2");
        lightColors.put("TitlePane.unifiedBackground", "false");

        // Sliders
        lightColors.put("Slider.thumb", "#2285e1");
        lightColors.put("Slider.track", "#c4c4c4");
        lightColors.put("Slider.trackColor", "#c4c4c4");
        lightColors.put("Slider.trackValueColor", "#2285e1");
        lightColors.put("Slider.focus", "#9c9c9c");
        lightColors.put("Slider.hoverThumbColor", "#1c78ce");
        lightColors.put("Slider.pressedThumbColor", "#1a70c0");

        // Tooltips
        lightColors.put("ToolTip.background", "#fafafa");
        lightColors.put("ToolTip.foreground", "#000000");

        // TitledBorder
        lightColors.put("TitledBorder.titleColor", "#000000");

        // Custom map panel background - use white like other input areas
        lightColors.put("MapPanel.background", "#ffffff");
        lightColors.put("MapPanel.gridlineColor", "#ebebeb");

        // Create minimal palette for backward compatibility
        Color primary = Color.decode("#2675bf");     // Selection blue
        Color secondary = Color.decode("#89b0d4");   // Focused border blue
        Color background = Color.decode("#f2f2f2");  // Panel background
        Color surface = Color.decode("#ffffff");     // Input surface
        Color onBackground = Color.decode("#000000"); // Text on background
        Color onSurface = Color.decode("#000000");    // Text on surface

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#2675bf"), Color.decode("#89b0d4"), Color.decode("#4e9de7"),
            Color.decode("#2285e1"), Color.decode("#1c78ce"), Color.decode("#1a70c0"),
            Color.decode("#f7f7f7"), Color.decode("#e6e6e6"), Color.decode("#e0e0e0")
        );

        ColorPalette palette = new ColorPalette(
            "Light", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false
        );

        return new UnifiedThemeDefinition("Light", palette, lightColors);
    }

    /**
     * Create the unified Keylime theme definition
     */
    public static UnifiedThemeDefinition createKeylimeTheme() {
        // Create exact color mapping from original keylime-theme.properties
        java.util.Map<String, String> keylimeColors = new java.util.HashMap<>();

        // Base backgrounds - clean whites and very light grays
        keylimeColors.put("Component.background", "#ffffff");
        keylimeColors.put("Panel.background", "#fafafa");
        keylimeColors.put("OptionPane.background", "#fafafa");
        keylimeColors.put("PopupMenu.background", "#ffffff");
        keylimeColors.put("MenuItem.background", "#ffffff");

        // Text areas and input fields - pure white with subtle border
        keylimeColors.put("TextArea.background", "#ffffff");
        keylimeColors.put("TextPane.background", "#ffffff");
        keylimeColors.put("TextField.background", "#ffffff");
        keylimeColors.put("FormattedTextField.background", "#ffffff");
        keylimeColors.put("PasswordField.background", "#ffffff");
        keylimeColors.put("EditorPane.background", "#ffffff");

        // Text colors - dark for excellent readability
        keylimeColors.put("Component.foreground", "#2d2d2d");
        keylimeColors.put("TextArea.foreground", "#1a1a1a");
        keylimeColors.put("TextPane.foreground", "#1a1a1a");
        keylimeColors.put("TextField.foreground", "#1a1a1a");
        keylimeColors.put("Label.foreground", "#2d2d2d");

        // Selection colors - lime green theme
        keylimeColors.put("TextArea.selectionBackground", "#d4ff8f");
        keylimeColors.put("TextPane.selectionBackground", "#d4ff8f");
        keylimeColors.put("TextField.selectionBackground", "#d4ff8f");
        keylimeColors.put("Component.focusedBorderColor", "#84cc16");

        // Buttons - clean with lime accents
        keylimeColors.put("Button.background", "#f5f5f5");
        keylimeColors.put("Button.foreground", "#2d2d2d");
        keylimeColors.put("Button.focusedBorderColor", "#84cc16");
        keylimeColors.put("Button.hoverBackground", "#f0f9ff");
        keylimeColors.put("Button.pressedBackground", "#e5e5e5");
        keylimeColors.put("Button.default.background", "#65a30d");
        keylimeColors.put("Button.default.foreground", "#ffffff");
        keylimeColors.put("Button.default.hoverBackground", "#84cc16");

        // Menu bar and menus - clean light
        keylimeColors.put("MenuBar.background", "#f8f8f8");
        keylimeColors.put("Menu.background", "#f8f8f8");
        keylimeColors.put("Menu.foreground", "#2d2d2d");
        keylimeColors.put("MenuItem.foreground", "#2d2d2d");
        keylimeColors.put("MenuItem.hoverBackground", "#f0f9ff");
        keylimeColors.put("MenuItem.selectionBackground", "#ecfccb");

        // Toolbar - subtle background
        keylimeColors.put("ToolBar.background", "#f8f8f8");
        keylimeColors.put("ToolBar.borderColor", "#e5e5e5");

        // Borders and separators - subtle gray
        keylimeColors.put("Component.borderColor", "#e0e0e0");
        keylimeColors.put("Separator.foreground", "#e0e0e0");

        // Tables - clean with lime selection
        keylimeColors.put("Table.background", "#ffffff");
        keylimeColors.put("Table.foreground", "#1a1a1a");
        keylimeColors.put("Table.selectionBackground", "#ecfccb");
        keylimeColors.put("Table.selectionForeground", "#1a1a1a");
        keylimeColors.put("Table.gridColor", "#f0f0f0");

        // Lists - consistent with tables
        keylimeColors.put("List.background", "#ffffff");
        keylimeColors.put("List.foreground", "#1a1a1a");
        keylimeColors.put("List.selectionBackground", "#ecfccb");
        keylimeColors.put("List.selectionForeground", "#1a1a1a");

        // Trees - consistent styling
        keylimeColors.put("Tree.background", "#ffffff");
        keylimeColors.put("Tree.foreground", "#1a1a1a");
        keylimeColors.put("Tree.selectionBackground", "#ecfccb");
        keylimeColors.put("Tree.selectionForeground", "#1a1a1a");

        // Progress bars - lime accent
        keylimeColors.put("ProgressBar.background", "#f0f0f0");
        keylimeColors.put("ProgressBar.foreground", "#65a30d");
        keylimeColors.put("ProgressBar.selectionBackground", "#ffffff");
        keylimeColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - clean with subtle hover
        keylimeColors.put("TabbedPane.background", "#fafafa");
        keylimeColors.put("TabbedPane.foreground", "#2d2d2d");
        keylimeColors.put("TabbedPane.selectedBackground", "#ffffff");
        keylimeColors.put("TabbedPane.hoverColor", "#f0f9ff");

        // Scrollbars - minimal light design
        keylimeColors.put("ScrollBar.track", "#f5f5f5");
        keylimeColors.put("ScrollBar.thumb", "#d0d0d0");
        keylimeColors.put("ScrollBar.hoverThumbColor", "#b0b0b0");
        keylimeColors.put("ScrollBar.pressedThumbColor", "#a0a0a0");

        // Status bar area
        keylimeColors.put("StatusBar.background", "#f8f8f8");
        keylimeColors.put("StatusBar.foreground", "#2d2d2d");

        // Custom map panel background and gridline colors
        keylimeColors.put("MapPanel.background", "#ffffff");
        keylimeColors.put("MapPanel.gridlineColor", "#f0f0f0");

        // Title bar properties for light theme
        keylimeColors.put("TitlePane.background", "#f8fcf4");
        keylimeColors.put("TitlePane.unifiedBackground", "false");

        // Create minimal palette for backward compatibility
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

        return new UnifiedThemeDefinition("Keylime", palette, keylimeColors);
    }

    /**
     * Create the unified Lapland theme definition
     */
    public static UnifiedThemeDefinition createLaplandTheme() {
        // Create exact color mapping from original lapland-theme.properties
        java.util.Map<String, String> laplandColors = new java.util.HashMap<>();

        // Base backgrounds - crisp whites with more pronounced cool blue tints
        laplandColors.put("Component.background", "#f8fafc");
        laplandColors.put("Panel.background", "#f1f5f9");
        laplandColors.put("OptionPane.background", "#f1f5f9");
        laplandColors.put("PopupMenu.background", "#f8fafc");
        laplandColors.put("MenuItem.background", "#f8fafc");

        // Text areas and input fields - very subtle pale blue for editor, blue tint for fields
        laplandColors.put("TextArea.background", "#f8fbff");
        laplandColors.put("TextPane.background", "#f8fbff");
        laplandColors.put("TextField.background", "#f8fafc");
        laplandColors.put("FormattedTextField.background", "#f8fafc");
        laplandColors.put("PasswordField.background", "#f8fafc");
        laplandColors.put("EditorPane.background", "#f8fbff");

        // Text colors - dark slate for excellent readability
        laplandColors.put("Component.foreground", "#334155");
        laplandColors.put("TextArea.foreground", "#1e293b");
        laplandColors.put("TextPane.foreground", "#1e293b");
        laplandColors.put("TextField.foreground", "#1e293b");
        laplandColors.put("Label.foreground", "#334155");

        // Selection colors - cool Arctic blue theme
        laplandColors.put("TextArea.selectionBackground", "#bfdbfe");
        laplandColors.put("TextPane.selectionBackground", "#bfdbfe");
        laplandColors.put("TextField.selectionBackground", "#bfdbfe");
        laplandColors.put("Component.focusedBorderColor", "#3b82f6");

        // Buttons - clean with cool blue accents
        laplandColors.put("Button.background", "#f1f5f9");
        laplandColors.put("Button.foreground", "#334155");
        laplandColors.put("Button.focusedBorderColor", "#3b82f6");
        laplandColors.put("Button.hoverBackground", "#f0f9ff");
        laplandColors.put("Button.pressedBackground", "#e2e8f0");
        laplandColors.put("Button.default.background", "#2563eb");
        laplandColors.put("Button.default.foreground", "#ffffff");
        laplandColors.put("Button.default.hoverBackground", "#3b82f6");

        // Menu bar and menus - clean Nordic light with blue tint
        laplandColors.put("MenuBar.background", "#f1f5f9");
        laplandColors.put("Menu.background", "#f1f5f9");
        laplandColors.put("Menu.foreground", "#334155");
        laplandColors.put("MenuItem.foreground", "#334155");
        laplandColors.put("MenuItem.hoverBackground", "#f0f9ff");
        laplandColors.put("MenuItem.selectionBackground", "#dbeafe");

        // Toolbar - subtle cool background with blue tint
        laplandColors.put("ToolBar.background", "#f1f5f9");
        laplandColors.put("ToolBar.borderColor", "#cbd5e1");

        // Borders and separators - cool gray
        laplandColors.put("Component.borderColor", "#cbd5e1");
        laplandColors.put("Separator.foreground", "#cbd5e1");

        // Tables - clean with Arctic blue selection
        laplandColors.put("Table.background", "#fcfcfc");
        laplandColors.put("Table.foreground", "#1e293b");
        laplandColors.put("Table.selectionBackground", "#dbeafe");
        laplandColors.put("Table.selectionForeground", "#1e293b");
        laplandColors.put("Table.gridColor", "#f1f5f9");

        // Lists - consistent with tables
        laplandColors.put("List.background", "#fcfcfc");
        laplandColors.put("List.foreground", "#1e293b");
        laplandColors.put("List.selectionBackground", "#dbeafe");
        laplandColors.put("List.selectionForeground", "#1e293b");

        // Trees - consistent styling
        laplandColors.put("Tree.background", "#fcfcfc");
        laplandColors.put("Tree.foreground", "#1e293b");
        laplandColors.put("Tree.selectionBackground", "#dbeafe");
        laplandColors.put("Tree.selectionForeground", "#1e293b");

        // Progress bars - Arctic blue accent
        laplandColors.put("ProgressBar.background", "#f1f5f9");
        laplandColors.put("ProgressBar.foreground", "#2563eb");
        laplandColors.put("ProgressBar.selectionBackground", "#ffffff");
        laplandColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - clean with cool hover and blue tint
        laplandColors.put("TabbedPane.background", "#f1f5f9");
        laplandColors.put("TabbedPane.foreground", "#334155");
        laplandColors.put("TabbedPane.selectedBackground", "#f8fafc");
        laplandColors.put("TabbedPane.hoverColor", "#f0f9ff");

        // Scrollbars - minimal Nordic design
        laplandColors.put("ScrollBar.track", "#f1f5f9");
        laplandColors.put("ScrollBar.thumb", "#cbd5e1");
        laplandColors.put("ScrollBar.hoverThumbColor", "#94a3b8");
        laplandColors.put("ScrollBar.pressedThumbColor", "#64748b");

        // Status bar area
        laplandColors.put("StatusBar.background", "#f1f5f9");
        laplandColors.put("StatusBar.foreground", "#334155");

        // Additional Nordic-inspired elements
        laplandColors.put("CheckBox.background", "#f8fafc");
        laplandColors.put("RadioButton.background", "#f8fafc");
        laplandColors.put("Spinner.background", "#f8fafc");

        // Dialog backgrounds
        laplandColors.put("Dialog.background", "#f1f5f9");

        // Custom map panel background and gridline colors
        laplandColors.put("MapPanel.background", "#fefbfa");
        laplandColors.put("MapPanel.gridlineColor", "#f0f0f0");

        // Title bar properties for light theme
        laplandColors.put("TitlePane.background", "#f1f5f9");
        laplandColors.put("TitlePane.unifiedBackground", "false");

        // Create minimal palette for backward compatibility
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

        return new UnifiedThemeDefinition("Lapland", palette, laplandColors);
    }

    /**
     * Create the unified Nemo theme definition
     */
    public static UnifiedThemeDefinition createNemoTheme() {
        // Create exact color mapping from original finding-nemo-theme.properties
        java.util.Map<String, String> nemoColors = new java.util.HashMap<>();

        // Base backgrounds - ocean blues with coral reef warmth
        nemoColors.put("Component.background", "#e6f3ff");
        nemoColors.put("Panel.background", "#cce7ff");
        nemoColors.put("OptionPane.background", "#cce7ff");
        nemoColors.put("PopupMenu.background", "#e6f3ff");
        nemoColors.put("MenuItem.background", "#e6f3ff");

        // Text areas and input fields - sandy seafloor and clear water
        nemoColors.put("TextArea.background", "#fff8e1");
        nemoColors.put("TextPane.background", "#fff8e1");
        nemoColors.put("TextField.background", "#f0f8ff");
        nemoColors.put("FormattedTextField.background", "#f0f8ff");
        nemoColors.put("PasswordField.background", "#f0f8ff");
        nemoColors.put("EditorPane.background", "#fff8e1");

        // Text colors - deep sea blue for readability
        nemoColors.put("Component.foreground", "#0d3d56");
        nemoColors.put("TextArea.foreground", "#1a4a5c");
        nemoColors.put("TextPane.foreground", "#1a4a5c");
        nemoColors.put("TextField.foreground", "#1a4a5c");
        nemoColors.put("Label.foreground", "#0d3d56");

        // Selection colors - clownfish orange and coral
        nemoColors.put("TextArea.selectionBackground", "#ffcc80");
        nemoColors.put("TextPane.selectionBackground", "#ffcc80");
        nemoColors.put("TextField.selectionBackground", "#ffcc80");
        nemoColors.put("Component.focusedBorderColor", "#ff6f00");

        // Buttons - coral reef colors
        nemoColors.put("Button.background", "#ffecb3");
        nemoColors.put("Button.foreground", "#0d3d56");
        nemoColors.put("Button.focusedBorderColor", "#ff6f00");
        nemoColors.put("Button.hoverBackground", "#ffcc80");
        nemoColors.put("Button.pressedBackground", "#ffb74d");
        nemoColors.put("Button.default.background", "#ff6f00");
        nemoColors.put("Button.default.foreground", "#ffffff");
        nemoColors.put("Button.default.hoverBackground", "#ff8f00");

        // Menu bar and menus - ocean surface
        nemoColors.put("MenuBar.background", "#b3e5fc");
        nemoColors.put("Menu.background", "#b3e5fc");
        nemoColors.put("Menu.foreground", "#0d3d56");
        nemoColors.put("MenuItem.foreground", "#0d3d56");
        nemoColors.put("MenuItem.hoverBackground", "#81d4fa");
        nemoColors.put("MenuItem.selectionBackground", "#ffcc80");

        // Toolbar - tropical lagoon
        nemoColors.put("ToolBar.background", "#b3e5fc");
        nemoColors.put("ToolBar.borderColor", "#4fc3f7");

        // Borders and separators - coral reef structure
        nemoColors.put("Component.borderColor", "#4fc3f7");
        nemoColors.put("Separator.foreground", "#4fc3f7");

        // Tables - clear tropical water
        nemoColors.put("Table.background", "#e1f5fe");
        nemoColors.put("Table.foreground", "#1a4a5c");
        nemoColors.put("Table.selectionBackground", "#ffcc80");
        nemoColors.put("Table.selectionForeground", "#0d3d56");
        nemoColors.put("Table.gridColor", "#b3e5fc");

        // Lists - consistent with tables
        nemoColors.put("List.background", "#e1f5fe");
        nemoColors.put("List.foreground", "#1a4a5c");
        nemoColors.put("List.selectionBackground", "#ffcc80");
        nemoColors.put("List.selectionForeground", "#0d3d56");

        // Trees - kelp forest
        nemoColors.put("Tree.background", "#e1f5fe");
        nemoColors.put("Tree.foreground", "#1a4a5c");
        nemoColors.put("Tree.selectionBackground", "#ffcc80");
        nemoColors.put("Tree.selectionForeground", "#0d3d56");

        // Progress bars - clownfish stripe pattern inspiration
        nemoColors.put("ProgressBar.background", "#b3e5fc");
        nemoColors.put("ProgressBar.foreground", "#ff6f00");
        nemoColors.put("ProgressBar.selectionBackground", "#ffffff");
        nemoColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - coral formations
        nemoColors.put("TabbedPane.background", "#cce7ff");
        nemoColors.put("TabbedPane.foreground", "#0d3d56");
        nemoColors.put("TabbedPane.selectedBackground", "#e1f5fe");
        nemoColors.put("TabbedPane.hoverColor", "#81d4fa");

        // Scrollbars - sea current design
        nemoColors.put("ScrollBar.track", "#e1f5fe");
        nemoColors.put("ScrollBar.thumb", "#4fc3f7");
        nemoColors.put("ScrollBar.hoverThumbColor", "#29b6f6");
        nemoColors.put("ScrollBar.pressedThumbColor", "#0288d1");

        // Status bar area - ocean floor
        nemoColors.put("StatusBar.background", "#b3e5fc");
        nemoColors.put("StatusBar.foreground", "#0d3d56");

        // Additional marine elements
        nemoColors.put("CheckBox.background", "#e1f5fe");
        nemoColors.put("RadioButton.background", "#e1f5fe");
        nemoColors.put("Spinner.background", "#f0f8ff");

        // Dialog backgrounds - underwater cave
        nemoColors.put("Dialog.background", "#cce7ff");

        // Custom map panel background - sandy ocean floor with coral hints
        nemoColors.put("MapPanel.background", "#fff3e0");

        // Custom gridline color - slightly darker for better visibility in ocean theme
        nemoColors.put("MapPanel.gridlineColor", "#d0d0d0");

        // Title bar properties for light theme
        nemoColors.put("TitlePane.background", "#e1f4ff");
        nemoColors.put("TitlePane.unifiedBackground", "false");

        // Split pane dividers - sea anemone inspired
        nemoColors.put("SplitPane.background", "#cce7ff");
        nemoColors.put("SplitPaneDivider.draggingColor", "#4fc3f7");
        nemoColors.put("SplitPane.dividerSize", "8");
        nemoColors.put("Component.splitPaneDividerColor", "#4fc3f7");

        // Create minimal palette for backward compatibility (colors won't be used due to overrides)
        Color primary = Color.decode("#ff6f00");     // Clownfish orange
        Color secondary = Color.decode("#4fc3f7");   // Ocean blue
        Color background = Color.decode("#cce7ff");  // Light ocean blue
        Color surface = Color.decode("#e6f3ff");     // Very light blue surface
        Color onBackground = Color.decode("#0d3d56"); // Deep sea blue text
        Color onSurface = Color.decode("#0d3d56");    // Deep sea blue text

        java.util.List<Color> accentColors = Arrays.asList(
            Color.decode("#191970"), Color.decode("#4169E1"), Color.decode("#6495ED"),
            Color.decode("#87CEEB"), Color.decode("#00CED1"), Color.decode("#48D1CC"),
            Color.decode("#20B2AA"), Color.decode("#008B8B"), Color.decode("#5F9EA0")
        );

        ColorPalette palette = new ColorPalette(
            "Nemo", primary, secondary, background, surface,
            onBackground, onSurface, accentColors, false
        );

        return new UnifiedThemeDefinition("Nemo", palette, nemoColors);
    }

    /**
     * Create the unified Sunset Warmth theme definition
     */
    public static UnifiedThemeDefinition createSunsetWarmthTheme() {
        // Create exact color mapping from original sunset-warmth-theme.properties
        java.util.Map<String, String> sunsetWarmthColors = new java.util.HashMap<>();

        // Base backgrounds - warm cream with sunset orange tints
        sunsetWarmthColors.put("Component.background", "#fef7f0");
        sunsetWarmthColors.put("Panel.background", "#fdf4e3");
        sunsetWarmthColors.put("OptionPane.background", "#fdf4e3");
        sunsetWarmthColors.put("PopupMenu.background", "#fef7f0");
        sunsetWarmthColors.put("MenuItem.background", "#fef7f0");

        // Text areas and input fields - very warm cream background
        sunsetWarmthColors.put("TextArea.background", "#fffcf8");
        sunsetWarmthColors.put("TextPane.background", "#fffcf8");
        sunsetWarmthColors.put("TextField.background", "#fffcf8");
        sunsetWarmthColors.put("FormattedTextField.background", "#fffcf8");
        sunsetWarmthColors.put("PasswordField.background", "#fffcf8");
        sunsetWarmthColors.put("EditorPane.background", "#fffcf8");

        // Text colors - dark warm brown for readability
        sunsetWarmthColors.put("Component.foreground", "#8b4513");
        sunsetWarmthColors.put("TextArea.foreground", "#654321");
        sunsetWarmthColors.put("TextPane.foreground", "#654321");
        sunsetWarmthColors.put("TextField.foreground", "#654321");
        sunsetWarmthColors.put("Label.foreground", "#8b4513");

        // Selection colors - warm sunset orange theme
        sunsetWarmthColors.put("TextArea.selectionBackground", "#feca57");
        sunsetWarmthColors.put("TextPane.selectionBackground", "#feca57");
        sunsetWarmthColors.put("TextField.selectionBackground", "#feca57");
        sunsetWarmthColors.put("Component.focusedBorderColor", "#f7931e");

        // Buttons - warm sunset orange with vibrant accents
        sunsetWarmthColors.put("Button.background", "#fef3e8");
        sunsetWarmthColors.put("Button.foreground", "#8b4513");
        sunsetWarmthColors.put("Button.focusedBorderColor", "#f7931e");
        sunsetWarmthColors.put("Button.hoverBackground", "#feca57");
        sunsetWarmthColors.put("Button.pressedBackground", "#f7931e");
        sunsetWarmthColors.put("Button.default.background", "#ff6b35");
        sunsetWarmthColors.put("Button.default.foreground", "#ffffff");
        sunsetWarmthColors.put("Button.default.hoverBackground", "#f7931e");

        // Menu bar and menus - vibrant sunset colors
        sunsetWarmthColors.put("MenuBar.background", "#feca57");
        sunsetWarmthColors.put("Menu.background", "#feca57");
        sunsetWarmthColors.put("Menu.foreground", "#8b4513");
        sunsetWarmthColors.put("MenuItem.foreground", "#8b4513");
        sunsetWarmthColors.put("MenuItem.hoverBackground", "#f7931e");
        sunsetWarmthColors.put("MenuItem.selectionBackground", "#ff6b35");

        // Toolbar - warm sunset background
        sunsetWarmthColors.put("ToolBar.background", "#feca57");
        sunsetWarmthColors.put("ToolBar.borderColor", "#f7931e");

        // Borders and separators - warm orange
        sunsetWarmthColors.put("Component.borderColor", "#f7d794");
        sunsetWarmthColors.put("Separator.foreground", "#f7d794");

        // Tables - clean with warm sunset selection
        sunsetWarmthColors.put("Table.background", "#fffcf8");
        sunsetWarmthColors.put("Table.foreground", "#654321");
        sunsetWarmthColors.put("Table.selectionBackground", "#feca57");
        sunsetWarmthColors.put("Table.selectionForeground", "#654321");
        sunsetWarmthColors.put("Table.gridColor", "#fef7f0");

        // Lists - consistent with tables
        sunsetWarmthColors.put("List.background", "#fffcf8");
        sunsetWarmthColors.put("List.foreground", "#654321");
        sunsetWarmthColors.put("List.selectionBackground", "#feca57");
        sunsetWarmthColors.put("List.selectionForeground", "#654321");

        // Trees - consistent styling
        sunsetWarmthColors.put("Tree.background", "#fffcf8");
        sunsetWarmthColors.put("Tree.foreground", "#654321");
        sunsetWarmthColors.put("Tree.selectionBackground", "#feca57");
        sunsetWarmthColors.put("Tree.selectionForeground", "#654321");

        // Progress bars - sunset orange accent
        sunsetWarmthColors.put("ProgressBar.background", "#fef7f0");
        sunsetWarmthColors.put("ProgressBar.foreground", "#ff6b35");
        sunsetWarmthColors.put("ProgressBar.selectionBackground", "#ffffff");
        sunsetWarmthColors.put("ProgressBar.selectionForeground", "#000000");

        // Tabs - vibrant sunset theme
        sunsetWarmthColors.put("TabbedPane.background", "#feca57");
        sunsetWarmthColors.put("TabbedPane.foreground", "#8b4513");
        sunsetWarmthColors.put("TabbedPane.selectedBackground", "#fffcf8");
        sunsetWarmthColors.put("TabbedPane.hoverColor", "#f7931e");

        // Scrollbars - vibrant sunset design
        sunsetWarmthColors.put("ScrollBar.track", "#feca57");
        sunsetWarmthColors.put("ScrollBar.thumb", "#ff6b35");
        sunsetWarmthColors.put("ScrollBar.hoverThumbColor", "#f7931e");
        sunsetWarmthColors.put("ScrollBar.pressedThumbColor", "#e85a2b");

        // Status bar area - sunset colors
        sunsetWarmthColors.put("StatusBar.background", "#feca57");
        sunsetWarmthColors.put("StatusBar.foreground", "#8b4513");

        // Additional sunset elements
        sunsetWarmthColors.put("CheckBox.background", "#fef7f0");
        sunsetWarmthColors.put("RadioButton.background", "#fef7f0");
        sunsetWarmthColors.put("Spinner.background", "#fef7f0");

        // Dialog backgrounds
        sunsetWarmthColors.put("Dialog.background", "#fdf4e3");

        // Custom map panel background - very subtle warm cream
        sunsetWarmthColors.put("MapPanel.background", "#fffef9");

        // Sunset-specific custom properties
        sunsetWarmthColors.put("MapPanel.gridlineColor", "#fbb668");
        sunsetWarmthColors.put("Component.splitPaneDividerColor", "#f7931e");

        // Title bar properties for light theme
        sunsetWarmthColors.put("TitlePane.background", "#feca57");
        sunsetWarmthColors.put("TitlePane.unifiedBackground", "false");

        // Additional vibrant sunset elements
        sunsetWarmthColors.put("Spinner.buttonBackground", "#ff6b35");
        sunsetWarmthColors.put("Spinner.buttonArrowColor", "#ffffff");
        sunsetWarmthColors.put("ComboBox.buttonBackground", "#ff6b35");
        sunsetWarmthColors.put("ComboBox.buttonArrowColor", "#ffffff");

        // Create minimal palette for backward compatibility
        Color primary = Color.decode("#FF6B35");     // Vibrant orange
        Color secondary = Color.decode("#F7931E");   // Orange
        Color background = Color.decode("#fdf4e3");  // Warm cream background
        Color surface = Color.decode("#fef7f0");     // Very light warm surface
        Color onBackground = Color.decode("#654321"); // Dark brown text
        Color onSurface = Color.decode("#8b4513");    // Saddle brown text

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

        return new UnifiedThemeDefinition("Sunset Warmth", palette, sunsetWarmthColors);
    }
}