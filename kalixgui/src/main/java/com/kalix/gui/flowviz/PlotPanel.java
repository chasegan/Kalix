package com.kalix.gui.flowviz;

import com.kalix.gui.flowviz.data.DataSet;
import com.kalix.gui.flowviz.data.TimeSeriesData;
import com.kalix.gui.flowviz.rendering.TimeSeriesRenderer;
import com.kalix.gui.flowviz.rendering.ViewPort;
import com.kalix.gui.io.TimeSeriesCsvExporter;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.kalix.gui.constants.UIConstants;

public class PlotPanel extends JPanel {
    
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
    private DataSet dataSet;
    private TimeSeriesRenderer renderer;
    private ViewPort currentViewport;
    private Map<String, Color> seriesColors;
    private List<String> visibleSeries;
    private boolean autoYMode = false;

    // Managers
    private CoordinateDisplayManager coordinateDisplayManager;
    private PlotInteractionManager plotInteractionManager;

    public PlotPanel() {
        setBackground(Color.WHITE);
        
        // Initialize data structures
        seriesColors = new HashMap<>();
        visibleSeries = new java.util.ArrayList<>();
        renderer = new TimeSeriesRenderer(seriesColors, visibleSeries);

        // Initialize managers
        coordinateDisplayManager = new CoordinateDisplayManager(this, seriesColors, visibleSeries);
        plotInteractionManager = new PlotInteractionManager(this, coordinateDisplayManager);

        // Setup manager data access
        plotInteractionManager.setupDataAccess(
            () -> dataSet,
            () -> currentViewport,
            viewport -> currentViewport = viewport,
            () -> visibleSeries,
            this::getPlotArea
        );

        setupMouseListeners();
    }
    
    public void setDataSet(DataSet dataSet) {
        this.dataSet = dataSet;
        
        // Create initial viewport to show all data
        if (dataSet != null && !dataSet.isEmpty()) {
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
        if (dataSet == null || dataSet.isEmpty()) {
            createDefaultViewport();
            return;
        }
        
        long startTime = dataSet.getGlobalMinTime();
        long endTime = dataSet.getGlobalMaxTime();
        Double minValue = dataSet.getGlobalMinValue();
        Double maxValue = dataSet.getGlobalMaxValue();
        
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
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height);
    }
    
    private void createDefaultViewport() {
        // Default viewport showing current time ± 1 hour
        long now = System.currentTimeMillis();
        long startTime = now - 3600000; // 1 hour ago
        long endTime = now + 3600000;   // 1 hour from now

        Rectangle plotArea = getPlotArea();
        currentViewport = new ViewPort(startTime, endTime, -10.0, 10.0,
                                     plotArea.x, plotArea.y, plotArea.width, plotArea.height);
    }

    private void setupMouseListeners() {
        MouseAdapter mouseHandler = plotInteractionManager.createMouseHandler();
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
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
        if (dataSet != null && renderer != null) {
            renderer.render(g2d, dataSet, currentViewport);
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
            coordinateDisplayManager.updateCoordinateDisplay(dataSet, currentViewport);
            coordinateDisplayManager.renderCoordinateOverlays(g2d, currentViewport);
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

    public void setShowCoordinates(boolean showCoordinates) {
        if (coordinateDisplayManager != null) {
            coordinateDisplayManager.setShowCoordinates(showCoordinates);
        }
    }

    public boolean isShowCoordinates() {
        return coordinateDisplayManager != null && coordinateDisplayManager.isShowCoordinates();
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
}