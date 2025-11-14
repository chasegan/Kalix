package com.kalix.ide.managers;

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
     * Creates a complete menu item text with shortcut hint.
     * @param menuText The menu item text (e.g., "Save", "Undo")
     * @param key The key (e.g., "S", "Z")
     * @return Complete menu text with tab and shortcut (e.g., "Save\tCmd+S")
     */
    public String getMenuItemWithShortcut(String menuText, String key) {
        return menuText + "\t" + getShortcutString(key);
    }
}