package com.kalix.ide.rendering;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.constants.UIConstants;

/**
 * Dedicated renderer class for map visualization in the KalixIDE application.
 *
 * This class handles all rendering operations for the map panel including:
 * - Grid rendering with dynamic viewport calculation
 * - Node visualization with theme-based styling and shapes
 * - Text rendering with proper font handling
 * - Selection rectangle drawing
 * - Debug information overlays
 *
 * The renderer is stateless and receives all necessary rendering context
 * through method parameters, making it thread-safe and easily testable.
 *
 * @author Claude Code Assistant
 * @version 1.1
 */
public class MapRenderer {

    // Shape renderer for node visualization
    private final NodeShapeRenderer shapeRenderer;

    // Rendering constants (centralized in UIConstants)
    private static final int NODE_SIZE = UIConstants.Map.NODE_SIZE;
    private static final int TEXT_BACKGROUND_PADDING = UIConstants.Text.BACKGROUND_PADDING;

    // Selection rectangle styling (centralized in UIConstants)
    private static final Color SELECTION_FILL_COLOR = UIConstants.Selection.RECTANGLE_FILL;
    private static final Color SELECTION_BORDER_COLOR = UIConstants.Selection.RECTANGLE_BORDER;
    private static final float[] SELECTION_DASH_PATTERN = UIConstants.Selection.RECTANGLE_DASH_PATTERN;
    private static final float SELECTION_DASH_MITER_LIMIT = UIConstants.Selection.RECTANGLE_DASH_MITER_LIMIT;

    // Debug info styling
    private static final Color DEBUG_TEXT_COLOR = Color.GRAY;
    private static final int DEBUG_TEXT_MARGIN = UIConstants.Text.DEBUG_MARGIN;

    // Link styling constants
    private static final Color LINK_COLOR = new Color(100, 100, 100); // Dark gray
    private static final float LINK_STROKE_WIDTH = 2.0f;
    private static final BasicStroke LINK_STROKE = new BasicStroke(LINK_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke LINK_DASHED_STROKE = new BasicStroke(LINK_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            10.0f, new float[]{5.0f, 5.0f}, 0.0f);

    // Chevron arrow constants
    private static final double CHEVRON_SIZE = 8.0; // Size of chevron in pixels
    private static final double CHEVRON_ANGLE = Math.PI / 6; // 30 degrees

    /**
     * Creates a new MapRenderer with shape rendering capabilities.
     */
    public MapRenderer() {
        this.shapeRenderer = new NodeShapeRenderer();
    }

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

        // Render screen-space elements (links first, then nodes, then selection, debug)
        if (model != null) {
            renderLinks(g2d, model, zoomLevel, panX, panY);
            renderNodes(g2d, model, nodeTheme, zoomLevel, panX, panY);
        }

        if (selectionStart != null && selectionCurrent != null) {
            renderSelectionRectangle(g2d, selectionStart, selectionCurrent);
        }

        renderDebugInfo(g2d, width, height, zoomLevel, panX, panY);
    }

    /**
     * Calculates adaptive grid size based on zoom level.
     * Uses powers of 2 to keep grid lines visually spaced at ~50 pixels on screen.
     *
     * @param zoomLevel Current zoom level
     * @return Grid spacing in world coordinates (always a power of 2)
     */
    private int calculateAdaptiveGridSize(double zoomLevel) {
        // Target screen spacing: 50 pixels
        double targetScreenSpacing = 50.0;

        // Calculate required world spacing
        double worldSpacing = targetScreenSpacing / zoomLevel;

        // Round to nearest power of 2
        int power = (int) Math.round(Math.log(worldSpacing) / Math.log(2));

        // Cap power to prevent overflow (2^26 = 67,108,864 is safe max)
        power = Math.max(0, Math.min(26, power));

        int gridSize = 1 << power; // 2^power

        return gridSize;
    }

    /**
     * Renders the grid overlay in world coordinates with adaptive spacing.
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

        // Set stroke width to maintain constant screen-space thickness (1 pixel)
        // Since we're in world space, we need to compensate for zoom
        float strokeWidth = (float) (1.0 / zoomLevel);
        g2d.setStroke(new BasicStroke(strokeWidth));

        // Calculate adaptive grid size based on zoom level (powers of 2)
        int adaptiveGridSize = calculateAdaptiveGridSize(zoomLevel);

        // Calculate the visible world bounds using long to prevent overflow
        long viewWidth = (long) (panelWidth / zoomLevel);
        long viewHeight = (long) (panelHeight / zoomLevel);
        long worldLeft = (long) (-panX / zoomLevel);
        long worldTop = (long) (-panY / zoomLevel);
        long worldRight = worldLeft + viewWidth;
        long worldBottom = worldTop + viewHeight;

        // Safety check: Skip rendering if viewport is unreasonably large
        // (would require drawing billions of lines)
        long maxReasonableViewport = 1_000_000_000L; // 1 billion units
        if (viewWidth > maxReasonableViewport || viewHeight > maxReasonableViewport) {
            return; // Skip grid rendering at extreme zoom levels
        }

        // Draw vertical lines - aligned to world grid
        long startX = (worldLeft / adaptiveGridSize) * adaptiveGridSize;  // Snap to grid
        for (long x = startX; x <= worldRight + adaptiveGridSize; x += adaptiveGridSize) {
            g2d.drawLine((int) x, (int) (worldTop - adaptiveGridSize),
                        (int) x, (int) (worldBottom + adaptiveGridSize));
        }

        // Draw horizontal lines - aligned to world grid
        long startY = (worldTop / adaptiveGridSize) * adaptiveGridSize;  // Snap to grid
        for (long y = startY; y <= worldBottom + adaptiveGridSize; y += adaptiveGridSize) {
            g2d.drawLine((int) (worldLeft - adaptiveGridSize), (int) y,
                        (int) (worldRight + adaptiveGridSize), (int) y);
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
     * Renders all model links in screen-space coordinates.
     * Links are drawn as solid lines from upstream to downstream node centers.
     *
     * @param g2d Graphics context (in screen space)
     * @param model Hydrological model containing links
     * @param zoomLevel Current zoom level for coordinate transformation
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderLinks(Graphics2D g2d, HydrologicalModel model,
                            double zoomLevel, double panX, double panY) {

        // Render each link (color and stroke set individually based on selection state)
        for (com.kalix.ide.model.ModelLink link : model.getAllLinks()) {
            renderSingleLink(g2d, link, model, zoomLevel, panX, panY);
        }

        // Reset stroke and color for subsequent rendering
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setColor(Color.BLACK);
    }

    /**
     * Renders a single link between two nodes.
     *
     * @param g2d Graphics context
     * @param link Link to render
     * @param model Model for node lookups
     * @param zoomLevel Current zoom level
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderSingleLink(Graphics2D g2d, com.kalix.ide.model.ModelLink link,
                                 HydrologicalModel model, double zoomLevel,
                                 double panX, double panY) {

        // Get upstream and downstream nodes
        com.kalix.ide.model.ModelNode upstreamNode = model.getNode(link.getUpstreamTerminus());
        com.kalix.ide.model.ModelNode downstreamNode = model.getNode(link.getDownstreamTerminus());

        // Skip link if either node is missing
        if (upstreamNode == null || downstreamNode == null) {
            return;
        }

        // Check if link is selected and set appropriate color and stroke
        boolean isSelected = model.isLinkSelected(link);
        if (isSelected) {
            g2d.setColor(UIConstants.Selection.NODE_SELECTED_BORDER);
            // Use solid or dashed stroke based on link type, but with selected width
            if (link.isPrimary()) {
                g2d.setStroke(new BasicStroke(UIConstants.Selection.NODE_SELECTED_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            } else {
                g2d.setStroke(new BasicStroke(UIConstants.Selection.NODE_SELECTED_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        10.0f, new float[]{5.0f, 5.0f}, 0.0f));
            }
        } else {
            g2d.setColor(LINK_COLOR);
            // Use solid stroke for primary links, dashed stroke for alternative links
            g2d.setStroke(link.isPrimary() ? LINK_STROKE : LINK_DASHED_STROKE);
        }

        // Transform node world coordinates to screen coordinates
        double upstreamScreenX = upstreamNode.getX() * zoomLevel + panX;
        double upstreamScreenY = upstreamNode.getY() * zoomLevel + panY;
        double downstreamScreenX = downstreamNode.getX() * zoomLevel + panX;
        double downstreamScreenY = downstreamNode.getY() * zoomLevel + panY;

        // Draw the link line
        g2d.drawLine((int) upstreamScreenX, (int) upstreamScreenY,
                    (int) downstreamScreenX, (int) downstreamScreenY);

        // Draw chevron arrow at midpoint
        renderChevronArrow(g2d, upstreamScreenX, upstreamScreenY,
                          downstreamScreenX, downstreamScreenY);
    }

    /**
     * Renders a chevron arrow at the midpoint of a link to indicate flow direction.
     *
     * @param g2d Graphics context
     * @param upstreamX X coordinate of upstream node
     * @param upstreamY Y coordinate of upstream node
     * @param downstreamX X coordinate of downstream node
     * @param downstreamY Y coordinate of downstream node
     */
    private void renderChevronArrow(Graphics2D g2d, double upstreamX, double upstreamY,
                                   double downstreamX, double downstreamY) {

        // Calculate midpoint
        double midX = (upstreamX + downstreamX) / 2.0;
        double midY = (upstreamY + downstreamY) / 2.0;

        // Calculate direction vector (from upstream to downstream)
        double deltaX = downstreamX - upstreamX;
        double deltaY = downstreamY - upstreamY;
        double linkLength = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        // Skip chevron for very short links
        if (linkLength < CHEVRON_SIZE * 2) {
            return;
        }

        // Normalize direction vector
        double dirX = deltaX / linkLength;
        double dirY = deltaY / linkLength;

        // Calculate chevron arrow points
        // Left arm of chevron
        double leftArmX = midX - CHEVRON_SIZE * (dirX * Math.cos(CHEVRON_ANGLE) + dirY * Math.sin(CHEVRON_ANGLE));
        double leftArmY = midY - CHEVRON_SIZE * (dirY * Math.cos(CHEVRON_ANGLE) - dirX * Math.sin(CHEVRON_ANGLE));

        // Right arm of chevron
        double rightArmX = midX - CHEVRON_SIZE * (dirX * Math.cos(-CHEVRON_ANGLE) + dirY * Math.sin(-CHEVRON_ANGLE));
        double rightArmY = midY - CHEVRON_SIZE * (dirY * Math.cos(-CHEVRON_ANGLE) - dirX * Math.sin(-CHEVRON_ANGLE));

        // Draw chevron arms
        g2d.drawLine((int) leftArmX, (int) leftArmY, (int) midX, (int) midY);
        g2d.drawLine((int) rightArmX, (int) rightArmY, (int) midX, (int) midY);
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

        // Get node styling from theme
        Color nodeColor = nodeTheme.getColorForNodeType(node.getType());
        NodeTheme.NodeShape nodeShape = nodeTheme.getShapeForNodeType(node.getType());
        String shapeText = nodeTheme.getShapeTextForNodeType(node.getType());

        // Transform node world coordinates to screen coordinates
        double screenX = node.getX() * zoomLevel + panX;
        double screenY = node.getY() * zoomLevel + panY;

        // Ensure we're in screen space for constant size rendering
        g2d.setTransform(originalTransform);

        // Determine selection state and border styling
        boolean isSelected = model.isNodeSelected(node.getName());
        Color borderColor = isSelected ? UIConstants.Selection.NODE_SELECTED_BORDER : UIConstants.Selection.NODE_UNSELECTED_BORDER;
        BasicStroke borderStroke = isSelected ?
            new BasicStroke(UIConstants.Selection.NODE_SELECTED_STROKE_WIDTH) :
            new BasicStroke(UIConstants.Selection.NODE_UNSELECTED_STROKE_WIDTH);

        // Render node shape with border
        shapeRenderer.renderShape(g2d, nodeShape, screenX, screenY, nodeColor, borderColor, borderStroke);

        // Render text inside shape
        shapeRenderer.renderShapeText(g2d, shapeText, screenX, screenY, nodeShape, nodeColor, nodeTheme);

        // Reset stroke for next node
        g2d.setStroke(new BasicStroke(1.0f));

        // Render node name text label below shape (unchanged)
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
                                     SELECTION_DASH_MITER_LIMIT, SELECTION_DASH_PATTERN, 0.0f));
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
            return UIConstants.Theme.LIGHT_GRID_COLOR;
        } else {
            return UIConstants.Theme.DARK_GRID_COLOR;
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
        // Consider theme light if the sum of RGB values exceeds threshold
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) >= UIConstants.Theme.LIGHT_THEME_RGB_THRESHOLD;
    }
}