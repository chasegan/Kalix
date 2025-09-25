package com.kalix.ide.managers;

import java.awt.Toolkit;

/**
 * Manager for handling cross-platform keyboard shortcuts and their display strings.
 * Provides platform-aware shortcut hints for menus and tooltips.
 */
public class KeyboardShortcutManager {

    private static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    // Singleton instance
    private static KeyboardShortcutManager instance;

    /**
     * Gets the singleton instance of KeyboardShortcutManager.
     * @return The KeyboardShortcutManager instance
     */
    public static KeyboardShortcutManager getInstance() {
        if (instance == null) {
            instance = new KeyboardShortcutManager();
        }
        return instance;
    }

    private KeyboardShortcutManager() {
        // Private constructor for singleton
    }

    /**
     * Returns the appropriate modifier key string for the current platform.
     * @return "Cmd" on macOS, "Ctrl" on Windows/Linux
     */
    public String getModifierKeyString() {
        return IS_MAC ? "Cmd" : "Ctrl";
    }

    /**
     * Returns the appropriate modifier key string with plus sign for the current platform.
     * @return "Cmd+" on macOS, "Ctrl+" on Windows/Linux
     */
    public String getModifierKeyStringWithPlus() {
        return getModifierKeyString() + "+";
    }

    /**
     * Creates a menu shortcut display string for a single key combination.
     * @param key The key (e.g., "S", "Z", "N")
     * @return Platform-appropriate shortcut string (e.g., "Cmd+S" or "Ctrl+S")
     */
    public String getShortcutString(String key) {
        return getModifierKeyStringWithPlus() + key;
    }

    /**
     * Creates a menu shortcut display string with modifier and key.
     * @param key The key (e.g., "S", "Z", "N")
     * @param withShift Whether to include Shift modifier
     * @return Platform-appropriate shortcut string (e.g., "Cmd+Shift+Z" or "Ctrl+Shift+Z")
     */
    public String getShortcutString(String key, boolean withShift) {
        if (withShift) {
            return getModifierKeyStringWithPlus() + "Shift+" + key;
        }
        return getShortcutString(key);
    }

    /**
     * Creates a complete menu item text with shortcut hint.
     * @param menuText The menu item text (e.g., "Save", "Undo")
     * @param key The key (e.g., "S", "Z")
     * @return Complete menu text with tab and shortcut (e.g., "Save\tCmd+S")
     */
    public String getMenuItemWithShortcut(String menuText, String key) {
        return menuText + "\t" + getShortcutString(key);
    }

    /**
     * Creates a complete menu item text with shortcut hint including Shift.
     * @param menuText The menu item text (e.g., "Redo")
     * @param key The key (e.g., "Z")
     * @param withShift Whether to include Shift modifier
     * @return Complete menu text with tab and shortcut (e.g., "Redo\tCmd+Shift+Z")
     */
    public String getMenuItemWithShortcut(String menuText, String key, boolean withShift) {
        return menuText + "\t" + getShortcutString(key, withShift);
    }

    /**
     * Checks if the current platform is macOS.
     * @return true if running on macOS, false otherwise
     */
    public boolean isMac() {
        return IS_MAC;
    }

    /**
     * Gets the platform-specific menu accelerator key mask.
     * Uses Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() for proper cross-platform behavior.
     * @return The appropriate key mask for the current platform
     */
    @SuppressWarnings("deprecation")
    public int getMenuShortcutKeyMask() {
        // Note: getMenuShortcutKeyMask() is deprecated in newer Java versions,
        // but we use it here for compatibility. In newer versions, use:
        // Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        try {
            // Try the new method first (Java 10+)
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (NoSuchMethodError e) {
            // Fall back to deprecated method for older Java versions
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        }
    }
}