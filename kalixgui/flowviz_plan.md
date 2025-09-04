# FlowViz Data Visualization Tool - Development Plan

## Requirements Summary
- **Multiple Windows**: Support multiple simultaneous FlowViz instances
- **Independent Data**: Maintain separate data sessions from main Kalix model
- **Large Datasets**: Handle 100,000+ data points with smooth rendering/panning
- **Missing Values**: Handle NaN, NA, blank spaces as missing data (don't render)
- **DateTime Support**: Parse both dates (YYYY-MM-DD) and optional time components
- **Regular Intervals**: Leverage assumption of regular time intervals for optimization
- **High Performance**: Focus on rendering strategy that maintains all data features

## High-Performance Rendering Strategy

### **Adaptive Level-of-Detail (LOD) Rendering**
- **Principle**: Never skip data points, but adaptively simplify visualization
- **Zoom Levels**:
  - **Full Resolution**: When <10,000 visible points, render every point
  - **Statistical Bands**: When >10,000 points, render min/max envelopes per pixel column
  - **Smart Sampling**: Use Douglas-Peucker algorithm to preserve visual features
- **Memory**: Pre-compute LOD levels during data loading
- **Threading**: Background thread for LOD computation, EDT for rendering only

### **Optimizations for Regular Intervals**
- **Binary Search**: Fast viewport clipping using regular time intervals
- **Pixel Mapping**: Direct time-to-pixel coordinate calculation
- **Dirty Regions**: Only re-render changed plot areas during pan/zoom

---

## Development Phases

### **Phase 1: Core Architecture & Window Setup**
**Goals**: Independent FlowViz window framework
- `FlowVizWindow` class extending JFrame (multiple instances supported)
- Menu bar: File (Open, Export, Close), View (Zoom, Pan, Reset), Tools, Help
- Layout: BorderLayout with collapsible left drawer (JSplitPane)
- Left drawer: Legend panel with toggle button
- Center: Custom `PlotPanel` extending JPanel
- Bottom: Status bar (data info, coordinates, zoom level)
- Window management: Track open FlowViz instances

### **Phase 2: High-Performance Data Model & CSV Loading**
**Goals**: Efficient data structures for 100k+ points
```java
class TimeSeriesData {
    private long[] timestamps;        // Unix timestamps for fast math
    private double[] values;          // Raw values (NaN for missing)
    private String name;
    private boolean[] validPoints;    // Quick missing data lookup
}

class DataSet {
    private List<TimeSeriesData> series;
    private long minTime, maxTime;
    private double globalMin, globalMax;
    private boolean hasRegularInterval;
    private long intervalMs;          // For optimization
}
```
- **CSV Parser**: 
  - Multi-threaded parsing with progress dialog
  - Robust date parsing (LocalDateTime -> Unix timestamp)
  - Missing value detection: "", "NaN", "NA", "null" (case-insensitive)
  - Automatic interval detection
- **Memory Management**: Use primitive arrays, not Collections for performance

### **Phase 3: Optimized Plotting Engine for 100k+ Points**
**Goals**: Smooth rendering regardless of data size
```java
class PlotPanel extends JPanel {
    private RenderingEngine engine;
    private ViewPort viewport;        // Current visible time/value range
    private BufferedImage plotBuffer; // Off-screen rendering buffer
}

class RenderingEngine {
    private LODManager lodManager;
    private CoordinateTransform transform;
}
```
- **Coordinate System**: 
  - Transform: (timestamp, value) ↔ (screen_x, screen_y)
  - Viewport: visible time range and value range
- **LOD Rendering**:
  - Algorithm: Pre-compute min/max bands for each pixel column at different zoom levels
  - Implementation: `double[][] minMaxBands = new double[pixelWidth][2]`
  - Feature Preservation: Always include local maxima/minima in bands
- **Double Buffering**: Off-screen rendering to eliminate flicker
- **Axis Rendering**: Smart tick calculation, date formatting

### **Phase 4: Multi-Series Support with Color Cycling**
**Goals**: Multiple timeseries with distinct visualization
- **Color Palette**: Predefined high-contrast colors
  ```java
  Color[] SERIES_COLORS = {
      new Color(31, 119, 180),   // Blue
      new Color(255, 127, 14),   // Orange  
      new Color(44, 160, 44),    // Green
      new Color(214, 39, 40),    // Red
      new Color(148, 103, 189),  // Purple
      new Color(140, 86, 75),    // Brown
      // ... cycle through 12 distinct colors
  };
  ```
- **Legend Panel**:
  - Series name + colored line sample
  - Show/hide checkboxes
  - Statistics (min, max, mean, count, missing)
- **Multi-Series Rendering**: Optimized to render all series in single pass

### **Phase 5: Mouse Interactions (Wheel Zoom, Pan)**
**Goals**: Fluid navigation of large datasets
- **Mouse Wheel Zoom**:
  - Zoom factor: 1.2x per wheel click
  - Zoom center: Mouse cursor position
  - Constraints: Prevent over-zoom (minimum 10 data points visible)
- **Pan Operations**:
  - Mouse drag: Translate viewport
  - Keyboard: Arrow keys for precise movement
  - Smooth pan: No data re-computation during drag
- **Zoom Extents**: Reset to show all data
- **Coordinate Display**: Show timestamp and value under mouse cursor

### **Phase 6: Advanced Features**
- **Export**: PNG, SVG export of current view
- **Statistics Panel**: Expandable panel showing series statistics
- **Data Validation**: Check for time ordering, duplicate timestamps
- **Error Handling**: Graceful handling of malformed CSV files

### **Phase 7: Performance Optimization & Polish**
- **Profiling**: Identify rendering bottlenecks
- **Memory**: Implement data streaming for extremely large files
- **Threading**: Background data processing
- **Anti-aliasing**: High-quality rendering options

---

## Technical Implementation Notes

### **Missing Data Handling**
```java
// During rendering, skip missing points by checking validPoints array
for (int i = startIdx; i < endIdx; i++) {
    if (!series.validPoints[i]) continue;  // Skip missing data
    // Render point...
}
```

### **Regular Interval Optimization**
```java
// Fast viewport calculation using regular intervals
long startTime = viewport.getStartTime();
long endTime = viewport.getEndTime();
int startIdx = (int)((startTime - series.getFirstTimestamp()) / intervalMs);
int endIdx = (int)((endTime - series.getFirstTimestamp()) / intervalMs);
```

### **LOD Algorithm Outline**
```java
// Pre-compute min/max bands for different zoom levels
private void computeLODBands(TimeSeriesData series, int pixelWidth) {
    double[][] bands = new double[pixelWidth][2]; // [min, max]
    int pointsPerPixel = series.size() / pixelWidth;
    
    for (int pixel = 0; pixel < pixelWidth; pixel++) {
        int startIdx = pixel * pointsPerPixel;
        int endIdx = Math.min(startIdx + pointsPerPixel, series.size());
        
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        
        for (int i = startIdx; i < endIdx; i++) {
            if (series.validPoints[i]) {
                min = Math.min(min, series.values[i]);
                max = Math.max(max, series.values[i]);
            }
        }
        
        bands[pixel][0] = min;
        bands[pixel][1] = max;
    }
}
```

This plan ensures **no data points are skipped** while maintaining smooth performance through intelligent rendering strategies. Would you like me to proceed with implementing Phase 1?