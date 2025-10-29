package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlotPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(PlotPanel.class);

    // Plot margins
    private static final int MARGIN_LEFT = 80;
    private static final int MARGIN_RIGHT = 20;
    private static final int MARGIN_TOP = 20;
    private static final int MARGIN_BOTTOM = 60;
    
    // Zoom and pan state
    private static final double ZOOM_FACTOR = UIConstants.Zoom.ZOOM_FACTOR;
    private static final double MIN_ZOOM = 0.001;
    private static final double MAX_ZOOM = 1000.0;
    
    
    // Data and rendering
    private DataSet originalDataSet;  // Reference to shared dataset
    private DataSet displayDataSet;   // Transformed for display (cached)
    private TimeSeriesRenderer renderer;
    private ViewPort currentViewport;
    private Map<String, Color> seriesColors;
    private List<String> visibleSeries;
    private boolean autoYMode = false;

    // Transform settings (per-tab)
    private AggregationPeriod aggregationPeriod = AggregationPeriod.ORIGINAL;
    private AggregationMethod aggregationMethod = AggregationMethod.SUM;
    private YAxisScale yAxisScale = YAxisScale.LINEAR;

    // Cache management
    private String lastTransformKey = null;

    // Managers
    private CoordinateDisplayManager coordinateDisplayManager;
    private PlotInteractionManager plotInteractionManager;
    private PlotLegendManager legendManager;

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
        
        // Add 5% padding
        long timePadding = (long) ((endTime - startTime) * 0.05);
        double valuePadding = (maxValue - minValue) * 0.05;
        
        startTime -= timePadding;
        endTime += timePadding;
        minValue -= valuePadding;
        maxValue += valuePadding;
        
        // Ensure minimum range
        if (endTime - startTime < 1000) { // Less than 1 second
            long center = (startTime + endTime) / 2;
            startTime = center - 500;
            endTime = center + 500;
        }
        
        if (maxValue - minValue < 0.001) { // Very small value range
            double center = (minValue + maxValue) / 2;
            minValue = center - 0.5;
            maxValue = center + 0.5;
        }
        
        Rectangle plotArea = getPlotArea();
        currentViewport = new ViewPort(startTime, endTime, minValue, maxValue,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height, yAxisScale);
    }

    private void createDefaultViewport() {
        // Default viewport showing current time ± 1 hour
        long now = System.currentTimeMillis();
        long startTime = now - 3600000; // 1 hour ago
        long endTime = now + 3600000;   // 1 hour from now

        Rectangle plotArea = getPlotArea();
        currentViewport = new ViewPort(startTime, endTime, -10.0, 10.0,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height, yAxisScale);
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
        // Log stack trace to see who's calling this
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (stackTrace.length > 2) {
            logger.info("zoomToFit() called from: {}.{}() line {}",
                stackTrace[2].getClassName(),
                stackTrace[2].getMethodName(),
                stackTrace[2].getLineNumber());
        }
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
     * Refreshes the display dataset from the original data.
     * Call this when the original dataset has been modified externally
     * (e.g., series added or removed).
     */
    public void refreshData() {
        refreshData(true);
    }

    /**
     * Refreshes the display dataset from the original data.
     *
     * @param resetZoom If true, resets zoom to fit all data. If false, preserves current zoom.
     */
    public void refreshData(boolean resetZoom) {
        // Invalidate cache to force rebuild with new data
        lastTransformKey = null;

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
     * Rebuilds the display dataset by applying current aggregation settings.
     * Uses caching to avoid unnecessary recomputation.
     */
    private void rebuildDisplayDataSet() {
        if (originalDataSet == null) {
            displayDataSet = null;
            return;
        }

        // Generate cache key from current transform settings
        String transformKey = aggregationPeriod.name() + "_" + aggregationMethod.name();

        // Check if we can reuse cached result
        if (transformKey.equals(lastTransformKey) && displayDataSet != null) {
            return; // Already computed
        }

        // Build new display dataset
        displayDataSet = new DataSet();

        for (String seriesName : originalDataSet.getSeriesNames()) {
            TimeSeriesData originalSeries = originalDataSet.getSeries(seriesName);

            // Apply aggregation
            TimeSeriesData transformedSeries = TimeSeriesAggregator.aggregate(
                originalSeries,
                aggregationPeriod,
                aggregationMethod
            );

            if (transformedSeries != null) {
                displayDataSet.addSeries(transformedSeries);
            }
        }

        // Update cache key
        lastTransformKey = transformKey;
    }
}