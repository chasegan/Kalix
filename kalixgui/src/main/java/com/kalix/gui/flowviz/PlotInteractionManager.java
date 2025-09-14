package com.kalix.gui.flowviz;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.rendering.ViewPort;
import com.kalix.gui.io.TimeSeriesCsvExporter;
import com.kalix.gui.io.KalixTimeSeriesWriter;

import javax.swing.*;
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
    private static final double ZOOM_FACTOR = 1.2;
    private static final double MIN_ZOOM = 0.001;
    private static final double MAX_ZOOM = 1000.0;

    private final JComponent parentComponent;
    private final CoordinateDisplayManager coordinateDisplayManager;

    // State management
    private Point lastMousePos;
    private boolean isDragging = false;
    private boolean autoYMode = false;
    private JPopupMenu contextMenu;

    // Data access callbacks
    private Supplier<DataSet> dataSetSupplier;
    private Supplier<ViewPort> viewportSupplier;
    private Consumer<ViewPort> viewportUpdater;
    private Supplier<List<String>> visibleSeriesSupplier;
    private Supplier<Rectangle> plotAreaSupplier;

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

            // Calculate Y range for visible data in new X range
            double[] yRange = calculateVisibleYRange(startTime, endTime);

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height);
            viewportUpdater.accept(newViewport);
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

            // Calculate Y range for visible data in new X range
            double[] yRange = calculateVisibleYRange(startTime, endTime);

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height);
            viewportUpdater.accept(newViewport);
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
     */
    private void handleZoom(MouseWheelEvent e) {
        ViewPort currentViewport = viewportSupplier.get();
        if (currentViewport == null) return;

        // Use smaller zoom factor for mouse wheel (less sensitive)
        double wheelZoomFactor = Math.pow(1.1, -e.getWheelRotation());

        if (autoYMode) {
            // Auto-Y mode: only zoom X-axis centered on mouse, then auto-fit Y
            long mouseTime = currentViewport.screenXToTime(e.getX());
            long currentStartTime = currentViewport.getStartTimeMs();
            long currentEndTime = currentViewport.getEndTimeMs();
            long currentTimeRange = currentEndTime - currentStartTime;

            // Calculate new time range
            long newTimeRange = (long) (currentTimeRange / wheelZoomFactor);

            // Center the new range on the mouse position
            double mouseRatio = (double) (mouseTime - currentStartTime) / currentTimeRange;
            long startTime = mouseTime - (long) (newTimeRange * mouseRatio);
            long endTime = startTime + newTimeRange;

            // Calculate Y range for visible data in new X range
            double[] yRange = calculateVisibleYRange(startTime, endTime);

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height);
            viewportUpdater.accept(newViewport);
        } else {
            // Standard zoom: zoom both axes centered on mouse position
            long mouseTime = currentViewport.screenXToTime(e.getX());
            double mouseValue = currentViewport.screenYToValue(e.getY());

            // Get current ranges
            long currentStartTime = currentViewport.getStartTimeMs();
            long currentEndTime = currentViewport.getEndTimeMs();
            double currentMinValue = currentViewport.getMinValue();
            double currentMaxValue = currentViewport.getMaxValue();

            long currentTimeRange = currentEndTime - currentStartTime;
            double currentValueRange = currentMaxValue - currentMinValue;

            // Calculate new ranges
            long newTimeRange = (long) (currentTimeRange / wheelZoomFactor);
            double newValueRange = currentValueRange / wheelZoomFactor;

            // Center the new ranges on mouse position
            double mouseTimeRatio = (double) (mouseTime - currentStartTime) / currentTimeRange;
            double mouseValueRatio = (mouseValue - currentMinValue) / currentValueRange;

            long startTime = mouseTime - (long) (newTimeRange * mouseTimeRatio);
            long endTime = startTime + newTimeRange;

            double minValue = mouseValue - (newValueRange * mouseValueRatio);
            double maxValue = minValue + newValueRange;

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height);
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
            long deltaTime = (long) (-dx * timeRange / currentViewport.getPlotWidth());

            // Calculate new time range
            long newStartTime = currentViewport.getStartTimeMs() + deltaTime;
            long newEndTime = currentViewport.getEndTimeMs() + deltaTime;

            // Calculate Y range for visible data in new X range
            double[] yRange = calculateVisibleYRange(newStartTime, newEndTime);

            Rectangle plotArea = plotAreaSupplier.get();
            ViewPort newViewport = new ViewPort(newStartTime, newEndTime, yRange[0], yRange[1],
                                              plotArea.x, plotArea.y, plotArea.width, plotArea.height);
            viewportUpdater.accept(newViewport);
        } else {
            // Standard pan: pan both axes
            long timeRange = currentViewport.getTimeRangeMs();
            double valueRange = currentViewport.getValueRange();

            long deltaTime = (long) (-dx * timeRange / currentViewport.getPlotWidth());
            double deltaValue = dy * valueRange / currentViewport.getPlotHeight();

            // Pan the viewport
            ViewPort newViewport = currentViewport.pan(deltaTime, deltaValue);
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
                    if (!Double.isNaN(value)) {
                        minValue = Math.min(minValue, value);
                        maxValue = Math.max(maxValue, value);
                        hasValidData = true;
                    }
                }
            }
        }

        if (!hasValidData) {
            return new double[]{-1.0, 1.0}; // Default range when no data
        }

        // Add 5% padding
        double valueRange = maxValue - minValue;
        if (valueRange < 0.001) { // Very small range
            double center = (minValue + maxValue) / 2;
            minValue = center - 0.5;
            maxValue = center + 0.5;
        } else {
            double padding = valueRange * 0.05;
            minValue -= padding;
            maxValue += padding;
        }

        return new double[]{minValue, maxValue};
    }

    /**
     * Sets up the right-click context menu.
     */
    private void setupContextMenu() {
        contextMenu = new JPopupMenu();

        JMenuItem zoomInItem = new JMenuItem("Zoom In");
        zoomInItem.addActionListener(e -> zoomIn());
        contextMenu.add(zoomInItem);

        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        zoomOutItem.addActionListener(e -> zoomOut());
        contextMenu.add(zoomOutItem);

        JMenuItem zoomToFitItem = new JMenuItem("Zoom to Fit");
        zoomToFitItem.addActionListener(e -> zoomToFit());
        contextMenu.add(zoomToFitItem);

        contextMenu.addSeparator();

        JMenuItem saveDataItem = new JMenuItem("Save Data...");
        saveDataItem.addActionListener(e -> saveData());
        contextMenu.add(saveDataItem);
    }

    /**
     * Displays a file save dialog with multiple format options and exports based on selected file extension.
     */
    private void saveData() {
        DataSet dataSet = dataSetSupplier.get();
        if (dataSet == null || dataSet.isEmpty()) {
            JOptionPane.showMessageDialog(parentComponent, "No data to save.", "Save Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();

        // Add file filters for different formats
        FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV Files (*.csv)", "csv");
        FileNameExtensionFilter kalixFilter = new FileNameExtensionFilter("Kalix Timeseries Files (*.ktm)", "ktm");

        fileChooser.addChoosableFileFilter(csvFilter);
        fileChooser.addChoosableFileFilter(kalixFilter);
        fileChooser.setFileFilter(csvFilter); // Default to CSV

        fileChooser.setSelectedFile(new File("timeseries_data.csv"));

        int result = fileChooser.showSaveDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String fileName = file.getName().toLowerCase();

            // Determine format based on selected filter or file extension
            FileNameExtensionFilter selectedFilter = (FileNameExtensionFilter) fileChooser.getFileFilter();

            if (selectedFilter == kalixFilter || fileName.endsWith(".ktm")) {
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
            TimeSeriesCsvExporter.export(dataSet, file);
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

        // Remove .ktm extension if present to get base path
        if (filePath.toLowerCase().endsWith(".ktm")) {
            filePath = filePath.substring(0, filePath.length() - 4);
        }

        try {
            DataSet dataSet = dataSetSupplier.get();

            // Convert DataSet to List<TimeSeriesData>
            java.util.List<com.kalix.gui.flowviz.data.TimeSeriesData> seriesList =
                new java.util.ArrayList<>();

            for (String seriesName : dataSet.getSeriesNames()) {
                com.kalix.gui.flowviz.data.TimeSeriesData series = dataSet.getSeries(seriesName);
                if (series != null) {
                    seriesList.add(series);
                }
            }

            // Write to Kalix format
            KalixTimeSeriesWriter writer = new KalixTimeSeriesWriter();
            writer.writeToFile(filePath, seriesList);

            JOptionPane.showMessageDialog(parentComponent,
                "Data saved successfully to " + new File(filePath + ".ktm").getName() + " and " + new File(filePath + ".kts").getName(),
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

        int result = fileChooser.showSaveDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }

            try {
                TimeSeriesCsvExporter.export(dataSet, file);
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