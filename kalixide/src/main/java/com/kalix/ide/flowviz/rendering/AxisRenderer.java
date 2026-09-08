package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.style.DashStyle;
import com.kalix.ide.flowviz.transform.YAxisScale;
import com.kalix.ide.utils.TimeFormatUtil;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.DoubleUnaryOperator;

import static com.kalix.ide.flowviz.transform.PlotTypeTransformer.PERCENTILE_SCALE;

/**
 * Handles rendering of grid lines, axis ticks, and axis labels for time series plots.
 * Works in conjunction with TemporalAxisCalculator to provide synchronized
 * grid and axis rendering.
 */
public class AxisRenderer {

    // Constants for rendering
    private static final float GRID_STROKE_WIDTH = 0.5f;
    private static final float AXIS_STROKE_WIDTH = 1.0f;
    private static final int MIN_TARGET_TICKS = 3;
    private static final int VALUE_AXIS_MIN_SPACING = 40;
    private static final int TICK_MARK_LENGTH = 5;
    private static final int TIME_LABEL_OFFSET = 18;
    private static final int VALUE_LABEL_OFFSET = 8;
    private static final int TIME_TITLE_OFFSET = 40;

    /** Round mantissas to place within each decade of a LOG axis, coarsest set first. */
    private static final double[][] LOG_DECADE_SUBDIVISIONS = {
        {1},
        {1, 2, 5},
        {1, 2, 3, 4, 5, 6, 7, 8, 9},
    };
    /** Slack for a tick sitting on a viewport edge, in decades (log10 rounding). */
    private static final double LOG_TICK_EPSILON = 1e-9;
    /** How far the threshold marker is shifted from the grid colour, towards the foreground. */
    private static final int THRESHOLD_CONTRAST_SHIFT = 80;

    private final TemporalAxisCalculator temporalCalculator;

    // Decimal places for NUMERIC x-axis labels, computed per draw call
    private int numericDecimalPlaces;

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
        } else if (viewport.getXAxisType() == XAxisType.COUNT) {
            timeTicks = calculateCountTicks(viewport);
        } else if (viewport.getXAxisType() == XAxisType.NUMERIC) {
            timeTicks = calculateNumericTicks(viewport);
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
        double startPercentile = (double) viewport.getStartTimeMs() / PERCENTILE_SCALE;
        double endPercentile = (double) viewport.getEndTimeMs() / PERCENTILE_SCALE;
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
                long fakeTimestamp = (long) (currentPercentile * PERCENTILE_SCALE);
                ticks.add(fakeTimestamp);
            }
            currentPercentile += tickInterval;
        }

        return ticks;
    }

    /**
     * Calculates optimal count tick positions for iteration/evaluation plots.
     * For COUNT axis type, timestamps represent actual count values.
     */
    private List<Long> calculateCountTicks(ViewPort viewport) {
        List<Long> ticks = new ArrayList<>();

        long startCount = viewport.getStartTimeMs();
        long endCount = viewport.getEndTimeMs();
        long range = endCount - startCount;

        if (range <= 0) return ticks;

        // Choose tick interval based on range
        long tickInterval;
        if (range > 10000) {
            tickInterval = roundToNiceCount(range / 5);  // ~5 ticks
        } else if (range > 1000) {
            tickInterval = roundToNiceCount(range / 8);  // ~8 ticks
        } else if (range > 100) {
            tickInterval = roundToNiceCount(range / 10); // ~10 ticks
        } else if (range > 20) {
            tickInterval = 10;
        } else if (range > 10) {
            tickInterval = 5;
        } else {
            tickInterval = 1;
        }

        // Generate ticks
        long currentCount = (startCount / tickInterval) * tickInterval;
        while (currentCount <= endCount) {
            if (currentCount >= 0) {
                ticks.add(currentCount);
            }
            currentCount += tickInterval;
        }

        return ticks;
    }

    /**
     * Calculates optimal tick positions for a numeric (non-time, non-percentile) X-axis.
     * Fake timestamps encode real values via NUMERIC_SCALE.
     */
    private List<Long> calculateNumericTicks(ViewPort viewport) {
        List<Long> ticks = new ArrayList<>();

        long scale = com.kalix.ide.flowviz.transform.PlotTypeTransformer.NUMERIC_SCALE;

        // Decode fake timestamps back to real values
        double startValue = (double) viewport.getStartTimeMs() / scale;
        double endValue = (double) viewport.getEndTimeMs() / scale;
        double range = endValue - startValue;

        if (range <= 0) return ticks;

        // Same nice, counted placement as the value axis. Counted matters here too: a
        // double-mass plot encodes cumulative volume x 1e6, and at large volumes zoomed
        // tight the interval drops under half an ulp, where an accumulating loop spins.
        int targetTicks = Math.max(MIN_TARGET_TICKS, Math.min(10, viewport.getPlotWidth() / 80));
        for (double value : evenTicks(startValue, endValue, targetTicks, DoubleUnaryOperator.identity())) {
            ticks.add((long) (value * scale));
        }
        return ticks;
    }

    /**
     * Rounds a count interval to a nice number (1, 2, 5, 10, 20, 50, 100, ...).
     */
    private long roundToNiceCount(long interval) {
        if (interval <= 0) return 1;

        long magnitude = (long) Math.pow(10, Math.floor(Math.log10(interval)));
        long normalizedInterval = interval / magnitude;

        if (normalizedInterval <= 1) return magnitude;
        if (normalizedInterval <= 2) return 2 * magnitude;
        if (normalizedInterval <= 5) return 5 * magnitude;
        return 10 * magnitude;
    }

    /**
     * Calculates the value axis tick positions for the viewport's scale.
     *
     * <p>LINEAR and SQRT space ticks evenly in transformed space, so they land evenly on
     * screen. LOG places them at values a modeller reads as round (see {@link #logTicks});
     * even spacing in log space would label 10^0.5 as 31.62 whenever the nice interval
     * fell below one decade -- correct positions, unreadable numbers.</p>
     *
     * @param viewport The current viewport
     * @return List of value tick positions (in data space)
     */
    public List<Double> calculateValueTicks(ViewPort viewport) {
        // Transformed bounds (viewport handles invalid bounds gracefully)
        double transformedMin = viewport.getTransformedMin();
        double transformedMax = viewport.getTransformedMax();
        // A non-finite bound (padding overflowed past 1e308) gives an empty axis, not a
        // decade loop to Integer.MAX_VALUE
        if (!Double.isFinite(transformedMin) || !Double.isFinite(transformedMax)
                || transformedMax - transformedMin <= 0) {
            return new ArrayList<>();
        }

        int numTicks = Math.max(MIN_TARGET_TICKS, Math.min(10, viewport.getPlotHeight() / VALUE_AXIS_MIN_SPACING));
        YAxisScale yAxisScale = viewport.getYAxisScale();
        return switch (yAxisScale) {
            case LINEAR, SQRT -> evenTicks(transformedMin, transformedMax, numTicks, yAxisScale::inverseTransform);
            case LOG -> logTicks(transformedMin, transformedMax, numTicks);
        };
    }

    /**
     * Ticks at a nice interval (1, 2, 5 x 10^k) evenly spaced over [min, max], mapped to
     * data space by {@code toData}. Starts at the nice multiple at or below {@code min},
     * so the first tick may fall just off-plot; the drawing code clips it.
     */
    private List<Double> evenTicks(double min, double max, int numTicks, DoubleUnaryOperator toData) {
        List<Double> ticks = new ArrayList<>();
        double tickInterval = roundToNiceValueInterval((max - min) / (numTicks - 1));
        if (!(tickInterval > 0) || !Double.isFinite(tickInterval)) return ticks; // span below the doubles' floor
        double first = Math.floor(min / tickInterval) * tickInterval;

        // Counted, not accumulated: once the interval is under half an ulp of the value
        // (a view zoomed to [1, 1 + 2e-16]) `current += interval` never advances and an
        // accumulating loop hangs the EDT on every paint. Coincident ticks are harmless.
        int count = 1 + (int) Math.min(4L * numTicks, (long) Math.floor((max + tickInterval / 2 - first) / tickInterval));
        for (int i = 0; i < count; i++) {
            ticks.add(toData.applyAsDouble(first + i * tickInterval));
        }
        return ticks;
    }

    /**
     * Ticks for a LOG axis, at values a modeller reads as round: whole decades when the
     * view spans many; 1-2-5 or 1..9 within each decade when it spans few; and plain
     * data-space steps once it spans less than a factor of about two, where those
     * mantissas run out and log is near enough linear that even spacing reads fine.
     *
     * @param transformedMin viewport minimum, in decades
     * @param transformedMax viewport maximum, in decades
     * @param numTicks target tick count for the plot height
     */
    private List<Double> logTicks(double transformedMin, double transformedMax, int numTicks) {
        double decades = transformedMax - transformedMin;
        double decadeInterval = roundToNiceValueInterval(decades / (numTicks - 1));
        if (decadeInterval >= 1) {
            // A whole number of decades per tick: even spacing lands exactly on 10^k
            return evenTicks(transformedMin, transformedMax, numTicks, t -> Math.pow(10, t));
        }

        // Less than a decade per tick: subdivide each decade with round mantissas, the
        // densest set that still fits the target count (the same budget the even
        // algorithm works to, so spacing stays comparable across scales)
        List<Double> ticks = new ArrayList<>();
        for (double[] mantissas : LOG_DECADE_SUBDIVISIONS) {
            List<Double> candidate = decadeTicks(transformedMin, transformedMax, mantissas);
            if (candidate.size() > numTicks + 1) break;
            ticks = candidate;
        }
        if (ticks.size() >= MIN_TARGET_TICKS) return ticks;

        // Under a factor of ~2 end to end even 1..9 gives too few (9.5..19 holds only
        // 10): even data-space steps do better there, and log is near enough linear
        // that their spacing reads fine. Keep the round set when the steps do no better
        // (a tiny budget over a wide span), so a step never lands on 0 or off-plot.
        double minValue = Math.pow(10, transformedMin);
        double maxValue = Math.pow(10, transformedMax);
        List<Double> evenSteps = new ArrayList<>();
        for (double step : evenTicks(minValue, maxValue, numTicks, DoubleUnaryOperator.identity())) {
            if (step >= minValue && step <= maxValue) evenSteps.add(step);
        }
        return evenSteps.size() > ticks.size() ? evenSteps : ticks;
    }

    /** Every {@code mantissa x 10^k} within [transformedMin, transformedMax], ascending. */
    private List<Double> decadeTicks(double transformedMin, double transformedMax, double[] mantissas) {
        List<Double> ticks = new ArrayList<>();
        int firstDecade = (int) Math.floor(transformedMin);
        int lastDecade = (int) Math.ceil(transformedMax);
        for (int decade = firstDecade; decade <= lastDecade; decade++) {
            double base = Math.pow(10, decade);
            for (double mantissa : mantissas) {
                double transformed = decade + Math.log10(mantissa);
                if (transformed >= transformedMin - LOG_TICK_EPSILON
                        && transformed <= transformedMax + LOG_TICK_EPSILON) {
                    ticks.add(mantissa * base);
                }
            }
        }
        return ticks;
    }

    /**
     * Draws the grid lines aligned with axis ticks.
     *
     * @param g2d Graphics context
     * @param viewport Current viewport
     * @param axisInfo Pre-calculated axis information
     * @param colors The current theme's plot colours (resolved once per paint)
     */
    public void drawGrid(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo, PlotColors colors) {
        g2d.setColor(colors.grid);
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
     * Marks the linear-region boundaries of a scale that has one (currently only
     * {@link YAxisScale#SYMLOG}) with a dashed horizontal line at +/-L.
     *
     * <p>Without this the change in axis behaviour at the threshold is invisible: the same
     * vertical distance means a fixed increment below it and a decade above it. Draws nothing
     * for scales with no linear region, and each line is clipped away individually once the
     * viewport is panned past it.</p>
     *
     * @param g2d Graphics context
     * @param viewport Current viewport
     * @param colors The current theme's plot colours (resolved once per paint)
     */
    public void drawScaleThresholds(Graphics2D g2d, ViewPort viewport, PlotColors colors) {
        OptionalDouble threshold = viewport.getYAxisScale().linearThreshold();
        if (threshold.isEmpty()) return;

        // Derived from the grid role rather than a theme key of its own, so the marker keeps
        // the grid's relationship to the background in every theme while reading as deliberate.
        g2d.setColor(PlotColors.shiftForContrast(colors.grid, colors.background, THRESHOLD_CONTRAST_SHIFT));
        g2d.setStroke(new BasicStroke(GRID_STROKE_WIDTH, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10.0f, DashStyle.DASHED.dashArray(), 0.0f));

        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();

        double linearThreshold = threshold.getAsDouble();
        for (double value : new double[]{linearThreshold, -linearThreshold}) {
            int screenY = viewport.valueToScreenY(value);
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
     * @param colors The current theme's plot colours (resolved once per paint)
     */
    public void drawAxes(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo, PlotColors colors) {
        g2d.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));

        drawTimeAxis(g2d, viewport, axisInfo, colors);
        drawValueAxis(g2d, viewport, axisInfo, colors);
        drawAxisTitles(g2d, viewport, axisInfo, colors);
    }

    /**
     * Draws the time axis with ticks and labels.
     */
    private void drawTimeAxis(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo, PlotColors colors) {
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();

        FontMetrics fm = g2d.getFontMetrics();

        // Pre-compute decimal places for NUMERIC labels (consistent with Y-axis approach)
        if (viewport.getXAxisType() == XAxisType.NUMERIC && axisInfo.timeTicks.size() >= 2) {
            long scale = com.kalix.ide.flowviz.transform.PlotTypeTransformer.NUMERIC_SCALE;
            List<Double> decodedTicks = new ArrayList<>();
            for (Long tick : axisInfo.timeTicks) {
                decodedTicks.add((double) tick / scale);
            }
            numericDecimalPlaces = decimalPlacesForTicks(decodedTicks);
        }

        // Tick interval drives the label format for time axes (e.g. hourly ticks need
        // hour-of-day in the label; daily ticks don't). Falls back to the full range
        // when there's only one tick.
        long tickIntervalMs = axisInfo.timeTicks.size() >= 2
            ? axisInfo.timeTicks.get(1) - axisInfo.timeTicks.get(0)
            : axisInfo.timeRangeMs;

        for (Long tickTime : axisInfo.timeTicks) {
            int screenX = viewport.timeToScreenX(tickTime);

            if (screenX >= plotX && screenX <= plotX + plotWidth) {
                // Draw tick mark
                g2d.setColor(colors.axis);
                g2d.drawLine(screenX, plotY + plotHeight, screenX, plotY + plotHeight + TICK_MARK_LENGTH);

                // Draw label
                String timeLabel = formatXAxisLabel(tickTime, tickIntervalMs, viewport.getXAxisType());
                int labelWidth = fm.stringWidth(timeLabel);
                g2d.setColor(colors.label);
                g2d.drawString(timeLabel, screenX - labelWidth / 2,
                             plotY + plotHeight + TIME_LABEL_OFFSET);
            }
        }
    }

    /**
     * Draws the value axis with ticks and labels.
     */
    private void drawValueAxis(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo, PlotColors colors) {
        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotHeight = viewport.getPlotHeight();

        FontMetrics fm = g2d.getFontMetrics();
        int decimalPlaces = decimalPlacesForTicks(axisInfo.valueTicks);

        for (Double tickValue : axisInfo.valueTicks) {
            int screenY = viewport.valueToScreenY(tickValue);

            if (screenY >= plotY && screenY <= plotY + plotHeight) {
                // Draw tick mark
                g2d.setColor(colors.axis);
                g2d.drawLine(plotX - TICK_MARK_LENGTH, screenY, plotX, screenY);

                // Draw label
                String valueLabel = formatValue(tickValue, decimalPlaces);
                int labelHeight = fm.getAscent();
                g2d.setColor(colors.label);
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
    private String formatXAxisLabel(long value, long tickIntervalMs, XAxisType xAxisType) {
        if (xAxisType == XAxisType.PERCENTILE) {
            return formatPercentile(value);
        } else if (xAxisType == XAxisType.COUNT) {
            return formatCount(value);
        } else if (xAxisType == XAxisType.NUMERIC) {
            return formatNumeric(value);
        } else {
            return TimeFormatUtil.formatForTickInterval(value, tickIntervalMs);
        }
    }

    /**
     * Formats a fake timestamp as a percentile label.
     */
    private String formatPercentile(long fakeTimestamp) {
        double percentile = (double) fakeTimestamp / PERCENTILE_SCALE;

        // Format with appropriate precision
        if (percentile == Math.floor(percentile)) {
            return String.format("%.0f%%", percentile);
        } else {
            return String.format("%.1f%%", percentile);
        }
    }

    /**
     * Formats a count value for axis labels.
     */
    private String formatCount(long count) {
        // Use thousands separator for large numbers
        if (count >= 1000) {
            return String.format("%,d", count);
        } else {
            return String.format("%d", count);
        }
    }

    /**
     * Formats a fake timestamp as a numeric value label.
     * Decodes from NUMERIC_SCALE encoding and formats consistently with Y-axis labels.
     */
    private String formatNumeric(long fakeTimestamp) {
        double value = (double) fakeTimestamp / com.kalix.ide.flowviz.transform.PlotTypeTransformer.NUMERIC_SCALE;
        return formatValue(value, numericDecimalPlaces);
    }

    /**
     * Calculates the number of decimal places needed to distinguish tick labels,
     * based on the spacing between consecutive ticks.
     */
    private int decimalPlacesForTicks(List<Double> ticks) {
        if (ticks.size() < 2) return 9;

        double minSpacing = Double.MAX_VALUE;
        for (int i = 1; i < ticks.size(); i++) {
            double spacing = Math.abs(ticks.get(i) - ticks.get(i - 1));
            if (spacing > 0) {
                minSpacing = Math.min(minSpacing, spacing);
            }
        }

        if (minSpacing == Double.MAX_VALUE) return 9;

        // Enough decimal places for 2 significant figures of the tick interval
        int places = (int) Math.ceil(-Math.log10(minSpacing)) + 2;
        return Math.max(0, Math.min(places, 9));
    }

    /**
     * Formats a value for axis labels with the given number of decimal places.
     */
    private String formatValue(double value, int decimalPlaces) {
        String formatted = String.format("%." + decimalPlaces + "f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0*$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }

    /**
     * Draws axis titles for both time and value axes.
     */
    private void drawAxisTitles(Graphics2D g2d, ViewPort viewport, AxisInfo axisInfo, PlotColors colors) {
        g2d.setColor(colors.label);
        g2d.setFont(new Font("Arial", Font.BOLD, 12));
        FontMetrics titleFm = g2d.getFontMetrics();

        int plotX = viewport.getPlotX();
        int plotY = viewport.getPlotY();
        int plotWidth = viewport.getPlotWidth();
        int plotHeight = viewport.getPlotHeight();

        // Draw X-axis title (dynamic based on axis type)
        String xTitle;
        if (viewport.getXAxisType() == XAxisType.PERCENTILE) {
            xTitle = "Exceedance Probability (%)";
        } else if (viewport.getXAxisType() == XAxisType.COUNT) {
            xTitle = "Count";
        } else if (viewport.getXAxisType() == XAxisType.NUMERIC) {
            xTitle = "Reference Value";
        } else {
            xTitle = "Time";
        }
        int xTitleWidth = titleFm.stringWidth(xTitle);
        int xTitleX = plotX + (plotWidth - xTitleWidth) / 2;
        int xTitleY = plotY + plotHeight + TIME_TITLE_OFFSET;
        g2d.drawString(xTitle, xTitleX, xTitleY);

        // Calculate dynamic Y-axis title offset based on maximum label width
        g2d.setFont(new Font("Arial", Font.PLAIN, 10)); // Use same font as axis labels
        FontMetrics labelFm = g2d.getFontMetrics();
        int maxLabelWidth = 0;
        int decimalPlaces = decimalPlacesForTicks(axisInfo.valueTicks);

        for (Double tickValue : axisInfo.valueTicks) {
            String valueLabel = formatValue(tickValue, decimalPlaces);
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