package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.transform.XAxisType;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.rendering.AxisLimitCodec;
import com.kalix.ide.flowviz.rendering.LineShape;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.io.SeriesFileWriter;
import com.kalix.ide.io.SourceResCsvExporter;
import com.kalix.ide.io.SourceResCsvFormat;
import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.DialogUtils;

/**
 * Manages all plot interaction functionality including mouse handling, zooming,
 * panning, and context menu operations.
 *
 * This class handles:
 * - Mouse wheel zooming with cursor positioning
 * - Click and drag panning
 * - Right-click context menu
 * - Auto-range per value axis
 * - Data export functionality
 */
public class PlotInteractionManager {

    // Zoom and pan constants
    private static final double ZOOM_FACTOR = UIConstants.Zoom.ZOOM_FACTOR;
    /** How far out from the plot edge an axis can be grabbed, in pixels. */
    private static final int AXIS_GRAB_DEPTH = 50;
    /** Pixels a press must move before it is an axis drag rather than a click (as the Key's). */
    private static final int AXIS_DRAG_THRESHOLD = 5;

    private final FlowVizPanel panel;
    private final CoordinateDisplayManager coordinateDisplayManager;

    // State management
    private Point lastMousePos;
    private boolean isDragging = false;
    private JPopupMenu contextMenu;
    // One submenu per axis, holding that axis' commands; the right axis' only while it exists
    private final Map<Axis, AxisMenu> axisMenus = new EnumMap<>(Axis.class);
    private JCheckBoxMenuItem connectGapsMenuItem;
    private JCheckBoxMenuItem orphanMarkersMenuItem;
    private final Map<LineShape, JRadioButtonMenuItem> lineShapeMenuItems = new EnumMap<>(LineShape.class);
    private JMenu yAxisScaleMenu;

    // Zoom rectangle selection state
    private boolean isZoomSelecting = false;
    private Point zoomRectStartPoint;
    private Point zoomRectCurrentPoint;

    // The axis end being dragged, from where, and the view it started from (null when none)
    private AxisHandle axisDrag;
    private Point axisDragPress;
    private ViewPort axisDragStart;
    private boolean axisDragMoved;

    // Data access callbacks
    private Supplier<DataSet> dataSetSupplier;
    private Supplier<ViewPort> viewportSupplier;
    private Consumer<ViewPort> viewportUpdater;
    private Supplier<List<com.kalix.ide.flowviz.data.SeriesRef>> visibleSeriesSupplier;
    private Supplier<Rectangle> plotAreaSupplier;
    private Supplier<Boolean> precision64Supplier;
    private Supplier<java.io.File> baseDirectorySupplier;
    private Supplier<com.kalix.ide.flowviz.data.LabelResolver> labelResolverSupplier;
    // Which series are drawn against the right axis; consulted only when the view has one
    private Predicate<SeriesRef> onRightAxis = ref -> false;

    // The Key entry under a point (null if none), and the one the open context menu is for
    private Function<Point, SeriesRef> keyEntryLookup = point -> null;
    private SeriesRef menuSeries;
    private JMenuItem moveAxisItem;


    /**
     * Creates a new plot interaction manager for handling all user interactions with the plot.
     *
     * <p>This manager centralizes all mouse-based interactions including:
     * <ul>
     * <li>Mouse wheel zooming with cursor-centered scaling</li>
     * <li>Click and drag panning with visual feedback</li>
     * <li>Right-click context menu with plot operations</li>
     * <li>Double-click zoom-to-fit functionality</li>
     * <li>Auto-Y mode support for intelligent Y-axis scaling</li>
     * </ul>
     *
     * @param panel The plot this manager serves: it draws the view, follows the model, and takes the mouse listeners
     * @param coordinateDisplayManager The coordinate display manager for integrated mouse position tracking
     */
    public PlotInteractionManager(FlowVizPanel panel, CoordinateDisplayManager coordinateDisplayManager) {
        this.panel = panel;
        this.coordinateDisplayManager = coordinateDisplayManager;

        setupContextMenu();
    }

    /**
     * Supplies what the panel keeps private: its display set, its viewport and the one way
     * to set it as a user gesture, its visible series, and its plot area.
     *
     * @param dataSetSupplier Supplier function to access the current dataset for auto-Y calculations
     * @param viewportSupplier Supplier function to access the current viewport state
     * @param viewportUpdater Consumer function to update the viewport after zoom/pan operations
     * @param visibleSeriesSupplier Supplier function to access the list of currently visible series
     * @param plotAreaSupplier Supplier function to access the current plot area rectangle
     */
    public void setupDataAccess(Supplier<DataSet> dataSetSupplier,
                               Supplier<ViewPort> viewportSupplier,
                               Consumer<ViewPort> viewportUpdater,
                               Supplier<List<com.kalix.ide.flowviz.data.SeriesRef>> visibleSeriesSupplier,
                               Supplier<Rectangle> plotAreaSupplier) {
        this.dataSetSupplier = dataSetSupplier;
        this.viewportSupplier = viewportSupplier;
        this.viewportUpdater = viewportUpdater;
        this.visibleSeriesSupplier = visibleSeriesSupplier;
        this.plotAreaSupplier = plotAreaSupplier;
    }

    /**
     * Sets the precision preference supplier for export operations.
     * This only affects data export format, not plotting functionality.
     */
    public void setPrecision64Supplier(Supplier<Boolean> precision64Supplier) {
        this.precision64Supplier = precision64Supplier;
    }

    /**
     * Sets the base directory supplier for file save dialogs.
     * This should provide the model's directory for saving exported data.
     *
     * @param baseDirectorySupplier Supplier that returns the base directory (null if no file is loaded)
     */
    public void setBaseDirectorySupplier(Supplier<java.io.File> baseDirectorySupplier) {
        this.baseDirectorySupplier = baseDirectorySupplier;
    }

    /**
     * Supplies the {@link com.kalix.ide.flowviz.data.LabelResolver} used to project
     * ref-keyed series to column headers when exporting CSV. May supply {@code null};
     * the exporter falls back to {@code ref.toString()} in that case.
     */
    public void setLabelResolverSupplier(Supplier<com.kalix.ide.flowviz.data.LabelResolver> supplier) {
        this.labelResolverSupplier = supplier;
    }

    /**
     * Creates and returns a comprehensive mouse adapter for handling all plot interactions.
     *
     * <p>The returned MouseAdapter handles:
     * <ul>
     * <li><strong>Right-click:</strong> Shows context menu with zoom and export options</li>
     * <li><strong>Left-click drag:</strong> Pans the plot view with visual cursor feedback</li>
     * <li><strong>Double-click:</strong> Resets zoom to fit all data</li>
     * <li><strong>Mouse wheel:</strong> Zooms in/out centered on cursor position</li>
     * <li><strong>Mouse movement:</strong> Delegates to coordinate display manager</li>
     * </ul>
     *
     * @return A configured MouseAdapter ready to be attached to the parent component
     */
    public MouseAdapter createMouseHandler() {
        return new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Rectangle plotArea = plotAreaSupplier.get();
                // A new press is a new gesture: a release lost to a focus change, or a
                // second button, must not leave an axis drag running into it. Finishing
                // the drag also turns auto-Y off if the axis had already moved.
                if (axisDrag != null) {
                    finishAxisDrag(e.getPoint());
                }
                AxisHandle handle = axisHandleAt(e.getPoint());
                // isPopupTrigger, checked on BOTH press and release: the trigger fires
                // on press on macOS/Linux but on release on Windows, and macOS
                // Ctrl+click is a popup gesture without being the right button.
                if (e.isPopupTrigger() && plotArea.contains(e.getPoint())) {
                    showContextMenuIfPlotted(e);
                } else if (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown() && plotArea.contains(e.getPoint())) {
                    // Shift+click: start zoom rectangle selection
                    isZoomSelecting = true;
                    zoomRectStartPoint = new Point(e.getPoint());
                    zoomRectCurrentPoint = new Point(e.getPoint());
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                } else if (SwingUtilities.isLeftMouseButton(e) && handle != null) {
                    // A press on an axis grabs the end nearest it (the half it landed in)
                    axisDrag = handle;
                    axisDragPress = e.getPoint();
                    axisDragStart = viewportSupplier.get();
                    axisDragMoved = false;
                    panel.setCursor(axisDrag.cursor());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    lastMousePos = e.getPoint();
                    isDragging = true;
                    panel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    // Double-click: reset zoom to fit all data, or on an axis fit just that axis
                    Rectangle plotArea = plotAreaSupplier.get();
                    if (plotArea.contains(e.getPoint())) {
                        zoomToFit();
                    } else {
                        AxisHandle handle = axisHandleAt(e.getPoint());
                        if (handle != null) {
                            fitAxis(handle.axis());
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger() && plotAreaSupplier.get().contains(e.getPoint())) {
                    // Return regardless of whether the menu showed: the gesture was a popup
                    // trigger either way, and on macOS Ctrl+click it is also a left-button
                    // release, which would otherwise fall through into the drag/zoom paths.
                    showContextMenuIfPlotted(e);
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && axisDrag != null) {
                    finishAxisDrag(e.getPoint());
                } else if (SwingUtilities.isLeftMouseButton(e) && isZoomSelecting) {
                    completeZoomRectSelection();
                    isZoomSelecting = false;
                    zoomRectStartPoint = null;
                    zoomRectCurrentPoint = null;
                    panel.setCursor(Cursor.getDefaultCursor());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    isDragging = false;
                    panel.setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (axisDrag != null) {
                    handleAxisDrag(e.getPoint());
                } else if (isZoomSelecting && zoomRectStartPoint != null) {
                    zoomRectCurrentPoint = new Point(e.getPoint());
                    panel.repaint();
                } else if (isDragging && lastMousePos != null) {
                    handlePan(e);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                Rectangle plotArea = plotAreaSupplier.get();
                coordinateDisplayManager.handleMouseMoved(e, plotArea);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                Rectangle plotArea = plotAreaSupplier.get();
                if (plotArea.contains(e.getPoint())) {
                    handleZoom(e);
                }
            }
        };
    }

    /** An axis end a press can grab: which axis, and which end moves. */
    private enum AxisHandle {
        X_START(Axis.X, Cursor.W_RESIZE_CURSOR), X_END(Axis.X, Cursor.E_RESIZE_CURSOR),
        Y_MIN(Axis.Y, Cursor.S_RESIZE_CURSOR), Y_MAX(Axis.Y, Cursor.N_RESIZE_CURSOR),
        Y2_MIN(Axis.Y2, Cursor.S_RESIZE_CURSOR), Y2_MAX(Axis.Y2, Cursor.N_RESIZE_CURSOR);

        private final Axis axis;
        private final int cursorType;

        AxisHandle(Axis axis, int cursorType) {
            this.axis = axis;
            this.cursorType = cursorType;
        }

        Axis axis() {
            return axis;
        }

        Cursor cursor() {
            return Cursor.getPredefinedCursor(cursorType);
        }
    }

    /**
     * The axis end under {@code point}, or null. Each axis is the band just outside its plot
     * edge: below for X, left for Y, right for Y2 (only while there is one). The half of the
     * axis the point is in picks the end -- bottom half the minimum, top half the maximum;
     * left half the start, right half the end.
     */
    private AxisHandle axisHandleAt(Point point) {
        ViewPort viewport = viewportSupplier.get();
        if (viewport == null || !hasPlot()) {
            return null;
        }
        Rectangle plot = plotAreaSupplier.get();
        int right = plot.x + plot.width;
        int bottom = plot.y + plot.height;
        boolean besidePlot = point.y >= plot.y && point.y <= bottom;
        boolean upperHalf = point.y < plot.y + plot.height / 2;
        if (point.x >= plot.x && point.x <= right && point.y > bottom && point.y <= bottom + AXIS_GRAB_DEPTH) {
            return point.x < plot.x + plot.width / 2 ? AxisHandle.X_START : AxisHandle.X_END;
        }
        if (besidePlot && point.x < plot.x && point.x >= plot.x - AXIS_GRAB_DEPTH) {
            return upperHalf ? AxisHandle.Y_MAX : AxisHandle.Y_MIN;
        }
        if (besidePlot && viewport.hasY2() && point.x > right && point.x <= right + AXIS_GRAB_DEPTH) {
            return upperHalf ? AxisHandle.Y2_MAX : AxisHandle.Y2_MIN;
        }
        return null;
    }

    /** The resize cursor for the axis end under {@code point}, or null when it is not over one. */
    public Cursor axisCursorAt(Point point) {
        AxisHandle handle = axisDrag != null ? axisDrag : axisHandleAt(point);
        return handle != null ? handle.cursor() : null;
    }

    /**
     * Moves the grabbed axis end so the value (or time) first grabbed follows the cursor,
     * once the press has moved {@link #AXIS_DRAG_THRESHOLD} pixels, always computed from the
     * view at the start of the drag. Only the grabbed axis comes
     * from that view; the rest is the view as it is now, so a change made meanwhile (a data
     * refresh, a series leaving the plot) is not undone. Dragging X is navigation, so the
     * auto-ranged value axes refit; dragging a value axis is an explicit range, so that
     * axis' auto-range goes off when the drag ends ({@link #finishAxisDrag}).
     */
    private void handleAxisDrag(Point point) {
        ViewPort current = viewportSupplier.get();
        if (axisDragStart == null || current == null) {
            return;
        }
        // A view the start can no longer stand for ends the drag: the right axis went away,
        // or the scale or X axis type changed, so its values would land on the wrong axis
        if ((axisDrag.axis() == Axis.Y2 && !current.hasY2()) || current.getYAxisScale() != axisDragStart.getYAxisScale()
                || current.getXAxisType() != axisDragStart.getXAxisType()) {
            finishAxisDrag(point);
            return;
        }
        if (!axisDragMoved && axisDragPress.distance(point) < AXIS_DRAG_THRESHOLD) {
            return; // a click with a little jitter, as when double-clicking to fit
        }
        axisDragMoved = true;
        ViewPort start = axisDragStart;
        switch (axisDrag) {
            case X_START, X_END -> {
                ViewPort dragged = start.dragTimeEnd(axisDrag == AxisHandle.X_END, axisDragPress.x, point.x);
                navigate(current.withTimeRange(dragged.getStartTimeMs(), dragged.getEndTimeMs()));
            }
            case Y_MIN, Y_MAX -> {
                ViewPort dragged = start.dragValueEnd(axisDrag == AxisHandle.Y_MAX, axisDragPress.y, point.y);
                acceptNewAxes(current.getStartTimeMs(), current.getEndTimeMs(),
                    dragged.getMinValue(), dragged.getMaxValue());
            }
            case Y2_MIN, Y2_MAX -> {
                ViewPort dragged = start.dragY2End(axisDrag == AxisHandle.Y2_MAX, axisDragPress.y, point.y);
                acceptY2Bounds(dragged.getY2MinValue(), dragged.getY2MaxValue());
            }
        }
        panel.repaint();
    }

    /**
     * Ends an axis drag. A value axis that actually moved switches its own auto-range off,
     * after the viewport, so undo records the two as one step (see
     * {@link #applyExplicitLimits}). A press without movement changes nothing. The cursor
     * goes back to whatever {@code point} is over, so a grabbable axis keeps its resize cursor.
     */
    private void finishAxisDrag(Point point) {
        Axis axis = axisDrag.axis();
        if (axisDragMoved && axis.isValueAxis()) {
            panel.model().update(st -> st.withAutoRange(axis, false));
        }
        axisDrag = null;
        axisDragPress = null;
        axisDragStart = null;
        Cursor cursor = axisCursorAt(point);
        panel.setCursor(cursor != null ? cursor : Cursor.getDefaultCursor());
    }

    /**
     * Fits one axis to its data, leaving the others and every auto-range flag alone: a
     * one-off fit, as auto-range would give that axis now. X fits the whole time span,
     * which is navigation, so the auto-ranged value axes refit with it; Y and Y2 fit their
     * own series over the time window shown.
     */
    private void fitAxis(Axis axis) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) {
            return;
        }
        switch (axis) {
            case X -> {
                long[] timeRange = panel.fittedTimeRange();
                if (timeRange != null) {
                    applyXLimits(timeRange[0], timeRange[1]);
                }
            }
            case Y -> {
                double[] y = fittedRangeForAxis(Axis.Y);
                if (y != null) {
                    acceptNewAxes(currentViewport.getStartTimeMs(), currentViewport.getEndTimeMs(), y[0], y[1]);
                }
            }
            case Y2 -> {
                double[] y2 = fittedRangeForAxis(Axis.Y2);
                if (y2 != null) {
                    acceptY2Bounds(y2[0], y2[1]);
                }
            }
        }
    }

    /**
     * Whether {@code axis} is auto-ranged, which decides whether navigation refits it. Read
     * on every use rather than mirrored, so undo/redo and every other path that changes
     * the flag are seen here without any synchronisation.
     */
    private boolean isAutoRange(Axis axis) {
        return panel.isAutoRange(axis);
    }

    /** True when some value axis {@code view} shows is not auto-ranged, so a gesture can zoom it. */
    private boolean anyManualValueAxis(ViewPort view) {
        return !isAutoRange(Axis.Y) || (view.hasY2() && !isAutoRange(Axis.Y2));
    }

    /** Sets the lookup from a point to the Key entry under it, for the axis menu item. */
    public void setKeyEntryLookup(Function<Point, SeriesRef> keyEntryLookup) {
        this.keyEntryLookup = keyEntryLookup;
    }

    /** Sets the test for which series are drawn against the right (y2) axis. */
    public void setRightAxisTest(Predicate<SeriesRef> onRightAxis) {
        this.onRightAxis = onRightAxis;
    }

    /**
     * The padded value range of one value axis' ({@link Axis#Y} or {@link Axis#Y2}) series
     * over the current time window: the range auto-range would give that axis. Null when
     * the axis has nothing showable there.
     */
    double[] fittedRangeForAxis(Axis axis) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return null;
        return fittedRangeForAxis(axis, currentViewport.getStartTimeMs(), currentViewport.getEndTimeMs(),
            currentViewport.getYAxisScale());
    }

    /** As {@link #fittedRangeForAxis(Axis)}, over {@code [startTime, endTime]} on {@code scale}. */
    double[] fittedRangeForAxis(Axis axis, long startTime, long endTime, YAxisScale scale) {
        if (axis == Axis.X) {
            throw new IllegalArgumentException("A value axis, Y or Y2, not X.");
        }
        boolean rightAxis = axis == Axis.Y2;
        return calculateVisibleYRange(startTime, endTime, scale, ref -> onRightAxis.test(ref) == rightAxis);
    }

    /**
     * Refits the auto-ranged value axes to the data in the current time window, keeping
     * the X zoom and every manual axis as they are.
     */
    public void fitYAxisToCurrentXRange() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;
        navigate(currentViewport);
        panel.repaint();
    }

    /**
     * Shows {@code navigated}, a view the user has moved to: each auto-ranged value axis is
     * refitted to its own series over the new time window, and a manual one shows the
     * range the gesture gave it. An auto-ranged axis with nothing showable in the window
     * keeps the range it has: a stock default can be off the scale (-1 on LOG). Every
     * navigation -- wheel, pan, the zoom buttons, a time-axis drag or paste -- lands here,
     * so the one rule holds for all of them.
     */
    private void navigate(ViewPort navigated) {
        ViewPort current = viewportSupplier.get();
        if (current == null) return;

        long startTime = navigated.getStartTimeMs();
        long endTime = navigated.getEndTimeMs();
        YAxisScale scale = navigated.getYAxisScale();
        ViewPort settled = navigated;
        if (isAutoRange(Axis.Y)) {
            double[] y = fittedRangeForAxis(Axis.Y, startTime, endTime, scale);
            settled = y != null ? settled.withValueBounds(y[0], y[1])
                : settled.withValueBounds(current.getMinValue(), current.getMaxValue());
        }
        if (navigated.hasY2() && isAutoRange(Axis.Y2)) {
            double[] y2 = fittedRangeForAxis(Axis.Y2, startTime, endTime, scale);
            settled = y2 != null ? settled.withY2Bounds(y2[0], y2[1])
                : settled.withY2Bounds(current.getY2MinValue(), current.getY2MaxValue());
        }
        Rectangle plotArea = plotAreaSupplier.get();
        viewportUpdater.accept(settled.withPlotArea(plotArea.x, plotArea.y, plotArea.width, plotArea.height));
    }

    /** Zooms in about the plot centre: X, and any manual value axis; the auto-ranged ones refit. */
    public void zoomIn() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;
        navigate(currentViewport.zoom(ZOOM_FACTOR));
        panel.repaint();
    }

    /** Zooms out about the plot centre: X, and any manual value axis; the auto-ranged ones refit. */
    public void zoomOut() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;
        navigate(currentViewport.zoom(1.0 / ZOOM_FACTOR));
        panel.repaint();
    }

    /**
     * Zooms to fit all data in the plot area.
     */
    public void zoomToFit() {
        panel.zoomToFit();
    }

    /**
     * Handles mouse wheel zoom events. The point under the mouse stays put. Scroll alone
     * zooms X and every manual value axis, and the auto-ranged value axes refit to the new
     * window. Ctrl+Scroll (Cmd+Scroll on macOS) zooms the manual value axes only; with
     * every value axis auto-ranged there is nothing for it to do, so it zooms as a plain
     * scroll does.
     */
    private void handleZoom(MouseWheelEvent e) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        // Use smaller zoom factor for mouse wheel (less sensitive)
        double wheelZoomFactor = Math.pow(1.1, -e.getWheelRotation());
        boolean valueAxesOnly = e.isControlDown() || e.isMetaDown();
        ViewPort zoomed = currentViewport.zoomValueAxis(wheelZoomFactor, e.getY());

        if (!valueAxesOnly || !anyManualValueAxis(currentViewport)) {
            // Time in data space, as it is always linear
            long mouseTime = currentViewport.screenXToTime(e.getX());
            long currentStartTime = currentViewport.getStartTimeMs();
            long currentTimeRange = currentViewport.getEndTimeMs() - currentStartTime;
            long newTimeRange = (long) (currentTimeRange / wheelZoomFactor);
            double mouseRatio = (double) (mouseTime - currentStartTime) / currentTimeRange;
            long startTime = mouseTime - (long) (newTimeRange * mouseRatio);
            zoomed = zoomed.withTimeRange(startTime, startTime + newTimeRange);
        }
        navigate(zoomed);
        panel.repaint();
    }

    /**
     * Handles mouse drag panning: X and every manual value axis pan with the mouse, and
     * the auto-ranged value axes refit to the new window.
     */
    private void handlePan(MouseEvent e) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        int dx = e.getX() - lastMousePos.x;
        int dy = e.getY() - lastMousePos.y;
        navigate(currentViewport.panByPixels(dx, dy));

        lastMousePos = e.getPoint();
        panel.repaint();
    }

    /**
     * Completes a zoom rectangle selection by zooming the viewport to the selected region.
     * The rectangle must be at least 5x5 pixels to be considered meaningful.
     */
    private void completeZoomRectSelection() {
        if (zoomRectStartPoint == null || zoomRectCurrentPoint == null) return;

        int rectWidth = Math.abs(zoomRectCurrentPoint.x - zoomRectStartPoint.x);
        int rectHeight = Math.abs(zoomRectCurrentPoint.y - zoomRectStartPoint.y);

        // Must be at least 5x5 pixels in BOTH dimensions (a 200x1 sliver would zoom
        // the Y axis to a ~1-pixel data range, leaving a broken view).
        if (rectWidth < 5 || rectHeight < 5) return;

        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        // Convert screen coordinates to data coordinates
        int leftX = Math.min(zoomRectStartPoint.x, zoomRectCurrentPoint.x);
        int rightX = Math.max(zoomRectStartPoint.x, zoomRectCurrentPoint.x);
        int topY = Math.min(zoomRectStartPoint.y, zoomRectCurrentPoint.y);
        int bottomY = Math.max(zoomRectStartPoint.y, zoomRectCurrentPoint.y);

        long startTime = currentViewport.screenXToTime(leftX);
        long endTime = currentViewport.screenXToTime(rightX);

        // Always zooms every axis -- ignores auto-range so the user can "peek" at a region.
        // The next pan or scroll-zoom refits the auto-ranged axes again.
        double maxValue = currentViewport.screenYToValue(topY);   // top of screen = max value
        double minValue = currentViewport.screenYToValue(bottomY); // bottom of screen = min value

        Rectangle plotArea = plotAreaSupplier.get();
        ViewPort newViewport = currentViewport.withTimeRange(startTime, endTime).withValueBounds(minValue, maxValue)
            .withPlotArea(plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        if (currentViewport.hasY2()) {
            ViewPort y2View = currentViewport.forY2();
            newViewport = newViewport.withY2Bounds(y2View.screenYToValue(bottomY), y2View.screenYToValue(topY));
        }
        viewportUpdater.accept(newViewport);

        panel.repaint();
    }

    /**
     * Renders the zoom selection rectangle overlay.
     * Uses the same visual style as the map panel's selection rectangle.
     */
    public void renderZoomRectangle(Graphics2D g2d) {
        if (!isZoomSelecting || zoomRectStartPoint == null || zoomRectCurrentPoint == null) return;

        int x = Math.min(zoomRectStartPoint.x, zoomRectCurrentPoint.x);
        int y = Math.min(zoomRectStartPoint.y, zoomRectCurrentPoint.y);
        int w = Math.abs(zoomRectCurrentPoint.x - zoomRectStartPoint.x);
        int h = Math.abs(zoomRectCurrentPoint.y - zoomRectStartPoint.y);

        // Semi-transparent blue fill
        g2d.setColor(UIConstants.Selection.RECTANGLE_FILL);
        g2d.fillRect(x, y, w, h);

        // Dashed blue border
        g2d.setColor(UIConstants.Selection.RECTANGLE_BORDER);
        g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                UIConstants.Selection.RECTANGLE_DASH_MITER_LIMIT,
                UIConstants.Selection.RECTANGLE_DASH_PATTERN, 0.0f));
        g2d.drawRect(x, y, w, h);

        // Reset stroke
        g2d.setStroke(new BasicStroke(1.0f));
    }

    /**
     * Calculates the visible Y range on {@code yAxisScale} of the series {@code include}
     * accepts, for a given time range (used in auto-Y mode and zoom to fit). Null when none
     * of them has a showable value there.
     */
    private double[] calculateVisibleYRange(long startTime, long endTime, YAxisScale yAxisScale,
                                            Predicate<SeriesRef> include) {
        DataSet dataSet = dataSetSupplier.get();
        List<com.kalix.ide.flowviz.data.SeriesRef> visibleSeries = visibleSeriesSupplier.get();

        if (dataSet == null || dataSet.isEmpty() || visibleSeries.isEmpty()) {
            return null;
        }

        // Values at or below the scale's domain floor have no position on the axis (LOG:
        // y <= 0). The scale owns the rule; resolved once here so the per-point check in
        // the loop below stays one compare.
        double domainFloor = yAxisScale.domainFloor();

        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        boolean hasValidData = false;

        // Check each visible series for data in the time range. This runs on EVERY
        // mouse event while panning with auto-Y (the default mode), so the scan must
        // be bounded by getIndexRange - O(1) on regular grids, O(log n) otherwise.
        // A full-array scan here made pan cost proportional to TOTAL points, which is
        // exactly where the "millions of points smoothly" promise died.
        for (com.kalix.ide.flowviz.data.SeriesRef ref : visibleSeries) {
            if (!include.test(ref)) continue;
            var series = dataSet.getSeries(ref);
            if (series == null) continue;

            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();
            var range = series.getIndexRange(startTime, endTime);

            // The whole series in range (zoom to fit, a zoomed-out view): its cached extent
            // is the answer when all of it is showable, so no scan
            Double seriesMin = series.getMinValue();
            Double seriesMax = series.getMaxValue();
            if (range.startIndex == 0 && range.endIndex == series.getPointCount()
                    && seriesMin != null && seriesMax != null && seriesMin > domainFloor
                    && !Double.isNaN(seriesMax)) {
                minValue = Math.min(minValue, seriesMin);
                maxValue = Math.max(maxValue, seriesMax);
                hasValidData = true;
                continue;
            }

            for (int i = range.startIndex; i < range.endIndex; i++) {
                if (!validPoints[i]) continue;
                double value = values[i];

                // Skip NaN and values invalid for current scale
                if (Double.isNaN(value)) continue;
                if (value <= domainFloor) continue; // Outside the scale's domain

                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
                hasValidData = true;
            }
        }

        if (!hasValidData) {
            return null;
        }

        // Clamp minimum value for log scale to prevent zooming too far out
        // Hydrological models often produce tiny values (e.g., 1e-12) that are meaningless
        // This only affects auto-zoom; manual zoom/pan can still access the full range
        double logScaleMin = PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD.get();
        if (yAxisScale == YAxisScale.LOG && minValue < logScaleMin && logScaleMin < maxValue) {
            minValue = logScaleMin;
        }

        // Add 5% padding appropriate for the current Y-axis scale
        double valueRange = maxValue - minValue;
        if (valueRange < 1e-15) { // Constant data - use relative padding based on magnitude
            double center = (minValue + maxValue) / 2;
            double halfRange = Math.max(Math.abs(center) * 0.1, 1e-6);
            minValue = center - halfRange;
            maxValue = center + halfRange;
        } else {
            // Apply padding in transformed space for correct visual spacing
            double transformedMin = yAxisScale.transform(minValue);
            double transformedMax = yAxisScale.transform(maxValue);
            double transformedRange = transformedMax - transformedMin;

            double padding = transformedRange * 0.05;
            transformedMin -= padding;
            transformedMax += padding;

            // Inverse transform back to data space
            minValue = yAxisScale.inverseTransform(transformedMin);
            maxValue = yAxisScale.inverseTransform(transformedMax);
        }

        return new double[]{minValue, maxValue};
    }

    /**
     * Shows the context menu at the event position, but only when there is an actual plot
     * under the cursor. Callers have already established that the gesture was a popup
     * trigger inside the plot area.
     */
    private void showContextMenuIfPlotted(MouseEvent e) {
        if (!hasPlot()) {
            return;
        }
        // Over a Key entry the menu can move that series between axes; elsewhere the
        // item is hidden (ADR-0002 §4).
        menuSeries = keyEntryLookup.apply(e.getPoint());
        moveAxisItem.setVisible(menuSeries != null);
        if (menuSeries != null) {
            moveAxisItem.setText(onRightAxis.test(menuSeries) ? "Move to left Y axis" : "Move to right Y axis");
        }
        contextMenu.show(panel, e.getX(), e.getY());
    }

    /**
     * The axis' name as the menus, the Key and the dialog spell it. The one Y axis is "Y
     * axis"; once there is a right one, the two are told apart as left and right, which
     * is what the eye sees (ADR-0002 §2.3: no word the plot does not already supply).
     */
    static String axisLabel(Axis axis, boolean rightAxisInUse) {
        return switch (axis) {
            case X -> "X axis";
            case Y -> rightAxisInUse ? "Left Y axis" : "Y axis";
            case Y2 -> "Right Y axis";
        };
    }

    /** The plot's context menu, for tests that check what it offers. */
    JPopupMenu contextMenu() {
        return contextMenu;
    }

    /**
     * Whether this panel is actually plotting something. Every item on the context menu --
     * zoom to fit, axis limits, axis copy/paste, save data -- acts on plotted series, so on
     * an empty tab the menu is a list of no-ops and error dialogs.
     *
     * <p>The evidence is data in the display set for a visible series, not the viewport
     * and not the selection: {@code FlowVizPanel} synthesises a placeholder viewport
     * ("now +/- 1 hour") while painting an empty panel, and a series is added to the
     * visible list the moment it is ticked, before its fetch completes (or fails). The
     * display set only holds series whose data has arrived, which is the same test
     * {@link #saveData()} applies. The viewport is still checked because the handlers
     * dereference it.</p>
     */
    private boolean hasPlot() {
        if (viewportSupplier == null || visibleSeriesSupplier == null || dataSetSupplier == null) {
            return false;
        }
        DataSet dataSet = dataSetSupplier.get();
        List<SeriesRef> visibleSeries = visibleSeriesSupplier.get();
        return viewportSupplier.get() != null
            && dataSet != null && !dataSet.isEmpty()
            && visibleSeries != null && !visibleSeries.isEmpty();
    }

    /**
     * Whether the clipboard currently holds text the paste items could read. Per
     * ADR-0002 §4, Paste with an empty clipboard is shown disabled rather than
     * hidden: the user should know the command exists. A clipboard held by another
     * application, or none at all (headless), counts as empty.
     */
    private static boolean hasClipboardText() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException | java.awt.HeadlessException ex) {
            return false;
        }
    }

    /**
     * Sets up the right-click context menu.
     */
    private void setupContextMenu() {
        contextMenu = new JPopupMenu();

        // Primary block (ADR-0002 §1, block 1): the default action, which on a plot
        // is what double-click does (see the mouse listener). The skeleton's table lists
        // "Zoom to fit" under block 8 for panels where it is merely a view command; here
        // it is the primary one, so it leads.
        JMenuItem zoomToFitItem = new JMenuItem("Zoom to fit");
        zoomToFitItem.addActionListener(e -> zoomToFit());
        contextMenu.add(zoomToFitItem);

        contextMenu.addSeparator();

        // Context-specific block (block 2): the plot's own handoff to a file.
        JMenuItem saveDataItem = new JMenuItem("Save data…");
        saveDataItem.addActionListener(e -> saveData());
        contextMenu.add(saveDataItem);

        contextMenu.addSeparator();

        // View/state block (block 8): everything that changes how the data is shown, never
        // the data. First the axes, each as a submenu of its own commands: a submenu named
        // for the thing its children act on, read aloud as "Left Y axis: copy range". The
        // copy and paste items act immediately and carry no ellipsis (2.4); "Set axis
        // ranges…" opens a dialog, so it does; the remaining submenus are category nouns
        // with value children (6).
        for (Axis axis : Axis.values()) {
            AxisMenu axisMenu = buildAxisMenu(axis);
            axisMenus.put(axis, axisMenu);
            contextMenu.add(axisMenu.menu());
        }

        JMenuItem setAxes = new JMenuItem("Set axis ranges…");
        setAxes.addActionListener(e1 -> showSetAxesDialog());
        contextMenu.add(setAxes);

        // Y-axis scale submenu
        yAxisScaleMenu = new JMenu("Y-axis scale");
        ButtonGroup yAxisScaleGroup = new ButtonGroup();
        for (YAxisScale scale : YAxisScale.values()) {
            JRadioButtonMenuItem scaleItem = new JRadioButtonMenuItem(scale.getDisplayName());
            scaleItem.addActionListener(e -> {
                panel.model().update(st -> st.withYAxisScale(scale));
            });
            yAxisScaleGroup.add(scaleItem);
            yAxisScaleMenu.add(scaleItem);
        }
        contextMenu.add(yAxisScaleMenu);

        // Line shape submenu: a category noun with value children (6). The items are shared
        // with the toolbar's line shape button (LineShapeMenu) and keyed by enum, so sync
        // never reads identity back out of label text (ADR-0003 §2.2).
        JMenu lineShapeMenu = new JMenu("Line shape");
        lineShapeMenuItems.putAll(LineShapeMenu.addItems(lineShapeMenu, shape -> {
            panel.model().update(st -> st.withLineShape(shape));
        }));
        contextMenu.add(lineShapeMenu);

        // Missing Data submenu. "Draw across gaps" and "Mark orphan points" are mutually
        // exclusive — drawing a continuous line removes the gaps that orphan points would mark —
        // but either may be off. They are checkboxes (mutual exclusion enforced in FlowVizPanel)
        // rather than a radio group, which could not express the "neither selected" state.
        JMenu missingDataMenu = new JMenu("Missing data");

        connectGapsMenuItem = new JCheckBoxMenuItem("Draw across gaps");
        connectGapsMenuItem.addActionListener(e -> {
            panel.setConnectAcrossGaps(connectGapsMenuItem.isSelected());
        });
        missingDataMenu.add(connectGapsMenuItem);

        orphanMarkersMenuItem = new JCheckBoxMenuItem("Mark orphan points");
        orphanMarkersMenuItem.addActionListener(e -> {
            panel.setShowOrphanMarkers(orphanMarkersMenuItem.isSelected());
        });
        missingDataMenu.add(orphanMarkersMenuItem);

        contextMenu.add(missingDataMenu);

        contextMenu.addSeparator();

        // Still block 8, but commands rather than settings (6), so they sit apart from the
        // settings above. The axis move is shown only over a Key entry (4).
        moveAxisItem = new JMenuItem("Move to right Y axis");
        moveAxisItem.setVisible(false); // shown over a Key entry (showContextMenuIfPlotted)
        moveAxisItem.addActionListener(e -> {
            if (menuSeries != null) {
                panel.model().update(st -> st.withSeriesOnRightAxis(menuSeries, !st.isOnRightAxis(menuSeries)));
            }
        });
        contextMenu.add(moveAxisItem);
        // "Key" is what the legend calls itself on screen and on the toolbar, so the item
        // says "Key" too (2.2: one name per concept).
        JMenuItem resetKeyItem = new JMenuItem("Reset key");
        resetKeyItem.addActionListener(e -> {
            panel.resetLegend();
        });
        contextMenu.add(resetKeyItem);

        // Add popup menu listener to update checkbox/radio button states when menu is shown
        contextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean clipboardHasText = hasClipboardText();
                ViewPort currentViewport = viewportSupplier.get();
                boolean hasY2 = currentViewport != null && currentViewport.hasY2();
                for (Axis axis : Axis.values()) {
                    AxisMenu axisMenu = axisMenus.get(axis);
                    axisMenu.menu().setText(axisLabel(axis, hasY2));
                    axisMenu.menu().setVisible(axis != Axis.Y2 || hasY2);
                    axisMenu.paste().setEnabled(clipboardHasText);
                    if (axisMenu.autoRange() != null) {
                        axisMenu.autoRange().setSelected(isAutoRange(axis));
                    }
                }

                // Sync the menu with the plot's current state
                lineShapeMenuItems.get(panel.getLineShape()).setSelected(true);
                connectGapsMenuItem.setSelected(panel.isConnectAcrossGaps());
                orphanMarkersMenuItem.setSelected(panel.isShowOrphanMarkers());
                YAxisScale currentScale = panel.getYAxisScale();
                for (int i = 0; i < yAxisScaleMenu.getItemCount(); i++) {
                    JMenuItem item = yAxisScaleMenu.getItem(i);
                    if (item instanceof JRadioButtonMenuItem radioItem) {
                        radioItem.setSelected(radioItem.getText().equals(currentScale.getDisplayName()));
                    }
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
    }

    /** An axis' submenu and the items on it that change with the plot's state. */
    private record AxisMenu(JMenu menu, JCheckBoxMenuItem autoRange, JMenuItem paste) {}

    /**
     * One axis' commands as a submenu named for it. "Fit to data" leads, being what
     * double-clicking the axis does (ADR-0002 §1, block 1); the value axes then have their
     * auto-range flag; copy and paste of the range close the list.
     */
    private AxisMenu buildAxisMenu(Axis axis) {
        JMenu menu = new JMenu(axisLabel(axis, true));
        JMenuItem fit = new JMenuItem("Fit to data");
        fit.addActionListener(e -> fitAxis(axis));
        menu.add(fit);

        JCheckBoxMenuItem autoRange = null;
        if (axis.isValueAxis()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem("Auto-range");
            item.addActionListener(e -> panel.model().update(st -> st.withAutoRange(axis, item.isSelected())));
            menu.add(item);
            autoRange = item;
        }

        // Copy and paste go through AxisLimitCodec -- the same codec the Set-axis-ranges
        // dialog uses -- so the clipboard always carries the axis' own units.
        JMenuItem copy = new JMenuItem("Copy range");
        copy.addActionListener(e -> copyAxisRange(axis));
        menu.add(copy);
        JMenuItem paste = new JMenuItem("Paste range");
        paste.addActionListener(e -> pasteAxisRange(axis));
        menu.add(paste);
        return new AxisMenu(menu, autoRange, paste);
    }

    private void copyAxisRange(Axis axis) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null || (axis == Axis.Y2 && !currentViewport.hasY2())) {
            return;
        }
        copyStringToClipboard(switch (axis) {
            case X -> AxisLimitCodec.formatXLimits(currentViewport);
            case Y -> AxisLimitCodec.formatYLimits(currentViewport);
            case Y2 -> AxisLimitCodec.formatYLimits(currentViewport.forY2());
        });
    }

    /**
     * Pastes a range onto one axis. A time range is navigation; a value range is explicit,
     * so that axis' auto-range goes off, as for a drag or the dialog.
     */
    private void pasteAxisRange(Axis axis) {
        ViewPort currentViewport = viewportSupplier.get();
        String text = readClipboardText();
        if (currentViewport == null || text == null || (axis == Axis.Y2 && !currentViewport.hasY2())) {
            return;
        }
        try {
            switch (axis) {
                case X -> {
                    long[] x = AxisLimitCodec.parseXLimits(text, currentViewport.getXAxisType());
                    ViewPort.validateBounds(x[0], x[1], currentViewport.getMinValue(), currentViewport.getMaxValue());
                    applyXLimits(x[0], x[1]);
                }
                case Y -> {
                    double[] y = parseValueRange(text, axisLabel(Axis.Y, currentViewport.hasY2()));
                    applyExplicitLimits(currentViewport.getStartTimeMs(), currentViewport.getEndTimeMs(), y[0], y[1]);
                }
                case Y2 -> {
                    double[] y2 = parseValueRange(text, axisLabel(Axis.Y2, true));
                    acceptY2Bounds(y2[0], y2[1]);
                    panel.model().update(st -> st.withAutoRange(Axis.Y2, false));
                }
            }
        } catch (IllegalArgumentException ex) {
            showInvalidInput(panel, ex.getMessage());
        }
    }

    /**
     * Puts a string on the system clipboard.
     */
    private void copyStringToClipboard(String formatted) {
        StringSelection selection = new StringSelection(formatted);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        } catch (IllegalStateException ex) {
            showClipboardError("The clipboard is not available right now.");
        }
    }

    /**
     * Reads the clipboard's text, or reports why it could not and returns {@code null} so
     * callers need only bail out. Text is unavailable when the clipboard is empty or holds
     * something else (which paste items are disabled for, but the contents can change
     * between the menu opening and the click), or when another application holds it.
     */
    private String readClipboardText() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException ex) {
            showClipboardError("The clipboard does not contain text.");
            return null;
        } catch (IllegalStateException ex) {
            showClipboardError("The clipboard is not available right now.");
            return null;
        }
    }

    /** A clipboard transport failure: nothing the user typed was wrong. */
    private void showClipboardError(String message) {
        DialogUtils.showError(panel, message, "Clipboard");
    }

    /** One error dialog for every rejected axis limit, whichever path it arrived by. */
    private static void showInvalidInput(java.awt.Component parent, String message) {
        DialogUtils.showError(parent, message, "Invalid input");
    }

    /**
     * Applies an X-only change: navigation, exactly as wheel zoom and drag pan are, so a
     * pasted time window shows its own data on the auto-ranged axes rather than the
     * previous window's range.
     */
    private void applyXLimits(long startTime, long endTime) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) {
            return;
        }
        navigate(currentViewport.withTimeRange(startTime, endTime));
        panel.repaint();
    }

    /**
     * Applies limits that include an explicit left-axis range: the user has opted that
     * axis out of auto-range, otherwise the next wheel tick or drag would silently refit
     * it and discard them. The viewport lands first and the flag second: the viewport
     * update only restarts the coalesce timer, while the model update records an entry at
     * once with the viewport as it then is, so undo restores both in one step.
     */
    private void applyExplicitLimits(long startTime, long endTime, double minValue, double maxValue) {
        acceptNewAxes(startTime, endTime, minValue, maxValue);
        panel.model().update(st -> st.withAutoRange(Axis.Y, false));
    }

    /**
     * Shows a modal dialog with the current axis limits, pre-filled and editable as text.
     * All four fields are required -- they arrive pre-filled with the current limits, so
     * leaving one alone is how a limit is kept, and a blank field is an error. X fields are
     * parsed according to the viewport's {@link XAxisType} (dates for TIME, percentages for
     * PERCENTILE, etc.) so the field always shows and accepts values in the axis' own units.
     */
    private void showSetAxesDialog() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) {
            return;
        }
        XAxisType xAxisType = currentViewport.getXAxisType();

        boolean hasY2 = currentViewport.hasY2();
        String yLabel = axisLabel(Axis.Y, hasY2);
        String y2Label = axisLabel(Axis.Y2, hasY2);
        JTextField xField = new JTextField(AxisLimitCodec.formatXLimits(currentViewport), 32);
        JTextField yField = new JTextField(AxisLimitCodec.formatYLimits(currentViewport), 32);
        // A right-axis row only while the plot has one
        JTextField y2Field = hasY2 ? new JTextField(AxisLimitCodec.formatYLimits(currentViewport.forY2()), 32) : null;

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        addAxisFieldRow(form, gbc, 0, axisLabel(Axis.X, hasY2) + ":", xField);
        addAxisFieldRow(form, gbc, 1, yLabel + ":", yField);
        if (y2Field != null) {
            addAxisFieldRow(form, gbc, 2, y2Label + ":", y2Field);
        }

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(panel),
            "Set axis ranges", Dialog.ModalityType.APPLICATION_MODAL);  // sentence case (2.1)
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());
        dialog.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(ev -> {
            try {
                long[] x = AxisLimitCodec.parseXLimits(xField.getText(), xAxisType);
                ViewPort.validateBounds(x[0], x[1], currentViewport.getMinValue(), currentViewport.getMaxValue());
                double[] y = parseValueRange(yField.getText(), yLabel);
                double[] y2 = y2Field != null ? parseValueRange(y2Field.getText(), y2Label) : null;
                // An edited value field is an explicit range for that axis, whose auto-range
                // goes off; an untouched one (the codec's formatting round-trips exactly)
                // leaves its axis alone, so the change is navigation for it.
                boolean yEdited = y[0] != currentViewport.getMinValue() || y[1] != currentViewport.getMaxValue();
                boolean y2Edited = y2 != null
                    && (y2[0] != currentViewport.getY2MinValue() || y2[1] != currentViewport.getY2MaxValue());
                if (yEdited || y2Edited) {
                    ViewPort target = currentViewport.withTimeRange(x[0], x[1]);
                    if (yEdited) {
                        target = target.withValueBounds(y[0], y[1]);
                    }
                    if (y2Edited) {
                        target = target.withY2Bounds(y2[0], y2[1]);
                    }
                    navigate(target);
                    panel.model().update(st -> {
                        FlowVizState next = yEdited ? st.withAutoRange(Axis.Y, false) : st;
                        return y2Edited ? next.withAutoRange(Axis.Y2, false) : next;
                    });
                } else {
                    applyXLimits(x[0], x[1]);
                }
                dialog.dispose();
            } catch (IllegalArgumentException ex) {
                // The dialog stays open so the user can correct the field.
                showInvalidInput(dialog, ex.getMessage());
            }
        });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(ev -> dialog.dispose());

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        // Esc cancels; Enter accepts via the default button (the KalixFileDialog idiom).
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });

        dialog.pack();
        dialog.setLocationRelativeTo(panel);
        dialog.setVisible(true);
    }

    /** Parses and checks a value axis' range, naming the axis in any complaint. */
    private static double[] parseValueRange(String text, String axisLabel) {
        double[] range = AxisLimitCodec.parseYLimits(text);
        ViewPort.validateValueRange(axisLabel, range[0], range[1]);
        return range;
    }

    /** Sets the right axis' range, leaving everything else as it is. */
    private void acceptY2Bounds(double minValue, double maxValue) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null || !currentViewport.hasY2()) {
            return;
        }
        viewportUpdater.accept(currentViewport.withY2Bounds(minValue, maxValue));
        panel.repaint();
    }

    /**
     * Adds a label + text field pair as one row of a {@link GridBagLayout} form.
     */
    private void addAxisFieldRow(JPanel form, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, gbc);
    }

    /**
     * Moves the viewport to the given axis limits, keeping plot area, Y-axis scale, and
     * X-axis type unchanged. All four limits are required and are applied as given; callers
     * are responsible for parsing and for checking them with
     * {@link ViewPort#validateBounds} first.
     */
    private void acceptNewAxes(long startTime, long endTime, double minValue, double maxValue) {
        var currentViewport = this.viewportSupplier.get();
        if (currentViewport == null) {
            return;
        }
        // Built from the current view: the window's scale, X axis type and y2 carry over
        Rectangle plotArea = plotAreaSupplier.get();
        ViewPort newViewport = currentViewport.withTimeRange(startTime, endTime).withValueBounds(minValue, maxValue)
            .withPlotArea(plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        viewportUpdater.accept(newViewport);
        panel.repaint();
    }

    /**
     * Displays a file save dialog with multiple format options and exports based on selected file extension.
     */
    public void saveData() {
        DataSet dataSet = dataSetSupplier.get();
        if (dataSet == null || dataSet.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "No data to save.", "Save Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // The suggested name carries the default format's extension; whatever the user
        // settles on is taken verbatim, and saveAs*Format normalises it from there.
        java.util.Optional<File> chosen = KalixFileDialog.saveFile(panel)
            .title("Save Data")
            .startIn(baseDirectorySupplier != null ? baseDirectorySupplier.get() : null)
            .suggestedName("timeseries_data.csv")
            .filters(
                FileDialogFilter.of("CSV Files (*.csv)", "csv"),
                FileDialogFilter.of("Zipped CSV (*.csv.zip)", "csv.zip"),
                FileDialogFilter.of("Source Result CSV (*.res.csv)", "res.csv"),
                FileDialogFilter.of("Pixie Files (*.pxt)", "pxt"))
            .show();
        if (chosen.isPresent()) {
            File file = chosen.get();
            String fileName = file.getName().toLowerCase();

            // The format IS the extension the user settled on. The type combo only ever
            // sets that extension, so there is no second opinion to reconcile against.
            // ".res.csv" is tested before ".csv", which would otherwise swallow it.
            boolean resCsv = SourceResCsvFormat.isResCsv(fileName);
            boolean pixie = !resCsv && fileName.endsWith(".pxt");

            if (resCsv) {
                saveAsResCsvFormat(file);
            } else if (pixie) {
                saveAsPixieFormat(file);
            } else {
                saveAsCsvFormat(file);
            }
        }
    }

    /**
     * Saves data in CSV format.
     */
    private void saveAsCsvFormat(File file) {
        try {
            DataSet dataSet = dataSetSupplier.get();
            com.kalix.ide.flowviz.transform.PlotType plotType = panel.getPlotType();
            com.kalix.ide.flowviz.data.LabelResolver labelResolver =
                (labelResolverSupplier != null) ? labelResolverSupplier.get() : null;
            // .csv.zip counts as CSV (the exporter zips by name); anything else gains .csv.
            file = SeriesFileWriter.write(dataSet, file, plotType, labelResolver, false);
            JOptionPane.showMessageDialog(panel,
                "Data saved successfully to " + file.getName(),
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            DialogUtils.showError(panel,
                "Error saving data: " + e.getMessage(),
                "Save Error");
        } catch (IllegalArgumentException e) {
            DialogUtils.showError(panel,
                "Invalid data: " + e.getMessage(),
                "Save Error");
        }
    }

    /**
     * Saves data in Source result CSV ({@code .res.csv}) format.
     */
    private void saveAsResCsvFormat(File file) {
        // Ensure .res.csv extension
        if (!SourceResCsvFormat.isResCsv(file.getName())) {
            file = new File(file.getAbsolutePath() + SourceResCsvFormat.EXTENSION);
        }

        try {
            DataSet dataSet = dataSetSupplier.get();
            com.kalix.ide.flowviz.data.LabelResolver labelResolver =
                (labelResolverSupplier != null) ? labelResolverSupplier.get() : null;
            SourceResCsvExporter.export(dataSet, file, labelResolver);
            JOptionPane.showMessageDialog(panel,
                "Data saved successfully to " + file.getName(),
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            DialogUtils.showError(panel,
                "Error saving data: " + e.getMessage(),
                "Save Error");
        } catch (IllegalArgumentException e) {
            DialogUtils.showError(panel,
                "Invalid data: " + e.getMessage(),
                "Save Error");
        }
    }

    /**
     * Saves data in Pixie format.
     */
    private void saveAsPixieFormat(File file) {
        try {
            DataSet dataSet = dataSetSupplier.get();
            com.kalix.ide.flowviz.data.LabelResolver labelResolver =
                (labelResolverSupplier != null) ? labelResolverSupplier.get() : null;
            boolean use64BitPrecision = precision64Supplier != null ? precision64Supplier.get() : true;
            // Series are named by their projected labels in the .pxt metadata.
            File pxtFile = SeriesFileWriter.write(dataSet, file, null, labelResolver, use64BitPrecision);
            String pxtName = pxtFile.getName();

            JOptionPane.showMessageDialog(panel,
                "Data saved successfully to " + pxtName + " and "
                    + pxtName.substring(0, pxtName.length() - ".pxt".length()) + ".pxb",
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            DialogUtils.showError(panel,
                "Error saving data: " + e.getMessage(),
                "Save Error");
        }
    }
}
