package com.kalix.ide.model;

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
        MODEL_RELOADED,
        NODE_SELECTED,
        NODE_DESELECTED,
        LINK_SELECTED,
        LINK_DESELECTED,
        SELECTION_CLEARED
    }
    
    private final Type type;
    private final String entityId;
    private final long timestamp;
    private final int affectedNodeCount;
    private final int affectedLinkCount;
    
    public ModelChangeEvent(Type type, String entityId) {
        this(type, entityId, 0, 0);
    }
    
    public ModelChangeEvent(Type type, String entityId, int affectedNodeCount, int affectedLinkCount) {
        this.type = type;
        this.entityId = entityId;
        this.affectedNodeCount = affectedNodeCount;
        this.affectedLinkCount = affectedLinkCount;
        this.timestamp = System.currentTimeMillis();
    }
    
    public Type getType() {
        return type;
    }
    
    public String getEntityId() {
        return entityId;
    }
    
    public int getAffectedNodeCount() {
        return affectedNodeCount;
    }
    
    public int getAffectedLinkCount() {
        return affectedLinkCount;
    }
    
    @Override
    public String toString() {
        return String.format("ModelChangeEvent{type=%s, entityId='%s', affectedNodes=%d, affectedLinks=%d, timestamp=%d}", 
            type, entityId, affectedNodeCount, affectedLinkCount, timestamp);
    }
}