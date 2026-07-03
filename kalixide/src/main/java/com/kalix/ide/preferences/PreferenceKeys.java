package com.kalix.ide.preferences;

import com.kalix.ide.constants.AppConstants;

import java.util.List;

/**
 * Every preference used by the application, as typed {@link Pref} constants.
 *
 * <p>Each constant fixes the key's storage tier, value type, and default in one
 * place — call sites just {@code KEY.get()} / {@code KEY.set(...)}:
 * <ul>
 *   <li><b>File-based</b> ({@code kalix_prefs.json}): portable, human-editable
 *       settings, shareable between users/machines.</li>
 *   <li><b>OS-based</b> (Java Preferences): machine-specific, transient UI
 *       state (panel widths, session restoration, ...).</li>
 * </ul>
 */
public final class PreferenceKeys {

    // ==== FILE-BASED PREFERENCES (kalix_prefs.json) ====
    // Portable settings, shared between users/machines.

    /** FlowViz coordinate display toggle. */
    public static final Pref<Boolean> FLOWVIZ_SHOW_COORDINATES =
        Pref.fileBoolean("flowviz.showCoordinates", false);

    /** FlowViz 64-bit precision toggle for data export. */
    public static final Pref<Boolean> FLOWVIZ_PRECISION64 =
        Pref.fileBoolean("flowviz.precision64", true);

    /** FlowViz Auto-Y mode toggle. */
    public static final Pref<Boolean> FLOWVIZ_AUTO_Y_MODE =
        Pref.fileBoolean("flowviz.autoYMode", true);

    /** Plot legend enabled toggle. */
    public static final Pref<Boolean> PLOT_LEGEND_ENABLED =
        Pref.fileBoolean("plot.legend.enabled", true);

    /** Plot legend collapsed state. */
    public static final Pref<Boolean> PLOT_LEGEND_COLLAPSED =
        Pref.fileBoolean("plot.legend.collapsed", false);

    /** Plot legend X position (-1 = auto-position). */
    public static final Pref<Integer> PLOT_LEGEND_POSITION_X =
        Pref.fileInt("plot.legend.position.x", -1);

    /** Plot legend Y position (-1 = auto-position). */
    public static final Pref<Integer> PLOT_LEGEND_POSITION_Y =
        Pref.fileInt("plot.legend.position.y", -1);

    /** Plot legend display mode ({@code LegendDisplayMode} enum name). */
    public static final Pref<String> PLOT_LEGEND_DISPLAY_MODE =
        Pref.fileString("plot.legend.display.mode", "FULL_NAME");

    /** Log scale auto-zoom minimum value threshold. */
    public static final Pref<Double> PLOT_LOG_SCALE_MIN_THRESHOLD =
        Pref.fileDouble("plot.logScale.minThreshold", 1.0);

    /** Custom plot palettes, one encoded string per user-defined palette. */
    public static final Pref<List<String>> PLOT_PALETTES =
        Pref.fileStringList("plot.palettes", List.of());

    /** Name of the globally active plot palette ({@code PlotPalette.ORIGINAL_NAME}). */
    public static final Pref<String> PLOT_ACTIVE_PALETTE =
        Pref.fileString("plot.activePalette", com.kalix.ide.flowviz.style.PlotPalette.ORIGINAL_NAME);

    /** STDIO format for get_result responses ("pixie" or "csv"). */
    public static final Pref<String> STDIO_DATA_FORMAT =
        Pref.fileString("stdio.dataFormat", "pixie");

    /** Application theme: a stable theme id such as "one-dark".
     *  Read/written via ThemePreferences, which migrates legacy display names. */
    public static final Pref<String> UI_THEME =
        Pref.fileString("ui.theme", "light");

    /** Node theme: a theme id, or "follow" to track the application theme (the default).
     *  Read/written via ThemePreferences, which migrates legacy stored names. */
    public static final Pref<String> UI_NODE_THEME =
        Pref.fileString("ui.nodeTheme", "follow");

    /** Syntax theme: a theme id, or "follow" to track the application theme (the default).
     *  Read/written via ThemePreferences, which migrates legacy enum names. */
    public static final Pref<String> UI_SYNTAX_THEME =
        Pref.fileString("ui.syntaxTheme", "follow");

    /** Editor font size in points. */
    public static final Pref<Integer> EDITOR_FONT_SIZE =
        Pref.fileInt("editor.fontSize", 12);

    /** Map gridlines visibility toggle. */
    public static final Pref<Boolean> MAP_SHOW_GRIDLINES =
        Pref.fileBoolean("map.showGridlines", true);

    /** Whether the project tree shows hidden (dot-prefixed) files/folders. */
    public static final Pref<Boolean> TREE_SHOW_HIDDEN_FILES =
        Pref.fileBoolean("tree.showHiddenFiles", true);

    /** KalixCLI binary search path (";"-delimited directories; "" = system PATH). */
    public static final Pref<String> CLI_BINARY_PATH =
        Pref.fileString("cli.binaryPath", "");

    /** Auto-reload clean files when they change externally. */
    public static final Pref<Boolean> FILE_AUTO_RELOAD =
        Pref.fileBoolean("file.autoReload", false);

    /** Prompt to save unsaved changes before closing. */
    public static final Pref<Boolean> FILE_PROMPT_SAVE_ON_EXIT =
        Pref.fileBoolean("file.promptSaveOnExit", true);

    /** External editor command template. */
    public static final Pref<String> FILE_EXTERNAL_EDITOR_COMMAND =
        Pref.fileString("file.externalEditorCommand", "code <folder_path> <file_path>");

    /** Default Windows value of {@link #FILE_PYTHON_TERMINAL_COMMAND}. */
    public static final String DEFAULT_PYTHON_TERMINAL_COMMAND_WINDOWS =
        "%windir%\\System32\\cmd.exe \"/K\" %USERPROFILE%\\anaconda3\\Scripts\\activate.bat";

    /**
     * Legacy combined terminal command (Windows default: cmd.exe "/K" &lt;activation&gt;).
     * Superseded by the per-platform {@code FILE_TERMINAL_ACTIVATION_*} keys; retained as a
     * migration fallback for the Windows activation command. See {@code TerminalLauncher}.
     */
    public static final Pref<String> FILE_PYTHON_TERMINAL_COMMAND =
        Pref.fileString("file.pythonTerminalCommand", DEFAULT_PYTHON_TERMINAL_COMMAND_WINDOWS);

    /**
     * Terminal activation command run after entering the working directory, per platform.
     * Typically activates a Python/conda environment; blank = plain shell.
     */
    public static final Pref<String> FILE_TERMINAL_ACTIVATION_WINDOWS =
        Pref.fileString("file.terminalActivation.windows", "");
    public static final Pref<String> FILE_TERMINAL_ACTIVATION_MACOS =
        Pref.fileString("file.terminalActivation.macos", "");
    public static final Pref<String> FILE_TERMINAL_ACTIVATION_LINUX =
        Pref.fileString("file.terminalActivation.linux", "");

    /** Default macOS terminal application (the built-in Terminal.app). */
    public static final String DEFAULT_MACOS_TERMINAL_APP = "Terminal";

    /** macOS terminal application to launch (e.g. "iTerm", "Warp", "Ghostty"). */
    public static final Pref<String> FILE_MACOS_TERMINAL_APP =
        Pref.fileString("file.macosTerminalApp", DEFAULT_MACOS_TERMINAL_APP);

    /** Enable model linting. */
    public static final Pref<Boolean> LINTER_ENABLED =
        Pref.fileBoolean("linter.enabled", true);

    /** Custom linter schema file path ("" = built-in schema). */
    public static final Pref<String> LINTER_SCHEMA_PATH =
        Pref.fileString("linter.schemaPath", "");

    /** Disabled linter rules. */
    public static final Pref<List<String>> LINTER_DISABLED_RULES =
        Pref.fileStringList("linter.disabledRules", List.of());

    // ==== OS-BASED PREFERENCES (Java Preferences) ====
    // Machine-specific, transient UI state.

    /** Width in pixels of the project tree region. */
    public static final Pref<Integer> UI_TREE_WIDTH =
        Pref.osInt("ui.treeWidth", AppConstants.DEFAULT_TREE_WIDTH);

    /** Width in pixels of the contextual view (map) region. */
    public static final Pref<Integer> UI_MAP_WIDTH =
        Pref.osInt("ui.mapWidth", AppConstants.DEFAULT_MAP_WIDTH);

    /** Whether the project tree region is collapsed. */
    public static final Pref<Boolean> UI_TREE_COLLAPSED =
        Pref.osBoolean("ui.treeCollapsed", false);

    /** Whether the contextual view (map) region is collapsed. */
    public static final Pref<Boolean> UI_MAP_COLLAPSED =
        Pref.osBoolean("ui.mapCollapsed", false);

    /** Absolute path of the currently open project folder, or empty if none. */
    public static final Pref<String> UI_WORKSPACE_FOLDER =
        Pref.osString("ui.workspaceFolder", "");

    /** Open document tabs as newline-separated {@code caret<TAB>absolutePath} entries. */
    public static final Pref<String> UI_OPEN_DOCUMENTS =
        Pref.osString("ui.openDocuments", "");

    /** Absolute path of the active document tab to restore. */
    public static final Pref<String> UI_ACTIVE_DOCUMENT =
        Pref.osString("ui.activeDocument", "");

    /** Last opened file path for session restoration. */
    public static final Pref<String> LAST_OPENED_FILE =
        Pref.osString("lastOpenedFile", "");

    // Private constructor to prevent instantiation
    private PreferenceKeys() {
        throw new UnsupportedOperationException("This is a constants class");
    }
}
