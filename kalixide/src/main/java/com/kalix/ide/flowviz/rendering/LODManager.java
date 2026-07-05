package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Level-of-Detail manager for efficient rendering of large time series.
 *
 * <h2>Purpose</h2>
 * When a time series has many more data points than pixels (e.g., 100k points in 1000px),
 * rendering every point is wasteful and can cause visual artifacts. LOD rendering computes
 * min/max bands per pixel column, allowing accurate representation with fewer draw calls.
 *
 * <h2>How a frame is costed</h2>
 * Bands are computed per frame from a per-series {@link MinMaxPyramid} (block min/max,
 * built once per series in O(n)), so a pan/zoom frame costs roughly O(plot width) - not
 * O(visible points). This replaced a viewport-keyed result cache that missed on every
 * pan frame (the key contained the exact start/end times) and silently stopped caching
 * for the session once it held 100 entries.
 *
 * <h2>Staleness is impossible by construction</h2>
 * Pyramids are cached weakly, keyed by {@link TimeSeriesData} identity. Series data is
 * immutable, so a cached pyramid can never disagree with its series; replacing the
 * display data creates new TimeSeriesData instances, which simply key new pyramids
 * (old ones fall out with the garbage collector). {@link #clearCache()} remains for
 * callers that want to drop the memory eagerly.
 *
 * @see TimeSeriesRenderer
 * @see com.kalix.ide.flowviz.PlotPanel#refreshData
 */
public class LODManager {

    // LOD threshold - switch to statistical bands when more than this many points per pixel
    private static final double POINTS_PER_PIXEL_THRESHOLD = 5.0;

    // Per-series acceleration structures. EDT-confined (painting), so a plain
    // WeakHashMap is safe; weak keys tie each pyramid's lifetime to its series.
    private final Map<TimeSeriesData, MinMaxPyramid> pyramids = new WeakHashMap<>();
    
    public static class LODData {
        public final int pixelWidth;
        public final double[][] minMaxBands;  // [pixelIndex][0=min, 1=max]
        public final boolean[] hasValidData;  // Whether this pixel column has any valid data
        public final long startTime;
        public final long endTime;
        
        public LODData(int pixelWidth, double[][] minMaxBands, boolean[] hasValidData, 
                      long startTime, long endTime) {
            this.pixelWidth = pixelWidth;
            this.minMaxBands = minMaxBands;
            this.hasValidData = hasValidData;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }
    
    public static class RenderStrategy {
        public final boolean useFullResolution;
        public final LODData lodData;
        public final TimeSeriesData.IndexRange indexRange;
        
        public RenderStrategy(boolean useFullResolution, LODData lodData, TimeSeriesData.IndexRange indexRange) {
            this.useFullResolution = useFullResolution;
            this.lodData = lodData;
            this.indexRange = indexRange;
        }
    }
    
    public RenderStrategy determineRenderStrategy(SeriesRef ref, TimeSeriesData series, ViewPort viewport) {
        // Get the visible data range
        TimeSeriesData.IndexRange indexRange = series.getIndexRange(
            viewport.getStartTimeMs(), viewport.getEndTimeMs());

        if (indexRange.isEmpty()) {
            return new RenderStrategy(true, null, indexRange);
        }

        // Calculate points per pixel
        int visiblePoints = indexRange.size();
        int plotWidth = viewport.getPlotWidth();

        if (plotWidth <= 0) {
            return new RenderStrategy(true, null, indexRange);
        }

        double pointsPerPixel = (double) visiblePoints / plotWidth;

        // Use full resolution if density is low enough
        if (pointsPerPixel <= POINTS_PER_PIXEL_THRESHOLD) {
            return new RenderStrategy(true, null, indexRange);
        }

        // LOD rendering: bands computed per frame via the series' pyramid.
        LODData lodData = computeLODData(series, viewport, indexRange);
        return new RenderStrategy(false, lodData, indexRange);
    }

    private LODData computeLODData(TimeSeriesData series, ViewPort viewport,
                                  TimeSeriesData.IndexRange indexRange) {
        int plotWidth = viewport.getPlotWidth();

        double[][] minMaxBands = new double[plotWidth][2];
        boolean[] hasValidData = new boolean[plotWidth];

        MinMaxPyramid pyramid = pyramids.computeIfAbsent(series, MinMaxPyramid::new);

        // Partition the visible index range into per-column sub-ranges by asking the
        // series for the first index at each column's start time (O(1) on regular
        // grids, O(log n) otherwise), then fold each sub-range through the pyramid.
        // No per-point work happens at frame time.
        int plotX = viewport.getPlotX();
        long viewEnd = viewport.getEndTimeMs();
        double[] acc = new double[2];

        int columnStartIdx = indexRange.startIndex;
        for (int pixel = 0; pixel < plotWidth; pixel++) {
            int columnEndIdx;
            if (pixel == plotWidth - 1) {
                columnEndIdx = indexRange.endIndex;
            } else {
                long nextColumnTime = viewport.screenXToTime(plotX + pixel + 1);
                columnEndIdx = series.getIndexRange(nextColumnTime, viewEnd).startIndex;
                if (columnEndIdx < columnStartIdx) {
                    columnEndIdx = columnStartIdx;
                }
                if (columnEndIdx > indexRange.endIndex) {
                    columnEndIdx = indexRange.endIndex;
                }
            }

            acc[0] = Double.POSITIVE_INFINITY;
            acc[1] = Double.NEGATIVE_INFINITY;
            pyramid.accumulate(columnStartIdx, columnEndIdx, acc);

            if (acc[0] <= acc[1]) {
                minMaxBands[pixel][0] = acc[0];
                minMaxBands[pixel][1] = acc[1];
                hasValidData[pixel] = true;
            } else {
                minMaxBands[pixel][0] = Double.NaN;
                minMaxBands[pixel][1] = Double.NaN;
                hasValidData[pixel] = false;
            }

            columnStartIdx = columnEndIdx;
        }

        return new LODData(plotWidth, minMaxBands, hasValidData,
                          viewport.getStartTimeMs(), viewport.getEndTimeMs());
    }

    /** Drops the per-series pyramids eagerly (they also fall away with their series). */
    public void clearCache() {
        pyramids.clear();
    }
}