package com.kalix.ide.flowviz.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@link ViewPort#validateBounds}, the guard on user-supplied axis limits (the
 * Set-axis-limits dialog and the axis paste commands).
 */
class ViewPortBoundsTest {

    private static final long T0 = 1_705_276_800_000L;   // 2024-01-15T00:00:00Z
    private static final long T1 = T0 + 86_400_000L;     // one day later

    @Test
    void acceptsIncreasingLimits() {
        assertDoesNotThrow(() -> ViewPort.validateBounds(T0, T1, 0.0, 10.0));
    }

    @Test
    void rejectsInvertedXRange() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ViewPort.validateBounds(T1, T0, 0.0, 10.0));
        assertEquals("X min must be less than X max.", ex.getMessage());
    }

    @Test
    void rejectsZeroWidthXRange() {
        assertThrows(IllegalArgumentException.class, () -> ViewPort.validateBounds(T0, T0, 0.0, 10.0));
    }

    @Test
    void rejectsInvertedYRange() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ViewPort.validateBounds(T0, T1, 10.0, 0.0));
        assertEquals("Y min must be less than Y max.", ex.getMessage());
    }

    @Test
    void rejectsZeroHeightYRange() {
        assertThrows(IllegalArgumentException.class, () -> ViewPort.validateBounds(T0, T1, 5.0, 5.0));
    }

    /**
     * "NaN" and "Infinity" both parse as doubles, and NaN compares false against
     * everything — so without the finite check they would slip past the ordering test.
     */
    @Test
    void rejectsNonFiniteYLimits() {
        assertThrows(IllegalArgumentException.class,
            () -> ViewPort.validateBounds(T0, T1, Double.NaN, 10.0));
        assertThrows(IllegalArgumentException.class,
            () -> ViewPort.validateBounds(T0, T1, 0.0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> ViewPort.validateBounds(T0, T1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
    }
}
