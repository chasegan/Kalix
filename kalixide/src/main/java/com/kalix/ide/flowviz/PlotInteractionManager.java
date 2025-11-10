package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.io.TimeSeriesCsvExporter;
import com.kalix.ide.io.KalixTimeSeriesWriter;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.preferences.PreferenceManager;
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
    private static final double MIN_ZOOM = 0.001;
    private static final double MAX_ZOOM = 1000.0;

    private final JComponent parentComponent;
    private final CoordinateDisplayManager coordinateDisplayManager;

    // State management
    private Point lastMousePos;
    private boolean isDragging = false;
    private boolean autoYMode = false;
    private JPopupMenu contextMenu;
    private JCheckBoxMenuItem autoYMenuItem;

    // Data access callbacks
    private Supplier<DataSet> dataSetSupplier;
    private Supplier<ViewPort> viewportSupplier;
    private Consumer<ViewPort> viewportUpdater;
    private Supplier<List<String>> visibleSeriesSupplier;
    private Supplier<Rectangle> plotAreaSupplier;
    private Supplier<Boolean> precision64Supplier;
    private Supplier<java.io.File> baseDirectorySupplier;
    private Supplier<com.kalix.ide.flowviz.transform.PlotType> plotTypeSupplier;

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
                               Supplier<List<String>> visibleSeriesSupplier,
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
                if (SwingUtilities.isRightMouseButton(e) && plotArea.contains(e.getPoint())) {
                    contextMenu.show(parentComponent, e.getX(), e.getY());
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
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isDragging = false;
                    parentComponent.setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (isDragging && lastMousePos != null) {
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
     * Calculates the visible Y range for a given time range (used in auto-Y mode).
     */
    private double[] calculateVisibleYRange(long startTime, long endTime) {
        DataSet dataSet = dataSetSupplier.get();
        List<String> visibleSeries = visibleSeriesSupplier.get();

        if (dataSet == null || dataSet.isEmpty() || visibleSeries.isEmpty()) {
            return new double[]{-1.0, 1.0}; // Default range
        }

        // Get current scale to filter invalid values
        ViewPort currentViewport = viewportSupplier.get();
        YAxisScale yAxisScale = currentViewport != null ? currentViewport.getYAxisScale() : YAxisScale.LINEAR;

        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        boolean hasValidData = false;

        // Check each visible series for data in the time range
        for (String seriesName : visibleSeries) {
            var series = dataSet.getSeries(seriesName);
            if (series == null) continue;

            long[] timestamps = series.getTimestamps();
            double[] values = series.getValues();
            boolean[] validPoints = series.getValidPoints();

            // Find data points within the time range
            for (int i = 0; i < timestamps.length; i++) {
                if (timestamps[i] >= startTime && timestamps[i] <= endTime && validPoints[i]) {
                    double value = values[i];

                    // Skip NaN and values invalid for current scale
                    if (Double.isNaN(value)) continue;
                    if (yAxisScale == YAxisScale.LOG && value <= 0) continue; // LOG requires positive values

                    minValue = Math.min(minValue, value);
                    maxValue = Math.max(maxValue, value);
                    hasValidData = true;
                }
            }
        }

        if (!hasValidData) {
            return new double[]{-1.0, 1.0}; // Default range when no data
        }

        // Clamp minimum value for log scale to prevent zooming too far out
        // Hydrological models often produce tiny values (e.g., 1e-12) that are meaningless
        // This only affects auto-zoom; manual zoom/pan can still access the full range
        double logScaleMin = PreferenceManager.getFileDouble(PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD, 0.001);
        if (yAxisScale == YAxisScale.LOG && minValue < logScaleMin) {
            minValue = logScaleMin;
        }

        // Add 5% padding appropriate for the current Y-axis scale
        double valueRange = maxValue - minValue;
        if (valueRange < 0.001) { // Very small range
            double center = (minValue + maxValue) / 2;
            minValue = center - 0.5;
            maxValue = center + 0.5;
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

        JMenuItem zoomToFitItem = new JMenuItem("Zoom to Fit");
        zoomToFitItem.addActionListener(e -> zoomToFit());
        contextMenu.add(zoomToFitItem);

        autoYMenuItem = new JCheckBoxMenuItem("Auto-Y");
        autoYMenuItem.addActionListener(e -> {
            autoYMode = autoYMenuItem.isSelected();
            // Update the parent PlotPanel's auto-Y mode if it has the method
            if (parentComponent instanceof PlotPanel) {
                ((PlotPanel) parentComponent).setAutoYMode(autoYMode);
            }
        });
        contextMenu.add(autoYMenuItem);

        contextMenu.addSeparator();

        JMenuItem saveDataItem = new JMenuItem("Save Data...");
        saveDataItem.addActionListener(e -> saveData());
        contextMenu.add(saveDataItem);

        // Add popup menu listener to update checkbox state when menu is shown
        contextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Sync the checkbox state with the current PlotPanel state
                if (parentComponent instanceof PlotPanel plotPanel) {
                    // Get the current auto-Y state from the PlotPanel
                    autoYMenuItem.setSelected(plotPanel.isAutoYMode());
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
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

        JFileChooser fileChooser = new JFileChooser();

        // Add file filters for different formats
        FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV Files (*.csv)", "csv");
        FileNameExtensionFilter kalixFilter = new FileNameExtensionFilter("Kalix Timeseries Files (*.kai)", "kai");

        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.addChoosableFileFilter(kalixFilter);
        fileChooser.setFileFilter(csvFilter); // Default to CSV

        fileChooser.setSelectedFile(new File("timeseries_data.csv"));

        // Set initial directory to model directory if available
        if (baseDirectorySupplier != null) {
            java.io.File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        int result = fileChooser.showSaveDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String fileName = file.getName().toLowerCase();

            // Determine format based on selected filter or file extension
            FileNameExtensionFilter selectedFilter = (FileNameExtensionFilter) fileChooser.getFileFilter();

            if (selectedFilter == kalixFilter || fileName.endsWith(".kai")) {
                // Save as Kalix format
                saveAsKalixFormat(file);
            } else {
                // Save as CSV format (default)
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
            TimeSeriesCsvExporter.export(dataSet, file, plotType);
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
     * Saves data in Kalix compressed format.
     */
    private void saveAsKalixFormat(File file) {
        String filePath = file.getAbsolutePath();

        // Remove .kai extension if present to get base path
        if (filePath.toLowerCase().endsWith(".kai")) {
            filePath = filePath.substring(0, filePath.length() - 4);
        }

        try {
            DataSet dataSet = dataSetSupplier.get();

            // Convert DataSet to List<TimeSeriesData>
            java.util.List<com.kalix.ide.flowviz.data.TimeSeriesData> seriesList =
                new java.util.ArrayList<>();

            for (String seriesName : dataSet.getSeriesNames()) {
                com.kalix.ide.flowviz.data.TimeSeriesData series = dataSet.getSeries(seriesName);
                if (series != null) {
                    seriesList.add(series);
                }
            }

            // Write to Kalix format
            KalixTimeSeriesWriter writer = new KalixTimeSeriesWriter();
            boolean use64BitPrecision = precision64Supplier != null ? precision64Supplier.get() : true;
            writer.writeToFile(filePath, seriesList, use64BitPrecision);

            JOptionPane.showMessageDialog(parentComponent,
                "Data saved successfully to " + new File(filePath + ".kai").getName() + " and " + new File(filePath + ".kaz").getName(),
                "Save Data",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parentComponent,
                "Error saving data: " + e.getMessage(),
                "Save Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Legacy method - kept for backward compatibility if needed elsewhere.
     */
    private void saveDataCsv() {
        DataSet dataSet = dataSetSupplier.get();
        if (dataSet == null || dataSet.isEmpty()) {
            JOptionPane.showMessageDialog(parentComponent, "No data to save.", "Save Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fileChooser.setSelectedFile(new File("timeseries_data.csv"));

        // Set initial directory to model directory if available
        if (baseDirectorySupplier != null) {
            java.io.File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        int result = fileChooser.showSaveDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }

            try {
                com.kalix.ide.flowviz.transform.PlotType plotType =
                    (plotTypeSupplier != null) ? plotTypeSupplier.get() : null;
                TimeSeriesCsvExporter.export(dataSet, file, plotType);
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
    }
}