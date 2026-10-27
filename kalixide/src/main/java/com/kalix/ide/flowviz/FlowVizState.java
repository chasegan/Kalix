package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.SourceRef;
import com.kalix.ide.flowviz.rendering.LineShape;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.stats.SeasonalMaskMode;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.YAxisScale;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of all user-facing plot state.
 * Used by {@link FlowVizStateHistory} for undo/redo navigation.
 *
 * <p>Identity in {@code visibleSeries} is carried as {@link SeriesRef}, not as a
 * rendered label. This means undo restores the same logical series even if the run
 * has since been renamed — the ref is stable across renames, so the snapshot remains
 * coherent and {@code F2} (undo-after-rename) cannot occur.</p>
 *
 * <p>{@code checkedSources} completes the view (a tab is "sources + series + plot
 * settings"), so undo restores the source context alongside the series. A snapshot may
 * outlive its sources — a run deleted or a dataset unloaded after the snapshot was
 * taken. Restoring such a snapshot fails silently by design: the tab record regains
 * the stale ref, projection skips paths it cannot find, and ids are never reused
 * (see {@link com.kalix.ide.flowviz.data.RunSource}), so nothing wrong can be resurrected under the same name.</p>
 */
public final class FlowVizState {

    private final List<SeriesRef> visibleSeries;
    private final Set<SourceRef> checkedSources;
    private final AggregationPeriod aggregationPeriod;
    private final AggregationMethod aggregationMethod;
    private final PlotType plotType;
    private final YAxisScale yAxisScale;
    private final LineShape lineShape;
    private final MaskMode maskMode;
    private final SeasonalMaskMode seasonalMaskMode;
    private final boolean autoYMode;
    // Visible series drawn against the right (y2) axis; a subset of visibleSeries
    private final Set<SeriesRef> rightAxisSeries;

    // Viewport zoom/pan state (excludes transient plotArea dimensions)
    private final long startTimeMs;
    private final long endTimeMs;
    private final double minValue;
    private final double maxValue;
    // Right-axis range; NaN when the view has no right axis
    private final double y2MinValue;
    private final double y2MaxValue;

    public FlowVizState(List<SeriesRef> visibleSeries,
                     Set<SourceRef> checkedSources,
                     AggregationPeriod aggregationPeriod,
                     AggregationMethod aggregationMethod,
                     PlotType plotType,
                     YAxisScale yAxisScale,
                     LineShape lineShape,
                     MaskMode maskMode,
                     SeasonalMaskMode seasonalMaskMode,
                     boolean autoYMode,
                     Set<SeriesRef> rightAxisSeries,
                     long startTimeMs,
                     long endTimeMs,
                     double minValue,
                     double maxValue,
                     double y2MinValue,
                     double y2MaxValue) {
        this.visibleSeries = List.copyOf(visibleSeries);
        // Order-preserving copy: source order is load-bearing (it drives the outputs
        // tree's section order), and Set.copyOf's iteration order is salt-randomised
        // per JVM run — an undo must not reorder a multi-source tab's sections.
        this.checkedSources = Collections.unmodifiableSet(new LinkedHashSet<>(checkedSources));
        this.aggregationPeriod = aggregationPeriod;
        this.aggregationMethod = aggregationMethod;
        this.plotType = plotType;
        this.yAxisScale = yAxisScale;
        this.lineShape = lineShape;
        this.maskMode = maskMode;
        this.seasonalMaskMode = seasonalMaskMode;
        this.autoYMode = autoYMode;
        this.rightAxisSeries = Collections.unmodifiableSet(new LinkedHashSet<>(rightAxisSeries));
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.y2MinValue = y2MinValue;
        this.y2MaxValue = y2MaxValue;
    }

    /**
     * Captures the current state from a FlowVizPanel's fields.
     */
    public static FlowVizState capture(List<SeriesRef> visibleSeries,
                                    Set<SourceRef> checkedSources,
                                    AggregationPeriod aggregationPeriod,
                                    AggregationMethod aggregationMethod,
                                    PlotType plotType,
                                    YAxisScale yAxisScale,
                                    LineShape lineShape,
                                    MaskMode maskMode,
                                    SeasonalMaskMode seasonalMaskMode,
                                    boolean autoYMode,
                                    Set<SeriesRef> rightAxisSeries,
                                    ViewPort viewport) {
        long startTime = viewport != null ? viewport.getStartTimeMs() : 0;
        long endTime = viewport != null ? viewport.getEndTimeMs() : 0;
        double min = viewport != null ? viewport.getMinValue() : 0;
        double max = viewport != null ? viewport.getMaxValue() : 0;
        double y2Min = viewport != null ? viewport.getY2MinValue() : Double.NaN;
        double y2Max = viewport != null ? viewport.getY2MaxValue() : Double.NaN;

        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod,
            aggregationMethod, plotType, yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode,
            rightAxisSeries, startTime, endTime, min, max, y2Min, y2Max);
    }

    public List<SeriesRef> getVisibleSeries() { return visibleSeries; }
    public Set<SourceRef> getCheckedSources() { return checkedSources; }
    public AggregationPeriod getAggregationPeriod() { return aggregationPeriod; }
    public AggregationMethod getAggregationMethod() { return aggregationMethod; }
    public PlotType getPlotType() { return plotType; }
    public YAxisScale getYAxisScale() { return yAxisScale; }
    public LineShape getLineShape() { return lineShape; }
    public MaskMode getMaskMode() { return maskMode; }
    public SeasonalMaskMode getSeasonalMaskMode() { return seasonalMaskMode; }
    public boolean isAutoYMode() { return autoYMode; }
    public Set<SeriesRef> getRightAxisSeries() { return rightAxisSeries; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public double getMinValue() { return minValue; }
    public double getMaxValue() { return maxValue; }
    public double getY2MinValue() { return y2MinValue; }
    public double getY2MaxValue() { return y2MaxValue; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FlowVizState s)) return false;
        return autoYMode == s.autoYMode
            && startTimeMs == s.startTimeMs
            && endTimeMs == s.endTimeMs
            && Double.compare(minValue, s.minValue) == 0
            && Double.compare(maxValue, s.maxValue) == 0
            && Double.compare(y2MinValue, s.y2MinValue) == 0
            && Double.compare(y2MaxValue, s.y2MaxValue) == 0
            && Objects.equals(rightAxisSeries, s.rightAxisSeries)
            && Objects.equals(visibleSeries, s.visibleSeries)
            && Objects.equals(checkedSources, s.checkedSources)
            && aggregationPeriod == s.aggregationPeriod
            && aggregationMethod == s.aggregationMethod
            && plotType == s.plotType
            && yAxisScale == s.yAxisScale
            && lineShape == s.lineShape
            && maskMode == s.maskMode
            && Objects.equals(seasonalMaskMode, s.seasonalMaskMode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod,
            plotType, yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode, rightAxisSeries,
            startTimeMs, endTimeMs, minValue, maxValue, y2MinValue, y2MaxValue);
    }
}
