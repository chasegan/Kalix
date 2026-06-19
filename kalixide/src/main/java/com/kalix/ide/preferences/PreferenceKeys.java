package com.kalix.ide.preferences;

import java.util.List;

/**
 * Centralized constants for all preference keys used throughout the application.
 *
 * File-based preferences are stored in kalix_prefs.json and are portable/shareable.
 * OS-based preferences are stored in the system's preference store and handle UI state.
 */
public class PreferenceKeys {

    // ==== FILE-BASED PREFERENCES (kalix_prefs.json) ====
    // These preferences are portable and should be shared between users/machines

    /** FlowViz coordinate display toggle (boolean, default: false) */
    public static final String FLOWVIZ_SHOW_COORDINATES = "flowviz.showCoordinates";

    /** FlowViz 64-bit precision toggle for data export (boolean, default: true) */
    public static final String FLOWVIZ_PRECISION64 = "flowviz.precision64";

    /** FlowViz Auto-Y mode toggle (boolean, default: true) */
    public static final String FLOWVIZ_AUTO_Y_MODE = "flowviz.autoYMode";

    /** Plot legend enabled toggle (boolean, default: true) */
    public static final String PLOT_LEGEND_ENABLED = "plot.legend.enabled";

    /** Plot legend collapsed state (boolean, default: false) */
    public static final String PLOT_LEGEND_COLLAPSED = "plot.legend.collapsed";

    /** Plot legend X position (int, default: -1 for auto-position) */
    public static final String PLOT_LEGEND_POSITION_X = "plot.legend.position.x";

    /** Plot legend Y position (int, default: -1 for auto-position) */
    public static final String PLOT_LEGEND_POSITION_Y = "plot.legend.position.y";

    /** Plot legend display mode (string, default: "FULL_NAME") */
    public static final String PLOT_LEGEND_DISPLAY_MODE = "plot.legend.display.mode";

    /** Log scale auto-zoom minimum value threshold (double, default: 0.001) */
    public static final String PLOT_LOG_SCALE_MIN_THRESHOLD = "plot.logScale.minThreshold";

    /** Custom plot palettes, one encoded string per user-defined palette (string list, default: empty) */
    public static final String PLOT_PALETTES = "plot.palettes";

    /** Name of the globally active plot palette (string, default: "Default") */
    public static final String PLOT_ACTIVE_PALETTE = "plot.activePalette";

    /** STDIO format for get_result responses ("pixie" or "csv", default: "pixie") */
    public static final String STDIO_DATA_FORMAT = "stdio.dataFormat";

    /** UI theme selection (string, default: "Light") */
    public static final String UI_THEME = "ui.theme";

    /** Node theme selection (string, default: "LIGHT") */
    public static final String UI_NODE_THEME = "ui.nodeTheme";

    /** Syntax theme selection (string, default: "LIGHT") */
    public static final String UI_SYNTAX_THEME = "ui.syntaxTheme";

    /** Width in pixels of the project tree region (OS UI state). */
    public static final String UI_TREE_WIDTH = "ui.treeWidth";

    /** Width in pixels of the contextual view (map) region (OS UI state). */
    public static final String UI_MAP_WIDTH = "ui.mapWidth";

    /** Whether the project tree region is collapsed (OS UI state). */
    public static final String UI_TREE_COLLAPSED = "ui.treeCollapsed";

    /** Whether the contextual view (map) region is collapsed (OS UI state). */
    public static final String UI_MAP_COLLAPSED = "ui.mapCollapsed";

    /** Absolute path of the currently open project folder, or empty if none (OS UI state). */
    public static final String UI_WORKSPACE_FOLDER = "ui.workspaceFolder";

    /** Open document tabs as newline-separated {@code caret<TAB>absolutePath} entries (OS UI state). */
    public static final String UI_OPEN_DOCUMENTS = "ui.openDocuments";

    /** Absolute path of the active document tab to restore (OS UI state). */
    public static final String UI_ACTIVE_DOCUMENT = "ui.activeDocument";

    /** Editor font size (int, default: 12) */
    public static final String EDITOR_FONT_SIZE = "editor.fontSize";

    /** Map gridlines visibility toggle (boolean, default: true) */
    public static final String MAP_SHOW_GRIDLINES = "map.showGridlines";

    /** Whether the project tree shows hidden (dot-prefixed) files/folders (boolean, default: true) */
    public static final String TREE_SHOW_HIDDEN_FILES = "tree.showHiddenFiles";

    /** KalixCLI binary path (string, default: "") */
    public static final String CLI_BINARY_PATH = "cli.binaryPath";

    /** Auto-reload clean files when they change externally (boolean, default: false) */
    public static final String FILE_AUTO_RELOAD = "file.autoReload";

    /** Prompt to save unsaved changes before closing (boolean, default: true) */
    public static final String FILE_PROMPT_SAVE_ON_EXIT = "file.promptSaveOnExit";

    /** External editor command template (string, default: "code <folder_path> <file_path>") */
    public static final String FILE_EXTERNAL_EDITOR_COMMAND = "file.externalEditorCommand";

    /**
     * Legacy combined terminal command (string, Windows default: cmd.exe "/K" &lt;activation&gt;).
     * Superseded by the per-platform {@code FILE_TERMINAL_ACTIVATION_*} keys; retained as a
     * migration fallback for the Windows activation command. See {@code TerminalLauncher}.
     */
    public static final String FILE_PYTHON_TERMINAL_COMMAND = "file.pythonTerminalCommand";

    /**
     * Terminal activation command run after entering the working directory, per platform
     * (string, default: ""). Typically activates a Python/conda environment; blank = plain shell.
     */
    public static final String FILE_TERMINAL_ACTIVATION_WINDOWS = "file.terminalActivation.windows";
    public static final String FILE_TERMINAL_ACTIVATION_MACOS = "file.terminalActivation.macos";
    public static final String FILE_TERMINAL_ACTIVATION_LINUX = "file.terminalActivation.linux";

    /** macOS terminal application to launch (string, default: "Terminal"; e.g. "iTerm", "Warp", "Ghostty") */
    public static final String FILE_MACOS_TERMINAL_APP = "file.macosTerminalApp";

    /** Enable model linting (boolean, default: true) */
    public static final String LINTER_ENABLED = "linter.enabled";

    /** Custom linter schema file path (string, default: "") */
    public static final String LINTER_SCHEMA_PATH = "linter.schemaPath";

    /** Disabled linter rules (string array, default: empty) */
    public static final String LINTER_DISABLED_RULES = "linter.disabledRules";

    // ==== OS-BASED PREFERENCES (Java Preferences) ====
    // These preferences are machine-specific and handle transient UI state

    /** Last opened file path for session restoration (string, default: "") */
    public static final String LAST_OPENED_FILE = "lastOpenedFile";

    // ==== DEFAULT VALUES ====

    /** Default Python terminal command for Windows */
    public static final String DEFAULT_PYTHON_TERMINAL_COMMAND_WINDOWS = "%windir%\\System32\\cmd.exe \"/K\" %USERPROFILE%\\anaconda3\\Scripts\\activate.bat";

    /** Default macOS terminal application (the built-in Terminal.app) */
    public static final String DEFAULT_MACOS_TERMINAL_APP = "Terminal";

    // Private constructor to prevent instantiation
    private PreferenceKeys() {
        throw new UnsupportedOperationException("This is a constants class");
    }
}