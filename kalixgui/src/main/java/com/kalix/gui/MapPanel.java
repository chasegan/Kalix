package com.kalix.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;

public class MapPanel extends JPanel {
    private double zoomLevel = 1.0;
    private static final double ZOOM_FACTOR = 1.2;
    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 5.0;

    public MapPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(600, 600));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        
        // Enable antialiasing for smoother graphics
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Apply zoom transformation
        AffineTransform originalTransform = g2d.getTransform();
        g2d.scale(zoomLevel, zoomLevel);
        
        // Draw placeholder content
        drawGrid(g2d);
        drawPlaceholderContent(g2d);
        
        g2d.setTransform(originalTransform);
        
        // Draw zoom level indicator
        g2d.setColor(Color.GRAY);
        g2d.drawString(String.format("Zoom: %.1f%%", zoomLevel * 100), 10, getHeight() - 10);
        
        g2d.dispose();
    }

    private void drawGrid(Graphics2D g2d) {
        g2d.setColor(new Color(240, 240, 240));
        g2d.setStroke(new BasicStroke(1));
        
        int width = (int) (getWidth() / zoomLevel);
        int height = (int) (getHeight() / zoomLevel);
        int gridSize = 50;
        
        // Draw vertical lines
        for (int x = 0; x < width; x += gridSize) {
            g2d.drawLine(x, 0, x, height);
        }
        
        // Draw horizontal lines
        for (int y = 0; y < height; y += gridSize) {
            g2d.drawLine(0, y, width, y);
        }
    }

    private void drawPlaceholderContent(Graphics2D g2d) {
        g2d.setColor(Color.LIGHT_GRAY);
        g2d.setFont(new Font("Arial", Font.PLAIN, 16));
        
        String message = "Kalix Model Map";
        FontMetrics fm = g2d.getFontMetrics();
        int x = (int) ((getWidth() / zoomLevel - fm.stringWidth(message)) / 2);
        int y = (int) ((getHeight() / zoomLevel) / 2);
        
        g2d.drawString(message, x, y);
        
        g2d.setFont(new Font("Arial", Font.PLAIN, 12));
        String subMessage = "Nodes and links will appear here";
        fm = g2d.getFontMetrics();
        x = (int) ((getWidth() / zoomLevel - fm.stringWidth(subMessage)) / 2);
        y += 25;
        
        g2d.drawString(subMessage, x, y);
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

    public void clearModel() {
        repaint();
    }
}