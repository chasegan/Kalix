package com.kalix.ide.themes.unified;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder for a {@link UnifiedThemeDefinition}: collects a theme's exact
 * FlatLaf property values and combines them with {@link #DEFAULT_PROPERTIES}
 * (fallbacks for keys a theme omits).
 *
 * <p>Every color set here reaches FlatLaf verbatim; the exact output for all
 * built-in themes is pinned by {@code ThemePropertiesSnapshotTest}.
 */
public class ExactColorTheme {

    /**
     * Fallback values applied for keys a theme does not set explicitly.
     *
     * <p>These are the effective fallbacks of the deleted component-builder
     * layer (BaseComponentTheme, ButtonComponentTheme, ...), folded into one
     * plain map and kept byte-identical — they are light-coloured legacy
     * defaults, so dark themes should override every key they care about.
     * Grouped as the builders were.
     */
    private static final Map<String, String> DEFAULT_PROPERTIES = Map.ofEntries(
        // Base components
        Map.entry("Component.background", "#f2f2f2"),
        Map.entry("Panel.background", "#f2f2f2"),
        Map.entry("OptionPane.background", "#f2f2f2"),
        Map.entry("PopupMenu.background", "#ffffff"),
        Map.entry("MenuItem.background", "#ffffff"),
        Map.entry("Dialog.background", "#f2f2f2"),
        Map.entry("Component.foreground", "#000000"),
        Map.entry("Label.foreground", "#000000"),
        Map.entry("Component.focusedBorderColor", "#89b0d4"),
        Map.entry("Component.borderColor", "#c0c0c0"),

        // Buttons, checkboxes, radio buttons
        Map.entry("Button.background", "#ffffff"),
        Map.entry("Button.foreground", "#000000"),
        Map.entry("Button.focusedBorderColor", "#89b0d4"),
        Map.entry("Button.hoverBackground", "#f7f7f7"),
        Map.entry("Button.pressedBackground", "#e6e6e6"),
        Map.entry("Button.default.background", "#ffffff"),
        Map.entry("Button.default.foreground", "#000000"),
        Map.entry("Button.default.hoverBackground", "#f7f7f7"),
        Map.entry("RadioButton.background", "#f2f2f2"),
        Map.entry("RadioButton.icon.centerColor", "#000000"),
        Map.entry("CheckBox.background", "#f2f2f2"),
        Map.entry("CheckBox.icon.checkmarkColor", "#000000"),

        // Text components
        Map.entry("TextArea.background", "#ffffff"),
        Map.entry("TextPane.background", "#ffffff"),
        Map.entry("TextField.background", "#ffffff"),
        Map.entry("FormattedTextField.background", "#ffffff"),
        Map.entry("PasswordField.background", "#ffffff"),
        Map.entry("EditorPane.background", "#ffffff"),
        Map.entry("TextArea.foreground", "#000000"),
        Map.entry("TextPane.foreground", "#000000"),
        Map.entry("TextField.foreground", "#000000"),
        Map.entry("TextArea.selectionBackground", "#2675bf"),
        Map.entry("TextPane.selectionBackground", "#2675bf"),
        Map.entry("TextField.selectionBackground", "#2675bf"),

        // Menus
        Map.entry("MenuBar.background", "#f2f2f2"),
        Map.entry("MenuBar.foreground", "#000000"),
        Map.entry("Menu.background", "#f2f2f2"),
        Map.entry("Menu.foreground", "#000000"),
        Map.entry("MenuItem.foreground", "#000000"),
        Map.entry("MenuItem.hoverBackground", "#e6e6e6"),
        Map.entry("MenuItem.selectionBackground", "#2675bf"),

        // Tables, lists, trees
        Map.entry("Table.background", "#ffffff"),
        Map.entry("Table.foreground", "#000000"),
        Map.entry("Table.selectionBackground", "#2675bf"),
        Map.entry("Table.selectionForeground", "#ffffff"),
        Map.entry("Table.gridColor", "#c0c0c0"),
        Map.entry("List.background", "#ffffff"),
        Map.entry("List.foreground", "#000000"),
        Map.entry("List.selectionBackground", "#2675bf"),
        Map.entry("List.selectionForeground", "#ffffff"),
        Map.entry("Tree.background", "#ffffff"),
        Map.entry("Tree.foreground", "#000000"),
        Map.entry("Tree.selectionBackground", "#2675bf"),
        Map.entry("Tree.selectionForeground", "#ffffff"),

        // Toolbar, status bar, separators
        Map.entry("ToolBar.background", "#f2f2f2"),
        Map.entry("ToolBar.borderColor", "#c0c0c0"),
        Map.entry("Separator.foreground", "#c0c0c0"),
        Map.entry("StatusBar.background", "#f2f2f2"),
        Map.entry("StatusBar.foreground", "#000000"),
        Map.entry("TitledBorder.titleColor", "#000000"),

        // Tabs
        Map.entry("TabbedPane.background", "#f2f2f2"),
        Map.entry("TabbedPane.foreground", "#000000"),
        Map.entry("TabbedPane.selectedBackground", "#ffffff"),
        Map.entry("TabbedPane.hoverColor", "#e6e6e6"),

        // Scrollbars
        Map.entry("ScrollBar.track", "#f2f2f2"),
        Map.entry("ScrollBar.thumb", "#c0c0c0"),
        Map.entry("ScrollBar.hoverThumbColor", "#89b0d4"),
        Map.entry("ScrollBar.pressedThumbColor", "#2675bf"),

        // Form components
        Map.entry("ComboBox.background", "#ffffff"),
        Map.entry("ComboBox.foreground", "#000000"),
        Map.entry("ComboBox.buttonBackground", "#89b0d4"),
        Map.entry("ComboBox.buttonArrowColor", "#000000"),
        Map.entry("ComboBox.selectionBackground", "#2675bf"),
        Map.entry("ComboBox.selectionForeground", "#ffffff"),
        Map.entry("Spinner.background", "#ffffff"),
        Map.entry("ProgressBar.background", "#f2f2f2"),
        Map.entry("ProgressBar.foreground", "#89b0d4"),
        Map.entry("ProgressBar.selectionBackground", "#000000"),
        Map.entry("ProgressBar.selectionForeground", "#ffffff"),
        Map.entry("ToolTip.background", "#ffffe1"),
        Map.entry("ToolTip.foreground", "#000000"),

        // Split panes and custom Kalix components
        Map.entry("SplitPane.background", "#f2f2f2"),
        Map.entry("SplitPaneDivider.draggingColor", "#c0c0c0"),
        Map.entry("SplitPane.dividerSize", "8"),
        Map.entry("Component.splitPaneDividerColor", "#c0c0c0"),
        Map.entry("SplitPane.oneTouchButtonColor", "#89b0d4"),
        Map.entry("SplitPane.oneTouchArrowColor", "#000000"),
        Map.entry("MapPanel.background", "#ffffff"),
        Map.entry("MapPanel.gridlineColor", "#e0e0e0")
    );

    private final String name;
    private final boolean isDark;
    private final Map<String, String> colors;

    public ExactColorTheme(String name, boolean isDark) {
        this.name = name;
        this.isDark = isDark;
        this.colors = new HashMap<>();
    }

    /**
     * Set an exact color for a property
     */
    public ExactColorTheme setColor(String property, String hexColor) {
        colors.put(property, hexColor);
        return this;
    }

    /**
     * Create a UnifiedThemeDefinition from this exact color theme
     */
    public UnifiedThemeDefinition toUnifiedTheme() {
        Map<String, String> properties = new HashMap<>(DEFAULT_PROPERTIES);

        // TitlePane keys have no unconditional defaults: themes without a custom
        // title bar must not emit them. Only supply the unifiedBackground default
        // when the theme opts in by setting TitlePane.background.
        if (colors.containsKey("TitlePane.background")) {
            properties.put("TitlePane.unifiedBackground", "false");
        }

        // Every explicitly set color passes through verbatim.
        properties.putAll(colors);

        return new UnifiedThemeDefinition(name, isDark, properties);
    }
}
