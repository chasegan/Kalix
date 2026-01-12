package com.kalix.ide.tableview;

/**
 * Utility class for calculating pyramidal storage dimensions.
 * Generates Level-Volume-Area-Spill (LVAS) tables based on full supply volume and area.
 */
public final class PyramidalDimensionsCalculator {

    /** Large volume value for the overflow row */
    private static final double BIG_VOL = 1e9;

    /** Large spill value for overflow conditions */
    private static final double BIG_SPILL = 1e9;

    /** Default number of rows */
    public static final int DEFAULT_NUM_ROWS = 7;

    // Prevent instantiation
    private PyramidalDimensionsCalculator() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Generates pyramidal storage dimension rows.
     *
     * @param fsVolume Full supply volume [ML]
     * @param fsArea   Full supply area [km²]
     * @param nRows    Number of rows (minimum 4)
     * @return Array of rows, each containing [level, volume, area, spill]
     */
    public static double[][] generateRows(double fsVolume, double fsArea, int nRows) {
        // Ensure minimum of 4 rows
        nRows = Math.max(4, nRows);

        double[][] result = new double[nRows][4];

        // Calculate full supply level
        double fsLevel = 3.0 * (fsVolume / 1000.0) / fsArea;

        // Number of pre-spill rows (from level=0 to level=fsLevel)
        int preSpillRows = nRows - 2;

        // Generate pre-spill rows with linearly spaced levels
        for (int i = 0; i < preSpillRows; i++) {
            double level = fsLevel * i / (preSpillRows - 1);
            double volume;
            double area;

            if (level == 0) {
                volume = 0;
                area = 0;
            } else {
                // Pyramidal scaling: volume ∝ level³, area ∝ level²
                double ratio = level / fsLevel;
                volume = fsVolume * ratio * ratio * ratio;
                area = fsArea * ratio * ratio;
            }

            result[i][0] = level;
            result[i][1] = volume;
            result[i][2] = area;
            result[i][3] = 0; // No spill below full supply
        }

        // Add the two overflow rows
        // Row at fsVolume + 1 (spill begins)
        double spillLevel = calculateLevel(fsVolume + 1, fsLevel, fsVolume);
        double spillRatio = spillLevel / fsLevel;
        double spillArea = fsArea * spillRatio * spillRatio;
        result[nRows - 2][0] = spillLevel;
        result[nRows - 2][1] = fsVolume + 1;
        result[nRows - 2][2] = spillArea;
        result[nRows - 2][3] = BIG_SPILL;

        // Row at BIG_VOL (overflow)
        double overflowLevel = calculateLevel(BIG_VOL, fsLevel, fsVolume);
        double overflowRatio = overflowLevel / fsLevel;
        double overflowArea = fsArea * overflowRatio * overflowRatio;
        result[nRows - 1][0] = overflowLevel;
        result[nRows - 1][1] = BIG_VOL;
        result[nRows - 1][2] = overflowArea;
        result[nRows - 1][3] = BIG_SPILL;

        return result;
    }

    /**
     * Calculates level from volume using pyramid geometry.
     * Since volume ∝ level³, we use: level = fsLevel * (volume/fsVolume)^(1/3)
     */
    private static double calculateLevel(double volume, double fsLevel, double fsVolume) {
        return fsLevel * Math.cbrt(volume / fsVolume);
    }
}
