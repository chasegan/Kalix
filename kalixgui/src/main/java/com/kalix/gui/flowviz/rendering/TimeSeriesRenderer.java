package com.kalix.gui.flowviz.rendering;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;

import java.awt.*;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class TimeSeriesRenderer {
    
    private final LODManager lodManager;
    private final Map<String, Color> seriesColors;
    private final List<String> visibleSeries;
    
    // Rendering options
    private boolean showDataPoints = false;
    private boolean showGrid = true;
    private boolean antiAliasing = true;
    
    public TimeSeriesRenderer(Map<String, Color> seriesColors, List<String> visibleSeries) {
        this.lodManager = new LODManager();
        this.seriesColors = seriesColors;
        this.visibleSeries = visibleSeries;
    }
    
    public void render(Graphics2D g2d, DataSet dataSet, ViewPort viewport) {
        if (dataSet.isEmpty()) {
            renderEmptyState(g2d, viewport);
            return;
        }
        
        // Set rendering hints
        if (antiAliasing) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }
        
        // Clear plot area
        g2d.setColor(Color.WHITE);
        g2d.fillRect(viewport.getPlotX(), viewport.getPlotY(), 
                    viewport.getPlotWidth(), viewport.getPlotHeight());
        
        // Draw grid
        if (showGrid) {
            drawGrid(g2d, viewport);
        }
        
        // Draw axes
        drawAxes(g2d, dataSet, viewport);
        
        // Draw time series data in legend order (visibleSeries list order)
        for (String seriesName : visibleSeries) {
            TimeSeriesData series = dataSet.getSeries(seriesName);
            if (series != null) {
                Color seriesColor = seriesColors.get(seriesName);
                if (seriesColor != null) {
                    renderSeries(g2d, series, viewport, seriesColor);
                }
            }
        }
        
        // Draw plot border
        drawPlotBorder(g2d, viewport);
    }
    
    private void renderSeries(Graphics2D g2d, TimeSeriesData series, ViewPort viewport, Color color) {
        LODManager.RenderStrategy strategy = lodManager.determineRenderStrategy(series, viewport);
        
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        
        if (strategy.useFullResolution) {
            renderFullResolution(g2d, series, viewport, strategy.indexRange);
        } else {
            renderLOD(g2d, series, viewport, strategy.lodData);
        }
    }
    
    private void renderFullResolution(Graphics2D g2d, TimeSeriesData series, ViewPort viewport, 
                                    TimeSeriesData.IndexRange indexRange) {
        if (indexRange.isEmpty()) return;
        
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();
        
        Path2D.Double path = new Path2D.Double();
        boolean pathStarted = false;
        
        for (int i = indexRange.startIndex; i < indexRange.endIndex; i++) {
            if (!validPoints[i]) {
                // Missing value - break the path
                pathStarted = false;
                continue;
            }
            
            long timestamp = timestamps[i];
            double value = values[i];
            
            int screenX = viewport.timeToScreenX(timestamp);
            int screenY = viewport.valueToScreenY(value);
            
            if (!pathStarted) {
                path.moveTo(screenX, screenY);
                pathStarted = true;
            } else {
                path.lineTo(screenX, screenY);
            }
            
            // Draw data points if enabled
            if (showDataPoints) {
                g2d.fillOval(screenX - 2, screenY - 2, 4, 4);
            }
        }
        
        g2d.draw(path);
    }
    
    private void renderLOD(Graphics2D g2d, TimeSeriesData series, ViewPort viewport, 
                          LODManager.LODData lodData) {
        double[][] minMaxBands = lodData.minMaxBands;
        boolean[] hasValidData = lodData.hasValidData;
        
        g2d.setStroke(new BasicStroke(1.0f));
        
        for (int pixelX = 0; pixelX < lodData.pixelWidth; pixelX++) {
            if (!hasValidData[pixelX]) continue;
            
            double minValue = minMaxBands[pixelX][0];
            double maxValue = minMaxBands[pixelX][1];
            
            int screenX = viewport.getPlotX() + pixelX;
            int minScreenY = viewport.valueToScreenY(maxValue);  // Note: Y is inverted
            int maxScreenY = viewport.valueToScreenY(minValue);
            
            // Draw vertical line representing min-max range for this pixel column
            if (minScreenY == maxScreenY) {
                // Single point - draw small circle
                g2d.fillOval(screenX - 1, minScreenY - 1, 2, 2);
            } else {
                // Range - draw vertical line
                g2d.drawLine(screenX, minScreenY, screenX, maxScreenY);
            }
        }
    }
    
    private void drawGrid(Graphics2D g2d, ViewPort viewport) {
        g2d.setColor(new Color(240, 240, 240));
        g2d.setStroke(new BasicStroke(0.5f));
        
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();
        
        // Vertical grid lines (time axis)
        int numVerticalLines = Math.max(5, Math.min(20, plotWidth / 50));
        for (int i = 0; i <= numVerticalLines; i++) {
            int x = plotX + (plotWidth * i) / numVerticalLines;
            g2d.drawLine(x, plotY, x, plotY + plotHeight);
        }
        
        // Horizontal grid lines (value axis)
        int numHorizontalLines = Math.max(5, Math.min(15, plotHeight / 40));
        for (int i = 0; i <= numHorizontalLines; i++) {
            int y = plotY + (plotHeight * i) / numHorizontalLines;
            g2d.drawLine(plotX, y, plotX + plotWidth, y);
        }
    }
    
    private void drawAxes(Graphics2D g2d, DataSet dataSet, ViewPort viewport) {
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));
        
        drawTimeAxis(g2d, viewport);
        drawValueAxis(g2d, dataSet, viewport);
    }
    
    private void drawTimeAxis(Graphics2D g2d, ViewPort viewport) {
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();
        
        // Calculate appropriate time tick interval
        long timeRangeMs = viewport.getTimeRangeMs();
        int numTicks = Math.max(3, Math.min(10, plotWidth / 100));
        long tickIntervalMs = timeRangeMs / (numTicks - 1);
        
        // Round to nice intervals
        tickIntervalMs = roundToNiceInterval(tickIntervalMs);
        
        // Draw time ticks and labels
        long currentTime = viewport.getStartTimeMs();
        // Round to nearest tick interval
        currentTime = (currentTime / tickIntervalMs) * tickIntervalMs;
        
        FontMetrics fm = g2d.getFontMetrics();
        
        while (currentTime <= viewport.getEndTimeMs()) {
            int screenX = viewport.timeToScreenX(currentTime);
            
            if (screenX >= plotX && screenX <= plotX + plotWidth) {
                // Draw tick mark
                g2d.drawLine(screenX, plotY + plotHeight, screenX, plotY + plotHeight + 5);
                
                // Draw label
                String timeLabel = formatTime(currentTime, timeRangeMs);
                int labelWidth = fm.stringWidth(timeLabel);
                g2d.drawString(timeLabel, screenX - labelWidth / 2, 
                             plotY + plotHeight + 18);
            }
            
            currentTime += tickIntervalMs;
        }
    }
    
    private void drawValueAxis(Graphics2D g2d, DataSet dataSet, ViewPort viewport) {
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotHeight = viewport.getPlotHeight();
        
        double valueRange = viewport.getValueRange();
        if (valueRange <= 0) return;
        
        // Calculate appropriate value tick interval
        int numTicks = Math.max(3, Math.min(10, plotHeight / 40));
        double tickInterval = valueRange / (numTicks - 1);
        tickInterval = roundToNiceValueInterval(tickInterval);
        
        double currentValue = Math.floor(viewport.getMinValue() / tickInterval) * tickInterval;
        
        FontMetrics fm = g2d.getFontMetrics();
        
        while (currentValue <= viewport.getMaxValue() + tickInterval / 2) {
            int screenY = viewport.valueToScreenY(currentValue);
            
            if (screenY >= plotY && screenY <= plotY + plotHeight) {
                // Draw tick mark
                g2d.drawLine(plotX - 5, screenY, plotX, screenY);
                
                // Draw label
                String valueLabel = formatValue(currentValue);
                int labelHeight = fm.getAscent();
                g2d.drawString(valueLabel, plotX - 8 - fm.stringWidth(valueLabel), 
                             screenY + labelHeight / 2);
            }
            
            currentValue += tickInterval;
        }
    }
    
    private long roundToNiceInterval(long intervalMs) {
        // Common time intervals in milliseconds
        long[] niceIntervals = {
            1000,           // 1 second
            5000,           // 5 seconds
            10000,          // 10 seconds
            30000,          // 30 seconds
            60000,          // 1 minute
            300000,         // 5 minutes
            600000,         // 10 minutes
            1800000,        // 30 minutes
            3600000,        // 1 hour
            7200000,        // 2 hours
            21600000,       // 6 hours
            43200000,       // 12 hours
            86400000,       // 1 day
            604800000,      // 1 week
            2592000000L,    // 30 days
            31536000000L    // 1 year
        };
        
        for (long niceInterval : niceIntervals) {
            if (intervalMs <= niceInterval) {
                return niceInterval;
            }
        }
        
        return intervalMs;
    }
    
    private double roundToNiceValueInterval(double interval) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(interval)));
        double normalizedInterval = interval / magnitude;
        
        if (normalizedInterval <= 1) return magnitude;
        if (normalizedInterval <= 2) return 2 * magnitude;
        if (normalizedInterval <= 5) return 5 * magnitude;
        return 10 * magnitude;
    }
    
    private String formatTime(long timeMs, long timeRangeMs) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timeMs), ZoneOffset.UTC);
        
        if (timeRangeMs < 86400000) {  // Less than 1 day - show time
            return dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        } else if (timeRangeMs < 2592000000L) {  // Less than 30 days - show date
            return dateTime.format(DateTimeFormatter.ofPattern("MM-dd"));
        } else {  // Show year, month, and day
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
    }
    
    private String formatValue(double value) {
        if (Math.abs(value) >= 1000000) {
            return String.format("%.1fM", value / 1000000);
        } else if (Math.abs(value) >= 1000) {
            return String.format("%.1fK", value / 1000);
        } else if (Math.abs(value) >= 1) {
            return String.format("%.1f", value);
        } else {
            return String.format("%.3f", value);
        }
    }
    
    private void drawPlotBorder(Graphics2D g2d, ViewPort viewport) {
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.drawRect(viewport.getPlotX(), viewport.getPlotY(),
                    viewport.getPlotWidth(), viewport.getPlotHeight());
    }
    
    private void renderEmptyState(Graphics2D g2d, ViewPort viewport) {
        g2d.setColor(Color.LIGHT_GRAY);
        g2d.setFont(new Font("Arial", Font.PLAIN, 14));
        
        String message = "No data to display";
        FontMetrics fm = g2d.getFontMetrics();
        int messageX = viewport.getPlotX() + (viewport.getPlotWidth() - fm.stringWidth(message)) / 2;
        int messageY = viewport.getPlotY() + viewport.getPlotHeight() / 2;
        
        g2d.drawString(message, messageX, messageY);
    }
    
    // Rendering options
    public void setShowDataPoints(boolean show) { this.showDataPoints = show; }
    public void setShowGrid(boolean show) { this.showGrid = show; }
    public void setAntiAliasing(boolean enable) { this.antiAliasing = enable; }
    
    public boolean isShowDataPoints() { return showDataPoints; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isAntiAliasing() { return antiAliasing; }
    
    // Cache management
    public void clearCache() { lodManager.clearCache(); }
    public void clearCache(String seriesName) { lodManager.clearCache(seriesName); }
    public int getCacheSize() { return lodManager.getCacheSize(); }
}