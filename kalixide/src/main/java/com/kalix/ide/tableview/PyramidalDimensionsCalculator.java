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

    // Prevent instantiation
    private PyramidalDimensionsCalculator() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Generates pyramidal storage dimension rows.
     *
     * @param fsVolume Full supply volume [ML]
     * @param fsArea   Full supply area [km²]
     * @return Array of rows, each containing [level, volume, area, spill]
     */
    public static double[][] generateRows(double fsVolume, double fsArea) {
        // Define rows: (volume, spill)
        double[][] volumeSpill = {
            {0, 0},
            {fsVolume, 0},
            {fsVolume + 1, BIG_SPILL},
            {BIG_VOL, BIG_SPILL}
        };

        double[][] result = new double[volumeSpill.length][4];

        for (int i = 0; i < volumeSpill.length; i++) {
            double volume = volumeSpill[i][0];
            double spill = volumeSpill[i][1];

            // Calculate pyramid dimensions
            double fsLevel = 3.0 * (fsVolume / 1000.0) / fsArea;
            double level = Math.sqrt(3.0 * (volume / 1000.0) * (fsLevel / fsArea));
            double area = (volume > 0) ? (3.0 * (volume / 1000.0) / level) : 0;

            result[i][0] = level;
            result[i][1] = volume;
            result[i][2] = area;
            result[i][3] = spill;
        }

        return result;
    }
}
