package com.kalix.gui.model;

/**
 * Event fired when the hydrological model changes.
 */
public class ModelChangeEvent {
    
    public enum Type {
        NODE_ADDED,
        NODE_REMOVED,
        NODE_MODIFIED,
        LINK_ADDED,
        LINK_REMOVED,
        LINK_MODIFIED,
        MODEL_CLEARED,
        MODEL_RELOADED
    }
    
    private final Type type;
    private final String entityId;
    private final long timestamp;
    
    public ModelChangeEvent(Type type, String entityId) {
        this.type = type;
        this.entityId = entityId;
        this.timestamp = System.currentTimeMillis();
    }
    
    public Type getType() {
        return type;
    }
    
    public String getEntityId() {
        return entityId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format("ModelChangeEvent{type=%s, entityId='%s', timestamp=%d}", 
            type, entityId, timestamp);
    }
}