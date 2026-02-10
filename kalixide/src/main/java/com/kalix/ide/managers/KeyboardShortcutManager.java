package com.kalix.ide.managers;

/**
 * Manager for handling cross-platform keyboard shortcuts and their display strings.
 * Provides platform-aware shortcut hints for menus and tooltips.
 *
 * Uses standard platform conventions:
 * - macOS: ⌘ symbol without plus (e.g., "⌘S")
 * - Windows/Linux: Ctrl with plus (e.g., "Ctrl+S")
 */
public class KeyboardShortcutManager {

    private static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");

    // macOS modifier symbols
    private static final String MAC_COMMAND = "⌘";
    private static final String MAC_OPTION = "⌥";
    private static final String MAC_SHIFT = "⇧";
    private static final String MAC_CONTROL = "⌃";

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
     * @return "⌘" on macOS, "Ctrl" on Windows/Linux
     */
    public String getModifierKeyString() {
        return IS_MAC ? MAC_COMMAND : "Ctrl";
    }

    /**
     * Creates a menu shortcut display string for a single key combination.
     * @param key The key (e.g., "S", "Z", "N")
     * @return Platform-appropriate shortcut string (e.g., "⌘S" on Mac, "Ctrl+S" on Windows)
     */
    public String getShortcutString(String key) {
        if (IS_MAC) {
            return MAC_COMMAND + key;
        } else {
            return "Ctrl+" + key;
        }
    }

    /**
     * Creates a complete menu item text with shortcut hint.
     * @param menuText The menu item text (e.g., "Save", "Undo")
     * @param key The key (e.g., "S", "Z")
     * @return Complete menu text with tab and shortcut (e.g., "Save\t⌘S" or "Save\tCtrl+S")
     */
    public String getMenuItemWithShortcut(String menuText, String key) {
        return menuText + "\t" + getShortcutString(key);
    }
}