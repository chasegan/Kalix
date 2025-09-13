package com.kalix.gui.flowviz;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;
import com.kalix.gui.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.gui.flowviz.rendering.ViewPort;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    public PlotPanel() {
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createTitledBorder("Time Series Plot"));
        
        // Initialize data structures
        seriesColors = new HashMap<>();
        visibleSeries = new java.util.ArrayList<>();
        renderer = new TimeSeriesRenderer(seriesColors, visibleSeries);

        setupContextMenu();
        setupMouseListeners();
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
                // Update cursor coordinates in status bar (future implementation)
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
                exportDataToCsv(file);
                JOptionPane.showMessageDialog(this,
                    "Data saved successfully to " + file.getName(),
                    "Save Data",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Error saving data: " + e.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportDataToCsv(File file) throws IOException {
        List<TimeSeriesData> allSeries = dataSet.getAllSeries();
        if (allSeries.isEmpty()) {
            return;
        }

        // Collect all unique timestamps across all series
        java.util.Set<Long> allTimestamps = new java.util.TreeSet<>();
        for (TimeSeriesData series : allSeries) {
            for (long timestamp : series.getTimestamps()) {
                allTimestamps.add(timestamp);
            }
        }

        try (FileWriter writer = new FileWriter(file)) {
            // Write header
            writer.write("Datetime");
            for (TimeSeriesData series : allSeries) {
                writer.write(",");
                writer.write(escapeCsvField(series.getName()));
            }
            writer.write("\n");

            // Write data rows
            for (Long timestamp : allTimestamps) {
                // Format datetime
                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC);
                String dateTimeStr = formatDateTime(dateTime);
                writer.write(dateTimeStr);

                // Write values for each series
                for (TimeSeriesData series : allSeries) {
                    writer.write(",");
                    Double value = getValueAtTimestamp(series, timestamp);
                    if (value != null && !Double.isNaN(value)) {
                        writer.write(String.valueOf(value));
                    }
                    // For NaN/missing values, write empty cell
                }
                writer.write("\n");
            }
        }
    }

    private String formatDateTime(LocalDateTime dateTime) {
        // Check if it's a whole day (midnight with no time component)
        if (dateTime.getHour() == 0 && dateTime.getMinute() == 0 && dateTime.getSecond() == 0 && dateTime.getNano() == 0) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        }
    }

    private String escapeCsvField(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    private Double getValueAtTimestamp(TimeSeriesData series, long timestamp) {
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();

        for (int i = 0; i < timestamps.length; i++) {
            if (timestamps[i] == timestamp && validPoints[i]) {
                return values[i];
            }
        }
        return null; // Not found or invalid
    }
}