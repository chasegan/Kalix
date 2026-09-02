package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.rendering.AxisLimitCodec;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.rendering.XAxisType;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.io.TimeSeriesCsvExporter;
import com.kalix.ide.io.SourceResCsvExporter;
import com.kalix.ide.io.SourceResCsvFormat;
import com.kalix.ide.io.PixieWriter;
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
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.preferences.PreferenceKeys;

/**
 * Manages all plot interaction functionality including mouse handling, zooming,
 * panning, and context menu operations.
 *
 * This class handles:
 * - Mouse wheel zooming with cursor positioning
 * - Click and drag panning
 * - Right-click context menu
 * - Auto-Y mode support
 * - Data export functionality
 */
public class PlotInteractionManager {

    // Zoom and pan constants
    private static final double ZOOM_FACTOR = UIConstants.Zoom.ZOOM_FACTOR;

    private final JComponent parentComponent;
    private final CoordinateDisplayManager coordinateDisplayManager;

    // State management
    private Point lastMousePos;
    private boolean isDragging = false;
    private JPopupMenu contextMenu;
    private JCheckBoxMenuItem autoYMenuItem;
    private JMenuItem pasteXAxisItem;
    private JMenuItem pasteYAxisItem;
    private JCheckBoxMenuItem connectGapsMenuItem;
    private JCheckBoxMenuItem orphanMarkersMenuItem;
    private JMenu yAxisScaleMenu;

    // Zoom rectangle selection state
    private boolean isZoomSelecting = false;
    private Point zoomRectStartPoint;
    private Point zoomRectCurrentPoint;

    // Data access callbacks
    private Supplier<DataSet> dataSetSupplier;
    private Supplier<ViewPort> viewportSupplier;
    private Consumer<ViewPort> viewportUpdater;
    private Supplier<List<com.kalix.ide.flowviz.data.SeriesRef>> visibleSeriesSupplier;
    private Supplier<Rectangle> plotAreaSupplier;
    private Supplier<Boolean> precision64Supplier;
    private Supplier<java.io.File> baseDirectorySupplier;
    private Supplier<com.kalix.ide.flowviz.transform.PlotType> plotTypeSupplier;
    private Supplier<com.kalix.ide.flowviz.data.LabelResolver> labelResolverSupplier;
    private BooleanSupplier autoYModeSupplier;


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
     * @param parentComponent The Swing component to handle interactions for and attach mouse listeners to
     * @param coordinateDisplayManager The coordinate display manager for integrated mouse position tracking
     */
    public PlotInteractionManager(JComponent parentComponent, CoordinateDisplayManager coordinateDisplayManager) {
        this.parentComponent = parentComponent;
        this.coordinateDisplayManager = coordinateDisplayManager;

        setupContextMenu();
    }

    /**
     * Sets up the data access callbacks for the manager to communicate with the parent plot component.
     *
     * <p>This method establishes the communication bridge between the interaction manager and
     * the parent plot panel by providing callback functions for accessing plot data, viewport
     * state, and plot area dimensions. This design allows the manager to operate independently
     * while still accessing necessary plot information.
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
     * Sets the plot type supplier for format-aware data export.
     * This allows the exporter to format data appropriately based on the plot type.
     *
     * @param plotTypeSupplier Supplier that returns the current plot type
     */
    public void setPlotTypeSupplier(Supplier<com.kalix.ide.flowviz.transform.PlotType> plotTypeSupplier) {
        this.plotTypeSupplier = plotTypeSupplier;
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
                    parentComponent.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    lastMousePos = e.getPoint();
                    isDragging = true;
                    parentComponent.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    // Double-click: reset zoom to fit all data
                    Rectangle plotArea = plotAreaSupplier.get();
                    if (plotArea.contains(e.getPoint())) {
                        zoomToFit();
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
                if (SwingUtilities.isLeftMouseButton(e) && isZoomSelecting) {
                    completeZoomRectSelection();
                    isZoomSelecting = false;
                    zoomRectStartPoint = null;
                    zoomRectCurrentPoint = null;
                    parentComponent.setCursor(Cursor.getDefaultCursor());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    isDragging = false;
                    parentComponent.setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (isZoomSelecting && zoomRectStartPoint != null) {
                    zoomRectCurrentPoint = new Point(e.getPoint());
                    parentComponent.repaint();
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

    /**
     * Supplies the panel's auto-Y mode, which decides whether X-only navigation refits
     * Y. Read on every use rather than mirrored, so undo/redo and every other path that
     * changes the mode on the panel are seen here without any synchronisation.
     */
    public void setAutoYModeSupplier(BooleanSupplier autoYModeSupplier) {
        this.autoYModeSupplier = autoYModeSupplier;
    }

    private boolean isAutoYMode() {
        return autoYModeSupplier.getAsBoolean();
    }

    /**
     * Fits the Y-axis to the data visible in the current X (time) range.
     * Keeps the X zoom unchanged.
     */
    public void fitYAxisToCurrentXRange() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        long startTime = currentViewport.getStartTimeMs();
        long endTime = currentViewport.getEndTimeMs();
        updateViewportWithFittedY(startTime, endTime);
        parentComponent.repaint();
    }

    /**
     * Helper method: Updates viewport with specified time range and Y fitted to visible data.
     * Used by Auto-Y mode zoom operations.
     */
    private void updateViewportWithFittedY(long startTime, long endTime) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        // Calculate Y range for visible data in the time range
        double[] yRange = calculateVisibleYRange(startTime, endTime);

        // Create new viewport (preserve XAxisType for exceedance plots)
        Rectangle plotArea = plotAreaSupplier.get();
        ViewPort newViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                          plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                                          currentViewport.getYAxisScale(), currentViewport.getXAxisType());
        viewportUpdater.accept(newViewport);
    }

    /**
     * Zooms in at the center of the plot area.
     */
    public void zoomIn() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        if (isAutoYMode()) {
            // Auto-Y mode: only zoom X-axis, then auto-fit Y
            long centerTime = (currentViewport.getStartTimeMs() + currentViewport.getEndTimeMs()) / 2;
            long timeRange = currentViewport.getTimeRangeMs();
            long newTimeRange = (long) (timeRange / ZOOM_FACTOR);

            long startTime = centerTime - newTimeRange / 2;
            long endTime = centerTime + newTimeRange / 2;

            updateViewportWithFittedY(startTime, endTime);
        } else {
            // Standard zoom: zoom both axes
            long centerTime = (currentViewport.getStartTimeMs() + currentViewport.getEndTimeMs()) / 2;
            double centerValue = (currentViewport.getMinValue() + currentViewport.getMaxValue()) / 2;

            ViewPort newViewport = currentViewport.zoom(ZOOM_FACTOR, centerTime, centerValue);
            viewportUpdater.accept(newViewport);
        }
        parentComponent.repaint();
    }

    /**
     * Zooms out from the center of the plot area.
     */
    public void zoomOut() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        if (isAutoYMode()) {
            // Auto-Y mode: only zoom X-axis, then auto-fit Y
            long centerTime = (currentViewport.getStartTimeMs() + currentViewport.getEndTimeMs()) / 2;
            long timeRange = currentViewport.getTimeRangeMs();
            long newTimeRange = (long) (timeRange * ZOOM_FACTOR);

            long startTime = centerTime - newTimeRange / 2;
            long endTime = centerTime + newTimeRange / 2;

            updateViewportWithFittedY(startTime, endTime);
        } else {
            // Standard zoom: zoom both axes
            long centerTime = (currentViewport.getStartTimeMs() + currentViewport.getEndTimeMs()) / 2;
            double centerValue = (currentViewport.getMinValue() + currentViewport.getMaxValue()) / 2;

            ViewPort newViewport = currentViewport.zoom(1.0 / ZOOM_FACTOR, centerTime, centerValue);
            viewportUpdater.accept(newViewport);
        }
        parentComponent.repaint();
    }

    /**
     * Zooms to fit all data in the plot area.
     */
    public void zoomToFit() {
        // This will be implemented by calling back to the parent component
        // as it requires access to the full data fitting logic
        if (parentComponent instanceof PlotPanel) {
            ((PlotPanel) parentComponent).zoomToFit();
        }
    }

    /**
     * Handles mouse wheel zoom events.
     * Ctrl+Scroll (Windows/Linux) or Cmd+Scroll (Mac): Y-axis only
     * Scroll alone: Both axes
     */
    private void handleZoom(MouseWheelEvent e) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        // Use smaller zoom factor for mouse wheel (less sensitive)
        double wheelZoomFactor = Math.pow(1.1, -e.getWheelRotation());

        // Check for modifier keys: Ctrl (Windows/Linux) or Cmd (Mac)
        boolean isYAxisOnlyZoom = e.isControlDown() || e.isMetaDown();

        if (isAutoYMode()) {
            // Auto-Y mode: only zoom X-axis centered on mouse, then auto-fit Y
            long mouseTime = currentViewport.screenXToTime(e.getX());
            long currentStartTime = currentViewport.getStartTimeMs();
            long currentEndTime = currentViewport.getEndTimeMs();
            long currentTimeRange = currentEndTime - currentStartTime;

            // Calculate new time range centered on mouse
            long newTimeRange = (long) (currentTimeRange / wheelZoomFactor);
            double mouseRatio = (double) (mouseTime - currentStartTime) / currentTimeRange;
            long startTime = mouseTime - (long) (newTimeRange * mouseRatio);
            long endTime = startTime + newTimeRange;

            updateViewportWithFittedY(startTime, endTime);
        } else if (isYAxisOnlyZoom) {
            // Ctrl/Cmd+Scroll: Y-axis only zoom centered on mouse Y position
            double mouseValue = currentViewport.screenYToValue(e.getY());

            // Keep time range unchanged
            long startTime = currentViewport.getStartTimeMs();
            long endTime = currentViewport.getEndTimeMs();

            // Zoom Y-axis in transformed space to keep mouse point stationary
            double transformedMouseValue = currentViewport.getYAxisScale().transform(mouseValue);
            double transformedMin = currentViewport.getTransformedMin();
            double transformedMax = currentViewport.getTransformedMax();
            double transformedRange = transformedMax - transformedMin;

            // Calculate new transformed range
            double newTransformedRange = transformedRange / wheelZoomFactor;

            // Center transformed range on mouse position in transformed space
            double mouseTransformedRatio = (transformedMouseValue - transformedMin) / transformedRange;
            double newTransformedMin = transformedMouseValue - (newTransformedRange * mouseTransformedRatio);
            double newTransformedMax = newTransformedMin + newTransformedRange;

            // Inverse transform back to data space
            double minValue = currentViewport.getYAxisScale().inverseTransform(newTransformedMin);
            double maxValue = currentViewport.getYAxisScale().inverseTransform(newTransformedMax);

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                                              currentViewport.getYAxisScale(), currentViewport.getXAxisType());
            viewportUpdater.accept(newViewport);
        } else {
            // Standard zoom: zoom both axes centered on mouse position
            long mouseTime = currentViewport.screenXToTime(e.getX());
            double mouseValue = currentViewport.screenYToValue(e.getY());

            // Get current ranges (time in data space, as it's always linear)
            long currentStartTime = currentViewport.getStartTimeMs();
            long currentEndTime = currentViewport.getEndTimeMs();
            long currentTimeRange = currentEndTime - currentStartTime;

            // Calculate new time range
            long newTimeRange = (long) (currentTimeRange / wheelZoomFactor);

            // Center time range on mouse position (linear, so data space is fine)
            double mouseTimeRatio = (double) (mouseTime - currentStartTime) / currentTimeRange;
            long startTime = mouseTime - (long) (newTimeRange * mouseTimeRatio);
            long endTime = startTime + newTimeRange;

            // For Y-axis: work in TRANSFORMED space to keep mouse point stationary
            double transformedMouseValue = currentViewport.getYAxisScale().transform(mouseValue);
            double transformedMin = currentViewport.getTransformedMin();
            double transformedMax = currentViewport.getTransformedMax();
            double transformedRange = transformedMax - transformedMin;

            // Calculate new transformed range
            double newTransformedRange = transformedRange / wheelZoomFactor;

            // Center transformed range on mouse position in transformed space
            double mouseTransformedRatio = (transformedMouseValue - transformedMin) / transformedRange;
            double newTransformedMin = transformedMouseValue - (newTransformedRange * mouseTransformedRatio);
            double newTransformedMax = newTransformedMin + newTransformedRange;

            // Inverse transform back to data space
            double minValue = currentViewport.getYAxisScale().inverseTransform(newTransformedMin);
            double maxValue = currentViewport.getYAxisScale().inverseTransform(newTransformedMax);

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                                              currentViewport.getYAxisScale(), currentViewport.getXAxisType());
            viewportUpdater.accept(newViewport);
        }

        parentComponent.repaint();
    }

    /**
     * Handles mouse drag panning events.
     */
    private void handlePan(MouseEvent e) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        int dx = e.getX() - lastMousePos.x;
        int dy = e.getY() - lastMousePos.y;

        if (isAutoYMode()) {
            // Auto-Y mode: only pan X-axis, then auto-fit Y
            long timeRange = currentViewport.getTimeRangeMs();
            long deltaTime = -dx * timeRange / currentViewport.getPlotWidth();

            long newStartTime = currentViewport.getStartTimeMs() + deltaTime;
            long newEndTime = currentViewport.getEndTimeMs() + deltaTime;

            updateViewportWithFittedY(newStartTime, newEndTime);
        } else {
            // Standard pan: pan both axes using pixel-based panning for correct non-linear scale behavior
            ViewPort newViewport = currentViewport.panByPixels(dx, dy);
            viewportUpdater.accept(newViewport);
        }

        lastMousePos = e.getPoint();
        parentComponent.repaint();
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

        // Always zoom both axes — ignores auto-Y so the user can "peek" at a region.
        // Subsequent pan or scroll-zoom will snap Y back via auto-Y.
        double maxValue = currentViewport.screenYToValue(topY);   // top of screen = max value
        double minValue = currentViewport.screenYToValue(bottomY); // bottom of screen = min value

        Rectangle plotArea = plotAreaSupplier.get();
        ViewPort newViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                currentViewport.getYAxisScale(), currentViewport.getXAxisType());
        viewportUpdater.accept(newViewport);

        parentComponent.repaint();
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
     * Calculates the visible Y range for a given time range (used in auto-Y mode).
     */
    private double[] calculateVisibleYRange(long startTime, long endTime) {
        DataSet dataSet = dataSetSupplier.get();
        List<com.kalix.ide.flowviz.data.SeriesRef> visibleSeries = visibleSeriesSupplier.get();

        if (dataSet == null || dataSet.isEmpty() || visibleSeries.isEmpty()) {
            return new double[]{-1.0, 1.0}; // Default range
        }

        // Get current scale to filter invalid values
        ViewPort currentViewport = viewportSupplier.get();
        YAxisScale yAxisScale = currentViewport != null ? currentViewport.getYAxisScale() : YAxisScale.LINEAR;

        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        boolean hasValidData = false;

        // Check each visible series for data in the time range. This runs on EVERY
        // mouse event while panning with auto-Y (the default mode), so the scan must
        // be bounded by getIndexRange - O(1) on regular grids, O(log n) otherwise.
        // A full-array scan here made pan cost proportional to TOTAL points, which is
        // exactly where the "millions of points smoothly" promise died.
        for (com.kalix.ide.flowviz.data.SeriesRef ref : visibleSeries) {
            var series = dataSet.getSeries(ref);
            if (series == null) continue;

            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();
            var range = series.getIndexRange(startTime, endTime);

            for (int i = range.startIndex; i < range.endIndex; i++) {
                if (!validPoints[i]) continue;
                double value = values[i];

                // Skip NaN and values invalid for current scale
                if (Double.isNaN(value)) continue;
                if (yAxisScale == YAxisScale.LOG && value <= 0) continue; // LOG requires positive values

                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
                hasValidData = true;
            }
        }

        if (!hasValidData) {
            return new double[]{-1.0, 1.0}; // Default range when no data
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
        contextMenu.show(parentComponent, e.getX(), e.getY());
    }

    /**
     * Whether this panel is actually plotting something. Every item on the context menu --
     * zoom to fit, axis limits, axis copy/paste, save data -- acts on plotted series, so on
     * an empty tab the menu is a list of no-ops and error dialogs.
     *
     * <p>The evidence is data in the display set for a visible series, not the viewport
     * and not the selection: {@code PlotPanel} synthesises a placeholder viewport
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
     * context-menu-style 4, Paste with an empty clipboard is shown disabled rather than
     * hidden: the user should know the command exists. A clipboard held by another
     * application counts as empty.
     */
    private static boolean hasClipboardText() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    /**
     * Sets up the right-click context menu.
     */
    private void setupContextMenu() {
        contextMenu = new JPopupMenu();

        // Primary block (context-menu-style 1, block 1): the default action, which on a plot
        // is what double-click does (see the mouse listener). The skeleton's table lists
        // "Zoom to fit" under block 7 for panels where it is merely a view command; here
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

        // Clipboard block (block 3). The copy/paste items act immediately and carry no
        // ellipsis (2.4).
        JMenuItem copyXAxis = new JMenuItem("Copy X axis");

        // Copy/paste go through AxisLimitCodec -- the same codec the Set-axis-limits
        // dialog uses -- so the clipboard always carries the axis' own units.
        copyXAxis.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            if (currentViewport != null) {
                copyStringToClipboard(AxisLimitCodec.formatXLimits(currentViewport));
            }
        });
        contextMenu.add(copyXAxis);
        pasteXAxisItem = new JMenuItem("Paste X axis");
        pasteXAxisItem.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            String text = readClipboardText();
            if (currentViewport == null || text == null) {
                return;
            }
            long[] x;
            try {
                x = AxisLimitCodec.parseXLimits(text, currentViewport.getXAxisType());
                ViewPort.validateBounds(x[0], x[1], currentViewport.getMinValue(), currentViewport.getMaxValue());
            } catch (IllegalArgumentException ex) {
                showInvalidInput(parentComponent, ex.getMessage());
                return;
            }
            applyXLimits(x[0], x[1]);
        });
        contextMenu.add(pasteXAxisItem);

        JMenuItem copyYAxis = new JMenuItem("Copy Y axis");
        copyYAxis.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            if (currentViewport != null) {
                copyStringToClipboard(AxisLimitCodec.formatYLimits(currentViewport));
            }
        });
        contextMenu.add(copyYAxis);
        pasteYAxisItem = new JMenuItem("Paste Y axis");
        pasteYAxisItem.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            String text = readClipboardText();
            if (currentViewport == null || text == null) {
                return;
            }
            double[] y;
            try {
                y = AxisLimitCodec.parseYLimits(text);
                ViewPort.validateBounds(currentViewport.getStartTimeMs(), currentViewport.getEndTimeMs(), y[0], y[1]);
            } catch (IllegalArgumentException ex) {
                showInvalidInput(parentComponent, ex.getMessage());
                return;
            }
            applyExplicitLimits(currentViewport.getStartTimeMs(), currentViewport.getEndTimeMs(), y[0], y[1]);
        });
        contextMenu.add(pasteYAxisItem);

        contextMenu.addSeparator();

        // View/state block (block 7): everything that changes how the data is shown, never
        // the data. "Set axis limits…" carries an ellipsis because it opens a dialog (2.4);
        // the submenus are category nouns with value children (6).
        JMenuItem setAxes = new JMenuItem("Set axis limits…");
        setAxes.addActionListener(e1 -> showSetAxesDialog());
        contextMenu.add(setAxes);

        autoYMenuItem = new JCheckBoxMenuItem("Auto-scale Y axis");
        autoYMenuItem.addActionListener(e -> {
            if (parentComponent instanceof PlotPanel plotPanel) {
                plotPanel.setAutoYMode(autoYMenuItem.isSelected());
            }
        });
        contextMenu.add(autoYMenuItem);

        // Y-axis scale submenu
        yAxisScaleMenu = new JMenu("Y-axis scale");
        ButtonGroup yAxisScaleGroup = new ButtonGroup();
        for (YAxisScale scale : YAxisScale.values()) {
            JRadioButtonMenuItem scaleItem = new JRadioButtonMenuItem(scale.getDisplayName());
            scaleItem.addActionListener(e -> {
                if (parentComponent instanceof PlotPanel) {
                    ((PlotPanel) parentComponent).setYAxisScale(scale);
                }
            });
            yAxisScaleGroup.add(scaleItem);
            yAxisScaleMenu.add(scaleItem);
        }
        contextMenu.add(yAxisScaleMenu);

        // Missing Data submenu. "Draw across gaps" and "Mark orphan points" are mutually
        // exclusive — drawing a continuous line removes the gaps that orphan points would mark —
        // but either may be off. They are checkboxes (mutual exclusion enforced in PlotPanel)
        // rather than a radio group, which could not express the "neither selected" state.
        JMenu missingDataMenu = new JMenu("Missing data");

        connectGapsMenuItem = new JCheckBoxMenuItem("Draw across gaps");
        connectGapsMenuItem.addActionListener(e -> {
            if (parentComponent instanceof PlotPanel plotPanel) {
                plotPanel.setConnectAcrossGaps(connectGapsMenuItem.isSelected());
            }
        });
        missingDataMenu.add(connectGapsMenuItem);

        orphanMarkersMenuItem = new JCheckBoxMenuItem("Mark orphan points");
        orphanMarkersMenuItem.addActionListener(e -> {
            if (parentComponent instanceof PlotPanel plotPanel) {
                plotPanel.setShowOrphanMarkers(orphanMarkersMenuItem.isSelected());
            }
        });
        missingDataMenu.add(orphanMarkersMenuItem);

        contextMenu.add(missingDataMenu);

        // Add popup menu listener to update checkbox/radio button states when menu is shown
        contextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean clipboardHasText = hasClipboardText();
                pasteXAxisItem.setEnabled(clipboardHasText);
                pasteYAxisItem.setEnabled(clipboardHasText);

                // Sync the checkbox state with the current PlotPanel state
                if (parentComponent instanceof PlotPanel plotPanel) {
                    // Get the current auto-Y state from the PlotPanel
                    autoYMenuItem.setSelected(plotPanel.isAutoYMode());

                    // Sync gap-handling toggles
                    connectGapsMenuItem.setSelected(plotPanel.isConnectAcrossGaps());
                    orphanMarkersMenuItem.setSelected(plotPanel.isShowOrphanMarkers());

                    // Get the current Y-axis scale and select the corresponding radio button
                    YAxisScale currentScale = plotPanel.getYAxisScale();
                    for (int i = 0; i < yAxisScaleMenu.getItemCount(); i++) {
                        JMenuItem item = yAxisScaleMenu.getItem(i);
                        if (item instanceof JRadioButtonMenuItem radioItem) {
                            radioItem.setSelected(radioItem.getText().equals(currentScale.getDisplayName()));
                        }
                    }
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
    }

    /**
     * Puts a string on the system clipboard.
     */
    private void copyStringToClipboard(String formatted) {
        StringSelection selection = new StringSelection(formatted);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        } catch (IllegalStateException ex) {
            showInvalidInput(parentComponent, "The clipboard is not available right now.");
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
        } catch (UnsupportedFlavorException | IOException | IllegalStateException ex) {
            showInvalidInput(parentComponent, "The clipboard does not contain text.");
            return null;
        }
    }

    /** One error dialog for every rejected axis limit, whichever path it arrived by. */
    private static void showInvalidInput(java.awt.Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Invalid input", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Applies an X-only change: obeys auto-Y exactly as wheel zoom and drag pan do, so a
     * pasted time window shows its own data rather than the previous window's Y range.
     */
    private void applyXLimits(long startTime, long endTime) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) {
            return;
        }
        if (isAutoYMode()) {
            updateViewportWithFittedY(startTime, endTime);
            parentComponent.repaint();
        } else {
            acceptNewAxes(startTime, endTime, currentViewport.getMinValue(), currentViewport.getMaxValue());
        }
    }

    /**
     * Applies limits that include an explicit Y range: the user has opted out of auto-Y,
     * otherwise the next wheel tick or drag would silently refit Y and discard them. The
     * viewport lands first and the mode second, because {@code setAutoYMode} pushes a
     * history entry immediately while the viewport update is coalesced -- this order
     * records the two together, so undo restores both in one step.
     */
    private void applyExplicitLimits(long startTime, long endTime, double minValue, double maxValue) {
        acceptNewAxes(startTime, endTime, minValue, maxValue);
        if (parentComponent instanceof PlotPanel plotPanel) {
            plotPanel.setAutoYMode(false);
        }
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

        JTextField xField = new JTextField(AxisLimitCodec.formatXLimits(currentViewport), 32);
        JTextField yField = new JTextField(AxisLimitCodec.formatYLimits(currentViewport), 32);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        addAxisFieldRow(form, gbc, 0, "X limits:", xField);
        addAxisFieldRow(form, gbc, 1, "Y limits:", yField);

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parentComponent),
            "Set axis limits", Dialog.ModalityType.APPLICATION_MODAL);  // sentence case (2.1)
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());
        dialog.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(ev -> {
            try {
                long[] x = AxisLimitCodec.parseXLimits(xField.getText(), xAxisType);
                double[] y = AxisLimitCodec.parseYLimits(yField.getText());
                ViewPort.validateBounds(x[0], x[1], y[0], y[1]);
                // An edited Y field is an explicit Y range; an untouched one (the codec's
                // formatting round-trips exactly) means the user only wanted X, which obeys
                // auto-Y like any other X-only change.
                boolean yEdited = y[0] != currentViewport.getMinValue() || y[1] != currentViewport.getMaxValue();
                if (yEdited) {
                    applyExplicitLimits(x[0], x[1], y[0], y[1]);
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
        dialog.setLocationRelativeTo(parentComponent);
        dialog.setVisible(true);
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
        Rectangle plotArea = plotAreaSupplier.get();
        ViewPort newViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                            // vvv Window itself does not move or change scale/type vvv
                                            plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                                            currentViewport.getYAxisScale(), currentViewport.getXAxisType());
        viewportUpdater.accept(newViewport);
        parentComponent.repaint();
    }

    /**
     * Displays a file save dialog with multiple format options and exports based on selected file extension.
     */
    public void saveData() {
        DataSet dataSet = dataSetSupplier.get();
        if (dataSet == null || dataSet.isEmpty()) {
            JOptionPane.showMessageDialog(parentComponent, "No data to save.", "Save Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // The suggested name carries the default format's extension; whatever the user
        // settles on is taken verbatim, and saveAs*Format normalises it from there.
        java.util.Optional<File> chosen = KalixFileDialog.saveFile(parentComponent)
            .title("Save Data")
            .startIn(baseDirectorySupplier != null ? baseDirectorySupplier.get() : null)
            .suggestedName("timeseries_data.csv")
            .filters(
                FileDialogFilter.of("CSV Files (*.csv)", "csv"),
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
        // Ensure .csv extension
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getAbsolutePath() + ".csv");
        }

        try {
            DataSet dataSet = dataSetSupplier.get();
            com.kalix.ide.flowviz.transform.PlotType plotType =
                (plotTypeSupplier != null) ? plotTypeSupplier.get() : null;
            com.kalix.ide.flowviz.data.LabelResolver labelResolver =
                (labelResolverSupplier != null) ? labelResolverSupplier.get() : null;
            TimeSeriesCsvExporter.export(dataSet, file, plotType, labelResolver);
            JOptionPane.showMessageDialog(parentComponent,
                "Data saved successfully to " + file.getName(),
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parentComponent,
                "Error saving data: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(parentComponent,
                "Invalid data: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE);
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
            JOptionPane.showMessageDialog(parentComponent,
                "Data saved successfully to " + file.getName(),
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parentComponent,
                "Error saving data: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(parentComponent,
                "Invalid data: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Saves data in Pixie format.
     */
    private void saveAsPixieFormat(File file) {
        String filePath = file.getAbsolutePath();

        // Remove .pxt extension if present to get base path
        if (filePath.toLowerCase().endsWith(".pxt")) {
            filePath = filePath.substring(0, filePath.length() - 4);
        }

        try {
            DataSet dataSet = dataSetSupplier.get();
            com.kalix.ide.flowviz.data.LabelResolver labelResolver =
                (labelResolverSupplier != null) ? labelResolverSupplier.get() : null;

            // Build (name, data) pairs — the .pxt metadata needs a series name, taken
            // from the ref's projected label.
            java.util.List<com.kalix.ide.io.NamedSeries> seriesList = new java.util.ArrayList<>();
            for (com.kalix.ide.flowviz.data.SeriesRef ref : dataSet.getSeriesRefs()) {
                com.kalix.ide.flowviz.data.TimeSeriesData series = dataSet.getSeries(ref);
                if (series != null) {
                    String name = labelResolver != null ? labelResolver.labelFor(ref) : String.valueOf(ref);
                    seriesList.add(new com.kalix.ide.io.NamedSeries(name, series));
                }
            }

            // Write to Pixie format
            PixieWriter writer = new PixieWriter();
            boolean use64BitPrecision = precision64Supplier != null ? precision64Supplier.get() : true;
            writer.writeToFile(filePath, seriesList, use64BitPrecision);

            JOptionPane.showMessageDialog(parentComponent,
                "Data saved successfully to " + new File(filePath + ".pxt").getName() + " and " + new File(filePath + ".pxb").getName(),
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parentComponent,
                "Error saving data: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }
}
