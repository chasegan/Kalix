package com.kalix.ide.flowviz.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StatSample} — the prepared-input value type statistics consume.
 * Covers the lazily-derived quantities, NaN handling, and edge cases.
 */
class StatSampleTest {

    private static final double EPS = 1e-9;

    @Test
    void derivesSumMeanMinMaxOverFiniteValues() {
        StatSample s = new StatSample(new double[]{2.0, 5.0, 1.0, 4.0});
        assertEquals(4, s.rawCount());
        assertEquals(4, s.validCount());
        assertEquals(12.0, s.sum(), EPS);
        assertEquals(3.0, s.mean(), EPS);
        assertEquals(1.0, s.min(), EPS);
        assertEquals(5.0, s.max(), EPS);
    }

    @Test
    void skipsNonFiniteValues() {
        StatSample s = new StatSample(new double[]{
            2.0, Double.NaN, 4.0, Double.POSITIVE_INFINITY, 6.0});
        assertEquals(5, s.rawCount(), "rawCount includes non-finite entries");
        assertEquals(3, s.validCount(), "validCount counts only finite entries");
        assertEquals(12.0, s.sum(), EPS);
        assertEquals(4.0, s.mean(), EPS);
        assertEquals(2.0, s.min(), EPS);
        assertEquals(6.0, s.max(), EPS);
    }

    @Test
    void emptySample() {
        StatSample s = new StatSample(new double[0]);
        assertEquals(0, s.rawCount());
        assertEquals(0, s.validCount());
        assertEquals(0.0, s.sum(), EPS);
        assertTrue(Double.isNaN(s.mean()));
        assertTrue(Double.isNaN(s.min()));
        assertTrue(Double.isNaN(s.max()));
        assertEquals(0, s.sortedValidValues().length);
    }

    @Test
    void allNonFiniteSampleHasNoStats() {
        StatSample s = new StatSample(new double[]{Double.NaN, Double.NaN});
        assertEquals(2, s.rawCount());
        assertEquals(0, s.validCount());
        assertEquals(0.0, s.sum(), EPS);
        assertTrue(Double.isNaN(s.mean()));
        assertTrue(Double.isNaN(s.min()));
        assertTrue(Double.isNaN(s.max()));
    }

    @Test
    void sortedValidValuesIsSortedAndFiniteOnly() {
        StatSample s = new StatSample(new double[]{5.0, Double.NaN, 1.0, 3.0, 2.0});
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 5.0}, s.sortedValidValues(), EPS);
    }

    @Test
    void sortedValidValuesIsCachedAndStable() {
        StatSample s = new StatSample(new double[]{3.0, 1.0, 2.0});
        double[] first = s.sortedValidValues();
        double[] second = s.sortedValidValues();
        assertSame(first, second, "sorted distribution should be computed once and cached");
    }

    @Test
    void valuesReturnsOriginalOrderIncludingNonFinite() {
        double[] raw = {3.0, Double.NaN, 1.0};
        StatSample s = new StatSample(raw);
        assertArrayEquals(raw, s.values(), "values() preserves order for index-aligned bivariate use");
    }
}
