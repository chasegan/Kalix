package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

public class ViewPort {
    /// Minimum visible time (x-axis min)
    private final long startTimeMs;
    /// Maximum visible time (x-axis max)
    private final long endTimeMs;
    /// Minimum visible value (y-axis min)
    private final double minValue;
    /// Maximum visible value (y-axis max)
    private final double maxValue;

    // Plot area dimensions
    /// Left edge of the plot area in screen pixels
    private final int plotX;
    /// Top edge of the plot area in screen pixels
    private final int plotY;
    /// Width of the plot area in screen pixels
    private final int plotWidth;
    /// Height of the plot area in screen pixels
    private final int plotHeight;

    // Axis transformations
    private final YAxisScale yAxisScale;
    private final XAxisType xAxisType;

    public ViewPort(long startTimeMs, long endTimeMs, double minValue, double maxValue,
                   int plotX, int plotY, int plotWidth, int plotHeight, YAxisScale yAxisScale, XAxisType xAxisType) {
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.plotX = plotX;
        this.plotY = plotY;
        this.plotWidth = plotWidth;
        this.plotHeight = plotHeight;
        this.yAxisScale = yAxisScale != null ? yAxisScale : YAxisScale.LINEAR;
        this.xAxisType = xAxisType != null ? xAxisType : XAxisType.TIME;
    }
    
    /**
     * Checks that a proposed set of axis limits describes a viewport a user could actually
     * read: X strictly increasing, Y strictly increasing, and Y finite. Throws
     * {@link IllegalArgumentException} with a message fit to show in a dialog.
     *
     * <p>Deliberately a separate check rather than a constructor guard. Degenerate
     * viewports are a legitimate <em>internal</em> state -- {@link #timeToScreenX} handles
     * {@code endTimeMs == startTimeMs} by design, and zoom/pan arithmetic can collapse a
     * range at extreme magnification -- so rejecting them at construction would turn an
     * interaction into a crash. What must be policed is the boundary where a user supplies
     * limits directly: the Set-axis-limits dialog and the axis paste commands, which can
     * otherwise install an inverted or zero-width viewport that feeds a divide-by-zero into
     * the axis transforms.</p>
     *
     * <p>The finite check matters because {@code Double.parseDouble} happily accepts
     * {@code "NaN"} and {@code "Infinity"}, and NaN compares false against everything --
     * so a NaN limit would slip past the ordering test unchallenged.</p>
     *
     * @throws IllegalArgumentException if the limits are inverted, empty, or non-finite
     */
    public static void validateBounds(long startTimeMs, long endTimeMs, double minValue, double maxValue) {
        if (startTimeMs >= endTimeMs) {
            throw new IllegalArgumentException("X min must be less than X max.");
        }
        if (!Double.isFinite(minValue) || !Double.isFinite(maxValue)) {
            throw new IllegalArgumentException("Y limits must be finite numbers.");
        }
        if (minValue >= maxValue) {
            throw new IllegalArgumentException("Y min must be less than Y max.");
        }
    }

    // Transform coordinates between data space and screen space
    public int timeToScreenX(long timeMs) {
        if (endTimeMs == startTimeMs) return plotX;
        return (int) (plotX + ((double)(timeMs - startTimeMs) / (endTimeMs - startTimeMs)) * plotWidth);
    }
    
    public int valueToScreenY(double value) {
        // Apply Y-axis transformation
        double transformedValue = yAxisScale.transform(value);

        // NaN values (invalid for the current scale) return a screen coordinate off-plot
        if (Double.isNaN(transformedValue)) {
            return plotY + plotHeight + 1000; // Far below visible area - won't be drawn
        }

        // Get transformed bounds (handles invalid bounds gracefully)
        double transformedMin = getTransformedMin();
        double transformedMax = getTransformedMax();

        if (transformedMax == transformedMin) return plotY + plotHeight / 2;
        return (int) (plotY + plotHeight - ((transformedValue - transformedMin) / (transformedMax - transformedMin)) * plotHeight);
    }

    /**
     * Decades shown below the maximum (or below 1) when a LOG viewport's minimum is not
     * positive, so a bound the scale cannot show still gives a readable axis.
     */
    private static final double FALLBACK_DECADES = 6.0;

    /**
     * Gets the transformed min value, clamping to valid range if needed.
     * Public helper for axis rendering.
     */
    public double getTransformedMin() {
        double transformedMin = yAxisScale.transform(minValue);
        if (!Double.isNaN(transformedMin)) return transformedMin;

        // Only LOG returns NaN (a non-positive minimum). Show six decades below 1, or
        // below the maximum when that is smaller: a fixed floor above the maximum would
        // invert the axis, and a zoom or pan step from an inverted view stays inverted.
        double transformedMax = yAxisScale.transform(maxValue);
        double ceiling = Double.isNaN(transformedMax) ? 0.0 : Math.min(0.0, transformedMax);
        return ceiling - FALLBACK_DECADES;
    }

    /**
     * Gets the transformed max value, clamping to valid range if needed.
     * Public helper for axis rendering.
     */
    public double getTransformedMax() {
        double transformedMax = yAxisScale.transform(maxValue);
        if (!Double.isNaN(transformedMax)) return transformedMax;
        return getTransformedMin() + FALLBACK_DECADES;
    }

    public long screenXToTime(int screenX) {
        if (plotWidth == 0) return startTimeMs;
        double ratio = (double)(screenX - plotX) / plotWidth;
        return (long) (startTimeMs + ratio * (endTimeMs - startTimeMs));
    }
    
    public double screenYToValue(int screenY) {
        if (plotHeight == 0) return minValue;
        double ratio = (double)(plotY + plotHeight - screenY) / plotHeight;

        // Work in transformed space (handles invalid bounds gracefully)
        double transformedMin = getTransformedMin();
        double transformedMax = getTransformedMax();
        double transformedValue = transformedMin + ratio * (transformedMax - transformedMin);

        // Apply inverse transformation to get back to data space
        return yAxisScale.inverseTransform(transformedValue);
    }
    
    // Check if point is visible
    public boolean isTimeVisible(long timeMs) {
        return timeMs >= startTimeMs && timeMs <= endTimeMs;
    }
    
    public boolean isValueVisible(double value) {
        return value >= minValue && value <= maxValue;
    }
    
    public boolean isPointVisible(long timeMs, double value) {
        return isTimeVisible(timeMs) && isValueVisible(value);
    }
    
    // Viewport manipulation

    /**
     * Zooms both axes by {@code factor} (above 1 zooms in) about the centre of the plot
     * area: the toolbar and keyboard zoom. The value axis is zoomed in transformed space
     * about the middle screen row, not about the data-space midpoint -- on LOG or any
     * other non-linear scale that midpoint sits near the top of the plot, and zooming
     * about it slid the view upward on every press.
     */
    public ViewPort zoom(double factor) {
        long timeRange = endTimeMs - startTimeMs;
        long centerTimeMs = startTimeMs + timeRange / 2;
        long newTimeRange = (long) (timeRange / factor);
        double[] valueBounds = valueBoundsZoomedAbout(factor, 0.5);

        return new ViewPort(centerTimeMs - newTimeRange / 2, centerTimeMs + newTimeRange / 2,
                          valueBounds[0], valueBounds[1],
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
    }

    /**
     * Value bounds after zooming the value axis by {@code factor} (above 1 zooms in) about
     * the value under {@code screenY}, which stays put on screen -- the wheel-zoom
     * contract. Computed in transformed space so the anchor holds on every scale. Returns
     * the current bounds unchanged when the zoomed span is not showable
     * (see {@link #valueBoundsFor}).
     */
    public double[] valueBoundsZoomedAbout(double factor, int screenY) {
        double ratio = plotHeight == 0 ? 0.5 : (double) (plotY + plotHeight - screenY) / plotHeight;
        return valueBoundsZoomedAbout(factor, ratio);
    }

    /** As above, anchored at {@code ratio} of the way up the plot (0 bottom, 1 top). */
    private double[] valueBoundsZoomedAbout(double factor, double ratio) {
        double transformedMin = getTransformedMin();
        double transformedMax = getTransformedMax();
        double anchor = transformedMin + ratio * (transformedMax - transformedMin);
        double newTransformedRange = (transformedMax - transformedMin) / factor;
        double newTransformedMin = anchor - newTransformedRange * ratio;
        return valueBoundsFor(newTransformedMin, newTransformedMin + newTransformedRange);
    }

    /**
     * Value bounds re-centred on {@code value} when it is off-screen, keeping the span in
     * transformed space so the zoom level survives on every scale (a log axis keeps its
     * decade count, not its data-space width). Returns the current bounds unchanged when
     * {@code value} is already on screen, is not showable on this scale (non-finite, or
     * non-positive on LOG), or the centred span would not be (see {@link #valueBoundsFor}).
     *
     * <p>"On screen" is judged in transformed space, against the axis as drawn: on LOG with
     * a non-positive stored minimum the drawn range is the fallback, not the stored bounds,
     * and a data-space compare would call a point below the plot visible.</p>
     */
    public double[] valueBoundsCentredOn(double value) {
        double transformedValue = yAxisScale.transform(value);
        double transformedMin = getTransformedMin();
        double transformedMax = getTransformedMax();
        boolean offScreen = transformedValue < transformedMin || transformedValue > transformedMax;
        if (!offScreen || !Double.isFinite(transformedValue)) {
            return new double[] {minValue, maxValue};
        }
        double halfSpan = (transformedMax - transformedMin) / 2;
        return valueBoundsFor(transformedValue - halfSpan, transformedValue + halfSpan);
    }

    /**
     * Data-space value bounds for a span given in transformed space, or the current
     * bounds when that span no longer maps to values the scale can show. LOG's inverse
     * is {@code 10^t}, which overflows to infinity past ~308 decades and underflows to
     * zero (invalid for LOG) past ~-323. Installing either blanks the axis and leaves the
     * viewport stuck, because every later step is computed from the broken bounds
     * ({@code Inf / 1.1} is still {@code Inf}) and only zoom-to-fit recovers. Refusing
     * the step keeps the last readable view instead, so a runaway wheel zoom-out simply
     * stops. An inverted or empty result is refused for the same reason. Every zoom, pan
     * and recentre of the value axis goes through here.
     */
    private double[] valueBoundsFor(double transformedMin, double transformedMax) {
        double newMinValue = yAxisScale.inverseTransform(transformedMin);
        double newMaxValue = yAxisScale.inverseTransform(transformedMax);
        if (!isShowable(newMinValue) || !isShowable(newMaxValue) || newMinValue >= newMaxValue) {
            return new double[] {minValue, maxValue};
        }
        return new double[] {newMinValue, newMaxValue};
    }

    /** Finite, and inside the scale's domain (LOG cannot show zero or negatives). */
    private boolean isShowable(double value) {
        return Double.isFinite(value) && !Double.isNaN(yAxisScale.transform(value));
    }

    /**
     * Pans the viewport by screen pixel distances.
     * Works correctly with non-linear Y-axis scales by computing deltas in transformed space.
     *
     * @param deltaPixelsX Horizontal pan distance in pixels (negative = pan left)
     * @param deltaPixelsY Vertical pan distance in pixels (positive = pan up)
     * @return New viewport after panning
     */
    public ViewPort panByPixels(int deltaPixelsX, int deltaPixelsY) {
        // Calculate time delta (unchanged)
        long timeRange = endTimeMs - startTimeMs;
        long deltaTime = (long) (-deltaPixelsX * timeRange / (double) plotWidth);
        long newStartTime = startTimeMs + deltaTime;
        long newEndTime = endTimeMs + deltaTime;

        // Calculate value delta in transformed space for correct scaling
        double transformedMin = getTransformedMin();
        double transformedMax = getTransformedMax();
        double transformedRange = transformedMax - transformedMin;

        // Delta in transformed space (positive deltaPixelsY = pan up = increase values)
        double deltaTransformed = deltaPixelsY * transformedRange / (double) plotHeight;

        double[] valueBounds = valueBoundsFor(transformedMin + deltaTransformed,
                                              transformedMax + deltaTransformed);

        return new ViewPort(newStartTime, newEndTime, valueBounds[0], valueBounds[1],
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
    }

    public ViewPort withPlotArea(int plotX, int plotY, int plotWidth, int plotHeight) {
        return new ViewPort(startTimeMs, endTimeMs, minValue, maxValue,
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
    }

    public ViewPort withYAxisScale(YAxisScale yAxisScale) {
        return new ViewPort(startTimeMs, endTimeMs, minValue, maxValue,
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
    }

    public ViewPort withXAxisType(XAxisType xAxisType) {
        return new ViewPort(startTimeMs, endTimeMs, minValue, maxValue,
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
    }
    
    // Calculate visible point density for LOD decisions
    public long getTimeRangeMs() {
        return endTimeMs - startTimeMs;
    }
    
    public double getValueRange() {
        return maxValue - minValue;
    }
    
    // Getters
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public double getMinValue() { return minValue; }
    public double getMaxValue() { return maxValue; }
    public int getPlotX() { return plotX; }
    public int getPlotY() { return plotY; }
    public int getPlotWidth() { return plotWidth; }
    public int getPlotHeight() { return plotHeight; }
    public YAxisScale getYAxisScale() { return yAxisScale; }
    public XAxisType getXAxisType() { return xAxisType; }
    
    @Override
    public String toString() {
        return String.format("ViewPort[time: %d-%d, value: %.3f-%.3f, plot: %d,%d %dx%d]",
            startTimeMs, endTimeMs, minValue, maxValue, plotX, plotY, plotWidth, plotHeight);
    }
}