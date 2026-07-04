package com.kalix.ide.managers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.formdev.flatlaf.FlatPropertiesLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.themes.KalixTheme;
import com.kalix.ide.themes.SyntaxTheme;
import com.kalix.ide.themes.ThemePreferences;
import com.kalix.ide.utils.Platform;
import com.kalix.ide.utils.PlatformUtils;
import com.kalix.ide.themes.unified.ThemeCompatibilityAdapter;

import javax.swing.*;
import java.awt.*;

/**
 * Manages theme switching functionality for the application.
 * Handles both initial theme loading and runtime theme changes with animations.
 */
public class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    
    private KalixTheme currentTheme;
    private final Component parentComponent;

    // Theme-aware components
    private MapPanel mapPanel;
    private EnhancedTextEditor textEditor;

    /**
     * Creates a new ThemeManager instance.
     *
     * @param parentComponent The parent component for UI updates
     */
    public ThemeManager(Component parentComponent) {
        this.parentComponent = parentComponent;
        // Resolve the stored theme (migrating legacy display names to stable ids)
        this.currentTheme = ThemePreferences.applicationTheme();
    }

    /**
     * Gets the current theme.
     *
     * @return The current theme
     */
    public KalixTheme getCurrentTheme() {
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
     * Switches to a new theme with animation. When the node or syntax theme is
     * in follow mode (the default), the matching linked palette is applied to
     * all documents and editors as part of the same switch.
     *
     * @param theme The theme to switch to
     * @return A status message describing the result
     */
    public String switchTheme(KalixTheme theme) {
        if (currentTheme == theme) {
            return "Already using " + theme.displayName() + " theme";
        }

        this.currentTheme = theme;
        ThemePreferences.storeApplicationTheme(theme);

        // Apply the new theme with animation. FlatLaf's documented order is
        // showSnapshot -> setLookAndFeel -> update UI -> hideSnapshotWithAnimation,
        // so the cross-fade blends the OLD look into the fully updated NEW look.
        FlatAnimatedLafChange.showSnapshot();

        try {
            setLookAndFeelForTheme(theme);

            // Re-derive UIManager tweaks that depend on the now-current LaF
            // (e.g. TabbedPane.selectedBackground is copied from Panel.background);
            // the values set at startup are stale after a runtime switch.
            configureFlatLafProperties();

            // Update all components while the snapshot overlay is still showing
            updateAllWindows();

            // Node and syntax themes in follow mode track the application theme
            applyFollowedThemes(theme);

            return "Switched to " + theme.displayName() + " theme";
        } catch (UnsupportedLookAndFeelException e) {
            logger.error(AppConstants.ERROR_FAILED_LOOK_AND_FEEL, e);
            return "Failed to switch to " + theme.displayName() + " theme";
        } finally {
            // Always dismiss the snapshot overlay, even when the switch fails —
            // otherwise the UI is left frozen behind the stale snapshot.
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
    }

    /**
     * Applies the theme's linked node and syntax palettes wherever the user has
     * not made an explicit choice (follow mode, the default). Explicit choices
     * are left untouched.
     */
    private void applyFollowedThemes(KalixTheme theme) {
        if (ThemePreferences.isNodeThemeFollowing()
                && parentComponent instanceof com.kalix.ide.KalixIDE ide) {
            SwingUtilities.invokeLater(() -> ide.setNodeTheme(theme.nodeTheme()));
        }
        if (ThemePreferences.isSyntaxThemeFollowing()) {
            updateSyntaxTheme(theme.syntaxTheme());
        }
    }

    /**
     * Sets the look and feel for the specified theme.
     *
     * @param theme The theme
     * @throws UnsupportedLookAndFeelException if the theme is not supported
     */
    private void setLookAndFeelForTheme(KalixTheme theme) throws UnsupportedLookAndFeelException {
        FlatPropertiesLaf laf = ThemeCompatibilityAdapter.createApplicationTheme(theme.definition());
        UIManager.setLookAndFeel(laf);
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