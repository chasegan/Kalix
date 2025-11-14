package com.kalix.ide.themes.unified;

import java.awt.Color;
import java.util.Properties;
import java.util.Map;

/**
 * Specification for generating application theme properties from a color palette.
 * Defines how palette colors map to FlatLaf properties.
 */
public class ApplicationThemeSpec {

    /**
     * Generate FlatLaf properties from a color palette with custom overrides.
     * Allows exact color mappings to be preserved for specific themes.
     */
    public static Properties generateProperties(ColorPalette palette, Map<String, String> customOverrides) {
        Properties props = new Properties();

        // Base backgrounds
        props.setProperty("Component.background", colorToHex(palette.getSurface()));
        props.setProperty("Panel.background", colorToHex(palette.getBackground()));
        props.setProperty("OptionPane.background", colorToHex(palette.getBackground()));
        props.setProperty("PopupMenu.background", colorToHex(palette.getSurface()));
        props.setProperty("MenuItem.background", colorToHex(palette.getSurface()));
        props.setProperty("Dialog.background", colorToHex(palette.getBackground()));

        // Text areas and input fields
        Color inputBackground = palette.isDark() ?
            palette.createVariant(palette.getSurface(), 1.2f) :
            palette.createVariant(palette.getSurface(), 0.95f);

        props.setProperty("TextArea.background", colorToHex(inputBackground));
        props.setProperty("TextPane.background", colorToHex(inputBackground));
        props.setProperty("TextField.background", colorToHex(palette.getSurface()));
        props.setProperty("FormattedTextField.background", colorToHex(palette.getSurface()));
        props.setProperty("PasswordField.background", colorToHex(palette.getSurface()));
        props.setProperty("EditorPane.background", colorToHex(inputBackground));

        // Text colors
        props.setProperty("Component.foreground", colorToHex(palette.getOnSurface()));
        props.setProperty("TextArea.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("TextPane.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("TextField.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("Label.foreground", colorToHex(palette.getOnSurface()));

        // Selection colors
        Color selectionBg = palette.getSemanticColor("selection");
        if (selectionBg == null) {
            selectionBg = palette.withAlpha(palette.getPrimary(), 80);
        }

        props.setProperty("TextArea.selectionBackground", colorToHex(selectionBg));
        props.setProperty("TextPane.selectionBackground", colorToHex(selectionBg));
        props.setProperty("TextField.selectionBackground", colorToHex(selectionBg));
        props.setProperty("Component.focusedBorderColor", colorToHex(palette.getPrimary()));

        // Buttons
        Color buttonBg = palette.isDark() ?
            palette.createVariant(palette.getSurface(), 1.3f) :
            palette.createVariant(palette.getSurface(), 0.9f);

        props.setProperty("Button.background", colorToHex(buttonBg));
        props.setProperty("Button.foreground", colorToHex(palette.getOnSurface()));
        props.setProperty("Button.focusedBorderColor", colorToHex(palette.getPrimary()));
        props.setProperty("Button.hoverBackground", colorToHex(palette.withAlpha(palette.getPrimary(), 60)));
        props.setProperty("Button.pressedBackground", colorToHex(palette.getPrimary()));
        props.setProperty("Button.default.background", colorToHex(palette.getPrimary()));
        props.setProperty("Button.default.foreground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));
        props.setProperty("Button.default.hoverBackground", colorToHex(palette.getSecondary()));

        // Menus
        props.setProperty("MenuBar.background", colorToHex(palette.getSurface()));
        props.setProperty("Menu.background", colorToHex(palette.getSurface()));
        props.setProperty("Menu.foreground", colorToHex(palette.getOnSurface()));
        props.setProperty("MenuItem.foreground", colorToHex(palette.getOnSurface()));
        props.setProperty("MenuItem.hoverBackground", colorToHex(palette.withAlpha(palette.getPrimary(), 40)));
        props.setProperty("MenuItem.selectionBackground", colorToHex(palette.withAlpha(palette.getPrimary(), 60)));

        // ComboBox (drop-downs)
        props.setProperty("ComboBox.background", colorToHex(inputBackground));
        props.setProperty("ComboBox.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("ComboBox.buttonBackground", colorToHex(palette.getPrimary()));
        props.setProperty("ComboBox.buttonArrowColor", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));
        props.setProperty("ComboBox.selectionBackground", colorToHex(selectionBg));
        props.setProperty("ComboBox.selectionForeground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));

        // Toolbar
        props.setProperty("ToolBar.background", colorToHex(palette.getSurface()));
        props.setProperty("ToolBar.borderColor", colorToHex(palette.getSemanticColor("border")));

        // Borders and separators
        props.setProperty("Component.borderColor", colorToHex(palette.getSemanticColor("border")));
        props.setProperty("Separator.foreground", colorToHex(palette.getSemanticColor("border")));

        // Tables and lists
        props.setProperty("Table.background", colorToHex(inputBackground));
        props.setProperty("Table.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("Table.selectionBackground", colorToHex(selectionBg));
        props.setProperty("Table.selectionForeground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));
        props.setProperty("Table.gridColor", colorToHex(palette.getSemanticColor("border")));

        props.setProperty("List.background", colorToHex(inputBackground));
        props.setProperty("List.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("List.selectionBackground", colorToHex(selectionBg));
        props.setProperty("List.selectionForeground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));

        props.setProperty("Tree.background", colorToHex(inputBackground));
        props.setProperty("Tree.foreground", colorToHex(palette.getOnBackground()));
        props.setProperty("Tree.selectionBackground", colorToHex(selectionBg));
        props.setProperty("Tree.selectionForeground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));

        // Progress bars
        props.setProperty("ProgressBar.background", colorToHex(buttonBg));
        props.setProperty("ProgressBar.foreground", colorToHex(palette.getPrimary()));
        props.setProperty("ProgressBar.selectionBackground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));
        props.setProperty("ProgressBar.selectionForeground", colorToHex(palette.isDark() ? Color.BLACK : Color.WHITE));

        // Tabs
        props.setProperty("TabbedPane.background", colorToHex(palette.getBackground()));
        props.setProperty("TabbedPane.foreground", colorToHex(palette.getOnSurface()));
        props.setProperty("TabbedPane.selectedBackground", colorToHex(inputBackground));
        props.setProperty("TabbedPane.hoverColor", colorToHex(palette.withAlpha(palette.getPrimary(), 30)));

        // Scrollbars
        Color scrollTrack = palette.getSurface();
        Color scrollThumb = palette.getSemanticColor("border");
        props.setProperty("ScrollBar.track", colorToHex(scrollTrack));
        props.setProperty("ScrollBar.thumb", colorToHex(scrollThumb));
        props.setProperty("ScrollBar.hoverThumbColor", colorToHex(palette.getPrimary()));
        props.setProperty("ScrollBar.pressedThumbColor", colorToHex(palette.getSecondary()));

        // Status bar
        props.setProperty("StatusBar.background", colorToHex(palette.getSurface()));
        props.setProperty("StatusBar.foreground", colorToHex(palette.getOnSurface()));

        // TitledBorder - important for fixing black text in dark themes
        props.setProperty("TitledBorder.titleColor", colorToHex(palette.getOnSurface()));

        // Split panes
        props.setProperty("SplitPane.background", colorToHex(palette.getBackground()));
        props.setProperty("SplitPaneDivider.draggingColor", colorToHex(palette.getSemanticColor("border")));
        props.setProperty("SplitPane.dividerSize", "8");
        props.setProperty("Component.splitPaneDividerColor", colorToHex(palette.getSemanticColor("border")));
        props.setProperty("SplitPane.oneTouchButtonColor", colorToHex(palette.getPrimary()));
        props.setProperty("SplitPane.oneTouchArrowColor", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));

        // Custom properties for Kalix components
        props.setProperty("MapPanel.background", colorToHex(palette.getBackground()));
        props.setProperty("MapPanel.gridlineColor", colorToHex(palette.withAlpha(palette.getPrimary(), 30)));

        // Additional form controls
        props.setProperty("CheckBox.background", colorToHex(palette.getSurface()));
        props.setProperty("RadioButton.background", colorToHex(palette.getSurface()));
        props.setProperty("Spinner.background", colorToHex(palette.getSurface()));
        props.setProperty("CheckBox.icon.checkmarkColor", colorToHex(palette.getPrimary()));
        props.setProperty("RadioButton.icon.centerColor", colorToHex(palette.getPrimary()));

        // Tooltips
        props.setProperty("ToolTip.background", colorToHex(palette.getPrimary()));
        props.setProperty("ToolTip.foreground", colorToHex(palette.isDark() ? Color.WHITE : Color.BLACK));

        // Apply custom overrides if provided
        if (customOverrides != null) {
            for (Map.Entry<String, String> override : customOverrides.entrySet()) {
                props.setProperty(override.getKey(), override.getValue());
            }
        }

        return props;
    }

    private static String colorToHex(Color color) {
        if (color == null) return "#000000";
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }
}