package com.kalix.ide.themes.unified;

import java.util.Map;

/**
 * Applies button component colors including regular buttons, radio buttons, and checkboxes.
 * Uses exact colors from ExactColorTheme to eliminate duplication.
 */
public class ButtonComponentTheme {

    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        // Regular buttons
        properties.put("Button.background", theme.getColor("Button.background", "#ffffff"));
        properties.put("Button.foreground", theme.getColor("Button.foreground", "#000000"));
        properties.put("Button.focusedBorderColor", theme.getColor("Button.focusedBorderColor", "#89b0d4"));
        properties.put("Button.hoverBackground", theme.getColor("Button.hoverBackground", "#f7f7f7"));
        properties.put("Button.pressedBackground", theme.getColor("Button.pressedBackground", "#e6e6e6"));

        // Default buttons
        properties.put("Button.default.background", theme.getColor("Button.default.background", "#ffffff"));
        properties.put("Button.default.foreground", theme.getColor("Button.default.foreground", "#000000"));
        properties.put("Button.default.hoverBackground", theme.getColor("Button.default.hoverBackground", "#f7f7f7"));

        // Radio buttons
        properties.put("RadioButton.background", theme.getColor("RadioButton.background", "#f2f2f2"));
        properties.put("RadioButton.icon.centerColor", theme.getColor("RadioButton.icon.centerColor", "#000000"));

        // Checkboxes
        properties.put("CheckBox.background", theme.getColor("CheckBox.background", "#f2f2f2"));
        properties.put("CheckBox.icon.checkmarkColor", theme.getColor("CheckBox.icon.checkmarkColor", "#000000"));
    }
}