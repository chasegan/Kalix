package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.YAxisScale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Cursor;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the axis-drag gesture through the panel's own mouse listeners: a drag on a value
 * axis moves the grabbed end and turns auto-Y off, a drag on the time axis obeys auto-Y,
 * and a press that does not move changes nothing.
 */
class FlowVizPanelAxisDragTest {

    private static final long DAY = 86_400_000L;
    // An 800x600 panel has its plot at x 80..780, y 20..540 (no right axis)
    private static final int Y_AXIS_X = 60;
    private static final int X_AXIS_Y = 560;
    // With a right axis the plot ends at x 730, so its axis band starts there
    private static final int Y2_AXIS_X = 800 - 40;

    private final DatasetSeries flow = new DatasetSeries("/tmp/x.csv", "flow");
    private FlowVizPanel panel;

    @BeforeEach
    void setUp() {
        DataSet dataSet = new DataSet();
        dataSet.addSeries(flow, new TimeSeriesData(
            new long[] {0, DAY, 2 * DAY, 3 * DAY}, new double[] {0, 10, 100, 1000}));
        panel = new FlowVizPanel();
        panel.setSize(800, 600);
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(flow));
        panel.zoomToFit();
    }

    private void drag(int fromX, int fromY, int toX, int toY) {
        press(fromX, fromY);
        if (fromX != toX || fromY != toY) {
            dragTo(toX, toY);
        }
        release(toX, toY);
    }

    private void press(int x, int y) {
        for (MouseListener l : panel.getMouseListeners()) l.mousePressed(event(MouseEvent.MOUSE_PRESSED, x, y));
    }

    private void dragTo(int x, int y) {
        for (MouseMotionListener l : panel.getMouseMotionListeners()) l.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, x, y));
    }

    private void release(int x, int y) {
        for (MouseListener l : panel.getMouseListeners()) l.mouseReleased(event(MouseEvent.MOUSE_RELEASED, x, y));
    }

    /** Puts a second series, much larger than flow, on the right axis; returns it. */
    private DatasetSeries useBothAxes() {
        DatasetSeries large = new DatasetSeries("/tmp/x.csv", "large");
        DataSet dataSet = new DataSet();
        long[] times = {0, DAY, 2 * DAY, 3 * DAY};
        dataSet.addSeries(flow, new TimeSeriesData(times, new double[] {0, 10, 100, 1000}));
        dataSet.addSeries(large, new TimeSeriesData(times, new double[] {5000, 6000, 7000, 8000}));
        panel.setDataSet(dataSet);
        panel.setVisibleSeries(List.of(flow, large));
        panel.setSeriesOnRightAxis(large, true);
        panel.zoomToFit();
        return large;
    }

    private MouseEvent event(int id, int x, int y) {
        return new MouseEvent(panel, id, 0, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1);
    }

    private void doubleClick(int x, int y) {
        drag(x, y, x, y);
        MouseEvent click = new MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK,
            x, y, 2, false, MouseEvent.BUTTON1);
        for (MouseListener l : panel.getMouseListeners()) l.mouseClicked(click);
    }

    private ViewPort view() {
        return panel.viewportForTests();
    }

    @Test
    void draggingTheBottomOfTheYAxisMovesOnlyTheMinimumAndTurnsAutoYOff() {
        panel.setAutoYMode(true);
        double max = view().getMaxValue();
        double min = view().getMinValue();

        drag(Y_AXIS_X, 450, Y_AXIS_X, 500);

        assertFalse(panel.isAutoYMode(), "an explicit Y range opts out of auto-Y");
        assertEquals(max, view().getMaxValue());
        assertNotEquals(min, view().getMinValue());
    }

    @Test
    void draggingTheTimeAxisUnderAutoYRefitsY() {
        panel.setAutoYMode(true);
        double max = view().getMaxValue();

        drag(500, X_AXIS_Y, 700, X_AXIS_Y); // grab in the right half: the end moves

        assertTrue(panel.isAutoYMode(), "an X drag is an X-only change");
        assertTrue(view().getMaxValue() < max, "Y refits to the narrower window: " + view());
    }

    @Test
    void doubleClickingTheYAxisFitsItAloneAndLeavesAutoYOff() {
        ViewPort fitted = view();
        drag(Y_AXIS_X, 450, Y_AXIS_X, 500);
        drag(500, X_AXIS_Y, 700, X_AXIS_Y);
        long start = view().getStartTimeMs();
        long end = view().getEndTimeMs();

        doubleClick(Y_AXIS_X, 300);

        assertFalse(panel.isAutoYMode(), "a one-off fit, not a mode change");
        assertEquals(start, view().getStartTimeMs(), "X is left alone");
        assertEquals(end, view().getEndTimeMs());
        assertTrue(view().getMaxValue() < fitted.getMaxValue(), "Y fits the narrower window shown: " + view());
    }

    @Test
    void doubleClickingTheXAxisFitsTheWholeTimeSpan() {
        ViewPort fitted = view();
        drag(500, X_AXIS_Y, 700, X_AXIS_Y);

        doubleClick(300, X_AXIS_Y);

        assertEquals(fitted.getStartTimeMs(), view().getStartTimeMs());
        assertEquals(fitted.getEndTimeMs(), view().getEndTimeMs());
    }

    @Test
    void doubleClickingTheRightAxisFitsItAlone() {
        useBothAxes();
        ViewPort fitted = view();

        drag(Y2_AXIS_X, 450, Y2_AXIS_X, 500);
        assertNotEquals(fitted.getY2MinValue(), view().getY2MinValue(), "the drag moved y2");
        double y1Min = view().getMinValue();

        doubleClick(Y2_AXIS_X, 300);

        assertEquals(fitted.getY2MinValue(), view().getY2MinValue(), 1e-9);
        assertEquals(fitted.getY2MaxValue(), view().getY2MaxValue(), 1e-9);
        assertEquals(y1Min, view().getMinValue(), "y1 is left alone");
    }

    @Test
    void aRightAxisDragEndsWhenTheRightAxisGoesAway() {
        useBothAxes();
        panel.setAutoYMode(true);
        press(Y2_AXIS_X, 450);
        dragTo(Y2_AXIS_X, 480);

        panel.setVisibleSeries(List.of(flow)); // the right axis' only series leaves the plot
        dragTo(Y2_AXIS_X, 500);
        release(Y2_AXIS_X, 500);

        assertFalse(view().hasY2(), "the drag does not bring the right axis back: " + view());
        assertFalse(panel.isAutoYMode(), "y2 did move before it went, so auto-Y is off");
    }

    @Test
    void aYDragKeepsARightAxisAddedDuringIt() {
        DatasetSeries large = useBothAxes();
        panel.setSeriesOnRightAxis(large, false);
        press(Y_AXIS_X, 450);
        dragTo(Y_AXIS_X, 480);

        panel.setSeriesOnRightAxis(large, true);
        dragTo(Y_AXIS_X, 500);
        release(Y_AXIS_X, 500);

        assertTrue(view().hasY2(), "the drag keeps the right axis that appeared meanwhile: " + view());
    }

    @Test
    void releasingAnAxisDragKeepsItsResizeCursor() {
        drag(Y_AXIS_X, 450, Y_AXIS_X, 500);

        assertEquals(Cursor.S_RESIZE_CURSOR, panel.getCursor().getType(), "the pointer is still on the axis' lower half");
    }

    @Test
    void aFewPixelsOfJitterOnAnAxisIsAClickNotADrag() {
        panel.setAutoYMode(true);
        ViewPort before = view();

        drag(Y_AXIS_X, 450, Y_AXIS_X + 1, 452);

        assertTrue(panel.isAutoYMode(), "a wobbly click, as in a double-click to fit, keeps auto-Y");
        assertEquals(before.getMinValue(), view().getMinValue());
    }

    @Test
    void aScaleChangeDuringADragEndsIt() {
        press(Y_AXIS_X, 450);
        dragTo(Y_AXIS_X, 480);

        panel.setYAxisScale(YAxisScale.LOG);
        ViewPort rescaled = view();
        dragTo(Y_AXIS_X, 520);
        release(Y_AXIS_X, 520);

        assertEquals(rescaled.getMinValue(), view().getMinValue(), "no linear-scale value lands on the log axis");
        assertEquals(rescaled.getMaxValue(), view().getMaxValue());
    }

    @Test
    void aPressOnAnAxisWithoutMovingChangesNothing() {
        panel.setAutoYMode(true);
        ViewPort before = view();

        drag(Y_AXIS_X, 450, Y_AXIS_X, 450);

        assertTrue(panel.isAutoYMode());
        assertEquals(before.getMinValue(), view().getMinValue());
        assertEquals(before.getMaxValue(), view().getMaxValue());
    }
}
