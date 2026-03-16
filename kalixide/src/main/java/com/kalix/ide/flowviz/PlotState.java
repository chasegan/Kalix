package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of all user-facing plot state.
 * Used by {@link PlotStateHistory} for undo/redo navigation.
 */
public final class PlotState {

    private final List<String> visibleSeries;
    private final AggregationPeriod aggregationPeriod;
    private final AggregationMethod aggregationMethod;
    private final PlotType plotType;
    private final YAxisScale yAxisScale;
    private final MaskMode maskMode;
    private final boolean autoYMode;

    // Viewport zoom/pan state (excludes transient plotArea dimensions)
    private final long startTimeMs;
    private final long endTimeMs;
    private final double minValue;
    private final double maxValue;

    public PlotState(List<String> visibleSeries,
                     AggregationPeriod aggregationPeriod,
                     AggregationMethod aggregationMethod,
                     PlotType plotType,
                     YAxisScale yAxisScale,
                     MaskMode maskMode,
                     boolean autoYMode,
                     long startTimeMs,
                     long endTimeMs,
                     double minValue,
                     double maxValue) {
        this.visibleSeries = List.copyOf(visibleSeries);
        this.aggregationPeriod = aggregationPeriod;
        this.aggregationMethod = aggregationMethod;
        this.plotType = plotType;
        this.yAxisScale = yAxisScale;
        this.maskMode = maskMode;
        this.autoYMode = autoYMode;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /**
     * Captures the current state from a PlotPanel's fields.
     */
    public static PlotState capture(List<String> visibleSeries,
                                    AggregationPeriod aggregationPeriod,
                                    AggregationMethod aggregationMethod,
                                    PlotType plotType,
                                    YAxisScale yAxisScale,
                                    MaskMode maskMode,
                                    boolean autoYMode,
                                    ViewPort viewport) {
        long startTime = viewport != null ? viewport.getStartTimeMs() : 0;
        long endTime = viewport != null ? viewport.getEndTimeMs() : 0;
        double min = viewport != null ? viewport.getMinValue() : 0;
        double max = viewport != null ? viewport.getMaxValue() : 0;

        return new PlotState(visibleSeries, aggregationPeriod, aggregationMethod,
            plotType, yAxisScale, maskMode, autoYMode, startTime, endTime, min, max);
    }

    public List<String> getVisibleSeries() { return visibleSeries; }
    public AggregationPeriod getAggregationPeriod() { return aggregationPeriod; }
    public AggregationMethod getAggregationMethod() { return aggregationMethod; }
    public PlotType getPlotType() { return plotType; }
    public YAxisScale getYAxisScale() { return yAxisScale; }
    public MaskMode getMaskMode() { return maskMode; }
    public boolean isAutoYMode() { return autoYMode; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public double getMinValue() { return minValue; }
    public double getMaxValue() { return maxValue; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlotState s)) return false;
        return autoYMode == s.autoYMode
            && startTimeMs == s.startTimeMs
            && endTimeMs == s.endTimeMs
            && Double.compare(minValue, s.minValue) == 0
            && Double.compare(maxValue, s.maxValue) == 0
            && Objects.equals(visibleSeries, s.visibleSeries)
            && aggregationPeriod == s.aggregationPeriod
            && aggregationMethod == s.aggregationMethod
            && plotType == s.plotType
            && yAxisScale == s.yAxisScale
            && maskMode == s.maskMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(visibleSeries, aggregationPeriod, aggregationMethod,
            plotType, yAxisScale, maskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }
}
