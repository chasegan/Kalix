package com.kalix.ide.flowviz.rendering;

public class ViewPort {
    private long startTimeMs;
    private long endTimeMs;
    private double minValue;
    private double maxValue;
    
    // Plot area dimensions
    private int plotX;
    private int plotY;
    private int plotWidth;
    private int plotHeight;
    
    public ViewPort(long startTimeMs, long endTimeMs, double minValue, double maxValue) {
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }
    
    public ViewPort(long startTimeMs, long endTimeMs, double minValue, double maxValue,
                   int plotX, int plotY, int plotWidth, int plotHeight) {
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.plotX = plotX;
        this.plotY = plotY;
        this.plotWidth = plotWidth;
        this.plotHeight = plotHeight;
    }
    
    // Transform coordinates between data space and screen space
    public int timeToScreenX(long timeMs) {
        if (endTimeMs == startTimeMs) return plotX;
        return (int) (plotX + ((double)(timeMs - startTimeMs) / (endTimeMs - startTimeMs)) * plotWidth);
    }
    
    public int valueToScreenY(double value) {
        if (maxValue == minValue) return plotY + plotHeight / 2;
        return (int) (plotY + plotHeight - ((value - minValue) / (maxValue - minValue)) * plotHeight);
    }
    
    public long screenXToTime(int screenX) {
        if (plotWidth == 0) return startTimeMs;
        double ratio = (double)(screenX - plotX) / plotWidth;
        return (long) (startTimeMs + ratio * (endTimeMs - startTimeMs));
    }
    
    public double screenYToValue(int screenY) {
        if (plotHeight == 0) return minValue;
        double ratio = (double)(plotY + plotHeight - screenY) / plotHeight;
        return minValue + ratio * (maxValue - minValue);
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
        long timeRange = endTimeMs - startTimeMs;
        double valueRange = maxValue - minValue;
        
        long newTimeRange = (long) (timeRange / factor);
        double newValueRange = valueRange / factor;
        
        // Center zoom on specified point
        long newStartTime = centerTimeMs - newTimeRange / 2;
        long newEndTime = centerTimeMs + newTimeRange / 2;
        
        double newMinValue = centerValue - newValueRange / 2;
        double newMaxValue = centerValue + newValueRange / 2;
        
        return new ViewPort(newStartTime, newEndTime, newMinValue, newMaxValue, 
                          plotX, plotY, plotWidth, plotHeight);
    }
    
    public ViewPort pan(long deltaTimeMs, double deltaValue) {
        return new ViewPort(startTimeMs + deltaTimeMs, endTimeMs + deltaTimeMs,
                          minValue + deltaValue, maxValue + deltaValue,
                          plotX, plotY, plotWidth, plotHeight);
    }
    
    public ViewPort withPlotArea(int plotX, int plotY, int plotWidth, int plotHeight) {
        return new ViewPort(startTimeMs, endTimeMs, minValue, maxValue,
                          plotX, plotY, plotWidth, plotHeight);
    }
    
    // Calculate how many pixels per data point
    public double getPixelsPerMillisecond() {
        if (endTimeMs == startTimeMs) return 0;
        return (double) plotWidth / (endTimeMs - startTimeMs);
    }
    
    public double getPixelsPerValue() {
        if (maxValue == minValue) return 0;
        return plotHeight / (maxValue - minValue);
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
    
    @Override
    public String toString() {
        return String.format("ViewPort[time: %d-%d, value: %.3f-%.3f, plot: %d,%d %dx%d]",
            startTimeMs, endTimeMs, minValue, maxValue, plotX, plotY, plotWidth, plotHeight);
    }
}