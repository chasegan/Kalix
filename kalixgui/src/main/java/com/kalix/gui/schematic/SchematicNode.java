package com.kalix.gui.schematic;

import java.awt.Color;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents a node in the hydrological model schematic.
 * Contains position, visual properties, and hydrological parameters.
 */
public class SchematicNode {
    
    // Unique identifier
    private String id;
    
    // Spatial properties
    private float x;
    private float y;
    private float width = 40.0f;
    private float height = 30.0f;
    
    // Visual properties
    private NodeType type;
    private Color color;
    private boolean selected = false;
    private boolean highlighted = false;
    
    // Model properties
    private String label;
    private Map<String, Object> parameters;
    
    // Connection information
    private java.util.List<String> inputConnectionIds;
    private java.util.List<String> outputConnectionIds;
    
    public SchematicNode(String id, NodeType type, float x, float y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.label = id;
        this.parameters = new HashMap<>();
        this.inputConnectionIds = new java.util.ArrayList<>();
        this.outputConnectionIds = new java.util.ArrayList<>();
        this.color = getDefaultColorForType(type);
    }
    
    /**
     * Get default color for each node type
     */
    private Color getDefaultColorForType(NodeType type) {
        return switch (type) {
            case RAINFALL_RUNOFF -> new Color(135, 206, 235);  // Sky blue
            case STORAGE -> new Color(70, 130, 180);           // Steel blue
            case LOSS -> new Color(255, 165, 0);               // Orange
            case ROUTING -> new Color(144, 238, 144);          // Light green
            case CONFLUENCE -> new Color(255, 192, 203);       // Pink
            case OUTLET -> new Color(220, 20, 60);             // Crimson
            case USER_DEFINED -> new Color(169, 169, 169);     // Dark gray
        };
    }
    
    // Getters and setters
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public float getX() { return x; }
    public void setX(float x) { this.x = x; }
    
    public float getY() { return y; }
    public void setY(float y) { this.y = y; }
    
    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }
    
    public float getWidth() { return width; }
    public void setWidth(float width) { this.width = width; }
    
    public float getHeight() { return height; }
    public void setHeight(float height) { this.height = height; }
    
    public NodeType getType() { return type; }
    public void setType(NodeType type) { 
        this.type = type;
        if (this.color.equals(getDefaultColorForType(this.type))) {
            this.color = getDefaultColorForType(type);
        }
    }
    
    public Color getColor() { return color; }
    public void setColor(Color color) { this.color = color; }
    
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    
    public boolean isHighlighted() { return highlighted; }
    public void setHighlighted(boolean highlighted) { this.highlighted = highlighted; }
    
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    
    public Object getParameter(String key) { return parameters.get(key); }
    public void setParameter(String key, Object value) { parameters.put(key, value); }
    
    public java.util.List<String> getInputConnectionIds() { return inputConnectionIds; }
    public void setInputConnectionIds(java.util.List<String> inputConnectionIds) { 
        this.inputConnectionIds = inputConnectionIds; 
    }
    
    public java.util.List<String> getOutputConnectionIds() { return outputConnectionIds; }
    public void setOutputConnectionIds(java.util.List<String> outputConnectionIds) { 
        this.outputConnectionIds = outputConnectionIds; 
    }
    
    /**
     * Check if a point is within this node's bounds
     */
    public boolean contains(float px, float py) {
        return px >= x - width/2 && px <= x + width/2 &&
               py >= y - height/2 && py <= y + height/2;
    }
    
    /**
     * Get the bounds of this node
     */
    public java.awt.geom.Rectangle2D.Float getBounds() {
        return new java.awt.geom.Rectangle2D.Float(
            x - width/2, y - height/2, width, height
        );
    }
    
    /**
     * Get center point
     */
    public java.awt.geom.Point2D.Float getCenter() {
        return new java.awt.geom.Point2D.Float(x, y);
    }
    
    /**
     * Calculate distance to another node
     */
    public float distanceTo(SchematicNode other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Get type ID for GPU instancing
     */
    public int getTypeId() {
        return type.ordinal();
    }
    
    /**
     * Get color components for GPU rendering
     */
    public float[] getColorComponents() {
        float[] components = new float[4];
        if (selected) {
            // Highlight selected nodes with orange tint
            components[0] = Math.min(1.0f, color.getRed() / 255.0f + 0.2f);
            components[1] = Math.min(1.0f, color.getGreen() / 255.0f + 0.1f);
            components[2] = color.getBlue() / 255.0f;
            components[3] = 1.0f;
        } else if (highlighted) {
            // Highlight hovered nodes with brighter colors
            components[0] = Math.min(1.0f, color.getRed() / 255.0f * 1.2f);
            components[1] = Math.min(1.0f, color.getGreen() / 255.0f * 1.2f);
            components[2] = Math.min(1.0f, color.getBlue() / 255.0f * 1.2f);
            components[3] = 1.0f;
        } else {
            components[0] = color.getRed() / 255.0f;
            components[1] = color.getGreen() / 255.0f;
            components[2] = color.getBlue() / 255.0f;
            components[3] = 1.0f;
        }
        return components;
    }
    
    @Override
    public String toString() {
        return String.format("SchematicNode{id='%s', type=%s, pos=(%.1f,%.1f)}", 
                           id, type, x, y);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SchematicNode that = (SchematicNode) obj;
        return id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
}