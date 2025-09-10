package com.kalix.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;

public class MapPanel extends JPanel {
    private double zoomLevel = 1.0;
    private static final double ZOOM_FACTOR = 1.2;
    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 5.0;
    
    // Panning variables
    private double panX = 0.0;
    private double panY = 0.0;
    private Point lastPanPoint = null;
    private boolean isPanning = false;

    public MapPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(600, 600));
        setupMouseListeners();
    }
    
    private void setupMouseListeners() {
        MouseAdapter panningHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    lastPanPoint = e.getPoint();
                    isPanning = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isPanning = false;
                    lastPanPoint = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                if (isPanning && lastPanPoint != null) {
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
        
        // Draw placeholder content
        drawGrid(g2d);
        drawPlaceholderContent(g2d);
        
        g2d.setTransform(originalTransform);
        
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

    public void clearModel() {
        repaint();
    }
}