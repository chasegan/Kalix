package com.kalix.ide.constants;

import java.awt.Color;

/**
 * Centralized UI constants for dimensions, colors, and interaction parameters.
 *
 * This class consolidates magic numbers and UI parameters that were previously
 * scattered throughout the codebase, making them easier to maintain and modify.
 * All constants are grouped by functionality and include documentation explaining
 * their purpose and recommended usage.
 *
 * @author Claude Code Assistant
 * @version 1.0
 */
public final class UIConstants {

    // Prevent instantiation
    private UIConstants() {
        throw new UnsupportedOperationException("Constants class should not be instantiated");
    }

    /**
     * Map panel and node visualization constants
     */
    public static final class Map {
        /** Standard node size in pixels (constant screen size) */
        public static final int NODE_SIZE = 20;

        /** Node radius in pixels (NODE_SIZE / 2 + 1 for slightly larger circles) */
        public static final int NODE_RADIUS = NODE_SIZE / 2 + 1;

        /** Grid size for map background grid lines */
        public static final int GRID_SIZE = 50;

        /** Click tolerance in pixels for node selection vs dragging */
        public static final int CLICK_TOLERANCE = 5;

        /** Minimum rectangle size for meaningful selection */
        public static final int MIN_SELECTION_RECTANGLE_SIZE = 5;

        private Map() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * Zoom and viewport constants
     */
    public static final class Zoom {
        /** Standard zoom factor for zoom in/out operations */
        public static final double ZOOM_FACTOR = 1.2;

        private Zoom() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * Selection visualization constants
     */
    public static final class Selection {
        /** Fill color for selection rectangles (light blue with transparency) */
        public static final Color RECTANGLE_FILL = new Color(0, 120, 255, 50);

        /** Border color for selection rectangles (darker blue) */
        public static final Color RECTANGLE_BORDER = new Color(0, 120, 255, 180);

        /** Dash pattern for selection rectangle borders */
        public static final float[] RECTANGLE_DASH_PATTERN = {5.0f, 3.0f};

        /** Miter limit for dashed selection borders */
        public static final float RECTANGLE_DASH_MITER_LIMIT = 10.0f;

        /** Border color for selected nodes */
        public static final Color NODE_SELECTED_BORDER = Color.BLUE;

        /** Border color for unselected nodes */
        public static final Color NODE_UNSELECTED_BORDER = Color.BLACK;

        /** Border width for selected nodes */
        public static final float NODE_SELECTED_STROKE_WIDTH = 3.0f;

        /** Border width for unselected nodes */
        public static final float NODE_UNSELECTED_STROKE_WIDTH = 1.0f;

        private Selection() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * Text rendering constants
     */
    public static final class Text {
        /** Padding around text backgrounds */
        public static final int BACKGROUND_PADDING = 2;

        /** Default font size for UI elements */
        public static final int DEFAULT_FONT_SIZE = 12;

        /** Margin for debug text from panel edges */
        public static final int DEBUG_MARGIN = 10;

        private Text() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * Theme detection constants
     */
    public static final class Theme {
        /** RGB sum threshold for light/dark theme detection (128 * 3) */
        public static final int LIGHT_THEME_RGB_THRESHOLD = 384;

        /** Default grid color for light themes */
        public static final Color LIGHT_GRID_COLOR = new Color(240, 240, 240);

        /** Default grid color for dark themes */
        public static final Color DARK_GRID_COLOR = new Color(80, 80, 80);

        private Theme() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * Animation and performance constants
     */
    public static final class Performance {
        /** Coordinate display update rate limit (60 FPS) */
        public static final int COORDINATE_UPDATE_RATE_LIMIT_MS = 16; // 1000/60

        /** Throttle interval for frequent operations */
        public static final int THROTTLE_INTERVAL_MS = 50;

        private Performance() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * Layout and spacing constants
     */
    public static final class Layout {
        /** Standard small spacing between elements */
        public static final int SMALL_SPACING = 5;

        /** Standard medium spacing between elements */
        public static final int MEDIUM_SPACING = 10;

        /** Standard large spacing between elements */
        public static final int LARGE_SPACING = 20;

        /** Default panel padding */
        public static final int PANEL_PADDING = 10;

        private Layout() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * FlowViz specific constants
     */
    public static final class FlowViz {
        /** Default split pane divider position for FlowViz windows */
        public static final int DEFAULT_SPLIT_POSITION = 250;

        /** Minimum component size for FlowViz split pane */
        public static final int MIN_COMPONENT_SIZE = 100;

        private FlowViz() { throw new UnsupportedOperationException("Constants class"); }
    }

    /**
     * STDIO Log window constants
     */
    public static final class StdioLog {
        /** Default window width for STDIO Log windows */
        public static final int WINDOW_WIDTH = 800;

        /** Default window height for STDIO Log windows */
        public static final int WINDOW_HEIGHT = 600;

        /** Window position offset from parent window */
        public static final int WINDOW_OFFSET = 100;

        /** Log update interval in milliseconds */
        public static final int UPDATE_INTERVAL_MS = 2000;

        /** Scroll threshold in pixels for auto-scroll detection */
        public static final int SCROLL_THRESHOLD_PIXELS = 10;

        /** Text area rows for JSON edit dialog */
        public static final int DIALOG_ROWS = 10;

        /** Text area columns for JSON edit dialog */
        public static final int DIALOG_COLS = 50;

        /** Font size for log text area */
        public static final int FONT_SIZE = 12;

        /** Message displayed when no log is available */
        public static final String NO_LOG_MESSAGE = "No communication log available for this session.";

        /** Text displayed for unknown session key */
        public static final String UNKNOWN_SESSION = "unknown";

        private StdioLog() { throw new UnsupportedOperationException("Constants class"); }
    }
}