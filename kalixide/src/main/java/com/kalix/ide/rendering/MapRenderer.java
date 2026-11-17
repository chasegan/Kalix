package com.kalix.ide.rendering;

import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import com.kalix.ide.model.ClusterCollapse;
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
 * @version 1.1
 */
public class MapRenderer {

    // Shape renderer for node visualization
    private final NodeShapeRenderer shapeRenderer;

    // Rendering constants (centralized in UIConstants)
    private static final int NODE_SIZE = UIConstants.Map.NODE_SIZE;

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
    private static final BasicStroke SELECTED_LINK_STROKE = new BasicStroke(
            UIConstants.Selection.NODE_SELECTED_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke SELECTED_LINK_DASHED_STROKE = new BasicStroke(
            UIConstants.Selection.NODE_SELECTED_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            10.0f, new float[]{5.0f, 5.0f}, 0.0f);

    // Node border strokes (cached — allocated per node per frame before)
    private static final BasicStroke SELECTED_NODE_STROKE =
            new BasicStroke(UIConstants.Selection.NODE_SELECTED_STROKE_WIDTH);
    private static final BasicStroke UNSELECTED_NODE_STROKE =
            new BasicStroke(UIConstants.Selection.NODE_UNSELECTED_STROKE_WIDTH);
    private static final BasicStroke DEFAULT_STROKE = new BasicStroke(1.0f);
    private static final BasicStroke SELECTION_RECTANGLE_STROKE = new BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            SELECTION_DASH_MITER_LIMIT, SELECTION_DASH_PATTERN, 0.0f);

    // Link-drag styling (dragging a new link from a node's ring)
    private static final Color LINK_DRAG_COLOR = UIConstants.Selection.RECTANGLE_BORDER;
    private static final Color LINK_DRAG_REFUSED_COLOR = UIConstants.Selection.LINK_DRAG_REFUSED;
    private static final BasicStroke LINK_HANDLE_STROKE = new BasicStroke(2.0f);

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
     * @param mouseWorldX Mouse X in world coordinates
     * @param mouseWorldY Mouse Y in world coordinates
     * @param mouseInPanel Whether the mouse is currently over the panel
     * @param labelMode Which text is drawn under each node
     * @param hoveredNodeName Node whose label the mouse changes, per {@code labelMode};
     *                        null when nothing is revealed
     * @param collapse Which clusters are collapsed
     */
    public void renderMap(Graphics2D g2d, int width, int height,
                         double zoomLevel, double panX, double panY,
                         boolean showGridlines, HydrologicalModel model,
                         NodeTheme nodeTheme, Point selectionStart, Point selectionCurrent,
                         double mouseWorldX, double mouseWorldY, boolean mouseInPanel,
                         NodeLabelMode labelMode, String hoveredNodeName, ClusterCollapse collapse) {

        // Enable antialiasing for smoother graphics
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Save original transform
        AffineTransform originalTransform = g2d.getTransform();

        // Apply pan and zoom transformations for world space rendering
        g2d.translate(panX, panY);
        g2d.scale(zoomLevel, zoomLevel);

        // Render world-space elements (grid)
        if (showGridlines) {
            renderGrid(g2d, width, height, zoomLevel, panX, panY);
        }

        // Reset to screen space for screen-space elements
        g2d.setTransform(originalTransform);

        // Render screen-space elements (links first, then nodes, then selection, debug)
        if (model != null) {
            List<MapLayers.Icon> icons = MapLayers.icons(model, collapse);
            renderLinks(g2d, model, collapse, icons, zoomLevel, panX, panY);
            renderNodes(g2d, model, collapse, icons, nodeTheme, zoomLevel, panX, panY,
                        labelMode, hoveredNodeName);
        }

        if (selectionStart != null && selectionCurrent != null) {
            renderSelectionRectangle(g2d, selectionStart, selectionCurrent);
        }

        renderDebugInfo(g2d, width, height, zoomLevel, mouseWorldX, mouseWorldY, mouseInPanel);
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
     * Renders all model links in screen-space coordinates, from centre to centre, as
     * {@link MapLinks} redirects them to the icons of collapsed clusters.
     *
     * @param g2d Graphics context (in screen space)
     * @param model Hydrological model containing links
     * @param collapse Which clusters are collapsed
     * @param icons The icons of the collapsed clusters
     * @param zoomLevel Current zoom level for coordinate transformation
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderLinks(Graphics2D g2d, HydrologicalModel model, ClusterCollapse collapse,
                            List<MapLayers.Icon> icons, double zoomLevel, double panX, double panY) {

        Map<String, MapLayers.Icon> iconByCluster = new HashMap<>();
        for (MapLayers.Icon icon : icons) {
            iconByCluster.put(icon.cluster(), icon);
        }
        // Render each link (color and stroke set individually based on selection state)
        for (MapLinks.Drawn link : MapLinks.of(model.getAllLinks(), model.getClusters(), collapse,
                model::isLinkSelected)) {
            renderSingleLink(g2d, link, model, iconByCluster, zoomLevel, panX, panY);
        }

        // Reset stroke and color for subsequent rendering
        g2d.setStroke(DEFAULT_STROKE);
        g2d.setColor(Color.BLACK);
    }

    /**
     * Renders a single link between two drawn ends: nodes, or icons.
     *
     * @param g2d Graphics context
     * @param link Link to render
     * @param model Model for node lookups
     * @param iconByCluster The icons, by cluster
     * @param zoomLevel Current zoom level
     * @param panX Horizontal pan offset
     * @param panY Vertical pan offset
     */
    private void renderSingleLink(Graphics2D g2d, MapLinks.Drawn link,
                                 HydrologicalModel model, Map<String, MapLayers.Icon> iconByCluster,
                                 double zoomLevel, double panX, double panY) {

        // Where each end is drawn, in map coordinates
        double[] upstream = position(link.upstream(), model, iconByCluster);
        double[] downstream = position(link.downstream(), model, iconByCluster);

        // Skip link if either end is missing
        if (upstream == null || downstream == null) {
            return;
        }

        // Check if link is selected and set appropriate color and stroke
        boolean isSelected = link.selected();
        if (isSelected) {
            g2d.setColor(UIConstants.Selection.NODE_SELECTED_BORDER);
            // Use solid or dashed stroke based on link type, but with selected width
            g2d.setStroke(link.primary() ? SELECTED_LINK_STROKE : SELECTED_LINK_DASHED_STROKE);
        } else {
            g2d.setColor(LINK_COLOR);
            // Use solid stroke for primary links, dashed stroke for alternative links
            g2d.setStroke(link.primary() ? LINK_STROKE : LINK_DASHED_STROKE);
        }

        // Transform the ends' world coordinates to screen coordinates
        double upstreamScreenX = upstream[0] * zoomLevel + panX;
        double upstreamScreenY = upstream[1] * zoomLevel + panY;
        double downstreamScreenX = downstream[0] * zoomLevel + panX;
        double downstreamScreenY = downstream[1] * zoomLevel + panY;

        // Draw the link line
        g2d.drawLine((int) upstreamScreenX, (int) upstreamScreenY,
                    (int) downstreamScreenX, (int) downstreamScreenY);

        // Draw chevron arrow at midpoint
        renderChevronArrow(g2d, upstreamScreenX, upstreamScreenY,
                          downstreamScreenX, downstreamScreenY);
    }

    /** Where a drawn end sits, in map coordinates, or {@code null} if it isn't on the map. */
    private static double[] position(MapLinks.End end, HydrologicalModel model,
                                     Map<String, MapLayers.Icon> iconByCluster) {
        if (end.isIcon()) {
            MapLayers.Icon icon = iconByCluster.get(end.cluster());
            return icon == null ? null : new double[] {icon.x(), icon.y()};
        }
        ModelNode node = model.getNode(end.node());
        return node == null ? null : new double[] {node.getX(), node.getY()};
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
     * @param labelMode Which text is drawn under each node
     * @param hoveredNodeName Node whose label the mouse changes, or null
     */
    private void renderNodes(Graphics2D g2d, HydrologicalModel model, ClusterCollapse collapse,
                            List<MapLayers.Icon> icons, NodeTheme nodeTheme,
                            double zoomLevel, double panX, double panY,
                            NodeLabelMode labelMode, String hoveredNodeName) {

        AffineTransform originalTransform = g2d.getTransform();
        NodeTheme.TextStyle textStyle = nodeTheme.getCurrentTextStyle();
        FontMetrics fm = g2d.getFontMetrics(textStyle.getFont());
        ClusterBox.TextMetrics metrics = textMetrics(fm, textStyle);

        // Layer by layer: a cluster's box, then every shape, then every label, so no shape
        // in a layer covers a label in it (MapLayers says why not all labels last).
        ModelNode hovered = null;
        for (MapLayers.Layer layer : MapLayers.of(model, collapse)) {
            Rectangle2D box = null;
            if (layer.cluster() != null) {
                box = clusterBox(layer, fm, metrics, labelMode, zoomLevel, panX, panY);
                renderClusterBox(g2d, box);
            }
            for (ModelNode node : layer.nodes()) {
                renderNodeShape(g2d, node, model, nodeTheme, zoomLevel, panX, panY, originalTransform);
            }
            for (ModelNode node : layer.nodes()) {
                // The label the mouse is over waits for the end, over everything.
                if (node.getName().equals(hoveredNodeName)) {
                    hovered = node;
                    continue;
                }
                // What the mode draws for this node without the mouse over it
                // (NodeLabelMode owns that rule).
                renderNodeText(g2d, labelMode.labelLines(node, false),
                               node.getX() * zoomLevel + panX, node.getY() * zoomLevel + panY, nodeTheme);
            }
            // The box name is a label, so it goes in the label pass; the hidden mode has
            // none (clusters.md, settled decision 8).
            if (box != null && labelMode.drawsLabels()) {
                renderBoxName(g2d, layer.cluster(), box, fm, metrics, textStyle);
            }
        }

        // The icons of collapsed clusters, over every layer: every icon's cards, then every
        // icon's name. The hidden mode shows none: it shows names on hover, and an icon
        // can't be hovered until icons are hit-tested (V1c).
        for (MapLayers.Icon icon : icons) {
            renderIcon(g2d, icon.count(), icon.x() * zoomLevel + panX, icon.y() * zoomLevel + panY, fm);
        }
        if (labelMode.drawsLabels()) {
            for (MapLayers.Icon icon : icons) {
                renderNodeText(g2d, List.of(icon.cluster()), icon.x() * zoomLevel + panX,
                               icon.y() * zoomLevel + panY + ClusterIcon.LABEL_DROP, nodeTheme);
            }
        }

        // The label the mouse is over, last of all, so nothing covers it while it is read.
        // A label shown only on hover is there for a moment, so being drawn over a box it
        // lies under doesn't matter (MapLayers says why other labels stay in their layer).
        if (hovered != null) {
            renderNodeText(g2d, labelMode.labelLines(hovered, true),
                           hovered.getX() * zoomLevel + panX, hovered.getY() * zoomLevel + panY, nodeTheme);
        }

        // Restore the original transform
        g2d.setTransform(originalTransform);
    }

    /** The label measurements {@link ClusterBox} lays strips out from, for this theme's font. */
    static ClusterBox.TextMetrics textMetrics(FontMetrics fm, NodeTheme.TextStyle textStyle) {
        return new ClusterBox.TextMetrics(fm.getAscent(), fm.getHeight(),
                NODE_SIZE / 2.0 + textStyle.getYOffset());
    }

    /**
     * The box of a cluster's layer, in screen pixels, from what its members show without
     * the mouse over them: hover-only labels don't widen it (clusters.md, "Box bounds").
     */
    static Rectangle2D clusterBox(MapLayers.Layer layer, FontMetrics fm,
                                  ClusterBox.TextMetrics metrics, NodeLabelMode labelMode,
                                  double zoomLevel, double panX, double panY) {
        List<ClusterBox.Member> members = new ArrayList<>(layer.nodes().size());
        for (ModelNode node : layer.nodes()) {
            List<String> lines = labelMode.labelLines(node, false);
            List<Integer> widths = new ArrayList<>(lines.size());
            for (String line : lines) {
                widths.add(fm.stringWidth(line));
            }
            members.add(new ClusterBox.Member(node.getX() * zoomLevel + panX,
                    node.getY() * zoomLevel + panY, widths));
        }
        OptionalInt nameWidth = labelMode.drawsLabels()
                ? OptionalInt.of(fm.stringWidth(layer.cluster())) : OptionalInt.empty();
        return ClusterBox.bounds(members, metrics, nameWidth);
    }

    /** Draws a collapsed cluster's stacked cards, centred on the screen point, with its count. */
    private void renderIcon(Graphics2D g2d, int count, double x, double y, FontMetrics labelFm) {
        double arc = 2 * ClusterIcon.CORNER_RADIUS;
        g2d.setStroke(DEFAULT_STROKE);
        Rectangle2D face = ClusterIcon.face(x, y);
        renderCard(g2d, ClusterIcon.back(x, y), ClusterIcon.BACK, arc);
        renderCard(g2d, face, ClusterIcon.FACE, arc);

        Font originalFont = g2d.getFont();
        g2d.setFont(labelFm.getFont().deriveFont(Font.BOLD));
        FontMetrics fm = g2d.getFontMetrics();
        String text = Integer.toString(count);
        g2d.setColor(ClusterIcon.COUNT);
        g2d.drawString(text, (float) (face.getCenterX() - fm.stringWidth(text) / 2.0),
                (float) (face.getCenterY() + (fm.getAscent() - fm.getDescent()) / 2.0));
        g2d.setFont(originalFont);
    }

    private void renderCard(Graphics2D g2d, Rectangle2D card, Color fill, double arc) {
        RoundRectangle2D shape = new RoundRectangle2D.Double(card.getX(), card.getY(),
                card.getWidth(), card.getHeight(), arc, arc);
        g2d.setColor(fill);
        g2d.fill(shape);
        g2d.setColor(ClusterIcon.BORDER);
        g2d.draw(shape);
    }

    /** Fills and outlines a cluster's box, in the one box colour on every theme. */
    private void renderClusterBox(Graphics2D g2d, Rectangle2D box) {
        double arc = 2 * ClusterBox.CORNER_RADIUS;
        RoundRectangle2D shape = new RoundRectangle2D.Double(box.getX(), box.getY(),
                box.getWidth(), box.getHeight(), arc, arc);
        g2d.setColor(ClusterBox.FILL);
        g2d.fill(shape);
        g2d.setColor(ClusterBox.border(isLightTheme()));
        g2d.setStroke(DEFAULT_STROKE);
        g2d.draw(shape);
    }

    /** Draws a cluster's name at its box's top left, looking like a node label. */
    private void renderBoxName(Graphics2D g2d, String name, Rectangle2D box, FontMetrics fm,
                               ClusterBox.TextMetrics metrics, NodeTheme.TextStyle textStyle) {
        Rectangle strip = ClusterBox.nameStrip(box.getX(), box.getY(), fm.stringWidth(name), metrics);
        Font originalFont = g2d.getFont();
        g2d.setFont(textStyle.getFont());
        g2d.setColor(textStyle.getBackgroundColorWithAlpha());
        g2d.fill(strip);
        g2d.setColor(textStyle.getTextColor());
        g2d.drawString(name, strip.x + ClusterBox.TEXT_PADDING, ClusterBox.nameBaseline(strip, metrics));
        g2d.setFont(originalFont);
    }

    /**
     * Renders a node's shape, with its border and the text inside it; its label is drawn
     * separately, after every shape in its layer.
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
    private void renderNodeShape(Graphics2D g2d, ModelNode node, HydrologicalModel model,
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
        BasicStroke borderStroke = isSelected ? SELECTED_NODE_STROKE : UNSELECTED_NODE_STROKE;

        // Render node shape with border
        shapeRenderer.renderShape(g2d, nodeShape, screenX, screenY, nodeColor, borderColor, borderStroke);

        // Render text inside shape
        shapeRenderer.renderShapeText(g2d, shapeText, screenX, screenY, nodeShape, nodeColor, nodeTheme);

        // Reset stroke for next node
        g2d.setStroke(DEFAULT_STROKE);
    }

    /**
     * Renders a node's label lines, top first, stacked below the node, each on its
     * own background strip, with theme-based styling.
     *
     * @param g2d Graphics context
     * @param lines Lines to draw; nothing is drawn for an empty list
     * @param nodeScreenX Screen X coordinate of node center
     * @param nodeScreenY Screen Y coordinate of node center
     * @param nodeTheme Theme for text styling
     */
    private void renderNodeText(Graphics2D g2d, List<String> lines, double nodeScreenX,
                               double nodeScreenY, NodeTheme nodeTheme) {

        if (lines.isEmpty()) {
            return;
        }

        // Get theme text styling
        NodeTheme.TextStyle textStyle = nodeTheme.getCurrentTextStyle();

        // Apply theme font (cached in the style)
        Font originalFont = g2d.getFont();
        g2d.setFont(textStyle.getFont());

        FontMetrics fm = g2d.getFontMetrics();
        ClusterBox.TextMetrics metrics = textMetrics(fm, textStyle);

        // ClusterBox lays the strips out, so a cluster's box covers exactly what is drawn.
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int textWidth = fm.stringWidth(line);
            double textX = nodeScreenX - (textWidth / 2.0);

            // Render text background
            g2d.setColor(textStyle.getBackgroundColorWithAlpha());
            g2d.fill(ClusterBox.labelStrip(nodeScreenX, nodeScreenY, i, textWidth, metrics));

            // Render text foreground
            g2d.setColor(textStyle.getTextColor());
            g2d.drawString(line, (int) textX, (int) ClusterBox.baseline(nodeScreenY, i, metrics));
        }

        // Restore original font
        g2d.setFont(originalFont);
    }

    /**
     * Renders the ring around a node (centre in screen coordinates) that shows a link can
     * be dragged from it, or that the dragged link will snap to it; in the refusal colour
     * unless {@code valid}.
     */
    public void renderLinkHandle(Graphics2D g2d, double screenX, double screenY, boolean valid) {
        double r = NODE_SIZE / 2.0 + UIConstants.Map.LINK_HANDLE_RING_PX / 2.0;
        g2d.setColor(valid ? LINK_DRAG_COLOR : LINK_DRAG_REFUSED_COLOR);
        g2d.setStroke(LINK_HANDLE_STROKE);
        g2d.draw(new java.awt.geom.Ellipse2D.Double(screenX - r, screenY - r, 2 * r, 2 * r));
        g2d.setStroke(DEFAULT_STROKE);
    }

    /**
     * Renders the link being dragged, in screen coordinates: a dashed line with a flow
     * chevron from the source node to the cursor or snapped target, in the refusal colour
     * unless {@code valid}.
     */
    public void renderLinkDrag(Graphics2D g2d, double fromX, double fromY, double toX, double toY,
                               boolean valid) {
        g2d.setColor(valid ? LINK_DRAG_COLOR : LINK_DRAG_REFUSED_COLOR);
        g2d.setStroke(LINK_DASHED_STROKE);
        g2d.draw(new java.awt.geom.Line2D.Double(fromX, fromY, toX, toY));
        g2d.setStroke(LINK_STROKE);
        renderChevronArrow(g2d, fromX, fromY, toX, toY);
        g2d.setStroke(DEFAULT_STROKE);
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
        g2d.setStroke(SELECTION_RECTANGLE_STROKE);
        g2d.drawRect(rectX, rectY, rectWidth, rectHeight);

        // Reset stroke
        g2d.setStroke(DEFAULT_STROKE);
    }

    /**
     * Renders debug information overlay showing zoom level and mouse hover coordinates.
     *
     * @param g2d Graphics context
     * @param width Panel width
     * @param height Panel height
     * @param zoomLevel Current zoom level
     * @param mouseWorldX Mouse X in world coordinates
     * @param mouseWorldY Mouse Y in world coordinates
     * @param mouseInPanel Whether the mouse is currently over the panel
     */
    private void renderDebugInfo(Graphics2D g2d, int width, int height,
                                double zoomLevel, double mouseWorldX, double mouseWorldY,
                                boolean mouseInPanel) {

        g2d.setColor(DEBUG_TEXT_COLOR);
        g2d.drawString(String.format("Zoom: %.1f%%", zoomLevel * 100),
                      DEBUG_TEXT_MARGIN, height - 25);
        if (mouseInPanel) {
            g2d.drawString(String.format("Loc: (%.0f, %.0f)", mouseWorldX, mouseWorldY),
                          DEBUG_TEXT_MARGIN, height - DEBUG_TEXT_MARGIN);
        } else {
            g2d.drawString("Loc: \u00af\\_(\u30c4)_/\u00af",
                          DEBUG_TEXT_MARGIN, height - DEBUG_TEXT_MARGIN);
        }
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
        return !com.kalix.ide.utils.ThemeUtils.isDark(UIManager.getColor("Panel.background"));
    }
}