package com.kalix.ide.managers;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages color assignment for time series in plots.
 * Provides a categorical color palette optimized for visibility and distinction.
 *
 * Features:
 * - Assigns colors sequentially from a predefined palette
 * - Reuses colors when series are removed (fills gaps)
 * - Wraps around when all colors are in use (10+ series)
 */
public class SeriesColorManager {

    // Categorical 10 color palette - optimized for visibility and distinction
    private static final Color[] SERIES_COLORS = {
        new Color(0x1f77b4),  // Blue
        new Color(0xff7f0e),  // Orange
        new Color(0x2ca02c),  // Green
        new Color(0xd62728),  // Red
        new Color(0x9467bd),  // Purple
        new Color(0x8c564b),  // Brown
        new Color(0xe377c2),  // Pink
        new Color(0x7f7f7f),  // Gray
        new Color(0xbcbd22),  // Yellow-green
        new Color(0x17becf)   // Cyan
    };

    private final Map<String, Color> seriesColorMap = new HashMap<>();

    /**
     * Assigns a color to a series.
     *
     * Algorithm:
     * 1. Finds which color indices are currently in use
     * 2. Assigns the first unused color from the palette
     * 3. If all colors are used (10+ series), wraps around
     *
     * @param seriesName The series to assign a color to
     * @return The assigned color
     */
    public Color assignColor(String seriesName) {
        // Check if already assigned
        if (seriesColorMap.containsKey(seriesName)) {
            return seriesColorMap.get(seriesName);
        }

        // Find which color indices are currently in use
        Set<Integer> usedIndices = new HashSet<>();
        for (Color color : seriesColorMap.values()) {
            for (int i = 0; i < SERIES_COLORS.length; i++) {
                if (SERIES_COLORS[i].equals(color)) {
                    usedIndices.add(i);
                    break;
                }
            }
        }

        // Find first available color index
        Color assignedColor = null;
        for (int i = 0; i < SERIES_COLORS.length; i++) {
            if (!usedIndices.contains(i)) {
                assignedColor = SERIES_COLORS[i];
                break;
            }
        }

        // All colors used (10+ series), wrap around
        if (assignedColor == null) {
            assignedColor = SERIES_COLORS[seriesColorMap.size() % SERIES_COLORS.length];
        }

        seriesColorMap.put(seriesName, assignedColor);
        return assignedColor;
    }

    /**
     * Gets the color assigned to a series.
     *
     * @param seriesName The series name
     * @return The assigned color, or null if not assigned
     */
    public Color getColor(String seriesName) {
        return seriesColorMap.get(seriesName);
    }

    /**
     * Removes color assignment for a series.
     * This makes the color available for reuse by future series.
     *
     * @param seriesName The series to remove
     */
    public void removeColor(String seriesName) {
        seriesColorMap.remove(seriesName);
    }

    /**
     * Clears all color assignments.
     */
    public void clearAll() {
        seriesColorMap.clear();
    }

    /**
     * Gets the color map reference for direct access.
     * Note: This returns the actual map, not a copy.
     * Changes to this map will affect the SeriesColorManager's state.
     *
     * @return The actual color map reference
     */
    public Map<String, Color> getColorMap() {
        return seriesColorMap;
    }

}