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

    // ===== Padding and Insets =====
    public static final int PADDING_SMALL = 5;

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

    // ===== Config Editor Dimensions =====
    public static final int CONFIG_TEXT_AREA_ROWS = 20;
    public static final int CONFIG_TEXT_AREA_COLUMNS = 80;
}
