package com.kalix.ide.themes.unified;


/**
 * Unified theme definitions extracted from existing Light themes.
 * This demonstrates the migration from separate theme systems to the unified approach.
 */
public class LightThemeDefinitions {

    /**
     * REFACTORED: Create the Light theme using component builders (eliminates 150+ lines of duplication)
     * This demonstrates the new architecture that preserves exact colors while eliminating code duplication.
     */
    public static UnifiedThemeDefinition createLightThemeRefactored() {
        ExactColorTheme lightTheme = new ExactColorTheme("Light", false);

        // Set exact colors (same as before, but organized)
        lightTheme
            // Base components
            .setColor("Component.background", "#f2f2f2")
            .setColor("Panel.background", "#f2f2f2")
            .setColor("OptionPane.background", "#f2f2f2")
            .setColor("PopupMenu.background", "#ffffff")
            .setColor("MenuItem.background", "#ffffff")
            .setColor("Dialog.background", "#f2f2f2")
            .setColor("Component.foreground", "#000000")
            .setColor("Label.foreground", "#000000")
            .setColor("Component.focusedBorderColor", "#89b0d4")
            .setColor("Component.borderColor", "#c2c2c2")

            // Text components
            .setColor("TextArea.background", "#ffffff")
            .setColor("TextPane.background", "#ffffff")
            .setColor("TextField.background", "#ffffff")
            .setColor("FormattedTextField.background", "#ffffff")
            .setColor("PasswordField.background", "#ffffff")
            .setColor("EditorPane.background", "#ffffff")
            .setColor("TextArea.foreground", "#000000")
            .setColor("TextPane.foreground", "#000000")
            .setColor("TextField.foreground", "#000000")
            .setColor("TextArea.selectionBackground", "#2675bf")
            .setColor("TextPane.selectionBackground", "#2675bf")
            .setColor("TextField.selectionBackground", "#2675bf")

            // Buttons
            .setColor("Button.background", "#ffffff")
            .setColor("Button.foreground", "#000000")
            .setColor("Button.focusedBorderColor", "#89b0d4")
            .setColor("Button.hoverBackground", "#f7f7f7")
            .setColor("Button.pressedBackground", "#e6e6e6")
            .setColor("Button.default.background", "#ffffff")
            .setColor("Button.default.foreground", "#000000")
            .setColor("Button.default.hoverBackground", "#f7f7f7")
            .setColor("RadioButton.background", "#f2f2f2")
            .setColor("RadioButton.icon.centerColor", "#000000")
            .setColor("CheckBox.background", "#f2f2f2")
            .setColor("CheckBox.icon.checkmarkColor", "#000000")

            // Custom Kalix components
            .setColor("MapPanel.background", "#ffffff")
            .setColor("MapPanel.gridlineColor", "#e0e0e0");

        return lightTheme.toUnifiedTheme();
    }

    /**
     * REFACTORED: Create the Keylime theme using component builders (eliminates 150+ lines of duplication)
     * This demonstrates the second theme migration to the new architecture.
     */
    public static UnifiedThemeDefinition createKeylimeThemeRefactored() {
        ExactColorTheme keylimeTheme = new ExactColorTheme("Keylime", false);

        keylimeTheme
            // Base components (lime green accent theme)
            .setColor("Component.background", "#ffffff")
            .setColor("Panel.background", "#fafafa")
            .setColor("OptionPane.background", "#fafafa")
            .setColor("PopupMenu.background", "#ffffff")
            .setColor("MenuItem.background", "#ffffff")
            .setColor("Component.foreground", "#2d2d2d")
            .setColor("Label.foreground", "#2d2d2d")
            .setColor("Component.focusedBorderColor", "#84cc16")
            .setColor("Component.borderColor", "#e0e0e0")

            // Text components
            .setColor("TextArea.background", "#ffffff")
            .setColor("TextPane.background", "#ffffff")
            .setColor("TextField.background", "#ffffff")
            .setColor("FormattedTextField.background", "#ffffff")
            .setColor("PasswordField.background", "#ffffff")
            .setColor("EditorPane.background", "#ffffff")
            .setColor("TextArea.foreground", "#1a1a1a")
            .setColor("TextPane.foreground", "#1a1a1a")
            .setColor("TextField.foreground", "#1a1a1a")
            .setColor("TextArea.selectionBackground", "#d4ff8f")
            .setColor("TextPane.selectionBackground", "#d4ff8f")
            .setColor("TextField.selectionBackground", "#d4ff8f")

            // Buttons (lime green theme)
            .setColor("Button.background", "#f5f5f5")
            .setColor("Button.foreground", "#2d2d2d")
            .setColor("Button.focusedBorderColor", "#84cc16")
            .setColor("Button.hoverBackground", "#f0f9ff")
            .setColor("Button.pressedBackground", "#e5e5e5")
            .setColor("Button.default.background", "#65a30d")
            .setColor("Button.default.foreground", "#ffffff")
            .setColor("Button.default.hoverBackground", "#84cc16")

            // Menus
            .setColor("MenuBar.background", "#f8f8f8")
            .setColor("Menu.background", "#f8f8f8")
            .setColor("Menu.foreground", "#2d2d2d")
            .setColor("MenuItem.foreground", "#2d2d2d")
            .setColor("MenuItem.hoverBackground", "#f0f9ff")
            .setColor("MenuItem.selectionBackground", "#ecfccb")

            // Tables and lists (lime selection)
            .setColor("Table.background", "#ffffff")
            .setColor("Table.foreground", "#1a1a1a")
            .setColor("Table.selectionBackground", "#ecfccb")
            .setColor("Table.selectionForeground", "#1a1a1a")
            .setColor("Table.gridColor", "#f0f0f0")
            .setColor("List.background", "#ffffff")
            .setColor("List.foreground", "#1a1a1a")
            .setColor("List.selectionBackground", "#ecfccb")
            .setColor("List.selectionForeground", "#1a1a1a")
            .setColor("Tree.background", "#ffffff")
            .setColor("Tree.foreground", "#1a1a1a")
            .setColor("Tree.selectionBackground", "#ecfccb")
            .setColor("Tree.selectionForeground", "#1a1a1a")

            // Toolbar and other components
            .setColor("ToolBar.background", "#f8f8f8")
            .setColor("ToolBar.borderColor", "#e5e5e5")
            .setColor("Separator.foreground", "#e0e0e0")
            .setColor("StatusBar.background", "#f8f8f8")
            .setColor("StatusBar.foreground", "#2d2d2d")

            // Tabs
            .setColor("TabbedPane.background", "#fafafa")
            .setColor("TabbedPane.foreground", "#2d2d2d")
            .setColor("TabbedPane.selectedBackground", "#ffffff")
            .setColor("TabbedPane.hoverColor", "#f0f9ff")

            // Scrollbars
            .setColor("ScrollBar.track", "#f5f5f5")
            .setColor("ScrollBar.thumb", "#dcdcdc")
            .setColor("ScrollBar.hoverThumbColor", "#c3c3c3")
            .setColor("ScrollBar.pressedThumbColor", "#a9a9a9")

            // Progress bars
            .setColor("ProgressBar.background", "#f0f0f0")
            .setColor("ProgressBar.foreground", "#65a30d")
            .setColor("ProgressBar.selectionBackground", "#ffffff")
            .setColor("ProgressBar.selectionForeground", "#000000")

            // ComboBox (critical for dropdown arrows - this is what makes it lime green!)
            .setColor("ComboBox.background", "#ffffff")
            .setColor("ComboBox.foreground", "#1a1a1a")
            .setColor("ComboBox.buttonBackground", "#65a30d")
            .setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#ecfccb")
            .setColor("ComboBox.selectionForeground", "#1a1a1a")

            // Spinner (also lime green accents)
            .setColor("Spinner.background", "#ffffff")
            .setColor("Spinner.buttonBackground", "#65a30d")
            .setColor("Spinner.buttonArrowColor", "#ffffff")

            // Additional form controls with lime accents
            .setColor("CheckBox.background", "#ffffff")
            .setColor("CheckBox.icon.checkmarkColor", "#65a30d")
            .setColor("RadioButton.background", "#ffffff")
            .setColor("RadioButton.icon.centerColor", "#65a30d")

            // Sliders with lime theme
            .setColor("Slider.thumb", "#65a30d")
            .setColor("Slider.track", "#e5e5e5")
            .setColor("Slider.trackColor", "#e5e5e5")
            .setColor("Slider.trackValueColor", "#84cc16")
            .setColor("Slider.focus", "#d4ff8f")
            .setColor("Slider.hoverThumbColor", "#84cc16")
            .setColor("Slider.pressedThumbColor", "#4ade80")

            // Tooltips
            .setColor("ToolTip.background", "#f0f9ff")
            .setColor("ToolTip.foreground", "#1a1a1a")

            // TitledBorder
            .setColor("TitledBorder.titleColor", "#2d2d2d")

            // Custom Kalix components
            .setColor("MapPanel.background", "#ffffff")
            .setColor("MapPanel.gridlineColor", "#f0f0f0")

            // Title bar
            .setColor("TitlePane.background", "#f8fcf4")
            .setColor("TitlePane.unifiedBackground", "false");

        return keylimeTheme.toUnifiedTheme();
    }

    /**
     * REFACTORED: Create the Lapland theme using component builders (eliminates 150+ lines of duplication)
     * Nordic blue theme with clean, minimalist design.
     */
    public static UnifiedThemeDefinition createLaplandThemeRefactored() {
        ExactColorTheme laplandTheme = new ExactColorTheme("Lapland", false);

        laplandTheme
            // Base components (Nordic blue accent theme)
            .setColor("Component.background", "#f8fafc")
            .setColor("Panel.background", "#f1f5f9")
            .setColor("OptionPane.background", "#f1f5f9")
            .setColor("PopupMenu.background", "#f8fafc")
            .setColor("MenuItem.background", "#f8fafc")
            .setColor("Dialog.background", "#f1f5f9")
            .setColor("Component.foreground", "#334155")
            .setColor("Label.foreground", "#334155")
            .setColor("Component.focusedBorderColor", "#3b82f6")
            .setColor("Component.borderColor", "#cbd5e1")

            // Text components (subtle blue tint)
            .setColor("TextArea.background", "#f8fbff")
            .setColor("TextPane.background", "#f8fbff")
            .setColor("TextField.background", "#f8fafc")
            .setColor("FormattedTextField.background", "#f8fafc")
            .setColor("PasswordField.background", "#f8fafc")
            .setColor("EditorPane.background", "#f8fbff")
            .setColor("TextArea.foreground", "#1e293b")
            .setColor("TextPane.foreground", "#1e293b")
            .setColor("TextField.foreground", "#1e293b")
            .setColor("TextArea.selectionBackground", "#bfdbfe")
            .setColor("TextPane.selectionBackground", "#bfdbfe")
            .setColor("TextField.selectionBackground", "#bfdbfe")

            // Buttons (Nordic blue theme)
            .setColor("Button.background", "#f1f5f9")
            .setColor("Button.foreground", "#334155")
            .setColor("Button.focusedBorderColor", "#3b82f6")
            .setColor("Button.hoverBackground", "#f0f9ff")
            .setColor("Button.pressedBackground", "#e2e8f0")
            .setColor("Button.default.background", "#2563eb")
            .setColor("Button.default.foreground", "#ffffff")
            .setColor("Button.default.hoverBackground", "#3b82f6")

            // Menus
            .setColor("MenuBar.background", "#f1f5f9")
            .setColor("Menu.background", "#f1f5f9")
            .setColor("Menu.foreground", "#334155")
            .setColor("MenuItem.foreground", "#334155")
            .setColor("MenuItem.hoverBackground", "#f0f9ff")
            .setColor("MenuItem.selectionBackground", "#dbeafe")

            // Tables and lists (blue selection)
            .setColor("Table.background", "#fcfcfc")
            .setColor("Table.foreground", "#1e293b")
            .setColor("Table.selectionBackground", "#dbeafe")
            .setColor("Table.selectionForeground", "#1e293b")
            .setColor("Table.gridColor", "#f1f5f9")
            .setColor("List.background", "#fcfcfc")
            .setColor("List.foreground", "#1e293b")
            .setColor("List.selectionBackground", "#dbeafe")
            .setColor("List.selectionForeground", "#1e293b")
            .setColor("Tree.background", "#fcfcfc")
            .setColor("Tree.foreground", "#1e293b")
            .setColor("Tree.selectionBackground", "#dbeafe")
            .setColor("Tree.selectionForeground", "#1e293b")

            // Toolbar and other components
            .setColor("ToolBar.background", "#f1f5f9")
            .setColor("ToolBar.borderColor", "#cbd5e1")
            .setColor("Separator.foreground", "#cbd5e1")
            .setColor("StatusBar.background", "#f1f5f9")
            .setColor("StatusBar.foreground", "#334155")

            // Tabs
            .setColor("TabbedPane.background", "#f1f5f9")
            .setColor("TabbedPane.foreground", "#334155")
            .setColor("TabbedPane.selectedBackground", "#f8fafc")
            .setColor("TabbedPane.hoverColor", "#f0f9ff")

            // Scrollbars
            .setColor("ScrollBar.track", "#f1f5f9")
            .setColor("ScrollBar.thumb", "#cbd5e1")
            .setColor("ScrollBar.hoverThumbColor", "#94a3b8")
            .setColor("ScrollBar.pressedThumbColor", "#64748b")

            // Progress bars
            .setColor("ProgressBar.background", "#f1f5f9")
            .setColor("ProgressBar.foreground", "#2563eb")
            .setColor("ProgressBar.selectionBackground", "#ffffff")
            .setColor("ProgressBar.selectionForeground", "#000000")

            // ComboBox (blue accents)
            .setColor("ComboBox.background", "#f8fafc")
            .setColor("ComboBox.foreground", "#1e293b")
            .setColor("ComboBox.buttonBackground", "#2563eb")
            .setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#dbeafe")
            .setColor("ComboBox.selectionForeground", "#1e293b")

            // Spinner (blue accents)
            .setColor("Spinner.background", "#f8fafc")
            .setColor("Spinner.buttonBackground", "#2563eb")
            .setColor("Spinner.buttonArrowColor", "#ffffff")

            // Form controls with blue accents
            .setColor("CheckBox.background", "#f8fafc")
            .setColor("CheckBox.icon.checkmarkColor", "#2563eb")
            .setColor("RadioButton.background", "#f8fafc")
            .setColor("RadioButton.icon.centerColor", "#2563eb")

            // Sliders with blue theme
            .setColor("Slider.thumb", "#2563eb")
            .setColor("Slider.track", "#cbd5e1")
            .setColor("Slider.trackColor", "#cbd5e1")
            .setColor("Slider.trackValueColor", "#3b82f6")
            .setColor("Slider.focus", "#bfdbfe")
            .setColor("Slider.hoverThumbColor", "#3b82f6")
            .setColor("Slider.pressedThumbColor", "#1d4ed8")

            // Tooltips
            .setColor("ToolTip.background", "#f0f9ff")
            .setColor("ToolTip.foreground", "#1e293b")

            // TitledBorder
            .setColor("TitledBorder.titleColor", "#334155")

            // Custom Kalix components
            .setColor("MapPanel.background", "#fefbfa")
            .setColor("MapPanel.gridlineColor", "#f0f0f0")

            // Title bar
            .setColor("TitlePane.background", "#f1f5f9")
            .setColor("TitlePane.unifiedBackground", "false");

        return laplandTheme.toUnifiedTheme();
    }



    /**
     * REFACTORED: Create the Nemo theme using component builders (eliminates 150+ lines of duplication)
     * Ocean-themed light theme with clownfish orange accents and coral reef colors.
     */
    public static UnifiedThemeDefinition createNemoThemeRefactored() {
        ExactColorTheme nemoTheme = new ExactColorTheme("Nemo", false);

        nemoTheme
            // Base backgrounds - ocean blues with coral reef warmth
            .setColor("Component.background", "#e6f3ff")
            .setColor("Panel.background", "#cce7ff")
            .setColor("OptionPane.background", "#cce7ff")
            .setColor("PopupMenu.background", "#e6f3ff")
            .setColor("MenuItem.background", "#e6f3ff")
            .setColor("Dialog.background", "#cce7ff")
            .setColor("Component.foreground", "#0d3d56")
            .setColor("Label.foreground", "#0d3d56")
            .setColor("Component.focusedBorderColor", "#ff6f00")
            .setColor("Component.borderColor", "#4fc3f7")

            // Text areas and input fields - sandy seafloor and clear water
            .setColor("TextArea.background", "#fff8e1")
            .setColor("TextPane.background", "#fff8e1")
            .setColor("TextField.background", "#f0f8ff")
            .setColor("FormattedTextField.background", "#f0f8ff")
            .setColor("PasswordField.background", "#f0f8ff")
            .setColor("EditorPane.background", "#fff8e1")
            .setColor("TextArea.foreground", "#1a4a5c")
            .setColor("TextPane.foreground", "#1a4a5c")
            .setColor("TextField.foreground", "#1a4a5c")
            .setColor("TextArea.selectionBackground", "#ffcc80")
            .setColor("TextPane.selectionBackground", "#ffcc80")
            .setColor("TextField.selectionBackground", "#ffcc80")

            // Buttons - coral reef colors
            .setColor("Button.background", "#ffecb3")
            .setColor("Button.foreground", "#0d3d56")
            .setColor("Button.focusedBorderColor", "#ff6f00")
            .setColor("Button.hoverBackground", "#ffcc80")
            .setColor("Button.pressedBackground", "#ffb74d")
            .setColor("Button.default.background", "#ff6f00")
            .setColor("Button.default.foreground", "#ffffff")
            .setColor("Button.default.hoverBackground", "#ff8f00")

            // CheckBox and RadioButton - clownfish orange accents
            .setColor("CheckBox.background", "#e1f5fe")
            .setColor("CheckBox.icon.checkmarkColor", "#ff6f00")
            .setColor("RadioButton.background", "#e1f5fe")
            .setColor("RadioButton.icon.centerColor", "#ff6f00")
            .setColor("RadioButton.icon.centerDiameter", "5")

            // Menu bar and menus - ocean surface
            .setColor("MenuBar.background", "#b3e5fc")
            .setColor("Menu.background", "#b3e5fc")
            .setColor("Menu.foreground", "#0d3d56")
            .setColor("MenuItem.foreground", "#0d3d56")
            .setColor("MenuItem.hoverBackground", "#81d4fa")
            .setColor("MenuItem.selectionBackground", "#ffcc80")

            // Toolbar
            .setColor("ToolBar.background", "#b3e5fc")
            .setColor("ToolBar.borderColor", "#4fc3f7")
            .setColor("Separator.foreground", "#4fc3f7")
            .setColor("StatusBar.background", "#b3e5fc")
            .setColor("StatusBar.foreground", "#0d3d56")
            .setColor("TitledBorder.titleColor", "#0d3d56")

            // Tables - clear tropical water
            .setColor("Table.background", "#e1f5fe")
            .setColor("Table.foreground", "#1a4a5c")
            .setColor("Table.selectionBackground", "#ffcc80")
            .setColor("Table.selectionForeground", "#0d3d56")
            .setColor("Table.gridColor", "#b3e5fc")

            // Lists
            .setColor("List.background", "#e1f5fe")
            .setColor("List.foreground", "#1a4a5c")
            .setColor("List.selectionBackground", "#ffcc80")
            .setColor("List.selectionForeground", "#0d3d56")

            // Trees
            .setColor("Tree.background", "#e1f5fe")
            .setColor("Tree.foreground", "#1a4a5c")
            .setColor("Tree.selectionBackground", "#ffcc80")
            .setColor("Tree.selectionForeground", "#0d3d56")

            // Tabs - coral formations
            .setColor("TabbedPane.background", "#cce7ff")
            .setColor("TabbedPane.foreground", "#0d3d56")
            .setColor("TabbedPane.selectedBackground", "#e1f5fe")
            .setColor("TabbedPane.hoverColor", "#81d4fa")

            // Scrollbars - sea current design
            .setColor("ScrollBar.track", "#e1f5fe")
            .setColor("ScrollBar.thumb", "#4fc3f7")
            .setColor("ScrollBar.hoverThumbColor", "#29b6f6")
            .setColor("ScrollBar.pressedThumbColor", "#0288d1")

            // Form components - clownfish orange accents
            .setColor("ComboBox.background", "#f0f8ff")
            .setColor("ComboBox.foreground", "#1a4a5c")
            .setColor("ComboBox.buttonBackground", "#ff6f00")
            .setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#ffcc80")
            .setColor("ComboBox.selectionForeground", "#0d3d56")

            .setColor("Spinner.background", "#f0f8ff")
            .setColor("Spinner.buttonBackground", "#ff6f00")
            .setColor("Spinner.buttonArrowColor", "#ffffff")

            .setColor("ProgressBar.background", "#b3e5fc")
            .setColor("ProgressBar.foreground", "#ff6f00")
            .setColor("ProgressBar.selectionBackground", "#ffffff")
            .setColor("ProgressBar.selectionForeground", "#000000")

            .setColor("ToolTip.background", "#fff8e1")
            .setColor("ToolTip.foreground", "#0d3d56")

            // Split panes - sea anemone inspired
            .setColor("SplitPane.background", "#cce7ff")
            .setColor("SplitPaneDivider.draggingColor", "#4fc3f7")
            .setColor("SplitPane.dividerSize", "8")
            .setColor("Component.splitPaneDividerColor", "#4fc3f7")
            .setColor("SplitPane.oneTouchButtonColor", "#ffecb3")
            .setColor("SplitPane.oneTouchArrowColor", "#0d3d56")

            // Sliders - clownfish orange accents
            .setColor("Slider.thumb", "#ff6f00")
            .setColor("Slider.track", "#b3e5fc")
            .setColor("Slider.trackColor", "#b3e5fc")
            .setColor("Slider.trackValueColor", "#ff6f00")
            .setColor("Slider.focus", "#ffcc80")
            .setColor("Slider.hoverThumbColor", "#ff8f00")
            .setColor("Slider.pressedThumbColor", "#ffab40")

            // Custom Kalix components - sandy ocean floor
            .setColor("MapPanel.background", "#fff3e0")
            .setColor("MapPanel.gridlineColor", "#d0d0d0")

            // Title bar properties
            .setColor("TitlePane.background", "#e1f4ff")
            .setColor("TitlePane.unifiedBackground", "false");

        return nemoTheme.toUnifiedTheme();
    }


    /**
     * REFACTORED: Create the Sunset Warmth theme using component builders (eliminates 150+ lines of duplication)
     * Warm sunset-themed light theme with vibrant orange accents and cream backgrounds.
     */
    public static UnifiedThemeDefinition createSunsetWarmthThemeRefactored() {
        ExactColorTheme sunsetTheme = new ExactColorTheme("Sunset Warmth", false);

        sunsetTheme
            // Base backgrounds - warm cream with sunset orange tints
            .setColor("Component.background", "#fef7f0")
            .setColor("Panel.background", "#fdf4e3")
            .setColor("OptionPane.background", "#fdf4e3")
            .setColor("PopupMenu.background", "#fef7f0")
            .setColor("MenuItem.background", "#fef7f0")
            .setColor("Dialog.background", "#fdf4e3")
            .setColor("Component.foreground", "#8b4513")
            .setColor("Label.foreground", "#8b4513")
            .setColor("Component.focusedBorderColor", "#f7931e")
            .setColor("Component.borderColor", "#f7d794")

            // Text areas and input fields - very warm cream background
            .setColor("TextArea.background", "#fffcf8")
            .setColor("TextPane.background", "#fffcf8")
            .setColor("TextField.background", "#fffcf8")
            .setColor("FormattedTextField.background", "#fffcf8")
            .setColor("PasswordField.background", "#fffcf8")
            .setColor("EditorPane.background", "#fffcf8")
            .setColor("TextArea.foreground", "#654321")
            .setColor("TextPane.foreground", "#654321")
            .setColor("TextField.foreground", "#654321")
            .setColor("TextArea.selectionBackground", "#feca57")
            .setColor("TextPane.selectionBackground", "#feca57")
            .setColor("TextField.selectionBackground", "#feca57")

            // Buttons - warm sunset orange with vibrant accents
            .setColor("Button.background", "#fef3e8")
            .setColor("Button.foreground", "#8b4513")
            .setColor("Button.focusedBorderColor", "#f7931e")
            .setColor("Button.hoverBackground", "#feca57")
            .setColor("Button.pressedBackground", "#f7931e")
            .setColor("Button.default.background", "#ff6b35")
            .setColor("Button.default.foreground", "#ffffff")
            .setColor("Button.default.hoverBackground", "#f7931e")

            // CheckBox and RadioButton - sunset orange accents
            .setColor("CheckBox.background", "#fef7f0")
            .setColor("CheckBox.icon.checkmarkColor", "#ff6b35")
            .setColor("RadioButton.background", "#fef7f0")
            .setColor("RadioButton.icon.centerColor", "#ff6b35")
            .setColor("RadioButton.icon.centerDiameter", "5")

            // Menu bar and menus - vibrant sunset colors
            .setColor("MenuBar.background", "#feca57")
            .setColor("Menu.background", "#feca57")
            .setColor("Menu.foreground", "#8b4513")
            .setColor("MenuItem.foreground", "#8b4513")
            .setColor("MenuItem.hoverBackground", "#f7931e")
            .setColor("MenuItem.selectionBackground", "#ff6b35")

            // Toolbar
            .setColor("ToolBar.background", "#feca57")
            .setColor("ToolBar.borderColor", "#f7931e")
            .setColor("Separator.foreground", "#f7d794")
            .setColor("StatusBar.background", "#feca57")
            .setColor("StatusBar.foreground", "#8b4513")
            .setColor("TitledBorder.titleColor", "#8b4513")

            // Tables - clean with warm sunset selection
            .setColor("Table.background", "#fffcf8")
            .setColor("Table.foreground", "#654321")
            .setColor("Table.selectionBackground", "#feca57")
            .setColor("Table.selectionForeground", "#654321")
            .setColor("Table.gridColor", "#fef7f0")

            // Lists
            .setColor("List.background", "#fffcf8")
            .setColor("List.foreground", "#654321")
            .setColor("List.selectionBackground", "#feca57")
            .setColor("List.selectionForeground", "#654321")

            // Trees
            .setColor("Tree.background", "#fffcf8")
            .setColor("Tree.foreground", "#654321")
            .setColor("Tree.selectionBackground", "#feca57")
            .setColor("Tree.selectionForeground", "#654321")

            // Tabs - vibrant sunset theme
            .setColor("TabbedPane.background", "#feca57")
            .setColor("TabbedPane.foreground", "#8b4513")
            .setColor("TabbedPane.selectedBackground", "#fffcf8")
            .setColor("TabbedPane.hoverColor", "#f7931e")

            // Scrollbars - vibrant sunset design
            .setColor("ScrollBar.track", "#feca57")
            .setColor("ScrollBar.thumb", "#ff6b35")
            .setColor("ScrollBar.hoverThumbColor", "#f7931e")
            .setColor("ScrollBar.pressedThumbColor", "#e85a2b")

            // Form components - sunset orange accents
            .setColor("ComboBox.background", "#fffcf8")
            .setColor("ComboBox.foreground", "#654321")
            .setColor("ComboBox.buttonBackground", "#ff6b35")
            .setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#feca57")
            .setColor("ComboBox.selectionForeground", "#654321")

            .setColor("Spinner.background", "#fef7f0")
            .setColor("Spinner.buttonBackground", "#ff6b35")
            .setColor("Spinner.buttonArrowColor", "#ffffff")

            .setColor("ProgressBar.background", "#fef7f0")
            .setColor("ProgressBar.foreground", "#ff6b35")
            .setColor("ProgressBar.selectionBackground", "#ffffff")
            .setColor("ProgressBar.selectionForeground", "#000000")

            .setColor("ToolTip.background", "#fffcf8")
            .setColor("ToolTip.foreground", "#8b4513")

            // Split panes
            .setColor("SplitPane.background", "#fdf4e3")
            .setColor("SplitPaneDivider.draggingColor", "#f7931e")
            .setColor("SplitPane.dividerSize", "8")
            .setColor("Component.splitPaneDividerColor", "#f7931e")
            .setColor("SplitPane.oneTouchButtonColor", "#fef3e8")
            .setColor("SplitPane.oneTouchArrowColor", "#8b4513")

            // Sliders - sunset orange accents
            .setColor("Slider.thumb", "#ff6b35")
            .setColor("Slider.track", "#feca57")
            .setColor("Slider.trackColor", "#feca57")
            .setColor("Slider.trackValueColor", "#ff6b35")
            .setColor("Slider.focus", "#f7931e")
            .setColor("Slider.hoverThumbColor", "#f7931e")
            .setColor("Slider.pressedThumbColor", "#e85a2b")

            // Custom Kalix components - very subtle warm cream
            .setColor("MapPanel.background", "#fffef9")
            .setColor("MapPanel.gridlineColor", "#fbb668")

            // Title bar properties
            .setColor("TitlePane.background", "#feca57")
            .setColor("TitlePane.unifiedBackground", "false");

        return sunsetTheme.toUnifiedTheme();
    }
}