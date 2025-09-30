package com.kalix.ide.constants;

import com.kalix.ide.managers.KeyboardShortcutManager;
import java.awt.*;

/**
 * Constants used throughout the Kalix IDE application.
 * Centralizes all magic numbers, strings, and configuration values.
 */
public final class AppConstants {
    
    // Prevent instantiation
    private AppConstants() {
        throw new UnsupportedOperationException("Constants class should not be instantiated");
    }
    
    // Application metadata
    public static final String APP_NAME = "Kalix";
    public static final String APP_VERSION = "1.0";
    public static final String APP_DESCRIPTION = "A Java Swing interface for Kalix hydrologic models.";
    public static final String APP_WEBSITE_URL = "https://www.notion.so/chasegan/Kalix-development-762687200b564e8e8c82b4f98879974f?pvs=12";
    
    // Window properties
    public static final int DEFAULT_WINDOW_WIDTH = 1200;
    public static final int DEFAULT_WINDOW_HEIGHT = 800;
    public static final int DEFAULT_SPLIT_PANE_DIVIDER_LOCATION = 600;
    public static final double DEFAULT_SPLIT_PANE_RESIZE_WEIGHT = 0.5;
    
    // Recent files (Note: MAX_RECENT_FILES also defined in PreferenceKeys - should be unified)
    public static final int MAX_RECENT_FILES = 10; // Unified with PreferenceKeys value
    public static final String RECENT_FILE_PREF_PREFIX = "recentFile";
    
    // File types
    public static final String INI_EXTENSION = ".ini";
    public static final String[] SUPPORTED_MODEL_EXTENSIONS = {INI_EXTENSION};

    // File dialog descriptions
    public static final String MODEL_FILES_DESCRIPTION = "Kalix Model Files (*.ini)";
    public static final String INI_FILES_DESCRIPTION = "INI Files (*.ini)";
    
    
    // Themes
    public static final String[] AVAILABLE_THEMES = {
        "Light", "Keylime", "Lapland", "Nemo", "Botanical", "Dracula", "One Dark", "Obsidian", "Sanne"
    };
    public static final String DEFAULT_THEME = "Light";
    public static final String DEFAULT_NODE_THEME = "Vibrant";
    
    // Preferences keys (DEPRECATED - use PreferenceKeys class for better organization)
    // TODO: Remove these constants and use PreferenceKeys class throughout codebase
    @Deprecated public static final String PREF_THEME = "theme";
    @Deprecated public static final String PREF_NODE_THEME = "node.theme";
    @Deprecated public static final String PREF_EDITOR_THEME = "editor.theme";
    public static final String PREF_SPLIT_PANE_DIVIDER = "ui.splitpane.divider.location";
    @Deprecated public static final String PREF_SHOW_GRIDLINES = "map.show.gridlines";
    @Deprecated public static final String PREF_FLOWVIZ_SHOW_COORDINATES = "flowviz.show.coordinates";
    
    
    // Status messages
    public static final String STATUS_READY = "Ready";
    public static final String STATUS_NEW_MODEL_CREATED = "New model created";
    public static final String STATUS_UNDO = "Undo";
    public static final String STATUS_REDO = "Redo";
    public static final String STATUS_NOTHING_TO_UNDO = "Nothing to undo";
    public static final String STATUS_NOTHING_TO_REDO = "Nothing to redo";
    public static final String STATUS_CUT = "Cut";
    public static final String STATUS_COPY = "Copy";
    public static final String STATUS_PASTE = "Paste";
    public static final String STATUS_ZOOMED_IN = "Zoomed in";
    public static final String STATUS_ZOOMED_OUT = "Zoomed out";
    public static final String STATUS_ZOOM_RESET = "Zoom reset";
    public static final String STATUS_FLOWVIZ_OPENED = "FlowViz window opened";
    public static final String STATUS_RECENT_FILES_CLEARED = "Recent files cleared";
    public static final String STATUS_INVALID_DROP_FILES = "Dropped files do not contain valid Kalix model files (.ini)";
    
    // Error messages
    public static final String ERROR_FILE_OPEN = "File Open Error";
    public static final String ERROR_FILE_NOT_FOUND_TITLE = "File Not Found";
    public static final String ERROR_PROCESSING_DROPPED_FILE = "Error processing dropped file: ";
    public static final String ERROR_OPENING_FILE = "Error opening file: ";
    public static final String ERROR_FAILED_TO_OPEN = "Failed to open file: ";
    public static final String ERROR_FILE_NOT_EXISTS = "File no longer exists:\n";
    public static final String ERROR_FAILED_LOOK_AND_FEEL = "Failed to set look and feel: ";
    public static final String ERROR_FAILED_FLATLAF_INIT = "Failed to initialize FlatLaf: ";
    
    // Menu text
    public static final String MENU_NO_RECENT_FILES = "No recent files";
    public static final String MENU_CLEAR_RECENT_FILES = "Clear Recent Files";
    
    // Default text content
    public static final String DEFAULT_MODEL_TEXT = "# Welcome, friend ...\n";

    // System properties for macOS
    public static final String PROP_MACOS_SCREEN_MENU = "apple.laf.useScreenMenuBar";
    public static final String PROP_MACOS_APP_NAME = "apple.awt.application.name";
    public static final String PROP_FLATLAF_WINDOW_DECORATIONS = "flatlaf.useWindowDecorations";
    public static final String PROP_FLATLAF_MENU_EMBEDDED = "flatlaf.menuBarEmbedded";
    
    // Layout margins and spacing
    public static final Insets DEFAULT_INSETS = new Insets(10, 10, 10, 10);
    public static final int STATUS_LABEL_BORDER_V = 5;
    public static final int STATUS_LABEL_BORDER_H = 10;
    
    // Toolbar properties
    public static final int TOOLBAR_ICON_SIZE = 16;
    public static String getToolbarNewTooltip() { return "New Model (" + KeyboardShortcutManager.getInstance().getShortcutString("N") + ")"; }
    public static String getToolbarOpenTooltip() { return "Open Model (" + KeyboardShortcutManager.getInstance().getShortcutString("O") + ")"; }
    public static String getToolbarSaveTooltip() { return "Save Model (" + KeyboardShortcutManager.getInstance().getShortcutString("S") + ")"; }
    public static String getToolbarSearchTooltip() { return "Search in Model (" + KeyboardShortcutManager.getInstance().getShortcutString("F") + ")"; }
    public static final String TOOLBAR_FLOWVIZ_TOOLTIP = "Open FlowViz Window";
    public static final String TOOLBAR_VERSION_TOOLTIP = "Check Kalix CLI Version";
    public static String getToolbarRunModelTooltip() { return "Run Model (" + KeyboardShortcutManager.getInstance().getShortcutString("R") + ")"; }
    public static final String TOOLBAR_SESSIONS_TOOLTIP = "Show Run Manager";
    
    // Branding
    public static final String KALIX_LOGO_PATH = "/images/kalix_landscape_1024_241.png";
    public static final String KALIX_LOGO_DARK_PATH = "/images/kalix_landscape_1024_241_dark.png";
    public static final String TOOLBAR_LOGO_TOOLTIP = "Visit Kalix Development Website";
    public static final int TOOLBAR_LOGO_HEIGHT = 24; // Scaled height for toolbar
    
    // Status messages for new actions
    public static final String STATUS_MODEL_RUNNING = "Running model...";
    public static final String STATUS_MODEL_RUN_COMPLETE = "Model run completed";
    public static final String STATUS_MODEL_RUN_ERROR = "Model run failed";
    public static final String STATUS_SEARCH_OPENED = "Search dialog opened";
    public static final String STATUS_SEARCH_NOT_IMPLEMENTED = "Search - Not yet implemented";
}