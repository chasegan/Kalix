package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.XAxisType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the wheel's time-axis steps on {@link ViewPort}: a whole notch on a millisecond
 * axis behaves as it always did, and a fractional (trackpad) step on a short count axis
 * neither stalls, overshoots, nor collapses the axis.
 */
class ViewPortTimeAxisTest {

    private static final long DAY = 86_400_000L;
    private static final int WIDTH = 600;
    private static final double NOTCH = 1.1;
    /** A tenth of a notch, as a trackpad reports it. */
    private static final double SLOW = Math.pow(NOTCH, 0.1);

    private static ViewPort viewport(long start, long end, XAxisType xAxisType) {
        return new ViewPort(start, end, 0, 100, 0, 0, WIDTH, 400, YAxisScale.LINEAR, xAxisType);
    }

    @Test
    void aWholeNotchOnATimeAxisKeepsTheTimeUnderTheCursor() {
        ViewPort v = viewport(0, DAY, XAxisType.TIME);
        int screenX = WIDTH / 4;
        long anchor = v.screenXToTime(screenX);

        ViewPort zoomed = v.zoomTimeAxis(NOTCH, screenX);

        assertEquals(DAY / NOTCH, zoomed.getTimeRangeMs(), 1.0);
        assertEquals(anchor, zoomed.screenXToTime(screenX), 1.0);
    }

    @Test
    void aSlowZoomOutOnACountAxisWidensByOneUnit() {
        ViewPort zoomed = viewport(0, 20, XAxisType.COUNT).zoomTimeAxis(1 / SLOW, WIDTH / 2);
        assertEquals(21, zoomed.getTimeRangeMs());
    }

    @Test
    void aSlowZoomInOnACountAxisNarrowsByOneUnit() {
        ViewPort zoomed = viewport(0, 20, XAxisType.COUNT).zoomTimeAxis(SLOW, WIDTH / 2);
        assertEquals(19, zoomed.getTimeRangeMs());
    }

    @Test
    void zoomingInNeverCollapsesTheAxis() {
        assertEquals(1, viewport(0, 2, XAxisType.COUNT).zoomTimeAxis(100, WIDTH / 2).getTimeRangeMs());
        assertEquals(1, viewport(5, 6, XAxisType.COUNT).zoomTimeAxis(NOTCH, WIDTH / 2).getTimeRangeMs());
    }

    @Test
    void panByPixelsTakesFractionsAndRounds() {
        ViewPort v = viewport(0, WIDTH, XAxisType.COUNT);   // one unit per pixel
        assertEquals(-1, v.panByPixels(0.6, 0).getStartTimeMs());
        assertEquals(0, v.panByPixels(0.4, 0).getStartTimeMs());
        assertEquals(48, v.panByPixels(-48.0, 0).getStartTimeMs());
    }

    @Test
    void aWholeNotchWheelPanOnATimeAxisMovesItsShareOfThePlot() {
        ViewPort panned = viewport(0, DAY, XAxisType.TIME).wheelPanTime(-48.0);
        assertEquals(DAY * 48 / WIDTH, panned.getStartTimeMs());
        assertEquals(DAY + DAY * 48 / WIDTH, panned.getEndTimeMs());
    }

    @Test
    void aSlowWheelPanOnACountAxisStillMovesOneUnit() {
        ViewPort v = viewport(0, 20, XAxisType.COUNT);
        assertEquals(1, v.wheelPanTime(-4.8).getStartTimeMs());
        assertEquals(-1, v.wheelPanTime(4.8).getStartTimeMs());
        assertEquals(20, v.wheelPanTime(4.8).getTimeRangeMs());
    }

    @Test
    void aWheelPanLeavesTheValueAxisAlone() {
        ViewPort panned = viewport(0, DAY, XAxisType.TIME).wheelPanTime(-48.0);
        assertEquals(0, panned.getMinValue());
        assertEquals(100, panned.getMaxValue());
    }
}
