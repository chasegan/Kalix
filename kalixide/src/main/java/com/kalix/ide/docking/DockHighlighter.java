package com.kalix.ide.docking;

import com.kalix.ide.constants.UIConstants;

import java.awt.*;

/**
 * Handles the visual highlighting of dockable panels when docking mode is active.
 * 
 * Draws a translucent colored border around the panel to clearly indicate that
 * it is in docking mode and can be dragged and repositioned.
 */
public class DockHighlighter {
    
    private boolean visible = false;
    
    /**
     * Creates a new DockHighlighter.
     */
    public DockHighlighter() {
        // Default constructor
    }
    
    /**
     * Sets the visibility of the highlight.
     * @param visible true to show the highlight, false to hide it
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Returns whether the highlight is visible.
     * @return true if the highlight is visible
     */
    public boolean isVisible() {
        return visible;
    }
    
    /**
     * Paints the highlight border around the given dimensions.
     * @param g The Graphics context to paint on
     * @param size The size of the area to highlight
     */
    public void paintHighlight(Graphics g, Dimension size) {
        if (!visible) return;
        
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            // Enable anti-aliasing for smooth lines
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Set up the stroke for the border
            int borderWidth = UIConstants.Docking.HIGHLIGHT_BORDER_WIDTH;
            g2d.setStroke(new BasicStroke(borderWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            
            // Draw the highlight border
            g2d.setColor(UIConstants.Docking.HIGHLIGHT_COLOR);
            
            // Draw border inset by half the border width to ensure it's fully visible
            int inset = borderWidth / 2;
            g2d.drawRect(inset, inset, 
                        size.width - borderWidth, 
                        size.height - borderWidth);
            
            // Add a subtle inner glow effect
            paintInnerGlow(g2d, size, borderWidth);
            
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * Paints an inner glow effect for enhanced visual feedback.
     * @param g2d The Graphics2D context
     * @param size The size of the area to highlight
     * @param borderWidth The width of the main border
     */
    private void paintInnerGlow(Graphics2D g2d, Dimension size, int borderWidth) {
        // Create a more transparent version of the highlight color for the glow
        Color glowColor = new Color(
            UIConstants.Docking.HIGHLIGHT_COLOR.getRed(),
            UIConstants.Docking.HIGHLIGHT_COLOR.getGreen(),
            UIConstants.Docking.HIGHLIGHT_COLOR.getBlue(),
            30 // Very transparent
        );
        
        g2d.setColor(glowColor);
        g2d.setStroke(new BasicStroke(1.0f));
        
        // Draw multiple concentric rectangles for glow effect
        for (int i = 1; i <= 3; i++) {
            int inset = borderWidth + i;
            if (inset * 2 < size.width && inset * 2 < size.height) {
                g2d.drawRect(inset, inset, 
                           size.width - inset * 2, 
                           size.height - inset * 2);
            }
        }
    }
    
    /**
     * Paints a drop zone highlight at the specified bounds.
     * This is used to show where a panel can be dropped.
     * @param g The Graphics context
     * @param bounds The bounds of the drop zone
     */
    public static void paintDropZoneHighlight(Graphics g, Rectangle bounds) {
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Use a slightly different color for drop zones
            Color dropZoneColor = new Color(0, 200, 100, 80); // Green tint
            g2d.setColor(dropZoneColor);
            
            // Fill the drop zone area
            g2d.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            
            // Draw border
            g2d.setColor(dropZoneColor.darker());
            g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                         0, new float[]{5, 5}, 0)); // Dashed line
            g2d.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
            
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * Paints a drag preview of the panel being moved.
     * @param g The Graphics context
     * @param bounds The bounds where the preview should be drawn
     * @param panelSnapshot An optional snapshot of the panel being dragged
     */
    public static void paintDragPreview(Graphics g, Rectangle bounds, Image panelSnapshot) {
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Set composite for transparency
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            
            if (panelSnapshot != null) {
                // Draw the panel snapshot
                g2d.drawImage(panelSnapshot, bounds.x, bounds.y, bounds.width, bounds.height, null);
            } else {
                // Fall back to a simple colored rectangle
                g2d.setColor(UIConstants.Docking.HIGHLIGHT_COLOR);
                g2d.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            
            // Draw border
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            g2d.setColor(UIConstants.Docking.HIGHLIGHT_COLOR.darker());
            g2d.setStroke(new BasicStroke(2));
            g2d.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
            
        } finally {
            g2d.dispose();
        }
    }
}