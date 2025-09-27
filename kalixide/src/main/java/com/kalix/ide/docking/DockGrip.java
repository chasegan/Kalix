package com.kalix.ide.docking;

import com.kalix.ide.constants.UIConstants;

import java.awt.*;
import java.awt.geom.Ellipse2D;

/**
 * Visual grip component for dockable panels.
 * 
 * Displays as a small rectangle with a dot pattern that appears in the top-left
 * corner of dockable panels when docking mode is activated. The grip provides
 * a clear visual indicator for users to click and drag.
 */
public class DockGrip {
    
    private boolean visible = false;
    private int x, y;
    private final int width;
    private final int height;
    
    /**
     * Creates a new DockGrip with default dimensions.
     */
    public DockGrip() {
        this.width = UIConstants.Docking.GRIP_WIDTH;
        this.height = UIConstants.Docking.GRIP_HEIGHT;
        this.x = UIConstants.Docking.GRIP_MARGIN;
        this.y = UIConstants.Docking.GRIP_MARGIN;
    }
    
    /**
     * Sets the position of the grip.
     * @param x The x coordinate
     * @param y The y coordinate
     */
    public void setLocation(int x, int y) {
        this.x = x;
        this.y = y;
    }
    
    /**
     * Sets the visibility of the grip.
     * @param visible true to show the grip, false to hide it
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Returns whether the grip is visible.
     * @return true if the grip is visible
     */
    public boolean isVisible() {
        return visible;
    }
    
    /**
     * Checks if the given point is within the grip bounds.
     * @param point The point to test
     * @return true if the point is within the grip
     */
    public boolean contains(Point point) {
        return point.x >= x && point.x <= x + width &&
               point.y >= y && point.y <= y + height;
    }
    
    /**
     * Paints the grip using the provided Graphics context.
     * @param g The Graphics context to paint on
     */
    public void paintGrip(Graphics g) {
        if (!visible) return;
        
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            // Enable anti-aliasing for smooth rendering
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Draw grip background with rounded corners
            g2d.setColor(UIConstants.Docking.GRIP_COLOR);
            g2d.fillRoundRect(x, y, width, height, 4, 4);
            
            // Draw subtle border
            g2d.setColor(UIConstants.Docking.GRIP_COLOR.darker());
            g2d.drawRoundRect(x, y, width, height, 4, 4);
            
            // Draw dot pattern
            paintGripDots(g2d);
            
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * Paints the dot pattern on the grip.
     * @param g2d The Graphics2D context
     */
    private void paintGripDots(Graphics2D g2d) {
        int dotSize = UIConstants.Docking.GRIP_DOT_SIZE;
        int spacing = UIConstants.Docking.GRIP_DOT_SPACING;
        
        // Calculate grid of dots that fit within the grip
        int startX = x + (width - getDotsWidth()) / 2;
        int startY = y + (height - getDotsHeight()) / 2;
        
        // Create dots in a 3x2 pattern
        int dotsPerRow = 3;
        int dotRows = 2;
        
        for (int row = 0; row < dotRows; row++) {
            for (int col = 0; col < dotsPerRow; col++) {
                int dotX = startX + col * (dotSize + spacing);
                int dotY = startY + row * (dotSize + spacing);
                
                // Paint dot with drop shadow effect
                paintDotWithShadow(g2d, dotX, dotY, dotSize);
            }
        }
    }
    
    /**
     * Paints a single dot with a drop shadow effect to make it look like a hole.
     * @param g2d The Graphics2D context
     * @param x The x coordinate of the dot
     * @param y The y coordinate of the dot
     * @param size The size of the dot
     */
    private void paintDotWithShadow(Graphics2D g2d, int x, int y, int size) {
        Ellipse2D dot = new Ellipse2D.Float(x, y, size, size);
        
        // Draw shadow first (offset by 1 pixel)
        g2d.setColor(UIConstants.Docking.GRIP_COLOR.darker().darker());
        Ellipse2D shadow = new Ellipse2D.Float(x + 1, y + 1, size, size);
        g2d.fill(shadow);
        
        // Draw main dot
        g2d.setColor(UIConstants.Docking.GRIP_DOT_COLOR);
        g2d.fill(dot);
        
        // Add highlight on top-left for 3D effect
        g2d.setColor(UIConstants.Docking.GRIP_DOT_COLOR.brighter());
        g2d.fillArc(x, y, size, size, 90, 90);
    }
    
    /**
     * Calculates the total width needed for the dot pattern.
     * @return The width in pixels
     */
    private int getDotsWidth() {
        int dotSize = UIConstants.Docking.GRIP_DOT_SIZE;
        int spacing = UIConstants.Docking.GRIP_DOT_SPACING;
        return 3 * dotSize + 2 * spacing; // 3 dots with 2 spaces
    }
    
    /**
     * Calculates the total height needed for the dot pattern.
     * @return The height in pixels
     */
    private int getDotsHeight() {
        int dotSize = UIConstants.Docking.GRIP_DOT_SIZE;
        int spacing = UIConstants.Docking.GRIP_DOT_SPACING;
        return 2 * dotSize + spacing; // 2 rows with 1 space
    }
    
    /**
     * Gets the grip bounds as a Rectangle.
     * @return The grip bounds
     */
    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }
}