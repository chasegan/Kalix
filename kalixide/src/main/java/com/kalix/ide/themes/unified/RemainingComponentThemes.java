package com.kalix.ide.themes.unified;

import java.util.Map;

/**
 * Contains the remaining component theme builders to complete the refactoring.
 * These eliminate duplication in theme definitions.
 */

class TableComponentTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        properties.put("Table.background", theme.getColor("Table.background", "#ffffff"));
        properties.put("Table.foreground", theme.getColor("Table.foreground", "#000000"));
        properties.put("Table.selectionBackground", theme.getColor("Table.selectionBackground", "#2675bf"));
        properties.put("Table.selectionForeground", theme.getColor("Table.selectionForeground", "#ffffff"));
        properties.put("Table.gridColor", theme.getColor("Table.gridColor", "#c0c0c0"));

        properties.put("List.background", theme.getColor("List.background", "#ffffff"));
        properties.put("List.foreground", theme.getColor("List.foreground", "#000000"));
        properties.put("List.selectionBackground", theme.getColor("List.selectionBackground", "#2675bf"));
        properties.put("List.selectionForeground", theme.getColor("List.selectionForeground", "#ffffff"));

        properties.put("Tree.background", theme.getColor("Tree.background", "#ffffff"));
        properties.put("Tree.foreground", theme.getColor("Tree.foreground", "#000000"));
        properties.put("Tree.selectionBackground", theme.getColor("Tree.selectionBackground", "#2675bf"));
        properties.put("Tree.selectionForeground", theme.getColor("Tree.selectionForeground", "#ffffff"));
    }
}

class ToolbarComponentTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        properties.put("ToolBar.background", theme.getColor("ToolBar.background", "#f2f2f2"));
        properties.put("ToolBar.borderColor", theme.getColor("ToolBar.borderColor", "#c0c0c0"));
        properties.put("Separator.foreground", theme.getColor("Separator.foreground", "#c0c0c0"));

        properties.put("StatusBar.background", theme.getColor("StatusBar.background", "#f2f2f2"));
        properties.put("StatusBar.foreground", theme.getColor("StatusBar.foreground", "#000000"));

        properties.put("TitledBorder.titleColor", theme.getColor("TitledBorder.titleColor", "#000000"));
    }
}

class TabComponentTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        properties.put("TabbedPane.background", theme.getColor("TabbedPane.background", "#f2f2f2"));
        properties.put("TabbedPane.foreground", theme.getColor("TabbedPane.foreground", "#000000"));
        properties.put("TabbedPane.selectedBackground", theme.getColor("TabbedPane.selectedBackground", "#ffffff"));
        properties.put("TabbedPane.hoverColor", theme.getColor("TabbedPane.hoverColor", "#e6e6e6"));
    }
}

class ScrollbarComponentTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        properties.put("ScrollBar.track", theme.getColor("ScrollBar.track", "#f2f2f2"));
        properties.put("ScrollBar.thumb", theme.getColor("ScrollBar.thumb", "#c0c0c0"));
        properties.put("ScrollBar.hoverThumbColor", theme.getColor("ScrollBar.hoverThumbColor", "#89b0d4"));
        properties.put("ScrollBar.pressedThumbColor", theme.getColor("ScrollBar.pressedThumbColor", "#2675bf"));
    }
}

class FormComponentTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        properties.put("ComboBox.background", theme.getColor("ComboBox.background", "#ffffff"));
        properties.put("ComboBox.foreground", theme.getColor("ComboBox.foreground", "#000000"));
        properties.put("ComboBox.buttonBackground", theme.getColor("ComboBox.buttonBackground", "#89b0d4"));
        properties.put("ComboBox.buttonArrowColor", theme.getColor("ComboBox.buttonArrowColor", "#000000"));
        properties.put("ComboBox.selectionBackground", theme.getColor("ComboBox.selectionBackground", "#2675bf"));
        properties.put("ComboBox.selectionForeground", theme.getColor("ComboBox.selectionForeground", "#ffffff"));

        properties.put("Spinner.background", theme.getColor("Spinner.background", "#ffffff"));

        properties.put("ProgressBar.background", theme.getColor("ProgressBar.background", "#f2f2f2"));
        properties.put("ProgressBar.foreground", theme.getColor("ProgressBar.foreground", "#89b0d4"));
        properties.put("ProgressBar.selectionBackground", theme.getColor("ProgressBar.selectionBackground", "#000000"));
        properties.put("ProgressBar.selectionForeground", theme.getColor("ProgressBar.selectionForeground", "#ffffff"));

        properties.put("ToolTip.background", theme.getColor("ToolTip.background", "#ffffe1"));
        properties.put("ToolTip.foreground", theme.getColor("ToolTip.foreground", "#000000"));
    }
}

class CustomKalixTheme {
    public void applyTheme(Map<String, String> properties, ExactColorTheme theme) {
        // Split panes
        properties.put("SplitPane.background", theme.getColor("SplitPane.background", "#f2f2f2"));
        properties.put("SplitPaneDivider.draggingColor", theme.getColor("SplitPaneDivider.draggingColor", "#c0c0c0"));
        properties.put("SplitPane.dividerSize", theme.getColor("SplitPane.dividerSize", "8"));
        properties.put("Component.splitPaneDividerColor", theme.getColor("Component.splitPaneDividerColor", "#c0c0c0"));
        properties.put("SplitPane.oneTouchButtonColor", theme.getColor("SplitPane.oneTouchButtonColor", "#89b0d4"));
        properties.put("SplitPane.oneTouchArrowColor", theme.getColor("SplitPane.oneTouchArrowColor", "#000000"));

        // Custom Kalix components
        properties.put("MapPanel.background", theme.getColor("MapPanel.background", "#ffffff"));
        properties.put("MapPanel.gridlineColor", theme.getColor("MapPanel.gridlineColor", "#e0e0e0"));
    }
}