package com.kalix.ide.interaction;

import java.util.List;

/**
 * Represents clipboard data for cut/copy operations on map nodes.
 * Stores node section text along with metadata for paste operations.
 *
 * @param nodeSections Ordered list of node sections (by original text position)
 * @param isCut true for cut operation, false for copy
 * @param anchorX X coordinate of anchor node (first by text order) for translation
 * @param anchorY Y coordinate of anchor node (first by text order) for translation
 */
public record ClipboardEntry(
    List<NodeSectionData> nodeSections,
    boolean isCut,
    double anchorX,
    double anchorY
) {
    /**
     * Represents a single node section extracted from the INI text.
     *
     * @param originalName Original node name (without any suffix)
     * @param sectionText Full text of the [node.X] section including all properties
     * @param x X coordinate from loc property
     * @param y Y coordinate from loc property
     * @param textOrder Original order in the text file (0-based)
     */
    public record NodeSectionData(
        String originalName,
        String sectionText,
        double x,
        double y,
        int textOrder
    ) {}
}
