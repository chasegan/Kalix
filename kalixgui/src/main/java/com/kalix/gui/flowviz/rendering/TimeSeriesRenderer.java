package com.kalix.gui.flowviz.rendering;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;

import java.awt.*;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

        // Draw vertical grid lines aligned with time axis ticks
        drawVerticalGridLines(g2d, viewport, plotX, plotY, plotWidth, plotHeight);

        // Draw horizontal grid lines aligned with value axis ticks
        drawHorizontalGridLines(g2d, viewport, plotX, plotY, plotWidth, plotHeight);
    }

    private void drawVerticalGridLines(Graphics2D g2d, ViewPort viewport, int plotX, int plotY, int plotWidth, int plotHeight) {
        // Use the same tick calculation as the time axis
        List<Long> tickTimes = calculateTemporalBoundaryTicks(
            viewport.getStartTimeMs(), viewport.getEndTimeMs(), plotWidth);

        for (Long tickTime : tickTimes) {
            int screenX = viewport.timeToScreenX(tickTime);
            if (screenX >= plotX && screenX <= plotX + plotWidth) {
                g2d.drawLine(screenX, plotY, screenX, plotY + plotHeight);
            }
        }
    }

    private void drawHorizontalGridLines(Graphics2D g2d, ViewPort viewport, int plotX, int plotY, int plotWidth, int plotHeight) {
        double valueRange = viewport.getValueRange();
        if (valueRange <= 0) return;

        // Calculate appropriate value tick interval (same logic as value axis)
        int numTicks = Math.max(3, Math.min(10, plotHeight / 40));
        double tickInterval = valueRange / (numTicks - 1);
        tickInterval = roundToNiceValueInterval(tickInterval);

        double currentValue = Math.floor(viewport.getMinValue() / tickInterval) * tickInterval;

        while (currentValue <= viewport.getMaxValue() + tickInterval / 2) {
            int screenY = viewport.valueToScreenY(currentValue);

            if (screenY >= plotY && screenY <= plotY + plotHeight) {
                g2d.drawLine(plotX, screenY, plotX + plotWidth, screenY);
            }

            currentValue += tickInterval;
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

        // Calculate appropriate time tick interval using temporal boundaries
        long timeRangeMs = viewport.getTimeRangeMs();
        List<Long> tickTimes = calculateTemporalBoundaryTicks(
            viewport.getStartTimeMs(), viewport.getEndTimeMs(), plotWidth);

        FontMetrics fm = g2d.getFontMetrics();

        for (Long tickTime : tickTimes) {
            int screenX = viewport.timeToScreenX(tickTime);

            if (screenX >= plotX && screenX <= plotX + plotWidth) {
                // Draw tick mark
                g2d.drawLine(screenX, plotY + plotHeight, screenX, plotY + plotHeight + 5);

                // Draw label
                String timeLabel = formatTime(tickTime, timeRangeMs);
                int labelWidth = fm.stringWidth(timeLabel);
                g2d.drawString(timeLabel, screenX - labelWidth / 2,
                             plotY + plotHeight + 18);
            }
        }
    }

    private List<Long> calculateTemporalBoundaryTicks(long startTimeMs, long endTimeMs, int plotWidth) {
        List<Long> ticks = new ArrayList<>();
        long timeRangeMs = endTimeMs - startTimeMs;

        // Target 3-8 labels depending on plot width
        int targetTicks = Math.max(3, Math.min(8, plotWidth / 120));

        // Define temporal intervals in milliseconds (approximate)
        TemporalInterval[] intervals = {
            new TemporalInterval("hour", 3600000L),
            new TemporalInterval("6hour", 6 * 3600000L),
            new TemporalInterval("12hour", 12 * 3600000L),
            new TemporalInterval("day", 86400000L),
            new TemporalInterval("week", 7 * 86400000L),
            new TemporalInterval("month", 30L * 86400000L),
            new TemporalInterval("quarter", 90L * 86400000L),
            new TemporalInterval("year", 365L * 86400000L),
            new TemporalInterval("5year", 5 * 365L * 86400000L),
            new TemporalInterval("decade", 10 * 365L * 86400000L),
            new TemporalInterval("25year", 25 * 365L * 86400000L),
            new TemporalInterval("50year", 50 * 365L * 86400000L),
            new TemporalInterval("century", 100 * 365L * 86400000L),
            new TemporalInterval("250year", 250 * 365L * 86400000L),
            new TemporalInterval("500year", 500 * 365L * 86400000L),
            new TemporalInterval("millennium", 1000 * 365L * 86400000L),
            new TemporalInterval("2500year", 2500 * 365L * 86400000L),
            new TemporalInterval("5millennium", 5000 * 365L * 86400000L),
            new TemporalInterval("10millennium", 10000 * 365L * 86400000L),
            new TemporalInterval("25millennium", 25000 * 365L * 86400000L),
            new TemporalInterval("50millennium", 50000 * 365L * 86400000L),
            new TemporalInterval("100millennium", 100000 * 365L * 86400000L)
        };

        // Find the best interval that gives us the right number of ticks
        TemporalInterval bestInterval = intervals[intervals.length - 1]; // Default to decade
        for (TemporalInterval interval : intervals) {
            long expectedTicks = timeRangeMs / interval.durationMs;
            if (expectedTicks >= targetTicks / 2 && expectedTicks <= targetTicks * 2) {
                bestInterval = interval;
                break;
            }
        }

        // Generate ticks at temporal boundaries
        LocalDateTime startDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(startTimeMs), ZoneOffset.UTC);
        LocalDateTime endDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(endTimeMs), ZoneOffset.UTC);

        LocalDateTime current = findNextBoundary(startDateTime, bestInterval);

        while (current.isBefore(endDateTime) || current.isEqual(endDateTime)) {
            long tickTimeMs = current.toInstant(ZoneOffset.UTC).toEpochMilli();
            if (tickTimeMs >= startTimeMs && tickTimeMs <= endTimeMs) {
                ticks.add(tickTimeMs);
            }
            current = advanceToBoundary(current, bestInterval);

            // Safety check to prevent infinite loops
            if (ticks.size() > 20) break;
        }

        // Ensure we have at least 2 ticks - add start/end if needed
        if (ticks.size() < 2) {
            ticks.clear();
            ticks.add(startTimeMs);
            if (timeRangeMs > 3600000) { // If range > 1 hour, add middle point
                ticks.add(startTimeMs + timeRangeMs / 2);
            }
            ticks.add(endTimeMs);
        }

        return ticks;
    }

    private static class TemporalInterval {
        final String type;
        final long durationMs;

        TemporalInterval(String type, long durationMs) {
            this.type = type;
            this.durationMs = durationMs;
        }
    }

    private LocalDateTime findNextBoundary(LocalDateTime dateTime, TemporalInterval interval) {
        switch (interval.type) {
            case "hour":
                return dateTime.withMinute(0).withSecond(0).withNano(0).plusHours(1);
            case "6hour":
                int hour6 = (dateTime.getHour() / 6) * 6;
                LocalDateTime next6h = dateTime.withHour(hour6).withMinute(0).withSecond(0).withNano(0);
                return next6h.isAfter(dateTime) ? next6h : next6h.plusHours(6);
            case "12hour":
                int hour12 = (dateTime.getHour() / 12) * 12;
                LocalDateTime next12h = dateTime.withHour(hour12).withMinute(0).withSecond(0).withNano(0);
                return next12h.isAfter(dateTime) ? next12h : next12h.plusHours(12);
            case "day":
                return dateTime.withHour(0).withMinute(0).withSecond(0).withNano(0).plusDays(1);
            case "week":
                // Find next Monday
                LocalDateTime nextWeek = dateTime.withHour(0).withMinute(0).withSecond(0).withNano(0);
                while (nextWeek.getDayOfWeek().getValue() != 1) { // Monday = 1
                    nextWeek = nextWeek.plusDays(1);
                }
                return nextWeek.isAfter(dateTime) ? nextWeek : nextWeek.plusWeeks(1);
            case "month":
                return dateTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0).plusMonths(1);
            case "quarter":
                int quarter = ((dateTime.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDateTime nextQuarter = dateTime.withMonth(quarter).withDayOfMonth(1)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);
                return nextQuarter.isAfter(dateTime) ? nextQuarter : nextQuarter.plusMonths(3);
            case "year":
                return dateTime.withDayOfYear(1).withHour(0).withMinute(0).withSecond(0).withNano(0).plusYears(1);
            case "5year":
                int year5 = (dateTime.getYear() / 5) * 5;
                LocalDateTime next5y = LocalDateTime.of(year5, 1, 1, 0, 0, 0);
                return next5y.isAfter(dateTime) ? next5y : next5y.plusYears(5);
            case "decade":
                int decade = (dateTime.getYear() / 10) * 10;
                LocalDateTime nextDecade = LocalDateTime.of(decade, 1, 1, 0, 0, 0);
                return nextDecade.isAfter(dateTime) ? nextDecade : nextDecade.plusYears(10);
            case "25year":
                int year25 = (dateTime.getYear() / 25) * 25;
                LocalDateTime next25y = LocalDateTime.of(year25, 1, 1, 0, 0, 0);
                return next25y.isAfter(dateTime) ? next25y : next25y.plusYears(25);
            case "50year":
                int year50 = (dateTime.getYear() / 50) * 50;
                LocalDateTime next50y = LocalDateTime.of(year50, 1, 1, 0, 0, 0);
                return next50y.isAfter(dateTime) ? next50y : next50y.plusYears(50);
            case "century":
                int century = (dateTime.getYear() / 100) * 100;
                LocalDateTime nextCentury = LocalDateTime.of(century, 1, 1, 0, 0, 0);
                return nextCentury.isAfter(dateTime) ? nextCentury : nextCentury.plusYears(100);
            case "250year":
                int year250 = (dateTime.getYear() / 250) * 250;
                LocalDateTime next250y = LocalDateTime.of(year250, 1, 1, 0, 0, 0);
                return next250y.isAfter(dateTime) ? next250y : next250y.plusYears(250);
            case "500year":
                int year500 = (dateTime.getYear() / 500) * 500;
                LocalDateTime next500y = LocalDateTime.of(year500, 1, 1, 0, 0, 0);
                return next500y.isAfter(dateTime) ? next500y : next500y.plusYears(500);
            case "millennium":
                int millennium = (dateTime.getYear() / 1000) * 1000;
                LocalDateTime nextMillennium = LocalDateTime.of(millennium, 1, 1, 0, 0, 0);
                return nextMillennium.isAfter(dateTime) ? nextMillennium : nextMillennium.plusYears(1000);
            case "2500year":
                int year2500 = (dateTime.getYear() / 2500) * 2500;
                LocalDateTime next2500y = LocalDateTime.of(year2500, 1, 1, 0, 0, 0);
                return next2500y.isAfter(dateTime) ? next2500y : next2500y.plusYears(2500);
            case "5millennium":
                int millennium5 = (dateTime.getYear() / 5000) * 5000;
                LocalDateTime next5millennium = LocalDateTime.of(millennium5, 1, 1, 0, 0, 0);
                return next5millennium.isAfter(dateTime) ? next5millennium : next5millennium.plusYears(5000);
            case "10millennium":
                int millennium10 = (dateTime.getYear() / 10000) * 10000;
                LocalDateTime next10millennium = LocalDateTime.of(millennium10, 1, 1, 0, 0, 0);
                return next10millennium.isAfter(dateTime) ? next10millennium : next10millennium.plusYears(10000);
            case "25millennium":
                int millennium25 = (dateTime.getYear() / 25000) * 25000;
                LocalDateTime next25millennium = LocalDateTime.of(millennium25, 1, 1, 0, 0, 0);
                return next25millennium.isAfter(dateTime) ? next25millennium : next25millennium.plusYears(25000);
            case "50millennium":
                int millennium50 = (dateTime.getYear() / 50000) * 50000;
                LocalDateTime next50millennium = LocalDateTime.of(millennium50, 1, 1, 0, 0, 0);
                return next50millennium.isAfter(dateTime) ? next50millennium : next50millennium.plusYears(50000);
            case "100millennium":
                int millennium100 = (dateTime.getYear() / 100000) * 100000;
                LocalDateTime next100millennium = LocalDateTime.of(millennium100, 1, 1, 0, 0, 0);
                return next100millennium.isAfter(dateTime) ? next100millennium : next100millennium.plusYears(100000);
            default:
                return dateTime.plusHours(1);
        }
    }

    private LocalDateTime advanceToBoundary(LocalDateTime dateTime, TemporalInterval interval) {
        switch (interval.type) {
            case "hour":
                return dateTime.plusHours(1);
            case "6hour":
                return dateTime.plusHours(6);
            case "12hour":
                return dateTime.plusHours(12);
            case "day":
                return dateTime.plusDays(1);
            case "week":
                return dateTime.plusWeeks(1);
            case "month":
                return dateTime.plusMonths(1);
            case "quarter":
                return dateTime.plusMonths(3);
            case "year":
                return dateTime.plusYears(1);
            case "5year":
                return dateTime.plusYears(5);
            case "decade":
                return dateTime.plusYears(10);
            case "25year":
                return dateTime.plusYears(25);
            case "50year":
                return dateTime.plusYears(50);
            case "century":
                return dateTime.plusYears(100);
            case "250year":
                return dateTime.plusYears(250);
            case "500year":
                return dateTime.plusYears(500);
            case "millennium":
                return dateTime.plusYears(1000);
            case "2500year":
                return dateTime.plusYears(2500);
            case "5millennium":
                return dateTime.plusYears(5000);
            case "10millennium":
                return dateTime.plusYears(10000);
            case "25millennium":
                return dateTime.plusYears(25000);
            case "50millennium":
                return dateTime.plusYears(50000);
            case "100millennium":
                return dateTime.plusYears(100000);
            default:
                return dateTime.plusHours(1);
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