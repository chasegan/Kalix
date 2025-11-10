package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.rendering.XAxisType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the coordinate display overlay system for the FlowViz plot panel.
 *
 * This class handles:
 * - Mouse hover coordinate detection
 * - Efficient nearest point lookup using binary search
 * - Coordinate overlay rendering with smart positioning
 * - Performance optimization with throttled updates
 */
public class CoordinateDisplayManager {

    private final JComponent parentComponent;
    private final Map<String, Color> seriesColors;
    private final List<String> visibleSeries;

    // Coordinate display state
    private boolean showCoordinates = false;
    private Point lastMousePosition;
    private List<CoordinateInfo> currentCoordinates = new ArrayList<>();
    private Timer coordinateUpdateTimer;

    /**
     * Creates a new coordinate display manager for handling mouse hover coordinate display.
     *
     * <p>This manager provides efficient coordinate tracking using binary search algorithms
     * for O(log n) performance, smart positioning to avoid overlaps, and throttled updates
     * for smooth user experience during mouse movement.
     *
     * @param parentComponent The Swing component to repaint when coordinates change
     * @param seriesColors Map of series names to their display colors for consistent theming
     * @param visibleSeries List of currently visible series names to filter coordinate display
     */
    public CoordinateDisplayManager(JComponent parentComponent, Map<String, Color> seriesColors, List<String> visibleSeries) {
        this.parentComponent = parentComponent;
        this.seriesColors = seriesColors;
        this.visibleSeries = visibleSeries;

        setupCoordinateDisplay();
    }

    /**
     * Sets up the coordinate display system with throttled updates for optimal performance.
     *
     * <p>Creates a throttling timer that limits coordinate updates to 60 FPS (16ms intervals)
     * to prevent excessive repainting during rapid mouse movement while maintaining smooth
     * visual feedback.
     */
    private void setupCoordinateDisplay() {
        // Set up a timer for throttled coordinate updates (60 FPS max)
        coordinateUpdateTimer = new Timer(16, e -> updateCoordinatesAtMousePosition());
        coordinateUpdateTimer.setRepeats(false);
    }

    /**
     * Handles mouse movement events for coordinate tracking and boundary validation.
     *
     * <p>This method is called on every mouse movement within the parent component.
     * It performs boundary checking to ensure coordinates are only displayed when
     * the mouse is within the plot area, and triggers throttled coordinate updates
     * to maintain performance.
     *
     * @param e The mouse movement event containing cursor position
     * @param plotArea The plot area rectangle for boundary checking and coordinate validation
     */
    public void handleMouseMoved(MouseEvent e, Rectangle plotArea) {
        if (showCoordinates && plotArea.contains(e.getPoint())) {
            lastMousePosition = e.getPoint();
            startCoordinateUpdate();
        } else if (showCoordinates && !plotArea.contains(e.getPoint())) {
            // Clear coordinates when mouse leaves plot area
            clearCoordinates();
        }
    }

    /**
     * Renders coordinate overlays on the graphics context.
     *
     * @param g2d The graphics context
     * @param viewport The current viewport for coordinate transformations
     */
    public void renderCoordinateOverlays(Graphics2D g2d, ViewPort viewport) {
        if (!showCoordinates || currentCoordinates.isEmpty() || viewport == null) {
            return;
        }

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Calculate positions and handle stacking
        List<Rectangle> usedAreas = new ArrayList<>();

        for (CoordinateInfo coord : currentCoordinates) {
            if (!viewport.isPointVisible(coord.timestamp, coord.value)) continue;

            int screenX = viewport.timeToScreenX(coord.timestamp);
            int screenY = viewport.valueToScreenY(coord.value);

            // Create coordinate text (single line with comma)
            String timeText = formatTimeForDisplay(coord.timestamp);
            String valueText = formatValueForDisplay(coord.value);
            String displayText = timeText + ", " + valueText;

            // Calculate rectangle bounds (single line)
            FontMetrics fm = g2d.getFontMetrics(new Font("Arial", Font.PLAIN, 10));
            int textWidth = fm.stringWidth(displayText);
            int textHeight = fm.getHeight();

            // Add padding
            int rectWidth = textWidth + 8;
            int rectHeight = textHeight + 6;

            // Calculate optimal position with stacking
            Rectangle plotArea = new Rectangle(viewport.getPlotX(), viewport.getPlotY(),
                                             viewport.getPlotWidth(), viewport.getPlotHeight());
            Rectangle optimalRect = calculateOptimalPosition(screenX, screenY, rectWidth, rectHeight, usedAreas, plotArea);
            usedAreas.add(optimalRect);

            // Draw the coordinate box and point
            drawCoordinateBox(g2d, optimalRect, coord.color, displayText, fm);
            drawDataPoint(g2d, screenX, screenY, coord.color);
        }
    }

    /**
     * Sets whether coordinate display is enabled.
     *
     * @param showCoordinates True to enable coordinate display
     */
    public void setShowCoordinates(boolean showCoordinates) {
        this.showCoordinates = showCoordinates;
        if (!showCoordinates) {
            clearCoordinates();
        }
        parentComponent.repaint();
    }

    /**
     * Returns whether coordinate display is currently enabled.
     *
     * @return True if coordinate display is enabled
     */
    public boolean isShowCoordinates() {
        return showCoordinates;
    }

    /**
     * Updates coordinate display for the current dataset and viewport.
     *
     * @param dataSet The current dataset
     * @param viewport The current viewport
     */
    public void updateCoordinateDisplay(DataSet dataSet, ViewPort viewport) {
        this.dataSet = dataSet;
        this.currentViewport = viewport;
    }

    // Private fields for coordinate updating
    private DataSet dataSet;
    private ViewPort currentViewport;

    /**
     * Starts a throttled coordinate update.
     */
    private void startCoordinateUpdate() {
        if (coordinateUpdateTimer != null && !coordinateUpdateTimer.isRunning()) {
            coordinateUpdateTimer.start();
        }
    }

    /**
     * Updates the coordinate display for the current mouse position.
     */
    private void updateCoordinatesAtMousePosition() {
        if (lastMousePosition == null || currentViewport == null || dataSet == null) {
            clearCoordinates();
            return;
        }

        // Convert mouse X position to time
        long mouseTime = currentViewport.screenXToTime(lastMousePosition.x);

        // Find closest points for all visible series
        List<CoordinateInfo> newCoordinates = new ArrayList<>();
        for (String seriesName : visibleSeries) {
            TimeSeriesData series = dataSet.getSeries(seriesName);
            if (series != null) {
                CoordinateInfo coord = findNearestPoint(series, mouseTime, seriesName);
                if (coord != null) {
                    newCoordinates.add(coord);
                }
            }
        }

        // Update coordinates if they changed
        if (!newCoordinates.equals(currentCoordinates)) {
            currentCoordinates = newCoordinates;
            parentComponent.repaint();
        }
    }

    /**
     * Finds the nearest valid data point to the given time using binary search for O(log n) performance.
     *
     * <p>This method uses a two-phase approach:
     * <ol>
     * <li>Binary search to quickly locate the closest timestamp index</li>
     * <li>Local search around the found index to find the nearest valid (non-NaN, non-null) point</li>
     * </ol>
     *
     * <p>The algorithm handles edge cases including empty series, all-invalid data points,
     * and boundary conditions at the start/end of the time series.
     *
     * @param series The time series data to search within
     * @param targetTime The target timestamp in milliseconds to find the nearest point for
     * @param seriesName The name of the series for coordinate info creation
     * @return CoordinateInfo containing the nearest valid point, or null if no valid point exists
     */
    private CoordinateInfo findNearestPoint(TimeSeriesData series, long targetTime, String seriesName) {
        long[] timestamps = series.getTimestamps();
        double[] values = series.getValues();
        boolean[] validPoints = series.getValidPoints();

        if (timestamps.length == 0) return null;

        // Binary search for closest time
        int index = binarySearchNearest(timestamps, targetTime);

        // Find the closest valid point around the found index
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;

        // Check a few points around the binary search result
        for (int i = Math.max(0, index - 2); i < Math.min(timestamps.length, index + 3); i++) {
            if (validPoints[i]) {
                long distance = Math.abs(timestamps[i] - targetTime);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
        }

        if (bestIndex == -1) return null;

        return new CoordinateInfo(
            seriesName,
            timestamps[bestIndex],
            values[bestIndex],
            seriesColors.get(seriesName)
        );
    }

    /**
     * Performs binary search to find the index of the timestamp closest to the target.
     *
     * <p>This is a standard binary search algorithm optimized for finding the nearest
     * value rather than exact matches. It handles edge cases where the target is
     * outside the range of available timestamps by returning the closest boundary index.
     *
     * @param timestamps Sorted array of timestamps in ascending order
     * @param target The target timestamp to find the closest match for
     * @return Index of the timestamp closest to the target value
     */
    private int binarySearchNearest(long[] timestamps, long target) {
        if (timestamps.length == 0) return 0;
        if (target <= timestamps[0]) return 0;
        if (target >= timestamps[timestamps.length - 1]) return timestamps.length - 1;

        int left = 0;
        int right = timestamps.length - 1;

        while (left < right) {
            int mid = (left + right) / 2;
            if (timestamps[mid] < target) {
                left = mid + 1;
            } else {
                right = mid;
            }
        }

        // Check if the previous point is closer
        if (left > 0 && Math.abs(timestamps[left - 1] - target) < Math.abs(timestamps[left] - target)) {
            return left - 1;
        }
        return left;
    }

    /**
     * Calculates the optimal position for a coordinate rectangle, avoiding overlaps.
     */
    private Rectangle calculateOptimalPosition(int pointX, int pointY, int width, int height,
                                             List<Rectangle> usedAreas, Rectangle plotArea) {
        // Try positions in order of preference: right, left, above, below
        int[] xOffsets = {10, -width - 10, -width / 2, -width / 2};
        int[] yOffsets = {-height / 2, -height / 2, -height - 10, 10};

        for (int i = 0; i < xOffsets.length; i++) {
            int x = pointX + xOffsets[i];
            int y = pointY + yOffsets[i];

            // Clamp to plot area
            x = Math.max(plotArea.x, Math.min(x, plotArea.x + plotArea.width - width));
            y = Math.max(plotArea.y, Math.min(y, plotArea.y + plotArea.height - height));

            Rectangle candidate = new Rectangle(x, y, width, height);

            // Check for overlaps and stack if needed
            boolean overlaps = false;
            for (Rectangle used : usedAreas) {
                if (candidate.intersects(used)) {
                    overlaps = true;
                    // Try stacking below
                    candidate.y = used.y + used.height + 2;
                    if (candidate.y + candidate.height > plotArea.y + plotArea.height) {
                        // Stack above instead
                        candidate.y = used.y - candidate.height - 2;
                    }
                    break;
                }
            }

            if (!overlaps || !intersectsAny(candidate, usedAreas)) {
                return candidate;
            }
        }

        // Fallback: place at default position even if it overlaps
        int x = Math.max(plotArea.x, Math.min(pointX + 10, plotArea.x + plotArea.width - width));
        int y = Math.max(plotArea.y, Math.min(pointY - height / 2, plotArea.y + plotArea.height - height));
        return new Rectangle(x, y, width, height);
    }

    /**
     * Checks if a rectangle intersects with any rectangle in the list.
     */
    private boolean intersectsAny(Rectangle rect, List<Rectangle> rects) {
        return rects.stream().anyMatch(rect::intersects);
    }

    /**
     * Draws a coordinate display box with translucent background and border.
     */
    private void drawCoordinateBox(Graphics2D g2d, Rectangle bounds, Color seriesColor, String text, FontMetrics fm) {
        // Draw more transparent background
        Color bgColor = new Color(255, 255, 255, 160);
        g2d.setColor(bgColor);
        g2d.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

        // Draw thin border
        Color borderColor = seriesColor != null ?
            new Color(seriesColor.getRed(), seriesColor.getGreen(), seriesColor.getBlue(), 120) :
            new Color(128, 128, 128, 120);
        g2d.setColor(borderColor);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);

        // Draw text
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));

        int textX = bounds.x + 4;
        int textY = bounds.y + fm.getAscent() + 2;

        g2d.drawString(text, textX, textY);
    }

    /**
     * Draws a small circle at the data point location.
     */
    private void drawDataPoint(Graphics2D g2d, int screenX, int screenY, Color seriesColor) {
        if (seriesColor == null) return;

        g2d.setColor(seriesColor);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw filled circle (4px diameter)
        int radius = 2;
        g2d.fillOval(screenX - radius, screenY - radius, radius * 2, radius * 2);

        // Draw slightly darker border
        Color borderColor = new Color(
            Math.max(0, seriesColor.getRed() - 40),
            Math.max(0, seriesColor.getGreen() - 40),
            Math.max(0, seriesColor.getBlue() - 40)
        );
        g2d.setColor(borderColor);
        g2d.drawOval(screenX - radius, screenY - radius, radius * 2, radius * 2);
    }

    /**
     * Formats timestamp for display based on axis type and precision requirements.
     */
    private String formatTimeForDisplay(long timestampMs) {
        // Check if we're in percentile mode (exceedance plots)
        if (currentViewport != null && currentViewport.getXAxisType() == XAxisType.PERCENTILE) {
            // Convert fake timestamp to percentile
            double percentile = timestampMs / 1_000_000.0;

            // Format with appropriate precision
            if (percentile == Math.floor(percentile)) {
                return String.format("%.0f%%", percentile);
            } else {
                return String.format("%.2f%%", percentile);
            }
        }

        // Standard time formatting
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneOffset.UTC);

        // Check if it's a whole day (midnight UTC)
        if (dateTime.getHour() == 0 && dateTime.getMinute() == 0 && dateTime.getSecond() == 0 &&
            timestampMs % 1000 == 0) {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else {
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        }
    }

    /**
     * Formats value for display with appropriate precision.
     */
    private String formatValueForDisplay(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.0f", value);
        } else if (Math.abs(value) >= 1) {
            return String.format("%.3g", value);
        } else {
            return String.format("%.4g", value);
        }
    }

    /**
     * Clears all coordinate displays and triggers a repaint.
     */
    private void clearCoordinates() {
        if (!currentCoordinates.isEmpty()) {
            currentCoordinates.clear();
            parentComponent.repaint();
        }
    }

    /**
     * Data class to hold coordinate information for display.
     */
    private static class CoordinateInfo {
        final String seriesName;
        final long timestamp;
        final double value;
        final Color color;

        CoordinateInfo(String seriesName, long timestamp, double value, Color color) {
            this.seriesName = seriesName;
            this.timestamp = timestamp;
            this.value = value;
            this.color = color;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CoordinateInfo other)) return false;
            return seriesName.equals(other.seriesName) &&
                   timestamp == other.timestamp &&
                   Double.compare(value, other.value) == 0;
        }

        @Override
        public int hashCode() {
            return seriesName.hashCode() + Long.hashCode(timestamp) + Double.hashCode(value);
        }
    }
}