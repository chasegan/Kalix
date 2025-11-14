package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.transform.YAxisScale;

public class ViewPort {
    private final long startTimeMs;
    private final long endTimeMs;
    private final double minValue;
    private final double maxValue;

    // Plot area dimensions
    private final int plotX;
    private final int plotY;
    private final int plotWidth;
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
     * Returns a valid minimum transformed value when viewport min is invalid for the scale.
     * Only LOG scale can produce NaN (for non-positive values).
     */
    private double getValidMinForScale(YAxisScale scale) {
        return switch(scale) {
            case LOG -> -6.0;  // log10(0.000001) - represents very small positive values
            case SQRT -> yAxisScale.transform(minValue);  // Signed sqrt handles all values
            case LINEAR -> minValue; // Should never be NaN
        };
    }

    /**
     * Returns a valid maximum transformed value when viewport max is invalid for the scale.
     * Only LOG scale can produce NaN (for non-positive values).
     */
    private double getValidMaxForScale(YAxisScale scale, double validMin) {
        return switch(scale) {
            case LOG -> validMin + 6.0;  // Reasonable range
            case SQRT -> yAxisScale.transform(maxValue);  // Signed sqrt handles all values
            case LINEAR -> maxValue; // Should never be NaN
        };
    }

    /**
     * Gets the transformed min value, clamping to valid range if needed.
     * Public helper for axis rendering.
     */
    public double getTransformedMin() {
        double transformedMin = yAxisScale.transform(minValue);
        if (Double.isNaN(transformedMin)) {
            return getValidMinForScale(yAxisScale);
        }
        return transformedMin;
    }

    /**
     * Gets the transformed max value, clamping to valid range if needed.
     * Public helper for axis rendering.
     */
    public double getTransformedMax() {
        double transformedMax = yAxisScale.transform(maxValue);
        if (Double.isNaN(transformedMax)) {
            double validMin = getTransformedMin();
            return getValidMaxForScale(yAxisScale, validMin);
        }
        return transformedMax;
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
    public ViewPort zoom(double factor, long centerTimeMs, double centerValue) {
        // Time zoom (always linear)
        long timeRange = endTimeMs - startTimeMs;
        long newTimeRange = (long) (timeRange / factor);
        long newStartTime = centerTimeMs - newTimeRange / 2;
        long newEndTime = centerTimeMs + newTimeRange / 2;

        // Value zoom in transformed space for correct behavior with non-linear scales
        double transformedCenter = yAxisScale.transform(centerValue);
        double transformedMin = getTransformedMin();
        double transformedMax = getTransformedMax();
        double transformedRange = transformedMax - transformedMin;

        double newTransformedRange = transformedRange / factor;
        double newTransformedMin = transformedCenter - newTransformedRange / 2;
        double newTransformedMax = transformedCenter + newTransformedRange / 2;

        // Inverse transform back to data space
        double newMinValue = yAxisScale.inverseTransform(newTransformedMin);
        double newMaxValue = yAxisScale.inverseTransform(newTransformedMax);

        return new ViewPort(newStartTime, newEndTime, newMinValue, newMaxValue,
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
    }

    /**
     * Pans the viewport by the specified deltas in data space.
     * @deprecated Use panByPixels for correct behavior with non-linear scales
     */
    @Deprecated
    public ViewPort pan(long deltaTimeMs, double deltaValue) {
        return new ViewPort(startTimeMs + deltaTimeMs, endTimeMs + deltaTimeMs,
                          minValue + deltaValue, maxValue + deltaValue,
                          plotX, plotY, plotWidth, plotHeight, yAxisScale, xAxisType);
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

        double newTransformedMin = transformedMin + deltaTransformed;
        double newTransformedMax = transformedMax + deltaTransformed;

        // Inverse transform back to data space
        double newMinValue = yAxisScale.inverseTransform(newTransformedMin);
        double newMaxValue = yAxisScale.inverseTransform(newTransformedMax);

        return new ViewPort(newStartTime, newEndTime, newMinValue, newMaxValue,
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