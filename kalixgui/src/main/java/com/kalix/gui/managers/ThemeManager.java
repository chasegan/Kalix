package com.kalix.gui.managers;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatPropertiesLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.MapPanel;
import com.kalix.gui.editor.EnhancedTextEditor;

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
    
    // Theme-aware components
    private MapPanel mapPanel;
    private EnhancedTextEditor textEditor;
    
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
            case "Dracula":
                UIManager.setLookAndFeel(new FlatDraculaIJTheme());
                break;
            case "One Dark":
                UIManager.setLookAndFeel(new FlatOneDarkIJTheme());
                break;
            case "Obsidian":
                try {
                    FlatPropertiesLaf obsidianLaf = new FlatPropertiesLaf("Obsidian", 
                        getClass().getResourceAsStream("/themes/obsidian-theme.properties"));
                    UIManager.setLookAndFeel(obsidianLaf);
                } catch (Exception e) {
                    System.err.println("Failed to load Obsidian theme properties, falling back to Dark theme: " + e.getMessage());
                    UIManager.setLookAndFeel(new FlatDarkLaf());
                }
                break;
            case "Keylime":
                try {
                    FlatPropertiesLaf keylimeLaf = new FlatPropertiesLaf("Keylime", 
                        getClass().getResourceAsStream("/themes/keylime-theme.properties"));
                    UIManager.setLookAndFeel(keylimeLaf);
                } catch (Exception e) {
                    System.err.println("Failed to load Keylime theme properties, falling back to Light theme: " + e.getMessage());
                    UIManager.setLookAndFeel(new FlatLightLaf());
                }
                break;
            case "Lapland":
                try {
                    FlatPropertiesLaf laplandLaf = new FlatPropertiesLaf("Lapland", 
                        getClass().getResourceAsStream("/themes/lapland-theme.properties"));
                    UIManager.setLookAndFeel(laplandLaf);
                } catch (Exception e) {
                    System.err.println("Failed to load Lapland theme properties, falling back to Light theme: " + e.getMessage());
                    UIManager.setLookAndFeel(new FlatLightLaf());
                }
                break;
            case "Nemo":
                try {
                    FlatPropertiesLaf nemoLaf = new FlatPropertiesLaf("Nemo", 
                        getClass().getResourceAsStream("/themes/finding-nemo-theme.properties"));
                    UIManager.setLookAndFeel(nemoLaf);
                } catch (Exception e) {
                    System.err.println("Failed to load Nemo theme properties, falling back to Light theme: " + e.getMessage());
                    UIManager.setLookAndFeel(new FlatLightLaf());
                }
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
     * Registers theme-aware components that need custom theme updates.
     * 
     * @param mapPanel The MapPanel instance to update
     * @param textEditor The EnhancedTextEditor instance to update
     */
    public void registerThemeAwareComponents(MapPanel mapPanel, EnhancedTextEditor textEditor) {
        this.mapPanel = mapPanel;
        this.textEditor = textEditor;
    }
    
    /**
     * Updates all open windows with the new theme.
     */
    private void updateAllWindows() {
        // Update the main application window
        if (parentComponent != null) {
            SwingUtilities.updateComponentTreeUI(parentComponent);
        }
        
        // Update custom theme-aware components
        updateCustomComponents();
        
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
     * Updates custom theme-aware components that need special handling.
     */
    private void updateCustomComponents() {
        // Update MapPanel background color
        if (mapPanel != null) {
            SwingUtilities.invokeLater(() -> mapPanel.updateThemeColors());
        }
        
        // Update EnhancedTextEditor theme colors
        if (textEditor != null) {
            SwingUtilities.invokeLater(() -> textEditor.updateThemeColors());
        }
        
        // Update toolbar icon colors
        if (parentComponent instanceof com.kalix.gui.KalixGUI) {
            SwingUtilities.invokeLater(() -> {
                com.kalix.gui.KalixGUI kalixGUI = (com.kalix.gui.KalixGUI) parentComponent;
                kalixGUI.updateToolBar();
            });
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