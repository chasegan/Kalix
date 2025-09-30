package com.kalix.ide.preferences;

import java.util.Arrays;
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

    /** UI theme selection (string, default: "Light") */
    public static final String UI_THEME = "ui.theme";

    /** Last directory used for file operations (string, default: "./") */
    public static final String DATA_LAST_DIRECTORY = "data.lastDirectory";

    /** List of recently opened files (string list, default: empty) */
    public static final String DATA_RECENT_FILES = "data.recentFiles";

    /** Node theme selection (string, default: "VIBRANT") */
    public static final String UI_NODE_THEME = "ui.nodeTheme";

    /** Map gridlines visibility toggle (boolean, default: true) */
    public static final String MAP_SHOW_GRIDLINES = "map.showGridlines";

    /** KalixCLI binary path (string, default: "") */
    public static final String CLI_BINARY_PATH = "cli.binaryPath";

    /** Auto-reload clean files when they change externally (boolean, default: false) */
    public static final String FILE_AUTO_RELOAD = "file.autoReload";

    /** External editor command template (string, default: "code <folder_path> <file_path>") */
    public static final String FILE_EXTERNAL_EDITOR_COMMAND = "file.externalEditorCommand";

    /** Enable model linting (boolean, default: true) */
    public static final String LINTER_ENABLED = "linter.enabled";

    /** Custom linter schema file path (string, default: "") */
    public static final String LINTER_SCHEMA_PATH = "linter.schemaPath";

    /** Disabled linter rules (string array, default: empty) */
    public static final String LINTER_DISABLED_RULES = "linter.disabledRules";

    // ==== OS-BASED PREFERENCES (Java Preferences) ====
    // These preferences are machine-specific and handle transient UI state

    /** Main window width (int, default: 1000) */
    public static final String WINDOW_WIDTH = "window.width";

    /** Main window height (int, default: 700) */
    public static final String WINDOW_HEIGHT = "window.height";

    /** Main window X position (int, default: center screen) */
    public static final String WINDOW_X = "window.x";

    /** Main window Y position (int, default: center screen) */
    public static final String WINDOW_Y = "window.y";

    /** Main window maximized state (boolean, default: false) */
    public static final String WINDOW_MAXIMIZED = "window.maximized";

    /** FlowViz split pane divider position (int, default: 250) */
    public static final String FLOWVIZ_SPLIT_PANE_POSITION = "flowviz.splitPanePosition";

    // ==== DEFAULT VALUES ====

    /** Default recent files list */
    public static final List<String> DEFAULT_RECENT_FILES = Arrays.asList();

    /** Maximum number of recent files to track - unified with AppConstants.Files.MAX_RECENT_FILES */
    public static final int MAX_RECENT_FILES = 10;

    // Private constructor to prevent instantiation
    private PreferenceKeys() {
        throw new UnsupportedOperationException("This is a constants class");
    }
}