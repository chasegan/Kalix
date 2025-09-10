package com.kalix.gui.schematic;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a connection link between nodes in the hydrological model schematic.
 * Links carry flow from one node to another and can have waypoints for custom routing.
 */
public class SchematicLink {
    
    // Unique identifier
    private String id;
    
    // Connection endpoints
    private String fromNodeId;
    private String toNodeId;
    
    // Visual routing (optional waypoints for custom link paths)
    private List<Point2D.Float> waypoints;
    
    // Visual properties
    private LinkType type;
    private Color color;
    private float width = 2.0f;
    private boolean selected = false;
    private boolean highlighted = false;
    
    // Flow properties
    private String flowVariable;    // What type of flow (discharge, volume, etc.)
    private double currentFlow = 0.0;
    private double maxFlow = Double.MAX_VALUE;
    
    public SchematicLink(String id, String fromNodeId, String toNodeId) {
        this.id = id;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.waypoints = new ArrayList<>();
        this.type = LinkType.FLOW;
        this.color = getDefaultColorForType(type);
        this.flowVariable = "discharge";
    }
    
    /**
     * Get default color for each link type
     */
    private Color getDefaultColorForType(LinkType type) {
        return switch (type) {
            case FLOW -> new Color(30, 144, 255);      // Dodger blue
            case CONTROL -> new Color(255, 69, 0);     // Red orange
            case DATA -> new Color(50, 205, 50);       // Lime green
            case FEEDBACK -> new Color(138, 43, 226);  // Blue violet
        };
    }
    
    // Getters and setters
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getFromNodeId() { return fromNodeId; }
    public void setFromNodeId(String fromNodeId) { this.fromNodeId = fromNodeId; }
    
    public String getToNodeId() { return toNodeId; }
    public void setToNodeId(String toNodeId) { this.toNodeId = toNodeId; }
    
    public List<Point2D.Float> getWaypoints() { return waypoints; }
    public void setWaypoints(List<Point2D.Float> waypoints) { this.waypoints = waypoints; }
    
    public void addWaypoint(Point2D.Float point) { waypoints.add(point); }
    public void addWaypoint(float x, float y) { waypoints.add(new Point2D.Float(x, y)); }
    
    public void clearWaypoints() { waypoints.clear(); }
    
    public LinkType getType() { return type; }
    public void setType(LinkType type) { 
        this.type = type;
        if (this.color.equals(getDefaultColorForType(this.type))) {
            this.color = getDefaultColorForType(type);
        }
    }
    
    public Color getColor() { return color; }
    public void setColor(Color color) { this.color = color; }
    
    public float getWidth() { return width; }
    public void setWidth(float width) { this.width = width; }
    
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    
    public boolean isHighlighted() { return highlighted; }
    public void setHighlighted(boolean highlighted) { this.highlighted = highlighted; }
    
    public String getFlowVariable() { return flowVariable; }
    public void setFlowVariable(String flowVariable) { this.flowVariable = flowVariable; }
    
    public double getCurrentFlow() { return currentFlow; }
    public void setCurrentFlow(double currentFlow) { this.currentFlow = currentFlow; }
    
    public double getMaxFlow() { return maxFlow; }
    public void setMaxFlow(double maxFlow) { this.maxFlow = maxFlow; }
    
    /**
     * Get the type ID for GPU rendering
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
            // Highlight selected links
            components[0] = Math.min(1.0f, color.getRed() / 255.0f + 0.3f);
            components[1] = Math.min(1.0f, color.getGreen() / 255.0f + 0.2f);
            components[2] = color.getBlue() / 255.0f;
            components[3] = 1.0f;
        } else if (highlighted) {
            // Highlight hovered links
            components[0] = Math.min(1.0f, color.getRed() / 255.0f * 1.3f);
            components[1] = Math.min(1.0f, color.getGreen() / 255.0f * 1.3f);
            components[2] = Math.min(1.0f, color.getBlue() / 255.0f * 1.3f);
            components[3] = 1.0f;
        } else {
            components[0] = color.getRed() / 255.0f;
            components[1] = color.getGreen() / 255.0f;
            components[2] = color.getBlue() / 255.0f;
            components[3] = 1.0f;
        }
        return components;
    }
    
    /**
     * Get effective line width for rendering (including selection/highlight)
     */
    public float getEffectiveWidth() {
        if (selected) {
            return width * 2.0f;
        } else if (highlighted) {
            return width * 1.5f;
        } else {
            return width;
        }
    }
    
    /**
     * Calculate total length of the link including waypoints
     */
    public float getLength(SchematicNode fromNode, SchematicNode toNode) {
        if (fromNode == null || toNode == null) return 0.0f;
        
        float totalLength = 0.0f;
        Point2D.Float prevPoint = fromNode.getCenter();
        
        // Add length through waypoints
        for (Point2D.Float waypoint : waypoints) {
            float dx = waypoint.x - prevPoint.x;
            float dy = waypoint.y - prevPoint.y;
            totalLength += Math.sqrt(dx * dx + dy * dy);
            prevPoint = waypoint;
        }
        
        // Add final segment to destination
        Point2D.Float toPoint = toNode.getCenter();
        float dx = toPoint.x - prevPoint.x;
        float dy = toPoint.y - prevPoint.y;
        totalLength += Math.sqrt(dx * dx + dy * dy);
        
        return totalLength;
    }
    
    /**
     * Check if a point is near this link (for mouse interaction)
     */
    public boolean isNearPoint(float px, float py, SchematicNode fromNode, SchematicNode toNode, float tolerance) {
        if (fromNode == null || toNode == null) return false;
        
        Point2D.Float prevPoint = fromNode.getCenter();
        
        // Check proximity to each segment
        for (Point2D.Float waypoint : waypoints) {
            if (distanceToLineSegment(px, py, prevPoint.x, prevPoint.y, waypoint.x, waypoint.y) <= tolerance) {
                return true;
            }
            prevPoint = waypoint;
        }
        
        // Check final segment
        Point2D.Float toPoint = toNode.getCenter();
        return distanceToLineSegment(px, py, prevPoint.x, prevPoint.y, toPoint.x, toPoint.y) <= tolerance;
    }
    
    /**
     * Calculate distance from point to line segment
     */
    private float distanceToLineSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float A = px - x1;
        float B = py - y1;
        float C = x2 - x1;
        float D = y2 - y1;
        
        float dot = A * C + B * D;
        float lenSq = C * C + D * D;
        
        if (lenSq == 0) {
            // Degenerate segment, return distance to point
            return (float) Math.sqrt(A * A + B * B);
        }
        
        float param = dot / lenSq;
        
        float xx, yy;
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }
        
        float dx = px - xx;
        float dy = py - yy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
    
    @Override
    public String toString() {
        return String.format("SchematicLink{id='%s', from='%s', to='%s', type=%s}", 
                           id, fromNodeId, toNodeId, type);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SchematicLink that = (SchematicLink) obj;
        return id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
}