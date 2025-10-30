package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles rendering of grid lines, axis ticks, and axis labels for time series plots.
 * Works in conjunction with TemporalAxisCalculator to provide synchronized
 * grid and axis rendering.
 */
public class AxisRenderer {

    // Constants for rendering
    private static final Color GRID_COLOR = new Color(240, 240, 240);
    private static final float GRID_STROKE_WIDTH = 0.5f;
    private static final float AXIS_STROKE_WIDTH = 1.0f;
    private static final int MIN_TARGET_TICKS = 3;
    private static final int VALUE_AXIS_MIN_SPACING = 40;
    private static final int TICK_MARK_LENGTH = 5;
    private static final int TIME_LABEL_OFFSET = 18;
    private static final int VALUE_LABEL_OFFSET = 8;
    private static final int TIME_TITLE_OFFSET = 40;
    private static final int VALUE_TITLE_OFFSET = 55;

    private final TemporalAxisCalculator temporalCalculator;

    public AxisRenderer() {
        this.temporalCalculator = new TemporalAxisCalculator();
    }

    /**
     * Contains pre-calculated axis information to avoid duplicate calculations.
     */
    public static class AxisInfo {
        public final List<Long> timeTicks;
        public final List<Double> valueTicks;
        public final long timeRangeMs;

        public AxisInfo(List<Long> timeTicks, List<Double> valueTicks, long timeRangeMs) {
            this.timeTicks = timeTicks;
            this.valueTicks = valueTicks;
            this.timeRangeMs = timeRangeMs;
        }
    }

    /**
     * Calculates optimal axis tick positions for both time and value axes.
     *
     * @param viewport The current viewport
     * @return AxisInfo containing tick positions and time range
     */
    public AxisInfo calculateAxisInfo(ViewPort viewport) {
        List<Long> timeTicks;

        // Calculate X-axis ticks based on axis type
        if (viewport.getXAxisType() == XAxisType.PERCENTILE) {
            timeTicks = calculatePercentileTicks(viewport);
        } else {
            // TIME: Calculate time ticks using temporal boundaries
            timeTicks = temporalCalculator.calculateTemporalBoundaryTicks(
                viewport.getStartTimeMs(), viewport.getEndTimeMs(), viewport.getPlotWidth());
        }

        // Calculate value ticks using nice intervals
        List<Double> valueTicks = calculateValueTicks(viewport);

        long timeRangeMs = viewport.getTimeRangeMs();

        return new AxisInfo(timeTicks, valueTicks, timeRangeMs);
    }

    /**
     * Calculates optimal percentile tick positions for exceedance plots.
     * Returns fake timestamps that represent percentile values.
     */
    private List<Long> calculatePercentileTicks(ViewPort viewport) {
        List<Long> ticks = new ArrayList<>();

        // Convert fake timestamps back to percentiles
        double startPercentile = viewport.getStartTimeMs() / 1_000_000.0;
        double endPercentile = viewport.getEndTimeMs() / 1_000_000.0;
        double range = endPercentile - startPercentile;

        // Choose tick interval based on zoom level
        double tickInterval;
        if (range > 80) {
            tickInterval = 20.0;  // 0, 20, 40, 60, 80, 100
        } else if (range > 40) {
            tickInterval = 10.0;  // 0, 10, 20, ..., 100
        } else if (range > 20) {
            tickInterval = 5.0;   // 0, 5, 10, ..., 100
        } else if (range > 10) {
            tickInterval = 2.0;   // 0, 2, 4, ..., 100
        } else {
            tickInterval = 1.0;   // 0, 1, 2, ..., 100
        }

        // Generate ticks
        double currentPercentile = Math.floor(startPercentile / tickInterval) * tickInterval;
        while (currentPercentile <= endPercentile + tickInterval / 2) {
            if (currentPercentile >= 0 && currentPercentile <= 100) {
                // Convert percentile to fake timestamp
                long fakeTimestamp = (long)(currentPercentile * 1_000_000);
                ticks.add(fakeTimestamp);
            }
            currentPercentile += tickInterval;
        }

        return ticks;
    }

    /**
     * Calculates optimal value axis tick positions.
     * Works in transformed space to ensure even distribution on screen.
     *
     * @param viewport The current viewport
     * @return List of value tick positions (in data space)
     */
    public List<Double> calculateValueTicks(ViewPort viewport) {
        List<Double> ticks = new ArrayList<>();

        // Get transformed bounds (viewport handles invalid bounds gracefully)
        double transformedMin = viewport.getTransformedMin();
        double transformedMax = viewport.getTransformedMax();

        double transformedRange = transformedMax - transformedMin;
        if (transformedRange <= 0) return ticks;

        // Calculate appropriate tick interval in transformed space
        int numTicks = Math.max(MIN_TARGET_TICKS, Math.min(10, viewport.getPlotHeight() / VALUE_AXIS_MIN_SPACING));
        double tickInterval = transformedRange / (numTicks - 1);
        tickInterval = roundToNiceValueInterval(tickInterval);

        // Generate ticks in transformed space
        double currentTransformedValue = Math.floor(transformedMin / tickInterval) * tickInterval;

        YAxisScale yAxisScale = viewport.getYAxisScale();
        while (currentTransformedValue <= transformedMax + tickInterval / 2) {
            // Inverse transform back to data space for storage and display
            double dataSpaceValue = yAxisScale.inverseTransform(currentTransformedValue);
            ticks.add(dataSpaceValue);
            currentTransformedValue += tickInterval;
        }

        return ticks;
    }

    /**
     * Draws the grid lines aligned with axis ticks.
     *
     * @param g2d Graphics context
     * @param viewport Current viewport
     * @param axisInfo Pre-calculated axis information
     */
    public void drawGrid(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo) {
        g2d.setColor(GRID_COLOR);
        g2d.setStroke(new BasicStroke(GRID_STROKE_WIDTH));

        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();

        // Draw vertical grid lines aligned with time axis ticks
        for (Long tickTime : axisInfo.timeTicks) {
            int screenX = viewport.timeToScreenX(tickTime);
            if (screenX >= plotX && screenX <= plotX + plotWidth) {
                g2d.drawLine(screenX, plotY, screenX, plotY + plotHeight);
            }
        }

        // Draw horizontal grid lines aligned with value axis ticks
        for (Double tickValue : axisInfo.valueTicks) {
            int screenY = viewport.valueToScreenY(tickValue);
            if (screenY >= plotY && screenY <= plotY + plotHeight) {
                g2d.drawLine(plotX, screenY, plotX + plotWidth, screenY);
            }
        }
    }

    /**
     * Draws both time and value axes with ticks and labels.
     *
     * @param g2d Graphics context
     * @param viewport Current viewport
     * @param axisInfo Pre-calculated axis information
     */
    public void drawAxes(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo) {
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));

        drawTimeAxis(g2d, viewport, axisInfo);
        drawValueAxis(g2d, viewport, axisInfo);
        drawAxisTitles(g2d, viewport, axisInfo);
    }

    /**
     * Draws the time axis with ticks and labels.
     */
    private void drawTimeAxis(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo) {
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();

        FontMetrics fm = g2d.getFontMetrics();

        for (Long tickTime : axisInfo.timeTicks) {
            int screenX = viewport.timeToScreenX(tickTime);

            if (screenX >= plotX && screenX <= plotX + plotWidth) {
                // Draw tick mark
                g2d.drawLine(screenX, plotY + plotHeight, screenX, plotY + plotHeight + TICK_MARK_LENGTH);

                // Draw label
                String timeLabel = formatXAxisLabel(tickTime, axisInfo.timeRangeMs, viewport.getXAxisType());
                int labelWidth = fm.stringWidth(timeLabel);
                g2d.drawString(timeLabel, screenX - labelWidth / 2,
                             plotY + plotHeight + TIME_LABEL_OFFSET);
            }
        }
    }

    /**
     * Draws the value axis with ticks and labels.
     */
    private void drawValueAxis(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo) {
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotHeight = viewport.getPlotHeight();

        FontMetrics fm = g2d.getFontMetrics();

        for (Double tickValue : axisInfo.valueTicks) {
            int screenY = viewport.valueToScreenY(tickValue);

            if (screenY >= plotY && screenY <= plotY + plotHeight) {
                // Draw tick mark
                g2d.drawLine(plotX - TICK_MARK_LENGTH, screenY, plotX, screenY);

                // Draw label
                String valueLabel = formatValue(tickValue);
                int labelHeight = fm.getAscent();
                g2d.drawString(valueLabel, plotX - VALUE_LABEL_OFFSET - fm.stringWidth(valueLabel),
                             screenY + labelHeight / 2);
            }
        }
    }

    /**
     * Rounds a value interval to a nice, human-readable number.
     */
    private double roundToNiceValueInterval(double interval) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(interval)));
        double normalizedInterval = interval / magnitude;

        if (normalizedInterval <= 1) return magnitude;
        if (normalizedInterval <= 2) return 2 * magnitude;
        if (normalizedInterval <= 5) return 5 * magnitude;
        return 10 * magnitude;
    }

    /**
     * Formats an X-axis label based on axis type.
     */
    private String formatXAxisLabel(long value, long rangeMs, XAxisType xAxisType) {
        if (xAxisType == XAxisType.PERCENTILE) {
            return formatPercentile(value);
        } else {
            return formatTime(value, rangeMs);
        }
    }

    /**
     * Formats a timestamp for axis labels.
     */
    private String formatTime(long timeMs, long timeRangeMs) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timeMs), ZoneOffset.UTC);

        if (timeRangeMs < 86400000) {  // Less than 1 day - show time
            return dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        } else {  // 1 day or more - always show year with date
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
    }

    /**
     * Formats a fake timestamp as a percentile label.
     */
    private String formatPercentile(long fakeTimestamp) {
        double percentile = fakeTimestamp / 1_000_000.0;

        // Format with appropriate precision
        if (percentile == Math.floor(percentile)) {
            return String.format("%.0f%%", percentile);
        } else {
            return String.format("%.1f%%", percentile);
        }
    }

    /**
     * Formats a value for axis labels.
     */
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

    /**
     * Draws axis titles for both time and value axes.
     */
    private void drawAxisTitles(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo) {
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.BOLD, 12));
        FontMetrics titleFm = g2d.getFontMetrics();

        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();

        // Draw X-axis title (dynamic based on axis type)
        String xTitle = (viewport.getXAxisType() == XAxisType.PERCENTILE) ? "Exceedance Probability (%)" : "Time";
        int xTitleWidth = titleFm.stringWidth(xTitle);
        int xTitleX = plotX + (plotWidth - xTitleWidth) / 2;
        int xTitleY = plotY + plotHeight + TIME_TITLE_OFFSET;
        g2d.drawString(xTitle, xTitleX, xTitleY);

        // Calculate dynamic Y-axis title offset based on maximum label width
        g2d.setFont(new Font("Arial", Font.PLAIN, 10)); // Use same font as axis labels
        FontMetrics labelFm = g2d.getFontMetrics();
        int maxLabelWidth = 0;

        for (Double tickValue : axisInfo.valueTicks) {
            String valueLabel = formatValue(tickValue);
            int labelWidth = labelFm.stringWidth(valueLabel);
            maxLabelWidth = Math.max(maxLabelWidth, labelWidth);
        }

        // Dynamic offset: base spacing + max label width + small padding
        int dynamicOffset = VALUE_LABEL_OFFSET + maxLabelWidth + 15;

        // Draw Y-axis title "Value" (rotated 90 degrees counter-clockwise)
        g2d.setFont(new Font("Arial", Font.BOLD, 12)); // Restore title font
        String yTitle = "Value";
        Graphics2D g2dRotated = (Graphics2D) g2d.create();
        g2dRotated.rotate(-Math.PI / 2);
        int yTitleWidth = titleFm.stringWidth(yTitle);
        int yTitleX = -(plotY + (plotHeight + yTitleWidth) / 2);
        int yTitleY = plotX - dynamicOffset;
        g2dRotated.drawString(yTitle, yTitleX, yTitleY);
        g2dRotated.dispose();
    }
}