package com.kalix.gui.schematic;

/**
 * Enumeration of hydrological node types supported in Kalix models.
 * Each type represents a different component in the hydrological system.
 */
public enum NodeType {
    
    /**
     * Rainfall-runoff model nodes (Sacramento, GR4J, etc.)
     * Transform precipitation into runoff
     */
    RAINFALL_RUNOFF("Rainfall-Runoff", "Transforms precipitation into surface runoff"),
    
    /**
     * Storage nodes (reservoirs, lakes, aquifers)
     * Store and release water over time
     */
    STORAGE("Storage", "Stores and releases water (reservoirs, lakes, aquifers)"),
    
    /**
     * Loss nodes (evaporation, infiltration)
     * Remove water from the system
     */
    LOSS("Loss", "Removes water through evaporation, infiltration, or other losses"),
    
    /**
     * Routing nodes (channel routing, flow delays)
     * Route water through the network with travel time
     */
    ROUTING("Routing", "Routes water through channels with travel time delays"),
    
    /**
     * Confluence nodes (flow junctions)
     * Combine multiple input flows into a single output
     */
    CONFLUENCE("Confluence", "Combines multiple input flows into single output"),
    
    /**
     * Outlet nodes (model boundaries)
     * Define system outlets and boundaries
     */
    OUTLET("Outlet", "System outlet or boundary condition"),
    
    /**
     * User-defined nodes (custom components)
     * Allow for custom hydrological components
     */
    USER_DEFINED("User Defined", "Custom user-defined hydrological component");
    
    private final String displayName;
    private final String description;
    
    NodeType(String displayName, String description) {
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
     * Get node type from string (case-insensitive)
     */
    public static NodeType fromString(String str) {
        if (str == null) return USER_DEFINED;
        
        String normalized = str.toUpperCase().replace(" ", "_").replace("-", "_");
        
        try {
            return NodeType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Try matching display names
            for (NodeType type : values()) {
                if (type.displayName.equalsIgnoreCase(str)) {
                    return type;
                }
            }
            
            // Default fallback
            return USER_DEFINED;
        }
    }
    
    /**
     * Check if this node type can accept input connections
     */
    public boolean canAcceptInputs() {
        return this != RAINFALL_RUNOFF; // Most nodes accept inputs except rainfall-runoff
    }
    
    /**
     * Check if this node type can produce output connections
     */
    public boolean canProduceOutputs() {
        return this != OUTLET && this != LOSS; // Most nodes produce outputs except outlets and losses
    }
    
    /**
     * Get maximum number of input connections (-1 for unlimited)
     */
    public int getMaxInputs() {
        return switch (this) {
            case RAINFALL_RUNOFF -> 0;  // No inputs - driven by precipitation data
            case STORAGE, ROUTING -> 1; // Single input flow
            case LOSS -> 1;             // Single input flow  
            case CONFLUENCE -> -1;      // Unlimited inputs
            case OUTLET -> 1;           // Single input flow
            case USER_DEFINED -> -1;    // Unlimited (user configurable)
        };
    }
    
    /**
     * Get maximum number of output connections (-1 for unlimited)
     */
    public int getMaxOutputs() {
        return switch (this) {
            case RAINFALL_RUNOFF -> -1; // Can feed multiple downstream nodes
            case STORAGE -> -1;         // Can feed multiple downstream nodes
            case ROUTING -> 1;          // Single output flow
            case LOSS -> 0;             // No outputs - water is lost
            case CONFLUENCE -> 1;       // Single combined output
            case OUTLET -> 0;           // No outputs - system boundary
            case USER_DEFINED -> -1;    // Unlimited (user configurable)
        };
    }
    
    /**
     * Get typical parameters for this node type
     */
    public String[] getTypicalParameters() {
        return switch (this) {
            case RAINFALL_RUNOFF -> new String[]{"model_type", "parameters", "initial_conditions"};
            case STORAGE -> new String[]{"capacity", "initial_volume", "release_rule"};
            case ROUTING -> new String[]{"travel_time", "attenuation", "method"};
            case LOSS -> new String[]{"loss_rate", "loss_function", "coefficients"};
            case CONFLUENCE -> new String[]{"weighting", "lag_time"};
            case OUTLET -> new String[]{"boundary_condition", "rating_curve"};
            case USER_DEFINED -> new String[]{"custom_parameters"};
        };
    }
}