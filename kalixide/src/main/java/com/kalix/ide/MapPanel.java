package com.kalix.ide;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.interaction.MapClipboardManager;
import com.kalix.ide.interaction.MapContextMenuManager;
import com.kalix.ide.interaction.MapInteractionManager;
import com.kalix.ide.interaction.MapSearchManager;
import com.kalix.ide.interaction.TextCoordinateUpdater;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.themes.unified.UnifiedThemeDefinition;
import com.kalix.ide.rendering.MapRenderer;
import com.kalix.ide.constants.UIConstants;

public class MapPanel extends JPanel implements KeyListener {
    private static final Cursor ROTATE_CURSOR = createRotateCursor();

    private double zoomLevel = 1.0;
    // Use centralized UI constants
    private static final double ZOOM_FACTOR = UIConstants.Zoom.ZOOM_FACTOR;

    // Panning variables
    private double panX = 0.0;
    private double panY = 0.0;
    private Point lastPanPoint = null;
    private boolean isPanning = false;
    
    // Click tracking for node navigation
    private Point clickStartPoint = null;
    private String clickedNodeName = null;

    // Rectangle selection state
    private boolean isRectangleSelecting = false;
    private Point rectangleStartPoint = null;
    private Point rectangleCurrentPoint = null;

    // Mouse hover tracking for coordinate display
    private double mouseWorldX = 0;
    private double mouseWorldY = 0;
    private boolean mouseInPanel = false;
    
    // Node rendering constants (centralized in UIConstants)
    private static final int NODE_SIZE = UIConstants.Map.NODE_SIZE;

    // Model integration
    private HydrologicalModel model = null;
    private final NodeTheme nodeTheme = new NodeTheme();

    // Interaction management
    private MapInteractionManager interactionManager;
    private MapContextMenuManager contextMenuManager;
    private MapClipboardManager clipboardManager;
    private MapSearchManager mapSearchManager;
    private EnhancedTextEditor textEditor;

    // Rendering
    private final MapRenderer mapRenderer = new MapRenderer();

    // Display settings
    private boolean showGridlines = true;

    // Theme management (optional - for enhanced unified theme support)
    private com.kalix.ide.managers.ThemeManager themeManager;

    public MapPanel() {
        updateThemeColors();

        // Enable keyboard focus for delete key handling
        setFocusable(true);
        addKeyListener(this);

        setupMouseListeners();
    }

    /**
     * Creates a custom rotation cursor: a circular arc with an arrowhead.
     */
    private static Cursor createRotateCursor() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int cx = size / 2;
        int cy = size / 2;
        int radius = 8;

        Arc2D arc = new Arc2D.Double(
            cx - radius, cy - radius, radius * 2, radius * 2,
            -20, 300, Arc2D.OPEN
        );

        // Inward tick endpoints at the end of the arc (at -20 degrees)
        double endAngle = Math.toRadians(-20);
        int outerX = (int) Math.round(cx + radius * Math.cos(endAngle));
        int outerY = (int) Math.round(cy - radius * Math.sin(endAngle));
        int innerX = (int) Math.round(cx + radius * 0.3 * Math.cos(endAngle));
        int innerY = (int) Math.round(cy - radius * 0.3 * Math.sin(endAngle));

        // First pass: white outline (thicker)
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arc);
        g.drawLine(outerX, outerY, innerX, innerY);

        // Second pass: black foreground (thinner)
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arc);
        g.drawLine(outerX, outerY, innerX, innerY);

        g.dispose();

        Toolkit toolkit = Toolkit.getDefaultToolkit();
        return toolkit.createCustomCursor(img, new Point(cx, cy), "rotate");
    }

    private void setupMouseListeners() {
        MouseAdapter panningHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Right-click: show context menu
                if (SwingUtilities.isRightMouseButton(e)) {
                    requestFocusInWindow();
                    if (contextMenuManager != null) {
                        contextMenuManager.showContextMenu(e.getPoint(), e);
                    }
                    return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                    // Request focus for keyboard events (delete key)
                    requestFocusInWindow();
                    
                    // Check if clicking on a node first
                    String nodeAtPoint = getNodeAtPoint(e.getPoint());
                    
                    // Store click information for potential navigation
                    clickStartPoint = new Point(e.getPoint());
                    clickedNodeName = nodeAtPoint;
                    
                    // Check for Ctrl+click rotation start (anywhere on the map)
                    boolean isCtrlDown = e.isControlDown() || e.isMetaDown();
                    if (isCtrlDown && interactionManager != null && interactionManager.canStartRotation()) {
                        // Ctrl held with multiple nodes selected — start rotation
                        interactionManager.startDrag(e.getPoint(), true);
                        setCursor(ROTATE_CURSOR);
                    } else if (nodeAtPoint != null) {
                        // Check if clicking on an already selected node
                        boolean nodeWasSelected = model.isNodeSelected(nodeAtPoint);

                        if (nodeWasSelected && !e.isShiftDown()) {
                            // Clicking on already selected node without Shift - preserve selection
                            // Don't start drag here - wait for mouseDragged event
                        } else {
                            // Clicking on unselected node, or Shift+clicking - handle selection normally
                            handleNodeSelection(nodeAtPoint, e.isShiftDown());
                            // Don't start drag here - wait for mouseDragged event
                        }

                        // Navigate to the node definition in text editor
                        if (interactionManager != null) {
                            interactionManager.handleNodeClick(nodeAtPoint);
                        }
                    } else {
                        // Not clicking on a node - check for links
                        com.kalix.ide.model.ModelLink linkAtPoint = getLinkAtPoint(e.getPoint());

                        if (linkAtPoint != null) {
                            // Clicking on a link - handle link selection
                            handleLinkSelection(linkAtPoint, e.isShiftDown());
                        } else {
                            // Not clicking on node or link - start rectangle selection if Shift held, otherwise clear selection and start panning
                            if (e.isShiftDown()) {
                                // Start rectangle selection
                                isRectangleSelecting = true;
                                rectangleStartPoint = new Point(e.getPoint());
                                rectangleCurrentPoint = new Point(e.getPoint());
                                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                            } else {
                                // Clear selection and start panning
                                if (model != null) {
                                    model.clearSelection();
                                }
                                lastPanPoint = e.getPoint();
                                isPanning = true;
                                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                            }
                        }
                    }
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    // End dragging if active
                    if (interactionManager != null && interactionManager.isDragging()) {
                        interactionManager.endDrag(e.getPoint());
                    }
                    
                    // Handle rectangle selection completion
                    if (isRectangleSelecting) {
                        completeRectangleSelection();
                        isRectangleSelecting = false;
                        rectangleStartPoint = null;
                        rectangleCurrentPoint = null;
                    }
                    
                    isPanning = false;
                    lastPanPoint = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                    
                    // Clear click tracking
                    clickStartPoint = null;
                    clickedNodeName = null;
                }
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                mouseInPanel = false;
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouseWorldX = (e.getX() - panX) / zoomLevel;
                mouseWorldY = (e.getY() - panY) / zoomLevel;
                mouseInPanel = true;

                // Show rotation cursor when Ctrl is held and multiple nodes are selected
                boolean isCtrlDown = e.isControlDown() || e.isMetaDown();
                if (isCtrlDown && interactionManager != null && interactionManager.canStartRotation()) {
                    setCursor(ROTATE_CURSOR);
                } else {
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }

                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // Update hover coordinates during drag too
                mouseWorldX = (e.getX() - panX) / zoomLevel;
                mouseWorldY = (e.getY() - panY) / zoomLevel;
                mouseInPanel = true;

                // Check if we should start node dragging
                if (interactionManager != null && !interactionManager.isDragging() && 
                    clickedNodeName != null && interactionManager.canStartDrag(clickStartPoint)) {
                    // Start drag operation now that we know it's actually a drag
                    interactionManager.startDrag(clickStartPoint);
                }
                
                // Handle node dragging
                if (interactionManager != null && interactionManager.isDragging()) {
                    interactionManager.updateDrag(e.getPoint());
                    repaint();
                } else if (isRectangleSelecting && rectangleStartPoint != null) {
                    // Update rectangle selection
                    rectangleCurrentPoint = new Point(e.getPoint());
                    repaint();
                } else if (isPanning && lastPanPoint != null) {
                    // Handle map panning
                    Point currentPoint = e.getPoint();
                    double deltaX = currentPoint.x - lastPanPoint.x;
                    double deltaY = currentPoint.y - lastPanPoint.y;
                    
                    panX += deltaX;
                    panY += deltaY;
                    
                    lastPanPoint = currentPoint;
                    repaint();
                }
            }
        };
        
        addMouseListener(panningHandler);
        addMouseMotionListener(panningHandler);
        
        // Mouse wheel zoom handler
        addMouseWheelListener(this::handleMouseWheelZoom);
    }
    
    /**
     * Handles mouse wheel zoom events.
     * Zooms in/out while keeping the point under the cursor fixed.
     */
    private void handleMouseWheelZoom(MouseWheelEvent e) {
        // Get mouse position in screen coordinates
        int mouseX = e.getX();
        int mouseY = e.getY();
        
        // Convert mouse position to world coordinates before zoom
        double worldX = (mouseX - panX) / zoomLevel;
        double worldY = (mouseY - panY) / zoomLevel;
        
        // Calculate new zoom level
        double zoomChange = Math.pow(ZOOM_FACTOR, -e.getWheelRotation());
        zoomLevel = zoomLevel * zoomChange;

        // Adjust pan so the world point under the mouse stays at the same screen position
        panX = mouseX - worldX * zoomLevel;
        panY = mouseY - worldY * zoomLevel;

        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();


        // Delegate all rendering to MapRenderer
        Point selectionStart = isRectangleSelecting ? rectangleStartPoint : null;
        Point selectionCurrent = isRectangleSelecting ? rectangleCurrentPoint : null;

        mapRenderer.renderMap(g2d, getWidth(), getHeight(), zoomLevel, panX, panY,
                             showGridlines, model, nodeTheme, selectionStart, selectionCurrent,
                             mouseWorldX, mouseWorldY, mouseInPanel);

        g2d.dispose();
    }

    
    /**
     * Sets the node color theme and triggers a repaint.
     * @param theme The new theme to use
     */
    public void setNodeTheme(NodeTheme.Theme theme) {
        nodeTheme.setTheme(theme);
        repaint();
    }
    
    /**
     * Gets the current node theme.
     * @return The current node theme
     */
    public NodeTheme.Theme getCurrentNodeTheme() {
        return nodeTheme.getCurrentTheme();
    }
    
    /**
     * Sets the theme manager for enhanced unified theme support.
     * This is optional - MapPanel will continue to work without it.
     *
     * @param themeManager The theme manager instance
     */
    public void setThemeManager(com.kalix.ide.managers.ThemeManager themeManager) {
        this.themeManager = themeManager;
    }

    /**
     * Updates the panel colors based on the current UI theme.
     * This method should be called when the theme changes.
     * Now supports enhanced unified theme integration.
     */
    public void updateThemeColors() {
        // Try unified theme system first if available
        if (themeManager != null) {
            UnifiedThemeDefinition unifiedTheme = themeManager.getCurrentUnifiedTheme();
            if (unifiedTheme != null) {
                // Use unified theme background color
                Color themeBackground = unifiedTheme.getColorPalette().getBackground();
                setBackground(themeBackground);
                repaint();
                return;
            }
        }

        // Fallback to existing theme logic
        // First check for custom MapPanel background color
        Color customMapBg = UIManager.getColor("MapPanel.background");
        if (customMapBg != null) {
            setBackground(customMapBg);
            repaint();
            return;
        }

        // Fallback to original logic
        Color bgColor = UIManager.getColor("Panel.background");

        // For light themes, keep the original white background
        if (isLightTheme()) {
            setBackground(Color.WHITE);
        } else {
            // For dark themes, use the theme's panel background color
            if (bgColor != null) {
                setBackground(bgColor);
            } else {
                // Fallback to white if theme color not available
                setBackground(Color.WHITE);
            }
        }
        repaint();
    }
    
    /**
     * Determines if the current theme is light based on the background color.
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
    
    
    /**
     * Sets whether gridlines should be shown on the map.
     * @param showGridlines true to show gridlines, false to hide them
     */
    public void setShowGridlines(boolean showGridlines) {
        this.showGridlines = showGridlines;
        repaint();
    }
    
    /**
     * Gets whether gridlines are currently shown on the map.
     * @return true if gridlines are shown, false otherwise
     */
    public boolean isShowGridlines() {
        return showGridlines;
    }
    
    public void setModel(HydrologicalModel model) {
        // Remove listener from old model if it exists
        if (this.model != null) {
            // We'll add this when we implement the listener
        }
        
        this.model = model;

        // Initialize interaction manager and context menu manager
        if (this.model != null) {
            this.model.addChangeListener(event -> repaint());
            this.interactionManager = new MapInteractionManager(this, this.model);
            this.contextMenuManager = new MapContextMenuManager(this, this.interactionManager, this.model);
            this.mapSearchManager = new MapSearchManager(this, this.model);
            this.contextMenuManager.setMapSearchManager(this.mapSearchManager);

            // Auto-fit the model content to the current component size
            if (getWidth() > 0 && getHeight() > 0) {
                zoomToFit();
            }
        } else {
            this.interactionManager = null;
            this.contextMenuManager = null;
            this.mapSearchManager = null;
        }
        
        repaint();
    }

    public void zoomIn() {
        zoomLevel *= ZOOM_FACTOR;
        repaint();
    }

    public void zoomOut() {
        zoomLevel /= ZOOM_FACTOR;
        repaint();
    }

    public void resetZoom() {
        zoomLevel = 1.0;
        repaint();
    }
    
    public void zoomToFit() {
        if (model == null || model.getAllNodes().isEmpty()) {
            return; // No nodes to fit
        }

        // Calculate bounding box of all nodes
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (ModelNode node : model.getAllNodes()) {
            minX = Math.min(minX, node.getX());
            minY = Math.min(minY, node.getY());
            maxX = Math.max(maxX, node.getX());
            maxY = Math.max(maxY, node.getY());
        }

        // Calculate center point
        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;

        // Calculate required dimensions with buffer
        double nodeSpanX = maxX - minX;
        double nodeSpanY = maxY - minY;

        // Add buffer (5% on each side = 10% total)
        double bufferFactor = 0.1;
        double bufferedSpanX = nodeSpanX * (1.0 + bufferFactor);
        double bufferedSpanY = nodeSpanY * (1.0 + bufferFactor);

        // Handle case where all nodes are at the same location
        if (bufferedSpanX == 0) bufferedSpanX = 200; // Default span
        if (bufferedSpanY == 0) bufferedSpanY = 200; // Default span

        // Calculate zoom level to fit the content
        double scaleX = getWidth() / bufferedSpanX;
        double scaleY = getHeight() / bufferedSpanY;
        double newZoom = Math.min(scaleX, scaleY);

        // Calculate pan to center the content
        double newPanX = getWidth() / 2.0 - centerX * newZoom;
        double newPanY = getHeight() / 2.0 - centerY * newZoom;

        // Apply the new view settings
        zoomLevel = newZoom;
        panX = newPanX;
        panY = newPanY;

        repaint();
    }

    /**
     * Selects a node and centers the view on it, without triggering the
     * map→editor scroll callback. Used by the editor's "Show on Map" action.
     *
     * @param nodeName The name of the node to select and center on
     */
    public void selectNodeFromEditor(String nodeName) {
        if (model == null || nodeName == null) {
            return;
        }

        ModelNode node = model.getNode(nodeName);
        if (node == null) {
            return;
        }

        // Select the node (replace selection)
        model.selectNode(nodeName, false);

        // Center the view on the node
        panX = getWidth() / 2.0 - node.getX() * zoomLevel;
        panY = getHeight() / 2.0 - node.getY() * zoomLevel;

        repaint();
    }

    public void clearModel() {
        repaint();
    }


    
    // Hit testing for node interaction
    
    /**
     * Find the node at the given screen coordinates.
     * @param screenPoint Screen coordinates (mouse position)
     * @return Node name if found, null if no node at that position
     */
    public String getNodeAtPoint(Point screenPoint) {
        if (model == null) {
            return null;
        }
        
        // Check each node to see if the screen point is within its bounds
        for (ModelNode node : model.getAllNodes()) {
            // Transform node world coordinates to screen coordinates
            double screenX = node.getX() * zoomLevel + panX;
            double screenY = node.getY() * zoomLevel + panY;
            
            // Calculate distance from screen point to node center
            double dx = screenPoint.x - screenX;
            double dy = screenPoint.y - screenY;
            double distance = Math.sqrt(dx * dx + dy * dy);
            
            // Check if within node radius (NODE_SIZE / 2)
            if (distance <= NODE_SIZE / 2.0) {
                return node.getName();
            }
        }
        
        return null;
    }

    /**
     * Find the link at the given screen coordinates.
     * @param screenPoint Screen coordinates (mouse position)
     * @return ModelLink if found, null if no link at that position
     */
    public com.kalix.ide.model.ModelLink getLinkAtPoint(Point screenPoint) {
        if (model == null) {
            return null;
        }

        final double LINK_HIT_TOLERANCE = 8.0; // pixels

        // Check each link to see if the screen point is near the line
        for (com.kalix.ide.model.ModelLink link : model.getAllLinks()) {
            // Get upstream and downstream nodes
            com.kalix.ide.model.ModelNode upstreamNode = model.getNode(link.getUpstreamTerminus());
            com.kalix.ide.model.ModelNode downstreamNode = model.getNode(link.getDownstreamTerminus());

            // Skip link if either node is missing
            if (upstreamNode == null || downstreamNode == null) {
                continue;
            }

            // Transform node world coordinates to screen coordinates
            double upstreamScreenX = upstreamNode.getX() * zoomLevel + panX;
            double upstreamScreenY = upstreamNode.getY() * zoomLevel + panY;
            double downstreamScreenX = downstreamNode.getX() * zoomLevel + panX;
            double downstreamScreenY = downstreamNode.getY() * zoomLevel + panY;

            // Calculate distance from point to line segment
            double distance = pointToLineDistance(screenPoint.x, screenPoint.y,
                                                 upstreamScreenX, upstreamScreenY,
                                                 downstreamScreenX, downstreamScreenY);

            if (distance <= LINK_HIT_TOLERANCE) {
                return link;
            }
        }

        return null;
    }

    /**
     * Calculate the shortest distance from a point to a line segment.
     * @param px Point X coordinate
     * @param py Point Y coordinate
     * @param x1 Line start X coordinate
     * @param y1 Line start Y coordinate
     * @param x2 Line end X coordinate
     * @param y2 Line end Y coordinate
     * @return Distance from point to line segment
     */
    private double pointToLineDistance(double px, double py, double x1, double y1, double x2, double y2) {
        // Vector from line start to line end
        double lineLength = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));

        // Handle degenerate case where line has zero length
        if (lineLength == 0) {
            return Math.sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1));
        }

        // Calculate the t parameter that represents the projection of point onto the line
        double t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / (lineLength * lineLength);

        // Clamp t to [0,1] to stay within the line segment
        t = Math.max(0, Math.min(1, t));

        // Calculate the closest point on the line segment
        double closestX = x1 + t * (x2 - x1);
        double closestY = y1 + t * (y2 - y1);

        // Return distance from point to closest point on line segment
        return Math.sqrt((px - closestX) * (px - closestX) + (py - closestY) * (py - closestY));
    }

    /**
     * Handle node selection logic.
     * @param nodeName Name of the node to select
     * @param addToSelection If true (Shift+click), add to selection; if false, replace selection
     */
    private void handleNodeSelection(String nodeName, boolean addToSelection) {
        if (model == null) {
            return;
        }
        
        if (addToSelection) {
            // Shift+click: toggle selection of this node
            if (model.isNodeSelected(nodeName)) {
                model.deselectNode(nodeName);
            } else {
                model.selectNode(nodeName, true); // Add to selection
            }
        } else {
            // Regular click: select only this node
            model.selectNode(nodeName, false); // Replace selection
        }
    }

    /**
     * Handle link selection logic.
     * @param link Link to select
     * @param addToSelection If true (Shift+click), add to selection; if false, replace selection
     */
    private void handleLinkSelection(com.kalix.ide.model.ModelLink link, boolean addToSelection) {
        if (model == null || link == null) {
            return;
        }

        if (addToSelection) {
            // Shift+click: toggle selection of this link
            if (model.isLinkSelected(link)) {
                model.deselectLink(link);
            } else {
                model.selectLink(link, true); // Add to selection
            }
        } else {
            // Regular click: select only this link
            model.selectLink(link, false); // Replace selection
        }
    }

    // Getters for MapInteractionManager
    
    public double getZoomLevel() {
        return zoomLevel;
    }
    
    public double getPanX() {
        return panX;
    }
    
    public double getPanY() {
        return panY;
    }
    
    /**
     * Set up text synchronization with the text editor.
     * This enables bidirectional sync between map dragging and text coordinate updates.
     * @param textEditor The text editor to synchronize with
     */
    public void setupTextSynchronization(EnhancedTextEditor textEditor) {
        this.textEditor = textEditor;
        if (interactionManager != null && textEditor != null) {
            TextCoordinateUpdater textUpdater = new TextCoordinateUpdater(textEditor);
            interactionManager.setTextUpdater(textUpdater);

            // Create clipboard manager (needs model, textEditor, and textUpdater)
            if (model != null) {
                clipboardManager = new MapClipboardManager(model, textEditor, textUpdater);
                if (contextMenuManager != null) {
                    contextMenuManager.setClipboardManager(clipboardManager);
                    contextMenuManager.setTextEditor(textEditor);
                }
            }
        }
    }
    
    // KeyListener implementation for delete key handling
    
    @Override
    public void keyPressed(KeyEvent e) {
        // Check for modifier key (Ctrl on Windows/Linux, Cmd on macOS)
        boolean isModifierDown = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0 ||
                                 (e.getModifiersEx() & InputEvent.META_DOWN_MASK) != 0;

        // Update cursor for rotation preview when Ctrl is pressed
        if ((e.getKeyCode() == KeyEvent.VK_CONTROL || e.getKeyCode() == KeyEvent.VK_META)
                && interactionManager != null && interactionManager.canStartRotation()
                && !interactionManager.isDragging()) {
            setCursor(ROTATE_CURSOR);
        }

        // Find Node: Ctrl+F / Cmd+F
        if (isModifierDown && e.getKeyCode() == KeyEvent.VK_F) {
            if (mapSearchManager != null) {
                mapSearchManager.showFindDialog();
            }
            return;
        }

        // Undo: Ctrl+Z / Cmd+Z
        if (isModifierDown && e.getKeyCode() == KeyEvent.VK_Z) {
            if (textEditor != null && textEditor.canUndo()) {
                textEditor.undo();
            }
            return;
        }

        // Redo: Ctrl+Y / Cmd+Y
        if (isModifierDown && e.getKeyCode() == KeyEvent.VK_Y) {
            if (textEditor != null && textEditor.canRedo()) {
                textEditor.redo();
            }
            return;
        }

        // Cut: Ctrl+X / Cmd+X
        if (isModifierDown && e.getKeyCode() == KeyEvent.VK_X) {
            if (clipboardManager != null && clipboardManager.canCutOrCopy()) {
                clipboardManager.cut();
                repaint();
            }
            return;
        }

        // Copy: Ctrl+C / Cmd+C
        if (isModifierDown && e.getKeyCode() == KeyEvent.VK_C) {
            if (clipboardManager != null && clipboardManager.canCutOrCopy()) {
                clipboardManager.copy();
            }
            return;
        }

        // Paste: Ctrl+V / Cmd+V (paste at center of viewport)
        if (isModifierDown && e.getKeyCode() == KeyEvent.VK_V) {
            if (clipboardManager != null && clipboardManager.hasClipboardContent()) {
                // Calculate center of viewport in world coordinates
                double centerX = (getWidth() / 2.0 - panX) / zoomLevel;
                double centerY = (getHeight() / 2.0 - panY) / zoomLevel;
                clipboardManager.pasteAtMapLocation(centerX, centerY);
                repaint();
            }
            return;
        }

        // Delete: Delete or Backspace key
        if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
            if (interactionManager != null && model != null &&
                (model.getSelectedNodeCount() > 0 || model.getSelectedLinkCount() > 0)) {
                interactionManager.deleteSelectedElements();
                repaint();
            }
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) {
        // Reset cursor when Ctrl is released (rotation preview ends)
        if ((e.getKeyCode() == KeyEvent.VK_CONTROL || e.getKeyCode() == KeyEvent.VK_META)
                && interactionManager != null && !interactionManager.isDragging()) {
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
    }
    
    @Override
    public void keyTyped(KeyEvent e) {
        // Not used but required by KeyListener interface
    }
    
    
    /**
     * Completes the rectangle selection by selecting all nodes and links within the rectangle.
     */
    private void completeRectangleSelection() {
        if (model == null || rectangleStartPoint == null || rectangleCurrentPoint == null) {
            return;
        }

        // Calculate rectangle bounds in screen coordinates
        int x1 = rectangleStartPoint.x;
        int y1 = rectangleStartPoint.y;
        int x2 = rectangleCurrentPoint.x;
        int y2 = rectangleCurrentPoint.y;

        int rectX = Math.min(x1, x2);
        int rectY = Math.min(y1, y2);
        int rectWidth = Math.abs(x2 - x1);
        int rectHeight = Math.abs(y2 - y1);

        // Only proceed if rectangle has meaningful size
        if (rectWidth < 5 && rectHeight < 5) {
            return; // Too small to be a meaningful selection
        }

        // Find all nodes within the rectangle
        for (ModelNode node : model.getAllNodes()) {
            // Transform node world coordinates to screen coordinates
            double screenX = node.getX() * zoomLevel + panX;
            double screenY = node.getY() * zoomLevel + panY;

            // Check if node center is within the rectangle
            if (screenX >= rectX && screenX <= rectX + rectWidth &&
                screenY >= rectY && screenY <= rectY + rectHeight) {
                // Add to selection
                model.selectNode(node.getName(), true); // Add to selection
            }
        }

        // Find all links within the rectangle (check if link midpoint or any part intersects)
        for (com.kalix.ide.model.ModelLink link : model.getAllLinks()) {
            // Get upstream and downstream nodes
            com.kalix.ide.model.ModelNode upstreamNode = model.getNode(link.getUpstreamTerminus());
            com.kalix.ide.model.ModelNode downstreamNode = model.getNode(link.getDownstreamTerminus());

            // Skip link if either node is missing
            if (upstreamNode == null || downstreamNode == null) {
                continue;
            }

            // Transform node world coordinates to screen coordinates
            double upstreamScreenX = upstreamNode.getX() * zoomLevel + panX;
            double upstreamScreenY = upstreamNode.getY() * zoomLevel + panY;
            double downstreamScreenX = downstreamNode.getX() * zoomLevel + panX;
            double downstreamScreenY = downstreamNode.getY() * zoomLevel + panY;

            // Check if link intersects with the rectangle
            if (lineIntersectsRectangle(upstreamScreenX, upstreamScreenY,
                                     downstreamScreenX, downstreamScreenY,
                                     rectX, rectY, rectWidth, rectHeight)) {
                // Add to selection
                model.selectLink(link, true); // Add to selection
            }
        }
    }

    /**
     * Check if a line segment intersects with a rectangle.
     * @param x1 Line start X
     * @param y1 Line start Y
     * @param x2 Line end X
     * @param y2 Line end Y
     * @param rectX Rectangle X
     * @param rectY Rectangle Y
     * @param rectWidth Rectangle width
     * @param rectHeight Rectangle height
     * @return true if line intersects rectangle
     */
    private boolean lineIntersectsRectangle(double x1, double y1, double x2, double y2,
                                          int rectX, int rectY, int rectWidth, int rectHeight) {
        // Check if either endpoint is inside the rectangle
        if ((x1 >= rectX && x1 <= rectX + rectWidth && y1 >= rectY && y1 <= rectY + rectHeight) ||
            (x2 >= rectX && x2 <= rectX + rectWidth && y2 >= rectY && y2 <= rectY + rectHeight)) {
            return true;
        }

        // Check if line intersects any of the rectangle edges
        // Top edge
        if (lineSegmentsIntersect(x1, y1, x2, y2, rectX, rectY, rectX + rectWidth, rectY)) {
            return true;
        }
        // Bottom edge
        if (lineSegmentsIntersect(x1, y1, x2, y2, rectX, rectY + rectHeight, rectX + rectWidth, rectY + rectHeight)) {
            return true;
        }
        // Left edge
        if (lineSegmentsIntersect(x1, y1, x2, y2, rectX, rectY, rectX, rectY + rectHeight)) {
            return true;
        }
        // Right edge
        return lineSegmentsIntersect(x1, y1, x2, y2, rectX + rectWidth, rectY, rectX + rectWidth, rectY + rectHeight);
    }

    /**
     * Check if two line segments intersect.
     * @param x1 First line start X
     * @param y1 First line start Y
     * @param x2 First line end X
     * @param y2 First line end Y
     * @param x3 Second line start X
     * @param y3 Second line start Y
     * @param x4 Second line end X
     * @param y4 Second line end Y
     * @return true if segments intersect
     */
    private boolean lineSegmentsIntersect(double x1, double y1, double x2, double y2,
                                        double x3, double y3, double x4, double y4) {
        double denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(denom) < 1e-10) {
            return false; // Lines are parallel
        }

        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
        double u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom;

        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
    }
}