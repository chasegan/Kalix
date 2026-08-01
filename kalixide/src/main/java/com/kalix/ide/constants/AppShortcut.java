package com.kalix.ide.constants;

import com.kalix.ide.managers.KeyboardShortcutManager;

import javax.swing.KeyStroke;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * The single source of truth for application-level keyboard shortcuts.
 *
 * <p>Each action declares its key once here; the menu accelerator
 * ({@code MenuBarBuilder}) and the toolbar tooltip hint ({@code ToolBarBuilder}) both
 * derive from the same declaration, so they cannot drift apart — the same principle as
 * the editor's {@code CommandMetadata}. (Historically the menu and the tooltips each
 * declared shortcuts independently, which let a tooltip advertise a shortcut its action
 * never had.)
 *
 * <p>Every stroke is the platform menu modifier (Cmd on macOS, Ctrl elsewhere) plus any
 * extra modifiers declared here. Note: {@code EnhancedTextEditor} registers some of these
 * bindings for both Cmd and Ctrl explicitly (a deliberate belt-and-braces); this enum is
 * the platform-correct declaration that user-facing hints are built from.
 */
public enum AppShortcut {

    NEW_MODEL("New Model", KeyEvent.VK_N),
    OPEN_MODEL("Open Model", KeyEvent.VK_O),
    OPEN_FOLDER("Open Folder", KeyEvent.VK_O, InputEvent.SHIFT_DOWN_MASK),
    SAVE_MODEL("Save Model", KeyEvent.VK_S),
    SAVE_MODEL_AS("Save Model As", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK),
    PREFERENCES("Preferences", KeyEvent.VK_COMMA),
    UNDO("Undo", KeyEvent.VK_Z),
    REDO("Redo", KeyEvent.VK_Y),
    CUT("Cut", KeyEvent.VK_X),
    COPY("Copy", KeyEvent.VK_C),
    PASTE("Paste", KeyEvent.VK_V),
    TOGGLE_COMMENT("Toggle Comment", KeyEvent.VK_SLASH),
    FIND("Find", KeyEvent.VK_F),
    FIND_AND_REPLACE("Find and Replace", KeyEvent.VK_H),
    TERMINAL("Terminal", KeyEvent.VK_BACK_QUOTE),
    /**
     * Repeat the last search. {@code ⌘G}/{@code ⇧⌘G} is the macOS system-wide
     * convention; {@code EnhancedTextEditor} additionally binds bare F3/Shift+F3
     * for the Windows/Linux convention, which this enum cannot express because
     * {@link #keyStroke()} always applies the menu modifier.
     */
    FIND_NEXT("Find Next", KeyEvent.VK_G),
    FIND_PREVIOUS("Find Previous", KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK),
    /**
     * Editor-only: bound in {@code EnhancedTextEditor}; has no menu item.
     * On L rather than G (IntelliJ/Xcode/Eclipse convention) so that G is free
     * for {@link #FIND_NEXT}, which has a far stronger claim to it.
     */
    GO_TO_LINE("Go to Line", KeyEvent.VK_L),
    TOGGLE_FILE_TREE("Toggle File Tree", KeyEvent.VK_B),
    RUN_MODEL("Run Model", KeyEvent.VK_R),
    NAVIGATE_BACK("Navigate Back", KeyEvent.VK_OPEN_BRACKET),
    NAVIGATE_FORWARD("Navigate Forward", KeyEvent.VK_CLOSE_BRACKET);

    private final String label;
    private final int keyCode;
    private final int extraModifiers;

    AppShortcut(String label, int keyCode) {
        this(label, keyCode, 0);
    }

    AppShortcut(String label, int keyCode, int extraModifiers) {
        this.label = label;
        this.keyCode = keyCode;
        this.extraModifiers = extraModifiers;
    }

    /** The action's display name, as used in toolbar tooltips. */
    public String label() {
        return label;
    }

    /**
     * The platform menu modifier — Cmd on macOS, Ctrl elsewhere — as an
     * {@code InputEvent} mask.
     *
     * <p>The one place the toolkit is asked. Callers outside this enum need it too
     * (map key bindings, context-menu hints, the table and parameter-sheet windows),
     * and asking separately each time both duplicated the platform rule and left every
     * site to rediscover that the query is display-dependent.
     *
     * <p>Falls back to Ctrl when headless, where {@code getMenuShortcutKeyMaskEx} throws.
     * There is no menu bar to match without a display, so any well-formed modifier will
     * do; what matters is that a capability query cannot abort the caller's construction.
     */
    public static int menuMask() {
        return GraphicsEnvironment.isHeadless()
            ? InputEvent.CTRL_DOWN_MASK
            : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    /** The platform keystroke: the menu shortcut modifier plus any declared extras. */
    public KeyStroke keyStroke() {
        return KeyStroke.getKeyStroke(keyCode, menuMask() | extraModifiers);
    }

    /**
     * The keystroke with an explicit primary modifier in place of the platform one —
     * for callers that deliberately register both the Cmd and Ctrl variants (the
     * editor's belt-and-braces bindings).
     */
    public KeyStroke keyStrokeWith(int primaryModifier) {
        return KeyStroke.getKeyStroke(keyCode, primaryModifier | extraModifiers);
    }

    /** The display hint for this stroke, e.g. {@code ⇧⌘O} on macOS, {@code Ctrl+Shift+O} elsewhere. */
    public String hint() {
        return KeyboardShortcutManager.getInstance().formatKeyStroke(keyStroke());
    }

    /** The full tooltip: label plus hint, e.g. {@code Open Folder (⇧⌘O)}. */
    public String tooltip() {
        return label + " (" + hint() + ")";
    }
}
