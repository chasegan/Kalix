package com.kalix.ide.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple spatial index for fast node lookups by location.
 * Uses a grid-based approach for O(1) spatial queries.
 */
public class SpatialIndex {
    private final double gridSize;
    private final Map<String, List<ModelNode>> grid;
    
    public SpatialIndex(double gridSize) {
        this.gridSize = gridSize;
        this.grid = new HashMap<>();
    }
    
    /**
     * Add a node to the spatial index
     */
    public void addNode(ModelNode node) {
        String gridKey = getGridKey(node.getX(), node.getY());
        grid.computeIfAbsent(gridKey, k -> new ArrayList<>()).add(node);
    }
    
    /**
     * Remove a node from the spatial index
     */
    public void removeNode(ModelNode node) {
        String gridKey = getGridKey(node.getX(), node.getY());
        List<ModelNode> nodes = grid.get(gridKey);
        if (nodes != null) {
            nodes.remove(node);
            if (nodes.isEmpty()) {
                grid.remove(gridKey);
            }
        }
    }
    
    /**
     * Find nodes near a given point (within the grid cell)
     */
    public List<ModelNode> getNodesNear(double x, double y) {
        String gridKey = getGridKey(x, y);
        List<ModelNode> nodes = grid.get(gridKey);
        return nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
    }
    
    /**
     * Find nodes within a rectangular region
     */
    public List<ModelNode> getNodesInRegion(double minX, double minY, double maxX, double maxY) {
        List<ModelNode> result = new ArrayList<>();
        
        int minGridX = (int) Math.floor(minX / gridSize);
        int minGridY = (int) Math.floor(minY / gridSize);
        int maxGridX = (int) Math.floor(maxX / gridSize);
        int maxGridY = (int) Math.floor(maxY / gridSize);
        
        for (int gx = minGridX; gx <= maxGridX; gx++) {
            for (int gy = minGridY; gy <= maxGridY; gy++) {
                String gridKey = gx + "," + gy;
                List<ModelNode> nodes = grid.get(gridKey);
                if (nodes != null) {
                    for (ModelNode node : nodes) {
                        if (node.getX() >= minX && node.getX() <= maxX &&
                            node.getY() >= minY && node.getY() <= maxY) {
                            result.add(node);
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * Clear the spatial index
     */
    public void clear() {
        grid.clear();
    }
    
    /**
     * Get statistics about the spatial index
     */
    public IndexStatistics getStatistics() {
        int totalNodes = 0;
        int gridCells = grid.size();
        int maxNodesPerCell = 0;
        
        for (List<ModelNode> nodes : grid.values()) {
            totalNodes += nodes.size();
            maxNodesPerCell = Math.max(maxNodesPerCell, nodes.size());
        }
        
        return new IndexStatistics(totalNodes, gridCells, maxNodesPerCell);
    }
    
    private String getGridKey(double x, double y) {
        int gridX = (int) Math.floor(x / gridSize);
        int gridY = (int) Math.floor(y / gridSize);
        return gridX + "," + gridY;
    }
    
    /**
     * Statistics about the spatial index
     */
    public static class IndexStatistics {
        private final int totalNodes;
        private final int gridCells;
        private final int maxNodesPerCell;
        
        public IndexStatistics(int totalNodes, int gridCells, int maxNodesPerCell) {
            this.totalNodes = totalNodes;
            this.gridCells = gridCells;
            this.maxNodesPerCell = maxNodesPerCell;
        }
        
        public int getTotalNodes() { return totalNodes; }
        public int getGridCells() { return gridCells; }
        public int getMaxNodesPerCell() { return maxNodesPerCell; }
        
        @Override
        public String toString() {
            return String.format("IndexStats{nodes=%d, cells=%d, maxPerCell=%d}", 
                totalNodes, gridCells, maxNodesPerCell);
        }
    }
}