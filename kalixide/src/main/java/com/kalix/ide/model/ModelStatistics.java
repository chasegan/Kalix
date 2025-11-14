package com.kalix.ide.model;

/**
 * Statistics about the hydrological model for performance monitoring.
 */
public class ModelStatistics {
    private final int nodeCount;
    private final int linkCount;
    private final int modifiedNodeCount;
    private final int modifiedLinkCount;

    public ModelStatistics(int nodeCount, int linkCount, int modifiedNodeCount, 
                          int modifiedLinkCount) {
        this.nodeCount = nodeCount;
        this.linkCount = linkCount;
        this.modifiedNodeCount = modifiedNodeCount;
        this.modifiedLinkCount = modifiedLinkCount;
    }
    
    public int getNodeCount() {
        return nodeCount;
    }
    
    public int getLinkCount() {
        return linkCount;
    }
    
    public int getModifiedNodeCount() {
        return modifiedNodeCount;
    }
    
    public int getModifiedLinkCount() {
        return modifiedLinkCount;
    }
    
    @Override
    public String toString() {
        return String.format("ModelStatistics{nodes=%d, links=%d, modifiedNodes=%d, modifiedLinks=%d, version=%d}",
            nodeCount, linkCount, modifiedNodeCount, modifiedLinkCount);
    }
}