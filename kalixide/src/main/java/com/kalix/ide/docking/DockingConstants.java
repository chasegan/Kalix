package com.kalix.ide.docking;

import javax.swing.UIManager;
import java.awt.*;

/**
 * Central repository for all docking system constants including colors, sizes, and timing values.
 * Organized into nested classes for better structure and discoverability.
 */
public final class DockingConstants {

    // Prevent instantiation
    private DockingConstants() {}

    /**
     * Dynamic color system for the docking components.
     * Colors are generated based on accent and background colors.
     */
    public static final class Colors {
        // Default colors (blue theme on light background)
        private static Color accentColor = new Color(0, 123, 255);
        private static Color backgroundColor = Color.WHITE;

        // Cached derived colors
        private static Color highlight;
        private static Color grip;
        private static Color gripDots;
        private static Color dropZoneHighlight;
        private static Color dropZoneBorder;
        private static Color dragPreview;
        private static Color dragPreviewBorder;
        private static Color dotShadow;
        private static Color dotHighlight;
        private static Color placeholderFallback;

        static {
            updateColors();
        }

        /**
         * Sets the theme colors and updates all derived colors.
         *
         * @param accent The accent color for highlights and borders
         * @param background The background color (determines light/dark mode)
         */
        public static void setTheme(Color accent, Color background) {
            accentColor = accent;
            backgroundColor = background;
            updateColors();
        }

        /**
         * Updates theme colors from standard UIManager properties.
         * Uses Panel.background for background color and Component.focusColor for accent.
         * Falls back to reasonable defaults if properties are not set.
         */
        public static void updateFromUIManager() {
            // Get background color from standard Panel.background
            Color uiBackground = UIManager.getColor("Panel.background");
            if (uiBackground == null) {
                uiBackground = UIManager.getColor("control"); // Fallback
            }
            if (uiBackground == null) {
                uiBackground = Color.LIGHT_GRAY; // Final fallback
            }

            // Get accent color from Component.focusColor or selection colors
            Color uiAccent = UIManager.getColor("Component.focusColor");
            if (uiAccent == null) {
                uiAccent = UIManager.getColor("textHighlight"); // Fallback
            }
            if (uiAccent == null) {
                uiAccent = UIManager.getColor("List.selectionBackground"); // Another fallback
            }
            if (uiAccent == null) {
                uiAccent = new Color(0, 123, 255); // Final fallback to blue
            }

            setTheme(uiAccent, uiBackground);
        }

        /**
         * Updates all derived colors based on current accent and background colors.
         */
        private static void updateColors() {
            // Create darker variant of accent (75% brightness)
            Color darkerAccent = darken(accentColor, 0.75f);

            // Detect if background is light or dark
            boolean isLightMode = isLight(backgroundColor);

            // Generate colors with appropriate transparencies
            highlight = withAlpha(accentColor, 60);
            grip = withAlpha(darkerAccent, 180);
            gripDots = withAlpha(accentColor, 120);
            dropZoneHighlight = withAlpha(accentColor, 80);
            dropZoneBorder = darkerAccent;
            dragPreview = withAlpha(accentColor, 150);
            dragPreviewBorder = darkerAccent;

            // Shadow/highlight colors based on light/dark mode
            if (isLightMode) {
                dotShadow = new Color(0, 0, 0, 100);
                dotHighlight = new Color(255, 255, 255, 80);
                placeholderFallback = new Color(120, 120, 120);
            } else {
                dotShadow = new Color(0, 0, 0, 150);
                dotHighlight = new Color(255, 255, 255, 120);
                placeholderFallback = new Color(180, 180, 180);
            }
        }

        /**
         * Determines if a color appears light (brightness > 128).
         */
        private static boolean isLight(Color color) {
            int brightness = (int)(0.299 * color.getRed() +
                                 0.587 * color.getGreen() +
                                 0.114 * color.getBlue());
            return brightness > 128;
        }

        /**
         * Creates a darker version of a color by scaling RGB values.
         */
        private static Color darken(Color color, float factor) {
            return new Color(
                Math.max(0, (int)(color.getRed() * factor)),
                Math.max(0, (int)(color.getGreen() * factor)),
                Math.max(0, (int)(color.getBlue() * factor))
            );
        }

        /**
         * Creates a new color with the specified alpha transparency.
         */
        private static Color withAlpha(Color color, int alpha) {
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
        }

        // Public getters for the derived colors (these always return current theme colors)
        public static Color getHighlight() { return highlight; }
        public static Color getGrip() { return grip; }
        public static Color getGripDots() { return gripDots; }
        public static Color getDropZoneHighlight() { return dropZoneHighlight; }
        public static Color getDropZoneBorder() { return dropZoneBorder; }
        public static Color getDragPreview() { return dragPreview; }
        public static Color getDragPreviewBorder() { return dragPreviewBorder; }
        public static Color getDotShadow() { return dotShadow; }
        public static Color getDotHighlight() { return dotHighlight; }
        public static Color getPlaceholderFallback() { return placeholderFallback; }

        // Constants that always return current dynamic colors
        public static final Color HIGHLIGHT = new Color(0, 0, 0) {
            @Override public int getRed() { return highlight.getRed(); }
            @Override public int getGreen() { return highlight.getGreen(); }
            @Override public int getBlue() { return highlight.getBlue(); }
            @Override public int getAlpha() { return highlight.getAlpha(); }
            @Override public int getRGB() { return highlight.getRGB(); }
        };

        public static final Color GRIP = new Color(0, 0, 0) {
            @Override public int getRed() { return grip.getRed(); }
            @Override public int getGreen() { return grip.getGreen(); }
            @Override public int getBlue() { return grip.getBlue(); }
            @Override public int getAlpha() { return grip.getAlpha(); }
            @Override public int getRGB() { return grip.getRGB(); }
        };

        public static final Color GRIP_DOTS = new Color(0, 0, 0) {
            @Override public int getRed() { return gripDots.getRed(); }
            @Override public int getGreen() { return gripDots.getGreen(); }
            @Override public int getBlue() { return gripDots.getBlue(); }
            @Override public int getAlpha() { return gripDots.getAlpha(); }
            @Override public int getRGB() { return gripDots.getRGB(); }
        };

        public static final Color DROP_ZONE_HIGHLIGHT = new Color(0, 0, 0) {
            @Override public int getRed() { return dropZoneHighlight.getRed(); }
            @Override public int getGreen() { return dropZoneHighlight.getGreen(); }
            @Override public int getBlue() { return dropZoneHighlight.getBlue(); }
            @Override public int getAlpha() { return dropZoneHighlight.getAlpha(); }
            @Override public int getRGB() { return dropZoneHighlight.getRGB(); }
        };

        public static final Color DROP_ZONE_BORDER = new Color(0, 0, 0) {
            @Override public int getRed() { return dropZoneBorder.getRed(); }
            @Override public int getGreen() { return dropZoneBorder.getGreen(); }
            @Override public int getBlue() { return dropZoneBorder.getBlue(); }
            @Override public int getAlpha() { return dropZoneBorder.getAlpha(); }
            @Override public int getRGB() { return dropZoneBorder.getRGB(); }
        };

        public static final Color DRAG_PREVIEW = new Color(0, 0, 0) {
            @Override public int getRed() { return dragPreview.getRed(); }
            @Override public int getGreen() { return dragPreview.getGreen(); }
            @Override public int getBlue() { return dragPreview.getBlue(); }
            @Override public int getAlpha() { return dragPreview.getAlpha(); }
            @Override public int getRGB() { return dragPreview.getRGB(); }
        };

        public static final Color DRAG_PREVIEW_BORDER = new Color(0, 0, 0) {
            @Override public int getRed() { return dragPreviewBorder.getRed(); }
            @Override public int getGreen() { return dragPreviewBorder.getGreen(); }
            @Override public int getBlue() { return dragPreviewBorder.getBlue(); }
            @Override public int getAlpha() { return dragPreviewBorder.getAlpha(); }
            @Override public int getRGB() { return dragPreviewBorder.getRGB(); }
        };

        public static final Color DOT_SHADOW = new Color(0, 0, 0) {
            @Override public int getRed() { return dotShadow.getRed(); }
            @Override public int getGreen() { return dotShadow.getGreen(); }
            @Override public int getBlue() { return dotShadow.getBlue(); }
            @Override public int getAlpha() { return dotShadow.getAlpha(); }
            @Override public int getRGB() { return dotShadow.getRGB(); }
        };

        public static final Color DOT_HIGHLIGHT = new Color(0, 0, 0) {
            @Override public int getRed() { return dotHighlight.getRed(); }
            @Override public int getGreen() { return dotHighlight.getGreen(); }
            @Override public int getBlue() { return dotHighlight.getBlue(); }
            @Override public int getAlpha() { return dotHighlight.getAlpha(); }
            @Override public int getRGB() { return dotHighlight.getRGB(); }
        };

        public static final Color PLACEHOLDER_FALLBACK = new Color(0, 0, 0) {
            @Override public int getRed() { return placeholderFallback.getRed(); }
            @Override public int getGreen() { return placeholderFallback.getGreen(); }
            @Override public int getBlue() { return placeholderFallback.getBlue(); }
            @Override public int getAlpha() { return placeholderFallback.getAlpha(); }
            @Override public int getRGB() { return placeholderFallback.getRGB(); }
        };
    }

    /**
     * Size and dimension constants for docking components.
     */
    public static final class Dimensions {
        /** Width of the draggable grip */
        public static final int GRIP_WIDTH = 30;

        /** Height of the draggable grip */
        public static final int GRIP_HEIGHT = 20;

        /** Margin around the grip from panel edges */
        public static final int GRIP_MARGIN = 5;

        /** Size of individual dots in the grip pattern */
        public static final int DOT_SIZE = 3;

        /** Spacing between dots in the grip pattern */
        public static final int DOT_SPACING = 5;

        /** Border width for highlights */
        public static final float HIGHLIGHT_BORDER_WIDTH = 3.0f;

        /** Border width for drop zone highlighting */
        public static final float DROP_ZONE_BORDER_WIDTH = 3.0f;

        /** Drag preview border width */
        public static final int DRAG_PREVIEW_BORDER_WIDTH = 2;

        /** Corner radius for rounded grip */
        public static final int GRIP_CORNER_RADIUS = 6;

        /** Default window padding for floating windows */
        public static final int WINDOW_PADDING_WIDTH = 20;

        /** Default window padding height for title bar and borders */
        public static final int WINDOW_PADDING_HEIGHT = 40;

        /** Default width for new docking windows */
        public static final int DEFAULT_WINDOW_WIDTH = 400;

        /** Default height for new docking windows */
        public static final int DEFAULT_WINDOW_HEIGHT = 300;

        /** Font size for placeholder text */
        public static final float PLACEHOLDER_FONT_SIZE = 12f;
    }

    /**
     * Timing constants for animations and polling.
     */
    public static final class Timing {
        /** Mouse state polling interval in milliseconds (60 FPS) */
        public static final int MOUSE_POLLING_INTERVAL = 16;

        /** Delay for mouse event settling in milliseconds */
        public static final int MOUSE_SETTLE_DELAY = 50;

        /** Click detection tolerance in pixels */
        public static final int CLICK_TOLERANCE = 5;

        /** Drag preview opacity (0.0 to 1.0) */
        public static final float DRAG_PREVIEW_OPACITY = 0.5f;
    }

    /**
     * Text constants for UI elements.
     */
    public static final class Text {
        /** Quote displayed in empty docking areas */
        public static final String EMPTY_AREA_QUOTE = "The void gazes back.";

        /** Default drag preview label */
        public static final String DRAG_PREVIEW_LABEL = "Docking Panel";

        /** Drop hint message */
        public static final String DROP_HINT = "Drop panel here";

        /** Default window title prefix */
        public static final String WINDOW_TITLE_PREFIX = "Docking Window";

        /** Empty window title */
        public static final String EMPTY_WINDOW_TITLE = "Empty Docking Window";

        /** Floating window title prefix */
        public static final String FLOATING_WINDOW_PREFIX = "Floating Window";
    }

    /**
     * Layout and positioning constants.
     */
    public static final class Layout {
        /** Default docking area name */
        public static final String DEFAULT_AREA_NAME = "Docking Area";

        /** Main area identifier */
        public static final String MAIN_AREA_NAME = "Main";

        /** Empty border size for docking areas */
        public static final int AREA_BORDER_SIZE = 5;

        /** Drop zone dash pattern */
        public static final float[] DROP_ZONE_DASH_PATTERN = {10, 5};
    }
}