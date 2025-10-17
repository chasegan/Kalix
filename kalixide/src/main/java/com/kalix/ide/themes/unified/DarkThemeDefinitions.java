package com.kalix.ide.themes.unified;


/**
 * Unified theme definitions for dark themes.
 * Extracts colors from existing dark theme systems and creates unified definitions.
 */
public class DarkThemeDefinitions {


    /**
     * REFACTORED: Create the Sanne theme using component builders (eliminates 150+ lines of duplication)
     * Dark theme with vibrant pink accents and rich dark gray backgrounds.
     */
    public static UnifiedThemeDefinition createSanneThemeRefactored() {
        ExactColorTheme sanneTheme = new ExactColorTheme("Sanne", true);

        sanneTheme
            // Base backgrounds - rich dark grays with subtle warmth
            .setColor("Component.background", "#2a2a2e")
            .setColor("Panel.background", "#2a2a2e")
            .setColor("OptionPane.background", "#2a2a2e")
            .setColor("PopupMenu.background", "#2a2a2e")
            .setColor("MenuItem.background", "#2a2a2e")
            .setColor("Dialog.background", "#2a2a2e")
            .setColor("Component.foreground", "#e8e8e8")
            .setColor("Label.foreground", "#e8e8e8")
            .setColor("Component.focusedBorderColor", "#ff1493")
            .setColor("Component.borderColor", "#505058")

            // Text areas and input fields - slightly lighter dark with subtle pink tint
            .setColor("TextArea.background", "#383840")
            .setColor("TextPane.background", "#383840")
            .setColor("TextField.background", "#2a2a2e")
            .setColor("FormattedTextField.background", "#2a2a2e")
            .setColor("PasswordField.background", "#2a2a2e")
            .setColor("EditorPane.background", "#383840")
            .setColor("TextArea.foreground", "#f0f0f0")
            .setColor("TextPane.foreground", "#f0f0f0")
            .setColor("TextField.foreground", "#f0f0f0")
            .setColor("TextArea.selectionBackground", "#ff69b4")
            .setColor("TextPane.selectionBackground", "#ff69b4")
            .setColor("TextField.selectionBackground", "#ff69b4")

            // Buttons - dark with bold pink accents
            .setColor("Button.background", "#404048")
            .setColor("Button.foreground", "#e8e8e8")
            .setColor("Button.focusedBorderColor", "#ff1493")
            .setColor("Button.hoverBackground", "#ff69b460")
            .setColor("Button.pressedBackground", "#ff1493")
            .setColor("Button.default.background", "#ff1493")
            .setColor("Button.default.foreground", "#ffffff")
            .setColor("Button.default.hoverBackground", "#ff69b4")

            // CheckBox and RadioButton - vibrant pink accents
            .setColor("CheckBox.background", "#2a2a2e")
            .setColor("CheckBox.icon.checkmarkColor", "#ff1493")
            .setColor("RadioButton.background", "#2a2a2e")
            .setColor("RadioButton.icon.centerColor", "#ff1493")
            .setColor("RadioButton.icon.centerDiameter", "5")

            // Menu bar and menus - dark with pink highlights
            .setColor("MenuBar.background", "#2a2a2e")
            .setColor("Menu.background", "#2a2a2e")
            .setColor("Menu.foreground", "#e8e8e8")
            .setColor("MenuItem.foreground", "#e8e8e8")
            .setColor("MenuItem.hoverBackground", "#ff69b440")
            .setColor("MenuItem.selectionBackground", "#ff69b460")

            // Toolbar
            .setColor("ToolBar.background", "#2a2a2e")
            .setColor("ToolBar.borderColor", "#404048")
            .setColor("Separator.foreground", "#505058")
            .setColor("StatusBar.background", "#2a2a2e")
            .setColor("StatusBar.foreground", "#e8e8e8")
            .setColor("TitledBorder.titleColor", "#e8e8e8")

            // Tables - dark with exciting pink selection
            .setColor("Table.background", "#383840")
            .setColor("Table.foreground", "#f0f0f0")
            .setColor("Table.selectionBackground", "#ff69b4")
            .setColor("Table.selectionForeground", "#ffffff")
            .setColor("Table.gridColor", "#404048")

            // Lists
            .setColor("List.background", "#383840")
            .setColor("List.foreground", "#f0f0f0")
            .setColor("List.selectionBackground", "#ff69b4")
            .setColor("List.selectionForeground", "#ffffff")

            // Trees
            .setColor("Tree.background", "#383840")
            .setColor("Tree.foreground", "#f0f0f0")
            .setColor("Tree.selectionBackground", "#ff69b4")
            .setColor("Tree.selectionForeground", "#ffffff")

            // Tabs - dark with pink hover effects
            .setColor("TabbedPane.background", "#2a2a2e")
            .setColor("TabbedPane.foreground", "#e8e8e8")
            .setColor("TabbedPane.selectedBackground", "#383840")
            .setColor("TabbedPane.hoverColor", "#ff69b430")

            // Scrollbars - sleek dark with pink accents
            .setColor("ScrollBar.track", "#2a2a2e")
            .setColor("ScrollBar.thumb", "#505058")
            .setColor("ScrollBar.hoverThumbColor", "#ff69b4")
            .setColor("ScrollBar.pressedThumbColor", "#ff1493")

            // Form components - pink accents
            .setColor("ComboBox.background", "#383840")
            .setColor("ComboBox.foreground", "#f0f0f0")
            .setColor("ComboBox.buttonBackground", "#ff1493")
            .setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#ff69b4")
            .setColor("ComboBox.selectionForeground", "#ffffff")

            .setColor("Spinner.background", "#2a2a2e")
            .setColor("Spinner.buttonBackground", "#ff1493")
            .setColor("Spinner.buttonArrowColor", "#ffffff")

            .setColor("ProgressBar.background", "#404048")
            .setColor("ProgressBar.foreground", "#ff1493")
            .setColor("ProgressBar.selectionBackground", "#ffffff")
            .setColor("ProgressBar.selectionForeground", "#000000")

            .setColor("ToolTip.background", "#ff1493")
            .setColor("ToolTip.foreground", "#ffffff")

            // Split panes - dark background with pink highlights
            .setColor("SplitPane.background", "#2a2a2e")
            .setColor("SplitPaneDivider.draggingColor", "#505058")
            .setColor("SplitPane.dividerSize", "8")
            .setColor("Component.splitPaneDividerColor", "#505058")
            .setColor("SplitPane.oneTouchButtonColor", "#ff69b4")
            .setColor("SplitPane.oneTouchArrowColor", "#ffffff")

            // Sliders - pink accents
            .setColor("Slider.thumb", "#ff1493")
            .setColor("Slider.track", "#404048")
            .setColor("Slider.trackColor", "#404048")
            .setColor("Slider.trackValueColor", "#ff1493")
            .setColor("Slider.focus", "#ff69b4")
            .setColor("Slider.hoverThumbColor", "#ff69b4")
            .setColor("Slider.pressedThumbColor", "#ff6ba4")

            // Custom Kalix components
            .setColor("MapPanel.background", "#2a2a2e")
            .setColor("MapPanel.gridlineColor", "#ff69b430")

            // Title bar properties
            .setColor("TitlePane.background", "#2a2a2e")
            .setColor("TitlePane.unifiedBackground", "false");

        return sanneTheme.toUnifiedTheme();
    }


    /**
     * Create the unified Obsidian theme definition - refactored version
     * Uses component builders to eliminate duplication while preserving exact colors.
     */
    public static UnifiedThemeDefinition createObsidianThemeRefactored() {
        ExactColorTheme obsidianTheme = new ExactColorTheme("Obsidian", true);

        obsidianTheme
            // Base backgrounds - deeper than standard dark theme
            .setColor("Component.background", "#1e1e1e")
            .setColor("Panel.background", "#1a1a1a")
            .setColor("OptionPane.background", "#1a1a1a")
            .setColor("PopupMenu.background", "#1e1e1e")
            .setColor("MenuItem.background", "#1e1e1e")
            .setColor("Dialog.background", "#1a1a1a")

            // Text areas and input fields - very dark
            .setColor("TextArea.background", "#0d1117")
            .setColor("TextPane.background", "#0d1117")
            .setColor("TextField.background", "#0d1117")
            .setColor("FormattedTextField.background", "#0d1117")
            .setColor("PasswordField.background", "#0d1117")
            .setColor("EditorPane.background", "#0d1117")

            // Text colors - light gray/white for good contrast
            .setColor("Component.foreground", "#e6e6e6")
            .setColor("TextArea.foreground", "#f0f6fc")
            .setColor("TextPane.foreground", "#f0f6fc")
            .setColor("TextField.foreground", "#f0f6fc")
            .setColor("Label.foreground", "#e6e6e6")

            // Selection colors - subtle purple
            .setColor("TextArea.selectionBackground", "#3d2a5c")
            .setColor("TextPane.selectionBackground", "#3d2a5c")
            .setColor("TextField.selectionBackground", "#3d2a5c")
            .setColor("Component.focusedBorderColor", "#8b5cf6")
            .setColor("Component.borderColor", "#30363d")

            // Buttons - dark with purple accents
            .setColor("Button.background", "#2d2d30")
            .setColor("Button.foreground", "#e6e6e6")
            .setColor("Button.focusedBorderColor", "#8b5cf6")
            .setColor("Button.hoverBackground", "#3d3d42")
            .setColor("Button.pressedBackground", "#1e1e1e")
            .setColor("Button.default.background", "#7c3aed")
            .setColor("Button.default.foreground", "#ffffff")
            .setColor("Button.default.hoverBackground", "#8b5cf6")

            // CheckBox and RadioButton - purple accents
            .setColor("CheckBox.background", "#0d1117")
            .setColor("CheckBox.icon.checkmarkColor", "#8b5cf6")
            .setColor("RadioButton.background", "#0d1117")
            .setColor("RadioButton.icon.centerColor", "#8b5cf6")
            .setColor("RadioButton.icon.centerDiameter", "5")

            // Menu bar and menus - very dark
            .setColor("MenuBar.background", "#161b22")
            .setColor("Menu.background", "#161b22")
            .setColor("Menu.foreground", "#e6e6e6")
            .setColor("MenuItem.foreground", "#e6e6e6")
            .setColor("MenuItem.hoverBackground", "#21262d")
            .setColor("MenuItem.selectionBackground", "#21262d")

            // Toolbar
            .setColor("ToolBar.background", "#161b22")
            .setColor("ToolBar.borderColor", "#30363d")
            .setColor("Separator.foreground", "#30363d")
            .setColor("StatusBar.background", "#161b22")
            .setColor("StatusBar.foreground", "#e6e6e6")
            .setColor("TitledBorder.titleColor", "#e6e6e6")

            // Tables
            .setColor("Table.background", "#0d1117")
            .setColor("Table.foreground", "#f0f6fc")
            .setColor("Table.selectionBackground", "#3d2a5c")
            .setColor("Table.selectionForeground", "#ffffff")
            .setColor("Table.gridColor", "#21262d")

            // Lists
            .setColor("List.background", "#0d1117")
            .setColor("List.foreground", "#f0f6fc")
            .setColor("List.selectionBackground", "#3d2a5c")
            .setColor("List.selectionForeground", "#ffffff")

            // Trees
            .setColor("Tree.background", "#0d1117")
            .setColor("Tree.foreground", "#f0f6fc")
            .setColor("Tree.selectionBackground", "#3d2a5c")
            .setColor("Tree.selectionForeground", "#ffffff")

            // Tabs
            .setColor("TabbedPane.background", "#1a1a1a")
            .setColor("TabbedPane.foreground", "#e6e6e6")
            .setColor("TabbedPane.selectedBackground", "#0d1117")
            .setColor("TabbedPane.hoverColor", "#21262d")

            // Scrollbars
            .setColor("ScrollBar.track", "#161b22")
            .setColor("ScrollBar.thumb", "#30363d")
            .setColor("ScrollBar.hoverThumbColor", "#484f58")
            .setColor("ScrollBar.pressedThumbColor", "#6e7681")

            // Form components - purple accents
            .setColor("ComboBox.background", "#0d1117")
            .setColor("ComboBox.foreground", "#f0f6fc")
            .setColor("ComboBox.buttonBackground", "#8b5cf6")
            .setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#3d2a5c")
            .setColor("ComboBox.selectionForeground", "#ffffff")

            .setColor("Spinner.background", "#0d1117")
            .setColor("Spinner.buttonBackground", "#8b5cf6")
            .setColor("Spinner.buttonArrowColor", "#ffffff")

            .setColor("ProgressBar.background", "#21262d")
            .setColor("ProgressBar.foreground", "#8b5cf6")
            .setColor("ProgressBar.selectionBackground", "#ffffff")
            .setColor("ProgressBar.selectionForeground", "#000000")

            .setColor("ToolTip.background", "#21262d")
            .setColor("ToolTip.foreground", "#e6e6e6")

            // Split panes
            .setColor("SplitPane.background", "#1a1a1a")
            .setColor("SplitPaneDivider.draggingColor", "#30363d")
            .setColor("SplitPane.dividerSize", "8")
            .setColor("Component.splitPaneDividerColor", "#30363d")
            .setColor("SplitPane.oneTouchButtonColor", "#2d2d30")
            .setColor("SplitPane.oneTouchArrowColor", "#e6e6e6")

            // Sliders - purple accents
            .setColor("Slider.thumb", "#8b5cf6")
            .setColor("Slider.track", "#21262d")
            .setColor("Slider.trackColor", "#21262d")
            .setColor("Slider.trackValueColor", "#8b5cf6")
            .setColor("Slider.focus", "#3d2a5c")
            .setColor("Slider.hoverThumbColor", "#a855f7")
            .setColor("Slider.pressedThumbColor", "#c084fc")

            // Custom Kalix components
            .setColor("MapPanel.background", "#1a1a1a")
            .setColor("MapPanel.gridlineColor", "#505050")

            // Title bar properties
            .setColor("TitlePane.background", "#161b22")
            .setColor("TitlePane.unifiedBackground", "false");

        return obsidianTheme.toUnifiedTheme();
    }




    // =================== REFACTORED THEME METHODS ===================

    /**
     * REFACTORED: Create the Dracula theme using component builders
     */
    public static UnifiedThemeDefinition createDraculaThemeRefactored() {
        ExactColorTheme draculaTheme = new ExactColorTheme("Dracula", true);
        draculaTheme
            .setColor("Component.background", "#414450").setColor("Panel.background", "#414450")
            .setColor("OptionPane.background", "#414450").setColor("PopupMenu.background", "#414450")
            .setColor("MenuItem.background", "#414450").setColor("Dialog.background", "#414450")
            .setColor("Component.foreground", "#bbbbbb").setColor("Label.foreground", "#bbbbbb")
            .setColor("Component.focusedBorderColor", "#6272a4").setColor("Component.borderColor", "#6272a4")

            .setColor("TextArea.background", "#3a3d4c").setColor("TextPane.background", "#3a3d4c")
            .setColor("TextField.background", "#3a3d4c").setColor("FormattedTextField.background", "#3a3d4c")
            .setColor("PasswordField.background", "#3a3d4c").setColor("EditorPane.background", "#3a3d4c")
            .setColor("TextArea.foreground", "#bbbbbb").setColor("TextPane.foreground", "#bbbbbb")
            .setColor("TextField.foreground", "#bbbbbb")
            .setColor("TextArea.selectionBackground", "#6272a4").setColor("TextPane.selectionBackground", "#6272a4")
            .setColor("TextField.selectionBackground", "#6272a4")

            .setColor("Button.background", "#414450").setColor("Button.foreground", "#f8f8f2")
            .setColor("Button.focusedBorderColor", "#bd93f9").setColor("Button.hoverBackground", "#55585a")
            .setColor("Button.pressedBackground", "#5d5f62").setColor("Button.default.background", "#414450")
            .setColor("Button.default.foreground", "#f8f8f2").setColor("Button.default.hoverBackground", "#3c618c")

            .setColor("CheckBox.background", "#414450").setColor("CheckBox.icon.checkmarkColor", "#bd93f9")
            .setColor("RadioButton.background", "#414450").setColor("RadioButton.icon.centerColor", "#bd93f9")
            .setColor("RadioButton.icon.centerDiameter", "5")

            .setColor("MenuBar.background", "#414450").setColor("Menu.background", "#414450")
            .setColor("Menu.foreground", "#bbbbbb").setColor("MenuItem.foreground", "#bbbbbb")
            .setColor("MenuItem.hoverBackground", "#474a58").setColor("MenuItem.selectionBackground", "#6272a4")

            .setColor("ToolBar.background", "#414450").setColor("ToolBar.borderColor", "#6272a4")
            .setColor("Separator.foreground", "#5d5e66").setColor("StatusBar.background", "#414450")
            .setColor("StatusBar.foreground", "#bbbbbb").setColor("TitledBorder.titleColor", "#bbbbbb")

            .setColor("Table.background", "#414450").setColor("Table.foreground", "#bbbbbb")
            .setColor("Table.selectionBackground", "#6272a4").setColor("Table.selectionForeground", "#ffffff")
            .setColor("Table.gridColor", "#5d5e66")
            .setColor("List.background", "#414450").setColor("List.foreground", "#bbbbbb")
            .setColor("List.selectionBackground", "#6272a4").setColor("List.selectionForeground", "#ffffff")
            .setColor("Tree.background", "#414450").setColor("Tree.foreground", "#bbbbbb")
            .setColor("Tree.selectionBackground", "#6272a4").setColor("Tree.selectionForeground", "#ffffff")

            .setColor("TabbedPane.background", "#414450").setColor("TabbedPane.foreground", "#bbbbbb")
            .setColor("TabbedPane.selectedBackground", "#3a3d4c").setColor("TabbedPane.hoverColor", "#474a58")

            .setColor("ScrollBar.track", "#3e4244").setColor("ScrollBar.thumb", "#565c5f")
            .setColor("ScrollBar.hoverThumbColor", "#6e767a").setColor("ScrollBar.pressedThumbColor", "#7a8387")

            .setColor("ComboBox.background", "#3a3d4c").setColor("ComboBox.foreground", "#bbbbbb")
            .setColor("ComboBox.buttonBackground", "#bd93f9").setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#6272a4").setColor("ComboBox.selectionForeground", "#ffffff")
            .setColor("Spinner.background", "#3a3d4c").setColor("Spinner.buttonBackground", "#bd93f9")
            .setColor("Spinner.buttonArrowColor", "#ffffff")
            .setColor("ProgressBar.background", "#3a3d4c").setColor("ProgressBar.foreground", "#bd93f9")
            .setColor("ProgressBar.selectionBackground", "#ffffff").setColor("ProgressBar.selectionForeground", "#000000")
            .setColor("ToolTip.background", "#6272a4").setColor("ToolTip.foreground", "#ffffff")

            .setColor("SplitPane.background", "#414450").setColor("SplitPaneDivider.draggingColor", "#5d5e66")
            .setColor("SplitPane.dividerSize", "8").setColor("Component.splitPaneDividerColor", "#5d5e66")
            .setColor("SplitPane.oneTouchButtonColor", "#55585a").setColor("SplitPane.oneTouchArrowColor", "#bbbbbb")

            .setColor("Slider.thumb", "#bd93f9").setColor("Slider.track", "#3a3d4c")
            .setColor("Slider.trackColor", "#3a3d4c").setColor("Slider.trackValueColor", "#bd93f9")
            .setColor("Slider.focus", "#6272a4").setColor("Slider.hoverThumbColor", "#c9a5f7")
            .setColor("Slider.pressedThumbColor", "#d7b8fb")

            .setColor("MapPanel.background", "#414450").setColor("MapPanel.gridlineColor", "#6272a4")
            .setColor("TitlePane.background", "#414450").setColor("TitlePane.unifiedBackground", "false");
        return draculaTheme.toUnifiedTheme();
    }

    /**
     * REFACTORED: Create the One Dark theme using component builders
     */
    public static UnifiedThemeDefinition createOneDarkThemeRefactored() {
        ExactColorTheme oneDarkTheme = new ExactColorTheme("One Dark", true);
        oneDarkTheme
            .setColor("Component.background", "#21252b").setColor("Panel.background", "#21252b")
            .setColor("OptionPane.background", "#21252b").setColor("PopupMenu.background", "#21252b")
            .setColor("MenuItem.background", "#21252b").setColor("Dialog.background", "#21252b")
            .setColor("Component.foreground", "#abb2bf").setColor("Label.foreground", "#abb2bf")
            .setColor("Component.focusedBorderColor", "#568af2").setColor("Component.borderColor", "#333841")

            .setColor("TextArea.background", "#282c34").setColor("TextPane.background", "#282c34")
            .setColor("TextField.background", "#282c34").setColor("FormattedTextField.background", "#282c34")
            .setColor("PasswordField.background", "#282c34").setColor("EditorPane.background", "#282c34")
            .setColor("TextArea.foreground", "#abb2bf").setColor("TextPane.foreground", "#abb2bf")
            .setColor("TextField.foreground", "#abb2bf")
            .setColor("TextArea.selectionBackground", "#4d78cc").setColor("TextPane.selectionBackground", "#4d78cc")
            .setColor("TextField.selectionBackground", "#4d78cc")

            .setColor("Button.background", "#21252b").setColor("Button.foreground", "#a0a7b4")
            .setColor("Button.focusedBorderColor", "#646a73").setColor("Button.hoverBackground", "#55585a")
            .setColor("Button.pressedBackground", "#5d5f62").setColor("Button.default.background", "#21252b")
            .setColor("Button.default.foreground", "#ffffff").setColor("Button.default.hoverBackground", "#3c618c")

            .setColor("CheckBox.background", "#21252b").setColor("CheckBox.icon.checkmarkColor", "#568af2")
            .setColor("RadioButton.background", "#21252b").setColor("RadioButton.icon.centerColor", "#568af2")
            .setColor("RadioButton.icon.centerDiameter", "5")

            .setColor("MenuBar.background", "#21252b").setColor("Menu.background", "#21252b")
            .setColor("Menu.foreground", "#abb2bf").setColor("MenuItem.foreground", "#abb2bf")
            .setColor("MenuItem.hoverBackground", "#24282f").setColor("MenuItem.selectionBackground", "#4d78cc")

            .setColor("ToolBar.background", "#21252b").setColor("ToolBar.borderColor", "#333841")
            .setColor("Separator.foreground", "#32363c").setColor("StatusBar.background", "#21252b")
            .setColor("StatusBar.foreground", "#abb2bf").setColor("TitledBorder.titleColor", "#bbbbbb")

            .setColor("Table.background", "#21252b").setColor("Table.foreground", "#abb2bf")
            .setColor("Table.selectionBackground", "#4d78cc").setColor("Table.selectionForeground", "#ffffff")
            .setColor("Table.gridColor", "#5c6370")
            .setColor("List.background", "#21252b").setColor("List.foreground", "#abb2bf")
            .setColor("List.selectionBackground", "#4d78cc").setColor("List.selectionForeground", "#ffffff")
            .setColor("Tree.background", "#21252b").setColor("Tree.foreground", "#abb2bf")
            .setColor("Tree.selectionBackground", "#4d78cc").setColor("Tree.selectionForeground", "#ffffff")

            .setColor("TabbedPane.background", "#21252b").setColor("TabbedPane.foreground", "#abb2bf")
            .setColor("TabbedPane.selectedBackground", "#21252b").setColor("TabbedPane.hoverColor", "#323844")

            .setColor("ScrollBar.track", "#3e4244").setColor("ScrollBar.thumb", "#565c5f")
            .setColor("ScrollBar.hoverThumbColor", "#6e767a").setColor("ScrollBar.pressedThumbColor", "#7a8387")

            .setColor("ComboBox.background", "#333841").setColor("ComboBox.foreground", "#abb2bf")
            .setColor("ComboBox.buttonBackground", "#333841").setColor("ComboBox.buttonArrowColor", "#abb2bf")
            .setColor("ComboBox.selectionBackground", "#4d78cc").setColor("ComboBox.selectionForeground", "#d7dae0")
            .setColor("Spinner.background", "#282c34").setColor("Spinner.buttonBackground", "#568af2")
            .setColor("Spinner.buttonArrowColor", "#ffffff")
            .setColor("ProgressBar.background", "#32363c").setColor("ProgressBar.foreground", "#568af2")
            .setColor("ProgressBar.selectionBackground", "#568af2").setColor("ProgressBar.selectionForeground", "#1d1d26")
            .setColor("ToolTip.background", "#3d424b").setColor("ToolTip.foreground", "#abb2bf")

            .setColor("SplitPane.background", "#21252b").setColor("SplitPaneDivider.draggingColor", "#616365")
            .setColor("SplitPane.dividerSize", "5").setColor("Component.splitPaneDividerColor", "#616365")
            .setColor("SplitPane.oneTouchButtonColor", "#21252b").setColor("SplitPane.oneTouchArrowColor", "#a0a7b4")

            .setColor("Slider.thumb", "#568af2").setColor("Slider.track", "#32363c")
            .setColor("Slider.trackColor", "#32363c").setColor("Slider.trackValueColor", "#568af2")
            .setColor("Slider.focus", "#7a7d7f").setColor("Slider.hoverThumbColor", "#6094ce")
            .setColor("Slider.pressedThumbColor", "#6b9cd2")

            .setColor("MapPanel.background", "#282c34").setColor("MapPanel.gridlineColor", "#5c6370")
            .setColor("TitlePane.background", "#21252b").setColor("TitlePane.unifiedBackground", "false");
        return oneDarkTheme.toUnifiedTheme();
    }

    /**
     * REFACTORED: Create the Botanical theme using component builders
     */
    public static UnifiedThemeDefinition createBotanicalThemeRefactored() {
        ExactColorTheme botanicalTheme = new ExactColorTheme("Botanical", false);
        botanicalTheme
            .setColor("Component.background", "#f5f8f3").setColor("Panel.background", "#edf5e8")
            .setColor("OptionPane.background", "#edf5e8").setColor("PopupMenu.background", "#f5f8f3")
            .setColor("MenuItem.background", "#f5f8f3").setColor("Dialog.background", "#edf5e8")
            .setColor("Component.foreground", "#2d4a2d").setColor("Label.foreground", "#2d4a2d")
            .setColor("Component.focusedBorderColor", "#7fb069").setColor("Component.borderColor", "#c8e0b8")

            .setColor("TextArea.background", "#fdfffe").setColor("TextPane.background", "#fdfffe")
            .setColor("TextField.background", "#f5f8f3").setColor("FormattedTextField.background", "#f5f8f3")
            .setColor("PasswordField.background", "#f5f8f3").setColor("EditorPane.background", "#fdfffe")
            .setColor("TextArea.foreground", "#1a331a").setColor("TextPane.foreground", "#1a331a")
            .setColor("TextField.foreground", "#1a331a")
            .setColor("TextArea.selectionBackground", "#c0f0b8").setColor("TextPane.selectionBackground", "#c0f0b8")
            .setColor("TextField.selectionBackground", "#c0f0b8")

            .setColor("Button.background", "#e8f2e0").setColor("Button.foreground", "#2d4a2d")
            .setColor("Button.focusedBorderColor", "#7fb069").setColor("Button.hoverBackground", "#d8ead0")
            .setColor("Button.pressedBackground", "#c8e0b8").setColor("Button.default.background", "#7fb069")
            .setColor("Button.default.foreground", "#ffffff").setColor("Button.default.hoverBackground", "#6b9d57")

            .setColor("CheckBox.background", "#f5f8f3").setColor("CheckBox.icon.checkmarkColor", "#7fb069")
            .setColor("RadioButton.background", "#f5f8f3").setColor("RadioButton.icon.centerColor", "#7fb069")
            .setColor("RadioButton.icon.centerDiameter", "5")

            .setColor("MenuBar.background", "#e8f2e0").setColor("Menu.background", "#e8f2e0")
            .setColor("Menu.foreground", "#2d4a2d").setColor("MenuItem.foreground", "#2d4a2d")
            .setColor("MenuItem.hoverBackground", "#d8ead0").setColor("MenuItem.selectionBackground", "#c8e0b8")

            .setColor("ToolBar.background", "#e8f2e0").setColor("ToolBar.borderColor", "#c8e0b8")
            .setColor("Separator.foreground", "#c8e0b8").setColor("StatusBar.background", "#e8f2e0")
            .setColor("StatusBar.foreground", "#2d4a2d").setColor("TitledBorder.titleColor", "#2d4a2d")

            .setColor("Table.background", "#fdfffe").setColor("Table.foreground", "#1a331a")
            .setColor("Table.selectionBackground", "#e8f0e8").setColor("Table.selectionForeground", "#1a331a")
            .setColor("Table.gridColor", "#f0f5f0")
            .setColor("List.background", "#fdfffe").setColor("List.foreground", "#1a331a")
            .setColor("List.selectionBackground", "#e8f0e8").setColor("List.selectionForeground", "#1a331a")
            .setColor("Tree.background", "#fdfffe").setColor("Tree.foreground", "#1a331a")
            .setColor("Tree.selectionBackground", "#e8f0e8").setColor("Tree.selectionForeground", "#1a331a")

            .setColor("TabbedPane.background", "#e8f2e0").setColor("TabbedPane.foreground", "#2d4a2d")
            .setColor("TabbedPane.selectedBackground", "#fdfffe").setColor("TabbedPane.hoverColor", "#d8ead0")

            .setColor("ScrollBar.track", "#e8f2e0").setColor("ScrollBar.thumb", "#7fb069")
            .setColor("ScrollBar.hoverThumbColor", "#6b9d57").setColor("ScrollBar.pressedThumbColor", "#5a8a4a")

            .setColor("ComboBox.background", "#f5f8f3").setColor("ComboBox.foreground", "#1a331a")
            .setColor("ComboBox.buttonBackground", "#7fb069").setColor("ComboBox.buttonArrowColor", "#ffffff")
            .setColor("ComboBox.selectionBackground", "#e8f0e8").setColor("ComboBox.selectionForeground", "#1a331a")
            .setColor("Spinner.background", "#f5f8f3").setColor("Spinner.buttonBackground", "#7fb069")
            .setColor("Spinner.buttonArrowColor", "#ffffff")
            .setColor("ProgressBar.background", "#f0f5f0").setColor("ProgressBar.foreground", "#5a8a5a")
            .setColor("ProgressBar.selectionBackground", "#ffffff").setColor("ProgressBar.selectionForeground", "#000000")
            .setColor("ToolTip.background", "#e8f0e8").setColor("ToolTip.foreground", "#1a331a")

            .setColor("SplitPane.background", "#edf5e8").setColor("SplitPaneDivider.draggingColor", "#c8e0b8")
            .setColor("SplitPane.dividerSize", "8").setColor("Component.splitPaneDividerColor", "#c8e0b8")
            .setColor("SplitPane.oneTouchButtonColor", "#e8f2e0").setColor("SplitPane.oneTouchArrowColor", "#2d4a2d")

            .setColor("Slider.thumb", "#7fb069").setColor("Slider.track", "#e8f2e0")
            .setColor("Slider.trackColor", "#e8f2e0").setColor("Slider.trackValueColor", "#7fb069")
            .setColor("Slider.focus", "#c8e0b8").setColor("Slider.hoverThumbColor", "#6b9d57")
            .setColor("Slider.pressedThumbColor", "#5a8a4a")

            .setColor("MapPanel.background", "#fefef9").setColor("MapPanel.gridlineColor", "#c8e0b8")
            .setColor("TitlePane.background", "#e8f2e0").setColor("TitlePane.unifiedBackground", "false");
        return botanicalTheme.toUnifiedTheme();
    }
}