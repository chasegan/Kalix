package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.rendering.XAxisType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.io.TimeSeriesCsvExporter;
import com.kalix.ide.io.SourceResCsvExporter;
import com.kalix.ide.io.SourceResCsvFormat;
import com.kalix.ide.io.PixieWriter;
import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.utils.TimeFormatUtil;

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
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.List;
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
    private boolean autoYMode = false;
    private JPopupMenu contextMenu;
    private JCheckBoxMenuItem autoYMenuItem;
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
                    contextMenu.show(parentComponent, e.getX(), e.getY());
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
                    contextMenu.show(parentComponent, e.getX(), e.getY());
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
     * Sets the auto-Y mode for zooming and panning operations.
     */
    public void setAutoYMode(boolean autoYMode) {
        this.autoYMode = autoYMode;
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

        if (autoYMode) {
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

        if (autoYMode) {
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

        if (autoYMode) {
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

        if (autoYMode) {
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
     * Sets up the right-click context menu.
     */
    private void setupContextMenu() {
        contextMenu = new JPopupMenu();

        JMenuItem zoomToFitItem = new JMenuItem("Zoom to fit");
        zoomToFitItem.addActionListener(e -> zoomToFit());
        contextMenu.add(zoomToFitItem);

        autoYMenuItem = new JCheckBoxMenuItem("Auto-scale Y axis");
        autoYMenuItem.addActionListener(e -> {
            autoYMode = autoYMenuItem.isSelected();
            // Update the parent PlotPanel's auto-Y mode if it has the method
            if (parentComponent instanceof PlotPanel) {
                ((PlotPanel) parentComponent).setAutoYMode(autoYMode);
            }
        });
        contextMenu.add(autoYMenuItem);

        contextMenu.addSeparator();

        JMenuItem setAxes = new JMenuItem("Set axis limits");
        setAxes.addActionListener(e1 -> showSetAxesDialog());
        contextMenu.add(setAxes);

        // Copy/paste go through formatXValue/parseX -- the same pair the Set-axis-limits
        // dialog uses -- so the clipboard always carries the axis' own units. X bounds are
        // real timestamps only on a TIME axis; on the others they are encoded values
        // (percentile x 1e6, numeric x NUMERIC_SCALE) that formatting as a date would
        // render as meaningless 1970 instants.
        JMenuItem copyXAxis = new JMenuItem("Copy X axis");
        copyXAxis.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            if (currentViewport == null) {
                return;
            }
            XAxisType xAxisType = currentViewport.getXAxisType();
            copyBoundsToClipboard(
                formatXValue(currentViewport.getStartTimeMs(), xAxisType),
                formatXValue(currentViewport.getEndTimeMs(), xAxisType)
            );
        });
        contextMenu.add(copyXAxis);
        JMenuItem pasteXAxis = new JMenuItem("Paste X axis");
        pasteXAxis.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            if (currentViewport == null) {
                return;
            }
            String[] parts = readBoundsFromClipboard("values");
            if (parts == null) {
                return;
            }

            long startTime;
            long endTime;
            try {
                // bounds-safe owing to the pair check in readBoundsFromClipboard
                XAxisType xAxisType = currentViewport.getXAxisType();
                startTime = parseX(parts[0], xAxisType);
                endTime = parseX(parts[1], xAxisType);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(
                    parentComponent, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            acceptNewAxes(
                startTime, endTime,
                currentViewport.getMinValue(), currentViewport.getMaxValue()
            );
        });
        contextMenu.add(pasteXAxis);

        JMenuItem copyYAxis = new JMenuItem("Copy Y axis");
        copyYAxis.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            if (currentViewport == null) {
                return;
            }
            var minVal = currentViewport.getMinValue();
            var maxVal = currentViewport.getMaxValue();
            copyBoundsToClipboard(String.valueOf(minVal), String.valueOf(maxVal));
        });
        contextMenu.add(copyYAxis);
        JMenuItem pasteYAxis = new JMenuItem("Paste Y axis");
        pasteYAxis.addActionListener(e -> {
            ViewPort currentViewport = viewportSupplier.get();
            if (currentViewport == null) {
                return;
            }
            String[] parts = readBoundsFromClipboard("numbers");
            if (parts == null) {
                return;
            }

            double minVal;
            double maxVal;
            try {
                // bounds-safe owing to the pair check in readBoundsFromClipboard
                minVal = parseY(parts[0]);
                maxVal = parseY(parts[1]);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(
                    parentComponent, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            acceptNewAxes(
                currentViewport.getStartTimeMs(), currentViewport.getEndTimeMs(),
                minVal, maxVal
            );
        });
        contextMenu.add(pasteYAxis);

        contextMenu.addSeparator();

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

        contextMenu.addSeparator();

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

        contextMenu.addSeparator();

        JMenuItem saveDataItem = new JMenuItem("Save data…");
        saveDataItem.addActionListener(e -> saveData());
        contextMenu.add(saveDataItem);

        // Add popup menu listener to update checkbox/radio button states when menu is shown
        contextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
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
     * Puts an axis' bounds on the system clipboard as {@code "lower, upper"} -- the format
     * {@link #readBoundsFromClipboard} reads back, and one a user can equally well type by
     * hand or paste in from elsewhere.
     *
     * @param lowerFormatted the axis minimum, already formatted in the axis' own units
     * @param upperFormatted the axis maximum, likewise
     */
    private static void copyBoundsToClipboard(String lowerFormatted, String upperFormatted) {
        StringSelection selection = new StringSelection(lowerFormatted + ", " + upperFormatted);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, selection);
    }

    /**
     * Reads a {@code "lower, upper"} pair off the clipboard, the inverse of
     * {@link #copyBoundsToClipboard}. Returns the two trimmed halves, or {@code null} when
     * the clipboard is unreadable or does not hold exactly two comma-separated values --
     * having reported that to the user, so callers need only bail out.
     *
     * @param expected what the halves should look like, for the error message ("values",
     *                 "numbers") -- the units differ per axis and per X-axis type
     */
    private String[] readBoundsFromClipboard(String expected) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        String clipboardString;
        try {
            clipboardString = (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException ex) {
            JOptionPane.showMessageDialog(parentComponent, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        String[] parts = clipboardString.split("\\s*,\\s*");
        if (parts.length != 2) {
            JOptionPane.showMessageDialog(
                parentComponent,
                String.format("Expected two comma-separated %s but received \"%s\"", expected, clipboardString),
                "Error", JOptionPane.ERROR_MESSAGE
            );
            return null;
        }
        return new String[]{parts[0].trim(), parts[1].trim()};
    }

    /**
     * Shows a modal dialog with the current axis limits, pre-filled and editable as text.
     * A blank field leaves that limit unchanged (see {@link #acceptNewAxes}); X fields are
     * parsed according to the viewport's {@link XAxisType} (dates for TIME, percentages for
     * PERCENTILE, etc.) so the field always shows and accepts values in the axis' own units.
     */
    private void showSetAxesDialog() {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) {
            return;
        }
        XAxisType xAxisType = currentViewport.getXAxisType();

        JTextField xMinField = new JTextField(formatXValue(currentViewport.getStartTimeMs(), xAxisType), 16);
        JTextField xMaxField = new JTextField(formatXValue(currentViewport.getEndTimeMs(), xAxisType), 16);
        JTextField yMinField = new JTextField(formatDoubleForField(currentViewport.getMinValue()), 16);
        JTextField yMaxField = new JTextField(formatDoubleForField(currentViewport.getMaxValue()), 16);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        addAxisFieldRow(form, gbc, 0, "X min:", xMinField);
        addAxisFieldRow(form, gbc, 1, "X max:", xMaxField);
        addAxisFieldRow(form, gbc, 2, "Y min:", yMinField);
        addAxisFieldRow(form, gbc, 3, "Y max:", yMaxField);

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parentComponent),
            "Set Axis Limits", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout());
        dialog.add(form, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(ev -> {
            try {
                Long   startTime = parseX(xMinField.getText(), xAxisType);
                Long   endTime   = parseX(xMaxField.getText(), xAxisType);
                Double minValue  = parseY(yMinField.getText());
                Double maxValue  = parseY(yMaxField.getText());

                if (startTime >= endTime) {
                    throw new IllegalArgumentException("X min must be less than X max.");
                }
                if (minValue >= maxValue) {
                    throw new IllegalArgumentException("Y min must be less than Y max.");
                }

                acceptNewAxes(startTime, endTime, minValue, maxValue);
                dialog.dispose();
            } catch (DateTimeParseException | NumberFormatException parseEx) {
                JOptionPane.showMessageDialog(dialog, "Could not parse axis limits: " + parseEx.getMessage(),
                    "Invalid input", JOptionPane.ERROR_MESSAGE);
            } catch (IllegalArgumentException rangeEx) {
                JOptionPane.showMessageDialog(dialog, rangeEx.getMessage(),
                    "Invalid input", JOptionPane.ERROR_MESSAGE);
            }
        });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(ev -> dialog.dispose());

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(okButton);

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
     * Parses a field's text into a viewport X value (a real timestamp for TIME, or one of the
     * fake-timestamp encodings used for the other axis types — see {@link XAxisType}).
     */
    private Long parseX(String text, XAxisType xAxisType) throws IllegalArgumentException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("X value cannot be blank.");
        }
        String trimmed = text.trim();
        switch (xAxisType) {
            case PERCENTILE:
                String stripped = trimmed.endsWith("%") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
                try {
                    return Math.round(Double.parseDouble(stripped) * 1_000_000.0);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Not a valid percentile: \"" + trimmed + "\"");
                }
            case COUNT:
                try {
                    return Long.parseLong(trimmed);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Not a valid integer count: \"" + trimmed + "\"");
                }
            case NUMERIC:
                try {
                    return Math.round(Double.parseDouble(trimmed) * PlotTypeTransformer.NUMERIC_SCALE);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Not a valid number: \"" + trimmed + "\"");
                }
            case TIME:
            default:
                try {
                    return TimeFormatUtil.parseFlexible(trimmed);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Not a valid date/time: \"" + trimmed + "\" (expected yyyy-MM-dd or yyyy-MM-dd HH:mm:ss)");
                }
        }
    }

    /**
     * Parses a field's text into a (double) Y value.
     */
    private Double parseY(String text) throws IllegalArgumentException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Y value cannot be blank.");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid number: \"" + text.trim() + "\"");
        }
    }

    /**
     * Formats a viewport X value for editing, in the units matching {@code xAxisType}
     * (the inverse of {@link #parseX}).
     */
    private String formatXValue(long value, XAxisType xAxisType) {
        return switch (xAxisType) {
            case PERCENTILE -> formatDoubleForField(value / 1_000_000.0) + "%";
            case COUNT -> String.valueOf(value);
            case NUMERIC -> formatDoubleForField((double) value / PlotTypeTransformer.NUMERIC_SCALE);
            default -> {
                // Date-only for midnight-aligned timestamps, full ISO datetime otherwise.
                //                          ms per day          s per day
                long stepSeconds = (value % 86_400_000L == 0) ? 86_400L : 1L;
                yield TimeFormatUtil.formatForStepSize(value, stepSeconds);
            }
        };
    }

    /**
     * Formats a double for editing without scientific notation or spurious ".0" noise.
     */
    private String formatDoubleForField(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)
                && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * Moves the viewport to the given axis limits, keeping plot area, Y-axis scale, and
     * X-axis type unchanged. Limits must already be resolved (no "leave unchanged" fallback
     * here) — see {@link #showSetAxesDialog}, which resolves blank fields against the current
     * viewport before calling this.
     */
    private void acceptNewAxes(Long startTime, Long endTime, Double minValue, Double maxValue) {
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