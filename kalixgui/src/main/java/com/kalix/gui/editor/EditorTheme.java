package com.kalix.gui.editor;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines color themes for the Enhanced Text Editor.
 * Currently supports GitHub Light Colorblind and Atom One Light themes.
 */
public class EditorTheme {
    
    /**
     * Theme color mapping based on GitHub Light Colorblind (Beta) theme.
     * Uses colorblind-friendly colors (orange/blue instead of red/green).
     */
    public static final Map<String, Color> GITHUB_LIGHT_COLORBLIND = new HashMap<String, Color>() {{
        // Syntax highlighting colors
        put("section", new Color(0x05, 0x50, 0xAE));        // #0550AE - Blue for sections [section_name]
        put("propertyName", new Color(0x8A, 0x46, 0x00));   // #8A4600 - Orange for property names
        put("propertyValue", new Color(0x24, 0x29, 0x2F));  // #24292F - Dark gray for property values
        put("comment", new Color(0x6E, 0x77, 0x81));        // #6E7781 - Gray for comments
        put("string", new Color(0x0A, 0x30, 0x69));         // #0A3069 - Dark blue for strings
        put("number", new Color(0x05, 0x50, 0xAE));         // #0550AE - Blue for numbers
        put("default", new Color(0x24, 0x29, 0x2F));        // #24292F - Dark gray for default text
        
        // Editor UI colors
        put("background", new Color(0xFF, 0xFF, 0xFF));      // #FFFFFF - White background
        put("foreground", new Color(0x24, 0x29, 0x2F));     // #24292F - Dark gray foreground
        put("lineNumber", new Color(0x8C, 0x95, 0x9F));     // #8C959F - Gray for line numbers
        put("lineNumberActive", new Color(0x24, 0x29, 0x2F)); // #24292F - Dark gray for active line number
        put("selection", new Color(0x54, 0xAE, 0xFF));       // #54AEFF - Blue for selection (without transparency)
        put("cursor", new Color(0x09, 0x69, 0xDA));          // #0969DA - Blue for cursor
    }};
    
    /**
     * Theme color mapping based on GitHub Light Alt theme.
     * A bright, clean theme with varied colors and purple accent colors for distinction.
     */
    public static final Map<String, Color> GITHUB_LIGHT_ALT = new HashMap<String, Color>() {{
        // Syntax highlighting colors - using more varied palette from Atom One Light
        put("section", new Color(0x82, 0x50, 0xDF));        // #8250DF - Purple for sections [section_name] (functions)
        put("propertyName", new Color(0xB3, 0x59, 0x00));   // #B35900 - Orange/brown for property names (keywords)
        put("propertyValue", new Color(0x57, 0x60, 0x6A));  // #57606A - Medium gray for property values
        put("comment", new Color(0x6E, 0x77, 0x81));        // #6E7781 - Gray for comments (same)
        put("string", new Color(0x0A, 0x30, 0x69));         // #0A3069 - Dark blue for strings (same)
        put("number", new Color(0x82, 0x50, 0xDF));         // #8250DF - Purple for numbers (matching sections)
        put("default", new Color(0x24, 0x29, 0x2F));        // #24292F - Default text color (same)
        
        // Editor UI colors - using Atom One Light accent colors
        put("background", new Color(0xFF, 0xFF, 0xFF));      // #ffffff - White background
        put("foreground", new Color(0x24, 0x29, 0x2F));     // #24292f - Dark gray foreground
        put("lineNumber", new Color(0x8C, 0x95, 0x9F));     // #8c959f - Gray for line numbers
        put("lineNumberActive", new Color(0x57, 0x60, 0x6A)); // #57606a - Medium gray for active line number
        put("selection", new Color(0xFD, 0x8C, 0x73));       // #fd8c73 - Orange/coral for selection (Atom accent)
        put("cursor", new Color(0x82, 0x50, 0xDF));          // #8250df - Purple for cursor (matching theme)
    }};
    
    /**
     * Theme color mapping based on authentic Atom One Light theme.
     * Uses the original colors from the VSCode Atom One Light theme for a genuine experience.
     */
    public static final Map<String, Color> ATOM_ONE_LIGHT = new HashMap<String, Color>() {{
        // Syntax highlighting colors - authentic Atom One Light palette
        put("section", new Color(0x40, 0x78, 0xF2));        // #4078F2 - Blue for sections [section_name] (entity.name.section)
        put("propertyName", new Color(0xA6, 0x26, 0xA4));   // #A626A4 - Purple for property names (keywords/storage)
        put("propertyValue", new Color(0x38, 0x3A, 0x42));  // #383A42 - Dark gray for property values (default text)
        put("comment", new Color(0xA0, 0xA1, 0xA7));        // #A0A1A7 - Light gray for comments
        put("string", new Color(0x50, 0xA1, 0x4F));         // #50A14F - Green for strings
        put("number", new Color(0x98, 0x68, 0x01));         // #986801 - Orange/brown for numbers
        put("default", new Color(0x38, 0x3A, 0x42));        // #383A42 - Dark gray for default text
        
        // Editor UI colors - using Atom One Light base colors
        put("background", new Color(0xFA, 0xFA, 0xFA));      // #fafafa - Very light gray background (from editor background)
        put("foreground", new Color(0x38, 0x3A, 0x42));     // #383a42 - Dark gray foreground
        put("lineNumber", new Color(0x9D, 0x9D, 0x9F));     // #9d9d9f - Gray for line numbers
        put("lineNumberActive", new Color(0x38, 0x3A, 0x42)); // #383a42 - Dark gray for active line number
        put("selection", new Color(0xE5, 0xE5, 0xE6));       // #e5e5e6 - Light gray for selection
        put("cursor", new Color(0x52, 0x67, 0x60));          // #526760 - Teal for cursor
    }};
    
    /**
     * Gets a theme color by name.
     * 
     * @param themeName The name of the theme (currently only "GitHub Light Colorblind" supported)
     * @param colorName The name of the color element
     * @return The Color object, or black if not found
     */
    public static Color getThemeColor(String themeName, String colorName) {
        Map<String, Color> theme = getTheme(themeName);
        return theme.getOrDefault(colorName, Color.BLACK);
    }
    
    /**
     * Gets a theme by name.
     * 
     * @param themeName The name of the theme
     * @return The theme color map
     */
    public static Map<String, Color> getTheme(String themeName) {
        if ("GitHub Light Colorblind".equals(themeName)) {
            return GITHUB_LIGHT_COLORBLIND;
        } else if ("GitHub Light Alt".equals(themeName)) {
            return GITHUB_LIGHT_ALT;
        } else if ("Atom One Light".equals(themeName)) {
            return ATOM_ONE_LIGHT;
        }
        // Default to GitHub Light Colorblind
        return GITHUB_LIGHT_COLORBLIND;
    }
    
    /**
     * Gets available theme names.
     * 
     * @return Array of available theme names
     */
    public static String[] getAvailableThemes() {
        return new String[] {"GitHub Light Colorblind", "GitHub Light Alt", "Atom One Light"};
    }
}