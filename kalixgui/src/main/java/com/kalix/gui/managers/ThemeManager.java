package com.kalix.gui.managers;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import com.kalix.gui.constants.AppConstants;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

/**
 * Manages theme switching functionality for the application.
 * Handles both initial theme loading and runtime theme changes with animations.
 */
public class ThemeManager {
    
    private final Preferences prefs;
    private String currentTheme;
    private Component parentComponent;
    
    /**
     * Creates a new ThemeManager instance.
     * 
     * @param prefs The preferences object for storing theme settings
     * @param parentComponent The parent component for UI updates
     */
    public ThemeManager(Preferences prefs, Component parentComponent) {
        this.prefs = prefs;
        this.parentComponent = parentComponent;
        this.currentTheme = prefs.get(AppConstants.PREF_THEME, AppConstants.DEFAULT_THEME);
    }
    
    /**
     * Gets the current theme name.
     * 
     * @return The current theme name
     */
    public String getCurrentTheme() {
        return currentTheme;
    }
    
    /**
     * Initializes the look and feel based on stored preferences.
     * Should be called during application startup.
     * 
     * @throws UnsupportedLookAndFeelException if the theme cannot be set
     */
    public void initializeLookAndFeel() throws UnsupportedLookAndFeelException {
        setLookAndFeelForTheme(currentTheme);
        configureFlatLafProperties();
    }
    
    /**
     * Switches to a new theme with animation.
     * 
     * @param theme The name of the theme to switch to
     * @return A status message describing the result
     */
    public String switchTheme(String theme) {
        if (currentTheme.equals(theme)) {
            return "Already using " + theme + " theme";
        }
        
        this.currentTheme = theme;
        prefs.put(AppConstants.PREF_THEME, theme);
        
        // Apply the new theme with animation
        FlatAnimatedLafChange.showSnapshot();
        
        try {
            setLookAndFeelForTheme(theme);
        } catch (UnsupportedLookAndFeelException e) {
            System.err.println(AppConstants.ERROR_FAILED_LOOK_AND_FEEL + e.getMessage());
            return "Failed to switch to " + theme + " theme";
        }
        
        // Update all components with animation
        FlatAnimatedLafChange.hideSnapshotWithAnimation();
        updateAllWindows();
        
        return "Switched to " + theme + " theme";
    }
    
    /**
     * Sets the look and feel for the specified theme.
     * 
     * @param theme The theme name
     * @throws UnsupportedLookAndFeelException if the theme is not supported
     */
    private void setLookAndFeelForTheme(String theme) throws UnsupportedLookAndFeelException {
        switch (theme) {
            case "Light":
                UIManager.setLookAndFeel(new FlatLightLaf());
                break;
            case "Dark":
                UIManager.setLookAndFeel(new FlatDarkLaf());
                break;
            case "Dracula":
                UIManager.setLookAndFeel(new FlatDraculaIJTheme());
                break;
            case "One Dark":
                UIManager.setLookAndFeel(new FlatOneDarkIJTheme());
                break;
            case "Carbon":
                UIManager.setLookAndFeel(new FlatCarbonIJTheme());
                break;
            default:
                UIManager.setLookAndFeel(new FlatLightLaf());
                break;
        }
    }
    
    /**
     * Configures FlatLaf UI properties for better appearance.
     */
    private void configureFlatLafProperties() {
        UIManager.put("TextComponent.arc", 4);
        UIManager.put("Button.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TabbedPane.tabHeight", 32);
        UIManager.put("Table.rowHeight", 24);
    }
    
    /**
     * Updates all open windows with the new theme.
     */
    private void updateAllWindows() {
        // Update the main application window
        if (parentComponent != null) {
            SwingUtilities.updateComponentTreeUI(parentComponent);
        }
        
        // Update all open FlowViz windows
        for (com.kalix.gui.flowviz.FlowVizWindow window : com.kalix.gui.flowviz.FlowVizWindow.getOpenWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
        
        // Update all open dialogs (iterate through all windows to find dialogs)
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isDisplayable()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
        }
    }
    
    /**
     * Configures system properties for better macOS integration.
     */
    public static void configureSystemProperties() {
        System.setProperty(AppConstants.PROP_MACOS_SCREEN_MENU, "true");
        System.setProperty(AppConstants.PROP_MACOS_APP_NAME, "Kalix GUI");
        System.setProperty(AppConstants.PROP_FLATLAF_WINDOW_DECORATIONS, "false");
        System.setProperty(AppConstants.PROP_FLATLAF_MENU_EMBEDDED, "false");
    }
}