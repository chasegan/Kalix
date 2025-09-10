package com.kalix.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.geom.AffineTransform;
import java.util.HashMap;
import java.util.Map;
import com.kalix.gui.model.HydrologicalModel;
import com.kalix.gui.model.ModelNode;
import com.kalix.gui.model.ModelChangeListener;
import com.kalix.gui.interaction.MapInteractionManager;
import com.kalix.gui.interaction.TextCoordinateUpdater;
import com.kalix.gui.editor.EnhancedTextEditor;

public class MapPanel extends JPanel implements KeyListener {
    private double zoomLevel = 1.0;
    private static final double ZOOM_FACTOR = 1.2;
    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 5.0;
    
    // Panning variables
    private double panX = 0.0;
    private double panY = 0.0;
    private Point lastPanPoint = null;
    private boolean isPanning = false;
    
    // Node rendering constants
    private static final int NODE_SIZE = 20; // Constant screen size in pixels
    private static final String[] COLOR_PALETTE = {
        "F94144", "F3722C", "F8961E", "F9844A", "F9C74F", 
        "90BE6D", "43AA8B", "4D908E", "577590", "277DA1"
    };
    
    // Model integration
    private HydrologicalModel model = null;
    private final Map<String, Color> nodeTypeColors = new HashMap<>();
    private int nextColorIndex = 0;
    
    // Interaction management
    private MapInteractionManager interactionManager;

    public MapPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(600, 600));
        
        // Enable keyboard focus for delete key handling
        setFocusable(true);
        addKeyListener(this);
        
        setupMouseListeners();
    }
    
    private void setupMouseListeners() {
        MouseAdapter panningHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    // Request focus for keyboard events (delete key)
                    requestFocusInWindow();
                    
                    // Check if clicking on a node first
                    String nodeAtPoint = getNodeAtPoint(e.getPoint());
                    
                    if (nodeAtPoint != null) {
                        // Check if clicking on an already selected node
                        boolean nodeWasSelected = model.isNodeSelected(nodeAtPoint);
                        
                        if (nodeWasSelected && !e.isShiftDown()) {
                            // Clicking on already selected node without Shift - preserve selection and start drag
                            if (interactionManager != null && interactionManager.canStartDrag(e.getPoint())) {
                                interactionManager.startDrag(e.getPoint());
                            }
                        } else {
                            // Clicking on unselected node, or Shift+clicking - handle selection normally
                            handleNodeSelection(nodeAtPoint, e.isShiftDown());
                            
                            // Check if we can start dragging after selection change
                            if (interactionManager != null && interactionManager.canStartDrag(e.getPoint())) {
                                interactionManager.startDrag(e.getPoint());
                            }
                        }
                    } else {
                        // Not clicking on a node - clear selection and start panning
                        if (model != null) {
                            model.clearSelection();
                        }
                        lastPanPoint = e.getPoint();
                        isPanning = true;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
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
                    
                    isPanning = false;
                    lastPanPoint = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                // Handle node dragging first
                if (interactionManager != null && interactionManager.isDragging()) {
                    interactionManager.updateDrag(e.getPoint());
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
        double oldZoom = zoomLevel;
        double zoomChange = Math.pow(ZOOM_FACTOR, -e.getWheelRotation());
        double newZoom = oldZoom * zoomChange;
        
        // Clamp zoom to valid range
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        
        // Only update if zoom actually changed
        if (newZoom != oldZoom) {
            zoomLevel = newZoom;
            
            // Adjust pan so the world point under the mouse stays at the same screen position
            panX = mouseX - worldX * zoomLevel;
            panY = mouseY - worldY * zoomLevel;
            
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        
        // Enable antialiasing for smoother graphics
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Apply pan and zoom transformations
        AffineTransform originalTransform = g2d.getTransform();
        g2d.translate(panX, panY);
        g2d.scale(zoomLevel, zoomLevel);
        
        // Draw grid and model content
        drawGrid(g2d);
        drawPlaceholderContent(g2d);
        
        g2d.setTransform(originalTransform);
        
        // Draw nodes in screen space (constant size)
        drawNodes(g2d);
        
        // Draw zoom level and pan indicators
        g2d.setColor(Color.GRAY);
        g2d.drawString(String.format("Zoom: %.1f%%", zoomLevel * 100), 10, getHeight() - 25);
        g2d.drawString(String.format("Pan: (%.0f, %.0f)", panX, panY), 10, getHeight() - 10);
        
        g2d.dispose();
    }

    private void drawGrid(Graphics2D g2d) {
        g2d.setColor(new Color(240, 240, 240));
        g2d.setStroke(new BasicStroke(1));
        
        int gridSize = 50;
        
        // Calculate the visible world bounds (accounting for pan and zoom transforms)
        int viewWidth = (int) (getWidth() / zoomLevel);
        int viewHeight = (int) (getHeight() / zoomLevel);
        int worldLeft = (int) (-panX / zoomLevel);
        int worldTop = (int) (-panY / zoomLevel);
        int worldRight = worldLeft + viewWidth;
        int worldBottom = worldTop + viewHeight;
        
        // Draw vertical lines - aligned to world grid
        int startX = (worldLeft / gridSize) * gridSize;  // Snap to grid
        for (int x = startX; x <= worldRight + gridSize; x += gridSize) {
            g2d.drawLine(x, worldTop - gridSize, x, worldBottom + gridSize);
        }
        
        // Draw horizontal lines - aligned to world grid  
        int startY = (worldTop / gridSize) * gridSize;  // Snap to grid
        for (int y = startY; y <= worldBottom + gridSize; y += gridSize) {
            g2d.drawLine(worldLeft - gridSize, y, worldRight + gridSize, y);
        }
        
    }

    private void drawPlaceholderContent(Graphics2D g2d) {
        // Placeholder method for future model content
    }
    
    private void drawNodes(Graphics2D g2d) {
        if (model == null) return;
        
        // Save the current transform
        AffineTransform originalTransform = g2d.getTransform();
        
        // Draw each node
        for (ModelNode node : model.getAllNodes()) {
            // Get or assign color for this node type
            Color nodeColor = getColorForNodeType(node.getType());
            g2d.setColor(nodeColor);
            
            // Transform node coordinates to screen space
            double screenX = node.getX();
            double screenY = node.getY();
            
            // Reset transform to screen space for constant size rendering
            g2d.setTransform(originalTransform);
            
            // Convert world coordinates to screen coordinates
            double transformedX = screenX * zoomLevel + panX;
            double transformedY = screenY * zoomLevel + panY;
            
            // Draw node as filled circle with constant screen size
            int nodeRadius = NODE_SIZE / 2;
            g2d.fillOval((int)(transformedX - nodeRadius), (int)(transformedY - nodeRadius), 
                        NODE_SIZE, NODE_SIZE);
            
            // Draw node border - blue for selected nodes, black for unselected
            boolean isSelected = model.isNodeSelected(node.getName());
            g2d.setColor(isSelected ? Color.BLUE : Color.BLACK);
            g2d.setStroke(isSelected ? new BasicStroke(3.0f) : new BasicStroke(1.0f));
            g2d.drawOval((int)(transformedX - nodeRadius), (int)(transformedY - nodeRadius), 
                        NODE_SIZE, NODE_SIZE);
            
            // Reset stroke for next node
            g2d.setStroke(new BasicStroke(1.0f));
        }
        
        // Restore the original transform
        g2d.setTransform(originalTransform);
    }
    
    private Color getColorForNodeType(String nodeType) {
        return nodeTypeColors.computeIfAbsent(nodeType, type -> {
            String hexColor = COLOR_PALETTE[nextColorIndex % COLOR_PALETTE.length];
            nextColorIndex++;
            return Color.decode("#" + hexColor);
        });
    }
    
    public void setModel(HydrologicalModel model) {
        // Remove listener from old model if it exists
        if (this.model != null) {
            // We'll add this when we implement the listener
        }
        
        this.model = model;
        
        // Initialize interaction manager
        if (this.model != null) {
            this.model.addChangeListener(event -> repaint());
            this.interactionManager = new MapInteractionManager(this, this.model);
        } else {
            this.interactionManager = null;
        }
        
        repaint();
    }

    public void zoomIn() {
        if (zoomLevel < MAX_ZOOM) {
            zoomLevel *= ZOOM_FACTOR;
            repaint();
        }
    }

    public void zoomOut() {
        if (zoomLevel > MIN_ZOOM) {
            zoomLevel /= ZOOM_FACTOR;
            repaint();
        }
    }

    public void resetZoom() {
        zoomLevel = 1.0;
        repaint();
    }
    
    public void resetPan() {
        panX = 0.0;
        panY = 0.0;
        repaint();
    }
    
    public void resetView() {
        zoomLevel = 1.0;
        panX = 0.0;
        panY = 0.0;
        repaint();
    }
    
    public void zoomToFit() {
        if (model == null || model.getAllNodes().isEmpty()) {
            return; // No nodes to fit
        }
        
        // Calculate bounding box of all nodes
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        
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
        
        // Clamp zoom to valid range
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        
        // Calculate pan to center the content
        double newPanX = getWidth() / 2.0 - centerX * newZoom;
        double newPanY = getHeight() / 2.0 - centerY * newZoom;
        
        // Apply the new view settings
        zoomLevel = newZoom;
        panX = newPanX;
        panY = newPanY;
        
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
        if (interactionManager != null && textEditor != null) {
            TextCoordinateUpdater textUpdater = new TextCoordinateUpdater(textEditor);
            interactionManager.setTextUpdater(textUpdater);
        }
    }
    
    // KeyListener implementation for delete key handling
    
    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
            if (interactionManager != null && model != null && model.getSelectedNodeCount() > 0) {
                interactionManager.deleteSelectedNodes();
                repaint();
            }
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) {
        // Not used but required by KeyListener interface
    }
    
    @Override
    public void keyTyped(KeyEvent e) {
        // Not used but required by KeyListener interface
    }
}