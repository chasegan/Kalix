package com.kalix.ide.model;

/**
 * Statistics about the hydrological model for performance monitoring.
 */
public class ModelStatistics {
    private final int nodeCount;
    private final int linkCount;
    private final int modifiedNodeCount;
    private final int modifiedLinkCount;
    private final long version;
    
    public ModelStatistics(int nodeCount, int linkCount, int modifiedNodeCount, 
                          int modifiedLinkCount, long version) {
        this.nodeCount = nodeCount;
        this.linkCount = linkCount;
        this.modifiedNodeCount = modifiedNodeCount;
        this.modifiedLinkCount = modifiedLinkCount;
        this.version = version;
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
    
    public long getVersion() {
        return version;
    }
    
    @Override
    public String toString() {
        return String.format("ModelStatistics{nodes=%d, links=%d, modifiedNodes=%d, modifiedLinks=%d, version=%d}",
            nodeCount, linkCount, modifiedNodeCount, modifiedLinkCount, version);
    }
}