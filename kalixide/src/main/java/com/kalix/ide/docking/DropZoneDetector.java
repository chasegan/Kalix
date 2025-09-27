package com.kalix.ide.docking;

import java.awt.*;

/**
 * Detects which drop zone the cursor is positioned over within a docking area.
 * Supports hybrid docking with tabs (center) and split panes (edges).
 */
public class DropZoneDetector {

    /**
     * Represents the different drop zones within a docking area.
     */
    public enum DropZone {
        CENTER,    // Create tabs
        TOP,       // Split horizontally, new panel on top
        BOTTOM,    // Split horizontally, new panel on bottom
        LEFT,      // Split vertically, new panel on left
        RIGHT,     // Split vertically, new panel on right
        NONE       // Outside valid drop area
    }

    /**
     * The width/height of edge zones as a fraction of the total area.
     * For example, 0.25 means edge zones take up 25% of each side.
     */
    private static final double EDGE_ZONE_FRACTION = 0.25;

    /**
     * Minimum pixel size for edge zones to ensure they're usable.
     */
    private static final int MIN_EDGE_ZONE_SIZE = 40;

    /**
     * Detects which drop zone contains the specified point within the given bounds.
     *
     * @param point The point to test (relative to the bounds)
     * @param bounds The bounds of the docking area
     * @return The drop zone containing the point, or NONE if outside bounds
     */
    public static DropZone detectZone(Point point, Rectangle bounds) {
        if (!bounds.contains(point)) {
            return DropZone.NONE;
        }

        // Calculate edge zone sizes
        int edgeWidth = Math.max(MIN_EDGE_ZONE_SIZE,
                                (int)(bounds.width * EDGE_ZONE_FRACTION));
        int edgeHeight = Math.max(MIN_EDGE_ZONE_SIZE,
                                 (int)(bounds.height * EDGE_ZONE_FRACTION));

        // Convert to local coordinates
        int x = point.x - bounds.x;
        int y = point.y - bounds.y;

        // Check edge zones first (they have priority over center)
        if (x < edgeWidth) {
            return DropZone.LEFT;
        }
        if (x >= bounds.width - edgeWidth) {
            return DropZone.RIGHT;
        }
        if (y < edgeHeight) {
            return DropZone.TOP;
        }
        if (y >= bounds.height - edgeHeight) {
            return DropZone.BOTTOM;
        }

        // Remaining area is center zone
        return DropZone.CENTER;
    }

    /**
     * Gets the bounds of a specific drop zone within the given area bounds.
     *
     * @param zone The zone to get bounds for
     * @param bounds The bounds of the docking area
     * @return The bounds of the specified zone, or null if zone is NONE
     */
    public static Rectangle getZoneBounds(DropZone zone, Rectangle bounds) {
        if (zone == DropZone.NONE) {
            return null;
        }

        // Calculate edge zone sizes
        int edgeWidth = Math.max(MIN_EDGE_ZONE_SIZE,
                                (int)(bounds.width * EDGE_ZONE_FRACTION));
        int edgeHeight = Math.max(MIN_EDGE_ZONE_SIZE,
                                 (int)(bounds.height * EDGE_ZONE_FRACTION));

        switch (zone) {
            case LEFT:
                return new Rectangle(bounds.x, bounds.y, edgeWidth, bounds.height);
            case RIGHT:
                return new Rectangle(bounds.x + bounds.width - edgeWidth, bounds.y,
                                   edgeWidth, bounds.height);
            case TOP:
                return new Rectangle(bounds.x, bounds.y, bounds.width, edgeHeight);
            case BOTTOM:
                return new Rectangle(bounds.x, bounds.y + bounds.height - edgeHeight,
                                   bounds.width, edgeHeight);
            case CENTER:
                return new Rectangle(bounds.x + edgeWidth, bounds.y + edgeHeight,
                                   bounds.width - 2 * edgeWidth,
                                   bounds.height - 2 * edgeHeight);
            default:
                return null;
        }
    }

    /**
     * Returns a descriptive name for the drop zone.
     */
    public static String getZoneDescription(DropZone zone) {
        switch (zone) {
            case CENTER: return "Tab here";
            case TOP: return "Split top";
            case BOTTOM: return "Split bottom";
            case LEFT: return "Split left";
            case RIGHT: return "Split right";
            default: return "";
        }
    }
}