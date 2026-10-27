package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the right (y2) axis on {@link ViewPort}: it is carried through every copy, and the
 * value-axis steps (zoom, pan, rescale) move it alongside y1, each about the same screen row.
 */
class ViewPortY2Test {

    private static final long DAY = 86_400_000L;
    private static final int HEIGHT = 400;
    private static final double REL = 1e-9;

    private static ViewPort viewport(double min, double max, YAxisScale scale) {
        return new ViewPort(0, DAY, min, max, 0, 0, 600, HEIGHT, scale, XAxisType.TIME);
    }

    @Test
    void aNewViewHasNoRightAxis() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        assertFalse(v.hasY2());
        assertTrue(Double.isNaN(v.getY2MinValue()));
        assertThrows(IllegalStateException.class, v::forY2);
    }

    @Test
    void forY2PutsTheRightRangeInTheValueSlotAndSharesTheRest() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR).withY2Bounds(10, 20);
        ViewPort y2 = v.forY2();
        assertEquals(10, y2.getMinValue());
        assertEquals(20, y2.getMaxValue());
        assertFalse(y2.hasY2());
        assertEquals(v.getStartTimeMs(), y2.getStartTimeMs());
        assertEquals(v.getEndTimeMs(), y2.getEndTimeMs());
        assertEquals(v.getPlotHeight(), y2.getPlotHeight());
        assertEquals(15.0, y2.screenYToValue(HEIGHT / 2), 1e-12);
    }

    @Test
    void copiesCarryTheRightAxis() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR).withY2Bounds(10, 20);
        for (ViewPort copy : new ViewPort[] {
                v.withTimeRange(5, DAY), v.withPlotArea(1, 2, 300, 200), v.withXAxisType(XAxisType.PERCENTILE)}) {
            assertEquals(10, copy.getY2MinValue(), copy.toString());
            assertEquals(20, copy.getY2MaxValue(), copy.toString());
        }
        assertFalse(v.withoutY2().hasY2());
    }

    @Test
    void wheelZoomKeepsTheValueUnderTheCursorStationaryOnBothAxes() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR).withY2Bounds(0, 10);
        int screenY = 100; // three quarters up: 75 on y1, 7.5 on y2

        ViewPort zoomed = v.zoomValueAxis(2.0, screenY);
        assertEquals(37.5, zoomed.getMinValue(), 1e-12);
        assertEquals(87.5, zoomed.getMaxValue(), 1e-12);
        assertEquals(3.75, zoomed.getY2MinValue(), 1e-12);
        assertEquals(8.75, zoomed.getY2MaxValue(), 1e-12);
        assertEquals(7.5, zoomed.forY2().screenYToValue(screenY), 1e-12);
    }

    @Test
    void panMovesBothAxesByTheSameScreenDistance() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR).withY2Bounds(0, 10);
        ViewPort panned = v.panByPixels(0, HEIGHT / 4); // up a quarter of the plot
        assertEquals(25, panned.getMinValue(), 1e-12);
        assertEquals(125, panned.getMaxValue(), 1e-12);
        assertEquals(2.5, panned.getY2MinValue(), 1e-12);
        assertEquals(12.5, panned.getY2MaxValue(), 1e-12);
    }

    @Test
    void eachAxisRefusesItsOwnUnshowableStep() {
        // y1 is near LOG's overflow ceiling and refuses the zoom-out; y2 has room and takes it.
        ViewPort v = viewport(1e-300, 1e300, YAxisScale.LOG).withY2Bounds(1, 100);
        ViewPort zoomed = v.zoomValueAxis(1 / 2.0, HEIGHT / 2);
        assertEquals(1e-300, zoomed.getMinValue(), 1e-300 * REL, "y1 kept its last showable view");
        assertEquals(1e300, zoomed.getMaxValue(), 1e300 * REL);
        ViewPort y2 = zoomed.forY2();
        assertEquals(4.0, y2.getTransformedMax() - y2.getTransformedMin(), REL, "y2's decade span doubled");
    }

    @Test
    void rescalingToLogRescuesANonPositiveRightAxisMinimum() {
        ViewPort v = viewport(1, 1000, YAxisScale.LINEAR).withY2Bounds(-5, 100);
        ViewPort log = v.withYAxisScale(YAxisScale.LOG);
        assertTrue(log.getY2MinValue() > 0, "y2's minimum is replaced by the bound LOG draws: " + log);
        assertEquals(100, log.getY2MaxValue(), 100 * REL);
        assertEquals(1, log.getMinValue(), REL, "a showable y1 is left alone");
    }
}
