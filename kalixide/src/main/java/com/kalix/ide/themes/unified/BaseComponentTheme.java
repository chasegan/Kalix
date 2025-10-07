package com.kalix.ide.themes.unified;

import java.util.Map;

/**
 * Applies base component colors (Panel, Component, Dialog, etc.)
 * Uses exact colors from ExactColorTheme to eliminate duplication.
 */
public class BaseComponentTheme {

    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        // Base backgrounds
        properties.put("Component.background", theme.getColor("Component.background", "#f2f2f2"));
        properties.put("Panel.background", theme.getColor("Panel.background", "#f2f2f2"));
        properties.put("OptionPane.background", theme.getColor("OptionPane.background", "#f2f2f2"));
        properties.put("PopupMenu.background", theme.getColor("PopupMenu.background", "#ffffff"));
        properties.put("MenuItem.background", theme.getColor("MenuItem.background", "#ffffff"));
        properties.put("Dialog.background", theme.getColor("Dialog.background", "#f2f2f2"));

        // Base text colors
        properties.put("Component.foreground", theme.getColor("Component.foreground", "#000000"));
        properties.put("Label.foreground", theme.getColor("Label.foreground", "#000000"));

        // Focus and borders
        properties.put("Component.focusedBorderColor", theme.getColor("Component.focusedBorderColor", "#89b0d4"));
        properties.put("Component.borderColor", theme.getColor("Component.borderColor", "#c0c0c0"));

        // Title bars (for themes that have them)
        if (theme.getColor("TitlePane.background") != null) {
            properties.put("TitlePane.background", theme.getColor("TitlePane.background"));
            properties.put("TitlePane.unifiedBackground", theme.getColor("TitlePane.unifiedBackground", "false"));
        }
    }
}