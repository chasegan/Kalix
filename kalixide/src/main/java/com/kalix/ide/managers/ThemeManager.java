package com.kalix.ide.managers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatPropertiesLaf;
import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.themes.KalixTheme;
import com.kalix.ide.themes.ThemePreferences;
import com.kalix.ide.utils.Platform;
import com.kalix.ide.utils.PlatformUtils;
import com.kalix.ide.themes.unified.ThemeCompatibilityAdapter;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Component;

/**
 * Manages theme switching functionality for the application.
 * Handles both initial theme loading and runtime theme changes with animations.
 */
public class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    
    private KalixTheme currentTheme;
    private final Component parentComponent;

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
     * Switches to a new theme. When the node or syntax theme is
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

        try {
            setLookAndFeelForTheme(theme);

            // Re-derive UIManager tweaks that depend on the now-current LaF
            // (e.g. TabbedPane.selectedBackground is copied from Panel.background);
            // the values set at startup are stale after a runtime switch.
            configureFlatLafProperties();

            // The built-in plot palette follows the theme (Kalix.plot.seriesN):
            // re-resolve it now so plots using the default palette recolour live.
            com.kalix.ide.flowviz.style.PlotPaletteManager.onThemeChanged();

            updateAllWindows();

            // Node and syntax themes in follow mode track the application theme
            applyFollowedThemes(theme);

            return "Switched to " + theme.displayName() + " theme";
        } catch (UnsupportedLookAndFeelException e) {
            logger.error(AppConstants.ERROR_FAILED_LOOK_AND_FEEL, e);
            return "Failed to switch to " + theme.displayName() + " theme";
        }
    }

    /**
     * Applies the theme's linked node palette wherever the user has not made an
     * explicit choice (follow mode, the default). The syntax palette needs no step
     * of its own: every editor resolves it from the preferences in {@code updateUI},
     * which the look-and-feel walk has just run.
     */
    private void applyFollowedThemes(KalixTheme theme) {
        if (ThemePreferences.isNodeThemeFollowing()
                && parentComponent instanceof com.kalix.ide.KalixIDE ide) {
            SwingUtilities.invokeLater(() -> ide.setNodeTheme(theme.nodeTheme()));
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
        // TabbedPane.selectedBackground: a theme that sets the key in its own
        // properties file wins (the dark themes define a designed tab-selected
        // colour). Only themes that merely inherit the legacy default (Light)
        // keep the historically derived Panel.background value. Putting null
        // clears any derived value left in UIManager by a previous switch.
        UIManager.put("TabbedPane.selectedBackground",
            currentTheme.definition().definesExplicitly("TabbedPane.selectedBackground")
                ? null
                : UIManager.getColor("Panel.background"));
        UIManager.put("TabbedPane.hoverColor", UIManager.getColor("Button.hoverBackground"));
        UIManager.put("TabbedPane.focusColor", UIManager.getColor("Component.focusColor"));
        UIManager.put("TabbedPane.closeArc", 4);
        UIManager.put("TabbedPane.closeCrossPlainSize", 5.5f);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);
        UIManager.put("TabbedPane.hasFullBorder", false);
    }
    
    /**
     * Updates every open window with the new theme. FlatLaf walks every window, frames
     * and dialogs alike, and each component re-resolves its own colours in
     * {@code updateUI}: the editors through {@code EditorTheme}, the map and the data
     * view through their own. The toolbar's icons are recoloured here because they are
     * built, not themed.
     */
    private void updateAllWindows() {
        FlatLaf.updateUI();
        if (parentComponent instanceof com.kalix.ide.KalixIDE ide) {
            SwingUtilities.invokeLater(ide::updateToolBar);
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
     * Re-themes every editor after the syntax palette preference changes. The palette
     * is read in each editor's {@code updateUI}, so the ordinary look-and-feel walk
     * over every window is the whole update.
     */
    public void syntaxThemeChanged() {
        FlatLaf.updateUI();
    }

    /**
     * Notifies all text editor components about a font size change.
     *
     * @param fontSize The new font size in points
     */
    public static void notifyFontSizeChanged(int fontSize) {
        com.kalix.ide.components.KalixTextArea.updateAllFontSizes(fontSize);
        com.kalix.ide.dataview.VirtualTextArea.updateAllFontSizes(fontSize);
    }
}