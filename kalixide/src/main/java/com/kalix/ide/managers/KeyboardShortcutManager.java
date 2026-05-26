package com.kalix.ide.managers;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

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
     * Formats a {@link KeyStroke} as a platform-appropriate display string,
     * matching the style of {@link #getShortcutString(String)}: "⌘T" on macOS,
     * "Ctrl+T" on Windows/Linux. Multi-modifier strokes are joined with the
     * platform separator (no separator on macOS, "+" elsewhere).
     *
     * @param ks the keystroke, or null
     * @return the formatted string, or null if {@code ks} is null
     */
    public String formatKeyStroke(KeyStroke ks) {
        if (ks == null) {
            return null;
        }
        String mods = InputEvent.getModifiersExText(ks.getModifiers());
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        if (mods.isEmpty()) {
            return key;
        }
        return IS_MAC ? mods + key : mods + "+" + key;
    }
}