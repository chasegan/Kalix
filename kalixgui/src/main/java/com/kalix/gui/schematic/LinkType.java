package com.kalix.gui.schematic;

/**
 * Enumeration of link types in hydrological model schematics.
 * Different link types represent different kinds of connections between nodes.
 */
public enum LinkType {
    
    /**
     * Flow links - carry water flow between nodes
     * Most common type representing hydrological flow paths
     */
    FLOW("Flow", "Carries water flow between hydrological components"),
    
    /**
     * Control links - carry control signals or rules
     * Used for reservoir operations, gates, pumps, etc.
     */
    CONTROL("Control", "Carries control signals for operational rules"),
    
    /**
     * Data links - carry information or data feeds
     * Used for parameter passing, data input, etc.
     */
    DATA("Data", "Carries data or parameter information"),
    
    /**
     * Feedback links - create feedback loops in the system
     * Used for iterative calculations or closed-loop controls
     */
    FEEDBACK("Feedback", "Creates feedback loops for iterative calculations");
    
    private final String displayName;
    private final String description;
    
    LinkType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Get link type from string (case-insensitive)
     */
    public static LinkType fromString(String str) {
        if (str == null) return FLOW;
        
        String normalized = str.toUpperCase().trim();
        
        try {
            return LinkType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Try matching display names
            for (LinkType type : values()) {
                if (type.displayName.equalsIgnoreCase(str)) {
                    return type;
                }
            }
            
            // Default fallback
            return FLOW;
        }
    }
    
    /**
     * Check if this link type carries physical flow
     */
    public boolean carriesFlow() {
        return this == FLOW;
    }
    
    /**
     * Check if this link type is directional
     */
    public boolean isDirectional() {
        return true; // All current link types are directional
    }
    
    /**
     * Get default line style for rendering
     */
    public LineStyle getDefaultLineStyle() {
        return switch (this) {
            case FLOW -> LineStyle.SOLID;
            case CONTROL -> LineStyle.DASHED;
            case DATA -> LineStyle.DOTTED;
            case FEEDBACK -> LineStyle.DASH_DOT;
        };
    }
    
    /**
     * Get default arrow style for rendering
     */
    public ArrowStyle getDefaultArrowStyle() {
        return switch (this) {
            case FLOW -> ArrowStyle.FILLED_TRIANGLE;
            case CONTROL -> ArrowStyle.OPEN_TRIANGLE;
            case DATA -> ArrowStyle.CIRCLE;
            case FEEDBACK -> ArrowStyle.DIAMOND;
        };
    }
    
    /**
     * Line styles for different link types
     */
    public enum LineStyle {
        SOLID,
        DASHED,
        DOTTED,
        DASH_DOT
    }
    
    /**
     * Arrow styles for link endpoints
     */
    public enum ArrowStyle {
        FILLED_TRIANGLE,
        OPEN_TRIANGLE,
        CIRCLE,
        DIAMOND,
        NONE
    }
}