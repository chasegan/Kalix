package com.kalix.gui.flowviz.data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

public class TimeSeriesData {
    private final String name;
    private final long[] timestamps;      // Unix timestamps in milliseconds for fast math
    private final double[] values;        // Raw values (NaN for missing data)
    private final boolean[] validPoints;  // Quick missing data lookup
    private final int pointCount;
    
    // Cached statistics
    private Double minValue;
    private Double maxValue;
    private Double meanValue;
    private Integer validPointCount;
    
    // Regular interval optimization
    private final boolean hasRegularInterval;
    private final long intervalMillis;
    private final long firstTimestamp;
    
    public TimeSeriesData(String name, LocalDateTime[] dateTimes, double[] values) {
        this.name = name;
        this.pointCount = dateTimes.length;
        
        if (dateTimes.length != values.length) {
            throw new IllegalArgumentException("DateTime and value arrays must have same length");
        }
        
        this.timestamps = new long[pointCount];
        this.values = new double[pointCount];
        this.validPoints = new boolean[pointCount];
        
        // Convert LocalDateTime to Unix timestamps in milliseconds and process values
        for (int i = 0; i < pointCount; i++) {
            this.timestamps[i] = dateTimes[i].toInstant(ZoneOffset.UTC).toEpochMilli();
            this.values[i] = values[i];
            this.validPoints[i] = !Double.isNaN(values[i]) && Double.isFinite(values[i]);
        }
        
        // Sort by timestamp if needed
        sortIfNeeded();
        
        // Detect regular intervals
        RegularIntervalInfo intervalInfo = detectRegularInterval();
        this.hasRegularInterval = intervalInfo.isRegular;
        this.intervalMillis = intervalInfo.intervalMillis;
        this.firstTimestamp = timestamps.length > 0 ? timestamps[0] : 0;
        
        // Pre-compute statistics
        computeStatistics();
    }
    
    private void sortIfNeeded() {
        boolean needsSort = false;
        for (int i = 1; i < pointCount; i++) {
            if (timestamps[i] < timestamps[i-1]) {
                needsSort = true;
                break;
            }
        }
        
        if (needsSort) {
            // Create indices array for sorting
            Integer[] indices = new Integer[pointCount];
            for (int i = 0; i < pointCount; i++) {
                indices[i] = i;
            }
            
            // Sort indices by timestamp
            Arrays.sort(indices, (a, b) -> Long.compare(timestamps[a], timestamps[b]));
            
            // Reorder all arrays
            long[] newTimestamps = new long[pointCount];
            double[] newValues = new double[pointCount];
            boolean[] newValidPoints = new boolean[pointCount];
            
            for (int i = 0; i < pointCount; i++) {
                int originalIndex = indices[i];
                newTimestamps[i] = timestamps[originalIndex];
                newValues[i] = values[originalIndex];
                newValidPoints[i] = validPoints[originalIndex];
            }
            
            System.arraycopy(newTimestamps, 0, timestamps, 0, pointCount);
            System.arraycopy(newValues, 0, values, 0, pointCount);
            System.arraycopy(newValidPoints, 0, validPoints, 0, pointCount);
        }
    }
    
    private static class RegularIntervalInfo {
        final boolean isRegular;
        final long intervalMillis;

        RegularIntervalInfo(boolean isRegular, long intervalMillis) {
            this.isRegular = isRegular;
            this.intervalMillis = intervalMillis;
        }
    }
    
    private RegularIntervalInfo detectRegularInterval() {
        if (pointCount < 3) {
            return new RegularIntervalInfo(false, 0);
        }
        
        long firstInterval = timestamps[1] - timestamps[0];
        if (firstInterval <= 0) {
            return new RegularIntervalInfo(false, 0);
        }
        
        // Check if all intervals match (within 1% tolerance)
        double tolerance = firstInterval * 0.01;
        
        for (int i = 2; i < Math.min(pointCount, 100); i++) { // Check first 100 points for efficiency
            long interval = timestamps[i] - timestamps[i-1];
            if (Math.abs(interval - firstInterval) > tolerance) {
                return new RegularIntervalInfo(false, 0);
            }
        }
        
        return new RegularIntervalInfo(true, firstInterval);
    }
    
    private void computeStatistics() {
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int validCount = 0;
        
        for (int i = 0; i < pointCount; i++) {
            if (validPoints[i]) {
                double value = values[i];
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                validCount++;
            }
        }
        
        this.validPointCount = validCount;
        if (validCount > 0) {
            this.minValue = min;
            this.maxValue = max;
            this.meanValue = sum / validCount;
        } else {
            this.minValue = null;
            this.maxValue = null;
            this.meanValue = null;
        }
    }
    
    // Optimized range queries using regular intervals
    public IndexRange getIndexRange(long startTimeMs, long endTimeMs) {
        if (pointCount == 0) {
            return new IndexRange(0, 0);
        }
        
        int startIndex, endIndex;
        
        if (hasRegularInterval) {
            // Fast calculation using regular intervals
            startIndex = (int) Math.max(0, (startTimeMs - firstTimestamp) / intervalMillis);
            endIndex = (int) Math.min(pointCount, (endTimeMs - firstTimestamp) / intervalMillis + 1);
            
            // Clamp to actual bounds
            startIndex = Math.max(0, Math.min(startIndex, pointCount - 1));
            endIndex = Math.max(startIndex, Math.min(endIndex, pointCount));
        } else {
            // Binary search for irregular intervals
            startIndex = binarySearchTimestamp(startTimeMs, true);
            endIndex = binarySearchTimestamp(endTimeMs, false);
        }
        
        return new IndexRange(startIndex, endIndex);
    }
    
    private int binarySearchTimestamp(long targetTime, boolean findFirst) {
        int left = 0;
        int right = pointCount - 1;
        int result = findFirst ? pointCount : 0;
        
        while (left <= right) {
            int mid = left + (right - left) / 2;
            long midTime = timestamps[mid];
            
            if (midTime == targetTime) {
                result = mid;
                if (findFirst) {
                    right = mid - 1; // Continue searching left for first occurrence
                } else {
                    left = mid + 1;  // Continue searching right for last occurrence
                }
            } else if (midTime < targetTime) {
                if (!findFirst) result = mid + 1;
                left = mid + 1;
            } else {
                if (findFirst) result = mid;
                right = mid - 1;
            }
        }
        
        return Math.max(0, Math.min(result, pointCount));
    }
    
    // Getters
    public String getName() { return name; }
    public int getPointCount() { return pointCount; }
    public long[] getTimestamps() { return timestamps; }
    public double[] getValues() { return values; }
    public boolean[] getValidPoints() { return validPoints; }
    
    public long getFirstTimestamp() { return firstTimestamp; }
    public long getLastTimestamp() { return pointCount > 0 ? timestamps[pointCount - 1] : 0; }
    
    public boolean hasRegularInterval() { return hasRegularInterval; }
    public long getIntervalMillis() { return intervalMillis; }
    
    // Statistics getters
    public Double getMinValue() { return minValue; }
    public Double getMaxValue() { return maxValue; }
    public Double getMeanValue() { return meanValue; }
    public Integer getValidPointCount() { return validPointCount; }
    public int getMissingPointCount() { return pointCount - (validPointCount != null ? validPointCount : 0); }
    
    public static class IndexRange {
        public final int startIndex;
        public final int endIndex;
        
        public IndexRange(int startIndex, int endIndex) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
        
        public int size() {
            return Math.max(0, endIndex - startIndex);
        }
        
        public boolean isEmpty() {
            return endIndex <= startIndex;
        }
    }
}