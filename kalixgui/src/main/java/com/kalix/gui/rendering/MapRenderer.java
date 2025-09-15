package com.kalix.gui.rendering;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import com.kalix.gui.model.HydrologicalModel;
import com.kalix.gui.model.ModelNode;
import com.kalix.gui.themes.NodeTheme;

/**
 * Dedicated renderer class for map visualization in the KalixGUI application.
 *
 * This class handles all rendering operations for the map panel including:
 * - Grid rendering with dynamic viewport calculation
 * - Node visualization with theme-based styling
 * - Text rendering with proper font handling
 * - Selection rectangle drawing
 * - Debug information overlays
 *
 * The renderer is stateless and receives all necessary rendering context
 * through method parameters, making it thread-safe and easily testable.
 *
 * @author Claude Code Assistant
 * @version 1.0
 */
public class MapRenderer {

    // Rendering constants
    private static final int GRID_SIZE = 50;
    private static final int NODE_SIZE = 20; // Constant screen size in pixels
    private static final int TEXT_BACKGROUND_PADDING = 2;

    // Selection rectangle styling
    private static final Color SELECTION_FILL_COLOR = new Color(0, 120, 255, 50);
    private static final Color SELECTION_BORDER_COLOR = new Color(0, 120, 255, 180);
    private static final float[] SELECTION_DASH_PATTERN = {5.0f, 3.0f};

    // Debug info styling
    private static final Color DEBUG_TEXT_COLOR = Color.GRAY;
    private static final int DEBUG_TEXT_MARGIN = 10;

    /**
     * Renders the complete map view including grid, nodes, selection, and debug info.
     *
     * @param g2d Graphics context for rendering
     * @param width Panel width
     * @param height Panel height
     * @param zoomLevel Current zoom level
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     * @param showGridlines Whether to show grid lines
     * @param model Hydrological model containing nodes to render
     * @param nodeTheme Theme for node visualization
     * @param selectionStart Selection rectangle start point (null if no selection)
     * @param selectionCurrent Selection rectangle current point (null if no selection)
     */
    public void renderMap(Graphics2D g2d, int width, int height,
                         double zoomLevel, double panX, double panY,
                         boolean showGridlines, HydrologicalModel model,
                         NodeTheme nodeTheme, Point selectionStart, Point selectionCurrent) {

        // Enable antialiasing for smoother graphics
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Save original transform
        AffineTransform originalTransform = g2d.getTransform();

        // Apply pan and zoom transformations for world space rendering
        g2d.translate(panX, panY);
        g2d.scale(zoomLevel, zoomLevel);

        // Render world-space elements (grid, placeholder content)
        if (showGridlines) {
            renderGrid(g2d, width, height, zoomLevel, panX, panY);
        }
        renderPlaceholderContent(g2d);

        // Reset to screen space for screen-space elements
        g2d.setTransform(originalTransform);

        // Render screen-space elements (nodes, selection, debug)
        if (model != null) {
            renderNodes(g2d, model, nodeTheme, zoomLevel, panX, panY);
        }

        if (selectionStart != null && selectionCurrent != null) {
            renderSelectionRectangle(g2d, selectionStart, selectionCurrent);
        }

        renderDebugInfo(g2d, width, height, zoomLevel, panX, panY);
    }

    /**
     * Renders the grid overlay in world coordinates.
     *
     * @param g2d Graphics context (in world space)
     * @param panelWidth Panel width for viewport calculation
     * @param panelHeight Panel height for viewport calculation
     * @param zoomLevel Current zoom level
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderGrid(Graphics2D g2d, int panelWidth, int panelHeight,
                           double zoomLevel, double panX, double panY) {

        // Get grid color from UI theme
        Color gridColor = getGridlineColor();
        g2d.setColor(gridColor);
        g2d.setStroke(new BasicStroke(1));

        // Calculate the visible world bounds (accounting for pan and zoom transforms)
        int viewWidth = (int) (panelWidth / zoomLevel);
        int viewHeight = (int) (panelHeight / zoomLevel);
        int worldLeft = (int) (-panX / zoomLevel);
        int worldTop = (int) (-panY / zoomLevel);
        int worldRight = worldLeft + viewWidth;
        int worldBottom = worldTop + viewHeight;

        // Draw vertical lines - aligned to world grid
        int startX = (worldLeft / GRID_SIZE) * GRID_SIZE;  // Snap to grid
        for (int x = startX; x <= worldRight + GRID_SIZE; x += GRID_SIZE) {
            g2d.drawLine(x, worldTop - GRID_SIZE, x, worldBottom + GRID_SIZE);
        }

        // Draw horizontal lines - aligned to world grid
        int startY = (worldTop / GRID_SIZE) * GRID_SIZE;  // Snap to grid
        for (int y = startY; y <= worldBottom + GRID_SIZE; y += GRID_SIZE) {
            g2d.drawLine(worldLeft - GRID_SIZE, y, worldRight + GRID_SIZE, y);
        }
    }

    /**
     * Renders placeholder content in world space.
     * Currently empty but reserved for future model visualization elements.
     *
     * @param g2d Graphics context (in world space)
     */
    private void renderPlaceholderContent(Graphics2D g2d) {
        // Placeholder method for future model content
    }

    /**
     * Renders all nodes in the model using screen-space coordinates for constant size.
     *
     * @param g2d Graphics context (in screen space)
     * @param model Hydrological model containing nodes
     * @param nodeTheme Theme for node styling
     * @param zoomLevel Current zoom level for coordinate transformation
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderNodes(Graphics2D g2d, HydrologicalModel model, NodeTheme nodeTheme,
                            double zoomLevel, double panX, double panY) {

        AffineTransform originalTransform = g2d.getTransform();

        // Render each node
        for (ModelNode node : model.getAllNodes()) {
            renderSingleNode(g2d, node, model, nodeTheme, zoomLevel, panX, panY, originalTransform);
        }

        // Restore the original transform
        g2d.setTransform(originalTransform);
    }

    /**
     * Renders a single node with proper coordinate transformation and styling.
     *
     * @param g2d Graphics context
     * @param node Node to render
     * @param model Model for selection state checking
     * @param nodeTheme Theme for styling
     * @param zoomLevel Current zoom level
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     * @param originalTransform Original screen-space transform
     */
    private void renderSingleNode(Graphics2D g2d, ModelNode node, HydrologicalModel model,
                                 NodeTheme nodeTheme, double zoomLevel, double panX, double panY,
                                 AffineTransform originalTransform) {

        // Get node color from theme
        Color nodeColor = nodeTheme.getColorForNodeType(node.getType());
        g2d.setColor(nodeColor);

        // Transform node world coordinates to screen coordinates
        double screenX = node.getX() * zoomLevel + panX;
        double screenY = node.getY() * zoomLevel + panY;

        // Ensure we're in screen space for constant size rendering
        g2d.setTransform(originalTransform);

        // Render node circle
        int nodeRadius = NODE_SIZE / 2;
        g2d.fillOval((int)(screenX - nodeRadius), (int)(screenY - nodeRadius),
                    NODE_SIZE, NODE_SIZE);

        // Render node border with selection highlighting
        boolean isSelected = model.isNodeSelected(node.getName());
        g2d.setColor(isSelected ? Color.BLUE : Color.BLACK);
        g2d.setStroke(isSelected ? new BasicStroke(3.0f) : new BasicStroke(1.0f));
        g2d.drawOval((int)(screenX - nodeRadius), (int)(screenY - nodeRadius),
                    NODE_SIZE, NODE_SIZE);

        // Reset stroke for next node
        g2d.setStroke(new BasicStroke(1.0f));

        // Render node text label
        renderNodeText(g2d, node.getName(), screenX, screenY, nodeTheme);
    }

    /**
     * Renders node name text with theme-based styling.
     *
     * @param g2d Graphics context
     * @param nodeName Name of the node to render
     * @param nodeScreenX Screen X coordinate of node center
     * @param nodeScreenY Screen Y coordinate of node center
     * @param nodeTheme Theme for text styling
     */
    private void renderNodeText(Graphics2D g2d, String nodeName, double nodeScreenX,
                               double nodeScreenY, NodeTheme nodeTheme) {

        if (nodeName == null || nodeName.trim().isEmpty()) {
            return;
        }

        // Get theme text styling
        NodeTheme.TextStyle textStyle = nodeTheme.getCurrentTextStyle();

        // Apply theme font
        Font originalFont = g2d.getFont();
        Font themeFont = textStyle.createFont();
        g2d.setFont(themeFont);

        // Calculate text positioning
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(nodeName);
        int textHeight = fm.getHeight();

        double textX = nodeScreenX - (textWidth / 2.0);
        double textY = nodeScreenY + (NODE_SIZE / 2.0) + textStyle.getYOffset();

        // Render text background
        Color backgroundColor = textStyle.createBackgroundColorWithAlpha();
        g2d.setColor(backgroundColor);
        g2d.fillRect(
            (int)(textX - TEXT_BACKGROUND_PADDING),
            (int)(textY - fm.getAscent() - TEXT_BACKGROUND_PADDING),
            textWidth + (TEXT_BACKGROUND_PADDING * 2),
            textHeight + TEXT_BACKGROUND_PADDING
        );

        // Render text foreground
        g2d.setColor(textStyle.getTextColor());
        g2d.drawString(nodeName, (int)textX, (int)textY);

        // Restore original font
        g2d.setFont(originalFont);
    }

    /**
     * Renders the selection rectangle during rectangle selection operations.
     *
     * @param g2d Graphics context
     * @param startPoint Rectangle start point
     * @param currentPoint Rectangle current point
     */
    private void renderSelectionRectangle(Graphics2D g2d, Point startPoint, Point currentPoint) {

        // Calculate rectangle bounds
        int x1 = startPoint.x;
        int y1 = startPoint.y;
        int x2 = currentPoint.x;
        int y2 = currentPoint.y;

        int rectX = Math.min(x1, x2);
        int rectY = Math.min(y1, y2);
        int rectWidth = Math.abs(x2 - x1);
        int rectHeight = Math.abs(y2 - y1);

        // Render selection rectangle with semi-transparent fill
        g2d.setColor(SELECTION_FILL_COLOR);
        g2d.fillRect(rectX, rectY, rectWidth, rectHeight);

        // Render dashed border
        g2d.setColor(SELECTION_BORDER_COLOR);
        g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                     10.0f, SELECTION_DASH_PATTERN, 0.0f));
        g2d.drawRect(rectX, rectY, rectWidth, rectHeight);

        // Reset stroke
        g2d.setStroke(new BasicStroke(1.0f));
    }

    /**
     * Renders debug information overlay showing zoom and pan values.
     *
     * @param g2d Graphics context
     * @param width Panel width
     * @param height Panel height
     * @param zoomLevel Current zoom level
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderDebugInfo(Graphics2D g2d, int width, int height,
                                double zoomLevel, double panX, double panY) {

        g2d.setColor(DEBUG_TEXT_COLOR);
        g2d.drawString(String.format("Zoom: %.1f%%", zoomLevel * 100),
                      DEBUG_TEXT_MARGIN, height - 25);
        g2d.drawString(String.format("Pan: (%.0f, %.0f)", panX, panY),
                      DEBUG_TEXT_MARGIN, height - DEBUG_TEXT_MARGIN);
    }

    /**
     * Gets the appropriate gridline color based on the current UI theme.
     *
     * @return Color for gridlines that provides subtle contrast with current background
     */
    private Color getGridlineColor() {
        // Check for custom gridline color from theme
        Color customGridlineColor = UIManager.getColor("MapPanel.gridlineColor");
        if (customGridlineColor != null) {
            return customGridlineColor;
        }

        // Fallback based on theme brightness
        if (isLightTheme()) {
            return new Color(240, 240, 240); // Light gray for light themes
        } else {
            return new Color(80, 80, 80); // Dark gray for dark themes
        }
    }

    /**
     * Determines if the current UI theme is light-based on the panel background color.
     *
     * @return true if light theme, false if dark theme
     */
    private boolean isLightTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return true; // Default to light theme
        }
        // Consider theme light if the sum of RGB values is >= 384 (128 * 3)
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) >= 384;
    }
}