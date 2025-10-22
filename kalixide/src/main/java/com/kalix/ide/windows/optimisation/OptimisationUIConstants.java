package com.kalix.ide.windows.optimisation;

import java.awt.*;

/**
 * UI constants for the Optimisation window and related components.
 * Centralizes magic numbers for easier maintenance and consistency.
 */
public final class OptimisationUIConstants {

    // Prevent instantiation
    private OptimisationUIConstants() {
        throw new UnsupportedOperationException("Constants class should not be instantiated");
    }

    // ===== Window Dimensions =====
    public static final int WINDOW_WIDTH = 900;
    public static final int WINDOW_HEIGHT = 700;
    public static final int WINDOW_OFFSET_X = 30;
    public static final int WINDOW_OFFSET_Y = 30;

    // ===== Tree Panel =====
    public static final int TREE_PANEL_WIDTH = 220;
    public static final int TREE_ICON_SIZE = 16;

    // ===== Text Areas =====
    public static final int TEXT_AREA_ROWS = 20;
    public static final int TEXT_AREA_COLUMNS = 60;
    public static final int TEXT_FIELD_SMALL = 10;
    public static final int TEXT_FIELD_MEDIUM = 20;

    // ===== Table Dimensions =====
    public static final int TABLE_ROW_HEIGHT = 25;
    public static final int TABLE_PREFERRED_WIDTH = 250;
    public static final int TABLE_PREFERRED_HEIGHT = 120;

    // ===== Padding and Insets =====
    public static final int PADDING_SMALL = 5;
    public static final int PADDING_MEDIUM = 10;
    public static final int CONFIG_STATUS_LABEL_TOP_PADDING = 4;

    // ===== Status Colors =====
    /** Dark green for successful/completed optimisations */
    public static final Color STATUS_COLOR_SUCCESS = new Color(0, 120, 0);

    /** Blue for running optimisations */
    public static final Color STATUS_COLOR_RUNNING = new Color(0, 0, 200);

    /** Red for failed optimisations */
    public static final Color STATUS_COLOR_ERROR = new Color(200, 0, 0);

    /** Dark yellow for starting/loading optimisations */
    public static final Color STATUS_COLOR_LOADING = new Color(150, 150, 0);

    /** Gray for stopped optimisations */
    public static final Color STATUS_COLOR_STOPPED = Color.GRAY;

    // ===== Grid Colors =====
    /** Light gray grid lines for tables */
    public static final Color GRID_COLOR = new Color(220, 220, 220);

    // ===== Icon Colors =====
    /** Green for "New" button icon */
    public static final Color ICON_COLOR_NEW = new Color(0, 120, 0);

    // ===== Card Layout Names =====
    public static final String CARD_MESSAGE = "MESSAGE";
    public static final String CARD_OPTIMISATION = "OPTIMISATION";

    // ===== Tab Names =====
    public static final String TAB_CONFIG = "Config";
    public static final String TAB_CONFIG_INI = "Config INI";
    public static final String TAB_RESULTS = "Results";

    // ===== Config Status Labels =====
    public static final String CONFIG_STATUS_ORIGINAL = "Original";
    public static final String CONFIG_STATUS_MODIFIED = "Modified";
}
