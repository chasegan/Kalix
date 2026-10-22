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
 * Used by {@link FlowVizModel} for undo/redo navigation.
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

    /** A fresh tab before anything is chosen: no series, native resolution, every option off. */
    public static final FlowVizState INITIAL = new FlowVizState(List.of(), Set.of(),
        AggregationPeriod.ORIGINAL, AggregationMethod.SUM, PlotType.VALUES, YAxisScale.LINEAR,
        LineShape.STRAIGHT, MaskMode.NONE, SeasonalMaskMode.DISABLED, false, 0, 0, 0, 0);

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

    // Viewport zoom/pan state (excludes transient plotArea dimensions)
    private final long startTimeMs;
    private final long endTimeMs;
    private final double minValue;
    private final double maxValue;

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
                     long startTimeMs,
                     long endTimeMs,
                     double minValue,
                     double maxValue) {
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
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public FlowVizState withVisibleSeries(List<SeriesRef> series) {
        return new FlowVizState(series, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withCheckedSources(Set<SourceRef> sources) {
        return new FlowVizState(visibleSeries, sources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withAggregation(AggregationPeriod period, AggregationMethod method) {
        return new FlowVizState(visibleSeries, checkedSources, period, method, plotType,
            yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withPlotType(PlotType type) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, type,
            yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withYAxisScale(YAxisScale scale) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            scale, lineShape, maskMode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withLineShape(LineShape shape) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, shape, maskMode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withMaskMode(MaskMode mode) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, lineShape, mode, seasonalMaskMode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withSeasonalMaskMode(SeasonalMaskMode mode) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, lineShape, maskMode, mode, autoYMode, startTimeMs, endTimeMs, minValue, maxValue);
    }

    public FlowVizState withAutoYMode(boolean autoY) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, lineShape, maskMode, seasonalMaskMode, autoY, startTimeMs, endTimeMs, minValue, maxValue);
    }

    /** The viewport's bounds; a null viewport records zeros. */
    public FlowVizState withViewport(ViewPort viewport) {
        return new FlowVizState(visibleSeries, checkedSources, aggregationPeriod, aggregationMethod, plotType,
            yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode,
            viewport != null ? viewport.getStartTimeMs() : 0, viewport != null ? viewport.getEndTimeMs() : 0,
            viewport != null ? viewport.getMinValue() : 0, viewport != null ? viewport.getMaxValue() : 0);
    }

    /** True when the two differ in anything but the viewport bounds and checked sources: what the plot draws. */
    public boolean differsInPlotFrom(FlowVizState other) {
        return autoYMode != other.autoYMode
            || !Objects.equals(visibleSeries, other.visibleSeries)
            || aggregationPeriod != other.aggregationPeriod
            || aggregationMethod != other.aggregationMethod
            || plotType != other.plotType
            || yAxisScale != other.yAxisScale
            || lineShape != other.lineShape
            || maskMode != other.maskMode
            || !Objects.equals(seasonalMaskMode, other.seasonalMaskMode);
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
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public double getMinValue() { return minValue; }
    public double getMaxValue() { return maxValue; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FlowVizState s)) return false;
        return autoYMode == s.autoYMode
            && startTimeMs == s.startTimeMs
            && endTimeMs == s.endTimeMs
            && Double.compare(minValue, s.minValue) == 0
            && Double.compare(maxValue, s.maxValue) == 0
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
            plotType, yAxisScale, lineShape, maskMode, seasonalMaskMode, autoYMode,
            startTimeMs, endTimeMs, minValue, maxValue);
    }
}
