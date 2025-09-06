package com.kalix.gui.constants;

import java.awt.*;

/**
 * Constants used throughout the Kalix GUI application.
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
    
    // Window properties
    public static final int DEFAULT_WINDOW_WIDTH = 1200;
    public static final int DEFAULT_WINDOW_HEIGHT = 800;
    public static final int DEFAULT_SPLIT_PANE_DIVIDER_LOCATION = 600;
    public static final double DEFAULT_SPLIT_PANE_RESIZE_WEIGHT = 0.5;
    
    // Recent files
    public static final int MAX_RECENT_FILES = 5;
    public static final String RECENT_FILE_PREF_PREFIX = "recentFile";
    
    // File types
    public static final String INI_EXTENSION = ".ini";
    public static final String TOML_EXTENSION = ".toml";
    public static final String[] SUPPORTED_MODEL_EXTENSIONS = {INI_EXTENSION, TOML_EXTENSION};
    
    // File dialog descriptions
    public static final String MODEL_FILES_DESCRIPTION = "Kalix Model Files (*.ini, *.toml)";
    public static final String INI_FILES_DESCRIPTION = "INI Files (*.ini)";
    public static final String TOML_FILES_DESCRIPTION = "TOML Files (*.toml)";
    
    // Font dialog
    public static final String[] MONOSPACE_FONTS = {
        "JetBrains Mono",
        "Fira Code", 
        "Consolas",
        "Courier New",
        "Monaco",
        "Menlo",
        "DejaVu Sans Mono",
        "Liberation Mono",
        "Source Code Pro",
        "Ubuntu Mono"
    };
    
    public static final Integer[] FONT_SIZES = {
        8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 36, 48
    };
    
    public static final int FONT_DIALOG_WIDTH = 400;
    public static final int FONT_DIALOG_HEIGHT = 300;
    public static final String FONT_PREVIEW_TEXT = "Sample text:\n[Section]\nname = value\n# Comment";
    
    // Themes
    public static final String[] AVAILABLE_THEMES = {
        "Light", "Dark", "Dracula", "One Dark", "Carbon"
    };
    public static final String DEFAULT_THEME = "Light";
    
    // Preferences keys
    public static final String PREF_THEME = "theme";
    public static final String PREF_FONT_NAME = "editor.font.name";
    public static final String PREF_FONT_SIZE = "editor.font.size";
    public static final String PREF_LINE_WRAP = "editor.line.wrap";
    public static final String PREF_EDITOR_THEME = "editor.theme";
    
    // Default values
    public static final String DEFAULT_FONT_NAME = "Consolas";
    public static final int DEFAULT_FONT_SIZE = 12;
    
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
    public static final String STATUS_SPLASH_DISPLAYED = "Splash screen displayed";
    public static final String STATUS_RECENT_FILES_CLEARED = "Recent files cleared";
    public static final String STATUS_INVALID_DROP_FILES = "Dropped files do not contain valid Kalix model files (.ini or .toml)";
    
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
    public static final String DEFAULT_MODEL_TEXT = "# Kalix Model\n# Edit your hydrologic model here...\n";
    public static final String NEW_MODEL_TEXT = "# New Kalix Model\n";
    
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
    public static final String TOOLBAR_NEW_TOOLTIP = "New Model (Ctrl+N)";
    public static final String TOOLBAR_OPEN_TOOLTIP = "Open Model (Ctrl+O)";
    public static final String TOOLBAR_SAVE_TOOLTIP = "Save Model (Ctrl+S)";
    public static final String TOOLBAR_RUN_TOOLTIP = "Run Model (F5)";
    public static final String TOOLBAR_SEARCH_TOOLTIP = "Search in Model (Ctrl+F)";
    public static final String TOOLBAR_FLOWVIZ_TOOLTIP = "Open FlowViz Window";
    public static final String TOOLBAR_VERSION_TOOLTIP = "Check Kalix CLI Version";
    
    // Status messages for new actions
    public static final String STATUS_MODEL_RUNNING = "Running model...";
    public static final String STATUS_MODEL_RUN_COMPLETE = "Model run completed";
    public static final String STATUS_MODEL_RUN_ERROR = "Model run failed";
    public static final String STATUS_SEARCH_OPENED = "Search dialog opened";
    public static final String STATUS_SEARCH_NOT_IMPLEMENTED = "Search - Not yet implemented";
}