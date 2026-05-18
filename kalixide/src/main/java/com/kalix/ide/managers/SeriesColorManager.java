package com.kalix.ide.managers;

import com.kalix.ide.flowviz.data.SeriesRef;

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

    private final Map<SeriesRef, Color> seriesColorMap = new HashMap<>();

    /**
     * Assigns a color to a series.
     *
     * Algorithm:
     * 1. Finds which color indices are currently in use
     * 2. Assigns the first unused color from the palette
     * 3. If all colors are used (10+ series), wraps around
     */
    public Color assignColor(SeriesRef ref) {
        // Check if already assigned
        if (seriesColorMap.containsKey(ref)) {
            return seriesColorMap.get(ref);
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

        seriesColorMap.put(ref, assignedColor);
        return assignedColor;
    }

    /**
     * Gets the color assigned to a series, or {@code null} if not assigned.
     */
    public Color getColor(SeriesRef ref) {
        return seriesColorMap.get(ref);
    }

    /**
     * Removes color assignment for a series, freeing the slot for reuse.
     */
    public void removeColor(SeriesRef ref) {
        seriesColorMap.remove(ref);
    }

    /**
     * Clears all color assignments.
     */
    public void clearAll() {
        seriesColorMap.clear();
    }

    /**
     * Gets the color map reference for direct access. Returns the live backing map;
     * mutations affect the manager's state.
     */
    public Map<SeriesRef, Color> getColorMap() {
        return seriesColorMap;
    }

}