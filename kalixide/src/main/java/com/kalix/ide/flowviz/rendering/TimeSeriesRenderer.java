package com.kalix.ide.flowviz.rendering;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.SeriesStyleResolver;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Map;

public class TimeSeriesRenderer {

    private final LODManager lodManager;
    private final List<SeriesRef> visibleSeries;
    private final Map<SeriesRef, SeriesRenderMode> seriesRenderModes;
    private final AxisRenderer axisRenderer;

    // Resolves each series to its colour + stroke at paint time (late-bound, so
    // palette edits propagate). Set by PlotPanel immediately after construction.
    private SeriesStyleResolver styleResolver;

    // Rendering options
    private boolean showDataPoints = false;
    private boolean showGrid = true;
    private boolean antiAliasing = true;

    public TimeSeriesRenderer(List<SeriesRef> visibleSeries) {
        this.lodManager = new LODManager();
        this.visibleSeries = visibleSeries;
        this.seriesRenderModes = new java.util.HashMap<>();
        this.axisRenderer = new AxisRenderer();
    }

    /** Sets the resolver consulted for each series' colour and stroke. */
    public void setStyleResolver(SeriesStyleResolver styleResolver) {
        this.styleResolver = styleResolver;
    }
    
    public void render(Graphics2D g2d, DataSet dataSet, ViewPort viewport) {
        if (dataSet.isEmpty()) {
            renderEmptyState(g2d, viewport);
            return;
        }
        
        // Set rendering hints
        if (antiAliasing) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }
        
        // Clear plot area
        g2d.setColor(Color.WHITE);
        g2d.fillRect(viewport.getPlotX(), viewport.getPlotY(), 
                    viewport.getPlotWidth(), viewport.getPlotHeight());
        
        // Calculate axis information once for both grid and axis drawing
        AxisRenderer.AxisInfo axisInfo = axisRenderer.calculateAxisInfo(viewport);

        // Draw grid
        if (showGrid) {
            axisRenderer.drawGrid(g2d, viewport, axisInfo);
        }

        // Draw axes
        axisRenderer.drawAxes(g2d, viewport, axisInfo);
        
        // Draw time series data in legend order (visibleSeries list order)
        for (SeriesRef ref : visibleSeries) {
            TimeSeriesData series = dataSet.getSeries(ref);
            if (series != null && styleResolver != null) {
                renderSeries(g2d, ref, series, viewport, styleResolver.styleFor(ref));
            }
        }
        
        // Draw plot border
        drawPlotBorder(g2d, viewport);
    }
    
    private void renderSeries(Graphics2D g2d, SeriesRef ref, TimeSeriesData series, ViewPort viewport, LineStyle style) {
        LODManager.RenderStrategy strategy = lodManager.determineRenderStrategy(ref, series, viewport);

        // Get render mode for this series (default to LINE)
        SeriesRenderMode renderMode = seriesRenderModes.getOrDefault(ref, SeriesRenderMode.LINE);

        // Colour carries opacity in its alpha channel; the stroke carries thickness
        // and dash. The LOD path inherits this colour but keeps its own fixed strokes.
        g2d.setColor(style.color());
        g2d.setStroke(style.stroke().toBasicStroke());

        if (strategy.useFullResolution) {
            renderFullResolution(g2d, series, viewport, strategy.indexRange, renderMode);
        } else {
            renderLOD(g2d, series, viewport, strategy.lodData);
        }
    }
    
    private void renderFullResolution(Graphics2D g2d, TimeSeriesData series, ViewPort viewport,
                                    TimeSeriesData.IndexRange indexRange, SeriesRenderMode renderMode) {
        if (indexRange.isEmpty()) return;

        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();

        // Define clipping bounds
        int clipLeft = viewport.getPlotX();
        int clipRight = viewport.getPlotX() + viewport.getPlotWidth();
        int clipTop = viewport.getPlotY();
        int clipBottom = viewport.getPlotY() + viewport.getPlotHeight();

        // Extend index range to include boundary connection points
        int extendedStartIndex = Math.max(0, indexRange.startIndex - 1);
        int extendedEndIndex = Math.min(timestamps.length, indexRange.endIndex + 1);

        // Determine if we should draw lines and/or points
        boolean drawLines = (renderMode == SeriesRenderMode.LINE || renderMode == SeriesRenderMode.LINE_AND_POINTS);
        boolean drawPoints = (renderMode == SeriesRenderMode.POINTS || renderMode == SeriesRenderMode.LINE_AND_POINTS || showDataPoints);

        Path2D.Double path = null;
        if (drawLines) {
            path = new Path2D.Double();
        }
        boolean pathStarted = false;
        int prevScreenX = 0, prevScreenY = 0;

        for (int i = extendedStartIndex; i < extendedEndIndex; i++) {
            if (!validPoints[i]) {
                // Missing value - break the path
                pathStarted = false;
                continue;
            }

            long timestamp = timestamps[i];
            double value = values[i];

            int screenX = viewport.timeToScreenX(timestamp);
            int screenY = viewport.valueToScreenY(value);

            if (drawLines) {
                if (!pathStarted) {
                    // Start new path segment
                    // Always start the path, even if the point is outside bounds
                    // The clipping will handle drawing only the visible portion
                    path.moveTo(screenX, screenY);
                    pathStarted = true;
                } else {
                    // Draw line from previous point to current point with clipping
                    drawClippedLine(path, prevScreenX, prevScreenY, screenX, screenY,
                                  clipLeft, clipRight, clipTop, clipBottom);
                }
            }

            prevScreenX = screenX;
            prevScreenY = screenY;

            // Draw data points if enabled and point is visible (only for original range)
            if (drawPoints &&
                i >= indexRange.startIndex && i < indexRange.endIndex &&
                isPointInBounds(screenX, screenY, clipLeft, clipRight, clipTop, clipBottom)) {
                g2d.fillOval(screenX - 2, screenY - 2, 4, 4);
            }
        }

        if (drawLines && path != null) {
            g2d.draw(path);
        }
    }

    private boolean isPointInBounds(int x, int y, int left, int right, int top, int bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private void drawClippedLine(Path2D.Double path, int x1, int y1, int x2, int y2,
                                int clipLeft, int clipRight, int clipTop, int clipBottom) {
        // Perform line clipping using Cohen-Sutherland algorithm
        LineClipResult result = clipLine(x1, y1, x2, y2, clipLeft, clipRight, clipTop, clipBottom);

        if (result.visible) {
            // Determine if we need to start a new path segment or continue existing one
            boolean p1Inside = isPointInBounds(x1, y1, clipLeft, clipRight, clipTop, clipBottom);
            boolean p2Inside = isPointInBounds(x2, y2, clipLeft, clipRight, clipTop, clipBottom);

            if (p1Inside && p2Inside) {
                // Both points inside - continue path normally
                path.lineTo(result.x2, result.y2);
            } else if (!p1Inside && p2Inside) {
                // P1 outside, P2 inside - move to clipped start point and draw to end
                path.moveTo(result.x1, result.y1);
                path.lineTo(result.x2, result.y2);
            } else if (p1Inside && !p2Inside) {
                // P1 inside, P2 outside - continue to clipped end point
                path.lineTo(result.x2, result.y2);
            } else {
                // Both points outside but line crosses visible area
                path.moveTo(result.x1, result.y1);
                path.lineTo(result.x2, result.y2);
            }
        }
        // If line is not visible, don't draw anything
    }

    private static class LineClipResult {
        boolean visible;
        int x1, y1, x2, y2;

        LineClipResult(boolean visible, int x1, int y1, int x2, int y2) {
            this.visible = visible;
            this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
        }
    }

    private LineClipResult clipLine(int x1, int y1, int x2, int y2,
                                   int clipLeft, int clipRight, int clipTop, int clipBottom) {
        // Cohen-Sutherland outcodes
        int outcode1 = computeOutcode(x1, y1, clipLeft, clipRight, clipTop, clipBottom);
        int outcode2 = computeOutcode(x2, y2, clipLeft, clipRight, clipTop, clipBottom);

        while (true) {
            if ((outcode1 | outcode2) == 0) {
                // Both points inside
                return new LineClipResult(true, x1, y1, x2, y2);
            } else if ((outcode1 & outcode2) != 0) {
                // Both points outside same boundary
                return new LineClipResult(false, 0, 0, 0, 0);
            } else {
                // Line crosses boundary - clip it
                int outcodeOut = (outcode1 != 0) ? outcode1 : outcode2;
                int x, y;

                if ((outcodeOut & 8) != 0) { // Top
                    x = x1 + (x2 - x1) * (clipTop - y1) / (y2 - y1);
                    y = clipTop;
                } else if ((outcodeOut & 4) != 0) { // Bottom
                    x = x1 + (x2 - x1) * (clipBottom - y1) / (y2 - y1);
                    y = clipBottom;
                } else if ((outcodeOut & 2) != 0) { // Right
                    y = y1 + (y2 - y1) * (clipRight - x1) / (x2 - x1);
                    x = clipRight;
                } else { // Left
                    y = y1 + (y2 - y1) * (clipLeft - x1) / (x2 - x1);
                    x = clipLeft;
                }

                if (outcodeOut == outcode1) {
                    x1 = x; y1 = y;
                    outcode1 = computeOutcode(x1, y1, clipLeft, clipRight, clipTop, clipBottom);
                } else {
                    x2 = x; y2 = y;
                    outcode2 = computeOutcode(x2, y2, clipLeft, clipRight, clipTop, clipBottom);
                }
            }
        }
    }

    private int computeOutcode(int x, int y, int clipLeft, int clipRight, int clipTop, int clipBottom) {
        int code = 0;
        if (x < clipLeft) code |= 1;      // Left
        else if (x > clipRight) code |= 2; // Right
        if (y < clipTop) code |= 8;       // Top
        else if (y > clipBottom) code |= 4; // Bottom
        return code;
    }
    
    private void renderLOD(Graphics2D g2d, TimeSeriesData series, ViewPort viewport,
                          LODManager.LODData lodData) {
        double[][] minMaxBands = lodData.minMaxBands;
        boolean[] hasValidData = lodData.hasValidData;

        // Define clipping bounds
        int clipLeft = viewport.getPlotX();
        int clipRight = viewport.getPlotX() + viewport.getPlotWidth();
        int clipTop = viewport.getPlotY();
        int clipBottom = viewport.getPlotY() + viewport.getPlotHeight();

        g2d.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // First pass: draw connected envelope (upper and lower bounds)
        Path2D.Double upperPath = new Path2D.Double();
        Path2D.Double lowerPath = new Path2D.Double();

        boolean upperPathStarted = false;
        boolean lowerPathStarted = false;

        // Build continuous envelope paths with clipping
        for (int pixelX = 0; pixelX < lodData.pixelWidth; pixelX++) {
            if (!hasValidData[pixelX]) {
                // Gap in data - break the paths
                upperPathStarted = false;
                lowerPathStarted = false;
                continue;
            }

            double minValue = minMaxBands[pixelX][0];
            double maxValue = minMaxBands[pixelX][1];

            int screenX = viewport.getPlotX() + pixelX;
            int minScreenY = viewport.valueToScreenY(maxValue);  // Note: Y is inverted
            int maxScreenY = viewport.valueToScreenY(minValue);

            // Clip Y coordinates to plot bounds
            minScreenY = Math.max(clipTop, Math.min(clipBottom, minScreenY));
            maxScreenY = Math.max(clipTop, Math.min(clipBottom, maxScreenY));

            // Only draw if X coordinate is within bounds
            if (screenX >= clipLeft && screenX <= clipRight) {
                // Add to upper path (max values)
                if (!upperPathStarted) {
                    upperPath.moveTo(screenX, minScreenY);
                    upperPathStarted = true;
                } else {
                    upperPath.lineTo(screenX, minScreenY);
                }

                // Add to lower path (min values)
                if (!lowerPathStarted) {
                    lowerPath.moveTo(screenX, maxScreenY);
                    lowerPathStarted = true;
                } else {
                    lowerPath.lineTo(screenX, maxScreenY);
                }
            } else {
                // Outside X bounds - break paths
                upperPathStarted = false;
                lowerPathStarted = false;
            }
        }

        // Draw the envelope paths
        g2d.draw(upperPath);
        g2d.draw(lowerPath);

        // Second pass: draw vertical connectors only where there's significant range
        g2d.setStroke(new BasicStroke(1.0f));

        for (int pixelX = 0; pixelX < lodData.pixelWidth; pixelX++) {
            if (!hasValidData[pixelX]) continue;

            double minValue = minMaxBands[pixelX][0];
            double maxValue = minMaxBands[pixelX][1];

            int screenX = viewport.getPlotX() + pixelX;

            // Only draw if X coordinate is within bounds
            if (screenX >= clipLeft && screenX <= clipRight) {
                int minScreenY = viewport.valueToScreenY(maxValue);  // Note: Y is inverted
                int maxScreenY = viewport.valueToScreenY(minValue);

                // Clip Y coordinates to plot bounds
                minScreenY = Math.max(clipTop, Math.min(clipBottom, minScreenY));
                maxScreenY = Math.max(clipTop, Math.min(clipBottom, maxScreenY));

                // Only draw vertical connector if there's a significant range (more than 3 pixels)
                if (Math.abs(maxScreenY - minScreenY) > 3) {
                    // Draw thin vertical line to show full range
                    g2d.setStroke(new BasicStroke(0.5f));
                    g2d.drawLine(screenX, minScreenY, screenX, maxScreenY);
                    g2d.setStroke(new BasicStroke(1.0f));
                }
            }
        }
    }

    private void drawPlotBorder(Graphics2D g2d, ViewPort viewport) {
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.drawRect(viewport.getPlotX(), viewport.getPlotY(),
                    viewport.getPlotWidth(), viewport.getPlotHeight());
    }
    
    private static final String[] EMPTY_STATE_LINES = {
        "# If you stare into the Abyss long enough,",
        "# the Abyss stares back at you. - Friedrich Nietzsche"
    };

    private void renderEmptyState(Graphics2D g2d, ViewPort viewport) {
        g2d.setColor(Color.LIGHT_GRAY);
        g2d.setFont(new Font("Arial", Font.ITALIC, 14));
        FontMetrics fm = g2d.getFontMetrics();

        // Centre the multi-line quote as a block within the plot area.
        int lineHeight = fm.getHeight();
        int centerX = viewport.getPlotX() + viewport.getPlotWidth() / 2;
        int blockTop = viewport.getPlotY()
            + (viewport.getPlotHeight() - lineHeight * EMPTY_STATE_LINES.length) / 2;

        for (int i = 0; i < EMPTY_STATE_LINES.length; i++) {
            String line = EMPTY_STATE_LINES[i];
            int lineX = centerX - fm.stringWidth(line) / 2;
            int lineY = blockTop + i * lineHeight + fm.getAscent();
            g2d.drawString(line, lineX, lineY);
        }
    }
    
    // Rendering options
    public void setShowDataPoints(boolean show) { this.showDataPoints = show; }
    public void setShowGrid(boolean show) { this.showGrid = show; }
    public void setAntiAliasing(boolean enable) { this.antiAliasing = enable; }

    public boolean isShowDataPoints() { return showDataPoints; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isAntiAliasing() { return antiAliasing; }

    // Series render mode management
    public void setSeriesRenderMode(SeriesRef seriesRef, SeriesRenderMode renderMode) {
        if (renderMode == null) {
            seriesRenderModes.remove(seriesRef);
        } else {
            seriesRenderModes.put(seriesRef, renderMode);
        }
    }

    public SeriesRenderMode getSeriesRenderMode(SeriesRef seriesRef) {
        return seriesRenderModes.getOrDefault(seriesRef, SeriesRenderMode.LINE);
    }

    public void clearSeriesRenderModes() {
        seriesRenderModes.clear();
    }

    // Cache management
    public void clearCache() { lodManager.clearCache(); }
    public int getCacheSize() { return lodManager.getCacheSize(); }
}