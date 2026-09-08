package com.kalix.ide.flowviz.transform;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the properties every {@link YAxisScale} has to hold for the plot to be readable, and
 * that were previously guaranteed only by comments.
 *
 * <p>The load-bearing one is SYMLOG's continuity: its two branches meet only because the
 * linear slope is derived as {@code log10(L) / L}. Hard-code a slope that disagrees with the
 * threshold and the axis silently gains a step discontinuity at +/-L - series jump, and
 * screen position stops being a monotone function of value. Nothing else in the codebase
 * would fail.</p>
 */
class YAxisScaleTest {

    /** Values spanning the magnitudes hydrological series actually carry, both signs. */
    private static final double[] SAMPLE_VALUES = {
        1e-12, 1e-6, 0.001, 0.5, 1.0, 9.999, 10.0, 10.001, 42.0, 1e3, 1e6, 1e9
    };

    @Test
    void transformRoundTripsForEveryScale() {
        for (YAxisScale scale : YAxisScale.values()) {
            for (double magnitude : SAMPLE_VALUES) {
                for (double y : new double[]{magnitude, -magnitude}) {
                    double transformed = scale.transform(y);
                    if (Double.isNaN(transformed)) continue; // outside this scale's domain

                    double back = scale.inverseTransform(transformed);
                    assertEquals(y, back, Math.abs(y) * 1e-12,
                        scale + " failed to round-trip " + y);
                }
            }
        }
    }

    @Test
    void symlogBranchesMeetAtTheThreshold() {
        double threshold = YAxisScale.SYMLOG.linearThreshold().orElseThrow();

        // Approach the seam from inside the linear region and from inside the log region.
        double justBelow = YAxisScale.SYMLOG.transform(threshold - Math.ulp(threshold));
        double atSeam = YAxisScale.SYMLOG.transform(threshold);
        assertEquals(atSeam, justBelow, 1e-9, "SYMLOG is discontinuous at +L");

        double justAbove = YAxisScale.SYMLOG.transform(-threshold + Math.ulp(threshold));
        double atNegativeSeam = YAxisScale.SYMLOG.transform(-threshold);
        assertEquals(atNegativeSeam, justAbove, 1e-9, "SYMLOG is discontinuous at -L");
    }

    @Test
    void symlogLinearRegionSpansExactlyOneDecade() {
        // The whole point of the derived slope: the linear zone occupies the same screen
        // distance as one decade of the log region, so ticks land on exact powers of ten.
        assertEquals(1.0, YAxisScale.SYMLOG.transform(10.0), 0.0);
        assertEquals(-1.0, YAxisScale.SYMLOG.transform(-10.0), 0.0);
        assertEquals(2.0, YAxisScale.SYMLOG.transform(100.0), 1e-15);
        assertEquals(0.0, YAxisScale.SYMLOG.transform(0.0), 0.0);

        // ...and back again, so axis ticks generated at integer transformed positions
        // inverse-transform to round decades rather than to offset values.
        assertEquals(10.0, YAxisScale.SYMLOG.inverseTransform(1.0), 1e-12);
        assertEquals(100.0, YAxisScale.SYMLOG.inverseTransform(2.0), 1e-12);
        assertEquals(-10.0, YAxisScale.SYMLOG.inverseTransform(-1.0), 1e-12);
    }

    @Test
    void symlogIsStrictlyMonotonicThroughZeroAndBothSeams() {
        // Screen position must be a monotone function of value, or panning reorders the data.
        double previous = Double.NEGATIVE_INFINITY;
        for (double y = -50.0; y <= 50.0; y += 0.0009) {
            double transformed = YAxisScale.SYMLOG.transform(y);
            double at = y;
            assertTrue(transformed > previous, () -> "SYMLOG is not strictly increasing at y=" + at);
            previous = transformed;
        }
    }

    @Test
    void symlogAcceptsEveryFiniteValueAndPropagatesNonFinite() {
        // Unlike LOG, SYMLOG has no domain restriction - autoscale relies on this when it
        // skips the non-positive filter for scales other than LOG.
        for (double magnitude : SAMPLE_VALUES) {
            assertFalse(Double.isNaN(YAxisScale.SYMLOG.transform(magnitude)));
            assertFalse(Double.isNaN(YAxisScale.SYMLOG.transform(-magnitude)));
        }
        assertTrue(Double.isNaN(YAxisScale.SYMLOG.transform(Double.NaN)));
        assertEquals(Double.POSITIVE_INFINITY, YAxisScale.SYMLOG.transform(Double.POSITIVE_INFINITY));
        assertEquals(Double.NEGATIVE_INFINITY, YAxisScale.SYMLOG.transform(Double.NEGATIVE_INFINITY));
    }

    @Test
    void logRejectsNonPositiveValues() {
        assertTrue(Double.isNaN(YAxisScale.LOG.transform(0.0)));
        assertTrue(Double.isNaN(YAxisScale.LOG.transform(-1.0)));
        assertEquals(3.0, YAxisScale.LOG.transform(1000.0), 1e-12);
    }

    @Test
    void sqrtIsSignedSoNegativeSpaceStaysReachable() {
        assertEquals(3.0, YAxisScale.SQRT.transform(9.0), 1e-12);
        assertEquals(-3.0, YAxisScale.SQRT.transform(-9.0), 1e-12);
    }

    @Test
    void onlySymlogReportsALinearThreshold() {
        for (YAxisScale scale : YAxisScale.values()) {
            OptionalDouble threshold = scale.linearThreshold();
            if (scale == YAxisScale.SYMLOG) {
                assertEquals(10.0, threshold.orElseThrow(), 0.0);
            } else {
                assertTrue(threshold.isEmpty(), scale + " should have no linear region");
            }
        }
    }

    @Test
    void onlyLogRestrictsTheDomain() {
        // Auto-Y drops values at or below the floor with one compare, so for the scales
        // defined everywhere the floor must sit below every finite value.
        assertEquals(0.0, YAxisScale.LOG.domainFloor(), 0.0);
        for (YAxisScale scale : new YAxisScale[] {YAxisScale.LINEAR, YAxisScale.SQRT, YAxisScale.SYMLOG}) {
            assertEquals(Double.NEGATIVE_INFINITY, scale.domainFloor(), scale + " is defined everywhere");
            assertFalse(-Double.MAX_VALUE <= scale.domainFloor(), scale + " must keep the most negative finite value");
        }
        assertTrue(0.0 <= YAxisScale.LOG.domainFloor(), "LOG drops zero");
        assertFalse(Double.MIN_VALUE <= YAxisScale.LOG.domainFloor(), "LOG keeps the smallest positive value");
    }

    @Test
    void displayNamesAreUniqueAndRoundTrip() {
        // Both the toolbar dropdown and the context-menu radio group select by display name,
        // so a duplicate would make one of two scales unselectable.
        Set<String> seen = new HashSet<>();
        for (YAxisScale scale : YAxisScale.values()) {
            assertNotNull(scale.getDisplayName());
            assertTrue(seen.add(scale.getDisplayName()),
                "duplicate display name: " + scale.getDisplayName());
            assertSame(scale, YAxisScale.fromDisplayName(scale.getDisplayName()));
        }
    }

    @Test
    void unknownDisplayNameFallsBackToLinear() {
        assertSame(YAxisScale.LINEAR, YAxisScale.fromDisplayName("Symlog10"));
        assertSame(YAxisScale.LINEAR, YAxisScale.fromDisplayName(""));
    }
}
