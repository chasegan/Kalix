package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers dragging one end of an axis: the value (or time) first grabbed follows the cursor
 * while the other end stays put, on every scale, and a drag past the fixed end stops short
 * of it instead of inverting or blowing up the axis.
 */
class ViewPortAxisDragTest {

    private static final long DAY = 86_400_000L;
    private static final int WIDTH = 600;
    private static final int HEIGHT = 400;
    private static final double REL = 1e-9;

    private static ViewPort viewport(double min, double max, YAxisScale scale) {
        return new ViewPort(0, DAY, min, max, 0, 0, WIDTH, HEIGHT, scale, XAxisType.TIME);
    }

    @Test
    void draggingTheMinimumKeepsTheGrabbedValueUnderTheCursorAndTheMaximumFixed() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        ViewPort dragged = v.dragValueEnd(false, 300, 350); // grab 25, pull it down

        assertEquals(100, dragged.getMaxValue(), 1e-12);
        assertEquals(25, dragged.screenYToValue(350), 1e-9);
        assertTrue(dragged.getMinValue() > 0, "pulling the grabbed value down narrows the range: " + dragged);
    }

    @Test
    void draggingTheMaximumKeepsTheGrabbedValueUnderTheCursorAndTheMinimumFixed() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        ViewPort dragged = v.dragValueEnd(true, 100, 200); // grab 75, pull it to the middle

        assertEquals(0, dragged.getMinValue(), 1e-12);
        assertEquals(150, dragged.getMaxValue(), 1e-9);
    }

    @Test
    void aValueDragOnALogScaleWorksInDecades() {
        ViewPort v = viewport(1, 10_000, YAxisScale.LOG);
        ViewPort dragged = v.dragValueEnd(true, 100, 200); // grab 10^3, pull it to the middle

        assertEquals(1, dragged.getMinValue(), REL);
        assertEquals(1e6, dragged.getMaxValue(), 1e6 * REL);
    }

    @Test
    void draggingPastTheFixedEndStopsShortOfIt() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        ViewPort pastTop = v.dragValueEnd(false, 300, -10_000);
        assertEquals(100, pastTop.getMaxValue(), 1e-12);
        assertTrue(Double.isFinite(pastTop.getMinValue()) && pastTop.getMinValue() < 100, "not inverted: " + pastTop);

        ViewPort pastBottom = v.dragValueEnd(true, 100, 10_000);
        assertEquals(0, pastBottom.getMinValue(), 1e-12);
        assertTrue(Double.isFinite(pastBottom.getMaxValue()) && pastBottom.getMaxValue() > 0, "not inverted: " + pastBottom);
    }

    @Test
    void eachValueAxisDragsAloneLeavingTheOtherAxisAsItWas() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR).withY2Bounds(0, 10);

        ViewPort y1Dragged = v.dragValueEnd(true, 100, 200);
        assertEquals(0, y1Dragged.getY2MinValue());
        assertEquals(10, y1Dragged.getY2MaxValue());

        ViewPort y2Dragged = v.dragY2End(true, 100, 200);
        assertEquals(15, y2Dragged.getY2MaxValue(), 1e-9);
        assertEquals(100, y2Dragged.getMaxValue());
    }

    @Test
    void draggingTheEndOfTheTimeAxisKeepsTheStartFixed() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        ViewPort dragged = v.dragTimeEnd(true, 450, 300); // grab 3/4 of a day, pull it to the middle

        assertEquals(0, dragged.getStartTimeMs());
        assertEquals(3 * DAY / 2, dragged.getEndTimeMs());
    }

    @Test
    void draggingTheStartOfTheTimeAxisKeepsTheEndFixed() {
        ViewPort v = viewport(0, 100, YAxisScale.LINEAR);
        ViewPort dragged = v.dragTimeEnd(false, 150, 0); // grab 1/4 of a day, pull it to the left edge

        assertEquals(DAY / 4, dragged.getStartTimeMs());
        assertEquals(DAY, dragged.getEndTimeMs());
    }
}
