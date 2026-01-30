package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Level-of-Detail manager for efficient rendering of large time series.
 *
 * <h2>Purpose</h2>
 * When a time series has many more data points than pixels (e.g., 100k points in 1000px),
 * rendering every point is wasteful and can cause visual artifacts. LOD rendering computes
 * min/max bands per pixel column, allowing accurate representation with fewer draw calls.
 *
 * <h2>Caching</h2>
 * LOD data is cached with key: {@code "seriesName_startTime_endTime_width_height"}.
 * <p>
 * <b>IMPORTANT:</b> The cache key does NOT include the actual data values! If the underlying
 * data changes but the series name and viewport remain the same, the cache returns stale data.
 * <p>
 * When data changes, callers MUST call {@link #clearCache()} to invalidate. This is done by
 * {@link com.kalix.ide.flowviz.PlotPanel#refreshData} via {@link TimeSeriesRenderer#clearCache()}.
 *
 * <h2>Why Pan/Zoom "Fixes" Stale Data</h2>
 * Panning or zooming changes the viewport, which changes the cache key, causing a cache miss
 * and fresh LOD computation. This is why stale data may appear correct after pan/zoom even
 * without explicit cache invalidation - but relying on this is incorrect!
 *
 * @see TimeSeriesRenderer
 * @see com.kalix.ide.flowviz.PlotPanel#refreshData
 */
public class LODManager {

    // LOD threshold - switch to statistical bands when more than this many points per pixel
    private static final double POINTS_PER_PIXEL_THRESHOLD = 5.0;

    // Cache for pre-computed LOD data
    // Key: "seriesName_startTime_endTime_width_height" - NOTE: does NOT include data values!
    // Must call clearCache() when underlying data changes
    private final Map<String, LODData> lodCache = new ConcurrentHashMap<>();
    
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
    
    public RenderStrategy determineRenderStrategy(TimeSeriesData series, ViewPort viewport) {
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
        
        // Use LOD rendering - compute or retrieve cached LOD data
        String cacheKey = generateCacheKey(series, viewport);
        LODData lodData = lodCache.get(cacheKey);
        
        if (lodData == null) {
            lodData = computeLODData(series, viewport, indexRange);
            // Cache with size limit to prevent memory issues
            if (lodCache.size() < 100) {  // Limit cache size
                lodCache.put(cacheKey, lodData);
            }
        }
        
        return new RenderStrategy(false, lodData, indexRange);
    }
    
    private String generateCacheKey(TimeSeriesData series, ViewPort viewport) {
        return String.format("%s_%d_%d_%d_%d",
            series.getName(),
            viewport.getStartTimeMs(),
            viewport.getEndTimeMs(),
            viewport.getPlotWidth(),
            viewport.getPlotHeight()
        );
    }
    
    private LODData computeLODData(TimeSeriesData series, ViewPort viewport, 
                                  TimeSeriesData.IndexRange indexRange) {
        int plotWidth = viewport.getPlotWidth();
        
        double[][] minMaxBands = new double[plotWidth][2];
        boolean[] hasValidData = new boolean[plotWidth];
        
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();
        
        // Initialize bands
        for (int pixel = 0; pixel < plotWidth; pixel++) {
            minMaxBands[pixel][0] = Double.POSITIVE_INFINITY;  // min
            minMaxBands[pixel][1] = Double.NEGATIVE_INFINITY;  // max
            hasValidData[pixel] = false;
        }
        
        // Map each data point to its pixel column and track min/max
        for (int i = indexRange.startIndex; i < indexRange.endIndex; i++) {
            if (!validPoints[i]) continue;  // Skip missing values
            
            long timestamp = timestamps[i];
            double value = values[i];
            
            // Calculate which pixel column this point belongs to
            int pixelX = viewport.timeToScreenX(timestamp) - viewport.getPlotX();
            
            // Clamp to valid pixel range
            pixelX = Math.max(0, Math.min(pixelX, plotWidth - 1));
            
            // Update min/max for this pixel column
            minMaxBands[pixelX][0] = Math.min(minMaxBands[pixelX][0], value);  // min
            minMaxBands[pixelX][1] = Math.max(minMaxBands[pixelX][1], value);  // max
            hasValidData[pixelX] = true;
        }
        
        // Clean up pixels with no data
        for (int pixel = 0; pixel < plotWidth; pixel++) {
            if (!hasValidData[pixel]) {
                minMaxBands[pixel][0] = Double.NaN;
                minMaxBands[pixel][1] = Double.NaN;
            }
        }
        
        return new LODData(plotWidth, minMaxBands, hasValidData,
                          viewport.getStartTimeMs(), viewport.getEndTimeMs());
    }
    
    // Clear cache when data changes
    public void clearCache() {
        lodCache.clear();
    }
    
    public void clearCache(String seriesName) {
        lodCache.entrySet().removeIf(entry -> entry.getKey().startsWith(seriesName + "_"));
    }
    
    // Get cache statistics for debugging
    public int getCacheSize() {
        return lodCache.size();
    }
    
    public void printCacheStats() {
        System.out.println("LOD Cache size: " + lodCache.size());
        for (String key : lodCache.keySet()) {
            System.out.println("  " + key);
        }
    }
}