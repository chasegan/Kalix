package com.kalix.ide.flowviz.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NaN-handling for the statistics most affected by the materialised-grid representation:
 * the "Points" count (must report valid, not raw) and SDEB (must not be poisoned to NaN).
 */
class StatisticNaNHandlingTest {

    @Test
    void countReportsValidPointsNotGridSlots() {
        // A materialised grid: 5 array slots, 2 of them NaN (missing).
        StatSample sample = new StatSample(new double[]{1.0, Double.NaN, 3.0, Double.NaN, 5.0});

        String count = new CountStatistic().calculate(sample, null);

        assertEquals("3", count, "Points should count the 3 observations, not the 5 grid slots");
    }

    @Test
    void sdebStaysFiniteIfAStrayNaNReachesIt() {
        // SDEB is only reached with masked (NaN-free) data; this exercises the defensive SD-loop
        // guard by feeding aligned samples carrying a co-located stray NaN (so valid counts stay
        // equal, as masking would guarantee). The NaN pair must not poison the objective to NaN.
        StatSample observed = new StatSample(new double[]{1.0, Double.NaN, 9.0, 16.0});
        StatSample simulated = new StatSample(new double[]{2.0, Double.NaN, 8.0, 15.0});

        String sdeb = new SdebStatistic().calculate(simulated, observed);

        assertNotEquals("N/A", sdeb, "samples are non-empty and aligned");
        assertFalse(sdeb.contains("NaN"), "a stray NaN pair must not poison SDEB: " + sdeb);
        assertTrue(Double.isFinite(Double.parseDouble(sdeb)), "SDEB result is finite: " + sdeb);
    }
}
