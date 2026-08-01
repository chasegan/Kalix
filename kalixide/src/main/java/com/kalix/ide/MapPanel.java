package com.kalix.ide;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
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
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
import com.kalix.ide.rendering.MapRenderer;
import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.constants.UIConstants;

public class MapPanel extends JPanel {

    /**
     * The rotation cursor, drawn once on first use.
     *
     * <p>Deliberately not a {@code static final} initialiser. Building it renders an
     * antialiased image and asks the toolkit for a custom cursor — work that ran at
     * class-load for every user whether or not they ever rotated, and, worse, could throw
     * during static initialisation. An {@code ExceptionInInitializerError} there does not
     * cost a cursor; it makes {@code MapPanel} permanently unloadable for the life of the
     * JVM, taking the whole map with it. Deferred, the same failure would cost only the
     * cursor.
     *
     * <p>Unsynchronised on purpose: every read is from a mouse handler, so this is
     * EDT-confined. Double-checked locking here would be cargo cult.
     */
    private static Cursor rotateCursor;

    private double zoomLevel = 1.0;
    // Use centralized UI constants
    private static final double ZOOM_FACTOR = UIConstants.Zoom.ZOOM_FACTOR;

    // Panning variables
    private double panX = 0.0;
    private double panY = 0.0;

    // True when a zoom-to-fit was requested before the panel had a real size
    // (e.g. an inactive tab restored at startup). The fit is completed the first
    // time the panel is laid out with non-zero dimensions; see componentResized below.
    private boolean pendingZoomToFit = false;
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
    private final HydrologicalModel model;
    private final NodeTheme nodeTheme = new NodeTheme();

    // Interaction management (constructor-wired; the panel is per-document)
    private final MapInteractionManager interactionManager;
    private final MapContextMenuManager contextMenuManager;
    private final MapClipboardManager clipboardManager;
    private final MapSearchManager mapSearchManager;
    private final EnhancedTextEditor textEditor;

    // Rendering
    private final MapRenderer mapRenderer = new MapRenderer();

    // Display settings
    private boolean showGridlines = true;
    private boolean showLabels = true;

    /**
     * Node whose label is shown transiently because the mouse is over it. Only ever
     * set while {@link #showLabels} is false — with labels on there is nothing to
     * reveal, so the hover hit-test is skipped entirely rather than computed and
     * discarded (mouseMoved fires continuously; getNodeAtPoint is a linear scan).
     */
    private String hoveredNodeName = null;

    // Theme management (optional - for enhanced unified theme support)

    /**
     * Creates a map panel permanently bound to one document's model and editor.
     * All collaborators (interaction, clipboard, context menu, search, text sync)
     * are wired here, symmetrically and exactly once — there is no re-wiring
     * entry point to call in the wrong order, and no stale model listener to leak.
     *
     * @param model      the document's data model (never null)
     * @param textEditor the document's editor, for bidirectional text sync (never null)
     */
    public MapPanel(HydrologicalModel model, EnhancedTextEditor textEditor) {
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.textEditor = java.util.Objects.requireNonNull(textEditor, "textEditor");

        updateThemeColors();

        // Enable keyboard focus for delete key handling
        setFocusable(true);

        setupKeyBindings();
        setupMouseListeners();

        // Repaint whenever the model changes. The panel lives exactly as long as its
        // model (both owned by the same KalixDocument), so this listener never leaks.
        model.addChangeListener(event -> repaint());

        TextCoordinateUpdater textUpdater = new TextCoordinateUpdater(textEditor);
        this.interactionManager = new MapInteractionManager(this, model, textUpdater);
        this.clipboardManager = new MapClipboardManager(model, textEditor, textUpdater);
        this.mapSearchManager = new MapSearchManager(this, model);
        this.contextMenuManager = new MapContextMenuManager(this, interactionManager, model);
        this.contextMenuManager.setMapSearchManager(mapSearchManager);
        this.contextMenuManager.setClipboardManager(clipboardManager);
        this.contextMenuManager.setTextEditor(textEditor);
    }

    /**
     * Completes a deferred zoom-to-fit the moment the layout manager gives this panel a
     * real size. Doing it here (synchronously during validation) rather than via a
     * {@code componentResized} listener means the fit is applied before the panel's first
     * paint in that cycle, avoiding a visible "draw then re-zoom" flicker for tabs whose
     * map was never sized when their model loaded (e.g. inactive tabs restored at startup).
     */
    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        if (pendingZoomToFit && width > 0 && height > 0) {
            zoomToFit();
        }
    }

    /** The rotation cursor, built on first use. EDT-confined; see the field. */
    private static Cursor rotateCursor() {
        if (rotateCursor == null) {
            rotateCursor = createRotateCursor();
        }
        return rotateCursor;
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
                // Right-click: show context menu.
                //
                // Deliberately isRightMouseButton and not the conventional isPopupTrigger:
                // on macOS a Ctrl+left-click is a popup trigger, and Ctrl+left-click is the
                // gesture that starts a rotation (see below). Honouring isPopupTrigger would
                // open this menu instead of rotating, costing the gesture entirely. The
                // trade is that macOS users reach this menu by right-click only.
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
                        setCursor(rotateCursor());
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

                        // Navigation to the node's definition happens on mouseReleased,
                        // once we know this was a click and not the start of a drag —
                        // navigating here moved the editor caret on every drag.
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
                    boolean wasDragging = interactionManager != null && interactionManager.isDragging();
                    if (wasDragging) {
                        interactionManager.endDrag(e.getPoint());
                    }

                    // Click (no drag) on a node: navigate to its definition in the editor
                    if (!wasDragging && clickedNodeName != null && interactionManager != null) {
                        interactionManager.handleNodeClick(clickedNodeName);
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
                if (hoveredNodeName != null) {
                    // Leaving the panel must retract a hover-revealed label, otherwise
                    // it stays painted with the mouse nowhere near it.
                    hoveredNodeName = null;
                    repaint();
                } else {
                    repaintCoordinateOverlay();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouseWorldX = toWorldX(e.getX());
                mouseWorldY = toWorldY(e.getY());
                mouseInPanel = true;

                // With labels hidden, hovering a node reveals its label. Only the
                // transition matters — an unchanged hover costs nothing beyond the
                // coordinate overlay repaint below.
                boolean hoverChanged = updateHoveredNode(e.getPoint());

                // Show rotation cursor when Ctrl is held and multiple nodes are selected.
                // Only touch the cursor on a state change — setCursor per event is wasteful.
                boolean isCtrlDown = e.isControlDown() || e.isMetaDown();
                Cursor desiredCursor = (isCtrlDown && interactionManager != null
                        && interactionManager.canStartRotation())
                    ? rotateCursor()
                    : Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
                if (getCursor() != desiredCursor) {
                    setCursor(desiredCursor);
                }

                if (hoverChanged) {
                    // A label appeared or disappeared — the overlay clip won't cover it.
                    repaint();
                } else {
                    // Idle mouse movement only changes the coordinate overlay — repaint
                    // just that region rather than the whole panel.
                    repaintCoordinateOverlay();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // Update hover coordinates during drag too
                mouseWorldX = toWorldX(e.getX());
                mouseWorldY = toWorldY(e.getY());
                mouseInPanel = true;

                // Check if we should start node dragging. A drag only starts once the
                // mouse has moved past the click tolerance, so small jitters while
                // clicking never displace the node.
                if (interactionManager != null && !interactionManager.isDragging() &&
                    clickedNodeName != null && clickStartPoint != null &&
                    e.getPoint().distance(clickStartPoint) >= UIConstants.Map.DRAG_START_THRESHOLD_PX &&
                    interactionManager.canStartDrag(clickStartPoint)) {
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
     * Repaints only the bottom-left zoom/coordinate overlay region. The overlay sits
     * at a fixed position, so one clip rectangle covers both the old and new text.
     */
    private void repaintCoordinateOverlay() {
        final int overlayWidth = 320;
        final int overlayHeight = 50;
        repaint(0, Math.max(0, getHeight() - overlayHeight), overlayWidth, overlayHeight);
    }
    
    /**
     * Handles mouse wheel zoom events.
     * Zooms in/out while keeping the point under the cursor fixed.
     */
    private void handleMouseWheelZoom(MouseWheelEvent e) {
        zoomAt(e.getX(), e.getY(), Math.pow(ZOOM_FACTOR, -e.getWheelRotation()));
    }

    /**
     * Multiplies the zoom level by the given factor (clamped to sane bounds),
     * keeping the world point under the given screen anchor fixed.
     *
     * @param anchorScreenX Anchor X in screen coordinates
     * @param anchorScreenY Anchor Y in screen coordinates
     * @param factor Zoom multiplier (&gt;1 zooms in, &lt;1 zooms out)
     */
    private void zoomAt(double anchorScreenX, double anchorScreenY, double factor) {
        double newZoom = clampZoom(zoomLevel * factor);

        // Convert anchor position to world coordinates before zoom
        double worldX = toWorldX(anchorScreenX);
        double worldY = toWorldY(anchorScreenY);

        zoomLevel = newZoom;

        // Adjust pan so the world point under the anchor stays at the same screen position
        panX = anchorScreenX - worldX * zoomLevel;
        panY = anchorScreenY - worldY * zoomLevel;

        repaint();
    }

    private static double clampZoom(double zoom) {
        return Math.max(UIConstants.Zoom.MIN_ZOOM, Math.min(UIConstants.Zoom.MAX_ZOOM, zoom));
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
                             mouseWorldX, mouseWorldY, mouseInPanel, showLabels, hoveredNodeName);

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
     * Updates the panel colors based on the current UI theme.
     * This method should be called when the theme changes.
     * Now supports enhanced unified theme integration.
     */
    public void updateThemeColors() {
        // Custom MapPanel background color from the active theme
        // (themes define MapPanel.background in resources/themes/*.properties)
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

    /**
     * Sets whether node labels should be shown on the map. With labels off, the label
     * of the node under the mouse is still revealed on hover, so a node can always be
     * identified without turning them back on.
     *
     * @param showLabels true to show all node labels, false to show them on hover only
     */
    public void setShowLabels(boolean showLabels) {
        this.showLabels = showLabels;
        // Turning labels on makes any pending hover reveal meaningless; turning them
        // off must not inherit a hover computed while they were on (none was tracked).
        this.hoveredNodeName = null;
        repaint();
    }

    /**
     * Gets whether node labels are currently shown on the map.
     * @return true if labels are shown, false if they appear on hover only
     */
    public boolean isShowLabels() {
        return showLabels;
    }

    /**
     * Recomputes which node's label is revealed by hover, returning whether it changed.
     *
     * <p>With labels visible there is nothing to reveal, so no hit-test is performed —
     * this is called from {@code mouseMoved}, which fires continuously, and
     * {@link #getNodeAtPoint} scans every node.</p>
     *
     * @param screenPoint current mouse position
     * @return true if the revealed node changed and the map needs repainting
     */
    private boolean updateHoveredNode(Point screenPoint) {
        String hovered = showLabels ? null : getNodeAtPoint(screenPoint);
        if (java.util.Objects.equals(hovered, hoveredNodeName)) {
            return false;
        }
        hoveredNodeName = hovered;
        return true;
    }
    
    // View-menu zoom operations anchor at the viewport centre so the content
    // in view stays in view (zooming about the world origin walked it off-screen).

    public void zoomIn() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, ZOOM_FACTOR);
    }

    public void zoomOut() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.0 / ZOOM_FACTOR);
    }

    public void resetZoom() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.0 / zoomLevel);
    }
    
    public void zoomToFit() {
        if (model == null || model.getAllNodes().isEmpty()) {
            return; // No nodes to fit
        }

        // The fit depends on the panel's pixel size. If we have no size yet (panel not
        // laid out, e.g. an inactive tab), defer the fit until componentResized fires
        // with a real size rather than computing a degenerate zoom of 0.
        if (getWidth() <= 0 || getHeight() <= 0) {
            pendingZoomToFit = true;
            return;
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
        double newZoom = clampZoom(Math.min(scaleX, scaleY));

        // Calculate pan to center the content
        double newPanX = getWidth() / 2.0 - centerX * newZoom;
        double newPanY = getHeight() / 2.0 - centerY * newZoom;

        // Apply the new view settings
        zoomLevel = newZoom;
        panX = newPanX;
        panY = newPanY;

        // Fit completed against a real size; no longer pending. This also prevents the
        // componentResized hook from clobbering a user's manual zoom/pan on later resizes.
        pendingZoomToFit = false;

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
            double screenX = toScreenX(node.getX());
            double screenY = toScreenY(node.getY());

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
            double upstreamScreenX = toScreenX(upstreamNode.getX());
            double upstreamScreenY = toScreenY(upstreamNode.getY());
            double downstreamScreenX = toScreenX(downstreamNode.getX());
            double downstreamScreenY = toScreenY(downstreamNode.getY());

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

    // View transform
    //
    // The map has exactly one transform: screen = world * zoom + pan. It is defined
    // here and nowhere else — collaborators convert through these methods rather than
    // reading zoom/pan and re-deriving the arithmetic, which is how eight subtly
    // independent copies of it accumulated previously.
    //
    // Scalar rather than Point2D-returning: hit testing and rectangle selection call
    // these once per node per gesture, and per performance §"no allocation in the
    // inner loop" there is no reason to allocate to move two doubles.

    /** Converts a screen X coordinate to world space. */
    public double toWorldX(double screenX) {
        return (screenX - panX) / zoomLevel;
    }

    /** Converts a screen Y coordinate to world space. */
    public double toWorldY(double screenY) {
        return (screenY - panY) / zoomLevel;
    }

    /** Converts a world X coordinate to screen space. */
    public double toScreenX(double worldX) {
        return worldX * zoomLevel + panX;
    }

    /** Converts a world Y coordinate to screen space. */
    public double toScreenY(double worldY) {
        return worldY * zoomLevel + panY;
    }

    /** Converts a screen point to world space. */
    public java.awt.geom.Point2D.Double toWorld(Point screenPoint) {
        return new java.awt.geom.Point2D.Double(toWorldX(screenPoint.x), toWorldY(screenPoint.y));
    }

    /**
     * Returns the world-space coordinate at the centre of the visible map viewport.
     */
    public java.awt.geom.Point2D.Double getCenterWorldPoint() {
        return new java.awt.geom.Point2D.Double(
            toWorldX(getWidth() / 2.0), toWorldY(getHeight() / 2.0));
    }

    /**
     * Shows the Find Node dialog for searching nodes on the map.
     */
    public void showFindNodeDialog() {
        if (mapSearchManager != null) {
            mapSearchManager.showFindDialog();
        }
    }

    // Keyboard bindings

    /**
     * Installs the map's keyboard shortcuts via InputMap/ActionMap using the
     * platform menu shortcut key (Ctrl on Windows/Linux, Cmd on macOS).
     */
    private void setupKeyBindings() {
        int menuMask = AppShortcut.menuMask();
        InputMap inputMap = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        // Find Node
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "map.find",
            this::showFindNodeDialog);

        // Undo
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), "map.undo", () -> {
            if (textEditor != null && textEditor.canUndo()) {
                textEditor.undo();
            }
        });

        // Redo: Ctrl+Y / Cmd+Y and the conventional Ctrl+Shift+Z / Cmd+Shift+Z
        Runnable redo = () -> {
            if (textEditor != null && textEditor.canRedo()) {
                textEditor.redo();
            }
        };
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask), "map.redo", redo);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | InputEvent.SHIFT_DOWN_MASK), "map.redo");

        // Cut
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_X, menuMask), "map.cut", () -> {
            if (clipboardManager != null && clipboardManager.canCutOrCopy()) {
                clipboardManager.cut();
                repaint();
            }
        });

        // Copy
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), "map.copy", () -> {
            if (clipboardManager != null && clipboardManager.canCutOrCopy()) {
                clipboardManager.copy();
            }
        });

        // Paste (at center of viewport)
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask), "map.paste", () -> {
            if (clipboardManager != null && clipboardManager.hasClipboardContent()) {
                double centerX = toWorldX(getWidth() / 2.0);
                double centerY = toWorldY(getHeight() / 2.0);
                clipboardManager.pasteAtMapLocation(centerX, centerY);
                repaint();
            }
        });

        // Delete selection
        Runnable delete = () -> {
            if (interactionManager != null && model != null &&
                (model.getSelectedNodeCount() > 0 || model.getSelectedLinkCount() > 0)) {
                interactionManager.deleteSelectedElements();
                repaint();
            }
        };
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "map.delete", delete);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "map.delete");

        // Rotation-cursor preview while the modifier key itself is held
        Runnable previewOn = () -> {
            if (interactionManager != null && interactionManager.canStartRotation()
                    && !interactionManager.isDragging()) {
                setCursor(rotateCursor());
            }
        };
        Runnable previewOff = () -> {
            if (interactionManager != null && !interactionManager.isDragging()) {
                setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        };
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK, false),
            "map.rotatePreviewOn", previewOn);
        bind(inputMap, KeyStroke.getKeyStroke(KeyEvent.VK_CONTROL, 0, true),
            "map.rotatePreviewOff", previewOff);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_META, InputEvent.META_DOWN_MASK, false), "map.rotatePreviewOn");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_META, 0, true), "map.rotatePreviewOff");
    }

    /** Registers a keystroke-to-action binding on this panel. */
    private void bind(InputMap inputMap, KeyStroke keyStroke, String actionName, Runnable action) {
        inputMap.put(keyStroke, actionName);
        getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
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
            double screenX = toScreenX(node.getX());
            double screenY = toScreenY(node.getY());

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
            double upstreamScreenX = toScreenX(upstreamNode.getX());
            double upstreamScreenY = toScreenY(upstreamNode.getY());
            double downstreamScreenX = toScreenX(downstreamNode.getX());
            double downstreamScreenY = toScreenY(downstreamNode.getY());

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