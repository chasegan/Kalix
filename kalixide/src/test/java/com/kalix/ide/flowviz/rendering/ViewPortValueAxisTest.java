package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static ViewPort withValueBounds(ViewPort v, double[] bounds) {
        return new ViewPort(v.getStartTimeMs(), v.getEndTimeMs(), bounds[0], bounds[1],
            v.getPlotX(), v.getPlotY(), v.getPlotWidth(), v.getPlotHeight(), v.getYAxisScale(), v.getXAxisType());
    }

    private static double decades(ViewPort v) {
        return v.getTransformedMax() - v.getTransformedMin();
    }

    @Test
    void wheelZoomKeepsTheValueUnderTheCursorStationaryOnALinearScale() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        int screenY = 100;   // three quarters up: value 75
        assertEquals(75.0, v.screenYToValue(screenY), 1e-12);

        ViewPort zoomed = withValueBounds(v, v.valueBoundsZoomedAbout(2.0, screenY));
        assertEquals(37.5, zoomed.getMinValue(), 1e-12);
        assertEquals(87.5, zoomed.getMaxValue(), 1e-12);
        assertEquals(75.0, zoomed.screenYToValue(screenY), 1e-12, "the anchor value stays under the cursor");
    }

    @Test
    void wheelZoomKeepsTheValueUnderTheCursorStationaryOnALogScale() {
        ViewPort v = viewport(1, 10_000, YAxisScale.LOG);
        int screenY = 100;   // three quarters up: 10^3
        assertEquals(1000.0, v.screenYToValue(screenY), 1000 * REL);

        ViewPort zoomed = withValueBounds(v, v.valueBoundsZoomedAbout(2.0, screenY));
        assertEquals(2.0, decades(zoomed), REL, "the decade span halves");
        assertEquals(1000.0, zoomed.screenYToValue(screenY), 1000 * REL, "the anchor value stays under the cursor");
    }

    /** ~45 wheel notches from a wide LOG view used to overflow to +Inf and leave the axis blank and stuck. */
    @Test
    void runawayWheelZoomOutStopsAtTheLastShowableViewAndCanZoomBackIn() {
        ViewPort v = viewport(1, 1e250, YAxisScale.LOG);
        for (int notch = 0; notch < 60; notch++) {
            double[] bounds = v.valueBoundsZoomedAbout(1 / 1.1, HEIGHT / 2);
            assertTrue(Double.isFinite(bounds[0]) && Double.isFinite(bounds[1]), "notch " + notch + " installed a non-finite bound");
            v = withValueBounds(v, bounds);
        }
        double span = decades(v);
        assertTrue(span > 250, "the view grew before the overflow step was refused: " + span);
        assertTrue(v.getTransformedMax() < 308.3, "the top never exceeds what 10^t can represent: " + v);
        assertTrue(v.getTransformedMin() > -323.3, "the bottom never underflows to zero: " + v);

        ViewPort zoomedIn = withValueBounds(v, v.valueBoundsZoomedAbout(1.1, HEIGHT / 2));
        assertEquals(span / 1.1, decades(zoomedIn), REL * span, "zooming back in works from the stopped view");
    }

    @Test
    void zoomAndPanRefuseAStepThatOverflowsTheScale() {
        ViewPort v = viewport(1, 1e300, YAxisScale.LOG);

        ViewPort zoomedOut = v.zoom(1 / 1.1, DAY / 2, 1e150);
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

    @Test
    void centringKeepsTheSpanInTransformedSpace() {
        ViewPort log = withValueBounds(viewport(1, 100, YAxisScale.LOG),
            viewport(1, 100, YAxisScale.LOG).valueBoundsCentredOn(5000));
        assertEquals(500.0, log.getMinValue(), 500 * REL);
        assertEquals(50_000.0, log.getMaxValue(), 50_000 * REL);

        ViewPort linear = withValueBounds(viewport(0, 100, YAxisScale.LINEAR),
            viewport(0, 100, YAxisScale.LINEAR).valueBoundsCentredOn(5000));
        assertEquals(4950.0, linear.getMinValue(), 1e-9);
        assertEquals(5050.0, linear.getMaxValue(), 1e-9);
    }

    @Test
    void centringLeavesTheViewAloneForAValueTheScaleCannotShow() {
        ViewPort log = viewport(1, 100, YAxisScale.LOG);
        assertEquals(1.0, log.valueBoundsCentredOn(-5)[0]);
        assertEquals(100.0, log.valueBoundsCentredOn(0)[1]);

        ViewPort linear = viewport(0, 100, YAxisScale.LINEAR);
        assertEquals(0.0, linear.valueBoundsCentredOn(Double.NaN)[0]);
        assertEquals(100.0, linear.valueBoundsCentredOn(Double.POSITIVE_INFINITY)[1]);
    }
}
