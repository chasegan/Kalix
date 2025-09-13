package com.kalix.gui.flowviz;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;
import com.kalix.gui.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.gui.flowviz.rendering.ViewPort;
import com.kalix.gui.io.TimeSeriesCsvExporter;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlotPanel extends JPanel {
    
    // Plot margins
    private static final int MARGIN_LEFT = 80;
    private static final int MARGIN_RIGHT = 20;
    private static final int MARGIN_TOP = 20;
    private static final int MARGIN_BOTTOM = 60;
    
    // Zoom and pan state
    private static final double ZOOM_FACTOR = 1.2;
    private static final double MIN_ZOOM = 0.001;
    private static final double MAX_ZOOM = 1000.0;
    
    private Point lastMousePos;
    private boolean isDragging = false;
    
    // Data and rendering
    private DataSet dataSet;
    private TimeSeriesRenderer renderer;
    private ViewPort currentViewport;
    private Map<String, Color> seriesColors;
    private List<String> visibleSeries;
    private boolean autoYMode = false;
    private JPopupMenu contextMenu;

    // Coordinate display features
    private boolean showCoordinates = false;
    private Point lastMousePosition;
    private List<CoordinateInfo> currentCoordinates = new ArrayList<>();
    private javax.swing.Timer coordinateUpdateTimer;

    public PlotPanel() {
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createTitledBorder("Time Series Plot"));
        
        // Initialize data structures
        seriesColors = new HashMap<>();
        visibleSeries = new java.util.ArrayList<>();
        renderer = new TimeSeriesRenderer(seriesColors, visibleSeries);

        setupContextMenu();
        setupMouseListeners();
        setupCoordinateDisplay();
    }
    
    public void setDataSet(DataSet dataSet) {
        this.dataSet = dataSet;
        
        // Create initial viewport to show all data
        if (dataSet != null && !dataSet.isEmpty()) {
            zoomToFitData();
        } else {
            createDefaultViewport();
        }
        
        repaint();
    }
    
    public void setSeriesColors(Map<String, Color> colors) {
        this.seriesColors.clear();
        this.seriesColors.putAll(colors);
        repaint();
    }
    
    public void setVisibleSeries(List<String> visibleSeries) {
        this.visibleSeries.clear();
        this.visibleSeries.addAll(visibleSeries);
        repaint();
    }
    
    private void zoomToFitData() {
        if (dataSet == null || dataSet.isEmpty()) {
            createDefaultViewport();
            return;
        }
        
        long startTime = dataSet.getGlobalMinTime();
        long endTime = dataSet.getGlobalMaxTime();
        Double minValue = dataSet.getGlobalMinValue();
        Double maxValue = dataSet.getGlobalMaxValue();
        
        if (minValue == null || maxValue == null) {
            createDefaultViewport();
            return;
        }
        
        // Add 5% padding
        long timePadding = (long) ((endTime - startTime) * 0.05);
        double valuePadding = (maxValue - minValue) * 0.05;
        
        startTime -= timePadding;
        endTime += timePadding;
        minValue -= valuePadding;
        maxValue += valuePadding;
        
        // Ensure minimum range
        if (endTime - startTime < 1000) { // Less than 1 second
            long center = (startTime + endTime) / 2;
            startTime = center - 500;
            endTime = center + 500;
        }
        
        if (maxValue - minValue < 0.001) { // Very small value range
            double center = (minValue + maxValue) / 2;
            minValue = center - 0.5;
            maxValue = center + 0.5;
        }
        
        Rectangle plotArea = getPlotArea();
        currentViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height);
    }
    
    private void createDefaultViewport() {
        // Default viewport showing current time ± 1 hour
        long now = System.currentTimeMillis();
        long startTime = now - 3600000; // 1 hour ago
        long endTime = now + 3600000;   // 1 hour from now

        Rectangle plotArea = getPlotArea();
        currentViewport = new ViewPort(startTime, endTime, -10.0, 10.0,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height);
    }

    /**
     * Initializes the right-click context menu for the plot area.
     *
     * <p>Creates a popup menu with the following options:</p>
     * <ul>
     *   <li><strong>Zoom In</strong> - Zooms into the plot area</li>
     *   <li><strong>Zoom Out</strong> - Zooms out from the plot area</li>
     *   <li><strong>Zoom to Fit</strong> - Fits all data within the plot area</li>
     *   <li><strong>Save Data...</strong> - Exports current dataset to CSV format</li>
     * </ul>
     *
     * <p>The context menu is shown when the user right-clicks within the plot area.
     * The menu provides quick access to common plot operations without navigating
     * through the main menu bar.</p>
     *
     * @see #saveData()
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

    private void setupMouseListeners() {
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) && isInPlotArea(e.getPoint())) {
                    contextMenu.show(PlotPanel.this, e.getX(), e.getY());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    lastMousePos = e.getPoint();
                    isDragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
            
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    // Double-click: reset zoom to fit all data
                    if (isInPlotArea(e.getPoint())) {
                        zoomToFit();
                    }
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isDragging = false;
                    setCursor(Cursor.getDefaultCursor());
                }
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                if (isDragging && lastMousePos != null && currentViewport != null) {
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
                        
                        Rectangle plotArea = getPlotArea();
                        currentViewport = new ViewPort(newStartTime, newEndTime, yRange[0], yRange[1],
                                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height);
                    } else {
                        // Standard pan: pan both axes
                        long timeRange = currentViewport.getTimeRangeMs();
                        double valueRange = currentViewport.getValueRange();
                        
                        long deltaTime = (long) (-dx * timeRange / currentViewport.getPlotWidth());
                        double deltaValue = dy * valueRange / currentViewport.getPlotHeight();
                        
                        // Pan the viewport
                        currentViewport = currentViewport.pan(deltaTime, deltaValue);
                    }
                    
                    lastMousePos = e.getPoint();
                    repaint();
                }
            }
            
            @Override
            public void mouseMoved(MouseEvent e) {
                if (showCoordinates && isInPlotArea(e.getPoint())) {
                    lastMousePosition = e.getPoint();
                    startCoordinateUpdate();
                } else if (showCoordinates && !isInPlotArea(e.getPoint())) {
                    // Clear coordinates when mouse leaves plot area
                    clearCoordinates();
                }
            }
            
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (isInPlotArea(e.getPoint())) {
                    handleZoom(e);
                }
            }
        };
        
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
    }
    
    private void handleZoom(MouseWheelEvent e) {
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
            
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                         plotArea.x, plotArea.y, plotArea.width, plotArea.height);
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
            
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                         plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        }
        
        repaint();
    }
    
    private boolean isInPlotArea(Point point) {
        Rectangle plotArea = getPlotArea();
        return plotArea.contains(point);
    }
    
    private Rectangle getPlotArea() {
        int width = getWidth();
        int height = getHeight();
        
        return new Rectangle(
            MARGIN_LEFT,
            MARGIN_TOP,
            width - MARGIN_LEFT - MARGIN_RIGHT,
            height - MARGIN_TOP - MARGIN_BOTTOM
        );
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        
        // Update viewport with current plot area
        Rectangle plotArea = getPlotArea();
        if (currentViewport != null) {
            currentViewport = currentViewport.withPlotArea(
                plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        } else {
            createDefaultViewport();
        }
        
        // Render using the new rendering engine
        if (dataSet != null && renderer != null) {
            renderer.render(g2d, dataSet, currentViewport);
        } else {
            // Fallback to empty state
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.setFont(new Font("Arial", Font.PLAIN, 16));

            String message = "No data loaded";
            FontMetrics fm = g2d.getFontMetrics();
            int messageX = plotArea.x + (plotArea.width - fm.stringWidth(message)) / 2;
            int messageY = plotArea.y + plotArea.height / 2;

            g2d.drawString(message, messageX, messageY);
        }

        // Draw coordinate overlays if enabled
        if (showCoordinates) {
            drawCoordinateOverlays(g2d);
        }

        g2d.dispose();
    }
    
    public void zoomIn() {
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
            
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                         plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        } else {
            // Standard zoom: zoom both axes
            long centerTime = (currentViewport.getStartTimeMs() + currentViewport.getEndTimeMs()) / 2;
            double centerValue = (currentViewport.getMinValue() + currentViewport.getMaxValue()) / 2;
            
            currentViewport = currentViewport.zoom(ZOOM_FACTOR, centerTime, centerValue);
        }
        repaint();
    }
    
    public void zoomOut() {
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
            
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                         plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        } else {
            // Standard zoom: zoom both axes
            long centerTime = (currentViewport.getStartTimeMs() + currentViewport.getEndTimeMs()) / 2;
            double centerValue = (currentViewport.getMinValue() + currentViewport.getMaxValue()) / 2;
            
            currentViewport = currentViewport.zoom(1.0 / ZOOM_FACTOR, centerTime, centerValue);
        }
        repaint();
    }
    
    public void zoomToFit() {
        zoomToFitData();
        repaint();
    }
    
    public void resetView() {
        zoomToFitData();
        repaint();
    }
    
    public void setAutoYMode(boolean autoYMode) {
        this.autoYMode = autoYMode;
    }

    public void setShowCoordinates(boolean showCoordinates) {
        this.showCoordinates = showCoordinates;
        if (!showCoordinates) {
            clearCoordinates();
        }
        repaint();
    }

    public boolean isShowCoordinates() {
        return showCoordinates;
    }
    
    private double[] calculateVisibleYRange(long startTime, long endTime) {
        if (dataSet == null || dataSet.isEmpty() || visibleSeries.isEmpty()) {
            return new double[]{-1.0, 1.0}; // Default range
        }
        
        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        boolean hasValidData = false;
        
        // Check each visible series for data in the time range
        for (String seriesName : visibleSeries) {
            TimeSeriesData series = dataSet.getSeries(seriesName);
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
     * Displays a file save dialog and exports the current dataset to CSV format.
     *
     * <p>This method shows a file chooser dialog allowing the user to select
     * a target CSV file. The export includes all time series data with proper
     * datetime formatting and handles missing values appropriately.</p>
     *
     * <p>If no data is loaded or the dataset is empty, shows a warning dialog.
     * On successful export, displays a confirmation message. On error, shows
     * an error dialog with details.</p>
     *
     * @see TimeSeriesCsvExporter#export(DataSet, File)
     */
    private void saveData() {
        if (dataSet == null || dataSet.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No data to save.", "Save Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        fileChooser.setSelectedFile(new File("timeseries_data.csv"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }

            try {
                TimeSeriesCsvExporter.export(dataSet, file);
                JOptionPane.showMessageDialog(this,
                    "Data saved successfully to " + file.getName(),
                    "Save Data",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Error saving data: " + e.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(this,
                    "Invalid data: " + e.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Coordinate display implementation

    /**
     * Sets up the coordinate display system with throttled updates.
     */
    private void setupCoordinateDisplay() {
        // Set up a timer for throttled coordinate updates (60 FPS max)
        coordinateUpdateTimer = new javax.swing.Timer(16, e -> updateCoordinatesAtMousePosition());
        coordinateUpdateTimer.setRepeats(false);
    }

    /**
     * Starts a throttled coordinate update.
     */
    private void startCoordinateUpdate() {
        if (coordinateUpdateTimer != null && !coordinateUpdateTimer.isRunning()) {
            coordinateUpdateTimer.start();
        }
    }

    /**
     * Updates the coordinate display for the current mouse position.
     */
    private void updateCoordinatesAtMousePosition() {
        if (lastMousePosition == null || currentViewport == null || dataSet == null) {
            clearCoordinates();
            return;
        }

        // Convert mouse X position to time
        long mouseTime = currentViewport.screenXToTime(lastMousePosition.x);

        // Find closest points for all visible series
        List<CoordinateInfo> newCoordinates = new ArrayList<>();
        for (String seriesName : visibleSeries) {
            TimeSeriesData series = dataSet.getSeries(seriesName);
            if (series != null) {
                CoordinateInfo coord = findNearestPoint(series, mouseTime, seriesName);
                if (coord != null) {
                    newCoordinates.add(coord);
                }
            }
        }

        // Update coordinates if they changed
        if (!newCoordinates.equals(currentCoordinates)) {
            currentCoordinates = newCoordinates;
            repaint();
        }
    }

    /**
     * Finds the nearest valid data point to the given time using binary search.
     */
    private CoordinateInfo findNearestPoint(TimeSeriesData series, long targetTime, String seriesName) {
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();

        if (timestamps.length == 0) return null;

        // Binary search for closest time
        int index = binarySearchNearest(timestamps, targetTime);

        // Find the closest valid point around the found index
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;

        // Check a few points around the binary search result
        for (int i = Math.max(0, index - 2); i < Math.min(timestamps.length, index + 3); i++) {
            if (validPoints[i]) {
                long distance = Math.abs(timestamps[i] - targetTime);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
        }

        if (bestIndex == -1) return null;

        return new CoordinateInfo(
            seriesName,
            timestamps[bestIndex],
            values[bestIndex],
            seriesColors.get(seriesName)
        );
    }

    /**
     * Binary search to find the index of the closest timestamp.
     */
    private int binarySearchNearest(long[] timestamps, long target) {
        if (timestamps.length == 0) return 0;
        if (target <= timestamps[0]) return 0;
        if (target >= timestamps[timestamps.length - 1]) return timestamps.length - 1;

        int left = 0;
        int right = timestamps.length - 1;

        while (left < right) {
            int mid = (left + right) / 2;
            if (timestamps[mid] < target) {
                left = mid + 1;
            } else {
                right = mid;
            }
        }

        // Check if the previous point is closer
        if (left > 0 && Math.abs(timestamps[left - 1] - target) < Math.abs(timestamps[left] - target)) {
            return left - 1;
        }
        return left;
    }

    /**
     * Draws coordinate overlay rectangles for all current coordinates.
     */
    private void drawCoordinateOverlays(Graphics2D g2d) {
        if (currentCoordinates.isEmpty() || currentViewport == null) return;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Calculate positions and handle stacking
        List<Rectangle> usedAreas = new ArrayList<>();

        for (CoordinateInfo coord : currentCoordinates) {
            if (!currentViewport.isTimeVisible(coord.timestamp)) continue;

            int screenX = currentViewport.timeToScreenX(coord.timestamp);
            int screenY = currentViewport.valueToScreenY(coord.value);

            // Create coordinate text (single line with comma)
            String timeText = formatTimeForDisplay(coord.timestamp);
            String valueText = formatValueForDisplay(coord.value);
            String displayText = timeText + ", " + valueText;

            // Calculate rectangle bounds (single line)
            FontMetrics fm = g2d.getFontMetrics(new Font("Arial", Font.PLAIN, 10));
            int textWidth = fm.stringWidth(displayText);
            int textHeight = fm.getHeight();

            // Add padding
            int rectWidth = textWidth + 8;
            int rectHeight = textHeight + 6;

            // Calculate optimal position with stacking
            Rectangle optimalRect = calculateOptimalPosition(screenX, screenY, rectWidth, rectHeight, usedAreas);
            usedAreas.add(optimalRect);

            // Draw the coordinate box and point
            drawCoordinateBox(g2d, optimalRect, coord.color, displayText, fm);
            drawDataPoint(g2d, screenX, screenY, coord.color);
        }
    }

    /**
     * Calculates the optimal position for a coordinate rectangle, avoiding overlaps.
     */
    private Rectangle calculateOptimalPosition(int pointX, int pointY, int width, int height, List<Rectangle> usedAreas) {
        Rectangle plotArea = getPlotArea();

        // Try positions in order of preference: right, left, above, below
        int[] xOffsets = {10, -width - 10, -width / 2, -width / 2};
        int[] yOffsets = {-height / 2, -height / 2, -height - 10, 10};

        for (int i = 0; i < xOffsets.length; i++) {
            int x = pointX + xOffsets[i];
            int y = pointY + yOffsets[i];

            // Clamp to plot area
            x = Math.max(plotArea.x, Math.min(x, plotArea.x + plotArea.width - width));
            y = Math.max(plotArea.y, Math.min(y, plotArea.y + plotArea.height - height));

            Rectangle candidate = new Rectangle(x, y, width, height);

            // Check for overlaps and stack if needed
            boolean overlaps = false;
            for (Rectangle used : usedAreas) {
                if (candidate.intersects(used)) {
                    overlaps = true;
                    // Try stacking below
                    candidate.y = used.y + used.height + 2;
                    if (candidate.y + candidate.height > plotArea.y + plotArea.height) {
                        // Stack above instead
                        candidate.y = used.y - candidate.height - 2;
                    }
                    break;
                }
            }

            if (!overlaps || !intersectsAny(candidate, usedAreas)) {
                return candidate;
            }
        }

        // Fallback: place at default position even if it overlaps
        int x = Math.max(plotArea.x, Math.min(pointX + 10, plotArea.x + plotArea.width - width));
        int y = Math.max(plotArea.y, Math.min(pointY - height / 2, plotArea.y + plotArea.height - height));
        return new Rectangle(x, y, width, height);
    }

    /**
     * Checks if a rectangle intersects with any rectangle in the list.
     */
    private boolean intersectsAny(Rectangle rect, List<Rectangle> rects) {
        return rects.stream().anyMatch(rect::intersects);
    }

    /**
     * Draws a coordinate display box with translucent background and border.
     */
    private void drawCoordinateBox(Graphics2D g2d, Rectangle bounds, Color seriesColor, String text, FontMetrics fm) {
        // Draw more transparent background
        Color bgColor = new Color(255, 255, 255, 160);
        g2d.setColor(bgColor);
        g2d.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

        // Draw thin border
        Color borderColor = seriesColor != null ?
            new Color(seriesColor.getRed(), seriesColor.getGreen(), seriesColor.getBlue(), 120) :
            new Color(128, 128, 128, 120);
        g2d.setColor(borderColor);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);

        // Draw text
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));

        int textX = bounds.x + 4;
        int textY = bounds.y + fm.getAscent() + 2;

        g2d.drawString(text, textX, textY);
    }

    /**
     * Draws a small circle at the data point location.
     */
    private void drawDataPoint(Graphics2D g2d, int screenX, int screenY, Color seriesColor) {
        if (seriesColor == null) return;

        g2d.setColor(seriesColor);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw filled circle (4px diameter)
        int radius = 2;
        g2d.fillOval(screenX - radius, screenY - radius, radius * 2, radius * 2);

        // Draw slightly darker border
        Color borderColor = new Color(
            Math.max(0, seriesColor.getRed() - 40),
            Math.max(0, seriesColor.getGreen() - 40),
            Math.max(0, seriesColor.getBlue() - 40)
        );
        g2d.setColor(borderColor);
        g2d.drawOval(screenX - radius, screenY - radius, radius * 2, radius * 2);
    }

    /**
     * Formats timestamp for display based on precision requirements.
     */
    private String formatTimeForDisplay(long timestampMs) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneOffset.UTC);

        // Check if it's a whole day (midnight UTC)
        if (dateTime.getHour() == 0 && dateTime.getMinute() == 0 && dateTime.getSecond() == 0 &&
            timestampMs % 1000 == 0) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        }
    }

    /**
     * Formats value for display with appropriate precision.
     */
    private String formatValueForDisplay(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.0f", value);
        } else if (Math.abs(value) >= 1) {
            return String.format("%.3g", value);
        } else {
            return String.format("%.4g", value);
        }
    }

    /**
     * Clears all coordinate displays and triggers a repaint.
     */
    private void clearCoordinates() {
        if (!currentCoordinates.isEmpty()) {
            currentCoordinates.clear();
            repaint();
        }
    }

    /**
     * Data class to hold coordinate information for display.
     */
    private static class CoordinateInfo {
        final String seriesName;
        final long timestamp;
        final double value;
        final Color color;

        CoordinateInfo(String seriesName, long timestamp, double value, Color color) {
            this.seriesName = seriesName;
            this.timestamp = timestamp;
            this.value = value;
            this.color = color;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CoordinateInfo)) return false;
            CoordinateInfo other = (CoordinateInfo) obj;
            return seriesName.equals(other.seriesName) &&
                   timestamp == other.timestamp &&
                   Double.compare(value, other.value) == 0;
        }

        @Override
        public int hashCode() {
            return seriesName.hashCode() + Long.hashCode(timestamp) + Double.hashCode(value);
        }
    }
}