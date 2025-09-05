package com.kalix.gui.flowviz;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;
import com.kalix.gui.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.gui.flowviz.rendering.ViewPort;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
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
    
    public PlotPanel() {
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createTitledBorder("Time Series Plot"));
        
        // Initialize data structures
        seriesColors = new HashMap<>();
        visibleSeries = new java.util.ArrayList<>();
        renderer = new TimeSeriesRenderer(seriesColors, visibleSeries);
        
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
    
    private void setupMouseListeners() {
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
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
                    
                    // Convert screen delta to data space delta
                    long timeRange = currentViewport.getTimeRangeMs();
                    double valueRange = currentViewport.getValueRange();
                    
                    long deltaTime = (long) (-dx * timeRange / currentViewport.getPlotWidth());
                    double deltaValue = dy * valueRange / currentViewport.getPlotHeight();
                    
                    // Pan the viewport
                    currentViewport = currentViewport.pan(deltaTime, deltaValue);
                    
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
        
        double zoomFactor = Math.pow(ZOOM_FACTOR, -e.getWheelRotation());
        
        if (autoYMode) {
            // Auto-Y mode: only zoom X-axis, then auto-fit Y
            long centerTime = currentViewport.screenXToTime(e.getX());
            long timeRange = currentViewport.getTimeRangeMs();
            long newTimeRange = (long) (timeRange / zoomFactor);
            
            long startTime = centerTime - newTimeRange / 2;
            long endTime = centerTime + newTimeRange / 2;
            
            // Calculate Y range for visible data in new X range
            double[] yRange = calculateVisibleYRange(startTime, endTime);
            
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(startTime, endTime, yRange[0], yRange[1],
                                         plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        } else {
            // Standard zoom: zoom both axes
            long centerTime = currentViewport.screenXToTime(e.getX());
            double centerValue = currentViewport.screenYToValue(e.getY());
            
            // Apply zoom centered on mouse position
            currentViewport = currentViewport.zoom(zoomFactor, centerTime, centerValue);
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
        
        // Draw debug info
        drawDebugInfo(g2d);
        
        g2d.dispose();
    }
    
    
    private void drawDebugInfo(Graphics2D g2d) {
        if (currentViewport == null) return;
        
        g2d.setColor(Color.DARK_GRAY);
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));
        
        String debugInfo = String.format("Viewport: %.0f ms range, %.3f value range | Cache: %d", 
            (double) currentViewport.getTimeRangeMs(), 
            currentViewport.getValueRange(),
            renderer != null ? renderer.getCacheSize() : 0);
        
        g2d.drawString(debugInfo, 10, getHeight() - 10);
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
}