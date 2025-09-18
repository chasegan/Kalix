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

        // Define clipping bounds
        int clipLeft = viewport.getPlotX();
        int clipRight = viewport.getPlotX() + viewport.getPlotWidth();
        int clipTop = viewport.getPlotY();
        int clipBottom = viewport.getPlotY() + viewport.getPlotHeight();

        // Extend index range to include boundary connection points
        int extendedStartIndex = Math.max(0, indexRange.startIndex - 1);
        int extendedEndIndex = Math.min(timestamps.length, indexRange.endIndex + 1);

        Path2D.Double path = new Path2D.Double();
        boolean pathStarted = false;
        int prevScreenX = 0, prevScreenY = 0;

        for (int i = extendedStartIndex; i < extendedEndIndex; i++) {
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
                // Start new path segment
                // Always start the path, even if the point is outside bounds
                // The clipping will handle drawing only the visible portion
                path.moveTo(screenX, screenY);
                pathStarted = true;
            } else {
                // Draw line from previous point to current point with clipping
                drawClippedLine(path, prevScreenX, prevScreenY, screenX, screenY,
                              clipLeft, clipRight, clipTop, clipBottom);
            }

            prevScreenX = screenX;
            prevScreenY = screenY;

            // Draw data points if enabled and point is visible (only for original range)
            if (showDataPoints &&
                i >= indexRange.startIndex && i < indexRange.endIndex &&
                isPointInBounds(screenX, screenY, clipLeft, clipRight, clipTop, clipBottom)) {
                g2d.fillOval(screenX - 2, screenY - 2, 4, 4);
            }
        }

        g2d.draw(path);
    }

    private boolean isPointInBounds(int x, int y, int left, int right, int top, int bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private void drawClippedLine(Path2D.Double path, int x1, int y1, int x2, int y2,
                                int clipLeft, int clipRight, int clipTop, int clipBottom) {
        // Perform line clipping using Cohen-Sutherland algorithm
        LineClipResult result = clipLine(x1, y1, x2, y2, clipLeft, clipRight, clipTop, clipBottom);

        if (result.visible) {
            // Determine if we need to start a new path segment or continue existing one
            boolean p1Inside = isPointInBounds(x1, y1, clipLeft, clipRight, clipTop, clipBottom);
            boolean p2Inside = isPointInBounds(x2, y2, clipLeft, clipRight, clipTop, clipBottom);

            if (p1Inside && p2Inside) {
                // Both points inside - continue path normally
                path.lineTo(result.x2, result.y2);
            } else if (!p1Inside && p2Inside) {
                // P1 outside, P2 inside - move to clipped start point and draw to end
                path.moveTo(result.x1, result.y1);
                path.lineTo(result.x2, result.y2);
            } else if (p1Inside && !p2Inside) {
                // P1 inside, P2 outside - continue to clipped end point
                path.lineTo(result.x2, result.y2);
            } else {
                // Both points outside but line crosses visible area
                path.moveTo(result.x1, result.y1);
                path.lineTo(result.x2, result.y2);
            }
        }
        // If line is not visible, don't draw anything
    }

    private static class LineClipResult {
        boolean visible;
        int x1, y1, x2, y2;

        LineClipResult(boolean visible, int x1, int y1, int x2, int y2) {
            this.visible = visible;
            this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
        }
    }

    private LineClipResult clipLine(int x1, int y1, int x2, int y2,
                                   int clipLeft, int clipRight, int clipTop, int clipBottom) {
        // Cohen-Sutherland outcodes
        int outcode1 = computeOutcode(x1, y1, clipLeft, clipRight, clipTop, clipBottom);
        int outcode2 = computeOutcode(x2, y2, clipLeft, clipRight, clipTop, clipBottom);

        while (true) {
            if ((outcode1 | outcode2) == 0) {
                // Both points inside
                return new LineClipResult(true, x1, y1, x2, y2);
            } else if ((outcode1 & outcode2) != 0) {
                // Both points outside same boundary
                return new LineClipResult(false, 0, 0, 0, 0);
            } else {
                // Line crosses boundary - clip it
                int outcodeOut = (outcode1 != 0) ? outcode1 : outcode2;
                int x, y;

                if ((outcodeOut & 8) != 0) { // Top
                    x = x1 + (x2 - x1) * (clipTop - y1) / (y2 - y1);
                    y = clipTop;
                } else if ((outcodeOut & 4) != 0) { // Bottom
                    x = x1 + (x2 - x1) * (clipBottom - y1) / (y2 - y1);
                    y = clipBottom;
                } else if ((outcodeOut & 2) != 0) { // Right
                    y = y1 + (y2 - y1) * (clipRight - x1) / (x2 - x1);
                    x = clipRight;
                } else { // Left
                    y = y1 + (y2 - y1) * (clipLeft - x1) / (x2 - x1);
                    x = clipLeft;
                }

                if (outcodeOut == outcode1) {
                    x1 = x; y1 = y;
                    outcode1 = computeOutcode(x1, y1, clipLeft, clipRight, clipTop, clipBottom);
                } else {
                    x2 = x; y2 = y;
                    outcode2 = computeOutcode(x2, y2, clipLeft, clipRight, clipTop, clipBottom);
                }
            }
        }
    }

    private int computeOutcode(int x, int y, int clipLeft, int clipRight, int clipTop, int clipBottom) {
        int code = 0;
        if (x < clipLeft) code |= 1;      // Left
        else if (x > clipRight) code |= 2; // Right
        if (y < clipTop) code |= 8;       // Top
        else if (y > clipBottom) code |= 4; // Bottom
        return code;
    }
    
    private void renderLOD(Graphics2D g2d, TimeSeriesData series, ViewPort viewport,
                          LODManager.LODData lodData) {
        double[][] minMaxBands = lodData.minMaxBands;
        boolean[] hasValidData = lodData.hasValidData;

        // Define clipping bounds
        int clipLeft = viewport.getPlotX();
        int clipRight = viewport.getPlotX() + viewport.getPlotWidth();
        int clipTop = viewport.getPlotY();
        int clipBottom = viewport.getPlotY() + viewport.getPlotHeight();

        g2d.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // First pass: draw connected envelope (upper and lower bounds)
        Path2D.Double upperPath = new Path2D.Double();
        Path2D.Double lowerPath = new Path2D.Double();

        boolean upperPathStarted = false;
        boolean lowerPathStarted = false;

        // Build continuous envelope paths with clipping
        for (int pixelX = 0; pixelX < lodData.pixelWidth; pixelX++) {
            if (!hasValidData[pixelX]) {
                // Gap in data - break the paths
                upperPathStarted = false;
                lowerPathStarted = false;
                continue;
            }

            double minValue = minMaxBands[pixelX][0];
            double maxValue = minMaxBands[pixelX][1];

            int screenX = viewport.getPlotX() + pixelX;
            int minScreenY = viewport.valueToScreenY(maxValue);  // Note: Y is inverted
            int maxScreenY = viewport.valueToScreenY(minValue);

            // Clip Y coordinates to plot bounds
            minScreenY = Math.max(clipTop, Math.min(clipBottom, minScreenY));
            maxScreenY = Math.max(clipTop, Math.min(clipBottom, maxScreenY));

            // Only draw if X coordinate is within bounds
            if (screenX >= clipLeft && screenX <= clipRight) {
                // Add to upper path (max values)
                if (!upperPathStarted) {
                    upperPath.moveTo(screenX, minScreenY);
                    upperPathStarted = true;
                } else {
                    upperPath.lineTo(screenX, minScreenY);
                }

                // Add to lower path (min values)
                if (!lowerPathStarted) {
                    lowerPath.moveTo(screenX, maxScreenY);
                    lowerPathStarted = true;
                } else {
                    lowerPath.lineTo(screenX, maxScreenY);
                }
            } else {
                // Outside X bounds - break paths
                upperPathStarted = false;
                lowerPathStarted = false;
            }
        }

        // Draw the envelope paths
        g2d.draw(upperPath);
        g2d.draw(lowerPath);

        // Second pass: draw vertical connectors only where there's significant range
        g2d.setStroke(new BasicStroke(1.0f));

        for (int pixelX = 0; pixelX < lodData.pixelWidth; pixelX++) {
            if (!hasValidData[pixelX]) continue;

            double minValue = minMaxBands[pixelX][0];
            double maxValue = minMaxBands[pixelX][1];

            int screenX = viewport.getPlotX() + pixelX;

            // Only draw if X coordinate is within bounds
            if (screenX >= clipLeft && screenX <= clipRight) {
                int minScreenY = viewport.valueToScreenY(maxValue);  // Note: Y is inverted
                int maxScreenY = viewport.valueToScreenY(minValue);

                // Clip Y coordinates to plot bounds
                minScreenY = Math.max(clipTop, Math.min(clipBottom, minScreenY));
                maxScreenY = Math.max(clipTop, Math.min(clipBottom, maxScreenY));

                // Only draw vertical connector if there's a significant range (more than 3 pixels)
                if (Math.abs(maxScreenY - minScreenY) > 3) {
                    // Draw thin vertical line to show full range
                    g2d.setStroke(new BasicStroke(0.5f));
                    g2d.drawLine(screenX, minScreenY, screenX, maxScreenY);
                    g2d.setStroke(new BasicStroke(1.0f));
                }
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
        } else {  // 1 day or more - always show year with date
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
    }
    
    private String formatValue(double value) {
        // Always show full numbers, omit decimals if not needed
        if (value == Math.floor(value)) {
            // Integer value - no decimal places
            return String.format("%.0f", value);
        } else if (Math.abs(value) >= 1) {
            // Values >= 1 - show 1-3 decimal places as needed
            String formatted = String.format("%.3f", value);
            // Remove trailing zeros and decimal point if not needed
            formatted = formatted.replaceAll("0*$", "").replaceAll("\\.$", "");
            return formatted;
        } else {
            // Small values < 1 - show up to 6 decimal places, remove trailing zeros
            String formatted = String.format("%.6f", value);
            formatted = formatted.replaceAll("0*$", "").replaceAll("\\.$", "");
            return formatted;
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