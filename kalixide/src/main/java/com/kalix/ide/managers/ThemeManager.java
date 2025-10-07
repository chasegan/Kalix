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
import com.kalix.ide.themes.unified.UnifiedThemeDefinition;
import com.kalix.ide.utils.Platform;
import com.kalix.ide.utils.PlatformUtils;
import com.kalix.ide.themes.unified.LightThemeDefinitions;
import com.kalix.ide.themes.unified.DarkThemeDefinitions;
import com.kalix.ide.themes.unified.ThemeCompatibilityAdapter;

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
     * Gets the current unified theme definition if available.
     * This allows components to access the full color palette.
     *
     * @return The current unified theme definition or null if using legacy theme
     */
    public UnifiedThemeDefinition getCurrentUnifiedTheme() {
        return getUnifiedThemeDefinition(currentTheme);
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
     * Gets a unified theme definition if available, null otherwise.
     * This allows for gradual migration from legacy to unified themes.
     *
     * @param themeName The name of the theme
     * @return The unified theme definition or null if not available
     */
    private UnifiedThemeDefinition getUnifiedThemeDefinition(String themeName) {
        switch (themeName) {
            // Light themes
            case "Light":
                // Use refactored version if available, fallback to original
                try {
                    return LightThemeDefinitions.createLightThemeRefactored();
                } catch (Exception e) {
                    System.err.println("Refactored Light theme failed, using original: " + e.getMessage());
                    return LightThemeDefinitions.createLightTheme();
                }
            case "Keylime":
                // Use refactored version if available, fallback to original
                try {
                    return LightThemeDefinitions.createKeylimeThemeRefactored();
                } catch (Exception e) {
                    System.err.println("Refactored Keylime theme failed, using original: " + e.getMessage());
                    return LightThemeDefinitions.createKeylimeTheme();
                }
            case "Lapland":
                return LightThemeDefinitions.createLaplandTheme();
            case "Nemo":
                return LightThemeDefinitions.createNemoTheme();
            case "Sunset Warmth":
                return LightThemeDefinitions.createSunsetWarmthTheme();
            case "Botanical":
                return DarkThemeDefinitions.createBotanicalTheme();

            // Dark themes
            case "Sanne":
                return DarkThemeDefinitions.createSanneTheme();
            case "Obsidian":
                return DarkThemeDefinitions.createObsidianTheme();
            case "Dracula":
                return DarkThemeDefinitions.createDraculaTheme();
            case "One Dark":
                return DarkThemeDefinitions.createOneDarkTheme();

            default:
                return null; // Fall back to legacy theme system (none remaining)
        }
    }

    /**
     * Sets the look and feel for the specified theme.
     * Supports both legacy and unified theme systems.
     *
     * @param theme The theme name
     * @throws UnsupportedLookAndFeelException if the theme is not supported
     */
    private void setLookAndFeelForTheme(String theme) throws UnsupportedLookAndFeelException {
        // Try unified theme system first
        UnifiedThemeDefinition unifiedTheme = getUnifiedThemeDefinition(theme);
        if (unifiedTheme != null) {
            FlatPropertiesLaf unifiedLaf = ThemeCompatibilityAdapter.createApplicationTheme(unifiedTheme);
            UIManager.setLookAndFeel(unifiedLaf);
            return;
        }

        // All themes should now use the unified system
        // This fallback should rarely be used since all known themes are unified
        System.err.println("Unknown theme '" + theme + "', falling back to unified Light theme");
        UnifiedThemeDefinition lightTheme = LightThemeDefinitions.createLightTheme();


        FlatPropertiesLaf lightLaf = ThemeCompatibilityAdapter.createApplicationTheme(lightTheme);
        UIManager.setLookAndFeel(lightLaf);
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
        Platform platform = PlatformUtils.getCurrentPlatform();

        switch (platform) {
            case MACOS:
                // macOS-specific properties
                System.setProperty(AppConstants.PROP_MACOS_SCREEN_MENU, "true");
                System.setProperty(AppConstants.PROP_MACOS_APP_NAME, "Kalix IDE");

                // Note: Not setting apple.awt.application.appearance to keep title bars light

                // Disable FlatLaf window decorations (not supported on macOS)
                System.setProperty(AppConstants.PROP_FLATLAF_WINDOW_DECORATIONS, "false");
                System.setProperty(AppConstants.PROP_FLATLAF_MENU_EMBEDDED, "false");
                break;

            case WINDOWS:
            case LINUX:
                // Enable FlatLaf window decorations for custom title bars on Windows/Linux
                System.setProperty(AppConstants.PROP_FLATLAF_WINDOW_DECORATIONS, "true");
                System.setProperty(AppConstants.PROP_FLATLAF_MENU_EMBEDDED, "false");
                break;

            case UNKNOWN:
                // Conservative defaults for unknown platforms
                System.setProperty(AppConstants.PROP_FLATLAF_WINDOW_DECORATIONS, "false");
                System.setProperty(AppConstants.PROP_FLATLAF_MENU_EMBEDDED, "false");
                break;
        }
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