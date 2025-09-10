package com.kalix.gui.schematic;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Manages viewport transformations for the schematic view.
 * Handles pan, zoom, and coordinate transformations between screen and world space.
 */
public class ViewportManager {
    
    // Viewport properties
    private float centerX = 0.0f;
    private float centerY = 0.0f;
    private float zoom = 1.0f;
    
    // Screen dimensions
    private int screenWidth = 800;
    private int screenHeight = 600;
    
    // Zoom constraints
    private static final float MIN_ZOOM = 0.01f;
    private static final float MAX_ZOOM = 100.0f;
    
    // Pan constraints (world units)
    private static final float MAX_PAN_DISTANCE = 1000000.0f;
    
    public ViewportManager() {
        // Default constructor
    }
    
    /**
     * Initialize viewport with screen dimensions
     */
    public void initialize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }
    
    /**
     * Update screen dimensions
     */
    public void resize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }
    
    /**
     * Pan the viewport by screen pixel amounts
     */
    public void pan(float deltaScreenX, float deltaScreenY) {
        // Convert screen deltas to world deltas
        float worldDeltaX = deltaScreenX / zoom;
        float worldDeltaY = -deltaScreenY / zoom; // Flip Y axis
        
        // Update center position with constraints
        centerX = Math.max(-MAX_PAN_DISTANCE, Math.min(MAX_PAN_DISTANCE, centerX + worldDeltaX));
        centerY = Math.max(-MAX_PAN_DISTANCE, Math.min(MAX_PAN_DISTANCE, centerY + worldDeltaY));
    }
    
    /**
     * Zoom at a specific screen point
     */
    public void zoomAtPoint(float screenX, float screenY, float zoomFactor) {
        // Convert screen point to world coordinates before zoom
        Point2D.Float worldPoint = screenToWorld(screenX, screenY);
        
        // Apply zoom with constraints
        float newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * zoomFactor));
        
        if (newZoom != zoom) {
            zoom = newZoom;
            
            // Adjust center to maintain the point under cursor
            Point2D.Float newWorldPoint = screenToWorld(screenX, screenY);
            centerX += worldPoint.x - newWorldPoint.x;
            centerY += worldPoint.y - newWorldPoint.y;
        }
    }
    
    /**
     * Zoom centered on viewport
     */
    public void zoomCentered(float zoomFactor) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * zoomFactor));
    }
    
    /**
     * Convert screen coordinates to world coordinates
     */
    public Point2D.Float screenToWorld(float screenX, float screenY) {
        float worldX = centerX + (screenX - screenWidth / 2.0f) / zoom;
        float worldY = centerY + (screenHeight / 2.0f - screenY) / zoom; // Flip Y axis
        return new Point2D.Float(worldX, worldY);
    }
    
    /**
     * Convert world coordinates to screen coordinates
     */
    public Point2D.Float worldToScreen(float worldX, float worldY) {
        float screenX = (worldX - centerX) * zoom + screenWidth / 2.0f;
        float screenY = screenHeight / 2.0f - (worldY - centerY) * zoom; // Flip Y axis
        return new Point2D.Float(screenX, screenY);
    }
    
    /**
     * Get the visible world bounds
     */
    public Rectangle2D.Float getVisibleBounds() {
        float halfWidth = screenWidth / (2.0f * zoom);
        float halfHeight = screenHeight / (2.0f * zoom);
        
        return new Rectangle2D.Float(
            centerX - halfWidth,
            centerY - halfHeight,
            2 * halfWidth,
            2 * halfHeight
        );
    }
    
    /**
     * Fit all nodes in the viewport with some padding
     */
    public void fitToNodes(List<SchematicNode> nodes) {
        if (nodes.isEmpty()) return;
        
        // Calculate bounding box of all nodes
        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        
        for (SchematicNode node : nodes) {
            Rectangle2D.Float bounds = node.getBounds();
            minX = Math.min(minX, bounds.x);
            maxX = Math.max(maxX, bounds.x + bounds.width);
            minY = Math.min(minY, bounds.y);
            maxY = Math.max(maxY, bounds.y + bounds.height);
        }
        
        // Add padding (10% of each dimension)
        float width = maxX - minX;
        float height = maxY - minY;
        float padding = Math.max(width, height) * 0.1f;
        
        minX -= padding;
        maxX += padding;
        minY -= padding;
        maxY += padding;
        
        // Update center
        centerX = (minX + maxX) / 2.0f;
        centerY = (minY + maxY) / 2.0f;
        
        // Calculate zoom to fit
        float requiredWidth = maxX - minX;
        float requiredHeight = maxY - minY;
        
        if (requiredWidth > 0 && requiredHeight > 0) {
            float zoomX = screenWidth / requiredWidth;
            float zoomY = screenHeight / requiredHeight;
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.min(zoomX, zoomY)));
        }
    }
    
    /**
     * Check if a world-space rectangle is visible
     */
    public boolean isVisible(Rectangle2D.Float worldBounds) {
        Rectangle2D.Float visibleBounds = getVisibleBounds();
        return visibleBounds.intersects(worldBounds);
    }
    
    /**
     * Get the world-space size of one screen pixel
     */
    public float getPixelSize() {
        return 1.0f / zoom;
    }
    
    /**
     * Create view matrix for OpenGL
     */
    public float[] getViewMatrix() {
        // Create a simple 2D view matrix
        // [zoom   0     0  -centerX*zoom]
        // [0     zoom   0  -centerY*zoom]
        // [0      0     1      0        ]
        // [0      0     0      1        ]
        
        return new float[] {
            zoom, 0.0f, 0.0f, -centerX * zoom,
            0.0f, zoom, 0.0f, -centerY * zoom,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }
    
    /**
     * Create projection matrix for OpenGL (orthographic)
     */
    public float[] getProjectionMatrix() {
        float left = -screenWidth / 2.0f;
        float right = screenWidth / 2.0f;
        float bottom = -screenHeight / 2.0f;
        float top = screenHeight / 2.0f;
        float near = -1.0f;
        float far = 1.0f;
        
        // Orthographic projection matrix
        return new float[] {
            2.0f / (right - left), 0.0f, 0.0f, -(right + left) / (right - left),
            0.0f, 2.0f / (top - bottom), 0.0f, -(top + bottom) / (top - bottom),
            0.0f, 0.0f, -2.0f / (far - near), -(far + near) / (far - near),
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }
    
    // Getters
    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public float getZoom() { return zoom; }
    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    
    // Setters with constraints
    public void setCenterX(float centerX) {
        this.centerX = Math.max(-MAX_PAN_DISTANCE, Math.min(MAX_PAN_DISTANCE, centerX));
    }
    
    public void setCenterY(float centerY) {
        this.centerY = Math.max(-MAX_PAN_DISTANCE, Math.min(MAX_PAN_DISTANCE, centerY));
    }
    
    public void setZoom(float zoom) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }
    
    public void setCenter(float x, float y) {
        setCenterX(x);
        setCenterY(y);
    }
}