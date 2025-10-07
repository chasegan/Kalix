package com.kalix.ide.themes.unified;

import java.util.Map;

public class MenuComponentTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        properties.put("MenuBar.background", theme.getColor("MenuBar.background", "#f2f2f2"));
        properties.put("Menu.background", theme.getColor("Menu.background", "#f2f2f2"));
        properties.put("Menu.foreground", theme.getColor("Menu.foreground", "#000000"));
        properties.put("MenuItem.foreground", theme.getColor("MenuItem.foreground", "#000000"));
        properties.put("MenuItem.hoverBackground", theme.getColor("MenuItem.hoverBackground", "#e6e6e6"));
        properties.put("MenuItem.selectionBackground", theme.getColor("MenuItem.selectionBackground", "#2675bf"));
    }
}