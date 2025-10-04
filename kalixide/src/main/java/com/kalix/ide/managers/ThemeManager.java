package com.kalix.ide.managers;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatPropertiesLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;

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
     * @param prefs The preferences object for storing theme settings (legacy OS preferences)
     * @param parentComponent The parent component for UI updates
     */
    public ThemeManager(Preferences prefs, Component parentComponent) {
        this.prefs = prefs;
        this.parentComponent = parentComponent;
        // Load theme from new file-based preference system
        this.currentTheme = PreferenceManager.getFileString(PreferenceKeys.UI_THEME, AppConstants.DEFAULT_THEME);
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
        // Save theme to new file-based preference system
        PreferenceManager.setFileString(PreferenceKeys.UI_THEME, theme);
        
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
            case "Botanical":
                try {
                    FlatPropertiesLaf botanicalLaf = new FlatPropertiesLaf("Botanical",
                        getClass().getResourceAsStream("/themes/botanical-theme.properties"));
                    UIManager.setLookAndFeel(botanicalLaf);
                } catch (Exception e) {
                    System.err.println("Failed to load Botanical theme properties, falling back to Light theme: " + e.getMessage());
                    UIManager.setLookAndFeel(new FlatLightLaf());
                }
                break;
            case "Sanne":
                try {
                    FlatPropertiesLaf sanneLaf = new FlatPropertiesLaf("Sanne",
                        getClass().getResourceAsStream("/themes/sanne-theme.properties"));
                    UIManager.setLookAndFeel(sanneLaf);
                } catch (Exception e) {
                    System.err.println("Failed to load Sanne theme properties, falling back to Dark theme: " + e.getMessage());
                    UIManager.setLookAndFeel(new FlatDarkLaf());
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
        UIManager.put("Table.rowHeight", 24);

        // Modern Windows-like tab styling
        UIManager.put("TabbedPane.tabType", "card");
        UIManager.put("TabbedPane.tabsOpaque", false);
        UIManager.put("TabbedPane.tabHeight", 32);
        UIManager.put("TabbedPane.tabInsets", new java.awt.Insets(4, 12, 4, 12));
        UIManager.put("TabbedPane.tabAreaInsets", new java.awt.Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.cardTabInsets", new java.awt.Insets(4, 12, 4, 12));
        UIManager.put("TabbedPane.selectedBackground", UIManager.getColor("Panel.background"));
        UIManager.put("TabbedPane.hoverColor", UIManager.getColor("Button.hoverBackground"));
        UIManager.put("TabbedPane.focusColor", UIManager.getColor("Component.focusColor"));
        UIManager.put("TabbedPane.closeArc", 4);
        UIManager.put("TabbedPane.closeCrossPlainSize", 5.5f);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);
        UIManager.put("TabbedPane.hasFullBorder", false);
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
        for (com.kalix.ide.flowviz.FlowVizWindow window : com.kalix.ide.flowviz.FlowVizWindow.getOpenWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }

        // Update Run Manager if open
        com.kalix.ide.windows.RunManager runManager = com.kalix.ide.windows.RunManager.getOpenInstance();
        if (runManager != null) {
            SwingUtilities.updateComponentTreeUI(runManager);
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
        if (parentComponent instanceof com.kalix.ide.KalixIDE) {
            SwingUtilities.invokeLater(() -> {
                com.kalix.ide.KalixIDE KalixIDE = (com.kalix.ide.KalixIDE) parentComponent;
                KalixIDE.updateToolBar();
            });
        }
    }
    
    /**
     * Configures system properties for better macOS integration.
     */
    public static void configureSystemProperties() {
        System.setProperty(AppConstants.PROP_MACOS_SCREEN_MENU, "true");
        System.setProperty(AppConstants.PROP_MACOS_APP_NAME, "Kalix IDE");
        System.setProperty(AppConstants.PROP_FLATLAF_WINDOW_DECORATIONS, "false");
        System.setProperty(AppConstants.PROP_FLATLAF_MENU_EMBEDDED, "false");
    }

    /**
     * Updates the syntax theme for text editors.
     * Called when the user changes the syntax theme in preferences.
     *
     * @param syntaxTheme The new syntax theme to apply
     */
    public void updateSyntaxTheme(SyntaxTheme.Theme syntaxTheme) {
        // Update EnhancedTextEditor with new syntax theme
        if (textEditor != null) {
            SwingUtilities.invokeLater(() -> textEditor.updateSyntaxTheme(syntaxTheme));
        }

        // Update all open FlowViz windows (if they have text editors)
        for (com.kalix.ide.flowviz.FlowVizWindow window : com.kalix.ide.flowviz.FlowVizWindow.getOpenWindows()) {
            SwingUtilities.invokeLater(() -> {
                // FlowViz windows don't currently have text editors, but if they do in the future
                // we would update them here
            });
        }
    }
}