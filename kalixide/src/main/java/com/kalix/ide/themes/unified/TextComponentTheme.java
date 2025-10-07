package com.kalix.ide.themes.unified;

import java.util.Map;

/**
 * Applies text component colors (TextArea, TextField, etc.)
 * Uses exact colors from ExactColorTheme to eliminate duplication.
 */
public class TextComponentTheme {

    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        // Text areas and input fields
        properties.put("TextArea.background", theme.getColor("TextArea.background", "#ffffff"));
        properties.put("TextPane.background", theme.getColor("TextPane.background", "#ffffff"));
        properties.put("TextField.background", theme.getColor("TextField.background", "#ffffff"));
        properties.put("FormattedTextField.background", theme.getColor("FormattedTextField.background", "#ffffff"));
        properties.put("PasswordField.background", theme.getColor("PasswordField.background", "#ffffff"));
        properties.put("EditorPane.background", theme.getColor("EditorPane.background", "#ffffff"));

        // Text colors
        properties.put("TextArea.foreground", theme.getColor("TextArea.foreground", "#000000"));
        properties.put("TextPane.foreground", theme.getColor("TextPane.foreground", "#000000"));
        properties.put("TextField.foreground", theme.getColor("TextField.foreground", "#000000"));

        // Selection colors
        properties.put("TextArea.selectionBackground", theme.getColor("TextArea.selectionBackground", "#2675bf"));
        properties.put("TextPane.selectionBackground", theme.getColor("TextPane.selectionBackground", "#2675bf"));
        properties.put("TextField.selectionBackground", theme.getColor("TextField.selectionBackground", "#2675bf"));
    }
}