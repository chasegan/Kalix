package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.rendering.XAxisType;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.flowviz.transform.PlotTypeTransformer;
import com.kalix.ide.flowviz.transform.TimeSeriesAggregator;
import com.kalix.ide.flowviz.transform.YAxisScale;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for rendering time series plots with support for aggregation, transforms, and LOD rendering.
 *
 * <h2>Data Flow and Caching</h2>
 * There are TWO caching layers that must be invalidated when data changes:
 * <ol>
 *   <li><b>Transform cache</b> ({@code displayDataSet}, keyed by {@code lastTransformKey}):
 *       Caches aggregated/transformed data. Invalidated by setting {@code lastTransformKey = null}.</li>
 *   <li><b>LOD rendering cache</b> (in {@link TimeSeriesRenderer} → {@link com.kalix.ide.flowviz.rendering.LODManager}):
 *       Caches pixel-level min/max bands for efficient rendering. Invalidated by {@code renderer.clearCache()}.</li>
 * </ol>
 *
 * <h2>IMPORTANT: Refreshing Data</h2>
 * When underlying data changes, call {@link #refreshData(boolean)} which:
 * <ul>
 *   <li>Invalidates both caches</li>
 *   <li>Rebuilds {@code displayDataSet} from {@code originalDataSet}</li>
 *   <li>Triggers repaint</li>
 * </ul>
 * Simply calling {@code repaint()} is NOT sufficient - it will render stale cached data!
 *
 * <h2>Rendering Pipeline</h2>
 * <pre>
 * originalDataSet (shared)
 *   → TimeSeriesAggregator.aggregate()   [aggregation: daily, monthly, etc.]
 *   → PlotTypeTransformer.transform()    [plot type: values, cumulative, difference]
 *   → displayDataSet (cached per-panel)
 *   → TimeSeriesRenderer.render()        [with LOD optimization for large datasets]
 * </pre>
 *
 * @see com.kalix.ide.windows.VisualizationTabManager#updateAllTabs
 * @see com.kalix.ide.flowviz.rendering.LODManager
 */
public class PlotPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(PlotPanel.class);

    // Plot margins
    private static final int MARGIN_LEFT = 80;
    private static final int MARGIN_RIGHT = 20;
    private static final int MARGIN_TOP = 20;
    private static final int MARGIN_BOTTOM = 60;


    // === DATA FLOW ===
    // originalDataSet → [transforms] → displayDataSet → [LOD] → screen
    // Both displayDataSet and LOD have caches that must be invalidated via refreshData()

    private DataSet originalDataSet;  // Reference to shared dataset (same for all plot tabs)
    private DataSet displayDataSet;   // Transformed data for display (CACHED - see lastTransformKey)
    private final TimeSeriesRenderer renderer;  // Contains LOD cache - clear via renderer.clearCache()
    private ViewPort currentViewport;
    private final Map<String, Color> seriesColors;
    private final List<String> visibleSeries;
    private boolean autoYMode = false;

    // Transform settings (per-tab) - changes trigger displayDataSet rebuild
    private AggregationPeriod aggregationPeriod = AggregationPeriod.ORIGINAL;
    private AggregationMethod aggregationMethod = AggregationMethod.SUM;
    private PlotType plotType = PlotType.VALUES;
    private YAxisScale yAxisScale = YAxisScale.LINEAR;
    private XAxisType xAxisTypeOverride = null;  // If set, overrides automatic axis type selection

    // Reference series tracking for DIFFERENCE plot types
    private String lastReferenceSeries = null;

    // === TRANSFORM CACHE ===
    // displayDataSet is cached and only rebuilt when transform settings change.
    // Key format: "aggregationPeriod_aggregationMethod_plotType_referenceSeries"
    // Set to null to force rebuild (done by refreshData())
    private String lastTransformKey = null;

    // Managers
    private final CoordinateDisplayManager coordinateDisplayManager;
    private final PlotInteractionManager plotInteractionManager;
    private final PlotLegendManager legendManager;

    public PlotPanel() {
        setBackground(Color.WHITE);
        
        // Initialize data structures
        seriesColors = new HashMap<>();
        visibleSeries = new java.util.ArrayList<>();
        renderer = new TimeSeriesRenderer(seriesColors, visibleSeries);

        // Initialize managers
        coordinateDisplayManager = new CoordinateDisplayManager(this, seriesColors, visibleSeries);
        plotInteractionManager = new PlotInteractionManager(this, coordinateDisplayManager);
        legendManager = new PlotLegendManager();

        // Setup manager data access
        plotInteractionManager.setupDataAccess(
            () -> displayDataSet,
            () -> currentViewport,
            viewport -> currentViewport = viewport,
            () -> visibleSeries,
            this::getPlotArea
        );

        // Setup plot type supplier for format-aware export
        plotInteractionManager.setPlotTypeSupplier(() -> plotType);

        setupMouseListeners();
    }
    
    public void setDataSet(DataSet dataSet) {
        this.originalDataSet = dataSet;

        // Rebuild display dataset with current transforms
        rebuildDisplayDataSet();

        // Create initial viewport to show all data
        if (displayDataSet != null && !displayDataSet.isEmpty()) {
            zoomToFitData();
        } else {
            createDefaultViewport();
        }

        repaint();
    }
    
    public void setSeriesColors(Map<String, Color> colors) {
        this.seriesColors.clear();
        this.seriesColors.putAll(colors);
        repaint();
    }
    
    public void setVisibleSeries(List<String> visibleSeries) {
        this.visibleSeries.clear();
        this.visibleSeries.addAll(visibleSeries);

        // Check if reference series changed for DIFFERENCE plot types
        checkReferenceSeriesChange();

        repaint();
    }
    
    private void zoomToFitData() {
        if (displayDataSet == null || displayDataSet.isEmpty()) {
            createDefaultViewport();
            return;
        }

        long startTime = displayDataSet.getGlobalMinTime();
        long endTime = displayDataSet.getGlobalMaxTime();
        Double minValue = displayDataSet.getGlobalMinValue();
        Double maxValue = displayDataSet.getGlobalMaxValue();
        
        if (minValue == null || maxValue == null) {
            createDefaultViewport();
            return;
        }

        // Clamp minimum value for log scale to prevent zooming too far out
        // Hydrological models often produce tiny values (e.g., 1e-12) that are meaningless
        // This only affects auto-zoom; manual zoom/pan can still access the full range
        double logScaleMin = PreferenceManager.getFileDouble(PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD, 1.0);
        if (yAxisScale == YAxisScale.LOG && minValue < logScaleMin && logScaleMin < maxValue) {
            minValue = logScaleMin;
        }

        // Add 5% padding
        long timePadding = (long) ((endTime - startTime) * 0.05);

        startTime -= timePadding;
        endTime += timePadding;

        // Ensure minimum time range
        if (endTime - startTime < 1000) { // Less than 1 second
            long center = (startTime + endTime) / 2;
            startTime = center - 500;
            endTime = center + 500;
        }

        // Apply padding to Y values in appropriate space (linear or log)
        double[] paddedYRange = applyYAxisPadding(minValue, maxValue, yAxisScale, 0.05);
        minValue = paddedYRange[0];
        maxValue = paddedYRange[1];

        Rectangle plotArea = getPlotArea();
        XAxisType xAxisType = determineXAxisType();
        currentViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height, yAxisScale, xAxisType);
    }

    private void createDefaultViewport() {
        // Default viewport showing current time ± 1 hour
        long now = System.currentTimeMillis();
        long startTime = now - 3600000; // 1 hour ago
        long endTime = now + 3600000;   // 1 hour from now

        Rectangle plotArea = getPlotArea();
        XAxisType xAxisType = determineXAxisType();
        currentViewport = new ViewPort(startTime, endTime, -10.0, 10.0,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height, yAxisScale, xAxisType);
    }

    /**
     * Applies padding to Y-axis range in the appropriate transformed space.
     * Works for all scale types (LINEAR, LOG, SQRT) by applying padding in transformed
     * space and then inverse transforming back to data space. This ensures consistent
     * visual spacing regardless of scale type.
     *
     * @param minValue The minimum Y value before padding
     * @param maxValue The maximum Y value before padding
     * @param yAxisScale The Y-axis scale type (LINEAR, LOG, or SQRT)
     * @param paddingFraction The fraction of range to use as padding (e.g., 0.05 for 5%)
     * @return Array of [paddedMin, paddedMax]
     */
    private static double[] applyYAxisPadding(double minValue, double maxValue, YAxisScale yAxisScale, double paddingFraction) {
        double valueRange = maxValue - minValue;

        // Handle constant data (all values identical) - use relative padding based on magnitude
        if (valueRange < 1e-15) {
            double center = (minValue + maxValue) / 2;
            double halfRange = Math.max(Math.abs(center) * 0.1, 1e-6);
            return new double[]{center - halfRange, center + halfRange};
        }

        // Apply padding in transformed space for correct visual spacing
        double transformedMin = yAxisScale.transform(minValue);
        double transformedMax = yAxisScale.transform(maxValue);
        double transformedRange = transformedMax - transformedMin;

        double padding = transformedRange * paddingFraction;
        transformedMin -= padding;
        transformedMax += padding;

        // Inverse transform back to data space
        double paddedMin = yAxisScale.inverseTransform(transformedMin);
        double paddedMax = yAxisScale.inverseTransform(transformedMax);

        return new double[]{paddedMin, paddedMax};
    }

    /**
     * Determines the X-axis type based on override or plot type.
     */
    private XAxisType determineXAxisType() {
        if (xAxisTypeOverride != null) {
            return xAxisTypeOverride;
        }
        return (plotType == PlotType.EXCEEDANCE) ? XAxisType.PERCENTILE : XAxisType.TIME;
    }

    private void setupMouseListeners() {
        MouseAdapter plotMouseHandler = plotInteractionManager.createMouseHandler();

        // Create composite mouse handler that routes events to legend first, then to plot
        MouseAdapter compositeHandler = new MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (legendManager != null && legendManager.handleMousePress(e.getX(), e.getY())) {
                    repaint();
                    return; // Event consumed by legend
                }
                plotMouseHandler.mousePressed(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (legendManager != null) {
                    legendManager.handleMouseRelease();
                    repaint();
                }
                plotMouseHandler.mouseReleased(e);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (legendManager != null && legendManager.handleMouseClick(e.getX(), e.getY())) {
                    repaint();
                    return; // Event consumed by legend
                }
                plotMouseHandler.mouseClicked(e);
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (legendManager != null && legendManager.handleMouseDrag(e.getX(), e.getY())) {
                    repaint();
                    return; // Event consumed by legend
                }
                plotMouseHandler.mouseDragged(e);
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                // Update legend hover state and cursor
                if (legendManager != null) {
                    if (legendManager.handleMouseMove(e.getX(), e.getY())) {
                        repaint();
                    }
                    Cursor legendCursor = legendManager.getCursor(e.getX(), e.getY());
                    if (legendCursor != null) {
                        setCursor(legendCursor);
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
                plotMouseHandler.mouseMoved(e);
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                // Don't zoom if mouse is over legend
                if (legendManager != null && legendManager.contains(e.getX(), e.getY())) {
                    return; // Event consumed by legend
                }
                plotMouseHandler.mouseWheelMoved(e);
            }
        };

        addMouseListener(compositeHandler);
        addMouseMotionListener(compositeHandler);
        addMouseWheelListener(compositeHandler);
    }
    
    private Rectangle getPlotArea() {
        int width = getWidth();
        int height = getHeight();
        
        return new Rectangle(
            MARGIN_LEFT,
            MARGIN_TOP,
            width - MARGIN_LEFT - MARGIN_RIGHT,
            height - MARGIN_TOP - MARGIN_BOTTOM
        );
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        
        // Update viewport with current plot area
        Rectangle plotArea = getPlotArea();
        if (currentViewport != null) {
            currentViewport = currentViewport.withPlotArea(
                plotArea.x, plotArea.y, plotArea.width, plotArea.height);
        } else {
            createDefaultViewport();
        }
        
        // Render using the new rendering engine
        if (displayDataSet != null && renderer != null) {
            renderer.render(g2d, displayDataSet, currentViewport);
        } else {
            // Fallback to empty state
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.setFont(new Font("Arial", Font.PLAIN, 16));

            String message = "No data loaded";
            FontMetrics fm = g2d.getFontMetrics();
            int messageX = plotArea.x + (plotArea.width - fm.stringWidth(message)) / 2;
            int messageY = plotArea.y + plotArea.height / 2;

            g2d.drawString(message, messageX, messageY);
        }

        // Update coordinate display manager and render overlays
        if (coordinateDisplayManager != null) {
            coordinateDisplayManager.updateCoordinateDisplay(displayDataSet, currentViewport);
            coordinateDisplayManager.renderCoordinateOverlays(g2d, currentViewport);
        }

        // Render legend (after plot and coordinates, so it appears on top)
        if (legendManager != null) {
            legendManager.render(g2d, currentViewport);
        }

        g2d.dispose();
    }
    
    public void zoomIn() {
        if (plotInteractionManager != null) {
            plotInteractionManager.zoomIn();
        }
    }

    public void zoomOut() {
        if (plotInteractionManager != null) {
            plotInteractionManager.zoomOut();
        }
    }
    
    public void zoomToFit() {
        zoomToFitData();
        repaint();
    }

    public void fitYAxis() {
        if (plotInteractionManager != null) {
            plotInteractionManager.fitYAxisToCurrentXRange();
        }
    }

    public void resetView() {
        zoomToFitData();
        repaint();
    }
    
    public void setAutoYMode(boolean autoYMode) {
        this.autoYMode = autoYMode;
        if (plotInteractionManager != null) {
            plotInteractionManager.setAutoYMode(autoYMode);
        }
    }

    public void saveData() {
        if (plotInteractionManager != null) {
            plotInteractionManager.saveData();
        }
    }

    public boolean isAutoYMode() {
        return autoYMode;
    }

    public void setShowCoordinates(boolean showCoordinates) {
        if (coordinateDisplayManager != null) {
            coordinateDisplayManager.setShowCoordinates(showCoordinates);
        }
    }

    public boolean isShowCoordinates() {
        return coordinateDisplayManager != null && coordinateDisplayManager.isShowCoordinates();
    }

    public void setLegendCollapsed(boolean collapsed) {
        if (legendManager != null) {
            legendManager.setCollapsed(collapsed);
            repaint();
        }
    }

    public boolean isLegendCollapsed() {
        return legendManager != null && legendManager.isCollapsed();
    }

    /**
     * Sets the precision preference supplier for export operations.
     * This only affects data export format, not plotting functionality.
     */
    public void setPrecision64Supplier(Supplier<Boolean> precision64Supplier) {
        if (plotInteractionManager != null) {
            plotInteractionManager.setPrecision64Supplier(precision64Supplier);
        }
    }

    /**
     * Sets the render mode for a specific series (LINE, POINTS, or LINE_AND_POINTS).
     */
    public void setSeriesRenderMode(String seriesName, com.kalix.ide.flowviz.rendering.SeriesRenderMode renderMode) {
        if (renderer != null) {
            renderer.setSeriesRenderMode(seriesName, renderMode);
            repaint();
        }
    }

    /**
     * Gets the render mode for a specific series.
     */
    public com.kalix.ide.flowviz.rendering.SeriesRenderMode getSeriesRenderMode(String seriesName) {
        if (renderer != null) {
            return renderer.getSeriesRenderMode(seriesName);
        }
        return com.kalix.ide.flowviz.rendering.SeriesRenderMode.LINE;
    }

    /**
     * Sets the X-axis type override (COUNT, TIME, or PERCENTILE).
     * Set to null to use automatic detection based on plot type.
     */
    public void setXAxisType(XAxisType xAxisType) {
        this.xAxisTypeOverride = xAxisType;

        // Update viewport with new axis type
        if (currentViewport != null) {
            XAxisType newAxisType = determineXAxisType();
            Rectangle plotArea = getPlotArea();
            currentViewport = new ViewPort(
                currentViewport.getStartTimeMs(),
                currentViewport.getEndTimeMs(),
                currentViewport.getMinValue(),
                currentViewport.getMaxValue(),
                plotArea.x, plotArea.y, plotArea.width, plotArea.height,
                yAxisScale,
                newAxisType
            );
        }

        repaint();
    }

    /**
     * Gets the current X-axis type (considering override).
     */
    public XAxisType getXAxisType() {
        return determineXAxisType();
    }

    // Legend management methods

    /**
     * Adds a series to the plot legend.
     */
    public void addLegendSeries(String name, Color color) {
        if (legendManager != null) {
            legendManager.addSeries(name, color);
            repaint();
        }
    }

    /**
     * Removes a series from the plot legend.
     */
    public void removeLegendSeries(String name) {
        if (legendManager != null) {
            legendManager.removeSeries(name);
            repaint();
        }
    }

    /**
     * Clears all series from the plot legend.
     */
    public void clearLegend() {
        if (legendManager != null) {
            legendManager.clear();
            repaint();
        }
    }

    /**
     * Gets the legend manager for direct access.
     */
    public PlotLegendManager getLegendManager() {
        return legendManager;
    }

    /**
     * Sets the aggregation period and method for this plot.
     * Triggers rebuild of display dataset.
     */
    public void setAggregation(AggregationPeriod period, AggregationMethod method) {
        if (period == null || method == null) {
            return;
        }

        this.aggregationPeriod = period;
        this.aggregationMethod = method;

        rebuildDisplayDataSet();

        // If Auto-Y mode is enabled, preserve X zoom and fit Y-axis to new data
        // Otherwise, zoom to fit both axes (user expects to see all the new data)
        if (autoYMode) {
            fitYAxis();
        } else {
            zoomToFit();
        }
    }

    /**
     * Gets the current aggregation period.
     * @return The current aggregation period
     */
    public AggregationPeriod getAggregationPeriod() {
        return aggregationPeriod;
    }

    /**
     * Gets the current aggregation method.
     * @return The current aggregation method
     */
    public AggregationMethod getAggregationMethod() {
        return aggregationMethod;
    }

    /**
     * Refreshes the display dataset from the original data.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    public void refreshData(boolean resetZoom) {
        // Invalidate caches to force rebuild with new data
        lastTransformKey = null;
        renderer.clearCache();  // Clear LOD rendering cache

        rebuildDisplayDataSet();

        // Refit viewport if we have data and resetZoom is requested
        if (resetZoom && displayDataSet != null && !displayDataSet.isEmpty()) {
            zoomToFit();
        }

        repaint();
    }

    /**
     * Sets the Y-axis scale for this plot.
     * Updates the viewport to use the new transformation.
     */
    public void setYAxisScale(YAxisScale scale) {
        if (scale == null) {
            return;
        }

        this.yAxisScale = scale;

        // Update viewport with new scale
        if (currentViewport != null) {
            currentViewport = currentViewport.withYAxisScale(scale);
        }

        // If Auto-Y mode is enabled, re-fit Y-axis to data in the new scale
        if (autoYMode) {
            fitYAxis();
        } else {
            repaint();
        }
    }

    /**
     * Gets the current Y-axis scale.
     * @return the current Y-axis scale
     */
    public YAxisScale getYAxisScale() {
        return yAxisScale;
    }

    /**
     * Sets the plot type for this plot.
     * Triggers rebuild of display dataset to apply the transformation.
     */
    public void setPlotType(PlotType type) {
        if (type == null || type == this.plotType) {
            return;
        }

        PlotType oldPlotType = this.plotType;
        this.plotType = type;
        lastReferenceSeries = null; // Reset reference tracking

        rebuildDisplayDataSet();

        // Check if X-axis domain changed (switching to/from EXCEEDANCE)
        boolean xAxisDomainChanged = (oldPlotType == PlotType.EXCEEDANCE) != (type == PlotType.EXCEEDANCE);

        // If X-axis domain changed, must recalculate entire viewport (can't preserve X zoom)
        // Otherwise, follow same logic as aggregation changes
        if (xAxisDomainChanged) {
            zoomToFit();  // Full recalculation - X domain is completely different
        } else if (autoYMode) {
            fitYAxis();   // Preserve X zoom, fit Y to new data
        } else {
            zoomToFit();  // Zoom to fit both axes
        }
    }

    /**
     * Gets the current plot type.
     */
    public PlotType getPlotType() {
        return plotType;
    }

    /**
     * Checks if the reference series (first visible series) has changed
     * for DIFFERENCE plot types. If so, triggers recalculation.
     */
    private void checkReferenceSeriesChange() {
        // Only check if current plot type requires reference series
        if (!plotType.requiresReferenceSeries()) {
            lastReferenceSeries = null;
            return;
        }

        // Determine new reference series
        String newReference = visibleSeries.isEmpty() ? null : visibleSeries.get(0);

        // Check if reference changed
        if (!java.util.Objects.equals(lastReferenceSeries, newReference)) {
            // Reference series changed - need to recalculate
            rebuildDisplayDataSet();

            if (autoYMode) {
                fitYAxis();
            }
        }
    }

    /**
     * Rebuilds the display dataset by applying current transformation settings.
     * Pipeline: Aggregation → Plot Type Transform → (Y-axis scale applied during render)
     * Uses caching to avoid unnecessary recomputation.
     */
    private void rebuildDisplayDataSet() {
        if (originalDataSet == null) {
            displayDataSet = null;
            return;
        }

        // Generate cache key from current transform settings
        String referenceKey = plotType.requiresReferenceSeries() && !visibleSeries.isEmpty()
            ? visibleSeries.get(0) : "none";
        String transformKey = aggregationPeriod.name() + "_" + aggregationMethod.name()
            + "_" + plotType.name() + "_" + referenceKey;

        // Check if we can reuse cached result
        if (transformKey.equals(lastTransformKey) && displayDataSet != null) {
            return; // Already computed
        }

        // Step 1: Build aggregated dataset
        DataSet aggregatedDataSet = new DataSet();

        for (String seriesName : originalDataSet.getSeriesNames()) {
            TimeSeriesData originalSeries = originalDataSet.getSeries(seriesName);

            // Apply aggregation
            TimeSeriesData aggregatedSeries = TimeSeriesAggregator.aggregate(
                originalSeries,
                aggregationPeriod,
                aggregationMethod
            );

            if (aggregatedSeries != null) {
                aggregatedDataSet.addSeries(aggregatedSeries);
            }
        }

        // Step 2: Apply plot type transformation
        displayDataSet = PlotTypeTransformer.transform(
            aggregatedDataSet,
            plotType,
            visibleSeries
        );

        // Update reference tracking
        if (plotType.requiresReferenceSeries() && !visibleSeries.isEmpty()) {
            lastReferenceSeries = visibleSeries.get(0);
        } else {
            lastReferenceSeries = null;
        }

        // Update cache key
        lastTransformKey = transformKey;
    }
}