package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value-axis steps on {@link ViewPort}: wheel zoom about a screen row, recentring
 * on a value, and the shared rule that a step whose bounds the scale cannot show (LOG
 * overflowing 10^t to infinity, or underflowing to zero) is refused rather than installed.
 */
class ViewPortValueAxisTest {

    private static final long DAY = 86_400_000L;
    private static final int HEIGHT = 400;
    private static final double REL = 1e-9;

    private static ViewPort viewport(double min, double max, YAxisScale scale) {
        return new ViewPort(0, DAY, min, max, 0, 0, 600, HEIGHT, scale, XAxisType.TIME);
    }

    private static double decades(ViewPort v) {
        return v.getTransformedMax() - v.getTransformedMin();
    }

    @Test
    void wheelZoomKeepsTheValueUnderTheCursorStationaryOnALinearScale() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        int screenY = 100;   // three quarters up: value 75
        assertEquals(75.0, v.screenYToValue(screenY), 1e-12);

        ViewPort zoomed = v.zoomValueAxis(2.0, screenY);
        assertEquals(37.5, zoomed.getMinValue(), 1e-12);
        assertEquals(87.5, zoomed.getMaxValue(), 1e-12);
        assertEquals(75.0, zoomed.screenYToValue(screenY), 1e-12, "the anchor value stays under the cursor");
    }

    @Test
    void wheelZoomKeepsTheValueUnderTheCursorStationaryOnALogScale() {
        ViewPort v = viewport(1, 10_000, YAxisScale.LOG);
        int screenY = 100;   // three quarters up: 10^3
        assertEquals(1000.0, v.screenYToValue(screenY), 1000 * REL);

        ViewPort zoomed = v.zoomValueAxis(2.0, screenY);
        assertEquals(2.0, decades(zoomed), REL, "the decade span halves");
        assertEquals(1000.0, zoomed.screenYToValue(screenY), 1000 * REL, "the anchor value stays under the cursor");
    }

    /** ~45 wheel notches from a wide LOG view used to overflow to +Inf and leave the axis blank and stuck. */
    @Test
    void runawayWheelZoomOutStopsAtTheLastShowableViewAndCanZoomBackIn() {
        ViewPort v = viewport(1, 1e250, YAxisScale.LOG);
        for (int notch = 0; notch < 60; notch++) {
            v = v.zoomValueAxis(1 / 1.1, HEIGHT / 2);
            assertTrue(Double.isFinite(v.getMinValue()) && Double.isFinite(v.getMaxValue()), "notch " + notch + " installed a non-finite bound");
        }
        double span = decades(v);
        assertTrue(span > 250, "the view grew before the overflow step was refused: " + span);
        assertTrue(v.getTransformedMax() < 308.3, "the top never exceeds what 10^t can represent: " + v);
        assertTrue(v.getTransformedMin() > -323.3, "the bottom never underflows to zero: " + v);

        ViewPort zoomedIn = v.zoomValueAxis(1.1, HEIGHT / 2);
        assertEquals(span / 1.1, decades(zoomedIn), REL * span, "zooming back in works from the stopped view");
    }

    /** Zooming about the data-space midpoint slid a LOG view upward on every button press. */
    @Test
    void buttonZoomHoldsTheMiddleOfThePlotOnEveryScale() {
        for (YAxisScale scale : YAxisScale.values()) {
            ViewPort v = viewport(1, 10_000, scale);
            double middle = v.screenYToValue(HEIGHT / 2);
            ViewPort in = v.zoom(2.0);
            ViewPort out = v.zoom(0.5);
            assertEquals(middle, in.screenYToValue(HEIGHT / 2), Math.abs(middle) * REL, scale + " zoom in moved the centre");
            assertEquals(middle, out.screenYToValue(HEIGHT / 2), Math.abs(middle) * REL, scale + " zoom out moved the centre");
            assertEquals(decades(v) / 2, decades(in), REL * decades(v), scale + " zoom in halves the span");
            assertEquals(decades(v) * 2, decades(out), REL * decades(v), scale + " zoom out doubles the span");
            assertEquals(DAY / 2, in.getTimeRangeMs(), 1, scale + " time axis zooms too");
        }
    }

    @Test
    void zoomAndPanRefuseAStepThatOverflowsTheScale() {
        ViewPort v = viewport(1, 1e300, YAxisScale.LOG);

        ViewPort zoomedOut = v.zoom(1 / 1.1);
        assertEquals(1.0, zoomedOut.getMinValue());
        assertEquals(1e300, zoomedOut.getMaxValue());
        assertEquals((long) (DAY * 1.1), zoomedOut.getTimeRangeMs(), 2, "the time axis still zooms");

        ViewPort pannedUp = v.panByPixels(0, HEIGHT);   // +300 decades: 10^600 overflows
        assertEquals(1.0, pannedUp.getMinValue());
        assertEquals(1e300, pannedUp.getMaxValue());

        ViewPort pannedDown = v.panByPixels(0, -HEIGHT);   // -300 decades: still representable
        assertEquals(1e-300, pannedDown.getMinValue(), 1e-300 * REL);
        assertEquals(1.0, pannedDown.getMaxValue(), REL);
    }

    /** A fixed six-decade floor sat above a tiny maximum, so one wheel notch inverted the axis for good. */
    @Test
    void logFallbackMinimumStaysBelowTheMaximum() {
        ViewPort v = viewport(0, 1e-8, YAxisScale.LOG);
        assertEquals(-14.0, v.getTransformedMin(), REL, "six decades below the maximum");
        assertEquals(-8.0, v.getTransformedMax(), REL);

        ViewPort zoomed = v.zoomValueAxis(1.1, HEIGHT / 2);
        assertTrue(zoomed.getMinValue() < zoomed.getMaxValue(), "the step keeps the axis upright: " + zoomed);

        assertEquals(-6.0, viewport(0, 100, YAxisScale.LOG).getTransformedMin(), REL, "the usual floor when the maximum allows it");
        assertEquals(-6.0, viewport(0, 0, YAxisScale.LOG).getTransformedMin(), REL, "both bounds unusable: 1e-6..1");
        assertEquals(0.0, viewport(0, 0, YAxisScale.LOG).getTransformedMax(), REL);
    }

    @Test
    void stepsThatWouldInvertTheAxisAreRefused() {
        ViewPort inverted = viewport(10, 1, YAxisScale.LINEAR);   // only reachable internally
        ViewPort refused = inverted.zoomValueAxis(2.0, HEIGHT / 2);
        assertEquals(10.0, refused.getMinValue());
        assertEquals(1.0, refused.getMaxValue());
    }

    @Test
    void centringIsANoOpForAValueAlreadyOnScreen() {
        ViewPort linear = viewport(0, 100, YAxisScale.LINEAR);
        assertSame(linear, linear.centreValueAxisOn(50));
        ViewPort log = viewport(1, 100, YAxisScale.LOG);
        assertSame(log, log.centreValueAxisOn(100));
    }

    /** On LOG with a non-positive stored minimum the axis draws 1e-6..max: judge visibility against that. */
    @Test
    void centringJudgesVisibilityAgainstTheAxisAsDrawn() {
        ViewPort v = viewport(-5, 100, YAxisScale.LOG);   // draws 1e-6..100
        ViewPort centred = v.centreValueAxisOn(1e-9);   // above -5 in data space, below the plot on screen
        assertEquals(1e-13, centred.getMinValue(), 1e-13 * REL);
        assertEquals(1e-5, centred.getMaxValue(), 1e-5 * REL);

        assertSame(v, v.centreValueAxisOn(1e-3), "already on screen");
    }

    /** Switching to LOG used to keep a stored -5 while the axis drew 1e-6, so copy-axis and set-limits lied. */
    @Test
    void switchingScaleMaterialisesTheBoundTheAxisDraws() {
        ViewPort log = viewport(-5, 100, YAxisScale.LINEAR).withYAxisScale(YAxisScale.LOG);
        assertEquals(1e-6, log.getMinValue(), 1e-6 * REL, "the drawn fallback becomes the stored minimum");
        assertEquals(100.0, log.getMaxValue(), REL);

        ViewPort sqrt = viewport(-5, 100, YAxisScale.LINEAR).withYAxisScale(YAxisScale.SQRT);
        assertEquals(-5.0, sqrt.getMinValue(), "a scale that shows the bound keeps it");
        ViewPort linear = viewport(1, 100, YAxisScale.LOG).withYAxisScale(YAxisScale.LINEAR);
        assertEquals(1.0, linear.getMinValue());
    }

    @Test
    void centringKeepsTheSpanInTransformedSpace() {
        ViewPort log = viewport(1, 100, YAxisScale.LOG).centreValueAxisOn(5000);
        assertEquals(500.0, log.getMinValue(), 500 * REL);
        assertEquals(50_000.0, log.getMaxValue(), 50_000 * REL);

        ViewPort linear = viewport(0, 100, YAxisScale.LINEAR).centreValueAxisOn(5000);
        assertEquals(4950.0, linear.getMinValue(), 1e-9);
        assertEquals(5050.0, linear.getMaxValue(), 1e-9);
    }

    @Test
    void centringLeavesTheViewAloneForAValueTheScaleCannotShow() {
        ViewPort log = viewport(1, 100, YAxisScale.LOG);
        assertSame(log, log.centreValueAxisOn(-5));
        assertSame(log, log.centreValueAxisOn(0));

        ViewPort linear = viewport(0, 100, YAxisScale.LINEAR);
        assertSame(linear, linear.centreValueAxisOn(Double.NaN));
        assertSame(linear, linear.centreValueAxisOn(Double.POSITIVE_INFINITY));
    }
}
