package com.kalix.ide.managers;

import com.formdev.flatlaf.FlatPropertiesLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
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
    private final Component parentComponent;
    
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
                return LightThemeDefinitions.createLightThemeRefactored();
            case "Keylime":
                return LightThemeDefinitions.createKeylimeThemeRefactored();
            case "Lapland":
                return LightThemeDefinitions.createLaplandThemeRefactored();
            case "Nemo":
                return LightThemeDefinitions.createNemoThemeRefactored();
            case "Sunset Warmth":
                return LightThemeDefinitions.createSunsetWarmthThemeRefactored();
            case "Botanical":
                return DarkThemeDefinitions.createBotanicalThemeRefactored();

            // Dark themes
            case "Sanne":
                return DarkThemeDefinitions.createSanneThemeRefactored();
            case "Obsidian":
                return DarkThemeDefinitions.createObsidianThemeRefactored();
            case "Dracula":
                return DarkThemeDefinitions.createDraculaThemeRefactored();
            case "One Dark":
                return DarkThemeDefinitions.createOneDarkThemeRefactored();

            default:
                return null; // Unknown theme
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
        UnifiedThemeDefinition lightTheme = LightThemeDefinitions.createLightThemeRefactored();

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

        // Notify all text editor components about the application theme change
        // This updates colors that aren't automatically handled by SwingUtilities.updateComponentTreeUI
        notifyApplicationThemeChanged();
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

        // Update all instances globally using static methods
        notifySyntaxThemeChanged(syntaxTheme);
    }

    // ========== Static Global Update Methods ==========

    /**
     * Notifies all text editor components about an application theme change.
     * This should be called after switching the FlatLaf theme.
     * Components will update their UI to match the new theme colors.
     */
    public static void notifyApplicationThemeChanged() {
        // MinimalEditorWindow instances need to update current line highlight colors
        com.kalix.ide.windows.MinimalEditorWindow.updateAllForThemeChange();

        // KalixIniTextArea instances need to update current line highlight colors
        com.kalix.ide.components.KalixIniTextArea.updateAllForThemeChange();

        // DiffWindow instances may need theme updates (if they track themes separately)
        // Currently DiffWindow relies on SwingUtilities.updateComponentTreeUI
    }

    /**
     * Notifies all text editor components about a syntax theme change.
     *
     * @param syntaxTheme The new syntax theme to apply
     */
    public static void notifySyntaxThemeChanged(SyntaxTheme.Theme syntaxTheme) {
        com.kalix.ide.windows.MinimalEditorWindow.updateAllSyntaxThemes(syntaxTheme);
        com.kalix.ide.components.KalixIniTextArea.updateAllSyntaxThemes(syntaxTheme);
        com.kalix.ide.diff.DiffWindow.updateAllSyntaxThemes(syntaxTheme);
    }

    /**
     * Notifies all text editor components about a font size change.
     *
     * @param fontSize The new font size in points
     */
    public static void notifyFontSizeChanged(int fontSize) {
        com.kalix.ide.windows.MinimalEditorWindow.updateAllFontSizes(fontSize);
        com.kalix.ide.components.KalixIniTextArea.updateAllFontSizes(fontSize);
        com.kalix.ide.diff.DiffWindow.updateAllFontSizes(fontSize);
    }
}